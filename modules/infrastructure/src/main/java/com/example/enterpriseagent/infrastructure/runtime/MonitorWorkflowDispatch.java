package com.example.enterpriseagent.infrastructure.runtime;

import com.example.enterpriseagent.infrastructure.workflow.PublishedWorkflowDag;
import com.example.enterpriseagent.intent.IntentModel.WorkflowSkill;
import com.example.enterpriseagent.intent.ValidatedIntentIR;
import com.example.enterpriseagent.runtime.MonitorLoop.SkillRef;
import com.example.enterpriseagent.runtime.MonitorLoop.Spec;
import com.example.enterpriseagent.runtime.MonitorLoop.ToolRef;
import com.example.enterpriseagent.runtime.RunFailure;
import com.example.enterpriseagent.runtime.RunManager;
import com.example.enterpriseagent.runtime.RunModel.Actor;
import com.example.enterpriseagent.runtime.RunModel.FixedPlan;
import com.example.enterpriseagent.skill.SkillRegistry;
import com.example.enterpriseagent.toolgateway.registry.ToolContracts;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.example.enterpriseagent.runtime.RunFailure.Code.*;

/** 审批工具成功结算后，以系统结构化入口启动已固定的 SOP。 */
public final class MonitorWorkflowDispatch {
    private final JdbcClient db;
    private final TransactionOperations tx;
    private final TransactionOperations savepoint;
    private final ObjectMapper json;
    private final SkillRegistry skills;
    private final ToolContracts contracts;
    private final RunManager runs;
    private final List<Binding> allowlist;

    public MonitorWorkflowDispatch(JdbcClient db, TransactionOperations tx, TransactionOperations savepoint,
                                   ObjectMapper json, SkillRegistry skills, ToolContracts contracts,
                                   RunManager runs, List<Binding> allowlist) {
        this.db = db;
        this.tx = tx;
        this.savepoint = savepoint;
        this.json = json;
        this.skills = skills;
        this.contracts = contracts;
        this.runs = runs;
        this.allowlist = List.copyOf(allowlist);
    }

    Selection pin(Actor actor, UUID monitor, Spec spec) {
        SkillRef ref = spec.remediationSop();
        if (ref == null) return null;
        if (spec.remediateWith() == null || ref.skillKey() == null || !ref.skillKey().matches("[a-z][a-z0-9.-]{2,79}")
                || ref.version() == null || ref.version() < 1) {
            throw new RunFailure(INVALID, "监控 SOP 需要审批工具和明确的 Skill Key、版本");
        }
        var published = requireWorkflow(actor, ref, spec.remediateWith(), arguments(monitor, 1));
        return new Selection(published.id(), ref);
    }

    public void advance() {
        tx.executeWithoutResult(ignored -> db.sql("""
                SELECT d.tenant_id,d.monitor_id,d.seq,d.workflow_skill_version_id,d.tool_name,d.tool_version,
                       s.skill_key,v.version,m.user_id,m.agent_id,m.roles::text,e.status,
                       EXISTS(SELECT 1 FROM gateway.tool_approval a WHERE a.tenant_id=e.tenant_id AND a.execution_id=e.id
                         AND a.version_id=e.version_id AND a.operation_hash=e.operation_hash AND a.status='APPROVED')
                FROM runtime.monitor_delivery d
                JOIN runtime.monitor m ON m.tenant_id=d.tenant_id AND m.id=d.monitor_id
                JOIN runtime.workflow_skill_version v ON v.tenant_id=d.tenant_id AND v.id=d.workflow_skill_version_id
                JOIN runtime.workflow_skill s ON s.tenant_id=v.tenant_id AND s.id=v.skill_id
                JOIN gateway.tool_execution e ON e.tenant_id=d.tenant_id AND e.id=d.execution_id
                WHERE d.workflow_state='AWAITING_GATE' AND d.state='SUBMITTED'
                  AND e.status IN ('SUCCEEDED','FAILED','REJECTED','EXPIRED','BLOCKED','CANCELLED','UNKNOWN')
                ORDER BY d.dispatched_at,d.tenant_id,d.monitor_id,d.seq LIMIT 1 FOR UPDATE OF d SKIP LOCKED
                """).query(this::job).optional().ifPresent(this::submit));
    }

    private void submit(Job job) {
        String tenant = job.actor().tenant();
        if (!"SUCCEEDED".equals(job.gateStatus()) || !job.approved()) {
            finish(job, "FAILED", null, "GATE_" + ("SUCCEEDED".equals(job.gateStatus()) ? "APPROVAL_MISSING" : job.gateStatus()));
            return;
        }
        if (!MonitorDeliveryQueue.remediationAllowed(db, tenant, job.monitor())) {
            finish(job, "SKIPPED", null, "MONITOR_INACTIVE");
            return;
        }
        try {
            UUID run = savepoint.execute(ignored -> enqueue(job));
            finish(job, "RUN_SUBMITTED", run, null);
        } catch (RunFailure failure) {
            // 保留网关审批的原始执行记录，独立记录 SOP 准入失败。
            finish(job, "FAILED", null, "WORKFLOW_" + failure.code().name());
        }
    }

    private UUID enqueue(Job job) {
        Actor actor = job.actor();
        Map<String, Object> args = arguments(job.monitor(), job.seq());
        var published = requireWorkflow(actor, job.selection().ref(), job.gate(), args);
        if (!published.id().equals(job.selection().id())) throw new RunFailure(CONFLICT, "已固定的 SOP 版本不可用");
        String key = "monitor-sop:" + job.monitor() + ":" + job.seq();
        UUID task = UUID.nameUUIDFromBytes((actor.tenant() + ":" + key).getBytes(StandardCharsets.UTF_8));
        String[] definition = published.dag().path("intentDefinition").asText().split("[.@]");
        int version = Integer.parseInt(definition[2]);
        var ir = new ValidatedIntentIR(definition[0], definition[1], version, task, args,
                Map.of("monitorId", "MONITOR", "tickSeq", "MONITOR", "signal", "MONITOR"), 1, "NORMAL",
                ValidatedIntentIR.ResponseMode.PROGRESS_AND_RESULT, new WorkflowSkill(published.id(),
                published.skillKey(), published.version(), version), null);
        insertTask(actor, key, ir);
        return runs.enqueueFixedInTransaction(actor, new FixedPlan(published.id(), task, 1,
                PublishedWorkflowDag.nodes(published.dag())), key);
    }

    private void insertTask(Actor actor, String key, ValidatedIntentIR ir) {
        String input = json.valueToTree(ir).toString();
        // 固定工作流复用标准 Task → 已校验 IR → ALLOW → Run 的准入链。
        int created = db.sql("""
                WITH definition AS (
                    SELECT v.intent_definition_id AS id FROM runtime.workflow_skill_version v
                    JOIN intent.definition d ON d.tenant_id=v.tenant_id AND d.id=v.intent_definition_id
                    WHERE v.tenant_id=:tenant AND v.id=:skill AND d.status='PUBLISHED'
                ), thread AS (
                    INSERT INTO intent.conversation(tenant_id,id,owner_user_id) VALUES(:tenant,:thread,:user)
                    RETURNING id
                ), turn_entry AS (
                    INSERT INTO intent.turn(tenant_id,id,conversation_id,actor_user_id,client_key,request_hash,status)
                    SELECT :tenant,:turn,id,:user,:key,:digest,'COMPLETED' FROM thread RETURNING id,conversation_id
                ), intent_entry AS (
                    INSERT INTO intent.intent_record(tenant_id,id,conversation_id,origin_turn_id,definition_id)
                    SELECT :tenant,:intent,t.conversation_id,t.id,d.id FROM turn_entry t CROSS JOIN definition d
                    RETURNING id,conversation_id
                ), candidate AS (
                    INSERT INTO intent.candidate(tenant_id,id,conversation_id,turn_id,ordinal,draft,relation,status)
                    SELECT :tenant,:candidate,conversation_id,id,0,'{"source":"monitor-sop"}','NEW_TASK','APPLIED'
                    FROM turn_entry RETURNING id
                ), task AS (
                    INSERT INTO intent.task(tenant_id,id,conversation_id,intent_id,status,input_version)
                    SELECT :tenant,:task,conversation_id,id,'READY',1 FROM intent_entry RETURNING id,conversation_id
                ), input AS (
                    INSERT INTO intent.task_input(tenant_id,task_id,version,conversation_id,source_candidate_id,
                                                  workflow_skill_version_id,slots,validated_ir)
                    SELECT :tenant,t.id,1,t.conversation_id,c.id,:skill,CAST(:ir AS jsonb)->'arguments',CAST(:ir AS jsonb)
                    FROM task t CROSS JOIN candidate c RETURNING task_id
                )
                INSERT INTO intent.route_decision(tenant_id,id,task_id,input_version,policy_version,verdict,route)
                SELECT :tenant,:decision,task_id,1,'monitor-sop-v1','ALLOW','WORKFLOW' FROM input
                """).param("tenant", actor.tenant()).param("skill", ir.workflowSkill().id())
                .param("thread", UUID.randomUUID()).param("user", actor.user()).param("turn", UUID.randomUUID())
                .param("key", key).param("digest", ToolContracts.digest(input)).param("intent", UUID.randomUUID())
                .param("candidate", UUID.randomUUID()).param("task", ir.taskId()).param("ir", input)
                .param("decision", UUID.randomUUID()).update();
        if (created != 1) throw new RunFailure(CONFLICT, "监控 SOP 关联的意图定义不可用");
    }

    private SkillRegistry.Published requireWorkflow(Actor actor, SkillRef ref, ToolRef gate, Map<String, Object> args) {
        if (!allowlist.contains(new Binding(actor.tenant(), ref.skillKey(), ref.version(), gate.tool(), gate.version()))) {
            throw new RunFailure(FORBIDDEN, "此租户的 SOP 版本与审批工具组合未获授权");
        }
        SkillRegistry.Published published;
        try {
            published = skills.get(new SkillRegistry.Actor(actor.tenant(), actor.user(), actor.agent(), actor.roles()),
                    ref.skillKey(), ref.version());
        } catch (SkillRegistry.Failure ex) {
            throw new RunFailure(CONFLICT, "找不到指定的已发布监控 SOP");
        }
        if (!List.of("PUBLISHED", "RETIRED").contains(published.status())
                || !"workflow.read".equals(published.dag().path("admissionPolicy").asText())) {
            throw new RunFailure(FORBIDDEN, "监控 SOP 必须是已发布的只读工作流");
        }
        if (!contracts.matches(published.dag().path("inputSchema").toString(), json.valueToTree(args))) {
            throw new RunFailure(INVALID, "监控信号与 SOP 的入参 Schema 不匹配");
        }
        return published;
    }

    private void finish(Job job, String state, UUID run, String reason) {
        db.sql("""
                UPDATE runtime.monitor_delivery SET workflow_state=?,workflow_run_id=?,workflow_reason=?
                WHERE tenant_id=? AND monitor_id=? AND seq=? AND kind='REMEDIATE'
                """).params(state, run, reason, job.actor().tenant(), job.monitor(), job.seq()).update();
    }

    private Job job(ResultSet rs, int row) throws SQLException {
        try {
            var actor = new Actor(rs.getString(1), rs.getString(9), rs.getString(10),
                    json.readValue(rs.getString(11), new TypeReference<List<String>>() {}));
            return new Job(actor, rs.getObject(2, UUID.class), rs.getLong(3), new Selection(rs.getObject(4, UUID.class),
                    new SkillRef(rs.getString(7), rs.getInt(8))), new ToolRef(rs.getString(5), rs.getString(6)),
                    rs.getString(12), rs.getBoolean(13));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法恢复监控执行主体", ex);
        }
    }

    private static Map<String, Object> arguments(UUID monitor, long seq) {
        return Map.of("monitorId", monitor.toString(), "tickSeq", seq, "signal", "ALERT");
    }

    private record Job(Actor actor, UUID monitor, long seq, Selection selection, ToolRef gate, String gateStatus,
                       boolean approved) {}
    record Selection(UUID id, SkillRef ref) {}
    public record Binding(String tenant, String skillKey, int version, String approvalTool, String approvalVersion) {}
}
