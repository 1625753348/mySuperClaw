package com.example.enterpriseagent.memory.conversation;

import com.example.enterpriseagent.memory.AbstractMemory;
import com.example.enterpriseagent.memory.store.MemoryStore;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 会话摘要与最近原文窗口。读取时校验所有者与历史代次。 */
public final class ConversationMemory extends AbstractMemory<ConversationMemory.Key, ConversationMemory.Window, ConversationMemory.Summary> {

    private final MemoryStore<Key, Window, Summary> store;

    public ConversationMemory(MemoryStore<Key, Window, Summary> store) {
        this.store = store;
    }

    @Override
    protected Window load(Key key) {
        return store.load(key);
    }

    @Override
    protected void save(Summary value) {
        store.save(value);
    }

    public record Key(String tenantId, UUID conversationId, String userId, int limit) {
        public Key {
            if (limit < 1 || limit > 200) {
                throw new IllegalArgumentException("对话窗口必须在 1 到 200 条之间");
            }
        }
    }
    public record Message(long seq, String role, String bodyRef) {}
    public record Summary(String tenantId, UUID conversationId, long version, long historyRevision,
                          long coveredThroughSeq, String sourceDigest, String summarizerVersion, String summaryRef) {}
    public record Window(Optional<Summary> summary, List<Message> messages) {}
}
