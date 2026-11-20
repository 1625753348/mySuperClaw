BEGIN;
ALTER TABLE runtime.workflow_skill_version ADD COLUMN IF NOT EXISTS source_markdown text;
COMMENT ON COLUMN runtime.workflow_skill_version.source_markdown IS '发布时保留的 SKILL.md 原文。';
COMMIT;
