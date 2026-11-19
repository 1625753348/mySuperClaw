package com.example.enterpriseagent.memory.session;

import com.example.enterpriseagent.memory.AbstractMemory;
import com.example.enterpriseagent.memory.store.MemoryStore;

import java.time.Duration;
import java.util.Optional;

/** 会话临时状态按租户、用户和会话隔离，过期后由 Redis 清除。 */
public final class SessionMemory extends AbstractMemory<SessionMemory.Key, Optional<String>, SessionMemory.Entry> {

    private final MemoryStore<Key, Optional<String>, Entry> store;

    public SessionMemory(MemoryStore<Key, Optional<String>, Entry> store) {
        this.store = store;
    }

    @Override
    protected Optional<String> load(Key key) {
        return store.load(key);
    }

    @Override
    protected void save(Entry value) {
        store.save(value);
    }

    public record Key(String tenantId, String userId, String sessionId) {}
    public record Entry(Key key, String contextJson, Duration ttl) {}
}
