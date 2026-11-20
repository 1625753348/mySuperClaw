package com.example.enterpriseagent.infrastructure.runtime;

import com.example.enterpriseagent.runtime.RunFailure;
import com.example.enterpriseagent.runtime.RunManager;
import com.example.enterpriseagent.runtime.RuntimeOperations;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.example.enterpriseagent.runtime.RunFailure.Code.*;

/** 租户级运维使用权威表，不把业务载荷、工具凭证或工具原始结果暴露给运维列表。 */
public final class PostgresRuntimeOperations implements RuntimeOperations {
    private static final String STREAMS = "stream IN ('agent:events:run','agent:events:tool-execution')";
    private static final String DEAD = "published_at IS NULL AND attempts>=12 AND (lease_until IS NULL OR lease_until<=now())";
    private final JdbcClient db;
    private final TransactionOperations tx;
    private final RunManager runs;

    public PostgresRuntimeOperations(JdbcClient db, TransactionOperations tx, RunManager runs) {
        this.db = db;
        this.tx = tx;
        this.runs = runs;
    }

    @Override public List<Delivery> deliveries(String tenant, boolean dead, int limit) {
        validLimit(limit);
        return db.sql("""
                SELECT event_id,event_type,stream,aggregate_id,attempts,last_error FROM eventing.outbox
                WHERE tenant_id=? AND
                """ + STREAMS + " AND " + (dead ? DEAD : "published_at IS NOT NULL")
                + " ORDER BY occurred_at DESC,event_id DESC LIMIT ?").params(tenant, limit)
                .query((rs, row) -> new Delivery(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getObject(4, UUID.class), rs.getInt(5), rs.getString(6))).list();
    }

    @Override public List<UncertainRun> uncertainRuns(String tenant, int limit) {
        validLimit(limit);
        return db.sql("""
                SELECT id,task_id,terminal_reason,deadline_at FROM runtime.run
                WHERE tenant_id=? AND status='NEEDS_RECONCILIATION' ORDER BY deadline_at,id LIMIT ?
                """).params(tenant, limit).query((rs, row) -> new UncertainRun(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getString(3), rs.getObject(4, OffsetDateTime.class))).list();
    }

    @Override public void replay(String tenant, String user, UUID event, String reason) {
        String note = note(reason);
        tx.executeWithoutResult(ignored -> {
            int changed = db.sql("""
                    UPDATE eventing.outbox SET published_at=NULL,message_id=NULL,last_error=NULL,next_at=now(),
                        attempts=0,lease_owner=NULL,lease_until=NULL
                    WHERE tenant_id=? AND event_id=? AND
                    """ + STREAMS + " AND (published_at IS NOT NULL OR (" + DEAD + "))")
                    .params(tenant, event).update();
            if (changed == 0) {
                boolean exists = db.sql("SELECT 1 FROM eventing.outbox WHERE tenant_id=? AND event_id=? AND " + STREAMS)
                        .params(tenant, event).query(Integer.class).optional().isPresent();
                throw new RunFailure(exists ? CONFLICT : NOT_FOUND, "事件不存在或当前不能重投");
            }
            audit(tenant, user, "REPLAY_EVENT", event, note);
        });
    }

    @Override public void close(String tenant, String user, UUID run, String reason) {
        String note = note(reason);
        tx.executeWithoutResult(ignored -> {
            runs.closeUncertainInTransaction(tenant, run);
            audit(tenant, user, "CLOSE_UNCERTAIN_RUN", run, note);
        });
    }

    private void audit(String tenant, String user, String action, UUID target, String reason) {
        db.sql("INSERT INTO eventing.operator_action(tenant_id,id,operator_user_id,action,target_id,reason) VALUES(?,?,?,?,?,?)")
                .params(tenant, UUID.randomUUID(), user, action, target, reason).update();
    }

    private static String note(String reason) {
        if (reason == null || reason.isBlank() || reason.strip().length() > 500) {
            throw new RunFailure(INVALID, "请填写 1 到 500 字的操作原因");
        }
        return reason.strip();
    }

    private static void validLimit(int limit) {
        if (limit < 1 || limit > 50) throw new RunFailure(INVALID, "列表大小须为 1 到 50");
    }
}
