BEGIN;

CREATE TABLE gateway.async_callback (
    tenant_id text NOT NULL,
    execution_id uuid NOT NULL,
    provider text NOT NULL,
    payload_digest text NOT NULL CHECK (payload_digest ~ '^[a-f0-9]{64}$'),
    encrypted_payload text NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id,execution_id),
    FOREIGN KEY (tenant_id,execution_id) REFERENCES gateway.tool_job (tenant_id,execution_id) ON DELETE CASCADE
);
COMMENT ON TABLE gateway.async_callback IS '验签后的供应商异步回调收件箱。原工具作业清理时一并删除。';
COMMENT ON COLUMN gateway.async_callback.tenant_id IS '供应商部署绑定的可信租户。';
COMMENT ON COLUMN gateway.async_callback.execution_id IS '关联原异步工具作业的执行标识。';
COMMENT ON COLUMN gateway.async_callback.provider IS '验证该回调的部署供应商标识。';
COMMENT ON COLUMN gateway.async_callback.payload_digest IS '结算载荷的 SHA-256，用于回调重放核对。';
COMMENT ON COLUMN gateway.async_callback.encrypted_payload IS 'AES-GCM 加密的终结状态与结果。';
COMMENT ON COLUMN gateway.async_callback.received_at IS '首次有效回调记录时间。';

COMMIT;
