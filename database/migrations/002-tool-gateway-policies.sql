BEGIN;
ALTER TABLE gateway.tool_policy DROP CONSTRAINT tool_policy_kind_check;
ALTER TABLE gateway.tool_policy ADD CONSTRAINT tool_policy_kind_check
    CHECK (kind IN ('ABAC', 'APPROVAL', 'QUOTA', 'RETRY', 'REDACTION'));
CREATE TABLE IF NOT EXISTS gateway.tool_usage_window (
    tenant_id text NOT NULL,
    tool_id uuid NOT NULL,
    scope text NOT NULL CHECK (scope IN ('PRINCIPAL', 'TENANT')),
    subject_id text NOT NULL,
    unit text NOT NULL CHECK (unit IN ('MINUTE', 'DAY')),
    window_start timestamptz NOT NULL,
    used integer NOT NULL CHECK (used > 0),
    PRIMARY KEY (tenant_id, tool_id, scope, subject_id, unit, window_start),
    FOREIGN KEY (tenant_id, tool_id) REFERENCES gateway.tool (tenant_id, id)
);
CREATE INDEX IF NOT EXISTS tool_usage_window_cleanup_idx ON gateway.tool_usage_window (window_start);
COMMENT ON TABLE gateway.tool_usage_window IS '工具限流与配额窗口，按租户、工具及计数主体隔离。';
COMMENT ON COLUMN gateway.tool_usage_window.tenant_id IS '工具所属租户。';
COMMENT ON COLUMN gateway.tool_usage_window.tool_id IS '被计数的工具 ID。';
COMMENT ON COLUMN gateway.tool_usage_window.scope IS '计数范围，按调用主体或整个租户。';
COMMENT ON COLUMN gateway.tool_usage_window.subject_id IS '主体标识，租户计数时为空字符串。';
COMMENT ON COLUMN gateway.tool_usage_window.unit IS '计数单位，分钟或 UTC 日。';
COMMENT ON COLUMN gateway.tool_usage_window.window_start IS '固定窗口起始时刻。';
COMMENT ON COLUMN gateway.tool_usage_window.used IS '窗口内成功取得执行资格的次数。';
COMMIT;
