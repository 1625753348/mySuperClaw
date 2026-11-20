BEGIN;
CREATE TABLE IF NOT EXISTS intent.conversation_state (
    tenant_id text NOT NULL,
    conversation_id uuid NOT NULL,
    agent_id text NOT NULL,
    revision bigint NOT NULL DEFAULT 0 CHECK (revision>=0),
    snapshot jsonb NOT NULL CHECK (jsonb_typeof(snapshot)='object'),
    PRIMARY KEY (tenant_id,conversation_id),
    FOREIGN KEY (tenant_id,conversation_id) REFERENCES intent.conversation (tenant_id,id)
);
CREATE TABLE IF NOT EXISTS intent.conversation_body (
    tenant_id text NOT NULL,
    conversation_id uuid NOT NULL,
    id uuid NOT NULL,
    body text NOT NULL,
    PRIMARY KEY (tenant_id,conversation_id,id),
    FOREIGN KEY (tenant_id,conversation_id) REFERENCES intent.conversation (tenant_id,id)
);
COMMENT ON TABLE intent.conversation_state IS '会话工作状态与乐观锁，限定创建时的 Agent。';
COMMENT ON COLUMN intent.conversation_state.tenant_id IS '认证租户。';
COMMENT ON COLUMN intent.conversation_state.conversation_id IS '关联会话。';
COMMENT ON COLUMN intent.conversation_state.agent_id IS '创建会话的 Agent。';
COMMENT ON COLUMN intent.conversation_state.revision IS '最近提交的识别轮次。';
COMMENT ON COLUMN intent.conversation_state.snapshot IS '待办、上下文实体及压缩后的历史信号。';
COMMENT ON TABLE intent.conversation_body IS '消息与会话摘要正文，由消息或摘要表引用。';
COMMENT ON COLUMN intent.conversation_body.tenant_id IS '认证租户。';
COMMENT ON COLUMN intent.conversation_body.conversation_id IS '正文所属会话。';
COMMENT ON COLUMN intent.conversation_body.id IS '正文引用标识。';
COMMENT ON COLUMN intent.conversation_body.body IS '限制长度的正文。';
COMMIT;
