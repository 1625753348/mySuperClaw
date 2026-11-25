package com.example.enterpriseagent.toolgateway.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** POST JSON 和显式注册的只读 GET 共用超时、响应限制及审计关联。 */
public final class HttpToolAdapter implements ToolAdapter {

    private static final int RESPONSE_LIMIT = 262_144;
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final Map<String, URI> targets;
    private final Map<String, ReadTarget> readTargets;
    private final ObjectMapper json;

    public HttpToolAdapter(Map<String, URI> targets) {
        this(targets, Map.of(), new ObjectMapper());
    }

    public HttpToolAdapter(Map<String, URI> targets, Map<String, ReadTarget> readTargets, ObjectMapper json) {
        this.targets = Map.copyOf(targets);
        this.readTargets = Map.copyOf(readTargets);
        this.json = json;
        this.targets.values().forEach(HttpToolAdapter::validateTarget);
        if (readTargets.keySet().stream().anyMatch(targets::containsKey)) {
            throw new IllegalArgumentException("HTTP GET 与 POST 目标别名必须唯一");
        }
    }

    @Override
    public Protocol protocol() {
        return Protocol.HTTP;
    }

    @Override
    public boolean configured(String targetRef) {
        return targets.containsKey(targetRef) || readTargets.containsKey(targetRef);
    }

    @Override
    public Set<String> targetRefs() {
        var aliases = new HashSet<>(targets.keySet());
        aliases.addAll(readTargets.keySet());
        return Set.copyOf(aliases);
    }

    @Override
    public Outcome invoke(Call call) {
        URI target = targets.get(call.targetRef());
        ReadTarget read = readTargets.get(call.targetRef());
        if (target == null && read == null) {
            return new Outcome("FAILED", null, "TARGET_NOT_CONFIGURED");
        }
        if (read != null) {
            if (call.credential() == null) return new Outcome("FAILED", null, "UPSTREAM_CREDENTIAL_REQUIRED");
            try {
                target = readUri(read, call.arguments());
            } catch (JsonProcessingException | IllegalArgumentException invalid) {
                return new Outcome("FAILED", null, "HTTP_QUERY_INVALID");
            }
        }
        var builder = HttpRequest.newBuilder(target)
                .timeout(Duration.ofMillis(call.timeoutMs()))
                .header("Accept", "application/json")
                .header("Idempotency-Key", call.operationKey())
                .header("X-Trace-Id", call.traceId());
        if (read == null) builder.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(call.arguments()));
        else builder.GET();
        if (call.credential() != null) builder.header("Authorization", "Bearer " + call.credential().bearerToken());
        HttpRequest request = builder.build();
        CompletableFuture<HttpResponse<byte[]>> exchange = client.sendAsync(request, ignored -> boundedBody());
        try {
            var response = exchange.get(call.timeoutMs(), TimeUnit.MILLISECONDS);
            if (response.statusCode() >= 500) {
                return new Outcome("UNKNOWN", null, "UPSTREAM_SERVER_ERROR");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new Outcome("FAILED", null, "UPSTREAM_REJECTED");
            }
            String body = new String(response.body(), StandardCharsets.UTF_8);
            if (read != null && !read.responseFields().isEmpty()) body = projectList(body, read.responseFields());
            return new Outcome("SUCCEEDED", body, null);
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            return new Outcome("FAILED", null, "UPSTREAM_RESPONSE_INVALID");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new Outcome("UNKNOWN", null, "UPSTREAM_INTERRUPTED");
        } catch (TimeoutException ex) {
            return new Outcome("UNKNOWN", null, "UPSTREAM_TIMEOUT");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof ResponseTooLarge) {
                return new Outcome("UNKNOWN", null, "UPSTREAM_RESPONSE_TOO_LARGE");
            }
            if (cause instanceof java.net.http.HttpTimeoutException) {
                return new Outcome("UNKNOWN", null, "UPSTREAM_TIMEOUT");
            }
            return new Outcome("UNKNOWN", null, "UPSTREAM_IO_ERROR");
        } finally {
            if (!exchange.isDone()) {
                exchange.cancel(true);
            }
        }
    }

    private URI readUri(ReadTarget read, String arguments) throws JsonProcessingException {
        var input = json.readTree(arguments);
        if (input == null || !input.isObject()) throw new IllegalArgumentException("GET 参数必须为对象");
        var query = new ArrayList<String>();
        for (var field : input.properties()) {
            var value = field.getValue();
            if (!read.queryParameters().contains(field.getKey())
                    || !(value.isTextual() || value.isNumber() || value.isBoolean())) {
                throw new IllegalArgumentException("GET 参数不在目标白名单中");
            }
            query.add(field.getKey() + "=" + URLEncoder.encode(value.asText(), StandardCharsets.UTF_8));
        }
        return query.isEmpty() ? read.uri() : URI.create(read.uri().toASCIIString() + "?" + String.join("&", query));
    }

    private String projectList(String body, Set<String> fields) throws JsonProcessingException {
        var source = json.readTree(body);
        if (source == null || !source.isArray()) throw new IllegalArgumentException("上游响应必须为列表");
        var result = json.createArrayNode();
        for (var item : source) {
            if (!item.isObject()) throw new IllegalArgumentException("上游列表项必须为对象");
            var visible = result.addObject();
            for (var field : item.properties()) {
                if (fields.contains(field.getKey())) visible.set(field.getKey(), field.getValue());
            }
        }
        return result.toString();
    }

    private static void validateTarget(URI target) {
        if (target == null || !List.of("http", "https").contains(target.getScheme()) || target.getHost() == null
                || target.getUserInfo() != null || target.getFragment() != null) {
            throw new IllegalArgumentException("网关目标必须为不含用户信息和片段的 HTTP(S) URL");
        }
    }

    public record ReadTarget(URI uri, Set<String> queryParameters, Set<String> responseFields) {
        public ReadTarget {
            validateTarget(uri);
            queryParameters = queryParameters == null ? Set.of() : Set.copyOf(queryParameters);
            responseFields = responseFields == null ? Set.of() : Set.copyOf(responseFields);
            if (uri.getRawQuery() != null || queryParameters.stream().anyMatch(key -> !key.matches("[A-Za-z0-9_-]+"))) {
                throw new IllegalArgumentException("GET 目标必须使用独立的查询参数白名单");
            }
        }
    }

    public static HttpResponse.BodySubscriber<byte[]> boundedBody() {
        return new BoundedBody();
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> buffer = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long consumed;

        @Override
        public CompletionStage<byte[]> getBody() { return buffer.getBody(); }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            buffer.onSubscribe(value);
        }

        @Override
        public void onNext(List<ByteBuffer> bytes) {
            for (ByteBuffer item : bytes) {
                consumed += item.remaining();
            }
            if (consumed > RESPONSE_LIMIT) {
                subscription.cancel();
                buffer.onError(new ResponseTooLarge());
            } else {
                buffer.onNext(bytes);
            }
        }

        @Override
        public void onError(Throwable error) { buffer.onError(error); }

        @Override
        public void onComplete() { buffer.onComplete(); }
    }

    private static final class ResponseTooLarge extends RuntimeException {}
}
