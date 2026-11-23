BEGIN;
ALTER TABLE runtime.step DROP CONSTRAINT step_kind_check;
ALTER TABLE runtime.step ADD CONSTRAINT step_kind_check CHECK (kind IN ('TOOL', 'COMPUTE', 'GATE', 'SUBAGENT'));
CREATE TABLE runtime.child_run (
    tenant_id text NOT NULL,
    parent_run_id uuid NOT NULL,
    parent_step_id uuid NOT NULL,
    child_run_id uuid NOT NULL,
    subagent_key text NOT NULL,
    PRIMARY KEY (tenant_id,parent_run_id,parent_step_id),
    UNIQUE (tenant_id,child_run_id),
    FOREIGN KEY (tenant_id,parent_run_id,parent_step_id) REFERENCES runtime.step (tenant_id,run_id,id),
    FOREIGN KEY (tenant_id,child_run_id) REFERENCES runtime.run (tenant_id,id),
    CHECK (parent_run_id<>child_run_id)
);
COMMENT ON TABLE runtime.child_run IS '一个委派节点与其实际子 Run 的持久化关系。';
COMMENT ON COLUMN runtime.child_run.subagent_key IS '部署配置中被委派的 SubAgent 名称。';
COMMIT;
