package com.example.enterpriseagent.memory.episode;

import com.example.enterpriseagent.memory.AbstractMemory;
import com.example.enterpriseagent.memory.store.MemoryStore;
import com.example.enterpriseagent.memory.store.MemoryVectorIndex;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 复用已经脱敏的任务经验。搜索索引仅返回候选，内容由 Store 复核可见性。 */
public final class EpisodicMemory extends AbstractMemory<EpisodicMemory.Key, Optional<EpisodicMemory.Episode>, EpisodicMemory.Episode> {

    private final MemoryStore<Key, Optional<Episode>, Episode> store;
    private final MemoryVectorIndex index;

    public EpisodicMemory(MemoryStore<Key, Optional<Episode>, Episode> store, MemoryVectorIndex index) {
        this.store = store;
        this.index = index;
    }

    @Override
    protected Optional<Episode> load(Key key) {
        return store.load(key);
    }

    @Override
    protected void save(Episode value) {
        store.save(value);
    }

    public void index(Key key, List<Float> vector) {
        var episode = read(key).orElseThrow(() -> new IllegalArgumentException("案例不可见或未发布"));
        index.upsert(key.tenantId(), MemoryVectorIndex.Kind.EPISODE, episode.id().toString(), vector);
    }

    /** 即使向量尚未删除，也只返回权威 Store 中当前可见的案例。 */
    public List<Episode> search(String tenantId, String userId, boolean allowTenantScope, List<Float> vector, int limit) {
        return index.search(tenantId, MemoryVectorIndex.Kind.EPISODE, vector, limit).stream()
                .map(hit -> read(new Key(tenantId, UUID.fromString(hit.sourceKey()), userId, allowTenantScope)))
                .flatMap(Optional::stream)
                .toList();
    }

    public record Key(String tenantId, UUID id, String userId, boolean allowTenantScope) {}
    public record Episode(String tenantId, UUID id, UUID sourceRunId, String ownerUserId, String scope,
                          String scenarioKey, String status, long revision, String episodeRef,
                          OffsetDateTime expiresAt) {}
}
