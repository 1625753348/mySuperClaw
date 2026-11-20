BEGIN;
CREATE TABLE IF NOT EXISTS intent.route_job (
    tenant_id text NOT NULL,
    turn_id uuid NOT NULL,
    ordinal integer NOT NULL CHECK (ordinal >= 0),
    conversation_id uuid NOT NULL,
    id uuid NOT NULL,
    mode text NOT NULL CHECK (mode IN ('DIRECT','CHAT','KNOWLEDGE')),
    owner_user_id text NOT NULL,
    agent_id text NOT NULL,
    roles jsonb NOT NULL CHECK (jsonb_typeof(roles)='array'),
    spec jsonb NOT NULL CHECK (jsonb_typeof(spec)='object'),
    status text NOT NULL CHECK (status IN ('QUEUED','WAITING_DEPENDENCY','WAITING_TOOL','SUCCEEDED','FAILED','BLOCKED')),
    reason text,
    tool_execution_id uuid,
    output jsonb,
    next_at timestamptz NOT NULL DEFAULT now(),
    lease_id uuid,
    lease_until timestamptz,
    deadline timestamptz NOT NULL DEFAULT now()+interval '30 minutes',
    PRIMARY KEY (tenant_id,turn_id,ordinal),
    UNIQUE (tenant_id,id),
    FOREIGN KEY (tenant_id,conversation_id,turn_id) REFERENCES intent.turn(tenant_id,conversation_id,id),
    FOREIGN KEY (tenant_id,turn_id,ordinal) REFERENCES intent.candidate(tenant_id,turn_id,ordinal),
    FOREIGN KEY (tenant_id,tool_execution_id) REFERENCES gateway.tool_execution(tenant_id,id),
    CHECK ((lease_id IS NULL) = (lease_until IS NULL))
);
CREATE INDEX IF NOT EXISTS route_job_due_idx ON intent.route_job(next_at)
    WHERE status IN ('QUEUED','WAITING_DEPENDENCY','WAITING_TOOL');
COMMENT ON TABLE intent.route_job IS '会话候选的依赖派发、聊天回复、知识检索及可恢复状态。';
COMMENT ON COLUMN intent.route_job.spec IS '绑定的工具版本、参数来源和前置候选序号，或当前候选问题。';
COMMENT ON COLUMN intent.route_job.output IS '本地保存的聊天答复。知识与工具结果通过网关按权限读取。';
COMMIT;
