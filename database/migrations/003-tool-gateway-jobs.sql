BEGIN;
ALTER TABLE gateway.tool_server DROP CONSTRAINT tool_server_protocol_check;
ALTER TABLE gateway.tool_server ADD CONSTRAINT tool_server_protocol_check
    CHECK (protocol IN ('HTTP', 'MCP', 'GRPC', 'DATABASE', 'RAG', 'SANDBOX', 'LOCAL', 'ASYNC'));
CREATE TABLE IF NOT EXISTS gateway.tool_job (
    tenant_id text NOT NULL,
    execution_id uuid NOT NULL,
    encrypted_request text,
    encrypted_result text,
    deadline_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, execution_id),
    FOREIGN KEY (tenant_id, execution_id) REFERENCES gateway.tool_execution (tenant_id, id)
);
CREATE INDEX IF NOT EXISTS tool_job_retention_idx ON gateway.tool_job (deadline_at);
COMMENT ON TABLE gateway.tool_job IS '审批等待与异步轮询使用的加密恢复数据。';
COMMENT ON COLUMN gateway.tool_job.tenant_id IS '执行所属租户。';
COMMENT ON COLUMN gateway.tool_job.execution_id IS '关联的工具执行 ID。';
COMMENT ON COLUMN gateway.tool_job.encrypted_request IS 'AES-GCM 加密的参数与已验证的角色快照。';
COMMENT ON COLUMN gateway.tool_job.encrypted_result IS 'AES-GCM 加密的脱敏结果。';
COMMENT ON COLUMN gateway.tool_job.deadline_at IS '从提交开始计算的最晚恢复或轮询时间。';
COMMIT;
