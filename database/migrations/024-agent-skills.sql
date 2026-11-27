BEGIN;

CREATE SCHEMA IF NOT EXISTS agent_skill;

CREATE TABLE agent_skill.invocation (
    id uuid PRIMARY KEY,
    tenant_id text NOT NULL,
    user_id text NOT NULL,
    agent_id text NOT NULL,
    conversation_id uuid,
    request_key text NOT NULL,
    request_digest text NOT NULL CHECK (request_digest ~ '^[0-9a-f]{64}$'),
    status text NOT NULL,
    skill_name text,
    answer text,
    execution_id uuid,
    reason text,
    created_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    UNIQUE (tenant_id, user_id, agent_id, request_key),
    FOREIGN KEY (tenant_id, conversation_id) REFERENCES intent.conversation (tenant_id, id),
    FOREIGN KEY (tenant_id, execution_id) REFERENCES gateway.tool_execution (tenant_id, id)
);
COMMENT ON TABLE agent_skill.invocation IS 'Agent Skills 宿主调用、幂等指纹与最终回执。';
COMMENT ON COLUMN agent_skill.invocation.conversation_id IS '可选的已验证所属会话，用于关联审计。';
COMMENT ON COLUMN agent_skill.invocation.execution_id IS '最近的网关执行，等待审批时可查询该调用状态。';

CREATE TABLE agent_skill.load_event (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    invocation_id uuid NOT NULL REFERENCES agent_skill.invocation (id),
    skill_name text NOT NULL,
    skill_version text NOT NULL,
    content_digest text NOT NULL CHECK (content_digest ~ '^[0-9a-f]{64}$'),
    action text NOT NULL CHECK (action IN ('ACTIVATED', 'RESOURCE_READ')),
    resource_key text,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((action = 'RESOURCE_READ') = (resource_key IS NOT NULL))
);
CREATE INDEX agent_skill_load_invocation_idx ON agent_skill.load_event (invocation_id, id);
COMMENT ON TABLE agent_skill.load_event IS 'Agent Skill 激活与按需内容加载的版本审计。';

COMMIT;
