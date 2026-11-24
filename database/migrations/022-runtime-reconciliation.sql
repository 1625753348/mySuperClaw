BEGIN;

CREATE TABLE runtime.reconciliation_confirmation (
    tenant_id text NOT NULL,
    run_id uuid NOT NULL,
    step_id uuid NOT NULL,
    execution_id uuid NOT NULL,
    operator_user_id text NOT NULL,
    operator_agent_id text NOT NULL,
    reason text NOT NULL CHECK (length(reason) BETWEEN 1 AND 500),
    evidence_ref text NOT NULL CHECK (length(evidence_ref) BETWEEN 1 AND 300),
    request_hash text NOT NULL CHECK (length(request_hash)=64),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id,run_id,step_id),
    UNIQUE (tenant_id,execution_id),
    FOREIGN KEY (tenant_id,run_id,step_id) REFERENCES runtime.step (tenant_id,run_id,id),
    FOREIGN KEY (tenant_id,execution_id,run_id,step_id)
        REFERENCES gateway.tool_execution (tenant_id,id,run_id,step_id)
);

COMMENT ON TABLE runtime.reconciliation_confirmation IS '外部工具成功的人工确认审计与防重记录，业务结果存入加密工具作业。';
COMMENT ON COLUMN runtime.reconciliation_confirmation.tenant_id IS '运维操作者和原运行的共同租户。';
COMMENT ON COLUMN runtime.reconciliation_confirmation.run_id IS '保留原计划版本的 Run。';
COMMENT ON COLUMN runtime.reconciliation_confirmation.step_id IS '该计划中的原工具节点。';
COMMENT ON COLUMN runtime.reconciliation_confirmation.execution_id IS '只确认、不重新派发的原工具执行。';
COMMENT ON COLUMN runtime.reconciliation_confirmation.operator_user_id IS '来自认证上下文的操作者用户。';
COMMENT ON COLUMN runtime.reconciliation_confirmation.operator_agent_id IS '来自认证上下文的操作者 Agent。';
COMMENT ON COLUMN runtime.reconciliation_confirmation.reason IS '操作理由，不记录原始结果。';
COMMENT ON COLUMN runtime.reconciliation_confirmation.evidence_ref IS '外部核对凭据的编号或引用。';
COMMENT ON COLUMN runtime.reconciliation_confirmation.request_hash IS '确认内容的 SHA-256，用于识别同一操作者的重试。';
COMMENT ON COLUMN runtime.reconciliation_confirmation.created_at IS '人工确认事务时间。';

COMMIT;
