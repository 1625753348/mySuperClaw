package com.example.enterpriseagent.memory.semantic;

import com.example.enterpriseagent.memory.AbstractMemory;
import com.example.enterpriseagent.memory.store.MemoryStore;
import com.example.enterpriseagent.memory.store.MemoryVectorIndex;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 知识正文使用版本化引用，按调用方获准的访问策略读取。 */
public final class SemanticMemory extends AbstractMemory<SemanticMemory.Key, Optional<SemanticMemory.Version>, SemanticMemory.Publication> {

    private final MemoryStore<Key, Optional<Version>, Publication> store;
    private final MemoryVectorIndex index;

    public SemanticMemory(MemoryStore<Key, Optional<Version>, Publication> store, MemoryVectorIndex index) {
        this.store = store;
        this.index = index;
    }

    @Override
    protected Optional<Version> load(Key key) {
        return store.load(key);
    }

    @Override
    protected void save(Publication value) {
        store.save(value);
    }

    public void index(Key key, List<Float> vector) {
        var version = read(key).orElseThrow(() -> new IllegalArgumentException("知识版本不可见或未发布"));
        index.upsert(key.tenantId(), MemoryVectorIndex.Kind.SEMANTIC,
                version.documentId() + "/" + version.version(), vector);
    }

    /** 索引键含版本，历史向量命中时不会用它替代当前文档。 */
    public List<Version> search(String tenantId, String accessPolicyRef, List<Float> vector, int limit) {
        return index.search(tenantId, MemoryVectorIndex.Kind.SEMANTIC, vector, limit).stream()
                .map(hit -> {
                    String[] pair = hit.sourceKey().split("/", 2);
                    return read(new Key(tenantId, UUID.fromString(pair[0]), accessPolicyRef))
                            .filter(version -> version.version() == Integer.parseInt(pair[1]));
                })
                .flatMap(Optional::stream)
                .toList();
    }

    public record Key(String tenantId, UUID documentId, String accessPolicyRef) {}
    public record Version(String tenantId, UUID documentId, int version, String sourceRef, String contentDigest) {}
    public record Chunk(int ordinal, String chunkRef, String contentDigest) {}
    public record Publication(Version version, String collectionKey, String sourceKey,
                              String accessPolicyRef, List<Chunk> chunks) {}
}
