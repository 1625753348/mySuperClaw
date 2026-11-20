package com.example.enterpriseagent.infrastructure.intent;

import com.example.enterpriseagent.intent.IntentEngine;
import com.example.enterpriseagent.intent.IntentModel.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 同一会话按修订号串行结算。识别和可选模型调用发生在事务外。 */
public final class PostgresIntentStore implements Store {
    private final JdbcClient db;
    private final TransactionOperations transactions;
    private final IntentEngine engine;
    private final ObjectMapper json;

    public PostgresIntentStore(JdbcClient db, TransactionOperations transactions, IntentEngine engine, ObjectMapper json) {
        this.db = db;
        this.transactions = transactions;
        this.engine = engine;
        this.json = json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    @Override public UUID create(Actor actor) {
        return transactions.execute(ignored -> {
            UUID id = UUID.randomUUID();
            db.sql("INSERT INTO intent.conversation(tenant_id,id,owner_user_id) VALUES(?,?,?)")
                    .params(actor.tenant(), id, actor.user()).update();
            db.sql("INSERT INTO intent.conversation_state(tenant_id,conversation_id,agent_id,snapshot) VALUES(?,?,?,?::jsonb)")
                    .params(actor.tenant(), id, actor.agent(), write(State.empty())).update();
            return id;
        });
    }

    @Override public Receipt turn(Actor actor, UUID conversation, Request request, String key) {
        if (key == null || key.isBlank() || key.length() > 128) throw new Failure(400, "需要有效的 Idempotency-Key");
        String encoded = write(request);
        if (encoded.length() > 32_768) throw new Failure(400, "请求体过大");
        String hash = IntentEngine.fingerprint(encoded);
        for (int retry = 0; retry < 3; retry++) {
            Snapshot snapshot = readSnapshot(actor, conversation, false);
            Previous old = previous(actor, conversation, key);
            if (old != null) return replay(old, hash);
            Request resolved = withReplyTarget(actor, conversation, request);
            Analysis result = engine.analyze(actor, resolved, snapshot.state());
            Receipt saved = transactions.execute(ignored -> {
                Snapshot locked = readSnapshot(actor, conversation, true);
                Previous existing = previous(actor, conversation, key);
                if (existing != null) return replay(existing, hash);
                if (locked.revision() != snapshot.revision()) return null;
                return commit(actor, conversation, request, key, hash, locked, result);
            });
            if (saved != null) return saved;
        }
        throw new Failure(409, "会话已被并发更新，请使用原幂等键重试");
    }

    @Override public Receipt receipt(Actor actor, UUID conversation, UUID turn) {
        readSnapshot(actor, conversation, false);
        return db.sql("SELECT receipt::text FROM intent.turn WHERE tenant_id=? AND conversation_id=? AND id=? AND actor_user_id=?")
                .params(actor.tenant(), conversation, turn, actor.user()).query(String.class).optional()
                .map(value -> read(value, Receipt.class)).orElseThrow(() -> new Failure(404, "会话中没有该轮次"));
    }

    private Request withReplyTarget(Actor actor, UUID conversation, Request request) {
        if (request.replyToMessageId() == null) return request;
        UUID turn = db.sql("SELECT turn_id FROM intent.message WHERE tenant_id=? AND conversation_id=? AND id=? AND redacted_at IS NULL")
                .params(actor.tenant(), conversation, request.replyToMessageId()).query(UUID.class).optional()
                .orElseThrow(() -> new Failure(404, "会话中没有该回复对象"));
        List<UUID> targets = receipt(actor, conversation, turn).outcomes().stream().map(Outcome::taskId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        UUID explicit = request.targetTaskId();
        if (explicit != null && !targets.contains(explicit)) throw new Failure(400, "明确任务与回复对象不一致");
        if (explicit == null && targets.size() > 1) throw new Failure(409, "该轮次有多个任务，请指定目标任务");
        return new Request(request.text(), request.skillKey(), request.arguments(),
                explicit != null ? explicit : targets.size() == 1 ? targets.getFirst() : null, request.replyToMessageId());
    }

    private Snapshot readSnapshot(Actor actor, UUID conversation, boolean lock) {
        String query = """
                SELECT s.revision,s.snapshot::text,c.last_message_seq,c.history_revision
                FROM intent.conversation_state s JOIN intent.conversation c
                   ON c.tenant_id=s.tenant_id AND c.id=s.conversation_id
                WHERE s.tenant_id=? AND s.conversation_id=? AND c.owner_user_id=? AND s.agent_id=?
                """;
        return db.sql(query + (lock ? " FOR UPDATE OF s,c" : ""))
                .params(actor.tenant(), conversation, actor.user(), actor.agent())
                .query((rs, row) -> new Snapshot(rs.getLong(1), read(rs.getString(2), State.class), rs.getLong(3), rs.getLong(4)))
                .optional().orElseThrow(() -> new Failure(404, "会话不存在"));
    }

    private Previous previous(Actor actor, UUID conversation, String key) {
        return db.sql("""
                SELECT request_hash,receipt::text FROM intent.turn
                WHERE tenant_id=? AND conversation_id=? AND actor_user_id=? AND client_key=?
                """).params(actor.tenant(), conversation, actor.user(), key)
                .query((rs, row) -> new Previous(rs.getString(1), rs.getString(2))).optional().orElse(null);
    }

    private Receipt replay(Previous entry, String hash) {
        if (!entry.hash().equals(hash)) throw new Failure(409, "幂等键已用于其他请求");
        return read(entry.receipt(), Receipt.class);
    }

    private Receipt commit(Actor actor, UUID conversation, Request request, String key, String hash,
                           Snapshot previous, Analysis result) {
        UUID turn = UUID.randomUUID();
        UUID reply = UUID.randomUUID();
        Receipt receipt = new Receipt(turn, reply, result.outcomes(), result.reply());
        db.sql("""
                INSERT INTO intent.turn(tenant_id,id,conversation_id,actor_user_id,client_key,request_hash,status,receipt)
                VALUES(?,?,?,?,?,?,'COMPLETED',?::jsonb)
                """).params(actor.tenant(), turn, conversation, actor.user(), key, hash, write(receipt)).update();
        State state = result.state();
        boolean cleared = previous.state().privacyRevision() != state.privacyRevision();
        if (cleared) clearContext(actor, conversation);
        String body = result.outcomes().stream().anyMatch(outcome -> "SECURITY.BLOCKED".equals(outcome.intent()))
                ? "[内容已拦截]" : request.text() == null ? request.skillKey() : request.text();
        appendMessage(actor, conversation, turn, UUID.randomUUID(), previous.seq() + 1, 0, "USER", body,
                cleared ? null : request.replyToMessageId());
        appendMessage(actor, conversation, turn, reply, previous.seq() + 2, 1, "ASSISTANT", result.reply(), null);
        UUID[] candidateIds = new UUID[result.outcomes().size()];
        for (int ordinal = 0; ordinal < candidateIds.length; ordinal++) {
            var outcome = result.outcomes().get(ordinal);
            var prior = previous.state().tasks().stream().anyMatch(t -> t.id().equals(outcome.taskId()));
            candidateIds[ordinal] = recordCandidate(actor, conversation, turn, ordinal, outcome, prior);
        }
        for (Task task : state.tasks()) {
            if (task.touched() != state.revision()) continue;
            int latest = result.outcomes().size() - 1;
            while (latest >= 0 && !task.id().equals(result.outcomes().get(latest).taskId())) latest--;
            if ("CANCELLED".equals(task.status())) cancelTask(actor, task.id());
            else if (latest >= 0) persistTask(actor, conversation, turn, candidateIds[latest], task, result.outcomes().get(latest));
        }
        db.sql("UPDATE intent.conversation_state SET revision=?,snapshot=?::jsonb WHERE tenant_id=? AND conversation_id=?")
                .params(state.revision(), write(state), actor.tenant(), conversation).update();
        long historyRevision = previous.historyRevision() + (cleared ? 1 : 0);
        db.sql("UPDATE intent.conversation SET last_message_seq=?,history_revision=? WHERE tenant_id=? AND id=?")
                .params(previous.seq() + 2, historyRevision, actor.tenant(), conversation).update();
        if (!state.summary().isBlank() && !state.summary().equals(previous.state().summary())) {
            appendSummary(actor, conversation, state, historyRevision, previous.seq() + 2);
        }
        return receipt;
    }

    private void appendMessage(Actor actor, UUID conversation, UUID turn, UUID id, long seq, int position,
                               String role, String body, UUID reply) {
        saveBody(actor, conversation, id, body);
        db.sql("""
                INSERT INTO intent.message(tenant_id,id,conversation_id,turn_id,seq,role,position,body_ref,reply_to_id)
                VALUES(:tenant,:id,:conversation,:turn,:seq,:role,:position,:body,:reply)
                """).param("tenant", actor.tenant()).param("id", id).param("conversation", conversation)
                .param("turn", turn).param("seq", seq).param("role", role).param("position", position)
                .param("body", "pg:" + id).param("reply", reply).update();
    }

    private void saveBody(Actor actor, UUID conversation, UUID id, String body) {
        db.sql("INSERT INTO intent.conversation_body(tenant_id,conversation_id,id,body) VALUES(?,?,?,?)")
                .params(actor.tenant(), conversation, id, body).update();
    }

    private void appendSummary(Actor actor, UUID conversation, State state, long history, long seq) {
        UUID id = UUID.randomUUID();
        saveBody(actor, conversation, id, state.summary());
        db.sql("""
                INSERT INTO memory.conversation_summary(tenant_id,conversation_id,version,history_revision,
                   covered_through_seq,source_digest,summarizer_version,summary_ref)
                VALUES(?,?,?,?,?,?,'intent-deterministic-v1',?)
                """).params(actor.tenant(), conversation, state.revision(), history, seq - 16,
                IntentEngine.fingerprint(state.summary()), "pg:" + id).update();
    }

    private UUID recordCandidate(Actor actor, UUID conversation, UUID turn, int ordinal, Outcome outcome, boolean prior) {
        UUID candidate = UUID.randomUUID();
        String relation = !prior && !"AMBIGUOUS".equals(outcome.relation()) ? "NEW_TASK" : switch (outcome.relation()) {
            case "REPLAY", "DUPLICATE" -> "CONTINUATION";
            case "UNDO" -> "CANCEL";
            default -> outcome.relation();
        };
        UUID target = prior && !"NEW_TASK".equals(relation) ? outcome.taskId() : null;
        String status = switch (outcome.status()) {
            case "DENIED", "UNSUPPORTED" -> "FAILED";
            case "NEEDS_CLARIFICATION" -> "NEEDS_CLARIFICATION";
            default -> "APPLIED";
        };
        db.sql("""
                INSERT INTO intent.candidate(tenant_id,id,conversation_id,turn_id,ordinal,draft,relation,target_task_id,confidence,status,outcome)
                VALUES(:tenant,:id,:conversation,:turn,:ordinal,:draft::jsonb,:relation,:task,:confidence,:status,:outcome::jsonb)
                """).param("tenant", actor.tenant()).param("id", candidate).param("conversation", conversation)
                .param("turn", turn).param("ordinal", ordinal).param("draft", write(Map.of("intent", outcome.intent(), "slots", outcome.slots())))
                .param("relation", relation).param("task", target).param("confidence", outcome.assessment().matchConfidence())
                .param("status", status).param("outcome", write(outcome)).update();
        recordAssessment(actor, conversation, turn, candidate, outcome);
        return candidate;
    }

    private void recordAssessment(Actor actor, UUID conversation, UUID turn, UUID candidate, Outcome outcome) {
        Assessment assessment = outcome.assessment();
        UUID prior = null;
        if ("HYSTERESIS".equals(assessment.band()) && assessment.comparisonKey() != null && !assessment.criticalRiskOverride()) {
            prior = db.sql("""
                    SELECT id FROM intent.routing_assessment WHERE tenant_id=? AND conversation_id=?
                      AND comparison_key=? AND applied_strategy=? AND turn_id<>? AND NOT critical_risk_override
                    ORDER BY created_at DESC,id DESC LIMIT 1
                    """).params(actor.tenant(), conversation, assessment.comparisonKey(), assessment.strategy(), turn)
                    .query(UUID.class).optional().orElse(null);
        }
        db.sql("""
                INSERT INTO intent.routing_assessment(tenant_id,id,conversation_id,turn_id,candidate_id,comparison_key,prior_assessment_id,
                   complexity_score,match_confidence,applied_strategy,score_config_version,critical_risk_override,reason_codes)
                VALUES(:tenant,:id,:conversation,:turn,:candidate,:key,:prior,:score,:confidence,:strategy,'intent-zh-v1',:critical,:reasons::jsonb)
                """).param("tenant", actor.tenant()).param("id", UUID.randomUUID()).param("conversation", conversation)
                .param("turn", turn).param("candidate", candidate).param("key", assessment.comparisonKey())
                .param("prior", prior)
                .param("score", assessment.complexityScore()).param("confidence", assessment.matchConfidence())
                .param("strategy", assessment.strategy()).param("critical", assessment.criticalRiskOverride())
                .param("reasons", write(List.of(outcome.reason()))).update();
    }

    private void persistTask(Actor actor, UUID conversation, UUID turn, UUID candidate, Task task, Outcome outcome) {
        String state = switch (task.status()) {
            case "READY", "WAITING_CONFIRMATION" -> task.status();
            default -> "COLLECTING_INPUT";
        };
        Integer previous = db.sql("SELECT input_version FROM intent.task WHERE tenant_id=? AND id=?")
                .params(actor.tenant(), task.id()).query(Integer.class).optional().orElse(null);
        int version = previous == null ? 1 : previous + 1;
        if (previous == null) {
            UUID definition = registerDefinition(actor, task.intent());
            UUID intent = UUID.randomUUID();
            db.sql("""
                    INSERT INTO intent.intent_record(tenant_id,id,conversation_id,origin_turn_id,definition_id)
                    VALUES(?,?,?,?,?)
                    """).params(actor.tenant(), intent, conversation, turn, definition).update();
            db.sql("INSERT INTO intent.task(tenant_id,id,conversation_id,intent_id,status,input_version) VALUES(?,?,?,?,?,?)")
                    .params(actor.tenant(), task.id(), conversation, intent, state, version).update();
        } else {
            db.sql("UPDATE intent.task SET input_version=?,status=?,revision=revision+1,updated_at=now() WHERE tenant_id=? AND id=?")
                    .params(version, state, actor.tenant(), task.id()).update();
        }
        db.sql("""
                INSERT INTO intent.task_input(tenant_id,task_id,version,conversation_id,source_candidate_id,slots,
                  slot_provenance,slot_state,validated_ir)
                VALUES(?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,nullif(?,'null')::jsonb)
                """).params(actor.tenant(), task.id(), version, conversation, candidate, write(task.slots()),
                write(outcome.ir() == null ? Map.of() : outcome.ir().provenance()),
                write(Map.of("missing", outcome.missing(), "invalid", outcome.invalid())), write(outcome.ir())).update();
        closeClarification(actor, task.id(), "READY".equals(state) ? "ANSWERED" : "SUPERSEDED");
        if ("NEEDS_CLARIFICATION".equals(outcome.status())) {
            db.sql("""
                    INSERT INTO intent.clarification(tenant_id,id,conversation_id,source_candidate_id,task_id,input_version,
                      kind,status,question,expires_at)
                    VALUES(?,?,?,?,?,?,'SLOTS','OPEN',?::jsonb,now()+interval '30 minutes')
                    """).params(actor.tenant(), UUID.randomUUID(), conversation, candidate, task.id(), version,
                    write(Map.of("text", outcome.question(), "missing", outcome.missing(), "invalid", outcome.invalid()))).update();
        }
        db.sql("UPDATE intent.confirmation SET status=?,decided_at=now() WHERE tenant_id=? AND task_id=? AND status='PENDING'")
                .params("CONFIRM".equals(outcome.relation()) ? "CONFIRMED" : "SUPERSEDED", actor.tenant(), task.id()).update();
        if ("WAITING_CONFIRMATION".equals(state)) {
            db.sql("""
                    INSERT INTO intent.confirmation(tenant_id,id,task_id,input_version,requester_user_id,operation_hash,status,expires_at)
                    VALUES(?,?,?,?,?,?,'PENDING',now()+interval '2 minutes')
                    """).params(actor.tenant(), UUID.randomUUID(), task.id(), version, actor.user(), task.confirmationHash()).update();
        }
        if ("READY".equals(state) || "WAITING_CONFIRMATION".equals(state)) {
            db.sql("""
                    INSERT INTO intent.route_decision(tenant_id,id,task_id,input_version,policy_version,verdict,route,reasons)
                    VALUES(?,?,?,?,'intent-admission-v1',?,?,?::jsonb)
                    """).params(actor.tenant(), UUID.randomUUID(), task.id(), version,
                    "READY".equals(state) ? "ALLOW" : "REQUIRE_CONFIRMATION", outcome.route(), write(List.of(outcome.reason()))).update();
        }
    }

    private UUID registerDefinition(Actor actor, String name) {
        Definition definition = engine.definition(name);
        String[] parts = name.split("\\.", 2);
        return db.sql("""
                INSERT INTO intent.definition(tenant_id,id,domain,action,version,status,parameter_schema,route_config)
                VALUES(?,?,?,?,1,'PUBLISHED',?::jsonb,?::jsonb)
                ON CONFLICT(tenant_id,domain,action,version) DO UPDATE SET version=excluded.version RETURNING id
                """).params(actor.tenant(), UUID.randomUUID(), parts[0], parts[1],
                write(Map.of("type", "object", "required", definition.required(), "optionalFields", definition.optional())),
                write(definition)).query(UUID.class).single();
    }

    private void clearContext(Actor actor, UUID conversation) {
        db.sql("UPDATE intent.message SET redacted_at=now(),body_ref=NULL WHERE tenant_id=? AND conversation_id=? AND redacted_at IS NULL")
                .params(actor.tenant(), conversation).update();
        db.sql("""
                UPDATE intent.clarification SET status='CANCELLED'
                WHERE tenant_id=? AND conversation_id=? AND status='OPEN'
                """).params(actor.tenant(), conversation).update();
        db.sql("""
                UPDATE intent.confirmation c SET status='CANCELLED',decided_at=now()
                FROM intent.task t WHERE t.tenant_id=c.tenant_id AND t.id=c.task_id
                  AND t.tenant_id=? AND t.conversation_id=? AND c.status='PENDING'
                """).params(actor.tenant(), conversation).update();
        db.sql("""
                UPDATE intent.task SET status='CANCELLED',revision=revision+1,updated_at=now()
                WHERE tenant_id=? AND conversation_id=? AND status IN ('COLLECTING_INPUT','WAITING_CONFIRMATION','READY')
                """).params(actor.tenant(), conversation).update();
    }

    private void cancelTask(Actor actor, UUID task) {
        db.sql("UPDATE intent.task SET status='CANCELLED',revision=revision+1,updated_at=now() WHERE tenant_id=? AND id=?")
                .params(actor.tenant(), task).update();
        closeClarification(actor, task, "CANCELLED");
        db.sql("UPDATE intent.confirmation SET status='CANCELLED',decided_at=now() WHERE tenant_id=? AND task_id=? AND status='PENDING'")
                .params(actor.tenant(), task).update();
    }

    private void closeClarification(Actor actor, UUID task, String status) {
        db.sql("UPDATE intent.clarification SET status=? WHERE tenant_id=? AND task_id=? AND status='OPEN'")
                .params(status, actor.tenant(), task).update();
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("无法编码意图记录", ex); }
    }
    private <T> T read(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("无法读取意图记录", ex); }
    }
    private record Snapshot(long revision, State state, long seq, long historyRevision) {}
    private record Previous(String hash, String receipt) {}
}
