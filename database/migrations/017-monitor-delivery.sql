BEGIN;
CREATE TABLE runtime.monitor_delivery (
    tenant_id text NOT NULL,
    monitor_id uuid NOT NULL,
    seq bigint NOT NULL,
    kind text NOT NULL CHECK (kind IN ('NOTIFY','REMEDIATE')),
    signal_event text NOT NULL CHECK (signal_event IN ('ALERT','RECOVERED')),
    tool_name text NOT NULL,
    tool_version text NOT NULL,
    state text NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','SUBMITTED','FAILED','SKIPPED')),
    execution_id uuid,
    reason text,
    created_at timestamptz NOT NULL DEFAULT now(),
    dispatched_at timestamptz,
    PRIMARY KEY (tenant_id,monitor_id,seq,kind),
    FOREIGN KEY (tenant_id,monitor_id,seq) REFERENCES runtime.monitor_tick (tenant_id,monitor_id,seq),
    FOREIGN KEY (tenant_id,execution_id) REFERENCES gateway.tool_execution (tenant_id,id),
    CHECK (kind<>'REMEDIATE' OR signal_event='ALERT'),
    CHECK ((state='SUBMITTED')=(execution_id IS NOT NULL)),
    CHECK ((state='PENDING')=(dispatched_at IS NULL))
);
CREATE INDEX runtime_monitor_delivery_pending ON runtime.monitor_delivery (created_at,tenant_id,monitor_id,seq,kind)
    WHERE state='PENDING';
COMMENT ON TABLE runtime.monitor_delivery IS '监控轮次产生的通知或处置登记，受理后以工具执行记录跟踪审批和结果。';
COMMENT ON COLUMN runtime.monitor_delivery.tenant_id IS '可信租户标识。';
COMMENT ON COLUMN runtime.monitor_delivery.monitor_id IS '来源监控标识。';
COMMENT ON COLUMN runtime.monitor_delivery.seq IS '来源监控轮次序号。';
COMMENT ON COLUMN runtime.monitor_delivery.kind IS 'NOTIFY 为通知，REMEDIATE 为提交处置审批。';
COMMENT ON COLUMN runtime.monitor_delivery.signal_event IS 'ALERT 为进入异常，RECOVERED 为恢复正常。';
COMMENT ON COLUMN runtime.monitor_delivery.tool_name IS '创建监控时选定的工具名。';
COMMENT ON COLUMN runtime.monitor_delivery.tool_version IS '创建监控时选定的工具版本。';
COMMENT ON COLUMN runtime.monitor_delivery.state IS '投递受理状态，工具实际运行状态由网关提供。';
COMMENT ON COLUMN runtime.monitor_delivery.execution_id IS '网关受理后关联的工具执行标识。';
COMMENT ON COLUMN runtime.monitor_delivery.reason IS '投递失败或跳过的原因码。';
COMMENT ON COLUMN runtime.monitor_delivery.created_at IS '信号与投递登记的事务时间。';
COMMENT ON COLUMN runtime.monitor_delivery.dispatched_at IS '网关受理、跳过或失败的登记时间。';
COMMIT;
