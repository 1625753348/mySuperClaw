package com.example.enterpriseagent.infrastructure.milvus;

import com.example.enterpriseagent.memory.store.MemoryVectorIndex;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.FloatVec;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Milvus 只保存向量和权威记录引用。召回强制按租户和记忆类型过滤。 */
public final class MilvusMemoryIndex implements MemoryVectorIndex {

    private final MilvusClientV2 client;
    private final String collection;
    private final int dimension;
    private volatile boolean initialized;

    public MilvusMemoryIndex(MilvusClientV2 client, String collection, int dimension) {
        if (!collection.matches("[A-Za-z_][A-Za-z0-9_]{0,254}") || dimension <= 0) {
            throw new IllegalArgumentException("Milvus 集合名或向量维度无效");
        }
        this.client = client;
        this.collection = collection;
        this.dimension = dimension;
    }

    @Override
    public void upsert(String tenantId, Kind kind, String sourceKey, List<Float> vector) {
        requireText(tenantId, 256);
        requireText(sourceKey, 512);
        validateVector(vector);
        String key = tenantId.length() + ":" + tenantId + ':' + kind.name() + ':' + sourceKey;
        JsonObject data = new JsonObject();
        data.addProperty("id", UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString());
        data.addProperty("tenant_id", tenantId);
        data.addProperty("kind", kind.name());
        data.addProperty("source_key", sourceKey);
        JsonArray embedding = new JsonArray();
        vector.forEach(embedding::add);
        data.add("embedding", embedding);
        ensureReady();
        client.upsert(UpsertReq.builder().collectionName(collection).data(List.of(data)).build());
    }

    @Override
    public List<Hit> search(String tenantId, Kind kind, List<Float> vector, int limit) {
        requireText(tenantId, 256);
        validateVector(vector);
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("向量查询数量必须为 1 到 100");
        }
        ensureReady();
        String filter = "tenant_id == " + new JsonPrimitive(tenantId) + " and kind == " + new JsonPrimitive(kind.name());
        var response = client.search(SearchReq.builder()
                .collectionName(collection).annsField("embedding").topK(limit)
                .filter(filter).outputFields(List.of("source_key"))
                .data(List.of(new FloatVec(vector))).consistencyLevel(ConsistencyLevel.STRONG).build());
        return response.getSearchResults().stream().flatMap(List::stream)
                .map(result -> new Hit(result.getEntity().get("source_key").toString(), result.getScore()))
                .toList();
    }

    private synchronized void ensureReady() {
        if (initialized) {
            return;
        }
        HasCollectionReq target = HasCollectionReq.builder().collectionName(collection).build();
        if (!Boolean.TRUE.equals(client.hasCollection(target))) {
            try {
                createCollection();
            } catch (RuntimeException failure) {
                // API 与 Worker 可以同时首次连接。若对方已创建集合，继续加载。
                if (!Boolean.TRUE.equals(client.hasCollection(target))) {
                    throw failure;
                }
            }
        }
        client.loadCollection(LoadCollectionReq.builder().collectionName(collection).build());
        initialized = true;
    }

    private void createCollection() {
        var schema = client.createSchema();
        schema.addField(AddFieldReq.builder().fieldName("id").dataType(DataType.VarChar)
                .isPrimaryKey(true).maxLength(36).build());
        schema.addField(AddFieldReq.builder().fieldName("tenant_id").dataType(DataType.VarChar).maxLength(256).build());
        schema.addField(AddFieldReq.builder().fieldName("kind").dataType(DataType.VarChar).maxLength(16).build());
        schema.addField(AddFieldReq.builder().fieldName("source_key").dataType(DataType.VarChar).maxLength(512).build());
        schema.addField(AddFieldReq.builder().fieldName("embedding").dataType(DataType.FloatVector).dimension(dimension).build());
        var index = IndexParam.builder().fieldName("embedding")
                .indexType(IndexParam.IndexType.FLAT).metricType(IndexParam.MetricType.COSINE).build();
        client.createCollection(CreateCollectionReq.builder().collectionName(collection)
                .collectionSchema(schema).indexParams(List.of(index)).build());
    }

    private void validateVector(List<Float> vector) {
        if (vector.size() != dimension || vector.stream().anyMatch(v -> v == null || !Float.isFinite(v))
                || vector.stream().allMatch(v -> v == 0)) {
            throw new IllegalArgumentException("向量必须与集合维度一致，且由有限数值组成并非零向量");
        }
    }

    private static void requireText(String value, int bytes) {
        Objects.requireNonNull(value, "向量引用不能为空");
        if (value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > bytes) {
            throw new IllegalArgumentException("向量引用为空或超过长度限制");
        }
    }
}
