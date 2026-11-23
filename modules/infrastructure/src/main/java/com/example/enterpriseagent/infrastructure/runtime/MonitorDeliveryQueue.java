package com.example.enterpriseagent.infrastructure.runtime;

import com.example.enterpriseagent.runtime.MonitorLoop.Delivery;
import com.example.enterpriseagent.runtime.MonitorLoop.Spec;
import com.example.enterpriseagent.runtime.MonitorLoop.SkillRef;
import com.example.enterpriseagent.runtime.MonitorLoop.ToolRef;
import com.example.enterpriseagent.runtime.MonitorLoop.WorkflowProgress;
import com.example.enterpriseagent.toolgateway.GatewayCaller;
import com.example.enterpriseagent.toolgateway.GatewayException;
import com.example.enterpriseagent.toolgateway.execution.ToolExecutionService;
import com.example.enterpriseagent.toolgateway.registry.ToolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/** 保存告警投递意图，实际副作用由 ToolGateway Worker 在事务提交后触发。 */
public final class MonitorDeliveryQueue {
    private final JdbcClient db;
    private final TransactionOperations tx;
    private final TransactionOperations savepoint;
    private final ObjectMapper json;
    private final ToolExecutionService gateway;
    private final ToolRegistry tools;
    private final List<Binding> notifications;
    private final List<Binding> remediations;

    public MonitorDeliveryQueue(JdbcClient db, TransactionOperations tx, TransactionOperations savepoint,
                                ObjectMapper json, ToolExecutionService gateway, ToolRegistry tools,
                                List<Binding> notifications, List<Binding> remediations) {
        this.db = db;
        this.tx = tx;
        this.savepoint = savepoint;
        this.json = json;
        this.gateway = gateway;
        this.tools = tools;
        this.notifications = List.copyOf(notifications);
        this.remediations = List.copyOf(remediations);
    }

    void validate(GatewayCaller caller, UUID monitor, Spec spec, MonitorWorkflowDispatch.Selection workflow) {
        if (spec.notifyWith() != null) {
            validate(caller, monitor, Kind.NOTIFY, spec.notifyWith(), "ALERT", null);
            validate(caller, monitor, Kind.NOTIFY, spec.notifyWith(), "RECOVERED", null);
        }
        if (spec.remediateWith() != null) validate(caller, monitor, Kind.REMEDIATE, spec.remediateWith(), "ALERT", workflow);
    }

    private void validate(GatewayCaller caller, UUID monitor, Kind kind, ToolRef tool, String signal,
                          MonitorWorkflowDispatch.Selection workflow) {
        requireListed(caller.tenantId(), kind, tool);
        gateway.validateQueued(caller, request(tool, monitor, 1, signal, workflow));
    }

    void record(String tenant, UUID monitor, long seq, String signal, Spec spec) {
        insert(tenant, monitor, seq, signal, Kind.NOTIFY, spec.notifyWith());
        if ("ALERT".equals(signal)) insert(tenant, monitor, seq, signal, Kind.REMEDIATE, spec.remediateWith());
    }

    private void insert(String tenant, UUID monitor, long seq, String signal, Kind kind, ToolRef tool) {
        if (tool == null) return;
        db.sql("""
                INSERT INTO runtime.monitor_delivery(tenant_id,monitor_id,seq,kind,signal_event,tool_name,tool_version,
                                                     workflow_skill_version_id,workflow_state)
                SELECT tenant_id,id,:seq,:kind,:signal,:tool,:version,
                       CASE WHEN :kind='REMEDIATE' THEN workflow_skill_version_id END,
                       CASE WHEN :kind='REMEDIATE' AND workflow_skill_version_id IS NOT NULL THEN 'AWAITING_GATE' END
                FROM runtime.monitor WHERE tenant_id=:tenant AND id=:monitor ON CONFLICT DO NOTHING
                """).param("seq", seq).param("kind", kind.name()).param("signal", signal).param("tool", tool.tool())
                .param("version", tool.version()).param("tenant", tenant).param("monitor", monitor).update();
    }

    public void advance() {
        tx.executeWithoutResult(ignored -> db.sql("""
                SELECT d.tenant_id,d.monitor_id,d.seq,d.kind,d.signal_event,d.tool_name,d.tool_version,
                       m.user_id,m.agent_id,m.roles::text,v.id,s.skill_key,v.version
                FROM runtime.monitor_delivery d JOIN runtime.monitor m ON m.tenant_id=d.tenant_id AND m.id=d.monitor_id
                LEFT JOIN runtime.workflow_skill_version v ON v.tenant_id=d.tenant_id AND v.id=d.workflow_skill_version_id
                LEFT JOIN runtime.workflow_skill s ON s.tenant_id=v.tenant_id AND s.id=v.skill_id
                WHERE d.state='PENDING' ORDER BY d.created_at,d.tenant_id,d.monitor_id,d.seq,d.kind
                LIMIT 1 FOR UPDATE OF d SKIP LOCKED
                """).query(this::job).optional().ifPresent(this::submit));
    }

    private void submit(Job job) {
        if (job.kind() == Kind.REMEDIATE && !remediationAllowed(db, job.caller().tenantId(), job.monitor())) {
            finish(job, "SKIPPED", null, "MONITOR_INACTIVE");
            return;
        }
        try {
            UUID execution = savepoint.execute(ignored -> reserve(job));
            finish(job, "SUBMITTED", execution, null);
        } catch (GatewayException ex) {
            finish(job, "FAILED", null, "TOOL_" + ex.code().name());
        }
    }

    static boolean remediationAllowed(JdbcClient db, String tenant, UUID monitor) {
        // 与暂停操作串行。自动暂停属于当前告警的配置，仍允许这次申请审批。
        return db.sql("""
                SELECT expires_at>now() AND (state='ACTIVE' OR reason='THRESHOLD_BREACHED')
                FROM runtime.monitor WHERE tenant_id=? AND id=? FOR UPDATE
                """).params(tenant, monitor).query(Boolean.class).optional().orElse(false);
    }

    private UUID reserve(Job job) {
        ToolRef ref = job.tool();
        requireListed(job.caller().tenantId(), job.kind(), ref);
        var tool = ToolRegistry.active(tools.lock(job.caller(), ref.tool()));
        if (job.kind() == Kind.NOTIFY && !"LOW".equals(tool.riskLevel())) {
            throw new GatewayException(GatewayException.Code.FORBIDDEN, "通知工具必须是低风险");
        }
        var result = gateway.enqueueInTransaction(job.caller(), request(ref, job.monitor(), job.seq(), job.signal(), job.workflow()),
                "monitor-event:" + job.monitor() + ":" + job.seq() + ":" + job.kind());
        if (job.kind() == Kind.REMEDIATE && !"WAITING_APPROVAL".equals(result.status())) {
            // 回滚保存点内的预占，防止缺少审批策略的处置进入网关队列。
            throw new GatewayException(GatewayException.Code.POLICY_REQUIRED, "监控处置必须等待人工审批");
        }
        return result.executionId();
    }

    private void finish(Job job, String state, UUID execution, String reason) {
        db.sql("""
                UPDATE runtime.monitor_delivery SET state=:state,execution_id=:execution,reason=:reason,dispatched_at=now(),
                    workflow_state=CASE WHEN workflow_state IS NOT NULL AND :state<>'SUBMITTED' THEN :state ELSE workflow_state END,
                    workflow_reason=CASE WHEN workflow_state IS NOT NULL AND :state<>'SUBMITTED' THEN :reason ELSE workflow_reason END
                WHERE tenant_id=:tenant AND monitor_id=:monitor AND seq=:seq AND kind=:kind
                """).param("state", state).param("execution", execution).param("reason", reason)
                .param("tenant", job.caller().tenantId()).param("monitor", job.monitor()).param("seq", job.seq())
                .param("kind", job.kind().name()).update();
    }

    List<Delivery> list(String tenant, UUID monitor, long before) {
        return db.sql("""
                SELECT d.seq,d.kind,d.signal_event,coalesce(e.status,d.state),d.execution_id,coalesce(e.error_code,d.reason),
                       d.workflow_skill_version_id,d.workflow_run_id,coalesce(r.status,d.workflow_state),
                       coalesce(r.terminal_reason,d.workflow_reason)
                FROM runtime.monitor_delivery d LEFT JOIN gateway.tool_execution e
                  ON e.tenant_id=d.tenant_id AND e.id=d.execution_id
                LEFT JOIN runtime.run r ON r.tenant_id=d.tenant_id AND r.id=d.workflow_run_id
                WHERE d.tenant_id=? AND d.monitor_id=? AND d.seq IN (
                    SELECT DISTINCT seq FROM runtime.monitor_delivery WHERE tenant_id=? AND monitor_id=? AND seq<?
                    ORDER BY seq DESC LIMIT 25)
                ORDER BY d.seq DESC,d.kind
                """).params(tenant, monitor, tenant, monitor, before)
                .query((rs, row) -> new Delivery(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getObject(5, UUID.class), rs.getString(6), workflowProgress(rs))).list();
    }

    private static WorkflowProgress workflowProgress(ResultSet rs) throws SQLException {
        UUID skill = rs.getObject(7, UUID.class);
        if (skill == null) return null;
        UUID run = rs.getObject(8, UUID.class);
        String status = rs.getString(9);
        if ("AWAITING_GATE".equals(status) && "WAITING_APPROVAL".equals(rs.getString(4))) status = "WAITING_APPROVAL";
        return new WorkflowProgress(skill, run, status, rs.getString(10),
                run != null && "SUCCEEDED".equals(status) ? "/v1/runs/" + run + "/result" : null);
    }

    private Job job(ResultSet rs, int row) throws SQLException {
        try {
            var caller = new GatewayCaller(rs.getString(1), rs.getString(8), rs.getString(9),
                    json.readValue(rs.getString(10), new TypeReference<List<String>>() {}));
            UUID skill = rs.getObject(11, UUID.class);
            var workflow = skill == null ? null : new MonitorWorkflowDispatch.Selection(skill, new SkillRef(rs.getString(12), rs.getInt(13)));
            return new Job(caller, rs.getObject(2, UUID.class), rs.getLong(3), Kind.valueOf(rs.getString(4)),
                    rs.getString(5), new ToolRef(rs.getString(6), rs.getString(7)), workflow);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法读取监控调用主体", ex);
        }
    }

    private void requireListed(String tenant, Kind kind, ToolRef tool) {
        if (!(kind == Kind.NOTIFY ? notifications : remediations).contains(new Binding(tenant, tool.tool(), tool.version()))) {
            throw new GatewayException(GatewayException.Code.FORBIDDEN, "工具版本不在监控投递名单");
        }
    }

    private ToolExecutionService.Request request(ToolRef ref, UUID monitor, long seq, String signal,
                                                 MonitorWorkflowDispatch.Selection workflow) {
        var input = json.createObjectNode().put("monitorId", monitor.toString()).put("tickSeq", seq).put("signal", signal);
        if (workflow != null) input.put("skillKey", workflow.ref().skillKey()).put("skillVersion", workflow.ref().version())
                .put("skillVersionId", workflow.id().toString());
        return new ToolExecutionService.Request(ref.tool(), ref.version(), input);
    }

    private enum Kind { NOTIFY, REMEDIATE }
    private record Job(GatewayCaller caller, UUID monitor, long seq, Kind kind, String signal, ToolRef tool,
                       MonitorWorkflowDispatch.Selection workflow) {}
    public record Binding(String tenant, String tool, String version) {}
}
