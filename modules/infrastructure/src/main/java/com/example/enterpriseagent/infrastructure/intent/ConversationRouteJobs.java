package com.example.enterpriseagent.infrastructure.intent;

import com.example.enterpriseagent.decision.DecisionService;
import com.example.enterpriseagent.decision.DecisionService.Binding;
import com.example.enterpriseagent.infrastructure.memory.MemoryLoop;
import com.example.enterpriseagent.intent.IntentModel.Actor;
import com.example.enterpriseagent.intent.IntentModel.Execution;
import com.example.enterpriseagent.intent.IntentModel.Receipt;
import com.example.enterpriseagent.toolgateway.GatewayCaller;
import com.example.enterpriseagent.toolgateway.GatewayException;
import com.example.enterpriseagent.toolgateway.execution.ToolExecutionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

/** 持久化候选派发。短事务负责领单和结算，模型调用在事务外执行。 */
public final class ConversationRouteJobs {
    private static final String COLUMNS = """
            j.tenant_id,j.turn_id,j.ordinal,j.conversation_id,j.id,j.mode,j.owner_user_id,j.agent_id,
            j.roles::text,j.spec::text,j.status,j.reason,j.tool_execution_id,j.output::text,
            j.lease_id,j.deadline<=now()
            """;
    private static final List<String> LIVE = List.of("QUEUED", "WAITING_APPROVAL", "RUNNING", "WAITING_EXTERNAL", "RETRY_WAIT");
    private final JdbcClient db;
    private final TransactionOperations tx;
    private final ObjectMapper json;
    private final ToolExecutionService gateway;
    private final DecisionService decision;
    private final List<ConversationRoutingSettings.Knowledge> knowledge;
    private final BiFunction<String, Map<String, String>, String> chat;
    private final MemoryLoop memory;
    private final PostgresRules rules;

    public ConversationRouteJobs(JdbcClient db, TransactionOperations tx, ObjectMapper json, ToolExecutionService gateway,
                                 DecisionService decision, List<ConversationRoutingSettings.Knowledge> knowledge,
                                 BiFunction<String, Map<String, String>, String> chat, MemoryLoop memory, PostgresRules rules) {
        this.db = db;
        this.tx = tx;
        this.json = json;
        this.gateway = gateway;
        this.decision = decision;
        this.knowledge = knowledge;
        this.chat = chat;
        this.memory = memory;
        this.rules = rules;
    }

    void enqueue(Actor actor, UUID conversation, Receipt receipt, String text) {
        for (int i = 0; i < receipt.outcomes().size(); i++) {
            var dependency = decision.dependency(actor.tenant(), receipt.outcomes(), i);
            if (dependency.isEmpty()) continue;
            var target = dependency.get();
            var policy = rules.evaluate(actor, receipt.outcomes().get(i));
            String blocked = policy != null && policy.blocked() != null ? policy.blocked() : target.blocked();
            insert(actor, conversation, receipt.turnId(), i, "DIRECT", "WAITING_DEPENDENCY",
                    new Spec(target.binding(), target.predecessorOrdinal(), target.slots(), null),
                    actor.scopes().contains("tools.invoke") ? blocked : "MISSING_EXECUTION_SCOPE");
        }
        // 防止把同一轮内的其他业务片段及参数交给对话模型或知识工具。
        if (receipt.outcomes().size() != 1 || text == null || text.isBlank() || text.length() > 4096) return;
        var outcome = receipt.outcomes().getFirst();
        if (!"ROUTED".equals(outcome.status())) return;
        if ("CHAT".equals(outcome.route()) && chat != null) {
            insert(actor, conversation, receipt.turnId(), 0, "CHAT", "QUEUED",
                    new Spec(null, null, Map.of(), text), null);
        } else if ("KNOWLEDGE".equals(outcome.route())) {
            var source = knowledge.stream().filter(binding -> binding.tenant().equals(actor.tenant())).findFirst();
            boolean local = memory != null && actor.scopes().contains("memory.knowledge.read");
            if (local || memory != null && source.isEmpty()) {
                insert(actor, conversation, receipt.turnId(), 0, "KNOWLEDGE", "QUEUED",
                        new Spec(null, null, Map.of(), text), local ? null : "MISSING_KNOWLEDGE_SCOPE");
            } else if (source.isPresent()) {
                var target = source.get();
                var binding = new Binding(DecisionService.Kind.DIRECT, 1, target.toolName(), target.toolVersion(), "query");
                insert(actor, conversation, receipt.turnId(), 0, "KNOWLEDGE", "QUEUED",
                        new Spec(binding, null, Map.of("query", text), null),
                        actor.scopes().contains("tools.invoke") ? null : "MISSING_EXECUTION_SCOPE");
            }
        }
    }

    private void insert(Actor actor, UUID conversation, UUID turn, int ordinal, String mode, String status, Spec spec, String blocked) {
        db.sql("""
                INSERT INTO intent.route_job(tenant_id,turn_id,ordinal,conversation_id,id,mode,owner_user_id,
                    agent_id,roles,spec,status,reason) VALUES(:tenant,:turn,:ordinal,:conversation,:id,:mode,:user,:agent,
                    :roles::jsonb,:spec::jsonb,:status,:reason)
                """).param("tenant", actor.tenant()).param("turn", turn).param("ordinal", ordinal)
                .param("conversation", conversation).param("id", UUID.randomUUID()).param("mode", mode)
                .param("user", actor.user()).param("agent", actor.agent()).param("roles", write(actor.roles().stream().sorted().toList()))
                .param("spec", write(spec)).param("status", blocked == null ? status : "BLOCKED").param("reason", blocked).update();
    }

    @Scheduled(fixedDelayString = "${enterprise.intent.routing.poll-ms:650}")
    public void poll() {
        Optional<Job> next = db.sql("""
                WITH due AS (
                    SELECT tenant_id,id FROM intent.route_job
                    WHERE status IN ('QUEUED','WAITING_DEPENDENCY','WAITING_TOOL') AND next_at<=now()
                      AND (lease_until IS NULL OR lease_until<=now())
                    ORDER BY next_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                UPDATE intent.route_job j SET lease_id=:lease,lease_until=now()+interval '45 seconds'
                FROM due WHERE j.tenant_id=due.tenant_id AND j.id=due.id RETURNING
                """ + COLUMNS).param("lease", UUID.randomUUID()).query(this::job).optional();
        next.ifPresent(job -> {
            try {
                advance(job);
            } catch (GatewayException ex) {
                if (ex.code() == GatewayException.Code.RATE_LIMITED) finish(job, job.status(), "TOOL_RATE_LIMITED", null, 3);
                else finish(job, "BLOCKED", "TOOL_" + ex.code().name(), null, 0);
            } catch (RuntimeException ex) {
                // 端口预计可能故障。不会提交缺少证据的后续操作。
                finish(job, "FAILED", "ROUTE_JOB_FAILED", null, 0);
            }
        });
    }

    private void advance(Job job) {
        if (job.expired()) {
            finish(job, "FAILED", "ROUTE_DEADLINE", null, 0);
            return;
        }
        if ("CHAT".equals(job.mode())) {
            if (chat == null) { finish(job, "FAILED", "CHAT_MODEL_UNAVAILABLE", null, 0); return; }
            String answer = chat.apply(job.spec().question(), memory == null ? Map.of() : memory.profile(job.tenant(), job.user()));
            if (answer == null || answer.isBlank() || answer.length() > 16_000) {
                finish(job, "FAILED", "CHAT_REPLY_UNAVAILABLE", null, 0);
                return;
            }
            finish(job, "SUCCEEDED", null, write(Map.of("answer", answer)), 0);
            return;
        }
        if ("KNOWLEDGE".equals(job.mode()) && job.spec().binding() == null) {
            if (memory == null) { finish(job, "FAILED", "KNOWLEDGE_INDEX_UNAVAILABLE", null, 0); return; }
            var refs = memory.retrieve(job.tenant(), job.spec().question());
            finish(job, refs.isEmpty() ? "FAILED" : "SUCCEEDED", refs.isEmpty() ? "KNOWLEDGE_NO_EVIDENCE" : null,
                    refs.isEmpty() ? null : write(refs), 0);
            return;
        }
        var caller = new GatewayCaller(job.tenant(), job.user(), job.agent(), job.roles());
        if (job.execution() != null) {
            var result = gateway.get(caller, job.execution());
            if (LIVE.contains(result.status())) { finish(job, "WAITING_TOOL", null, null, 2); return; }
            boolean success = "SUCCEEDED".equals(result.status());
            boolean valid = !"KNOWLEDGE".equals(job.mode()) || knowledgePayload(result.output()) != null;
            finish(job, success && valid ? "SUCCEEDED" : "FAILED",
                    success ? valid ? null : "KNOWLEDGE_RESULT_INVALID" : "TOOL_" + result.status(), null, 0);
            return;
        }
        Map<String, Object> evidence = Map.of();
        if (job.spec().parent() != null) {
            Parent parent = predecessor(job);
            if (parent == null || "BLOCKED".equals(parent.status()) || "FAILED".equals(parent.status())) {
                finish(job, "BLOCKED", "DEPENDENCY_UNAVAILABLE", null, 0);
                return;
            }
            if (parent.execution() == null) { finish(job, "WAITING_DEPENDENCY", null, null, 2); return; }
            var result = gateway.get(caller, parent.execution());
            if (LIVE.contains(result.status())) { finish(job, "WAITING_DEPENDENCY", null, null, 2); return; }
            if (!"SUCCEEDED".equals(result.status()) || result.output() == null || !result.output().isObject()) {
                finish(job, "BLOCKED", "DEPENDENCY_RESULT_UNAVAILABLE", null, 0);
                return;
            }
            evidence = json.convertValue(result.output(), new TypeReference<>() {});
        }
        var plan = DecisionService.bind(job.spec().binding(), job.spec().slots(), evidence);
        if (plan.isEmpty()) { finish(job, "BLOCKED", "BOUND_FIELD_MISSING", null, 0); return; }
        tx.executeWithoutResult(ignored -> {
            boolean owns = db.sql("""
                    SELECT 1 FROM intent.route_job WHERE tenant_id=? AND id=? AND lease_id=? AND lease_until>now() FOR UPDATE
                    """).params(job.tenant(), job.id(), job.lease()).query(Integer.class).optional().isPresent();
            if (!owns) return;
            var route = plan.get();
            var request = new ToolExecutionService.Request(route.toolName(), route.toolVersion(), json.valueToTree(route.arguments()));
            var result = gateway.enqueueInTransaction(caller, request, "route:" + job.id());
            db.sql("UPDATE intent.route_job SET tool_execution_id=? WHERE tenant_id=? AND id=?")
                    .params(result.executionId(), job.tenant(), job.id()).update();
            finish(job, "WAITING_TOOL", null, null, 0);
        });
    }

    private Parent predecessor(Job job) {
        return db.sql("""
                SELECT tool_execution_id,CASE WHEN blocked_reason IS NULL THEN 'QUEUED' ELSE 'BLOCKED' END
                FROM intent.turn_execution WHERE tenant_id=:tenant AND turn_id=:turn AND candidate_ordinal=:ordinal AND mode='DIRECT'
                UNION ALL
                SELECT tool_execution_id,status FROM intent.route_job
                WHERE tenant_id=:tenant AND turn_id=:turn AND ordinal=:ordinal AND mode='DIRECT'
                """).param("tenant", job.tenant()).param("turn", job.turn()).param("ordinal", job.spec().parent())
                .query((rs, row) -> new Parent(rs.getObject(1, UUID.class), rs.getString(2))).optional().orElse(null);
    }

    private void finish(Job job, String state, String reason, String output, int delay) {
        db.sql("""
                UPDATE intent.route_job SET status=:state,reason=:reason,output=:output::jsonb,
                    next_at=now()+:delay*interval '1 second',lease_id=NULL,lease_until=NULL
                WHERE tenant_id=:tenant AND id=:id AND lease_id=:lease
                """).param("state", state).param("reason", reason).param("output", output).param("delay", delay)
                .param("tenant", job.tenant()).param("id", job.id()).param("lease", job.lease()).update();
    }

    List<Execution> views(Actor actor, UUID conversation, UUID turn) {
        return db.sql("SELECT " + COLUMNS + """
                FROM intent.route_job j WHERE j.tenant_id=? AND j.conversation_id=? AND j.turn_id=?
                    AND j.owner_user_id=? AND j.agent_id=? ORDER BY j.ordinal
                """).params(actor.tenant(), conversation, turn, actor.user(), actor.agent()).query(this::job).list().stream()
                .map(job -> view(actor, job)).toList();
    }

    Object describe(Actor actor, UUID conversation, UUID turn, int ordinal, Execution execution) {
        var result = json.createObjectNode().put("mode", execution.mode()).put("status", execution.status())
                .put("policyVersion", "conversation-route-job-v1");
        if (execution.reason() != null) result.put("reason", execution.reason());
        if (execution.targetId() == null) return result;
        db.sql("""
                SELECT spec::text FROM intent.route_job WHERE tenant_id=? AND conversation_id=? AND turn_id=?
                    AND ordinal=? AND owner_user_id=? AND agent_id=?
                """).params(actor.tenant(), conversation, turn, ordinal, actor.user(), actor.agent())
                .query(String.class).optional().map(text -> read(text, new TypeReference<Spec>() {})).ifPresent(spec -> {
                    if (spec.parent() != null) result.put("predecessorOrdinal", spec.parent());
                    if (spec.binding() != null) result.set("parameterSources", json.valueToTree(spec.binding().parameters()));
                });
        return result;
    }

    private Execution view(Actor actor, Job job) {
        if ("CHAT".equals(job.mode())) {
            return new Execution(job.ordinal(), job.mode(), job.id(), job.status(), job.reason(), null, job.output(), null, null);
        }
        if ("KNOWLEDGE".equals(job.mode()) && job.spec().binding() == null) {
            if (memory == null || !actor.scopes().contains("memory.knowledge.read")) {
                return new Execution(job.ordinal(), job.mode(), null, "BLOCKED", "MISSING_KNOWLEDGE_SCOPE", null, null, null, null);
            }
            if (!"SUCCEEDED".equals(job.status())) {
                return new Execution(job.ordinal(), job.mode(), job.id(), job.status(), job.reason(), null, null, null, null);
            }
            var refs = new java.util.ArrayList<String>();
            if (job.output() != null) job.output().forEach(ref -> refs.add(ref.asText()));
            var evidence = memory.evidence(actor.tenant(), refs);
            return new Execution(job.ordinal(), job.mode(), job.id(), evidence.isEmpty() ? "BLOCKED" : "SUCCEEDED",
                    evidence.isEmpty() ? "KNOWLEDGE_VERSION_UNAVAILABLE" : null,
                    null, evidence.isEmpty() ? null : evidence, null, null);
        }
        if (!actor.scopes().contains("tools.invoke")) {
            return new Execution(job.ordinal(), job.mode(), null, "BLOCKED", "MISSING_RESULT_SCOPE", null, null, null, null);
        }
        if (job.execution() == null) {
            return new Execution(job.ordinal(), job.mode(), job.id(), job.status(), job.reason(), null, null, null, null);
        }
        try {
            var tool = gateway.get(new GatewayCaller(actor.tenant(), actor.user(), actor.agent(), List.copyOf(actor.roles())), job.execution());
            Object output = "KNOWLEDGE".equals(job.mode()) ? knowledgePayload(tool.output()) : tool.output();
            boolean invalid = "KNOWLEDGE".equals(job.mode()) && "SUCCEEDED".equals(tool.status()) && output == null;
            boolean failed = "FAILED".equals(job.status()) || invalid;
            String reason = invalid ? "KNOWLEDGE_RESULT_INVALID" : job.reason() == null ? tool.errorCode() : job.reason();
            return new Execution(job.ordinal(), job.mode(), tool.executionId(), failed ? "FAILED" : tool.status(),
                    reason, tool.approvalId(), failed ? null : output,
                    "/v1/tools/executions/" + tool.executionId(), null);
        } catch (GatewayException ex) {
            return new Execution(job.ordinal(), job.mode(), null, "BLOCKED", "TOOL_" + ex.code().name(), null, null, null, null);
        }
    }

    private static Map<String, Object> knowledgePayload(JsonNode node) {
        if (node == null || !node.path("answer").isTextual() || node.path("answer").asText().isBlank()
                || !node.path("sources").isArray()) return null;
        return Map.of("answer", node.path("answer").asText(), "sources", node.path("sources"));
    }

    void clear(Actor actor, UUID conversation) {
        db.sql("""
                UPDATE intent.route_job SET status='BLOCKED',reason='CONTEXT_CLEARED',
                    spec='{}'::jsonb,output=NULL,tool_execution_id=NULL,lease_id=NULL,lease_until=NULL
                WHERE tenant_id=? AND conversation_id=? AND owner_user_id=? AND agent_id=?
                """).params(actor.tenant(), conversation, actor.user(), actor.agent()).update();
    }

    private Job job(ResultSet rs, int ignored) throws SQLException {
        return new Job(rs.getString(1), rs.getObject(2, UUID.class), rs.getInt(3), rs.getObject(4, UUID.class),
                rs.getObject(5, UUID.class), rs.getString(6), rs.getString(7), rs.getString(8),
                read(rs.getString(9), new TypeReference<List<String>>() {}),
                read(rs.getString(10), new TypeReference<Spec>() {}), rs.getString(11), rs.getString(12),
                rs.getObject(13, UUID.class), read(rs.getString(14), new TypeReference<JsonNode>() {}),
                rs.getObject(15, UUID.class), rs.getBoolean(16));
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("无法保存会话路由作业", ex); }
    }

    private <T> T read(String text, TypeReference<T> type) {
        if (text == null) return null;
        try { return json.readValue(text, type); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("无法读取会话路由作业", ex); }
    }

    private record Spec(Binding binding, Integer parent, Map<String, Object> slots, String question) {}
    private record Parent(UUID execution, String status) {}
    private record Job(String tenant, UUID turn, int ordinal, UUID conversation, UUID id, String mode, String user,
                       String agent, List<String> roles, Spec spec, String status, String reason, UUID execution,
                       JsonNode output, UUID lease, boolean expired) {}
}
