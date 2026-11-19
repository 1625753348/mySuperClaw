package com.example.enterpriseagent.toolgateway.registry;

import com.example.enterpriseagent.toolgateway.GatewayCaller;
import com.example.enterpriseagent.toolgateway.GatewayException;
import com.example.enterpriseagent.toolgateway.adapter.ToolAdapter;
import com.example.enterpriseagent.toolgateway.adapter.ToolAdapterFactory;
import com.example.enterpriseagent.toolgateway.discovery.ToolDiscovery;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.example.enterpriseagent.toolgateway.GatewayException.Code.*;
import static com.example.enterpriseagent.toolgateway.registry.GatewayRepository.*;

/** 控制面管理工具及不可变契约。每次发布和授权修改都锁定所属工具。 */
public final class ToolRegistry {

    private final GatewayRepository store;
    private final TransactionOperations transactions;
    private final ToolContracts contracts;
    private final ToolAdapterFactory adapters;
    private final ToolDiscovery discovery;
    private final ObjectMapper json;

    public ToolRegistry(GatewayRepository store, TransactionOperations transactions,
                        ToolContracts contracts, ToolAdapterFactory adapters, ToolDiscovery discovery, ObjectMapper json) {
        this.store = store;
        this.transactions = transactions;
        this.contracts = contracts;
        this.adapters = adapters;
        this.discovery = discovery;
        this.json = json;
    }

    public Tool register(GatewayCaller caller, Register request) {
        token(request.name(), "[A-Za-z0-9_-]{1,64}", "工具名称");
        text(request.description(), 2048, "工具说明");
        option(request.riskLevel(), Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL"), "风险等级");
        if (request.category() != null) {
            text(request.category(), 128, "工具分类");
        }
        return transactions.execute(ignored -> {
            UUID id = UUID.randomUUID();
            changed(store.insertTool(new ToolDraft(caller.tenantId(), id, request.name(),
                    request.description(), request.riskLevel(), request.category())), "工具名称已存在");
            audit(caller, id, null, "TOOL_REGISTERED", trace());
            return lock(caller, request.name());
        });
    }

    public Contract addVersion(GatewayCaller caller, String name, Draft draft) {
        token(draft.version(), "[A-Za-z0-9._-]{1,48}", "版本");
        String protocol = draft.protocol() == null ? "HTTP" : draft.protocol();
        ToolAdapter adapter = adapters.require(protocol);
        if (draft.targetRef() == null || !adapter.configuredForTenant(caller.tenantId(), draft.targetRef())
                || draft.timeoutMs() < 100 || draft.timeoutMs() > 30_000) {
            throw new GatewayException(INVALID, "工具目标未配置或超时参数超出范围");
        }
        String input = contracts.definition(draft.inputSchema());
        String output = draft.outputSchema() == null || draft.outputSchema().isNull()
                ? null : contracts.definition(draft.outputSchema());
        return transactions.execute(ignored -> {
            Tool tool = lock(caller, name);
            UUID id = UUID.randomUUID();
            changed(store.insertVersion(new VersionDraft(caller.tenantId(), id, tool.id(),
                    draft.version(), input, output, ToolContracts.digest(input + '\n' + output))), "版本已存在");
            UUID server = store.upsertServer(caller.tenantId(), UUID.randomUUID(), protocol);
            if (server == null) {
                throw new GatewayException(CONFLICT, "网关协议服务标识已被占用或停用");
            }
            store.insertEndpoint(new EndpointDraft(caller.tenantId(), UUID.randomUUID(), id, server,
                    draft.targetRef(), draft.timeoutMs(), protocol));
            audit(caller, tool.id(), null, "TOOL_VERSION_CREATED", trace());
            return describe(tool, store.version(caller.tenantId(), id));
        });
    }

    public Tool publish(GatewayCaller caller, String name, String versionName, long expectedRevision) {
        token(versionName, "[A-Za-z0-9._-]{1,48}", "版本");
        return transactions.execute(ignored -> {
            Tool tool = lock(caller, name);
            checkRevision(tool, expectedRevision);
            Version version = resolve(caller, tool, versionName, null);
            if (!Set.of("DRAFT", "PUBLISHED").contains(version.status())) {
                throw new GatewayException(CONFLICT, "当前版本状态不能发布");
            }
            endpoint(caller, version);
            if ("DRAFT".equals(version.status())) {
                changed(store.publishVersion(caller.tenantId(), version.id()), "版本状态已变化");
            }
            changed(store.activateTool(caller.tenantId(), tool.id(), version.id(), expectedRevision), "工具修订号已变化");
            String traceId = trace();
            audit(caller, tool.id(), null, "TOOL_VERSION_PUBLISHED", traceId);
            store.enqueueChange(new CatalogChange(caller.tenantId(), UUID.randomUUID(), tool.id(), version.id(),
                    expectedRevision + 1, "tool.version_published", traceId));
            return lock(caller, name);
        });
    }

    public Tool disable(GatewayCaller caller, String name, long expectedRevision) {
        return transactions.execute(ignored -> {
            Tool tool = lock(caller, name);
            checkRevision(tool, expectedRevision);
            changed(store.disableTool(caller.tenantId(), tool.id(), expectedRevision), "工具修订号已变化");
            String traceId = trace();
            audit(caller, tool.id(), null, "TOOL_DISABLED", traceId);
            store.enqueueChange(new CatalogChange(caller.tenantId(), UUID.randomUUID(), tool.id(), tool.currentVersionId(),
                    expectedRevision + 1, "tool.disabled", traceId));
            return lock(caller, name);
        });
    }

    public void setPermission(GatewayCaller caller, String name, Grant grant) {
        option(grant.subjectType(), Set.of("USER", "AGENT", "ROLE"), "权限主体类型");
        option(grant.action(), Set.of("DISCOVER", "EXECUTE"), "权限动作");
        option(grant.effect(), Set.of("ALLOW", "DENY"), "权限效果");
        text(grant.subjectId(), 128, "权限主体");
        transactions.executeWithoutResult(ignored -> {
            Tool tool = lock(caller, name);
            store.setPermission(new Permission(caller.tenantId(), tool.id(), grant.subjectType(),
                    grant.subjectId(), grant.action(), grant.effect()));
            audit(caller, tool.id(), null, "TOOL_PERMISSION_UPDATED", trace());
        });
    }

    public List<ToolDiscovery.Hit> search(GatewayCaller caller, String query, int limit) {
        return discovery.search(caller, query, limit);
    }

    public ToolDiscovery.Reindex reindex(GatewayCaller caller, String afterName, int limit) {
        return discovery.reindex(caller.tenantId(), afterName, limit);
    }

    public Contract schema(GatewayCaller caller, String name, String version) {
        return transactions.execute(ignored -> {
            Tool tool = active(lock(caller, name));
            requirePermission(caller, tool.id(), "DISCOVER");
            return describe(tool, published(resolve(caller, tool, version, null)));
        });
    }

    public Tool lock(GatewayCaller caller, String name) {
        Tool tool = store.lockTool(caller.tenantId(), name);
        if (tool == null) {
            throw new GatewayException(NOT_FOUND, "工具不存在");
        }
        return tool;
    }

    public static Tool active(Tool tool) {
        if (!"ACTIVE".equals(tool.status())) {
            throw new GatewayException(NOT_FOUND, "工具未启用");
        }
        return tool;
    }

    public Version resolve(GatewayCaller caller, Tool tool, String requested, UUID pinned) {
        UUID id = pinned != null ? pinned : requested == null
                ? tool.currentVersionId()
                : store.versionId(caller.tenantId(), tool.id(), requested);
        Version version = id == null ? null : store.version(caller.tenantId(), id);
        if (version == null || !tool.id().equals(version.toolId())) {
            throw new GatewayException(NOT_FOUND, "工具版本不存在");
        }
        return version;
    }

    public static Version published(Version version) {
        if (!"PUBLISHED".equals(version.status())) {
            throw new GatewayException(NOT_FOUND, "工具版本未发布");
        }
        return version;
    }

    public Endpoint endpoint(GatewayCaller caller, Version version) {
        Endpoint endpoint = store.enabledEndpoint(caller.tenantId(), version.id());
        if (endpoint == null || !adapters.configured(caller.tenantId(), endpoint.protocol(), endpoint.targetRef())
                || endpoint.timeoutMs() < 100 || endpoint.timeoutMs() > 30_000) {
            throw new GatewayException(CONFLICT, "工具没有可用的受控端点");
        }
        return endpoint;
    }

    public void requirePermission(GatewayCaller caller, UUID tool, String action) {
        if (!store.allowed(new Authorization(caller.tenantId(), tool, caller.principalId(),
                caller.agentId(), roles(caller), action))) {
            throw new GatewayException(FORBIDDEN, "缺少工具权限");
        }
    }

    public void audit(GatewayCaller caller, UUID toolId, UUID executionId, String action, String traceId) {
        store.audit(new Audit(caller.tenantId(), UUID.randomUUID(), executionId, toolId,
                caller.principalId(), action, traceId));
    }

    public static String trace() {
        return UUID.randomUUID().toString();
    }

    private String roles(GatewayCaller caller) {
        return json.valueToTree(caller.roles()).toString();
    }

    private Contract describe(Tool tool, Version version) {
        return new Contract(tool.name(), version.versionName(), contracts.decode(version.inputSchema()),
                version.outputSchema() == null ? null : contracts.decode(version.outputSchema()));
    }

    private static void checkRevision(Tool tool, long expectedRevision) {
        if (expectedRevision != tool.revision()) {
            throw new GatewayException(CONFLICT, "工具修订号已变化");
        }
    }

    private static void changed(int count, String error) {
        if (count != 1) {
            throw new GatewayException(CONFLICT, error);
        }
    }

    private static void option(String value, Set<String> options, String field) {
        if (value == null || !options.contains(value)) {
            throw new GatewayException(INVALID, field + "无效");
        }
    }

    private static void token(String value, String pattern, String field) {
        if (value == null || !value.matches(pattern)) {
            throw new GatewayException(INVALID, field + "格式无效");
        }
    }

    private static void text(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new GatewayException(INVALID, field + "为空或过长");
        }
    }

    public record Register(String name, String description, String riskLevel, String category) {}
    public record Draft(String version, String targetRef, int timeoutMs, JsonNode inputSchema,
                        JsonNode outputSchema, String protocol) {}
    public record Grant(String subjectType, String subjectId, String action, String effect) {}
    public record Contract(String name, String version, JsonNode inputSchema, JsonNode outputSchema) {}
}
