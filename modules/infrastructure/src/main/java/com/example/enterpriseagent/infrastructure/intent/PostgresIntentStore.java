package com.example.enterpriseagent.infrastructure.intent;

import com.example.enterpriseagent.intent.IntentEngine;
import com.example.enterpriseagent.intent.IntentArtifacts.ContextRelation;
import com.example.enterpriseagent.intent.IntentArtifacts.RawRequest;
import com.example.enterpriseagent.intent.IntentArtifacts.SlotState;
import com.example.enterpriseagent.intent.IntentArtifacts.TurnArtifacts;
import com.example.enterpriseagent.intent.IntentModel.*;
import com.example.enterpriseagent.intent.ResponseComposer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 同一会话按修订号串行结算。识别和可选模型调用发生在事务外。 */
public final class PostgresIntentStore implements Store {
    private final JdbcClient db;
    private final TransactionOperations transactions;
    private final IntentEngine engine;
    private final ObjectMapper json;
    private final PostgresIntentDispatch dispatch;
    private final ConversationRouteJobs jobs;
    private final ConfiguredWorkflowRoutes workflows;
    private final PostgresIntentArtifacts artifacts;
    private final ResponseComposer responses = new ResponseComposer();

    public PostgresIntentStore(JdbcClient db, TransactionOperations transactions, IntentEngine engine, ObjectMapper json,
                               PostgresIntentDispatch dispatch, ConversationRouteJobs jobs, ConfiguredWorkflowRoutes workflows) {
        this.db = db;
        this.transactions = transactions;
        this.engine = engine;
        this.json = json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.dispatch = dispatch;
        this.jobs = jobs;
        this.workflows = workflows;
        this.artifacts = new PostgresIntentArtifacts(db, this.json, dispatch, jobs);
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
            if (old != null) return withExecutions(actor, conversation, replay(old, hash));
            Request resolved = withReplyTarget(actor, conversation, request);
            Analysis analyzed = engine.analyze(new RawRequest(actor, conversation, key, resolved), snapshot.state());
            Analysis result = workflows == null ? analyzed : workflows.bind(actor, analyzed);
            Receipt saved = transactions.execute(ignored -> {
                Snapshot locked = readSnapshot(actor, conversation, true);
                Previous existing = previous(actor, conversation, key);
                if (existing != null) return replay(existing, hash);
                if (locked.revision() != snapshot.revision()) return null;
                return commit(actor, conversation, request, key, hash, locked, result);
            });
            if (saved != null) return withExecutions(actor, conversation, saved);
        }
        throw new Failure(409, "会话已被并发更新，请使用原幂等键重试");
    }

    @Override public Receipt receipt(Actor actor, UUID conversation, UUID turn) {
        readSnapshot(actor, conversation, false);
        return withExecutions(actor, conversation, readReceipt(actor, conversation, turn));
    }

    @Override public TurnArtifacts artifacts(Actor actor, UUID conversation, UUID turn) {
        readSnapshot(actor, conversation, false);
        return artifacts.read(actor, conversation, readReceipt(actor, conversation, turn));
    }

    private Receipt readReceipt(Actor actor, UUID conversation, UUID turn) {
        return db.sql("SELECT receipt::text FROM intent.turn WHERE tenant_id=? AND conversation_id=? AND id=? AND actor_user_id=?")
                .params(actor.tenant(), conversation, turn, actor.user()).query(String.class).optional()
                .map(value -> read(value, Receipt.class)).orElseThrow(() -> new Failure(404, "会话中没有该轮次"));
    }

    private Receipt withExecutions(Actor actor, UUID conversation, Receipt receipt) {
        if (dispatch == null && jobs == null) return receipt;
        return responses.withExecutions(receipt, artifacts.executions(actor, conversation, receipt.turnId()));
    }

    private Request withReplyTarget(Actor actor, UUID conversation, Request request) {
        if (request.replyToMessageId() == null) return request;
        UUID turn = db.sql("SELECT turn_id FROM intent.message WHERE tenant_id=? AND conversation_id=? AND id=? AND redacted_at IS NULL")
                .params(actor.tenant(), conversation, request.replyToMessageId()).query(UUID.class).optional()
                .orElseThrow(() -> new Failure(404, "会话中没有该回复对象"));
        List<UUID> targets = readReceipt(actor, conversation, turn).outcomes().stream().map(Outcome::taskId)
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
        State state = result.state();
        Map<UUID, Integer> inputVersions = new HashMap<>();
        for (Outcome outcome : result.outcomes()) {
            if (outcome.taskId() != null) {
                inputVersions.computeIfAbsent(outcome.taskId(), task -> inputVersion(actor, conversation, task));
            }
        }
        List<Outcome> outcomes = new ArrayList<>(result.outcomes());
        for (Task task : state.tasks()) {
            if (task.touched() != state.revision() || "CANCELLED".equals(task.status())) continue;
            int latest = latestOutcome(outcomes, task.id());
            if (latest >= 0 && outcomes.get(latest).ir() != null) {
                Outcome outcome = outcomes.get(latest);
                outcomes.set(latest, outcome.withIR(outcome.ir().atVersion(inputVersions.get(task.id()) + 1)));
            }
        }
        Receipt receipt = new Receipt(turn, reply, List.copyOf(outcomes), result.reply(), List.of());
        boolean blocked = outcomes.stream().anyMatch(outcome -> "SECURITY.BLOCKED".equals(outcome.intent()));
        db.sql("""
                INSERT INTO intent.turn(tenant_id,id,conversation_id,actor_user_id,client_key,request_hash,status,receipt,trace_input)
                VALUES(?,?,?,?,?,?,'COMPLETED',?::jsonb,nullif(?,'null')::jsonb)
                """).params(actor.tenant(), turn, conversation, actor.user(), key, hash, write(receipt),
                write(blocked ? null : result.inputObservation())).update();
        boolean cleared = previous.state().privacyRevision() != state.privacyRevision();
        if (cleared) clearContext(actor, conversation, turn);
        String body = blocked ? "[内容已拦截]" : request.text() == null ? request.skillKey() : request.text();
        appendMessage(actor, conversation, turn, UUID.randomUUID(), previous.seq() + 1, 0, "USER", body,
                cleared ? null : request.replyToMessageId());
        appendMessage(actor, conversation, turn, reply, previous.seq() + 2, 1, "ASSISTANT", result.reply(), null);
        UUID[] candidateIds = new UUID[outcomes.size()];
        for (int ordinal = 0; ordinal < candidateIds.length; ordinal++) {
            var outcome = outcomes.get(ordinal);
            int version = outcome.taskId() == null ? 0 : inputVersions.get(outcome.taskId());
            candidateIds[ordinal] = recordCandidate(actor, conversation, turn, ordinal, outcome, version,
                    result.classifiedIntents().get(ordinal));
        }
        for (Task task : state.tasks()) {
            if (task.touched() != state.revision()) continue;
            int latest = latestOutcome(outcomes, task.id());
            if ("CANCELLED".equals(task.status())) cancelTask(actor, task.id());
            else if (latest >= 0) persistTask(actor, conversation, turn, candidateIds[latest], task,
                    outcomes.get(latest), inputVersions.get(task.id()));
        }
        db.sql("UPDATE intent.conversation_state SET revision=?,snapshot=?::jsonb WHERE tenant_id=? AND conversation_id=?")
                .params(state.revision(), write(state), actor.tenant(), conversation).update();
        long historyRevision = previous.historyRevision() + (cleared ? 1 : 0);
        db.sql("UPDATE intent.conversation SET last_message_seq=?,history_revision=? WHERE tenant_id=? AND id=?")
                .params(previous.seq() + 2, historyRevision, actor.tenant(), conversation).update();
        if (!state.summary().isBlank() && !state.summary().equals(previous.state().summary())) {
            appendSummary(actor, conversation, state, historyRevision, previous.seq() + 2);
        }
        if (dispatch != null) dispatch.enqueue(actor, conversation, receipt);
        if (!blocked && jobs != null) jobs.enqueue(actor, conversation, receipt, request.text());
        return receipt;
    }

    private int inputVersion(Actor actor, UUID conversation, UUID task) {
        return db.sql("SELECT input_version FROM intent.task WHERE tenant_id=? AND conversation_id=? AND id=?")
                .params(actor.tenant(), conversation, task).query(Integer.class).optional().orElse(0);
    }

    private static int latestOutcome(List<Outcome> outcomes, UUID task) {
        int latest = outcomes.size() - 1;
        while (latest >= 0 && !task.equals(outcomes.get(latest).taskId())) latest--;
        return latest;
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

    private UUID recordCandidate(Actor actor, UUID conversation, UUID turn, int ordinal, Outcome outcome, int priorVersion,
                                 String classifiedIntent) {
        UUID candidate = UUID.randomUUID();
        boolean prior = priorVersion > 0;
        String relation = !prior && !"AMBIGUOUS".equals(outcome.relation()) ? "NEW_TASK" : switch (outcome.relation()) {
            case "REPLAY", "DUPLICATE" -> "CONTINUATION";
            case "UNDO" -> "CANCEL";
            default -> outcome.relation();
        };
        UUID target = prior && !"NEW_TASK".equals(relation) ? outcome.taskId() : null;
        var evidence = new ContextRelation(outcome.relation(), target, target == null ? null : priorVersion,
                List.of(outcome.reason()), "AMBIGUOUS".equals(relation));
        String[] intent = outcome.intent().split("\\.", 2);
        var draft = Map.of("intent", outcome.intent(), "domain", intent[0],
                "action", intent.length == 2 ? intent[1] : "", "classifierIntent", classifiedIntent == null ? "RULE" : classifiedIntent,
                "entityFields", outcome.slots().keySet().stream().sorted().toList());
        String status = switch (outcome.status()) {
            case "DENIED", "UNSUPPORTED" -> "FAILED";
            case "NEEDS_CLARIFICATION" -> "NEEDS_CLARIFICATION";
            default -> "APPLIED";
        };
        db.sql("""
                INSERT INTO intent.candidate(tenant_id,id,conversation_id,turn_id,ordinal,draft,relation,target_task_id,evidence,confidence,status,outcome)
                VALUES(:tenant,:id,:conversation,:turn,:ordinal,:draft::jsonb,:relation,:task,:evidence::jsonb,:confidence,:status,:outcome::jsonb)
                """).param("tenant", actor.tenant()).param("id", candidate).param("conversation", conversation)
                .param("turn", turn).param("ordinal", ordinal).param("draft", write(draft))
                .param("evidence", write(List.of(evidence)))
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

    private void persistTask(Actor actor, UUID conversation, UUID turn, UUID candidate, Task task, Outcome outcome, int previous) {
        if (task.workflowSkill() != null && previous > 0) ensureWorkflowIdle(actor, task.id());
        String state = switch (task.status()) {
            case "READY", "WAITING_CONFIRMATION" -> task.status();
            default -> "COLLECTING_INPUT";
        };
        int version = previous + 1;
        if (previous == 0) {
            UUID definition = task.workflowSkill() == null ? registerDefinition(actor, task.intent(), task.definitionVersion())
                    : workflowDefinition(actor, task.workflowSkill());
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
                  slot_provenance,slot_state,validated_ir,workflow_skill_version_id)
                VALUES(:tenant,:task,:version,:conversation,:candidate,:slots::jsonb,:provenance::jsonb,:state::jsonb,
                    nullif(:ir,'null')::jsonb,:skill)
                """).param("tenant", actor.tenant()).param("task", task.id()).param("version", version)
                .param("conversation", conversation).param("candidate", candidate).param("slots", write(task.slots()))
                .param("provenance", write(outcome.slotProvenance())).param("state", write(new SlotState(outcome.missing(), outcome.invalid())))
                .param("ir", write(outcome.ir())).param("skill", task.workflowSkill() == null ? null : task.workflowSkill().id()).update();
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

    private UUID workflowDefinition(Actor actor, WorkflowSkill skill) {
        return db.sql("""
                SELECT intent_definition_id FROM runtime.workflow_skill_version
                WHERE tenant_id=? AND id=? AND status IN ('PUBLISHED','RETIRED')
                """).params(actor.tenant(), skill.id()).query(UUID.class).optional()
                .orElseThrow(() -> new Failure(409, "Skill 版本已不可用"));
    }

    private UUID registerDefinition(Actor actor, String name, int version) {
        Definition definition = engine.definition(name, version);
        var schema = definition.parameterSchema();
        String[] parts = name.split("\\.", 2);
        return db.sql("""
                INSERT INTO intent.definition(tenant_id,id,domain,action,version,status,parameter_schema,route_config)
                VALUES(?,?,?,?,?,'PUBLISHED',?::jsonb,?::jsonb)
                ON CONFLICT(tenant_id,domain,action,version) DO UPDATE SET version=excluded.version RETURNING id
                """).params(actor.tenant(), UUID.randomUUID(), parts[0], parts[1], schema.definitionVersion(),
                write(Map.of("type", "object", "required", schema.required(), "optionalFields", schema.optional())),
                write(definition)).query(UUID.class).single();
    }

    private void clearContext(Actor actor, UUID conversation, UUID turn) {
        if (jobs != null) jobs.clear(actor, conversation);
        db.sql("""
                UPDATE intent.turn SET trace_input=NULL
                WHERE tenant_id=? AND conversation_id=? AND id<>? AND trace_input IS NOT NULL
                """).params(actor.tenant(), conversation, turn).update();
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
        ensureWorkflowIdle(actor, task);
        db.sql("UPDATE intent.task SET status='CANCELLED',revision=revision+1,updated_at=now() WHERE tenant_id=? AND id=?")
                .params(actor.tenant(), task).update();
        closeClarification(actor, task, "CANCELLED");
        db.sql("UPDATE intent.confirmation SET status='CANCELLED',decided_at=now() WHERE tenant_id=? AND task_id=? AND status='PENDING'")
                .params(actor.tenant(), task).update();
    }

    private void ensureWorkflowIdle(Actor actor, UUID task) {
        boolean active = db.sql("""
                SELECT EXISTS(SELECT 1 FROM runtime.run WHERE tenant_id=? AND task_id=?
                    AND finished_at IS NULL AND graph_version='published-workflow-v1')
                """).params(actor.tenant(), task).query(Boolean.class).single();
        if (active) throw new Failure(409, "工作流正在运行，请从运行入口取消或等待结束");
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
