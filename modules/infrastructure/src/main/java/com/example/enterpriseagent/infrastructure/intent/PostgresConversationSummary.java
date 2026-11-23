package com.example.enterpriseagent.infrastructure.intent;

import com.example.enterpriseagent.intent.IntentEngine;
import com.example.enterpriseagent.memory.conversation.ConversationMemory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 使用已提交的闲聊轮次压缩较早的历史。模型推理由调用方在事务提交之后执行。 */
public final class PostgresConversationSummary {
    private static final String VERSION = "conversation-extractive-v1";
    private static final int WINDOW_MESSAGES = 16;
    private static final int RETAINED_TURNS = 8;
    private static final int SUMMARY_CHARS = 160;
    private static final int WINDOW_CHARS = 512;
    private final JdbcClient db;
    private final TransactionOperations transactions;
    private final ConversationMemory memory;
    private final ObjectMapper json;

    public PostgresConversationSummary(JdbcClient db, TransactionOperations transactions, ConversationMemory memory,
                                       ObjectMapper json) {
        this.db = db;
        this.transactions = transactions;
        this.memory = memory;
        this.json = json;
    }

    void afterTurn(String tenant, UUID conversation, long historyRevision, long lastSeq) {
        compact(tenant, conversation, historyRevision, lastSeq - WINDOW_MESSAGES);
    }

    History forChat(String tenant, UUID conversation, String user, String agent, UUID turn) {
        return transactions.execute(ignored -> {
            var cursor = db.sql("""
                    SELECT c.history_revision,m.seq FROM intent.conversation c
                    JOIN intent.conversation_state s ON s.tenant_id=c.tenant_id AND s.conversation_id=c.id
                    JOIN intent.message m ON m.tenant_id=c.tenant_id AND m.conversation_id=c.id
                    WHERE c.tenant_id=? AND c.id=? AND c.owner_user_id=? AND s.agent_id=?
                      AND m.turn_id=? AND m.role='USER' AND m.redacted_at IS NULL
                    FOR UPDATE OF c
                    """).params(tenant, conversation, user, agent, turn)
                    .query((rs, row) -> new Cursor(rs.getLong(1), rs.getLong(2))).optional()
                    .orElseThrow(() -> new IllegalStateException("对话历史不可用"));
            long cutoff = Math.max(0, cursor.seq() - 1 - WINDOW_MESSAGES);
            Stored summary = compact(tenant, conversation, cursor.revision(), cutoff);
            var recent = readTurns(tenant, conversation, cutoff, cursor.seq() - 1, WINDOW_CHARS);
            return new History(summary.turns(), recent);
        });
    }

    private Stored compact(String tenant, UUID conversation, long revision, long proposed) {
        if (proposed <= 0) return Stored.EMPTY;
        Stored previous = load(tenant, conversation, revision, proposed);
        if (previous.watermark() >= proposed) return previous;
        // 活跃的旧闲聊可能还有正文未生成。先压缩它之前的完整轮次，防止跨过去后漏掉回复。
        long cutoff = db.sql("""
                SELECT coalesce(min(m.seq)-1,:until) FROM intent.route_job j
                JOIN intent.message m ON m.tenant_id=j.tenant_id AND m.conversation_id=j.conversation_id AND m.turn_id=j.turn_id
                WHERE j.tenant_id=:tenant AND j.conversation_id=:conversation AND j.mode='CHAT'
                  AND j.status='QUEUED' AND j.deadline>now() AND m.role='USER' AND m.redacted_at IS NULL
                  AND m.seq>:after AND m.seq<=:until
                """).param("tenant", tenant).param("conversation", conversation).param("after", previous.watermark())
                .param("until", proposed).query(Long.class).single();
        if (cutoff <= previous.watermark()) return previous;
        var older = readTurns(tenant, conversation, previous.watermark(), cutoff, SUMMARY_CHARS);
        if (older.isEmpty()) return previous;
        var entries = new ArrayList<>(previous.turns());
        entries.addAll(older);
        var result = List.copyOf(entries.subList(Math.max(0, entries.size() - RETAINED_TURNS), entries.size()));
        String digest = IntentEngine.fingerprint(previous.digest() + "|" + encode(older));
        if (canAppend(tenant, conversation, revision, cutoff)) {
            UUID body = UUID.randomUUID();
            db.sql("INSERT INTO intent.conversation_body(tenant_id,conversation_id,id,body) VALUES(?,?,?,?)")
                    .params(tenant, conversation, body, encode(result)).update();
            long version = db.sql("""
                    SELECT coalesce(max(version),0)+1 FROM memory.conversation_summary WHERE tenant_id=? AND conversation_id=?
                    """).params(tenant, conversation).query(Long.class).single();
            memory.write(new ConversationMemory.Summary(tenant, conversation, version, revision, cutoff, digest, VERSION,
                    "pg:" + body));
        }
        return new Stored(cutoff, digest, result);
    }

    private boolean canAppend(String tenant, UUID conversation, long revision, long cutoff) {
        // 异步聊天可能比新轮次晚到。可还原该轮摘要，但应保留已推进的持久水位。
        return db.sql("""
                SELECT NOT EXISTS (SELECT 1 FROM memory.conversation_summary
                WHERE tenant_id=? AND conversation_id=? AND history_revision=? AND covered_through_seq>=?)
                """).params(tenant, conversation, revision, cutoff).query(Boolean.class).single();
    }

    private Stored load(String tenant, UUID conversation, long revision, long cutoff) {
        return db.sql("""
                SELECT s.covered_through_seq,s.source_digest,b.body FROM memory.conversation_summary s
                JOIN intent.conversation_body b ON b.tenant_id=s.tenant_id AND b.conversation_id=s.conversation_id
                    AND b.id=CASE WHEN s.summary_ref LIKE 'pg:%' THEN substring(s.summary_ref FROM 4)::uuid END
                WHERE s.tenant_id=? AND s.conversation_id=? AND s.history_revision=? AND s.covered_through_seq<=?
                    AND s.summarizer_version=? ORDER BY s.covered_through_seq DESC LIMIT 1
                """).params(tenant, conversation, revision, cutoff, VERSION)
                .query((rs, row) -> new Stored(rs.getLong(1), rs.getString(2), decode(rs.getString(3))))
                .optional().orElse(Stored.EMPTY);
    }

    private List<Turn> readTurns(String tenant, UUID conversation, long after, long before, int maxChars) {
        return db.sql("""
                SELECT * FROM (
                    SELECT m.seq,b.body,CASE WHEN j.status='SUCCEEDED' THEN j.output->>'answer' END AS answer
                    FROM intent.message m
                    JOIN intent.turn t ON t.tenant_id=m.tenant_id AND t.conversation_id=m.conversation_id AND t.id=m.turn_id
                    JOIN intent.conversation_body b ON b.tenant_id=m.tenant_id AND b.conversation_id=m.conversation_id
                        AND b.id=CASE WHEN m.body_ref LIKE 'pg:%' THEN substring(m.body_ref FROM 4)::uuid END
                    LEFT JOIN intent.route_job j ON j.tenant_id=t.tenant_id AND j.conversation_id=t.conversation_id
                        AND j.turn_id=t.id AND j.ordinal=0 AND j.mode='CHAT'
                    WHERE m.tenant_id=? AND m.conversation_id=? AND m.role='USER' AND m.redacted_at IS NULL
                      AND m.seq>? AND m.seq<=? AND jsonb_array_length(t.receipt->'outcomes')=1
                      AND t.receipt#>>'{outcomes,0,route}'='CHAT' AND t.receipt#>>'{outcomes,0,status}'='ROUTED'
                    ORDER BY m.seq DESC LIMIT ?
                ) recent ORDER BY seq
                """).params(tenant, conversation, after, before, RETAINED_TURNS)
                .query((rs, row) -> new Turn(rs.getLong(1), clip(rs.getString(2), maxChars), clip(rs.getString(3), maxChars))).list();
    }

    private static String clip(String input, int limit) {
        if (input == null) return null;
        String text = input.strip();
        if (limit == SUMMARY_CHARS) text = text.replaceAll("\\s+", " ");
        return text.codePointCount(0, text.length()) <= limit ? text : text.substring(0, text.offsetByCodePoints(0, limit)) + "…";
    }

    private String encode(List<Turn> turns) {
        try { return json.writeValueAsString(turns); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("无法保存对话摘要", ex); }
    }

    private List<Turn> decode(String body) {
        try { return json.readValue(body, new TypeReference<>() {}); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("无法读取对话摘要", ex); }
    }

    public record History(List<Turn> summary, List<Turn> recent) {}
    public record Turn(long seq, String user, String assistant) {}
    private record Cursor(long revision, long seq) {}
    private record Stored(long watermark, String digest, List<Turn> turns) {
        private static final Stored EMPTY = new Stored(0, "", List.of());
    }
}
