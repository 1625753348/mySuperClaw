package com.example.enterpriseagent.toolgateway.adapter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 首期协议为 POST JSON。目标来自部署配置，响应大小与整体等待时间均有上限。 */
public final class HttpToolAdapter implements ToolAdapter {

    private static final int RESPONSE_LIMIT = 262_144;
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final Map<String, URI> targets;

    public HttpToolAdapter(Map<String, URI> targets) {
        this.targets = Map.copyOf(targets);
        this.targets.values().forEach(target -> {
            if (!List.of("http", "https").contains(target.getScheme()) || target.getHost() == null
                    || target.getUserInfo() != null || target.getFragment() != null) {
                throw new IllegalArgumentException("网关目标必须为不含用户信息和片段的 HTTP(S) URL");
            }
        });
    }

    @Override
    public Protocol protocol() {
        return Protocol.HTTP;
    }

    @Override
    public boolean configured(String targetRef) {
        return targets.containsKey(targetRef);
    }

    @Override
    public Set<String> targetRefs() {
        return targets.keySet();
    }

    @Override
    public Outcome invoke(Call call) {
        URI target = targets.get(call.targetRef());
        if (target == null) {
            return new Outcome("FAILED", null, "TARGET_NOT_CONFIGURED");
        }
        var builder = HttpRequest.newBuilder(target)
                .timeout(Duration.ofMillis(call.timeoutMs()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Idempotency-Key", call.operationKey())
                .header("X-Trace-Id", call.traceId())
                .POST(HttpRequest.BodyPublishers.ofString(call.arguments()));
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
            return new Outcome("SUCCEEDED", new String(response.body(), StandardCharsets.UTF_8), null);
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
