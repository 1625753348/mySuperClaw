package com.example.enterpriseagent.infrastructure.intent;

import com.example.enterpriseagent.intent.IntentModel.Actor;
import com.example.enterpriseagent.intent.IntentModel.Execution;
import com.example.enterpriseagent.intent.IntentModel.Receipt;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.example.enterpriseagent.intent.IntentArtifacts.*;

/** 读取这次识别实际写入的阶段记录，结合当前已授权的执行状态。 */
final class PostgresIntentArtifacts {
    private final JdbcClient db;
    private final ObjectMapper json;
    private final PostgresIntentDispatch dispatch;
    private final ConversationRouteJobs jobs;

    PostgresIntentArtifacts(JdbcClient db, ObjectMapper json, PostgresIntentDispatch dispatch, ConversationRouteJobs jobs) {
        this.db = db;
        this.json = json;
        this.dispatch = dispatch;
        this.jobs = jobs;
    }

    List<Execution> executions(Actor actor, UUID conversation, UUID turn) {
        var result = new ArrayList<Execution>();
        if (dispatch != null) result.addAll(dispatch.views(actor, conversation, turn));
        if (jobs != null) result.addAll(jobs.views(actor, conversation, turn));
        result.sort(java.util.Comparator.comparingInt(Execution::candidateOrdinal));
        return List.copyOf(result);
    }

    TurnArtifacts read(Actor actor, UUID conversation, Receipt receipt) {
        UUID turn = receipt.turnId();
        var request = db.sql("""
                SELECT b.body, m.reply_to_id, m.redacted_at IS NOT NULL, t.trace_input::text, t.client_key
                FROM intent.turn t
                JOIN intent.message m ON m.tenant_id=t.tenant_id AND m.turn_id=t.id AND m.role='USER'
                LEFT JOIN intent.conversation_body b ON b.tenant_id=m.tenant_id AND b.conversation_id=m.conversation_id
                  AND b.id=CASE WHEN m.body_ref LIKE 'pg:%' THEN substring(m.body_ref FROM 4)::uuid END
                WHERE t.tenant_id=? AND t.conversation_id=? AND t.id=? AND t.actor_user_id=?
                """).params(actor.tenant(), conversation, turn, actor.user())
                .query((rs, row) -> new Source(rs.getString(1), rs.getObject(2, UUID.class),
                        rs.getBoolean(3), parse(rs.getString(4)), rs.getString(5))).optional().orElse(null);
        if (request != null && request.redacted()) {
            return new TurnArtifacts(turn, "REDACTED", List.of(), List.of(), null);
        }
        boolean blocked = receipt.outcomes().stream().anyMatch(outcome -> "SECURITY.BLOCKED".equals(outcome.intent()));
        var raw = json.createObjectNode().put("conversationId", conversation.toString());
        if (request != null && request.replyTo() != null) raw.put("replyToMessageId", request.replyTo().toString());
        if (request != null && !blocked) {
            raw.put("text", request.text()).put("idempotencyKey", request.clientKey());
        }
        JsonNode observed = request == null ? null : request.observation();
        ObjectNode snapshot = observed == null || blocked ? null : contextSnapshot(actor, conversation, turn, observed);
        var intake = List.of(new Stage("RawRequest", blocked ? "REDACTED" : "PRODUCED", raw),
                stage("NormalizedInput", observed == null ? null : Map.of("text", observed.path("normalizedText").asText()),
                        blocked ? "REDACTED" : "UNAVAILABLE"));
        List<Execution> primary = dispatch == null ? List.of() : dispatch.views(actor, conversation, turn);
        List<Execution> deferred = jobs == null ? List.of() : jobs.views(actor, conversation, turn);
        var executions = new ArrayList<>(primary);
        executions.addAll(deferred);
        executions.sort(java.util.Comparator.comparingInt(Execution::candidateOrdinal));
        var rows = db.sql("""
                SELECT c.ordinal,c.draft::text,c.evidence::text,
                  CASE WHEN d.id IS NOT NULL THEN jsonb_build_object(
                    'definitionVersion',d.version,'schema',d.parameter_schema)::text END,
                  CASE WHEN ti.task_id IS NOT NULL THEN jsonb_build_object(
                    'values',ti.slots,'provenance',ti.slot_provenance)::text END,
                  ti.slot_state::text,ti.validated_ir::text,
                  CASE WHEN rd.id IS NOT NULL THEN jsonb_build_object(
                    'verdict',rd.verdict,'mode',rd.route,'policyVersion',rd.policy_version,'reasons',rd.reasons)::text END
                FROM intent.candidate c
                LEFT JOIN intent.task_input ti ON ti.tenant_id=c.tenant_id
                  AND ti.conversation_id=c.conversation_id AND ti.source_candidate_id=c.id
                LEFT JOIN intent.task task ON task.tenant_id=ti.tenant_id AND task.id=ti.task_id
                LEFT JOIN intent.intent_record ir ON ir.tenant_id=task.tenant_id AND ir.id=task.intent_id
                LEFT JOIN intent.definition d ON d.tenant_id=ir.tenant_id AND d.id=ir.definition_id
                LEFT JOIN intent.route_decision rd ON rd.tenant_id=ti.tenant_id AND rd.task_id=ti.task_id
                  AND rd.input_version=ti.version AND rd.policy_version='intent-admission-v1'
                WHERE c.tenant_id=? AND c.conversation_id=? AND c.turn_id=?
                ORDER BY c.ordinal
                """).params(actor.tenant(), conversation, turn).query((rs, ignored) ->
                new Candidate(rs.getInt(1), parse(rs.getString(2)), parse(rs.getString(3)), parse(rs.getString(4)),
                        parse(rs.getString(5)), parse(rs.getString(6)), parse(rs.getString(7)), parse(rs.getString(8)))).list();
        var candidates = new ArrayList<CandidateArtifacts>();
        for (Candidate row : rows) {
            var outcome = receipt.outcomes().get(row.ordinal());
            var detail = new ArrayList<Stage>();
            detail.add(stage("IntentCandidate", Map.of("draft", row.draft(), "assessment", outcome.assessment()), "SKIPPED"));
            detail.add(stage("ContextSnapshot", snapshot, blocked ? "SKIPPED" : "UNAVAILABLE"));
            detail.add(stage("ContextRelation", contextRelation(row.relation(), actor), "UNAVAILABLE"));
            if (outcome.taskId() == null && outcome.slots().containsKey("offset") && outcome.slots().containsKey("format")) {
                detail.add(stage("ConversationHistorySelection", outcome.slots(), "SKIPPED"));
            }
            if (outcome.workflowSkill() != null) detail.add(stage("WorkflowSkill", outcome.workflowSkill(), "SKIPPED"));
            detail.add(stage("ParameterSchema", row.schema(), "SKIPPED"));
            detail.add(stage("FilledSlots", row.slots(), "SKIPPED"));
            detail.add(stage("SlotState", row.slotsState() == null ? null :
                    Map.of("validation", row.slotsState(), "status", outcome.status(),
                            "reason", outcome.reason(), "question", Objects.toString(outcome.question(), "")), "SKIPPED"));
            detail.add(stage("ValidatedIntentIR", row.ir(), "NOT_PRODUCED"));
            Execution execution = primary.stream().filter(e -> e.candidateOrdinal() == row.ordinal()).findFirst().orElse(null);
            Execution queued = deferred.stream().filter(e -> e.candidateOrdinal() == row.ordinal()).findFirst().orElse(null);
            if (execution != null) detail.addAll(dispatch.executionArtifacts(actor, conversation, turn, execution));
            else if (queued != null) {
                detail.add(stage("RouteDecision", jobs.describe(actor, conversation, turn, row.ordinal(), queued), "NOT_PRODUCED"));
                if (queued.output() != null) detail.add(stage("RouteResult", queued.output(), "NOT_PRODUCED"));
            } else detail.add(stage("RouteDecision", row.decision(), "NOT_PRODUCED"));
            candidates.add(new CandidateArtifacts(row.ordinal(), List.copyOf(detail)));
        }
        return new TurnArtifacts(turn, "AVAILABLE", intake, List.copyOf(candidates),
                stage("Response", Map.of("reply", receipt.reply(), "executions", executions), "SKIPPED"));
    }

    private ObjectNode contextSnapshot(Actor actor, UUID conversation, UUID turn, JsonNode observed) {
        boolean canReadRuns = actor.scopes().contains("runs.read");
        var snapshot = json.createObjectNode().put("conversationId", conversation.toString()).put("currentTurnId", turn.toString());
        for (String field : List.of("snapshotRevision", "capturedAt", "taskCount", "recentTurnCount")) {
            snapshot.set(field, observed.get(field));
        }
        var counts = observed.path("contextCounts");
        if (counts.isObject()) {
            ObjectNode visible = counts.deepCopy();
            if (!canReadRuns) visible.remove("activeRunCount");
            snapshot.set("contextCounts", visible);
        }
        var memory = observed.path("memory");
        if (memory.isObject()) {
            ObjectNode visible = memory.deepCopy();
            if (!canReadRuns) visible.remove("episodeIds");
            snapshot.set("memory", visible);
        }
        var view = observed.path("contextView");
        if (!view.isObject()) return snapshot.put("detailsStatus", "NOT_CAPTURED");
        for (String field : List.of("focusTaskId", "recentIntents", "activeTasks", "pendingClarifications")) {
            snapshot.set(field, view.get(field));
        }
        if (canReadRuns) snapshot.set("activeRuns", view.get("activeRuns"));
        var messages = snapshot.putArray("recentMessages");
        var refs = view.path("recentMessages");
        var visibleBodies = messageBodies(actor, conversation, refs);
        for (JsonNode ref : refs) {
            var body = visibleBodies.get(UUID.fromString(ref.path("messageId").asText()));
            if (body == null) continue;
            ObjectNode message = ref.deepCopy();
            message.remove("chatAnswer");
            String content = ref.path("chatAnswer").asBoolean() ? body.chatAnswer() : body.original();
            if (content != null) messages.add(message.put("content", content));
        }
        return snapshot;
    }

    private Map<UUID, MessageBody> messageBodies(Actor actor, UUID conversation, JsonNode refs) {
        if (refs.isEmpty()) return Map.of();
        var ids = new ArrayList<UUID>();
        refs.forEach(ref -> ids.add(UUID.fromString(ref.path("messageId").asText())));
        return db.sql("""
                SELECT m.id,b.body,j.output->>'answer' FROM intent.message m
                JOIN intent.turn t ON t.tenant_id=m.tenant_id AND t.conversation_id=m.conversation_id
                    AND t.id=m.turn_id AND t.actor_user_id=:user AND t.trace_input IS NOT NULL
                JOIN intent.conversation_body b ON b.tenant_id=m.tenant_id AND b.conversation_id=m.conversation_id
                    AND b.id=CASE WHEN m.body_ref LIKE 'pg:%' THEN substring(m.body_ref FROM 4)::uuid END
                LEFT JOIN intent.route_job j ON j.tenant_id=m.tenant_id AND j.conversation_id=m.conversation_id
                    AND j.turn_id=m.turn_id AND j.ordinal=0 AND m.role='ASSISTANT' AND j.mode='CHAT' AND j.status='SUCCEEDED'
                WHERE m.tenant_id=:tenant AND m.conversation_id=:conversation AND m.id IN (:ids) AND m.redacted_at IS NULL
                """).param("tenant", actor.tenant()).param("conversation", conversation).param("user", actor.user()).param("ids", ids)
                .query((rs, row) -> Map.entry(rs.getObject(1, UUID.class), new MessageBody(rs.getString(2), rs.getString(3))))
                .list().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    static Stage stage(String name, Object data, String absent) {
        return new Stage(name, data == null ? absent : "PRODUCED", data);
    }

    static JsonNode parse(ObjectMapper json, String encoded) {
        if (encoded == null) return null;
        try {
            return json.readTree(encoded);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法读取意图中间产物", ex);
        }
    }

    private JsonNode parse(String encoded) {
        return parse(json, encoded);
    }

    private record Source(String text, UUID replyTo, boolean redacted, JsonNode observation, String clientKey) {}
    private JsonNode contextRelation(JsonNode evidence, Actor actor) {
        if (evidence.isEmpty()) return null;
        var relation = evidence.get(0);
        if (actor.scopes().contains("runs.read") || !relation.isObject()) return relation;
        ObjectNode visible = relation.deepCopy();
        var reasons = visible.putArray("evidence");
        relation.path("evidence").forEach(reason -> {
            if (!reason.asText().startsWith("ACTIVE_RUN:")) reasons.add(reason);
        });
        return visible;
    }

    private record MessageBody(String original, String chatAnswer) {}

    private record Candidate(int ordinal, JsonNode draft, JsonNode relation, JsonNode schema, JsonNode slots,
                             JsonNode slotsState, JsonNode ir, JsonNode decision) {}
}
