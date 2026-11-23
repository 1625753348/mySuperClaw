package com.example.enterpriseagent.infrastructure.runtime;

import com.example.enterpriseagent.runtime.MonitorLoop;
import com.example.enterpriseagent.runtime.RunFailure;
import com.example.enterpriseagent.runtime.RunModel.Actor;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.example.enterpriseagent.runtime.RunFailure.Code.*;

/** 监控游标、轮次和工具预占在同一短事务提交。所有调用继续走 ToolGateway。 */
public final class PostgresMonitorLoop implements MonitorLoop {
    private static final String VIEW = """
            SELECT id,spec->>'tool',spec->>'version',state,reason,next_at,expires_at,last_seq,active_seq,last_signal
            FROM runtime.monitor
            """;
    private static final Set<String> WAITING = Set.of("QUEUED", "RUNNING", "WAITING_EXTERNAL", "RETRY_WAIT", "WAITING_APPROVAL");
    private final JdbcClient db;
    private final TransactionOperations tx;
    private final TransactionOperations savepoint;
    private final ObjectMapper json;
    private final ToolExecutionService gateway;
    private final ToolRegistry tools;
    private final List<Binding> allowlist;
    private final MonitorDeliveryQueue deliveryQueue;

    public PostgresMonitorLoop(JdbcClient db, TransactionOperations tx, TransactionOperations savepoint, ObjectMapper json,
                               ToolExecutionService gateway, ToolRegistry tools, List<Binding> allowlist,
                               MonitorDeliveryQueue deliveryQueue) {
        this.db = db;
        this.tx = tx;
        this.savepoint = savepoint;
        this.json = json;
        this.gateway = gateway;
        this.tools = tools;
        this.allowlist = List.copyOf(allowlist);
        this.deliveryQueue = deliveryQueue;
    }

    @Override public View create(Actor actor, UUID id, Spec spec) {
        validate(spec);
        if (!listed(actor.tenant(), spec)) throw new RunFailure(FORBIDDEN, "工具未获监控授权");
        String encoded = json.valueToTree(spec).toString();
        try {
            // 网关自行占用工具锁校验契约，先结束它的事务再进入监控受理。
            gateway.validateQueued(caller(actor), request(spec));
            deliveryQueue.validate(caller(actor), id, spec);
        } catch (GatewayException ex) {
            throw admissionFailure(ex);
        }
        return tx.execute(ignored -> {
            try {
                requireTool(caller(actor), spec);
            } catch (GatewayException ex) {
                throw admissionFailure(ex);
            }
            int inserted = db.sql("""
                    INSERT INTO runtime.monitor(tenant_id,id,user_id,agent_id,roles,spec,state,expires_at)
                    VALUES(?,?,?,?,?::jsonb,?::jsonb,'ACTIVE',now()+?*interval '1 second') ON CONFLICT DO NOTHING
                    """).params(actor.tenant(), id, actor.user(), actor.agent(), json.valueToTree(actor.roles()).toString(),
                    encoded, spec.lifetimeSeconds()).update();
            if (inserted == 0) {
                boolean same = db.sql("""
                        SELECT spec=?::jsonb FROM runtime.monitor WHERE tenant_id=? AND id=? AND user_id=? AND agent_id=?
                        """).params(encoded, actor.tenant(), id, actor.user(), actor.agent()).query(Boolean.class).optional()
                        .orElseThrow(PostgresMonitorLoop::notFound);
                if (!same) throw new RunFailure(CONFLICT, "监控标识已用于其他配置");
            }
            return get(actor, id);
        });
    }

    @Override public View get(Actor actor, UUID id) {
        return db.sql(VIEW + " WHERE tenant_id=? AND user_id=? AND agent_id=? AND id=?")
                .params(actor.tenant(), actor.user(), actor.agent(), id).query(this::view).optional()
                .orElseThrow(PostgresMonitorLoop::notFound);
    }

    @Override public List<View> list(Actor actor) {
        return db.sql(VIEW + " WHERE tenant_id=? AND user_id=? AND agent_id=? ORDER BY next_at DESC,id LIMIT 50")
                .params(actor.tenant(), actor.user(), actor.agent()).query(this::view).list();
    }

    @Override public List<Tick> history(Actor actor, UUID id, long before) {
        get(actor, id);
        return db.sql("""
                SELECT seq,execution_id,state,signal_event,reason,started_at,finished_at FROM runtime.monitor_tick
                WHERE tenant_id=? AND monitor_id=? AND seq<? ORDER BY seq DESC LIMIT 50
                """).params(actor.tenant(), id, before).query(this::tick).list();
    }

    @Override public List<Delivery> deliveries(Actor actor, UUID id, long before) {
        get(actor, id);
        return deliveryQueue.list(actor.tenant(), id, before);
    }

    @Override public View setPaused(Actor actor, UUID id, boolean paused) {
        return tx.execute(ignored -> {
            var existing = get(actor, id);
            if (paused ? "MANUAL_PAUSE".equals(existing.reason()) : "ACTIVE".equals(existing.state())) return existing;
            int changed = db.sql("""
                    UPDATE runtime.monitor SET state=:state,reason=:reason,next_at=now(),consecutive_errors=0
                    WHERE tenant_id=:tenant AND id=:id AND user_id=:user AND agent_id=:agent AND expires_at>now()
                      AND state<>'EXPIRED' AND (:paused OR active_seq IS NULL)
                    """).param("state", paused ? "PAUSED" : "ACTIVE")
                    .param("reason", paused ? "MANUAL_PAUSE" : null).param("tenant", actor.tenant()).param("id", id)
                    .param("user", actor.user()).param("agent", actor.agent()).param("paused", paused).update();
            if (changed != 1) throw new RunFailure(CONFLICT, "监控已过期或已有轮次正在结算");
            return get(actor, id);
        });
    }

    public void advance() {
        tx.executeWithoutResult(ignored -> db.sql("""
                SELECT tenant_id,id,user_id,agent_id,roles::text,spec::text,expires_at<=now(),
                       last_seq,active_seq,consecutive_errors,last_signal
                FROM runtime.monitor
                WHERE ((state='ACTIVE' OR active_seq IS NOT NULL) AND next_at<=now())
                   OR (state='PAUSED' AND active_seq IS NULL AND expires_at<=now())
                ORDER BY next_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                """).query(this::monitor).optional().ifPresent(this::advance));
    }

    private void advance(Monitor monitor) {
        if (monitor.active() != null) {
            settle(monitor);
        } else if (monitor.expired()) {
            db.sql("UPDATE runtime.monitor SET state='EXPIRED',reason='MONITOR_EXPIRED' WHERE tenant_id=? AND id=?")
                    .params(monitor.tenant(), monitor.id()).update();
        } else {
            submit(monitor);
        }
    }

    private void submit(Monitor m) {
        long seq = m.lastSeq() + 1;
        ToolExecutionService.Result admitted;
        try {
            admitted = savepoint.execute(ignored -> {
                requireTool(m.caller(), m.spec());
                return gateway.enqueueInTransaction(m.caller(), request(m.spec()), "monitor:" + m.id() + ":" + seq);
            });
        } catch (GatewayException error) {
            db.sql("INSERT INTO runtime.monitor_tick(tenant_id,monitor_id,seq,state,reason,finished_at) VALUES(?,?,?,'FAILED',?,now())")
                    .params(m.tenant(), m.id(), seq, "TOOL_" + error.code().name()).update();
            record(m, seq, null, "TOOL_" + error.code().name(), error.code() != GatewayException.Code.RATE_LIMITED);
            return;
        }
        db.sql("INSERT INTO runtime.monitor_tick(tenant_id,monitor_id,seq,execution_id,state) VALUES(?,?,?,?,'RUNNING')")
                .params(m.tenant(), m.id(), seq, admitted.executionId()).update();
        db.sql("UPDATE runtime.monitor SET last_seq=?,active_seq=?,next_at=now()+interval '1 second' WHERE tenant_id=? AND id=?")
                .params(seq, seq, m.tenant(), m.id()).update();
    }

    private void settle(Monitor m) {
        Tick tick = db.sql("""
                SELECT seq,execution_id,state,signal_event,reason,started_at,finished_at FROM runtime.monitor_tick
                WHERE tenant_id=? AND monitor_id=? AND seq=?
                """).params(m.tenant(), m.id(), m.active()).query(this::tick).single();
        ToolExecutionService.Result observed;
        try {
            observed = savepoint.execute(ignored -> gateway.get(m.caller(), tick.executionId()));
        } catch (GatewayException error) {
            finish(m, null, "TOOL_" + error.code().name(), true);
            return;
        }
        if (WAITING.contains(observed.status())) {
            boolean approval = "WAITING_APPROVAL".equals(observed.status());
            db.sql("""
                    UPDATE runtime.monitor SET next_at=now()+interval '1 second',
                        state=CASE WHEN expires_at<=now() THEN 'EXPIRED' WHEN ? AND state='ACTIVE' THEN 'PAUSED' ELSE state END,
                        reason=CASE WHEN expires_at<=now() THEN 'MONITOR_EXPIRED'
                                    WHEN ? AND state='ACTIVE' THEN 'APPROVAL_REQUIRED' ELSE reason END
                    WHERE tenant_id=? AND id=?
                    """).params(approval, approval, m.tenant(), m.id()).update();
            return;
        }
        if (!"SUCCEEDED".equals(observed.status())) {
            finish(m, null, "TOOL_" + observed.status(), Set.of("UNKNOWN","REJECTED","CANCELLED","EXPIRED").contains(observed.status()));
            return;
        }
        var field = observed.output() == null ? null : observed.output().path(m.spec().field());
        if (field == null || !field.isNumber()) {
            finish(m, null, "METRIC_UNAVAILABLE", false);
            return;
        }
        int comparison = field.decimalValue().compareTo(m.spec().threshold());
        boolean breach = m.spec().comparison() == Comparison.ABOVE ? comparison > 0 : comparison < 0;
        finish(m, breach ? "BREACH" : "NORMAL", null, breach && m.spec().action() == Action.PAUSE);
    }

    private void finish(Monitor m, String signal, String reason, boolean pause) {
        String event = "BREACH".equals(signal) && !"BREACH".equals(m.signal()) ? "ALERT"
                : "NORMAL".equals(signal) && "BREACH".equals(m.signal()) ? "RECOVERED" : null;
        db.sql("UPDATE runtime.monitor_tick SET state=?,signal_event=?,reason=?,finished_at=now() WHERE tenant_id=? AND monitor_id=? AND seq=?")
                .params(signal == null ? "FAILED" : signal, event, reason, m.tenant(), m.id(), m.active()).update();
        if (event != null) deliveryQueue.record(m.tenant(), m.id(), m.active(), event, m.spec());
        record(m, m.active(), signal, reason, pause);
    }

    private void record(Monitor m, long seq, String signal, String error, boolean pause) {
        int errors = signal == null ? m.errors() + 1 : 0;
        boolean shouldPause = pause || errors >= 3;
        String reason = error != null ? error : shouldPause ? "THRESHOLD_BREACHED" : null;
        db.sql("""
                UPDATE runtime.monitor SET last_seq=:seq,active_seq=NULL,last_signal=coalesce(:signal,last_signal),
                    consecutive_errors=:errors,next_at=least(expires_at,now()+:seconds*interval '1 second'),
                    state=CASE WHEN expires_at<=now() THEN 'EXPIRED' WHEN :pause THEN 'PAUSED' ELSE state END,
                    reason=CASE WHEN expires_at<=now() THEN 'MONITOR_EXPIRED' WHEN reason='MANUAL_PAUSE' THEN reason
                                WHEN :pause THEN :reason
                                WHEN state='PAUSED' THEN reason ELSE :reason END
                WHERE tenant_id=:tenant AND id=:id
                """).param("seq", seq).param("signal", signal).param("errors", errors).param("seconds", m.spec().everySeconds())
                .param("pause", shouldPause).param("reason", reason).param("tenant", m.tenant()).param("id", m.id()).update();
    }

    private void requireTool(GatewayCaller caller, Spec spec) {
        if (!listed(caller.tenantId(), spec)
                || !"LOW".equals(ToolRegistry.active(tools.lock(caller, spec.tool())).riskLevel())) {
            throw new GatewayException(GatewayException.Code.FORBIDDEN, "工具未获监控授权");
        }
    }

    private boolean listed(String tenant, Spec spec) {
        return allowlist.contains(new Binding(tenant, spec.tool(), spec.version()));
    }

    private void validate(Spec s) {
        if (s == null || s.tool() == null || !s.tool().matches("[A-Za-z0-9_-]{1,64}")
                || s.version() == null || !s.version().matches("[A-Za-z0-9._-]{1,48}")
                || s.arguments() == null || !s.arguments().isObject() || s.arguments().toString().length() > 16_384
                || s.field() == null || !s.field().matches("[A-Za-z][A-Za-z0-9_]{0,63}")
                || s.comparison() == null || s.threshold() == null || s.threshold().precision() > 32
                || Math.abs((long) s.threshold().scale()) > 32
                || s.action() == null || s.everySeconds() < 5 || s.everySeconds() > 86_400
                || s.lifetimeSeconds() < s.everySeconds() || s.lifetimeSeconds() > 2_592_000) {
            throw new RunFailure(INVALID, "监控配置无效");
        }
    }

    private Monitor monitor(ResultSet rs, int row) throws SQLException {
        try {
            var caller = new GatewayCaller(rs.getString(1), rs.getString(3), rs.getString(4), json.readValue(rs.getString(5),
                    new TypeReference<List<String>>() {}));
            return new Monitor(rs.getString(1), rs.getObject(2, UUID.class), caller, json.readValue(rs.getString(6), Spec.class),
                    rs.getBoolean(7), rs.getLong(8), rs.getObject(9, Long.class), rs.getInt(10), rs.getString(11));
        } catch (JsonProcessingException error) { throw new IllegalStateException("无法读取监控配置", error); }
    }

    private View view(ResultSet rs, int row) throws SQLException {
        return new View(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getObject(6, OffsetDateTime.class), rs.getObject(7, OffsetDateTime.class),
                rs.getLong(8), rs.getObject(9, Long.class), rs.getString(10));
    }

    private Tick tick(ResultSet rs, int row) throws SQLException {
        return new Tick(rs.getLong(1), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getObject(6, OffsetDateTime.class), rs.getObject(7, OffsetDateTime.class));
    }

    private static GatewayCaller caller(Actor actor) { return new GatewayCaller(actor.tenant(), actor.user(), actor.agent(), actor.roles()); }
    private static ToolExecutionService.Request request(Spec spec) { return new ToolExecutionService.Request(spec.tool(), spec.version(), spec.arguments()); }
    private static RunFailure notFound() { return new RunFailure(NOT_FOUND, "监控不存在"); }
    private static RunFailure admissionFailure(GatewayException failure) {
        return new RunFailure(failure.code() == GatewayException.Code.INVALID ? INVALID : FORBIDDEN,
                "监控工具契约、权限或配置不可用");
    }

    private record Monitor(String tenant, UUID id, GatewayCaller caller, Spec spec, boolean expired, long lastSeq,
                           Long active, int errors, String signal) {}
    public record Binding(String tenant, String tool, String version) {}
}
