BEGIN;
ALTER TABLE runtime.monitor
    ADD COLUMN workflow_skill_version_id uuid,
    ADD FOREIGN KEY (tenant_id,workflow_skill_version_id) REFERENCES runtime.workflow_skill_version (tenant_id,id);

ALTER TABLE runtime.monitor_delivery
    ADD COLUMN workflow_skill_version_id uuid,
    ADD COLUMN workflow_run_id uuid,
    ADD COLUMN workflow_state text CHECK (workflow_state IN ('AWAITING_GATE','RUN_SUBMITTED','FAILED','SKIPPED')),
    ADD COLUMN workflow_reason text,
    ADD FOREIGN KEY (tenant_id,workflow_skill_version_id) REFERENCES runtime.workflow_skill_version (tenant_id,id),
    ADD FOREIGN KEY (tenant_id,workflow_run_id) REFERENCES runtime.run (tenant_id,id),
    ADD CHECK ((workflow_skill_version_id IS NULL)=(workflow_state IS NULL)),
    ADD CHECK (workflow_state IS NULL OR kind='REMEDIATE'),
    ADD CHECK ((workflow_run_id IS NOT NULL)=(workflow_state IS NOT DISTINCT FROM 'RUN_SUBMITTED'));

CREATE INDEX runtime_monitor_workflow_ready ON runtime.monitor_delivery (dispatched_at,tenant_id,monitor_id,seq)
    WHERE workflow_state='AWAITING_GATE' AND state='SUBMITTED';

COMMENT ON COLUMN runtime.monitor.workflow_skill_version_id IS '监控创建时绑定的 SOP 发布版本。';
COMMENT ON COLUMN runtime.monitor_delivery.workflow_skill_version_id IS '此轮处置审批所绑定的 SOP 版本。';
COMMENT ON COLUMN runtime.monitor_delivery.workflow_run_id IS '审批工具成功后创建的固定 SOP Run。';
COMMENT ON COLUMN runtime.monitor_delivery.workflow_state IS 'SOP 派发阶段，执行结果由关联 Run 提供。';
COMMENT ON COLUMN runtime.monitor_delivery.workflow_reason IS '审批失败、跳过或 SOP 准入失败的原因。';
COMMIT;
