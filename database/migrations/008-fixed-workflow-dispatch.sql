BEGIN;
ALTER TABLE intent.turn_execution DROP CONSTRAINT turn_execution_mode_check;
ALTER TABLE intent.turn_execution DROP CONSTRAINT turn_execution_check2;
ALTER TABLE intent.turn_execution ADD CONSTRAINT turn_execution_mode_check
    CHECK (mode IN ('DIRECT', 'PLAN_AND_EXECUTE', 'WORKFLOW'));
ALTER TABLE intent.turn_execution ADD CONSTRAINT turn_execution_check2
    CHECK (run_id IS NULL OR mode IN ('PLAN_AND_EXECUTE', 'WORKFLOW'));
COMMIT;
