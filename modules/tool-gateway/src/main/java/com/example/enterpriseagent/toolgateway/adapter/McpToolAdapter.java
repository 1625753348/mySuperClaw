package com.example.enterpriseagent.toolgateway.adapter;

import com.example.enterpriseagent.toolgateway.GatewayCaller;
import com.example.enterpriseagent.toolgateway.GatewayException;
import com.example.enterpriseagent.toolgateway.registry.ToolContracts;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator.ValidationResponse;
import io.modelcontextprotocol.spec.McpSchema;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Streamable HTTP MCP。远端工具列表仅用于管理端审核，实际派发绑定已批准的名称。 */
public final class McpToolAdapter implements ToolAdapter {
    private final ObjectMapper json;
    private final ToolContracts contracts;
    private final Map<String, Server> servers;
    private final Map<String, Binding> tools;

    public McpToolAdapter(ObjectMapper json, ToolContracts contracts, Map<String, Server> servers, Map<String, Binding> tools) {
        this.json = json;
        this.contracts = contracts;
        this.servers = Map.copyOf(servers);
        this.tools = Map.copyOf(tools);
        this.servers.values().forEach(server -> {
            URI uri = server.uri();
            if (uri == null || !List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getFragment() != null || uri.getUserInfo() != null || uri.getRawQuery() != null) {
                throw new IllegalArgumentException("MCP 服务需要受控的 HTTP(S) 地址，不在 URL 中传递凭证");
            }
            if (server.allowedTenants() == null || server.allowedTenants().isEmpty()) {
                throw new IllegalArgumentException("MCP 服务必须配置允许的租户");
            }
        });
        this.tools.values().forEach(binding -> {
            if (binding.server() == null || !this.servers.containsKey(binding.server())
                    || binding.toolName() == null || binding.toolName().isBlank()) {
                throw new IllegalArgumentException("MCP 工具绑定需要已配置的服务和远端名称");
            }
        });
    }

    @Override public Protocol protocol() { return Protocol.MCP; }

    @Override public boolean configured(String targetRef) { return tools.containsKey(targetRef); }

    @Override
    public boolean configuredForTenant(String tenant, String targetRef) {
        Binding binding = tools.get(targetRef);
        return binding != null && permits(servers.get(binding.server()), tenant);
    }

    public Catalog catalog(GatewayCaller caller, String serverAlias, String cursor) {
        Server server = servers.get(serverAlias);
        if (server == null || !permits(server, caller.tenantId())) {
            throw new GatewayException(GatewayException.Code.NOT_FOUND, "MCP 服务未配置");
        }
        try (McpSyncClient client = connect(server, 5_000, null)) {
            client.initialize();
            var result = client.listTools(cursor);
            return new Catalog(result.tools().stream().map(tool -> new CatalogTool(
                    tool.name(), tool.description(), json.valueToTree(tool.inputSchema()),
                    json.valueToTree(tool.outputSchema()))).toList(), result.nextCursor());
        } catch (RuntimeException ex) {
            throw new GatewayException(GatewayException.Code.UPSTREAM, "MCP 服务目录暂不可获取");
        }
    }

    @Override
    public Outcome invoke(Call call) {
        Binding binding = tools.get(call.targetRef());
        if (binding == null || !permits(servers.get(binding.server()), call.caller().tenantId())) {
            return new Outcome("FAILED", null, "MCP_TOOL_NOT_CONFIGURED");
        }
        try (McpSyncClient client = connect(servers.get(binding.server()), Math.max(1, call.timeoutMs() / 2), call)) {
            client.initialize();
            Map<String, Object> arguments = json.readValue(call.arguments(), new TypeReference<>() {});
            var result = client.callTool(McpSchema.CallToolRequest.builder(binding.toolName()).arguments(arguments).build());
            if (Boolean.TRUE.equals(result.isError())) {
                return new Outcome("FAILED", null, "MCP_TOOL_ERROR");
            }
            Object output = result.structuredContent();
            if (output == null) {
                output = Map.of("content", result.content() == null ? List.of() : result.content());
            }
            return new Outcome("SUCCEEDED", json.writeValueAsString(output), null);
        } catch (JsonProcessingException ex) {
            return new Outcome("FAILED", null, "MCP_JSON_ERROR");
        } catch (RuntimeException ex) {
            return new Outcome("UNKNOWN", null, "MCP_TRANSPORT_ERROR");
        }
    }

    private McpSyncClient connect(Server server, int timeout, Call call) {
        Duration budget = Duration.ofMillis(timeout);
        URI uri = server.uri();
        String path = uri.getRawPath();
        var transport = HttpClientStreamableHttpTransport.builder(uri.resolve("/").toString())
                .endpoint(path.isBlank() || path.equals("/") ? "/mcp" : path)
                .customizeClient(builder -> builder.followRedirects(HttpClient.Redirect.NEVER))
                .connectTimeout(budget).maxResponseSize(262_144)
                .httpRequestCustomizer((request, method, address, body, context) -> {
                    if (server.bearerToken() != null && !server.bearerToken().isBlank()) {
                        request.header("Authorization", "Bearer " + server.bearerToken());
                    }
                    if (call != null) {
                        request.header("X-Trace-Id", call.traceId()).header("Idempotency-Key", call.operationKey());
                    }
                }).build();
        return McpClient.sync(transport).initializationTimeout(budget).requestTimeout(budget)
                .jsonSchemaValidator((schema, value) -> {
                    // SDK 与网关共用受控契约校验，避免加载另一代 NetworkNT 验证器。
                    try {
                        String definition = contracts.definition(json.valueToTree(schema));
                        return contracts.matches(definition, json.valueToTree(value))
                                ? ValidationResponse.asValid(json.writeValueAsString(value))
                                : ValidationResponse.asInvalid("MCP 数据不符合 Schema");
                    } catch (GatewayException | JsonProcessingException ex) {
                        return ValidationResponse.asInvalid("MCP Schema 或数据无效");
                    }
                })
                .enableCallToolSchemaCaching(false).build();
    }

    private static boolean permits(Server server, String tenant) {
        return server.allowedTenants().contains(tenant) || server.allowedTenants().contains("*");
    }

    public record Server(URI uri, String bearerToken, Set<String> allowedTenants) {}
    public record Binding(String server, String toolName) {}
    public record Catalog(List<CatalogTool> tools, String nextCursor) {}
    public record CatalogTool(String name, String description, JsonNode inputSchema, JsonNode outputSchema) {}
}
