package com.example.enterpriseagent.infrastructure.gateway;

import com.example.enterpriseagent.toolgateway.execution.ToolCallbacks;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

/** 接收时锁住执行记录，与供应商任务号入库及 Worker 结算使用同一并发边界。 */
public final class PostgresToolCallbacks implements ToolCallbacks.Store {
    private final JdbcClient db;

    public PostgresToolCallbacks(JdbcClient db) {
        this.db = db;
    }

    @Override
    public ToolCallbacks.Target lockTarget(String tenant, UUID execution) {
        return db.sql("""
                SELECT p.target_ref,e.external_job_id,e.status,j.deadline_at<=now()
                FROM gateway.tool_execution e
                JOIN gateway.tool_endpoint p ON p.tenant_id=e.tenant_id AND p.id=e.endpoint_id AND p.protocol='ASYNC'
                JOIN gateway.tool_job j ON j.tenant_id=e.tenant_id AND j.execution_id=e.id
                WHERE e.tenant_id=? AND e.id=? FOR UPDATE OF e
                """).params(tenant, execution).query((rs, row) -> new ToolCallbacks.Target(rs.getString(1),
                rs.getString(2), rs.getString(3), rs.getBoolean(4))).optional().orElse(null);
    }

    @Override
    public ToolCallbacks.Identity identity(String tenant, UUID execution) {
        return db.sql("SELECT provider,payload_digest FROM gateway.async_callback WHERE tenant_id=? AND execution_id=?")
                .params(tenant, execution).query((rs, row) -> new ToolCallbacks.Identity(rs.getString(1), rs.getString(2)))
                .optional().orElse(null);
    }

    @Override
    public void insert(ToolCallbacks.Receipt receipt) {
        db.sql("""
                INSERT INTO gateway.async_callback(tenant_id,execution_id,provider,payload_digest,encrypted_payload)
                VALUES(?,?,?,?,?)
                """).params(receipt.tenant(), receipt.execution(), receipt.provider(), receipt.digest(), receipt.encrypted()).update();
    }

    @Override
    public void wake(String tenant, UUID execution) {
        db.sql("UPDATE gateway.tool_execution SET next_at=least(next_at,now()) WHERE tenant_id=? AND id=?")
                .params(tenant, execution).update();
    }

    @Override
    public String payload(String tenant, UUID execution) {
        return db.sql("SELECT encrypted_payload FROM gateway.async_callback WHERE tenant_id=? AND execution_id=?")
                .params(tenant, execution).query(String.class).optional().orElse(null);
    }
}
