BEGIN;
CREATE TABLE runtime.monitor (
    tenant_id text NOT NULL,
    id uuid NOT NULL,
    user_id text NOT NULL,
    agent_id text NOT NULL,
    roles jsonb NOT NULL CHECK (jsonb_typeof(roles)='array'),
    spec jsonb NOT NULL CHECK (jsonb_typeof(spec)='object'),
    state text NOT NULL CHECK (state IN ('ACTIVE','PAUSED','EXPIRED')),
    reason text,
    next_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    last_seq bigint NOT NULL DEFAULT 0,
    active_seq bigint,
    consecutive_errors integer NOT NULL DEFAULT 0,
    last_signal text CHECK (last_signal IN ('NORMAL','BREACH')),
    PRIMARY KEY (tenant_id,id)
);
CREATE TABLE runtime.monitor_tick (
    tenant_id text NOT NULL,
    monitor_id uuid NOT NULL,
    seq bigint NOT NULL,
    execution_id uuid,
    state text NOT NULL CHECK (state IN ('RUNNING','NORMAL','BREACH','FAILED')),
    signal_event text CHECK (signal_event IN ('ALERT','RECOVERED')),
    reason text,
    started_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    PRIMARY KEY (tenant_id,monitor_id,seq),
    FOREIGN KEY (tenant_id,monitor_id) REFERENCES runtime.monitor (tenant_id,id),
    FOREIGN KEY (tenant_id,execution_id) REFERENCES gateway.tool_execution (tenant_id,id)
);
CREATE INDEX runtime_monitor_due ON runtime.monitor (next_at) WHERE state='ACTIVE' OR active_seq IS NOT NULL;
CREATE INDEX runtime_monitor_paused_expiry ON runtime.monitor (expires_at) WHERE state='PAUSED' AND active_seq IS NULL;
COMMENT ON TABLE runtime.monitor IS '周期监控计划，保存受控工具、触发条件、游标、截止时间和暂停原因。';
COMMENT ON TABLE runtime.monitor_tick IS '监控轮次，记录工具执行关联、阈值判断、告警及恢复。';
COMMENT ON COLUMN runtime.monitor.active_seq IS '未结算轮次的序号，暂停时仍会结算该轮次。';
COMMENT ON COLUMN runtime.monitor_tick.signal_event IS '跨过阈值时为 ALERT，恢复正常时为 RECOVERED。';
COMMIT;
