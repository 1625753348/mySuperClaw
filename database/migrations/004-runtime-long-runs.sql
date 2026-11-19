BEGIN;
CREATE TABLE IF NOT EXISTS runtime.run_submission (
    tenant_id text NOT NULL,
    user_id text NOT NULL,
    agent_id text NOT NULL,
    request_key text NOT NULL,
    run_id uuid NOT NULL,
    request_hash text NOT NULL,
    roles jsonb NOT NULL CHECK (jsonb_typeof(roles) = 'array'),
    PRIMARY KEY (tenant_id, user_id, agent_id, request_key),
    UNIQUE (tenant_id, run_id),
    FOREIGN KEY (tenant_id, run_id) REFERENCES runtime.run (tenant_id, id) DEFERRABLE INITIALLY DEFERRED
);
COMMENT ON TABLE runtime.run_submission IS '结构化长任务入口的幂等回执和授权角色快照。';
COMMENT ON COLUMN runtime.run_submission.tenant_id IS '经过认证的租户标识。';
COMMENT ON COLUMN runtime.run_submission.user_id IS '经过认证的调用主体。';
COMMENT ON COLUMN runtime.run_submission.agent_id IS '经过认证的代理标识。';
COMMENT ON COLUMN runtime.run_submission.request_key IS '调用方提交的幂等键。';
COMMENT ON COLUMN runtime.run_submission.run_id IS '受理的运行标识。';
COMMENT ON COLUMN runtime.run_submission.request_hash IS '规范化请求的摘要。';
COMMENT ON COLUMN runtime.run_submission.roles IS '发起时验证的角色快照，工作器仍使用实时工具授权。';

CREATE TABLE IF NOT EXISTS runtime.artifact_body (
    tenant_id text NOT NULL,
    run_id uuid NOT NULL,
    artifact_id uuid NOT NULL,
    body jsonb NOT NULL,
    PRIMARY KEY (tenant_id, run_id, artifact_id),
    FOREIGN KEY (tenant_id, run_id, artifact_id) REFERENCES runtime.context_artifact (tenant_id, run_id, id)
);
COMMENT ON TABLE runtime.artifact_body IS '首期小型运行产物正文，检查点保存引用。';
COMMENT ON COLUMN runtime.artifact_body.tenant_id IS '运行所属租户。';
COMMENT ON COLUMN runtime.artifact_body.run_id IS '产物所属运行。';
COMMENT ON COLUMN runtime.artifact_body.artifact_id IS '对应产物的版本化元数据标识。';
COMMENT ON COLUMN runtime.artifact_body.body IS '受大小限制的结构化运行产物正文。';
COMMIT;
