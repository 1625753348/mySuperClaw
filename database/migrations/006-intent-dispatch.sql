BEGIN;

CREATE TABLE IF NOT EXISTS intent.turn_execution (
    tenant_id text NOT NULL,
    conversation_id uuid NOT NULL,
    turn_id uuid NOT NULL,
    candidate_ordinal integer NOT NULL CHECK (candidate_ordinal >= 0),
    mode text NOT NULL CHECK (mode IN ('DIRECT', 'PLAN_AND_EXECUTE')),
    policy_version text NOT NULL,
    decision_reason text NOT NULL,
    tool_execution_id uuid,
    run_id uuid,
    blocked_reason text,
    PRIMARY KEY (tenant_id, conversation_id, turn_id, candidate_ordinal),
    FOREIGN KEY (tenant_id, conversation_id, turn_id) REFERENCES intent.turn (tenant_id, conversation_id, id),
    FOREIGN KEY (tenant_id, turn_id, candidate_ordinal) REFERENCES intent.candidate (tenant_id, turn_id, ordinal),
    FOREIGN KEY (tenant_id, tool_execution_id) REFERENCES gateway.tool_execution (tenant_id, id),
    FOREIGN KEY (tenant_id, run_id) REFERENCES runtime.run (tenant_id, id),
    CHECK (num_nonnulls(tool_execution_id, run_id, blocked_reason) = 1),
    CHECK (tool_execution_id IS NULL OR mode = 'DIRECT'),
    CHECK (run_id IS NULL OR mode = 'PLAN_AND_EXECUTE')
);
ALTER TABLE intent.turn ADD COLUMN IF NOT EXISTS trace_input jsonb CHECK (trace_input IS NULL OR jsonb_typeof(trace_input)='object');
CREATE INDEX IF NOT EXISTS task_input_source_candidate_idx ON intent.task_input (tenant_id, conversation_id, source_candidate_id);
COMMENT ON COLUMN intent.turn.trace_input IS '本轮实际的归一化文本与输入快照元数据，供授权会话查看。';
COMMENT ON TABLE intent.turn_execution IS '关联识别候选、执行目标或准入拒绝；执行正文由网关或运行时管理。';
COMMENT ON COLUMN intent.turn_execution.tenant_id IS '可信租户标识。';
COMMENT ON COLUMN intent.turn_execution.conversation_id IS '识别所在会话。';
COMMENT ON COLUMN intent.turn_execution.turn_id IS '触发派发的识别轮次。';
COMMENT ON COLUMN intent.turn_execution.candidate_ordinal IS '触发派发的候选在回执中的序号，从零开始。';
COMMENT ON COLUMN intent.turn_execution.mode IS '执行路由模式。';
COMMENT ON COLUMN intent.turn_execution.policy_version IS '执行路由策略版本。';
COMMENT ON COLUMN intent.turn_execution.decision_reason IS '路由命中已配置业务绑定的原因码。';
COMMENT ON COLUMN intent.turn_execution.tool_execution_id IS 'DIRECT 在 ToolGateway 中的执行标识。';
COMMENT ON COLUMN intent.turn_execution.run_id IS 'PLAN_AND_EXECUTE 在运行时中的 Run 标识。';
COMMENT ON COLUMN intent.turn_execution.blocked_reason IS '准入拒绝时保存的稳定原因码。';

COMMIT;
