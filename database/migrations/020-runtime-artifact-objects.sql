BEGIN;

ALTER TABLE runtime.context_artifact
    ADD COLUMN object_attempts bigint NOT NULL DEFAULT 0 CHECK (object_attempts >= 0),
    ADD COLUMN object_next_at timestamptz NOT NULL DEFAULT now();
CREATE INDEX context_artifact_object_due ON runtime.context_artifact (object_next_at, created_at)
    WHERE content_ref LIKE 'pg:%' AND kind IN ('TOOL_RESULT','OBSERVATION','REPORT')
        AND artifact_key NOT IN ('gate','acceptance');

COMMENT ON COLUMN runtime.context_artifact.content_ref IS 'pg: 指向内联正文，obj: 指向私有对象键，类型切换不改变产物 ID。';
COMMENT ON COLUMN runtime.context_artifact.object_attempts IS '对象卸载领取代次，阻止已失效的领取者切换正文引用。';
COMMENT ON COLUMN runtime.context_artifact.object_next_at IS '下次可卸载时间。已领取时临时保存租约截止时间。';
COMMENT ON TABLE runtime.artifact_body IS '旧版或小型产物的 JSON 正文，也是对象卸载确认前的持久正文。';

COMMIT;
