BEGIN;
CREATE TABLE intent.predefined_rule (
    tenant_id text NOT NULL,
    intent_key text NOT NULL,
    definition_version integer NOT NULL CHECK (definition_version > 0),
    version integer NOT NULL CHECK (version > 0),
    status text NOT NULL CHECK (status IN ('DRAFT','PUBLISHED','RETIRED')),
    spec jsonb NOT NULL CHECK (jsonb_typeof(spec)='object'),
    created_by text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_by text,
    published_at timestamptz,
    PRIMARY KEY (tenant_id,intent_key,definition_version,version)
);
CREATE UNIQUE INDEX predefined_rule_active ON intent.predefined_rule (tenant_id,intent_key,definition_version)
    WHERE status='PUBLISHED';
COMMENT ON TABLE intent.predefined_rule IS '按租户与意图定义版本管理的确定性业务约束。';
COMMENT ON COLUMN intent.predefined_rule.spec IS '已校验的参数约束、追问及安全边界。';
COMMENT ON COLUMN intent.predefined_rule.published_by IS '发布者的认证用户标识。';
COMMIT;
