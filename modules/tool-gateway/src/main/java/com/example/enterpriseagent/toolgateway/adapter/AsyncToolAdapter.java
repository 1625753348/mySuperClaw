package com.example.enterpriseagent.toolgateway.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** 固定协议的受管 HTTP 异步工具。POST 提交一次，之后按供应商任务号轮询。 */
public final class AsyncToolAdapter implements ToolAdapter {
    private final ObjectMapper json;
    private final Map<String, Binding> bindings;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public AsyncToolAdapter(ObjectMapper json, Map<String, Binding> bindings) {
        this.json = json;
        this.bindings = Map.copyOf(bindings);
    }

    @Override public Protocol protocol() { return Protocol.ASYNC; }
    @Override public Set<String> targetRefs() { return bindings.keySet(); }
    @Override public boolean configured(String ref) { return bindings.containsKey(ref); }
    @Override public boolean configuredForTenant(String tenant, String ref) {
        Binding binding = bindings.get(ref);
        return binding != null && binding.allowedTenants().contains(tenant);
    }

    @Override
    public Outcome invoke(Call call) {
        if (!configuredForTenant(call.caller().tenantId(), call.targetRef())) {
            return new Outcome("FAILED", null, "ASYNC_TARGET_NOT_CONFIGURED");
        }
        Binding binding = bindings.get(call.targetRef());
        var request = request(binding.submitUri(), call).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(call.arguments())).build();
        HttpResponse<byte[]> response = send(request, call.timeoutMs());
        if (response == null || response.statusCode() >= 500) return unknown();
        if (response.statusCode() != 202) {
            return response.statusCode() >= 400 ? new Outcome("FAILED", null, "ASYNC_REQUEST_REJECTED") : unknown();
        }
        String id = body(response).path("jobId").asText();
        return validJobId(id) ? new Outcome("WAITING_EXTERNAL", null, null, id) : unknown();
    }

    /** 轮询键来自已持久化的供应商回执，调用参数不能提供轮询地址。 */
    public Outcome poll(Call call, String id) {
        if (!configuredForTenant(call.caller().tenantId(), call.targetRef()) || !validJobId(id)) {
            return new Outcome("FAILED", null, "ASYNC_JOB_INVALID");
        }
        Binding binding = bindings.get(call.targetRef());
        var uri = URI.create(binding.statusUri() + "?jobId=" + id);
        HttpResponse<byte[]> response = send(request(uri, call).GET().build(), call.timeoutMs());
        if (response == null || response.statusCode() >= 500 || response.statusCode() == 429 || response.statusCode() == 404) {
            return new Outcome("WAITING_EXTERNAL", null, "ASYNC_STATUS_UNAVAILABLE");
        }
        if (response.statusCode() != 200) return new Outcome("UNKNOWN", null, "ASYNC_STATUS_REJECTED");
        JsonNode body = body(response);
        return switch (body.path("status").asText()) {
            case "QUEUED", "RUNNING", "PENDING" -> new Outcome("WAITING_EXTERNAL", null, null);
            case "SUCCEEDED" -> body.has("result") ? new Outcome("SUCCEEDED", body.get("result").toString(), null)
                    : new Outcome("WAITING_EXTERNAL", null, "ASYNC_STATUS_UNAVAILABLE");
            case "FAILED" -> new Outcome("FAILED", null, "ASYNC_PROVIDER_FAILED");
            default -> new Outcome("WAITING_EXTERNAL", null, "ASYNC_STATUS_UNAVAILABLE");
        };
    }

    private HttpRequest.Builder request(URI uri, Call call) {
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(call.timeoutMs()))
                .header("Accept", "application/json").header("Idempotency-Key", call.operationKey())
                .header("X-Trace-Id", call.traceId());
        if (call.credential() != null) builder.header("Authorization", "Bearer " + call.credential().bearerToken());
        return builder;
    }

    private HttpResponse<byte[]> send(HttpRequest request, int timeout) {
        var exchange = client.sendAsync(request, ignored -> HttpToolAdapter.boundedBody());
        try {
            return exchange.get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ex) {
            return null;
        } finally {
            if (!exchange.isDone()) exchange.cancel(true);
        }
    }

    private JsonNode body(HttpResponse<byte[]> response) {
        try {
            JsonNode value = json.readTree(response.body());
            if (value != null) return value;
        } catch (java.io.IOException ex) {
            // 未知格式按协议错误处理，避免将下游内容回传到错误或日志。
        }
        return json.createObjectNode();
    }

    private static boolean validJobId(String id) {
        return id != null && id.matches("[A-Za-z0-9._-]{1,128}");
    }

    private static boolean safe(URI uri) {
        return uri != null && List.of("http", "https").contains(uri.getScheme()) && uri.getHost() != null
                && uri.getRawQuery() == null && uri.getFragment() == null && uri.getUserInfo() == null;
    }

    private static int port(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : "https".equals(uri.getScheme()) ? 443 : 80;
    }

    private static Outcome unknown() { return new Outcome("UNKNOWN", null, "ASYNC_TRANSPORT_ERROR"); }

    public record Binding(URI submitUri, URI statusUri, Set<String> allowedTenants) {
        public Binding {
            if (!safe(submitUri) || !safe(statusUri) || !submitUri.getHost().equalsIgnoreCase(statusUri.getHost())
                    || !submitUri.getScheme().equals(statusUri.getScheme()) || port(submitUri) != port(statusUri)
                    || allowedTenants == null || allowedTenants.isEmpty()) {
                throw new IllegalArgumentException("异步工具需要同一源的受控提交与回查地址，以及租户范围");
            }
            allowedTenants = Set.copyOf(allowedTenants);
        }
    }
}
