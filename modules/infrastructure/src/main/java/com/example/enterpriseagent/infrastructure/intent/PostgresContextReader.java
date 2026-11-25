package com.example.enterpriseagent.infrastructure.intent;

import com.example.enterpriseagent.intent.context.ContextAssembler;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Comparator;
import java.util.stream.Collectors;

import static com.example.enterpriseagent.intent.IntentArtifacts.*;

/** 读取当前已授权会话的有限历史和任务证据。查询本身不会改变任务或会话。 */
final class PostgresContextReader implements ContextAssembler.Source {
    private final JdbcClient db;

    PostgresContextReader(JdbcClient db) {
        this.db = db;
    }

    @Override public ContextDetails load(ContextRequest request) {
        return new ContextDetails(recentMessages(request), recentIntents(request), taskDetails(request));
    }

    private List<RecentMessage> recentMessages(ContextRequest request) {
        return scoped("""
                SELECT m.id,m.turn_id,m.seq,m.role,
                    EXISTS (SELECT 1 FROM intent.route_job j WHERE j.tenant_id=m.tenant_id
                        AND j.conversation_id=m.conversation_id AND j.turn_id=m.turn_id AND j.ordinal=0
                        AND m.role='ASSISTANT' AND j.mode='CHAT' AND j.status='SUCCEEDED'
                        AND j.output->>'answer' IS NOT NULL) AS chat_answer
                FROM intent.conversation c
                JOIN intent.conversation_state s ON s.tenant_id=c.tenant_id AND s.conversation_id=c.id
                JOIN intent.message m ON m.tenant_id=c.tenant_id AND m.conversation_id=c.id AND m.redacted_at IS NULL
                JOIN intent.turn t ON t.tenant_id=m.tenant_id AND t.id=m.turn_id AND t.trace_input IS NOT NULL
                WHERE c.tenant_id=:tenant AND c.id=:conversation AND c.owner_user_id=:user AND s.agent_id=:agent
                    AND m.body_ref IS NOT NULL
                ORDER BY (m.id=CAST(:anchor AS uuid)) DESC NULLS LAST,m.seq DESC LIMIT 8
                """, request).param("anchor", request.replyToMessageId())
                .query((rs, row) -> new RecentMessage(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getLong(3), rs.getString(4), rs.getBoolean(5)))
                .list().stream().sorted(Comparator.comparingLong(RecentMessage::sequence)).toList();
    }

    private List<RecentIntent> recentIntents(ContextRequest request) {
        return scoped("""
                WITH recent AS (
                    SELECT m.turn_id,m.seq FROM intent.conversation c
                    JOIN intent.conversation_state s ON s.tenant_id=c.tenant_id AND s.conversation_id=c.id
                    JOIN intent.message m ON m.tenant_id=c.tenant_id AND m.conversation_id=c.id
                        AND m.role='USER' AND m.redacted_at IS NULL
                    JOIN intent.turn t ON t.tenant_id=m.tenant_id AND t.id=m.turn_id AND t.trace_input IS NOT NULL
                    WHERE c.tenant_id=:tenant AND c.id=:conversation AND c.owner_user_id=:user AND s.agent_id=:agent
                    ORDER BY m.seq DESC LIMIT 8
                )
                SELECT i.id,i.turn_id,t.intent_id,i.draft->>'intent',i.draft->>'domain',i.draft->>'action',i.outcome->>'status'
                FROM recent x JOIN intent.candidate i ON i.tenant_id=:tenant AND i.conversation_id=:conversation AND i.turn_id=x.turn_id
                LEFT JOIN intent.task_input ti ON ti.tenant_id=i.tenant_id AND ti.source_candidate_id=i.id
                    AND ti.conversation_id=i.conversation_id
                LEFT JOIN intent.task t ON t.tenant_id=i.tenant_id AND t.id=coalesce(ti.task_id,i.target_task_id)
                    AND t.conversation_id=i.conversation_id
                ORDER BY x.seq DESC,i.ordinal DESC LIMIT 24
                """, request).query((rs, row) -> new RecentIntent(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)))
                .list().reversed();
    }

    private Map<UUID, TaskContext> taskDetails(ContextRequest request) {
        if (request.taskIds().isEmpty()) return Map.of();
        return scoped("""
                SELECT t.id,t.intent_id,t.input_version,t.status,q.id AS clarification_id,
                  ARRAY(SELECT jsonb_array_elements_text(q.question->'missing')) AS missing,
                  ARRAY(SELECT jsonb_array_elements_text(q.question->'invalid')) AS invalid,
                  q.question->>'text' AS question,q.expires_at,
                  r.id AS run_id,r.status AS run_status,r.input_version AS run_input_version
                FROM intent.conversation c
                JOIN intent.conversation_state s ON s.tenant_id=c.tenant_id AND s.conversation_id=c.id
                JOIN intent.task t ON t.tenant_id=c.tenant_id AND t.conversation_id=c.id
                LEFT JOIN LATERAL (
                    SELECT p.id,p.question,p.expires_at FROM intent.clarification p
                    WHERE p.tenant_id=t.tenant_id AND p.conversation_id=t.conversation_id AND p.task_id=t.id
                      AND p.input_version=t.input_version AND p.status='OPEN'
                      AND (p.expires_at IS NULL OR p.expires_at>now())
                    ORDER BY p.created_at DESC,p.id LIMIT 1
                ) q ON t.status='COLLECTING_INPUT'
                LEFT JOIN runtime.run r ON :readRuns AND r.tenant_id=t.tenant_id AND r.task_id=t.id
                    AND r.user_id=:user AND r.agent_id=:agent AND r.finished_at IS NULL
                WHERE c.tenant_id=:tenant AND c.id=:conversation AND c.owner_user_id=:user AND s.agent_id=:agent
                    AND t.id IN (:tasks)
                """, request).param("readRuns", request.actor().scopes().contains("runs.read")).param("tasks", request.taskIds())
                .query((rs, row) -> {
                    int version = rs.getInt("input_version");
                    UUID clarificationId = rs.getObject("clarification_id", UUID.class);
                    UUID runId = rs.getObject("run_id", UUID.class);
                    var clarification = clarificationId == null ? null : new PendingClarification(clarificationId, version,
                            Arrays.asList((String[]) rs.getArray("missing").getArray()),
                            Arrays.asList((String[]) rs.getArray("invalid").getArray()), rs.getString("question"),
                            rs.getObject("expires_at", OffsetDateTime.class));
                    var run = runId == null ? null : new ActiveRun(runId, rs.getString("run_status"), rs.getInt("run_input_version"));
                    return Map.entry(rs.getObject("id", UUID.class),
                            new TaskContext(rs.getObject("intent_id", UUID.class), version, rs.getString("status"), clarification, run));
                }).list().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private JdbcClient.StatementSpec scoped(String sql, ContextRequest request) {
        var actor = request.actor();
        return db.sql(sql).param("tenant", actor.tenant()).param("conversation", request.conversationId())
                .param("user", actor.user()).param("agent", actor.agent());
    }
}
