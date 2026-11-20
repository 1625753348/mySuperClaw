package com.example.enterpriseagent.infrastructure.gateway;

import com.example.enterpriseagent.memory.store.MemoryVectorIndex;
import com.example.enterpriseagent.toolgateway.discovery.ToolSemanticSearch;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** OpenAI 兼容 Embeddings 接口与独立 Milvus 工具集合。 */
public final class MilvusToolSemanticSearch implements ToolSemanticSearch {
    private final boolean enabled;
    private final ObjectMapper json;
    private final MemoryVectorIndex index;
    private final URI embeddingUri;
    private final String apiKey;
    private final String model;
    private final int dimension;
    private final double threshold;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public MilvusToolSemanticSearch(boolean enabled, ObjectMapper json, MemoryVectorIndex index, URI embeddingUri,
                                    String apiKey, String model, int dimension, double threshold) {
        if (enabled && (apiKey == null || apiKey.isBlank() || model == null || model.isBlank()
                || embeddingUri == null || embeddingUri.getHost() == null
                || !List.of("https", "http").contains(embeddingUri.getScheme())
                || embeddingUri.getUserInfo() != null || dimension <= 0
                || !Double.isFinite(threshold) || threshold < -1 || threshold > 1)) {
            throw new IllegalArgumentException("启用工具向量发现需要正确的 Embedding 配置");
        }
        this.enabled = enabled;
        this.json = json;
        this.index = index;
        this.embeddingUri = embeddingUri;
        this.apiKey = apiKey;
        this.model = model == null ? null : model.replaceFirst("^dashscope/", "");
        this.dimension = dimension;
        this.threshold = threshold;
    }

    @Override public boolean enabled() { return enabled; }

    @Override
    public List<Match> search(String tenant, String query, int limit) {
        return index.search(tenant, MemoryVectorIndex.Kind.TOOL, embed(query), limit).stream()
                .filter(hit -> hit.score() >= threshold)
                .map(hit -> new Match(UUID.fromString(hit.sourceKey()), hit.score())).toList();
    }

    @Override
    public void index(String tenant, UUID version, String text) {
        index.upsert(tenant, MemoryVectorIndex.Kind.TOOL, version.toString(), embed(text));
    }

    public List<Float> embed(String input) {
        var body = json.createObjectNode().put("model", model).put("input", input)
                .put("dimensions", dimension).put("encoding_format", "float");
        var request = HttpRequest.newBuilder(embeddingUri).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        var exchange = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            var response = exchange.get(5, TimeUnit.SECONDS);
            if (response.statusCode() != 200 || response.body().length() > 262_144) {
                throw new IllegalStateException("Embedding 服务未返回有效响应");
            }
            JsonNode array = json.readTree(response.body()).path("data").path(0).path("embedding");
            if (!array.isArray() || array.size() != dimension) {
                throw new IllegalStateException("Embedding 维度与 Milvus 集合不匹配");
            }
            List<Float> result = new ArrayList<>(dimension);
            for (JsonNode value : array) {
                if (!value.isNumber() || !Float.isFinite(value.floatValue())) {
                    throw new IllegalStateException("Embedding 含无效数值");
                }
                result.add(value.floatValue());
            }
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Embedding 调用被中断");
        } catch (Exception failure) {
            throw new IllegalStateException("Embedding 服务暂不可用");
        } finally {
            if (!exchange.isDone()) {
                exchange.cancel(true);
            }
        }
    }
}
