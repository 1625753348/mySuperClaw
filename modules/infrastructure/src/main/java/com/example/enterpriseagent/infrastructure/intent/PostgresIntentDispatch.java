package com.example.enterpriseagent.infrastructure.intent;

import com.example.enterpriseagent.decision.DecisionService;
import com.example.enterpriseagent.decision.DecisionService.Kind;
import com.example.enterpriseagent.decision.DecisionService.RouteDecision;
import com.example.enterpriseagent.intent.IntentArtifacts.Stage;
import com.example.enterpriseagent.intent.IntentModel.Actor;
import com.example.enterpriseagent.intent.IntentModel.Analysis;
import com.example.enterpriseagent.intent.IntentModel.Execution;
import com.example.enterpriseagent.intent.IntentModel.Outcome;
import com.example.enterpriseagent.intent.IntentModel.Receipt;
import com.example.enterpriseagent.intent.IntentModel.Request;
import com.example.enterpriseagent.infrastructure.workflow.PublishedWorkflowDag;
import com.example.enterpriseagent.runtime.RunFailure;
import com.example.enterpriseagent.runtime.RunManager;
import com.example.enterpriseagent.runtime.RunModel;
import com.example.enterpriseagent.skill.SkillRegistry;
import com.example.enterpriseagent.toolgateway.GatewayCaller;
import com.example.enterpriseagent.toolgateway.GatewayException;
import com.example.enterpriseagent.toolgateway.execution.ToolExecutionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.example.enterpriseagent.infrastructure.intent.PostgresIntentArtifacts.stage;
import static com.example.enterpriseagent.infrastructure.intent.PostgresIntentArtifacts.parse;

/** 持久保存候选与真实执行的关联。结果读取走执行方的授权接口。 */
public final class PostgresIntentDispatch {
    private final JdbcClient db;
    private final TransactionOperations savepoints;
    private final DecisionService decision;
    private final ToolExecutionService gateway;
    private final RunManager runs;
    private final SkillRegistry skills;
    private final ObjectMapper json;
    private final List<ConversationRoutingSettings.PlannerRoute> planners;

    public PostgresIntentDispatch(JdbcClient db, TransactionOperations savepoints, DecisionService decision,
                                  ToolExecutionService gateway, RunManager runs, ObjectMapper json, SkillRegistry skills,
                                  List<ConversationRoutingSettings.PlannerRoute> planners) {
        this.db = db;
        this.savepoints = savepoints;
        this.decision = decision;
        this.gateway = gateway;
        this.runs = runs;
        this.skills = skills;
        this.json = json;
        this.planners = planners;
    }

    public void enqueue(Actor actor, UUID conversation, Receipt receipt) {
        for (int ordinal = 0; ordinal < receipt.outcomes().size(); ordinal++) {
            Outcome outcome = receipt.outcomes().get(ordinal);
            var planner = generalRoute(actor, outcome);
            var dispatch = planner == null ? decision.resolve(actor.tenant(), outcome)
                    : java.util.Optional.of(new RouteDecision(Kind.PLAN_AND_EXECUTE, "general-planner-routing-v1",
                    "CONFIGURED_OPEN_INTENT", null, null, Map.of()));
            if (dispatch.isEmpty()) continue;
            var plan = dispatch.get();
            String key = "intent:" + receipt.turnId() + ":" + ordinal;
            Link link = admit(actor, plan, outcome, ordinal, key, planner);
            db.sql("""
                    INSERT INTO intent.turn_execution(tenant_id,conversation_id,turn_id,candidate_ordinal,
                        mode,policy_version,decision_reason,tool_execution_id,run_id,blocked_reason)
                    VALUES(:tenant,:conversation,:turn,:ordinal,:mode,:policy,:reason,:tool,:run,:blocked)
                    """).param("tenant", actor.tenant()).param("conversation", conversation).param("turn", receipt.turnId())
                    .param("ordinal", ordinal).param("mode", link.kind().name())
                    .param("policy", plan.policyVersion()).param("reason", plan.reason()).param("tool", link.tool())
                    .param("run", link.run()).param("blocked", link.blocked()).update();
        }
    }

    /** 仅提升已有可信 Skill 绑定的单意图请求，固定命令和普通 SOP 仍由原流程受理。 */
    Analysis composeWorkflowRoute(Actor actor, UUID conversation, Analysis analysis, Request request) {
        if (analysis.outcomes().size() != 1 || request.skillKey() != null || analysis.inputObservation() == null) return analysis;
        var outcome = analysis.outcomes().getFirst();
        var ir = outcome.ir();
        String input = analysis.inputObservation().normalizedText();
        if (input == null || input.startsWith("/") || !"WORKFLOW".equals(outcome.route()) || !"READY".equals(outcome.status())
                || ir == null || ir.workflowSkill() == null || !"NORMAL".equals(ir.riskHint())) return analysis;
        var route = planners.stream().filter(plan -> plan.tenant().equals(actor.tenant())
                && plan.intent().equals(outcome.intent()) && plan.definitionVersion() == ir.definitionVersion()
                && plan.workflowTrigger() != null).findFirst().orElse(null);
        if (route == null) return analysis;
        String trigger = route.workflowTrigger();
        if (!input.endsWith(trigger)) {
            if (!"CONTINUATION".equals(outcome.relation())) return analysis;
            // 补参轮可沿用同一 Task 的发起方式。按租户、用户与会话读取原始归一化文本。
            String original = db.sql("""
                    SELECT t.trace_input->>'normalizedText'
                    FROM intent.task a JOIN intent.intent_record i ON i.tenant_id=a.tenant_id AND i.id=a.intent_id
                    JOIN intent.turn t ON t.tenant_id=i.tenant_id AND t.id=i.origin_turn_id
                    WHERE a.tenant_id=? AND a.id=? AND a.conversation_id=? AND t.actor_user_id=?
                    """).params(actor.tenant(), outcome.taskId(), conversation, actor.user())
                    .query(String.class).optional().orElse("");
            if (original.startsWith("/") || !original.endsWith(trigger)) return analysis;
        }
        var promoted = new Outcome(outcome.intent(), outcome.relation(), outcome.taskId(), outcome.status(), "AGENT",
                outcome.skillHint(), outcome.slots(), outcome.missing(), outcome.invalid(), outcome.question(),
                "CONFIGURED_SOP_COMPOSITION", outcome.dependsOn(), outcome.assessment(), ir,
                outcome.workflowSkill(), outcome.slotProvenance());
        return new Analysis(List.of(promoted), analysis.state(), analysis.reply(), analysis.inputObservation(), analysis.classifiedIntents());
    }

    private ConversationRoutingSettings.PlannerRoute generalRoute(Actor actor, Outcome outcome) {
        var ir = outcome.ir();
        if (!"AGENT".equals(outcome.route()) || !"READY".equals(outcome.status()) || ir == null || ir.inputVersion() == null
                || !"NORMAL".equals(ir.riskHint()) || !outcome.dependsOn().isEmpty()) return null;
        return planners.stream().filter(plan -> plan.tenant().equals(actor.tenant()) && plan.intent().equals(outcome.intent())
                && plan.definitionVersion() == ir.definitionVersion()
                && (plan.workflowTrigger() == null ? ir.workflowSkill() == null : ir.workflowSkill() != null)).findFirst().orElse(null);
    }

    private Link admit(Actor actor, RouteDecision plan, Outcome outcome, int ordinal, String key,
                       ConversationRoutingSettings.PlannerRoute planner) {
        Kind kind = plan.kind();
        if (!kind.maySubmit(actor.scopes())) return new Link(ordinal, kind, null, null, "MISSING_EXECUTION_SCOPE");
        try {
            // 预期拒绝只回滚本候选的网关或 Run 受理，保留本轮识别记录。
            return savepoints.execute(ignored -> {
                JsonNode arguments = json.valueToTree(plan.arguments());
                if (kind == Kind.DIRECT) {
                    var request = new ToolExecutionService.Request(plan.toolName(), plan.toolVersion(), arguments);
                    UUID execution = gateway.enqueueInTransaction(toolCaller(actor), request, key).executionId();
                    return new Link(ordinal, kind, execution, null, null);
                }
                if (runs == null) return new Link(ordinal, kind, null, null, "RUN_PROFILE_REQUIRED");
                if (kind == Kind.WORKFLOW) {
                    UUID run = runs.enqueueFixedInTransaction(runActor(actor), fixedPlan(actor, outcome), key);
                    return new Link(ordinal, kind, null, run, null);
                }
                if (planner != null) {
                    var ir = outcome.ir();
                    UUID run = runs.enqueueGeneralInTransaction(runActor(actor), ir.taskId(), ir.inputVersion(), planner.policy(), key);
                    return new Link(ordinal, kind, null, run, null);
                }
                var source = new RunModel.Source(plan.toolName(), plan.toolVersion(), arguments);
                var request = new RunModel.Request("AGENT", "分析销售变化", List.of("region", "product", "customer"),
                        null, source, null, null);
                return new Link(ordinal, kind, null, runs.enqueueInTransaction(runActor(actor), request, key), null);
            });
        } catch (GatewayException denied) {
            return new Link(ordinal, kind, null, null, "TOOL_" + denied.code().name());
        } catch (RunFailure denied) {
            return new Link(ordinal, kind, null, null, "RUN_" + denied.code().name());
        } catch (SkillRegistry.Failure denied) {
            return new Link(ordinal, kind, null, null, "SKILL_VERSION_UNAVAILABLE");
        }
    }

    private RunModel.FixedPlan fixedPlan(Actor actor, Outcome outcome) {
        var ir = outcome.ir();
        var selection = ir.workflowSkill();
        var published = skills.get(new SkillRegistry.Actor(actor.tenant(), actor.user(), actor.agent(), List.copyOf(actor.roles())),
                selection.key(), selection.version());
        if (!selection.id().equals(published.id()) || !List.of("PUBLISHED", "RETIRED").contains(published.status())) {
            throw new SkillRegistry.Failure(409, "Skill 版本无法执行");
        }
        return new RunModel.FixedPlan(selection.id(), outcome.taskId(), ir.inputVersion(), PublishedWorkflowDag.nodes(published.dag()));
    }

    public List<Execution> views(Actor actor, UUID conversation, UUID turn) {
        var links = db.sql("""
                SELECT x.candidate_ordinal,x.mode,x.tool_execution_id,x.run_id,x.blocked_reason
                FROM intent.turn_execution x
                JOIN intent.conversation_state s ON s.tenant_id=x.tenant_id AND s.conversation_id=x.conversation_id
                JOIN intent.turn t ON t.tenant_id=x.tenant_id AND t.id=x.turn_id
                WHERE x.tenant_id=? AND x.conversation_id=? AND x.turn_id=? AND t.actor_user_id=? AND s.agent_id=?
                ORDER BY x.candidate_ordinal
                """).params(actor.tenant(), conversation, turn, actor.user(), actor.agent())
                .query((rs, row) -> new Link(rs.getInt(1), Kind.valueOf(rs.getString(2)),
                        rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getString(5))).list();
        var result = new ArrayList<Execution>(links.size());
        for (Link link : links) result.add(view(actor, link));
        return result;
    }

    List<Stage> executionArtifacts(Actor actor, UUID conversation, UUID turn, Execution execution) {
        var route = db.sql("""
                SELECT mode,policy_version,decision_reason,blocked_reason FROM intent.turn_execution
                WHERE tenant_id=? AND conversation_id=? AND turn_id=? AND candidate_ordinal=?
                """).params(actor.tenant(), conversation, turn, execution.candidateOrdinal())
                .query((rs, ignored) -> json.createObjectNode().put("mode", rs.getString(1))
                        .put("policyVersion", rs.getString(2)).put("reason", rs.getString(3))
                        .put("verdict", rs.getString(4) == null ? "ALLOW" : "DENY")
                        .put("blockedReason", rs.getString(4))).single();
        var stages = new ArrayList<Stage>();
        stages.add(stage("RouteDecision", route, "NOT_PRODUCED"));
        UUID targetId = execution.targetId();
        if (targetId == null) return stages;
        if (Kind.DIRECT.name().equals(execution.mode())) {
            stages.add(stage("ToolCall", toolMetadata(actor, targetId), "NOT_PRODUCED"));
            stages.add(stage("ToolResult", execution, "NOT_PRODUCED"));
            stages.add(stage("StateUpdate", Map.of("executionId", targetId, "status", execution.status()), "NOT_PRODUCED"));
            return stages;
        }
        var run = runs.view(runActor(actor), targetId);
        var proposals = db.sql("""
                SELECT p.id,p.base_plan_version,p.kind,p.status,p.trigger_reason,p.planner_version,
                    p.validation_result::text,b.body::text
                FROM runtime.plan_proposal p LEFT JOIN runtime.artifact_body b
                  ON b.tenant_id=p.tenant_id AND b.run_id=p.run_id AND b.artifact_id::text=p.draft_ref
                WHERE p.tenant_id=? AND p.run_id=? ORDER BY p.created_at,p.id
                """).params(actor.tenant(), targetId).query((rs, ignored) -> {
            var proposal = json.createObjectNode().put("id", rs.getString(1)).put("basePlanVersion", rs.getInt(2))
                    .put("kind", rs.getString(3)).put("status", rs.getString(4))
                    .put("triggerReason", rs.getString(5)).put("plannerVersion", rs.getString(6));
            proposal.set("validation", parse(json, rs.getString(7)));
            proposal.set("draft", parse(json, rs.getString(8)));
            return proposal;
        }).list();
        stages.add(stage("PlanProposal", proposals.isEmpty() ? null : proposals,
                Kind.WORKFLOW.name().equals(execution.mode()) ? "SKIPPED" : "PENDING"));
        var plans = db.sql("""
                SELECT version,origin,proposal_id FROM runtime.plan WHERE tenant_id=? AND run_id=? AND version<=?
                ORDER BY version
                """).params(actor.tenant(), targetId, run.planVersion())
                .query((rs, ignored) -> json.createObjectNode().put("version", rs.getInt(1)).put("origin", rs.getString(2))
                        .put("proposalId", rs.getString(3))).list();
        var graph = db.sql("""
                SELECT s.node_key,s.kind,s.status,
                       array(SELECT p.node_key FROM runtime.step_dependency d
                             JOIN runtime.step p ON p.tenant_id=d.tenant_id AND p.id=d.depends_on_step_id
                             WHERE d.tenant_id=s.tenant_id AND d.run_id=s.run_id AND d.plan_version=s.plan_version
                               AND d.step_id=s.id ORDER BY p.node_key),
                       s.spec->'skillVersion' AS skill_version,s.spec->>'skillNode' AS skill_node
                FROM runtime.step s WHERE s.tenant_id=? AND s.run_id=? AND s.plan_version=? ORDER BY s.node_key
                """).params(actor.tenant(), targetId, run.planVersion()).query((rs, ignored) -> {
            var parents = rs.getArray(4);
            try {
                return new PlanNode(rs.getString(1), rs.getString(2), rs.getString(3), List.of((String[]) parents.getArray()),
                        parse(json, rs.getString(5)), rs.getString(6));
            } finally {
                parents.free();
            }
        }).list();
        stages.add(stage("PlanVersion", Map.of("activeVersion", run.planVersion(), "versions", plans,
                "nodes", graph.stream().map(node -> {
                    var item = json.createObjectNode().put("key", node.key()).put("kind", node.kind()).put("status", node.status());
                    item.set("dependsOn", json.valueToTree(node.parents()));
                    if (node.skill() != null && node.skill().isObject()) {
                        item.set("skillVersion", node.skill());
                        item.put("skillNode", node.skillNode());
                    }
                    return item;
                }).toList()), "PENDING"));
        // 重规划可复用旧节点的产物，工具实际调用仍属于首次执行的计划版本。
        var calls = db.sql("""
                SELECT DISTINCT ON (tool_execution_id) node_key,plan_version,tool_execution_id
                FROM runtime.step WHERE tenant_id=? AND run_id=? AND tool_execution_id IS NOT NULL
                ORDER BY tool_execution_id,plan_version,node_key
                """).params(actor.tenant(), targetId).query((rs, ignored) ->
                new NodeCall(rs.getString(1), rs.getInt(2), rs.getObject(3, UUID.class))).list();
        for (NodeCall call : calls) {
            try {
                var result = gateway.get(toolCaller(actor), call.tool());
                var request = toolMetadata(actor, call.tool()).put("planVersion", call.version()).put("node", call.key());
                stages.add(stage("ToolCall", request, "NOT_PRODUCED"));
                var resultRef = json.createObjectNode().put("executionId", result.executionId().toString())
                        .put("status", result.status()).put("node", call.key())
                        .put("resultUrl", "/v1/tools/executions/" + call.tool());
                if (result.approvalId() != null) resultRef.put("approvalId", result.approvalId().toString());
                stages.add(stage("ToolResult", resultRef, "NOT_PRODUCED"));
            } catch (GatewayException denied) {
                stages.add(stage("ToolResult", null, "BLOCKED"));
            }
        }
        var checks = db.sql("""
                SELECT iteration_no,plan_version,check_key,verdict FROM runtime.gate_check
                WHERE tenant_id=? AND run_id=? ORDER BY iteration_no,check_key
                """).params(actor.tenant(), targetId).query((rs, ignored) -> Map.of(
                "iteration", rs.getInt(1), "planVersion", rs.getInt(2), "check", rs.getString(3),
                "verdict", rs.getString(4))).list();
        stages.add(stage("StateUpdate", Map.of("run", run, "gateChecks", checks), "NOT_PRODUCED"));
        return stages;
    }

    private ObjectNode toolMetadata(Actor actor, UUID execution) {
        return db.sql("""
                SELECT tool.name,v.version_name,e.trace_id FROM gateway.tool_execution e
                JOIN gateway.tool tool ON tool.tenant_id=e.tenant_id AND tool.id=e.tool_id
                JOIN gateway.tool_version v ON v.tenant_id=e.tenant_id AND v.id=e.version_id
                WHERE e.tenant_id=? AND e.id=? AND e.principal_id=? AND e.agent_id=?
                """).params(actor.tenant(), execution, actor.user(), actor.agent())
                .query((rs, ignored) -> json.createObjectNode().put("executionId", execution.toString())
                        .put("toolName", rs.getString(1)).put("toolVersion", rs.getString(2))
                        .put("traceId", rs.getString(3))).single();
    }

    private Execution view(Actor actor, Link link) {
        if (link.blocked() != null) return blocked(link, link.blocked());
        if (!link.kind().mayRead(actor.scopes())) return blocked(link, "MISSING_RESULT_SCOPE");
        try {
            if (link.kind() == Kind.DIRECT) {
                var tool = gateway.get(toolCaller(actor), link.tool());
                return new Execution(link.ordinal(), link.kind().name(), link.tool(), tool.status(), tool.errorCode(),
                        tool.approvalId(),
                        tool.output(), "/v1/tools/executions/" + link.tool(), null);
            }
            if (runs == null) return blocked(link, "RUN_PROFILE_REQUIRED");
            var run = runs.view(runActor(actor), link.run());
            return new Execution(link.ordinal(), link.kind().name(), link.run(), run.status(), run.reason(),
                    null, null, run.resultUrl(), run.eventsUrl());
        } catch (GatewayException denied) {
            return blocked(link, "TOOL_" + denied.code().name());
        } catch (RunFailure denied) {
            return blocked(link, "RUN_" + denied.code().name());
        }
    }

    private static Execution blocked(Link link, String code) {
        return new Execution(link.ordinal(), link.kind().name(), null, "BLOCKED", code, null, null, null, null);
    }

    private static GatewayCaller toolCaller(Actor actor) {
        return new GatewayCaller(actor.tenant(), actor.user(), actor.agent(), List.copyOf(actor.roles()));
    }

    private static RunModel.Actor runActor(Actor actor) {
        return new RunModel.Actor(actor.tenant(), actor.user(), actor.agent(), List.copyOf(actor.roles()));
    }

    private record Link(int ordinal, Kind kind, UUID tool, UUID run, String blocked) {}
    private record PlanNode(String key, String kind, String status, List<String> parents, JsonNode skill, String skillNode) {}
    private record NodeCall(String key, int version, UUID tool) {}
}
