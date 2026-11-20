BEGIN;
CREATE INDEX conversation_owner_page ON intent.conversation (tenant_id, owner_user_id, created_at DESC, id DESC);
CREATE TABLE eventing.operator_action (
    tenant_id text NOT NULL,
    id uuid NOT NULL,
    operator_user_id text NOT NULL,
    action text NOT NULL CHECK (action IN ('REPLAY_EVENT','CLOSE_UNCERTAIN_RUN')),
    target_id uuid NOT NULL,
    reason text NOT NULL CHECK (length(reason) BETWEEN 1 AND 500),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id,id)
);
CREATE INDEX operator_action_target ON eventing.operator_action (tenant_id,target_id,created_at DESC);
COMMENT ON TABLE eventing.operator_action IS '运维人工操作审计，记录操作者、目标和原因。';
COMMENT ON COLUMN eventing.operator_action.operator_user_id IS '认证凭证中的运维操作者。';
COMMENT ON COLUMN eventing.operator_action.target_id IS '原 Outbox 事件或待对账 Run 标识。';
COMMENT ON COLUMN eventing.operator_action.reason IS '操作者填写的处理原因。';
COMMIT;
