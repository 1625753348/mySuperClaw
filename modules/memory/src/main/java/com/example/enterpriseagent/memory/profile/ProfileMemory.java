package com.example.enterpriseagent.memory.profile;

import com.example.enterpriseagent.memory.AbstractMemory;
import com.example.enterpriseagent.memory.store.MemoryStore;

import java.time.OffsetDateTime;
import java.util.Optional;

/** 用户档案按精确键及修订号管理，仅应用受信用户或同步流程写入。 */
public final class ProfileMemory extends AbstractMemory<ProfileMemory.Key, Optional<ProfileMemory.Item>, ProfileMemory.Item> {

    private final MemoryStore<Key, Optional<Item>, Item> store;

    public ProfileMemory(MemoryStore<Key, Optional<Item>, Item> store) {
        this.store = store;
    }

    @Override
    protected Optional<Item> load(Key key) {
        return store.load(key);
    }

    @Override
    protected void save(Item value) {
        store.save(value);
    }

    public record Key(String tenantId, String userId, String itemKey) {}
    public record Item(String tenantId, String userId, String itemKey, String valueJson,
                       String sourceType, String sourceRef, long revision, OffsetDateTime expiresAt) {}
}
