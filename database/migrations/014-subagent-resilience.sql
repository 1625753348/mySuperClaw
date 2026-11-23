BEGIN;
ALTER TABLE runtime.child_run ADD COLUMN attempt_no integer NOT NULL DEFAULT 1 CHECK (attempt_no BETWEEN 1 AND 3);
ALTER TABLE runtime.child_run DROP CONSTRAINT child_run_pkey;
ALTER TABLE runtime.child_run ADD PRIMARY KEY (tenant_id,parent_run_id,parent_step_id,attempt_no);
COMMENT ON COLUMN runtime.child_run.attempt_no IS '委派节点的业务尝试序号。同一尝试内的 Worker 重领共用一个子 Run。';
COMMIT;
