-- PostgreSQL design draft. Load into an empty review database.
-- Application services generate UUIDs, bind trusted tenant scope, and enforce transitions.
BEGIN;

CREATE SCHEMA intent;
CREATE SCHEMA runtime;
CREATE SCHEMA gateway;
CREATE SCHEMA eventing;
CREATE SCHEMA memory;

CREATE TABLE intent.conversation (
    tenant_id text NOT NULL,
    id uuid NOT NULL,
    owner_user_id text NOT NULL,
    last_message_seq bigint NOT NULL DEFAULT 0 CHECK (last_message_seq >= 0),
    history_revision bigint NOT NULL DEFAULT 0 CHECK (history_revision >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id)
);

CREATE TABLE intent.conversation_state (
    tenant_id text NOT NULL,
    conversation_id uuid NOT NULL,
    agent_id text NOT NULL,
    revision bigint NOT NULL DEFAULT 0 CHECK (revision>=0),
    snapshot jsonb NOT NULL CHECK (jsonb_typeof(snapshot)='object'),
    PRIMARY KEY (tenant_id,conversation_id),
    FOREIGN KEY (tenant_id,conversation_id) REFERENCES intent.conversation (tenant_id,id)
);
CREATE TABLE intent.conversation_body (
    tenant_id text NOT NULL,
    conversation_id uuid NOT NULL,
    id uuid NOT NULL,
    body text NOT NULL,
    PRIMARY KEY (tenant_id,conversation_id,id),
    FOREIGN KEY (tenant_id,conversation_id) REFERENCES intent.conversation (tenant_id,id)
);
COMMENT ON TABLE intent.conversation_state IS '会话工作状态与乐观锁，限定创建时的 Agent。';
COMMENT ON COLUMN intent.conversation_state.tenant_id IS '认证租户。';
COMMENT ON COLUMN intent.conversation_state.conversation_id IS '关联会话。';
COMMENT ON COLUMN intent.conversation_state.agent_id IS '创建会话的 Agent。';
COMMENT ON COLUMN intent.conversation_state.revision IS '最近提交的识别轮次。';
COMMENT ON COLUMN intent.conversation_state.snapshot IS '待办、上下文实体及压缩后的历史信号。';
COMMENT ON TABLE intent.conversation_body IS '消息与会话摘要正文，由消息或摘要表引用。';
COMMENT ON COLUMN intent.conversation_body.tenant_id IS '认证租户。';
COMMENT ON COLUMN intent.conversation_body.conversation_id IS '正文所属会话。';
COMMENT ON COLUMN intent.conversation_body.id IS '正文引用标识。';
COMMENT ON COLUMN intent.conversation_body.body IS '限制长度的正文。';

CREATE TABLE intent.turn (
    tenant_id text NOT NULL,
    id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    actor_user_id text NOT NULL,
    client_key text NOT NULL,
    request_hash text NOT NULL,
    status text NOT NULL CHECK (status IN ('QUEUED', 'CLASSIFYING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    next_at timestamptz NOT NULL DEFAULT now(),
    lease_owner text,
    lease_until timestamptz,
    lease_epoch bigint NOT NULL DEFAULT 0,
    attempts integer NOT NULL DEFAULT 0,
    receipt jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, conversation_id, id),
    UNIQUE (tenant_id, conversation_id, actor_user_id, client_key),
    FOREIGN KEY (tenant_id, conversation_id) REFERENCES intent.conversation (tenant_id, id),
    CHECK ((lease_owner IS NULL) = (lease_until IS NULL)),
    CHECK (lease_epoch >= 0 AND attempts >= 0)
);
CREATE INDEX turn_due_idx ON intent.turn (next_at, lease_until)
    WHERE status IN ('QUEUED', 'CLASSIFYING', 'PROCESSING');

CREATE TABLE intent.message (
    tenant_id text NOT NULL,
    id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    turn_id uuid NOT NULL,
    seq bigint NOT NULL CHECK (seq > 0),
    role text NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    position integer NOT NULL CHECK (position >= 0),
    body_ref text,
    redacted_at timestamptz,
    reply_to_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, conversation_id, id),
    UNIQUE (tenant_id, conversation_id, seq),
    UNIQUE (tenant_id, turn_id, position),
    FOREIGN KEY (tenant_id, conversation_id, turn_id)
        REFERENCES intent.turn (tenant_id, conversation_id, id),
    FOREIGN KEY (tenant_id, conversation_id, reply_to_id)
        REFERENCES intent.message (tenant_id, conversation_id, id),
    CHECK (reply_to_id IS DISTINCT FROM id),
    CHECK ((redacted_at IS NULL) = (body_ref IS NOT NULL))
);
-- The unique message sequence index also supports reverse-ordered window reads.

CREATE TABLE memory.conversation_summary (
    tenant_id text NOT NULL,
    conversation_id uuid NOT NULL,
    version bigint NOT NULL CHECK (version > 0),
    history_revision bigint NOT NULL CHECK (history_revision >= 0),
    covered_through_seq bigint NOT NULL CHECK (covered_through_seq > 0),
    source_digest text NOT NULL,
    summarizer_version text NOT NULL,
    summary_ref text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, conversation_id, version),
    UNIQUE (tenant_id, conversation_id, history_revision, covered_through_seq),
    FOREIGN KEY (tenant_id, conversation_id) REFERENCES intent.conversation (tenant_id, id)
);

CREATE TABLE intent.definition (
    tenant_id text NOT NULL,
    id uuid NOT NULL,
    domain text NOT NULL,
    action text NOT NULL,
    version integer NOT NULL CHECK (version > 0),
    status text NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    is_current boolean NOT NULL DEFAULT false,
    parameter_schema jsonb NOT NULL CHECK (jsonb_typeof(parameter_schema) = 'object'),
    route_config jsonb NOT NULL DEFAULT '{}',
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, domain, action, version),
    CHECK (NOT is_current OR status = 'PUBLISHED')
);
CREATE UNIQUE INDEX intent_current_idx ON intent.definition (tenant_id, domain, action)
    WHERE is_current;

CREATE TABLE intent.intent_record (
    tenant_id text NOT NULL,
    id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    origin_turn_id uuid NOT NULL,
    definition_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, conversation_id, id),
    FOREIGN KEY (tenant_id, conversation_id, origin_turn_id)
        REFERENCES intent.turn (tenant_id, conversation_id, id),
    FOREIGN KEY (tenant_id, definition_id) REFERENCES intent.definition (tenant_id, id)
);
CREATE INDEX intent_recent_idx ON intent.intent_record (tenant_id, conversation_id, created_at DESC);

CREATE TABLE intent.task (
    tenant_id text NOT NULL,
    id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    intent_id uuid NOT NULL,
    parent_task_id uuid,
    status text NOT NULL CHECK (status IN (
        'COLLECTING_INPUT', 'WAITING_CONFIRMATION', 'READY', 'EXECUTING',
        'COMPLETED', 'FAILED', 'CANCELLED')),
    input_version integer NOT NULL CHECK (input_version > 0),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, conversation_id, id),
    UNIQUE (tenant_id, intent_id),
    FOREIGN KEY (tenant_id, conversation_id, intent_id)
        REFERENCES intent.intent_record (tenant_id, conversation_id, id),
    FOREIGN KEY (tenant_id, conversation_id, parent_task_id)
        REFERENCES intent.task (tenant_id, conversation_id, id),
    CHECK (parent_task_id IS DISTINCT FROM id)
);
CREATE INDEX task_context_idx ON intent.task (tenant_id, conversation_id, status, updated_at DESC);

CREATE TABLE intent.candidate (
    tenant_id text NOT NULL,
    id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    turn_id uuid NOT NULL,
    ordinal integer NOT NULL CHECK (ordinal >= 0),
    draft jsonb NOT NULL,
    relation text CHECK (relation IN (
        'NEW_TASK', 'CONTINUATION', 'MODIFICATION', 'CANCEL', 'CONFIRM', 'AMBIGUOUS')),
    target_task_id uuid,
    target_clarification_id uuid,
    resolves_question_id uuid,
    evidence jsonb NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(evidence) = 'array'),
    confidence numeric(5,4) CHECK (confidence BETWEEN 0 AND 1),
    status text NOT NULL CHECK (status IN ('DRAFT', 'APPLIED', 'NEEDS_CLARIFICATION', 'FAILED')),
    outcome jsonb,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, conversation_id, id),
    UNIQUE (tenant_id, turn_id, ordinal),
    UNIQUE (tenant_id, turn_id, id),
    FOREIGN KEY (tenant_id, conversation_id, turn_id)
        REFERENCES intent.turn (tenant_id, conversation_id, id),
    FOREIGN KEY (tenant_id, conversation_id, target_task_id)
        REFERENCES intent.task (tenant_id, conversation_id, id),
    CHECK (relation NOT IN ('CONTINUATION', 'MODIFICATION', 'CANCEL', 'CONFIRM') OR target_task_id IS NOT NULL),
    CHECK (relation IS DISTINCT FROM 'NEW_TASK' OR target_task_id IS NULL),
    CHECK (target_clarification_id IS NULL OR target_task_id IS NOT NULL)
);

-- Recognition-stage routing, distinct from business admission and execution mode.
CREATE TABLE intent.routing_assessment (
    tenant_id text NOT NULL,
    id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    turn_id uuid NOT NULL,
    candidate_id uuid,
    comparison_key text,
    prior_assessment_id uuid,
    complexity_score numeric(7,6) NOT NULL CHECK (complexity_score BETWEEN 0 AND 1),
    match_confidence numeric(7,6) CHECK (match_confidence BETWEEN 0 AND 1),
    score_band text GENERATED ALWAYS AS (
        CASE
            WHEN complexity_score < 0.30 THEN 'RULE'
            WHEN complexity_score < 0.50 THEN 'RETRIEVAL_SMALL_MODEL'
            WHEN complexity_score < 0.70 THEN 'HYSTERESIS'
            ELSE 'LARGE_MODEL'
        END
    ) STORED,
    applied_strategy text NOT NULL CHECK (applied_strategy IN (
        'RULE', 'RETRIEVAL_SMALL_MODEL', 'HYBRID', 'LARGE_MODEL')),
    score_config_version text NOT NULL,
    critical_risk_override boolean NOT NULL DEFAULT false,
    reason_codes jsonb NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(reason_codes) = 'array'),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, conversation_id, comparison_key, applied_strategy, id),
    FOREIGN KEY (tenant_id, conversation_id, turn_id)
        REFERENCES intent.turn (tenant_id, conversation_id, id),
    FOREIGN KEY (tenant_id, turn_id, candidate_id)
        REFERENCES intent.candidate (tenant_id, turn_id, id),
    FOREIGN KEY (tenant_id, conversation_id, comparison_key, applied_strategy, prior_assessment_id)
        REFERENCES intent.routing_assessment (tenant_id, conversation_id, comparison_key, applied_strategy, id),
    CHECK (prior_assessment_id IS DISTINCT FROM id),
    CHECK (prior_assessment_id IS NULL OR
        (comparison_key IS NOT NULL AND score_band = 'HYSTERESIS')),
    CHECK (NOT critical_risk_override OR
        (applied_strategy = 'RULE' AND prior_assessment_id IS NULL))
);
CREATE INDEX routing_assessment_comparable_idx
    ON intent.routing_assessment (tenant_id, conversation_id, comparison_key, created_at DESC)
    WHERE comparison_key IS NOT NULL;

CREATE TABLE intent.task_input (
    tenant_id text NOT NULL,
    task_id uuid NOT NULL,
    version integer NOT NULL CHECK (version > 0),
    conversation_id uuid NOT NULL,
    source_candidate_id uuid NOT NULL,
    workflow_skill_version_id uuid,
    slots jsonb NOT NULL CHECK (jsonb_typeof(slots) = 'object'),
    slot_provenance jsonb NOT NULL DEFAULT '{}',
    slot_state jsonb NOT NULL DEFAULT '{}',
    validated_ir jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, task_id, version),
    FOREIGN KEY (tenant_id, conversation_id, task_id)
        REFERENCES intent.task (tenant_id, conversation_id, id),
    FOREIGN KEY (tenant_id, conversation_id, source_candidate_id)
        REFERENCES intent.candidate (tenant_id, conversation_id, id)
);
ALTER TABLE intent.task ADD CONSTRAINT task_current_input_fk
    FOREIGN KEY (tenant_id, id, input_version)
    REFERENCES intent.task_input (tenant_id, task_id, version) DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE intent.clarification (
    tenant_id text NOT NULL,
    id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    source_candidate_id uuid NOT NULL,
    task_id uuid,
    input_version integer,
    kind text NOT NULL CHECK (kind IN ('SLOTS', 'TARGET')),
    status text NOT NULL CHECK (status IN ('OPEN', 'ANSWERED', 'EXPIRED', 'SUPERSEDED', 'CANCELLED')),
    question jsonb NOT NULL,
    expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, task_id, id),
    UNIQUE (tenant_id, conversation_id, id),
    FOREIGN KEY (tenant_id, conversation_id, source_candidate_id)
        REFERENCES intent.candidate (tenant_id, conversation_id, id),
    FOREIGN KEY (tenant_id, conversation_id, task_id)
        REFERENCES intent.task (tenant_id, conversation_id, id),
    FOREIGN KEY (tenant_id, task_id, input_version)
        REFERENCES intent.task_input (tenant_id, task_id, version),
    CHECK ((kind = 'SLOTS' AND task_id IS NOT NULL AND input_version IS NOT NULL)
        OR (kind = 'TARGET' AND task_id IS NULL AND input_version IS NULL))
);
ALTER TABLE intent.candidate ADD CONSTRAINT candidate_target_question_fk
    FOREIGN KEY (tenant_id, target_task_id, target_clarification_id)
    REFERENCES intent.clarification (tenant_id, task_id, id);
ALTER TABLE intent.candidate ADD CONSTRAINT candidate_resolves_question_fk
    FOREIGN KEY (tenant_id, conversation_id, resolves_question_id)
    REFERENCES intent.clarification (tenant_id, conversation_id, id);
CREATE UNIQUE INDEX clarification_open_slot_idx ON intent.clarification (tenant_id, task_id)
    WHERE status = 'OPEN' AND kind = 'SLOTS';
CREATE INDEX clarification_context_idx ON intent.clarification (tenant_id, conversation_id, created_at)
    WHERE status = 'OPEN';

CREATE TABLE intent.confirmation (
    tenant_id text NOT NULL,
    id uuid NOT NULL,
    task_id uuid NOT NULL,
    input_version integer NOT NULL,
    requester_user_id text NOT NULL,
    operation_hash text NOT NULL,
    status text NOT NULL CHECK (status IN ('PENDING', 'CONFIRMED', 'EXPIRED', 'SUPERSEDED', 'CANCELLED')),
    expires_at timestamptz NOT NULL,
    decided_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, task_id, input_version)
        REFERENCES intent.task_input (tenant_id, task_id, version)
);
CREATE UNIQUE INDEX confirmation_pending_idx ON intent.confirmation (tenant_id, task_id)
    WHERE status = 'PENDING';
CREATE INDEX confirmation_expiry_idx ON intent.confirmation (expires_at)
    WHERE status = 'PENDING';

CREATE TABLE intent.route_decision (
    tenant_id text NOT NULL,
    id uuid NOT NULL,
    task_id uuid NOT NULL,
    input_version integer NOT NULL,
    policy_version text NOT NULL,
    verdict text NOT NULL CHECK (verdict IN ('ALLOW', 'DENY', 'REQUIRE_CONFIRMATION')),
    route text CHECK (route IN ('DIRECT', 'WORKFLOW', 'AGENT')),
    reasons jsonb NOT NULL DEFAULT '[]',
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, task_id, input_version, id, route, verdict),
    FOREIGN KEY (tenant_id, task_id, input_version)
        REFERENCES intent.task_input (tenant_id, task_id, version),
    CHECK (verdict <> 'ALLOW' OR route IS NOT NULL)
);

CREATE TABLE runtime.run (
    tenant_id text NOT NULL,
    id uuid NOT NULL,
    task_id uuid NOT NULL,
    input_version integer NOT NULL,
    route_decision_id uuid NOT NULL,
    route text NOT NULL CHECK (route IN ('DIRECT', 'WORKFLOW', 'AGENT')),
    admitted_verdict text NOT NULL DEFAULT 'ALLOW' CHECK (admitted_verdict = 'ALLOW'),
    user_id text NOT NULL,
    agent_id text NOT NULL,
    trace_id text NOT NULL,
    graph_version text NOT NULL,
    active_plan_version integer NOT NULL CHECK (active_plan_version > 0),
    status text NOT NULL CHECK (status IN (
        'QUEUED', 'RUNNING', 'WAITING_APPROVAL', 'WAITING_EXTERNAL', 'WAITING_RETRY',
        'CANCEL_REQUESTED', 'NEEDS_RECONCILIATION', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    last_event_seq bigint NOT NULL DEFAULT 0 CHECK (last_event_seq >= 0),
    gate_policy_version text,
    gate_pass_iteration integer,
    gate_pass_status text GENERATED ALWAYS AS ('PASS'::text) STORED,
    max_iterations integer NOT NULL DEFAULT 1 CHECK (max_iterations > 0),
    budget_limits jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(budget_limits) = 'object'),
    budget_used jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(budget_used) = 'object'),
    deadline_at timestamptz NOT NULL,
    cancel_requested_at timestamptz,
    terminal_reason text,
    result_ref text,
    created_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, task_id, input_version),
    UNIQUE (tenant_id, id, gate_policy_version),
    FOREIGN KEY (tenant_id, task_id, input_version, route_decision_id, route, admitted_verdict)
        REFERENCES intent.route_decision (tenant_id, task_id, input_version, id, route, verdict),
    CHECK (gate_policy_version IS NULL OR route = 'AGENT'),
    CHECK ((status = 'SUCCEEDED' AND gate_policy_version IS NOT NULL) = (gate_pass_iteration IS NOT NULL)),
    CHECK ((status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')) = (finished_at IS NOT NULL))
);
CREATE UNIQUE INDEX run_one_active_per_task_idx ON runtime.run (tenant_id, task_id)
    WHERE finished_at IS NULL;
CREATE INDEX run_deadline_idx ON runtime.run (deadline_at) WHERE finished_at IS NULL;

CREATE TABLE runtime.run_submission (
    tenant_id text NOT NULL,
    user_id text NOT NULL,
    agent_id text NOT NULL,
    request_key text NOT NULL,
    run_id uuid NOT NULL,
    request_hash text NOT NULL,
    roles jsonb NOT NULL CHECK (jsonb_typeof(roles) = 'array'),
    PRIMARY KEY (tenant_id, user_id, agent_id, request_key),
    UNIQUE (tenant_id, run_id),
    FOREIGN KEY (tenant_id, run_id) REFERENCES runtime.run (tenant_id, id) DEFERRABLE INITIALLY DEFERRED
);
COMMENT ON TABLE runtime.run_submission IS '结构化长任务入口的幂等回执和授权角色快照。';
COMMENT ON COLUMN runtime.run_submission.tenant_id IS '经过认证的租户标识。';
COMMENT ON COLUMN runtime.run_submission.user_id IS '经过认证的调用主体。';
COMMENT ON COLUMN runtime.run_submission.agent_id IS '经过认证的代理标识。';
COMMENT ON COLUMN runtime.run_submission.request_key IS '调用方提交的幂等键。';
COMMENT ON COLUMN runtime.run_submission.run_id IS '受理的运行标识。';
COMMENT ON COLUMN runtime.run_submission.request_hash IS '规范化请求的摘要。';
COMMENT ON COLUMN runtime.run_submission.roles IS '发起时验证的角色快照，工作器仍使用实时工具授权。';

CREATE TABLE runtime.plan (
    tenant_id text NOT NULL,
    run_id uuid NOT NULL,
    version integer NOT NULL CHECK (version > 0),
    origin text NOT NULL CHECK (origin IN ('DIRECT', 'WORKFLOW', 'BOOTSTRAP', 'PLANNER', 'REPLAN')),
    proposal_id uuid,
    workflow_skill_version_id uuid,
    definition_ref text,
    plan_hash text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, run_id, version),
    UNIQUE (tenant_id, run_id, proposal_id),
    FOREIGN KEY (tenant_id, run_id) REFERENCES runtime.run (tenant_id, id),
    CHECK ((origin IN ('PLANNER', 'REPLAN')) = (proposal_id IS NOT NULL))
);
ALTER TABLE runtime.run ADD CONSTRAINT run_active_plan_fk
    FOREIGN KEY (tenant_id, id, active_plan_version)
    REFERENCES runtime.plan (tenant_id, run_id, version) DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE runtime.plan_proposal (
    tenant_id text NOT NULL,
    run_id uuid NOT NULL,
    id uuid NOT NULL,
    base_plan_version integer NOT NULL CHECK (base_plan_version > 0),
    kind text NOT NULL CHECK (kind IN ('INITIAL', 'REPLAN')),
    trigger_reason text NOT NULL,
    planner_version text NOT NULL,
    context_hash text,
    context_ref text,
    draft_ref text,
    status text NOT NULL CHECK (status IN (
        'REQUESTED', 'GENERATED', 'PUBLISHED', 'REJECTED', 'STALE', 'CANCELLED')),
    validation_result jsonb,
    next_at timestamptz NOT NULL DEFAULT now(),
    lease_epoch bigint NOT NULL DEFAULT 0 CHECK (lease_epoch >= 0),
    lease_owner text,
    lease_until timestamptz,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    settled_at timestamptz,
    PRIMARY KEY (tenant_id, run_id, id),
    FOREIGN KEY (tenant_id, run_id, base_plan_version)
        REFERENCES runtime.plan (tenant_id, run_id, version),
    CHECK (status NOT IN ('GENERATED', 'PUBLISHED') OR
        (context_hash IS NOT NULL AND context_ref IS NOT NULL AND draft_ref IS NOT NULL)),
    CHECK ((lease_owner IS NULL) = (lease_until IS NULL)),
    CHECK ((status IN ('PUBLISHED', 'REJECTED', 'STALE', 'CANCELLED')) = (settled_at IS NOT NULL))
);
CREATE UNIQUE INDEX plan_proposal_open_idx ON runtime.plan_proposal (tenant_id, run_id)
    WHERE status IN ('REQUESTED', 'GENERATED');
CREATE INDEX plan_proposal_due_idx ON runtime.plan_proposal (next_at, lease_until)
    WHERE status IN ('REQUESTED', 'GENERATED');
ALTER TABLE runtime.plan ADD CONSTRAINT plan_proposal_fk
    FOREIGN KEY (tenant_id, run_id, proposal_id)
    REFERENCES runtime.plan_proposal (tenant_id, run_id, id);

CREATE TABLE runtime.step (
    tenant_id text NOT NULL,
    id uuid NOT NULL,
    run_id uuid NOT NULL,
    plan_version integer NOT NULL,
    node_key text NOT NULL,
    kind text NOT NULL CHECK (kind IN ('TOOL', 'COMPUTE', 'GATE')),
    spec jsonb NOT NULL,
    status text NOT NULL CHECK (status IN (
        'PENDING', 'READY', 'RUNNING', 'WAITING_APPROVAL', 'WAITING_EXTERNAL',
        'RETRY_WAIT', 'NEEDS_RECONCILIATION', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'SUPERSEDED', 'SKIPPED')),
    operation_key text,
    tool_execution_id uuid,
    observation_ref text,
    current_attempt integer NOT NULL DEFAULT 0 CHECK (current_attempt >= 0),
    next_at timestamptz NOT NULL DEFAULT now(),
    lease_epoch bigint NOT NULL DEFAULT 0 CHECK (lease_epoch >= 0),
    lease_owner text,
    lease_until timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, run_id, id),
    UNIQUE (tenant_id, run_id, plan_version, id),
    UNIQUE (tenant_id, run_id, plan_version, node_key),
    UNIQUE (tenant_id, run_id, operation_key),
    FOREIGN KEY (tenant_id, run_id, plan_version)
        REFERENCES runtime.plan (tenant_id, run_id, version),
    CHECK ((lease_owner IS NULL) = (lease_until IS NULL)),
    CHECK ((kind = 'TOOL') = (operation_key IS NOT NULL)),
    CHECK (tool_execution_id IS NULL OR kind = 'TOOL')
);
CREATE INDEX step_run_state_idx ON runtime.step (tenant_id, run_id, plan_version, status);
CREATE INDEX step_due_idx ON runtime.step (next_at, lease_until)
    WHERE status IN ('READY', 'RUNNING', 'RETRY_WAIT', 'WAITING_APPROVAL', 'WAITING_EXTERNAL', 'NEEDS_RECONCILIATION');

CREATE TABLE runtime.step_dependency (
    tenant_id text NOT NULL,
    run_id uuid NOT NULL,
    plan_version integer NOT NULL,
    step_id uuid NOT NULL,
    depends_on_step_id uuid NOT NULL,
    PRIMARY KEY (tenant_id, run_id, plan_version, step_id, depends_on_step_id),
    FOREIGN KEY (tenant_id, run_id, plan_version, step_id)
        REFERENCES runtime.step (tenant_id, run_id, plan_version, id),
    FOREIGN KEY (tenant_id, run_id, plan_version, depends_on_step_id)
        REFERENCES runtime.step (tenant_id, run_id, plan_version, id),
    CHECK (step_id <> depends_on_step_id)
);
CREATE INDEX step_dependency_reverse_idx
    ON runtime.step_dependency (tenant_id, run_id, plan_version, depends_on_step_id);

CREATE TABLE runtime.step_attempt (
    tenant_id text NOT NULL,
    step_id uuid NOT NULL,
    attempt_no integer NOT NULL CHECK (attempt_no > 0),
    lease_epoch bigint NOT NULL CHECK (lease_epoch > 0),
    worker_id text NOT NULL,
    status text NOT NULL CHECK (status IN ('RUNNING', 'DONE', 'FAILED', 'LEASE_EXPIRED', 'SUSPENDED')),
    error_code text,
    started_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    PRIMARY KEY (tenant_id, step_id, attempt_no),
    FOREIGN KEY (tenant_id, step_id) REFERENCES runtime.step (tenant_id, id)
);

CREATE TABLE runtime.checkpoint (
    tenant_id text NOT NULL,
    run_id uuid NOT NULL,
    seq bigint NOT NULL CHECK (seq > 0),
    plan_version integer NOT NULL,
    after_step_id uuid,
    graph_thread_key text NOT NULL,
    format_version integer NOT NULL CHECK (format_version > 0),
    graph_state jsonb NOT NULL CHECK (jsonb_typeof(graph_state) = 'object'),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, run_id, seq),
    FOREIGN KEY (tenant_id, run_id, plan_version)
        REFERENCES runtime.plan (tenant_id, run_id, version),
    FOREIGN KEY (tenant_id, run_id, plan_version, after_step_id)
        REFERENCES runtime.step (tenant_id, run_id, plan_version, id)
);
CREATE INDEX checkpoint_resume_idx ON runtime.checkpoint
    (tenant_id, run_id, graph_thread_key, plan_version, seq DESC);

CREATE TABLE runtime.context_artifact (
    tenant_id text NOT NULL,
    run_id uuid NOT NULL,
    id uuid NOT NULL,
    step_id uuid,
    artifact_key text NOT NULL,
    version integer NOT NULL CHECK (version > 0),
    kind text NOT NULL CHECK (kind IN ('INPUT_SNAPSHOT', 'TOOL_RESULT', 'OBSERVATION', 'REPORT', 'PLAN_CONTEXT')),
    content_ref text NOT NULL,
    content_digest text NOT NULL,
    media_type text NOT NULL,
    format_version integer NOT NULL CHECK (format_version > 0),
    size_bytes bigint NOT NULL CHECK (size_bytes >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, run_id, id),
    UNIQUE (tenant_id, run_id, artifact_key, version),
    FOREIGN KEY (tenant_id, run_id) REFERENCES runtime.run (tenant_id, id),
    FOREIGN KEY (tenant_id, run_id, step_id) REFERENCES runtime.step (tenant_id, run_id, id)
);

CREATE TABLE runtime.checkpoint_artifact (
    tenant_id text NOT NULL,
    run_id uuid NOT NULL,
    checkpoint_seq bigint NOT NULL,
    artifact_id uuid NOT NULL,
    binding_key text NOT NULL,
    PRIMARY KEY (tenant_id, run_id, checkpoint_seq, binding_key),
    FOREIGN KEY (tenant_id, run_id, checkpoint_seq) REFERENCES runtime.checkpoint (tenant_id, run_id, seq),
    FOREIGN KEY (tenant_id, run_id, artifact_id) REFERENCES runtime.context_artifact (tenant_id, run_id, id)
);
CREATE TABLE runtime.artifact_body (
    tenant_id text NOT NULL,
    run_id uuid NOT NULL,
    artifact_id uuid NOT NULL,
    body jsonb NOT NULL,
    PRIMARY KEY (tenant_id, run_id, artifact_id),
    FOREIGN KEY (tenant_id, run_id, artifact_id) REFERENCES runtime.context_artifact (tenant_id, run_id, id)
);
COMMENT ON TABLE runtime.artifact_body IS '首期小型运行产物正文，检查点保存引用。';
COMMENT ON COLUMN runtime.artifact_body.tenant_id IS '运行所属租户。';
COMMENT ON COLUMN runtime.artifact_body.run_id IS '产物所属运行。';
COMMENT ON COLUMN runtime.artifact_body.artifact_id IS '对应产物的版本化元数据标识。';
COMMENT ON COLUMN runtime.artifact_body.body IS '受大小限制的结构化运行产物正文。';
CREATE INDEX checkpoint_artifact_usage_idx ON runtime.checkpoint_artifact (tenant_id, run_id, artifact_id);

CREATE TABLE runtime.iteration (
    tenant_id text NOT NULL,
    run_id uuid NOT NULL,
    iteration_no integer NOT NULL CHECK (iteration_no > 0),
    plan_version integer NOT NULL,
    gate_policy_version text NOT NULL,
    context_hash text NOT NULL,
    context_manifest jsonb NOT NULL,
    status text NOT NULL CHECK (status IN ('ACTIVE', 'PASS', 'RETRYABLE_FAIL', 'TERMINAL_FAIL', 'CANCELLED')),
    budget_used jsonb NOT NULL DEFAULT '{}',
    created_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    PRIMARY KEY (tenant_id, run_id, iteration_no),
    UNIQUE (tenant_id, run_id, iteration_no, plan_version),
    UNIQUE (tenant_id, run_id, iteration_no, plan_version, status),
    FOREIGN KEY (tenant_id, run_id, gate_policy_version)
        REFERENCES runtime.run (tenant_id, id, gate_policy_version),
    FOREIGN KEY (tenant_id, run_id, plan_version)
        REFERENCES runtime.plan (tenant_id, run_id, version),
    CHECK ((status = 'ACTIVE') = (finished_at IS NULL))
);
CREATE UNIQUE INDEX iteration_active_idx ON runtime.iteration (tenant_id, run_id)
    WHERE status = 'ACTIVE';
ALTER TABLE runtime.run ADD CONSTRAINT run_passed_iteration_fk
    FOREIGN KEY (tenant_id, id, gate_pass_iteration, active_plan_version, gate_pass_status)
    REFERENCES runtime.iteration (tenant_id, run_id, iteration_no, plan_version, status)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE runtime.gate_check (
    tenant_id text NOT NULL,
    run_id uuid NOT NULL,
    iteration_no integer NOT NULL,
    plan_version integer NOT NULL,
    check_key text NOT NULL,
    verifier_version text NOT NULL,
    required boolean NOT NULL DEFAULT true,
    verdict text NOT NULL CHECK (verdict IN ('PASS', 'RETRYABLE_FAIL', 'TERMINAL_FAIL')),
    evidence_ref text,
    checked_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, run_id, iteration_no, check_key),
    FOREIGN KEY (tenant_id, run_id, iteration_no, plan_version)
        REFERENCES runtime.iteration (tenant_id, run_id, iteration_no, plan_version)
);

CREATE TABLE runtime.run_event (
    tenant_id text NOT NULL,
    run_id uuid NOT NULL,
    seq bigint NOT NULL CHECK (seq > 0),
    event_id uuid NOT NULL,
    event_type text NOT NULL,
    schema_version integer NOT NULL DEFAULT 1 CHECK (schema_version > 0),
    payload jsonb NOT NULL DEFAULT '{}',
    occurred_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, run_id, seq),
    UNIQUE (tenant_id, event_id),
    FOREIGN KEY (tenant_id, run_id) REFERENCES runtime.run (tenant_id, id)
);

CREATE TABLE runtime.run_command (
    tenant_id text NOT NULL,
    run_id uuid NOT NULL,
    actor_user_id text NOT NULL,
    command_key text NOT NULL,
    command_type text NOT NULL CHECK (command_type IN ('CANCEL', 'RECONCILE')),
    request_hash text NOT NULL,
    receipt jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, run_id, actor_user_id, command_key),
    FOREIGN KEY (tenant_id, run_id) REFERENCES runtime.run (tenant_id, id)
);

CREATE TABLE gateway.tool_server (
    tenant_id text NOT NULL,
    id uuid NOT NULL,
    name text NOT NULL,
    protocol text NOT NULL CHECK (protocol IN ('HTTP', 'MCP', 'GRPC', 'DATABASE', 'RAG', 'SANDBOX', 'LOCAL', 'ASYNC')),
    status text NOT NULL CHECK (status IN ('ENABLED', 'DISABLED')),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, name),
    UNIQUE (tenant_id, id, protocol)
);

CREATE TABLE gateway.tool (
    tenant_id text NOT NULL,
    id uuid NOT NULL,
    name text NOT NULL,
    display_name text,
    description text NOT NULL,
    category text,
    risk_level text NOT NULL CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    status text NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'DISABLED')),
    current_version_id uuid,
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, name),
    CHECK (status <> 'ACTIVE' OR current_version_id IS NOT NULL)
);

CREATE TABLE gateway.tool_version (
    tenant_id text NOT NULL,
    id uuid NOT NULL,
    tool_id uuid NOT NULL,
    version_name text NOT NULL,
    status text NOT NULL CHECK (status IN ('DRAFT', 'VALIDATING', 'TESTING', 'PUBLISHED', 'DEPRECATED', 'DISABLED')),
    input_schema jsonb NOT NULL CHECK (jsonb_typeof(input_schema) = 'object'),
    output_schema jsonb CHECK (jsonb_typeof(output_schema) = 'object'),
    contract_hash text NOT NULL,
    published_at timestamptz,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, tool_id, id),
    UNIQUE (tenant_id, tool_id, version_name),
    FOREIGN KEY (tenant_id, tool_id) REFERENCES gateway.tool (tenant_id, id),
    CHECK (status NOT IN ('PUBLISHED', 'DEPRECATED') OR published_at IS NOT NULL)
);
ALTER TABLE gateway.tool ADD CONSTRAINT tool_current_version_fk
    FOREIGN KEY (tenant_id, id, current_version_id)
    REFERENCES gateway.tool_version (tenant_id, tool_id, id);

CREATE TABLE runtime.workflow_skill (
    tenant_id text NOT NULL,
    id uuid NOT NULL,
    skill_key text NOT NULL,
    display_name text NOT NULL,
    status text NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'DISABLED')),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, skill_key)
);

CREATE TABLE runtime.workflow_skill_version (
    tenant_id text NOT NULL,
    id uuid NOT NULL,
    skill_id uuid NOT NULL,
    version integer NOT NULL CHECK (version > 0),
    intent_definition_id uuid NOT NULL,
    execution_mode text NOT NULL CHECK (execution_mode IN ('DIRECT', 'WORKFLOW')),
    status text NOT NULL CHECK (status IN ('DRAFT', 'REVIEWED', 'PUBLISHED', 'RETIRED', 'DISABLED')),
    is_current boolean NOT NULL DEFAULT false,
    source_ref text NOT NULL,
    source_markdown text,
    admission_policy_ref text NOT NULL,
    compiled_dag jsonb CHECK (jsonb_typeof(compiled_dag) = 'object'),
    compiled_digest text,
    reviewed_by text,
    reviewed_at timestamptz,
    published_by text,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, id, execution_mode),
    UNIQUE (tenant_id, skill_id, version),
    FOREIGN KEY (tenant_id, skill_id) REFERENCES runtime.workflow_skill (tenant_id, id),
    FOREIGN KEY (tenant_id, intent_definition_id) REFERENCES intent.definition (tenant_id, id),
    CHECK (NOT is_current OR status = 'PUBLISHED'),
    CHECK (status NOT IN ('REVIEWED', 'PUBLISHED', 'RETIRED') OR
        (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)),
    CHECK (status NOT IN ('PUBLISHED', 'RETIRED') OR
        (published_by IS NOT NULL AND published_at IS NOT NULL AND compiled_dag IS NOT NULL AND compiled_digest IS NOT NULL))
);
CREATE UNIQUE INDEX workflow_skill_active_version_idx
    ON runtime.workflow_skill_version (tenant_id, skill_id) WHERE is_current;

CREATE TABLE runtime.workflow_skill_trigger (
    tenant_id text NOT NULL,
    trigger_kind text NOT NULL CHECK (trigger_kind IN ('COMMAND', 'FORM_ACTION', 'EXACT_ALIAS')),
    normalized_key text NOT NULL,
    skill_id uuid NOT NULL,
    PRIMARY KEY (tenant_id, trigger_kind, normalized_key),
    FOREIGN KEY (tenant_id, skill_id) REFERENCES runtime.workflow_skill (tenant_id, id)
);

CREATE TABLE runtime.workflow_skill_tool (
    tenant_id text NOT NULL,
    skill_version_id uuid NOT NULL,
    node_key text NOT NULL,
    tool_id uuid NOT NULL,
    tool_version_id uuid NOT NULL,
    PRIMARY KEY (tenant_id, skill_version_id, node_key),
    FOREIGN KEY (tenant_id, skill_version_id) REFERENCES runtime.workflow_skill_version (tenant_id, id),
    FOREIGN KEY (tenant_id, tool_id, tool_version_id) REFERENCES gateway.tool_version (tenant_id, tool_id, id)
);

ALTER TABLE intent.task_input ADD CONSTRAINT task_input_workflow_skill_fk
    FOREIGN KEY (tenant_id, workflow_skill_version_id) REFERENCES runtime.workflow_skill_version (tenant_id, id);
ALTER TABLE runtime.plan ADD CONSTRAINT plan_workflow_skill_fk
    FOREIGN KEY (tenant_id, workflow_skill_version_id, origin)
    REFERENCES runtime.workflow_skill_version (tenant_id, id, execution_mode);

CREATE TABLE gateway.tool_endpoint (
    tenant_id text NOT NULL,
    id uuid NOT NULL,
    version_id uuid NOT NULL,
    server_id uuid NOT NULL,
    protocol text NOT NULL,
    target_ref text NOT NULL,
    credential_ref text,
    timeout_ms integer NOT NULL CHECK (timeout_ms > 0),
    async_capable boolean NOT NULL DEFAULT false,
    idempotency_supported boolean NOT NULL DEFAULT false,
    enabled boolean NOT NULL DEFAULT true,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, version_id, id),
    FOREIGN KEY (tenant_id, version_id) REFERENCES gateway.tool_version (tenant_id, id),
    FOREIGN KEY (tenant_id, server_id, protocol) REFERENCES gateway.tool_server (tenant_id, id, protocol)
);

CREATE TABLE gateway.tool_permission (
    tenant_id text NOT NULL,
    tool_id uuid NOT NULL,
    subject_type text NOT NULL CHECK (subject_type IN ('USER', 'ROLE', 'AGENT', 'SERVICE')),
    subject_id text NOT NULL,
    action text NOT NULL CHECK (action IN ('DISCOVER', 'EXECUTE', 'APPROVE', 'MANAGE')),
    effect text NOT NULL CHECK (effect IN ('ALLOW', 'DENY')),
    enabled boolean NOT NULL DEFAULT true,
    PRIMARY KEY (tenant_id, tool_id, subject_type, subject_id, action),
    FOREIGN KEY (tenant_id, tool_id) REFERENCES gateway.tool (tenant_id, id)
);
CREATE INDEX tool_permission_subject_idx
    ON gateway.tool_permission (tenant_id, subject_type, subject_id, action) WHERE enabled;

CREATE TABLE gateway.tool_policy (
    tenant_id text NOT NULL,
    id uuid NOT NULL,
    tool_id uuid NOT NULL,
    policy_version integer NOT NULL CHECK (policy_version > 0),
    kind text NOT NULL CHECK (kind IN ('ABAC', 'APPROVAL', 'QUOTA', 'RETRY', 'REDACTION')),
    rule jsonb NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, tool_id, kind, policy_version),
    FOREIGN KEY (tenant_id, tool_id) REFERENCES gateway.tool (tenant_id, id)
);
CREATE UNIQUE INDEX tool_policy_current_idx
    ON gateway.tool_policy (tenant_id, tool_id, kind) WHERE enabled;

CREATE TABLE gateway.tool_usage_window (
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
CREATE INDEX tool_usage_window_cleanup_idx ON gateway.tool_usage_window (window_start);
COMMENT ON TABLE gateway.tool_usage_window IS '工具限流与配额窗口，按租户、工具及计数主体隔离。';
COMMENT ON COLUMN gateway.tool_usage_window.tenant_id IS '工具所属租户。';
COMMENT ON COLUMN gateway.tool_usage_window.tool_id IS '被计数的工具 ID。';
COMMENT ON COLUMN gateway.tool_usage_window.scope IS '计数范围，按调用主体或整个租户。';
COMMENT ON COLUMN gateway.tool_usage_window.subject_id IS '主体标识，租户计数时为空字符串。';
COMMENT ON COLUMN gateway.tool_usage_window.unit IS '计数单位，分钟或 UTC 日。';
COMMENT ON COLUMN gateway.tool_usage_window.window_start IS '固定窗口起始时刻。';
COMMENT ON COLUMN gateway.tool_usage_window.used IS '窗口内成功取得执行资格的次数。';

CREATE TABLE gateway.tool_execution (
    tenant_id text NOT NULL,
    id uuid NOT NULL,
    tool_id uuid NOT NULL,
    version_id uuid NOT NULL,
    endpoint_id uuid,
    run_id uuid,
    step_id uuid,
    agent_id text NOT NULL,
    principal_id text NOT NULL,
    trace_id text NOT NULL,
    idempotency_key text NOT NULL,
    operation_hash text NOT NULL,
    arguments_ref text NOT NULL,
    status text NOT NULL CHECK (status IN (
        'QUEUED', 'RUNNING', 'WAITING_APPROVAL', 'WAITING_EXTERNAL', 'RETRY_WAIT', 'CANCEL_REQUESTED',
        'SUCCEEDED', 'FAILED', 'REJECTED', 'EXPIRED', 'BLOCKED', 'CANCELLED', 'UNKNOWN')),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    next_at timestamptz NOT NULL DEFAULT now(),
    external_job_id text,
    downstream_request_id text,
    result_ref text,
    error_code text,
    lease_epoch bigint NOT NULL DEFAULT 0 CHECK (lease_epoch >= 0),
    lease_owner text,
    lease_until timestamptz,
    cancel_requested_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, id, run_id, step_id),
    UNIQUE (tenant_id, id, version_id, operation_hash, principal_id),
    UNIQUE (tenant_id, agent_id, principal_id, tool_id, idempotency_key),
    FOREIGN KEY (tenant_id, tool_id, version_id) REFERENCES gateway.tool_version (tenant_id, tool_id, id),
    FOREIGN KEY (tenant_id, version_id, endpoint_id) REFERENCES gateway.tool_endpoint (tenant_id, version_id, id),
    FOREIGN KEY (tenant_id, run_id, step_id) REFERENCES runtime.step (tenant_id, run_id, id),
    CHECK ((run_id IS NULL) = (step_id IS NULL)),
    CHECK (external_job_id IS NULL OR endpoint_id IS NOT NULL),
    CHECK ((lease_owner IS NULL) = (lease_until IS NULL)),
    CHECK ((status IN ('SUCCEEDED', 'FAILED', 'REJECTED', 'EXPIRED', 'BLOCKED', 'CANCELLED'))
        = (finished_at IS NOT NULL))
);
CREATE UNIQUE INDEX execution_one_per_step_idx ON gateway.tool_execution (tenant_id, step_id)
    WHERE step_id IS NOT NULL;
CREATE UNIQUE INDEX execution_external_idx ON gateway.tool_execution (tenant_id, endpoint_id, external_job_id)
    WHERE external_job_id IS NOT NULL;
CREATE INDEX execution_due_idx ON gateway.tool_execution (next_at, lease_until)
    WHERE status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT', 'WAITING_EXTERNAL', 'CANCEL_REQUESTED', 'UNKNOWN');
ALTER TABLE runtime.step ADD CONSTRAINT step_tool_execution_fk
    FOREIGN KEY (tenant_id, tool_execution_id, run_id, id)
    REFERENCES gateway.tool_execution (tenant_id, id, run_id, step_id);

CREATE TABLE gateway.tool_approval (
    tenant_id text NOT NULL,
    id uuid NOT NULL,
    execution_id uuid NOT NULL,
    version_id uuid NOT NULL,
    operation_hash text NOT NULL,
    requester_principal_id text NOT NULL,
    approver_selector jsonb NOT NULL,
    policy_version text NOT NULL,
    status text NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED', 'CANCELLED')),
    expires_at timestamptz NOT NULL,
    decided_by text,
    decided_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, execution_id, version_id, operation_hash, requester_principal_id)
        REFERENCES gateway.tool_execution (tenant_id, id, version_id, operation_hash, principal_id),
    CHECK ((status = 'PENDING') = (decided_at IS NULL)),
    CHECK (status NOT IN ('APPROVED', 'REJECTED') OR decided_by IS NOT NULL)
);
CREATE UNIQUE INDEX tool_approval_pending_idx ON gateway.tool_approval (tenant_id, execution_id)
    WHERE status = 'PENDING';
CREATE INDEX tool_approval_expiry_idx ON gateway.tool_approval (expires_at) WHERE status = 'PENDING';

CREATE TABLE gateway.tool_job (
    tenant_id text NOT NULL,
    execution_id uuid NOT NULL,
    encrypted_request text,
    encrypted_result text,
    deadline_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, execution_id),
    FOREIGN KEY (tenant_id, execution_id) REFERENCES gateway.tool_execution (tenant_id, id)
);
CREATE INDEX tool_job_retention_idx ON gateway.tool_job (deadline_at);
COMMENT ON TABLE gateway.tool_job IS '审批等待与异步轮询使用的加密恢复数据。';
COMMENT ON COLUMN gateway.tool_job.tenant_id IS '执行所属租户。';
COMMENT ON COLUMN gateway.tool_job.execution_id IS '关联的工具执行 ID。';
COMMENT ON COLUMN gateway.tool_job.encrypted_request IS 'AES-GCM 加密的参数与已验证的角色快照。';
COMMENT ON COLUMN gateway.tool_job.encrypted_result IS 'AES-GCM 加密的脱敏结果。';
COMMENT ON COLUMN gateway.tool_job.deadline_at IS '从提交开始计算的最晚恢复或轮询时间。';

CREATE TABLE gateway.callback_receipt (
    tenant_id text NOT NULL,
    server_id uuid NOT NULL,
    provider_event_id text NOT NULL,
    execution_id uuid NOT NULL,
    payload_hash text NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, server_id, provider_event_id),
    FOREIGN KEY (tenant_id, server_id) REFERENCES gateway.tool_server (tenant_id, id),
    FOREIGN KEY (tenant_id, execution_id) REFERENCES gateway.tool_execution (tenant_id, id)
);

CREATE TABLE gateway.audit_entry (
    tenant_id text NOT NULL,
    id uuid NOT NULL,
    execution_id uuid,
    tool_id uuid,
    actor_id text NOT NULL,
    action text NOT NULL,
    trace_id text NOT NULL,
    detail jsonb NOT NULL DEFAULT '{}',
    occurred_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, execution_id) REFERENCES gateway.tool_execution (tenant_id, id),
    FOREIGN KEY (tenant_id, tool_id) REFERENCES gateway.tool (tenant_id, id)
);
CREATE INDEX audit_execution_idx ON gateway.audit_entry (tenant_id, execution_id, occurred_at);

CREATE TABLE eventing.outbox (
    tenant_id text NOT NULL,
    event_id uuid NOT NULL,
    event_type text NOT NULL,
    schema_version integer NOT NULL DEFAULT 1 CHECK (schema_version > 0),
    aggregate_type text NOT NULL,
    aggregate_id uuid NOT NULL,
    aggregate_version bigint NOT NULL CHECK (aggregate_version > 0),
    stream text NOT NULL,
    trace_id text NOT NULL,
    correlation_id text NOT NULL,
    causation_id uuid,
    payload jsonb NOT NULL DEFAULT '{}',
    occurred_at timestamptz NOT NULL DEFAULT now(),
    next_at timestamptz NOT NULL DEFAULT now(),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    lease_epoch bigint NOT NULL DEFAULT 0 CHECK (lease_epoch >= 0),
    lease_owner text,
    lease_until timestamptz,
    published_at timestamptz,
    message_id text,
    last_error text,
    PRIMARY KEY (tenant_id, event_id),
    CHECK ((lease_owner IS NULL) = (lease_until IS NULL)),
    CHECK ((published_at IS NULL) = (message_id IS NULL))
);
CREATE INDEX outbox_due_idx ON eventing.outbox (next_at, lease_until) WHERE published_at IS NULL;
CREATE INDEX outbox_replay_idx ON eventing.outbox (stream, occurred_at, event_id);

CREATE TABLE eventing.inbox (
    tenant_id text NOT NULL,
    consumer_name text NOT NULL,
    event_id uuid NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, consumer_name, event_id)
);

CREATE TABLE eventing.delivery_failure (
    tenant_id text NOT NULL,
    consumer_name text NOT NULL,
    event_id uuid NOT NULL,
    source_stream text NOT NULL,
    source_message_id text NOT NULL,
    envelope jsonb NOT NULL,
    failure_code text NOT NULL,
    failure_count integer NOT NULL CHECK (failure_count > 0),
    updated_at timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz,
    PRIMARY KEY (tenant_id, consumer_name, event_id)
);

-- Optional cross-session memory and knowledge modules.
CREATE TABLE memory.profile_item (
    tenant_id text NOT NULL,
    user_id text NOT NULL,
    item_key text NOT NULL,
    value jsonb NOT NULL,
    source_type text NOT NULL CHECK (source_type IN ('USER_CONFIRMED', 'BUSINESS_SYSTEM', 'ADMIN')),
    source_ref text,
    revision bigint NOT NULL CHECK (revision > 0),
    updated_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz,
    PRIMARY KEY (tenant_id, user_id, item_key)
);

CREATE TABLE memory.task_episode (
    tenant_id text NOT NULL,
    id uuid NOT NULL,
    -- Verified when captured. Preserve provenance after the source run is archived or purged.
    source_run_id uuid NOT NULL,
    owner_user_id text NOT NULL,
    scope text NOT NULL CHECK (scope IN ('USER', 'TENANT')),
    scenario_key text NOT NULL,
    status text NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'REVOKED')),
    revision bigint NOT NULL CHECK (revision > 0),
    episode_ref text,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, source_run_id),
    CHECK (status <> 'ACTIVE' OR episode_ref IS NOT NULL)
);
CREATE INDEX episode_scope_idx ON memory.task_episode (tenant_id, scope, owner_user_id, scenario_key)
    WHERE status = 'ACTIVE';

CREATE TABLE memory.knowledge_document (
    tenant_id text NOT NULL,
    id uuid NOT NULL,
    collection_key text NOT NULL,
    source_key text NOT NULL,
    access_policy_ref text NOT NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, collection_key, source_key)
);

CREATE TABLE memory.knowledge_version (
    tenant_id text NOT NULL,
    document_id uuid NOT NULL,
    version integer NOT NULL CHECK (version > 0),
    status text NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED', 'REVOKED')),
    source_ref text NOT NULL,
    content_digest text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    PRIMARY KEY (tenant_id, document_id, version),
    FOREIGN KEY (tenant_id, document_id) REFERENCES memory.knowledge_document (tenant_id, id),
    CHECK (status <> 'ACTIVE' OR published_at IS NOT NULL)
);
CREATE UNIQUE INDEX knowledge_active_version_idx ON memory.knowledge_version (tenant_id, document_id)
    WHERE status = 'ACTIVE';

CREATE TABLE memory.knowledge_chunk (
    tenant_id text NOT NULL,
    document_id uuid NOT NULL,
    document_version integer NOT NULL,
    ordinal integer NOT NULL CHECK (ordinal >= 0),
    chunk_ref text NOT NULL,
    content_digest text NOT NULL,
    PRIMARY KEY (tenant_id, document_id, document_version, ordinal),
    FOREIGN KEY (tenant_id, document_id, document_version)
        REFERENCES memory.knowledge_version (tenant_id, document_id, version)
);

-- 表用途与 database/dictionary.md 中的说明对应。
COMMENT ON TABLE intent.conversation IS '用户会话表，会话所有者与消息水位。';
COMMENT ON TABLE intent.turn IS '对话轮次表，每轮请求、幂等回执和处理进度。';
COMMENT ON TABLE intent.message IS '会话消息表，按会话排序的原文引用与脱敏位置。';
COMMENT ON TABLE intent.definition IS '意图定义表，意图参数契约与发布版本。';
COMMENT ON TABLE intent.intent_record IS '意图实例表，已绑定的业务意图。';
COMMENT ON TABLE intent.task IS '业务任务表，用户业务任务及当前参数版本。';
COMMENT ON TABLE intent.candidate IS '意图候选表，每轮候选、历史关联和处理结果。';
COMMENT ON TABLE intent.routing_assessment IS '识别路由评估表，复杂度分档、实际识别策略和滞回复用记录。';
COMMENT ON TABLE intent.task_input IS '任务输入版本表，不可变参数版本及已校验意图。';
COMMENT ON TABLE intent.clarification IS '澄清问题表，补槽或目标消歧问题。';
COMMENT ON TABLE intent.confirmation IS '用户确认表，意图级用户确认。';
COMMENT ON TABLE intent.route_decision IS '执行路由决策表，策略准入和实际执行模式。';

COMMENT ON TABLE runtime.run IS '任务执行表，已接纳的执行与运行预算。';
COMMENT ON TABLE runtime.plan IS '运行计划表，运行计划版本及来源。';
COMMENT ON TABLE runtime.plan_proposal IS '规划提案表，L4 规划提案、校验和发布。';
COMMENT ON TABLE runtime.step IS '运行节点表，DAG 节点、调度状态与租约。';
COMMENT ON TABLE runtime.step_dependency IS '节点依赖表，当前计划内的有向依赖。';
COMMENT ON TABLE runtime.step_attempt IS '节点尝试表，节点领取和执行尝试。';
COMMENT ON TABLE runtime.checkpoint IS '运行检查点表，可恢复的结构化快照。';
COMMENT ON TABLE runtime.context_artifact IS '上下文产物表，外置产物的不可变版本与校验摘要。';
COMMENT ON TABLE runtime.checkpoint_artifact IS '检查点产物绑定表，快照与同 Run 产物的绑定。';
COMMENT ON TABLE runtime.iteration IS '任务迭代表，Ralph 迭代上下文与结果。';
COMMENT ON TABLE runtime.gate_check IS '验收检查表，验收规则逐项判定的证据。';
COMMENT ON TABLE runtime.run_event IS '运行事件表，可补发的 Run 进度事件。';
COMMENT ON TABLE runtime.run_command IS '运行命令表，取消和对账命令的幂等回执。';
COMMENT ON TABLE runtime.workflow_skill IS '工作流技能表，企业 SOP 的稳定注册标识。';
COMMENT ON TABLE runtime.workflow_skill_version IS '工作流技能版本表，清单、编译 DAG 与审核发布记录。';
COMMENT ON TABLE runtime.workflow_skill_trigger IS '工作流技能触发器表，命令、表单或精确别名映射。';
COMMENT ON TABLE runtime.workflow_skill_tool IS '工作流技能工具绑定表，Skill 节点到工具版本的绑定。';

COMMENT ON TABLE gateway.tool_server IS '工具服务表，HTTP、MCP 等受管工具服务。';
COMMENT ON TABLE gateway.tool IS '工具注册表，工具身份、风险级别与当前版本。';
COMMENT ON TABLE gateway.tool_version IS '工具版本表，版本化输入输出契约。';
COMMENT ON TABLE gateway.tool_endpoint IS '工具端点表，协议端点和凭证引用。';
COMMENT ON TABLE gateway.tool_permission IS '工具权限表，用户、角色、Agent 和服务的工具授权。';
COMMENT ON TABLE gateway.tool_policy IS '工具策略表，工具约束、审批、限额与重试策略。';
COMMENT ON TABLE gateway.tool_execution IS '工具调用表，调用身份、状态、幂等与下游关联。';
COMMENT ON TABLE gateway.tool_approval IS '工具审批表，具体工具执行的审批绑定与裁决。';
COMMENT ON TABLE gateway.callback_receipt IS '回调收据表，下游回调的去重收据。';
COMMENT ON TABLE gateway.audit_entry IS '网关审计表，工具相关的操作与安全审计。';

COMMENT ON TABLE eventing.outbox IS '待投递事件表，与业务写入同事务保存的待发布消息。';
COMMENT ON TABLE eventing.inbox IS '消费去重表，消费事务的去重凭据。';
COMMENT ON TABLE eventing.delivery_failure IS '事件投递失败表，不可处理的消息及重放依据。';

COMMENT ON TABLE memory.conversation_summary IS '会话摘要表，对话摘要的历史代次、覆盖水位和内容位置。';
COMMENT ON TABLE memory.profile_item IS '用户档案记忆表，带来源和版本的用户档案字段。';
COMMENT ON TABLE memory.task_episode IS '任务情节记忆表，审核后的可复用任务经验。';
COMMENT ON TABLE memory.knowledge_document IS '知识文档表，知识条目身份及访问策略。';
COMMENT ON TABLE memory.knowledge_version IS '知识文档版本表，源文档有效版本和内容摘要。';
COMMENT ON TABLE memory.knowledge_chunk IS '知识文档分块表，版本内的分块和引用。';

-- 字段注释与数据字典中的字段说明对应。
COMMENT ON COLUMN intent.conversation.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN intent.conversation.id IS '本表实体标识，通常与租户组成主键。';
COMMENT ON COLUMN intent.conversation.owner_user_id IS '会话或记忆的所属用户。';
COMMENT ON COLUMN intent.conversation.last_message_seq IS '会话当前已分配的最大消息序号。';
COMMENT ON COLUMN intent.conversation.history_revision IS '原文发生变化时递增的会话代次。';
COMMENT ON COLUMN intent.conversation.created_at IS '记录创建时间。';

COMMENT ON COLUMN intent.turn.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN intent.turn.id IS '本表实体标识，通常与租户组成主键。';
COMMENT ON COLUMN intent.turn.conversation_id IS '所属会话标识。';
COMMENT ON COLUMN intent.turn.actor_user_id IS '触发本轮或命令的用户标识。';
COMMENT ON COLUMN intent.turn.client_key IS '客户端提交幂等键。';
COMMENT ON COLUMN intent.turn.request_hash IS '规范化请求指纹，用于排查重复键冲突。';
COMMENT ON COLUMN intent.turn.status IS '本表对象在其生命周期中的当前状态。';
COMMENT ON COLUMN intent.turn.next_at IS '下次可领取、重试或复核的时间。';
COMMENT ON COLUMN intent.turn.lease_owner IS '当前任务领取者。';
COMMENT ON COLUMN intent.turn.lease_until IS '当前领取有效期。';
COMMENT ON COLUMN intent.turn.lease_epoch IS '拒绝旧领取者提交的递增租约代次。';
COMMENT ON COLUMN intent.turn.attempts IS '已尝试领取或投递次数。';
COMMENT ON COLUMN intent.turn.receipt IS '供重复请求返回的已持久化回执。';
COMMENT ON COLUMN intent.turn.created_at IS '记录创建时间。';

COMMENT ON COLUMN intent.message.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN intent.message.id IS '本表实体标识，通常与租户组成主键。';
COMMENT ON COLUMN intent.message.conversation_id IS '所属会话标识。';
COMMENT ON COLUMN intent.message.turn_id IS '所属用户输入轮次。';
COMMENT ON COLUMN intent.message.seq IS '所属会话或 Run 中的顺序号。';
COMMENT ON COLUMN intent.message.role IS '会话消息角色。';
COMMENT ON COLUMN intent.message.position IS '同一轮中的消息位置。';
COMMENT ON COLUMN intent.message.body_ref IS '原始消息正文的受控引用。';
COMMENT ON COLUMN intent.message.redacted_at IS '消息正文被撤回或脱敏的时间。';
COMMENT ON COLUMN intent.message.reply_to_id IS '同一会话中被回复的消息。';
COMMENT ON COLUMN intent.message.created_at IS '记录创建时间。';

COMMENT ON COLUMN intent.definition.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN intent.definition.id IS '本表实体标识，通常与租户组成主键。';
COMMENT ON COLUMN intent.definition.domain IS '业务领域编码。';
COMMENT ON COLUMN intent.definition.action IS '动作编码或授权动作。';
COMMENT ON COLUMN intent.definition.version IS '表内实体版本或状态修订号。';
COMMENT ON COLUMN intent.definition.status IS '本表对象在其生命周期中的当前状态。';
COMMENT ON COLUMN intent.definition.is_current IS '是否是新请求的默认版本。';
COMMENT ON COLUMN intent.definition.parameter_schema IS '已发布的意图参数 JSON Schema。';
COMMENT ON COLUMN intent.definition.route_config IS '意图定义的路由配置。';
COMMENT ON COLUMN intent.definition.created_at IS '记录创建时间。';

COMMENT ON COLUMN intent.intent_record.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN intent.intent_record.id IS '本表实体标识，通常与租户组成主键。';
COMMENT ON COLUMN intent.intent_record.conversation_id IS '所属会话标识。';
COMMENT ON COLUMN intent.intent_record.origin_turn_id IS '原始业务意图创建轮次。';
COMMENT ON COLUMN intent.intent_record.definition_id IS '已固定的意图定义标识。';
COMMENT ON COLUMN intent.intent_record.created_at IS '记录创建时间。';

COMMENT ON COLUMN intent.task.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN intent.task.id IS '本表实体标识，通常与租户组成主键。';
COMMENT ON COLUMN intent.task.conversation_id IS '所属会话标识。';
COMMENT ON COLUMN intent.task.intent_id IS '对应的业务意图标识。';
COMMENT ON COLUMN intent.task.parent_task_id IS '后继任务关联的原任务。';
COMMENT ON COLUMN intent.task.status IS '本表对象在其生命周期中的当前状态。';
COMMENT ON COLUMN intent.task.input_version IS 'Task 参数版本。';
COMMENT ON COLUMN intent.task.revision IS '实体修订号。';
COMMENT ON COLUMN intent.task.created_at IS '记录创建时间。';
COMMENT ON COLUMN intent.task.updated_at IS '最近一次业务更新的时间。';

COMMENT ON COLUMN intent.candidate.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN intent.candidate.id IS '本表实体标识，通常与租户组成主键。';
COMMENT ON COLUMN intent.candidate.conversation_id IS '所属会话标识。';
COMMENT ON COLUMN intent.candidate.turn_id IS '所属用户输入轮次。';
COMMENT ON COLUMN intent.candidate.ordinal IS '同一轮的候选或同一版本的分块序号。';
COMMENT ON COLUMN intent.candidate.draft IS '结构化意图候选草案。';
COMMENT ON COLUMN intent.candidate.relation IS '候选和历史任务之间的关系。';
COMMENT ON COLUMN intent.candidate.target_task_id IS '当前候选关联的目标 Task。';
COMMENT ON COLUMN intent.candidate.target_clarification_id IS '关联的槽位澄清问题。';
COMMENT ON COLUMN intent.candidate.resolves_question_id IS '本候选试图解决的消歧问题。';
COMMENT ON COLUMN intent.candidate.evidence IS '关系判断采用的证据。';
COMMENT ON COLUMN intent.candidate.confidence IS '上下文关系判断的置信度。';
COMMENT ON COLUMN intent.candidate.status IS '本表对象在其生命周期中的当前状态。';
COMMENT ON COLUMN intent.candidate.outcome IS '已提交的候选级处理结果。';

COMMENT ON COLUMN intent.routing_assessment.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN intent.routing_assessment.id IS '本表实体标识，通常与租户组成主键。';
COMMENT ON COLUMN intent.routing_assessment.conversation_id IS '所属会话标识。';
COMMENT ON COLUMN intent.routing_assessment.turn_id IS '所属用户输入轮次。';
COMMENT ON COLUMN intent.routing_assessment.candidate_id IS '本次路由评估所对应的候选。';
COMMENT ON COLUMN intent.routing_assessment.comparison_key IS '约束滞回可比较范围的摘要键。';
COMMENT ON COLUMN intent.routing_assessment.prior_assessment_id IS '滞回复用的前一次识别评估。';
COMMENT ON COLUMN intent.routing_assessment.complexity_score IS '归一化识别复杂度。';
COMMENT ON COLUMN intent.routing_assessment.match_confidence IS '候选匹配置信度，与复杂度分开计算。';
COMMENT ON COLUMN intent.routing_assessment.score_band IS '生成 `。';
COMMENT ON COLUMN intent.routing_assessment.applied_strategy IS '本次实际采用的识别策略。';
COMMENT ON COLUMN intent.routing_assessment.score_config_version IS '评分及校准配置版本。';
COMMENT ON COLUMN intent.routing_assessment.critical_risk_override IS '是否由确定性极高风险策略接管。';
COMMENT ON COLUMN intent.routing_assessment.reason_codes IS '可审计的识别判断原因码。';
COMMENT ON COLUMN intent.routing_assessment.created_at IS '记录创建时间。';

COMMENT ON COLUMN intent.task_input.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN intent.task_input.task_id IS '所属业务任务标识。';
COMMENT ON COLUMN intent.task_input.version IS '表内实体版本或状态修订号。';
COMMENT ON COLUMN intent.task_input.conversation_id IS '所属会话标识。';
COMMENT ON COLUMN intent.task_input.source_candidate_id IS '导致本次持久化变更的候选。';
COMMENT ON COLUMN intent.task_input.workflow_skill_version_id IS 'Task 或 Plan 固定使用的 SOP Skill 版本。';
COMMENT ON COLUMN intent.task_input.slots IS '该输入版本的结构化槽位。';
COMMENT ON COLUMN intent.task_input.slot_provenance IS '各槽位的来源与证据位置。';
COMMENT ON COLUMN intent.task_input.slot_state IS '槽位待填、冲突或校验状态。';
COMMENT ON COLUMN intent.task_input.validated_ir IS '按定义校验后的结构化意图。';
COMMENT ON COLUMN intent.task_input.created_at IS '记录创建时间。';

COMMENT ON COLUMN intent.clarification.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN intent.clarification.id IS '本表实体标识，通常与租户组成主键。';
COMMENT ON COLUMN intent.clarification.conversation_id IS '所属会话标识。';
COMMENT ON COLUMN intent.clarification.source_candidate_id IS '导致本次持久化变更的候选。';
COMMENT ON COLUMN intent.clarification.task_id IS '所属业务任务标识。';
COMMENT ON COLUMN intent.clarification.input_version IS 'Task 参数版本。';
COMMENT ON COLUMN intent.clarification.kind IS '本表所定义的对象或操作子类型。';
COMMENT ON COLUMN intent.clarification.status IS '本表对象在其生命周期中的当前状态。';
COMMENT ON COLUMN intent.clarification.question IS '澄清问题与待回答字段。';
COMMENT ON COLUMN intent.clarification.expires_at IS '可使用或裁决的截止时间。';
COMMENT ON COLUMN intent.clarification.created_at IS '记录创建时间。';

COMMENT ON COLUMN intent.confirmation.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN intent.confirmation.id IS '本表实体标识，通常与租户组成主键。';
COMMENT ON COLUMN intent.confirmation.task_id IS '所属业务任务标识。';
COMMENT ON COLUMN intent.confirmation.input_version IS 'Task 参数版本。';
COMMENT ON COLUMN intent.confirmation.requester_user_id IS '发起意图级确认的用户标识。';
COMMENT ON COLUMN intent.confirmation.operation_hash IS '调用版本、身份及参数的绑定摘要。';
COMMENT ON COLUMN intent.confirmation.status IS '本表对象在其生命周期中的当前状态。';
COMMENT ON COLUMN intent.confirmation.expires_at IS '可使用或裁决的截止时间。';
COMMENT ON COLUMN intent.confirmation.decided_at IS '裁决时间。';
COMMENT ON COLUMN intent.confirmation.created_at IS '记录创建时间。';

COMMENT ON COLUMN intent.route_decision.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN intent.route_decision.id IS '本表实体标识，通常与租户组成主键。';
COMMENT ON COLUMN intent.route_decision.task_id IS '所属业务任务标识。';
COMMENT ON COLUMN intent.route_decision.input_version IS 'Task 参数版本。';
COMMENT ON COLUMN intent.route_decision.policy_version IS '准入或审批策略版本。';
COMMENT ON COLUMN intent.route_decision.verdict IS '业务准入或 Gate 检查的裁决。';
COMMENT ON COLUMN intent.route_decision.route IS '已选的执行路由。';
COMMENT ON COLUMN intent.route_decision.reasons IS '策略判断依据。';
COMMENT ON COLUMN intent.route_decision.created_at IS '记录创建时间。';

COMMENT ON COLUMN runtime.run.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN runtime.run.id IS '本表实体标识，通常与租户组成主键。';
COMMENT ON COLUMN runtime.run.task_id IS '所属业务任务标识。';
COMMENT ON COLUMN runtime.run.input_version IS 'Task 参数版本。';
COMMENT ON COLUMN runtime.run.route_decision_id IS 'Run 所绑定的业务准入决定。';
COMMENT ON COLUMN runtime.run.route IS '已选的执行路由。';
COMMENT ON COLUMN runtime.run.admitted_verdict IS '入场许可断言。';
COMMENT ON COLUMN runtime.run.user_id IS '当前记录的业务用户标识。';
COMMENT ON COLUMN runtime.run.agent_id IS '调用链上的 Agent 标识。';
COMMENT ON COLUMN runtime.run.trace_id IS '观测系统中的请求链路标识。';
COMMENT ON COLUMN runtime.run.graph_version IS 'Run 使用的图定义版本。';
COMMENT ON COLUMN runtime.run.active_plan_version IS '当前有效计划版本。';
COMMENT ON COLUMN runtime.run.status IS '本表对象在其生命周期中的当前状态。';
COMMENT ON COLUMN runtime.run.version IS '表内实体版本或状态修订号。';
COMMENT ON COLUMN runtime.run.last_event_seq IS '同一 Run 已提交的最后一个进度事件序号。';
COMMENT ON COLUMN runtime.run.gate_policy_version IS '验收规则集版本。';
COMMENT ON COLUMN runtime.run.gate_pass_iteration IS 'Run 成功所依据的通过验收迭代。';
COMMENT ON COLUMN runtime.run.gate_pass_status IS '用于外键断言的通过状态常量。';
COMMENT ON COLUMN runtime.run.max_iterations IS '允许的最大迭代次数。';
COMMENT ON COLUMN runtime.run.budget_limits IS '运行预算上限。';
COMMENT ON COLUMN runtime.run.budget_used IS '已消耗的运行或迭代预算。';
COMMENT ON COLUMN runtime.run.deadline_at IS '运行总截止时间。';
COMMENT ON COLUMN runtime.run.cancel_requested_at IS '收到取消请求的时间。';
COMMENT ON COLUMN runtime.run.terminal_reason IS 'Run 终态的结构化原因。';
COMMENT ON COLUMN runtime.run.result_ref IS '已授权输出的受控结果引用。';
COMMENT ON COLUMN runtime.run.created_at IS '记录创建时间。';
COMMENT ON COLUMN runtime.run.finished_at IS '当前运行、节点尝试或迭代终结的时间。';

COMMENT ON COLUMN runtime.plan.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN runtime.plan.run_id IS '所属 Run 标识。';
COMMENT ON COLUMN runtime.plan.version IS '表内实体版本或状态修订号。';
COMMENT ON COLUMN runtime.plan.origin IS '计划来自直接执行、固定流程还是动态规划。';
COMMENT ON COLUMN runtime.plan.proposal_id IS '发布计划所关联的规划提案。';
COMMENT ON COLUMN runtime.plan.workflow_skill_version_id IS 'Task 或 Plan 固定使用的 SOP Skill 版本。';
COMMENT ON COLUMN runtime.plan.definition_ref IS '内置流程或定义引用。';
COMMENT ON COLUMN runtime.plan.plan_hash IS '计划内容的完整性摘要。';
COMMENT ON COLUMN runtime.plan.created_at IS '记录创建时间。';

COMMENT ON COLUMN runtime.plan_proposal.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN runtime.plan_proposal.run_id IS '所属 Run 标识。';
COMMENT ON COLUMN runtime.plan_proposal.id IS '本表实体标识，通常与租户组成主键。';
COMMENT ON COLUMN runtime.plan_proposal.base_plan_version IS '动态提案所基于的计划版本。';
COMMENT ON COLUMN runtime.plan_proposal.kind IS '本表所定义的对象或操作子类型。';
COMMENT ON COLUMN runtime.plan_proposal.trigger_reason IS '请求规划或重规划的原因。';
COMMENT ON COLUMN runtime.plan_proposal.planner_version IS '生成提案所用的规划器版本。';
COMMENT ON COLUMN runtime.plan_proposal.context_hash IS '组装上下文的摘要。';
COMMENT ON COLUMN runtime.plan_proposal.context_ref IS '规划输入上下文引用。';
COMMENT ON COLUMN runtime.plan_proposal.draft_ref IS '规划模型产出草案的受控引用。';
COMMENT ON COLUMN runtime.plan_proposal.status IS '本表对象在其生命周期中的当前状态。';
COMMENT ON COLUMN runtime.plan_proposal.validation_result IS '计划提案的校验结果与原因。';
COMMENT ON COLUMN runtime.plan_proposal.next_at IS '下次可领取、重试或复核的时间。';
COMMENT ON COLUMN runtime.plan_proposal.lease_epoch IS '拒绝旧领取者提交的递增租约代次。';
COMMENT ON COLUMN runtime.plan_proposal.lease_owner IS '当前任务领取者。';
COMMENT ON COLUMN runtime.plan_proposal.lease_until IS '当前领取有效期。';
COMMENT ON COLUMN runtime.plan_proposal.attempts IS '已尝试领取或投递次数。';
COMMENT ON COLUMN runtime.plan_proposal.created_at IS '记录创建时间。';
COMMENT ON COLUMN runtime.plan_proposal.settled_at IS '提案处理结算时间。';

COMMENT ON COLUMN runtime.step.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN runtime.step.id IS '本表实体标识，通常与租户组成主键。';
COMMENT ON COLUMN runtime.step.run_id IS '所属 Run 标识。';
COMMENT ON COLUMN runtime.step.plan_version IS 'Run 内的计划版本。';
COMMENT ON COLUMN runtime.step.node_key IS '计划或 Skill 内稳定节点键。';
COMMENT ON COLUMN runtime.step.kind IS '本表所定义的对象或操作子类型。';
COMMENT ON COLUMN runtime.step.spec IS '已校验的节点类型、参数及分支配置。';
COMMENT ON COLUMN runtime.step.status IS '本表对象在其生命周期中的当前状态。';
COMMENT ON COLUMN runtime.step.operation_key IS '跨重试保持稳定的逻辑操作键。';
COMMENT ON COLUMN runtime.step.tool_execution_id IS '本步骤绑定的网关执行标识。';
COMMENT ON COLUMN runtime.step.observation_ref IS '已保存的节点观测结果引用。';
COMMENT ON COLUMN runtime.step.current_attempt IS '当前步骤的尝试序号。';
COMMENT ON COLUMN runtime.step.next_at IS '下次可领取、重试或复核的时间。';
COMMENT ON COLUMN runtime.step.lease_epoch IS '拒绝旧领取者提交的递增租约代次。';
COMMENT ON COLUMN runtime.step.lease_owner IS '当前任务领取者。';
COMMENT ON COLUMN runtime.step.lease_until IS '当前领取有效期。';
COMMENT ON COLUMN runtime.step.updated_at IS '最近一次业务更新的时间。';

COMMENT ON COLUMN runtime.step_dependency.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN runtime.step_dependency.run_id IS '所属 Run 标识。';
COMMENT ON COLUMN runtime.step_dependency.plan_version IS 'Run 内的计划版本。';
COMMENT ON COLUMN runtime.step_dependency.step_id IS '所属的运行节点标识。';
COMMENT ON COLUMN runtime.step_dependency.depends_on_step_id IS '当前节点的前置步骤。';

COMMENT ON COLUMN runtime.step_attempt.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN runtime.step_attempt.step_id IS '所属的运行节点标识。';
COMMENT ON COLUMN runtime.step_attempt.attempt_no IS '节点尝试序号。';
COMMENT ON COLUMN runtime.step_attempt.lease_epoch IS '拒绝旧领取者提交的递增租约代次。';
COMMENT ON COLUMN runtime.step_attempt.worker_id IS '本次步骤尝试的 Worker 标识。';
COMMENT ON COLUMN runtime.step_attempt.status IS '本表对象在其生命周期中的当前状态。';
COMMENT ON COLUMN runtime.step_attempt.error_code IS '执行或尝试的结构化错误码。';
COMMENT ON COLUMN runtime.step_attempt.started_at IS '实际开始执行的时间。';
COMMENT ON COLUMN runtime.step_attempt.finished_at IS '当前运行、节点尝试或迭代终结的时间。';

COMMENT ON COLUMN runtime.checkpoint.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN runtime.checkpoint.run_id IS '所属 Run 标识。';
COMMENT ON COLUMN runtime.checkpoint.seq IS '所属会话或 Run 中的顺序号。';
COMMENT ON COLUMN runtime.checkpoint.plan_version IS 'Run 内的计划版本。';
COMMENT ON COLUMN runtime.checkpoint.after_step_id IS '保存快照前最后结算的步骤。';
COMMENT ON COLUMN runtime.checkpoint.graph_thread_key IS 'LangGraph4j 图执行线程关联键。';
COMMENT ON COLUMN runtime.checkpoint.format_version IS '快照或产物格式版本。';
COMMENT ON COLUMN runtime.checkpoint.graph_state IS '可恢复的结构化小型图状态。';
COMMENT ON COLUMN runtime.checkpoint.created_at IS '记录创建时间。';

COMMENT ON COLUMN runtime.context_artifact.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN runtime.context_artifact.run_id IS '所属 Run 标识。';
COMMENT ON COLUMN runtime.context_artifact.id IS '本表实体标识，通常与租户组成主键。';
COMMENT ON COLUMN runtime.context_artifact.step_id IS '所属的运行节点标识。';
COMMENT ON COLUMN runtime.context_artifact.artifact_key IS '同个 Run 内稳定的产物业务键。';
COMMENT ON COLUMN runtime.context_artifact.version IS '表内实体版本或状态修订号。';
COMMENT ON COLUMN runtime.context_artifact.kind IS '本表所定义的对象或操作子类型。';
COMMENT ON COLUMN runtime.context_artifact.content_ref IS '私有存储中的内容引用。';
COMMENT ON COLUMN runtime.context_artifact.content_digest IS '原始内容完整性摘要。';
COMMENT ON COLUMN runtime.context_artifact.media_type IS '产物内容类型。';
COMMENT ON COLUMN runtime.context_artifact.format_version IS '快照或产物格式版本。';
COMMENT ON COLUMN runtime.context_artifact.size_bytes IS '内容大小，单位字节。';
COMMENT ON COLUMN runtime.context_artifact.created_at IS '记录创建时间。';

COMMENT ON COLUMN runtime.checkpoint_artifact.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN runtime.checkpoint_artifact.run_id IS '所属 Run 标识。';
COMMENT ON COLUMN runtime.checkpoint_artifact.checkpoint_seq IS 'Run 内的 Checkpoint 序号。';
COMMENT ON COLUMN runtime.checkpoint_artifact.artifact_id IS '外置产物标识。';
COMMENT ON COLUMN runtime.checkpoint_artifact.binding_key IS '快照内用于恢复产物的绑定键。';

COMMENT ON COLUMN runtime.iteration.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN runtime.iteration.run_id IS '所属 Run 标识。';
COMMENT ON COLUMN runtime.iteration.iteration_no IS '同一 Run 内的迭代号。';
COMMENT ON COLUMN runtime.iteration.plan_version IS 'Run 内的计划版本。';
COMMENT ON COLUMN runtime.iteration.gate_policy_version IS '验收规则集版本。';
COMMENT ON COLUMN runtime.iteration.context_hash IS '组装上下文的摘要。';
COMMENT ON COLUMN runtime.iteration.context_manifest IS '本轮上下文组成和受控引用。';
COMMENT ON COLUMN runtime.iteration.status IS '本表对象在其生命周期中的当前状态。';
COMMENT ON COLUMN runtime.iteration.budget_used IS '已消耗的运行或迭代预算。';
COMMENT ON COLUMN runtime.iteration.created_at IS '记录创建时间。';
COMMENT ON COLUMN runtime.iteration.finished_at IS '当前运行、节点尝试或迭代终结的时间。';

COMMENT ON COLUMN runtime.gate_check.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN runtime.gate_check.run_id IS '所属 Run 标识。';
COMMENT ON COLUMN runtime.gate_check.iteration_no IS '同一 Run 内的迭代号。';
COMMENT ON COLUMN runtime.gate_check.plan_version IS 'Run 内的计划版本。';
COMMENT ON COLUMN runtime.gate_check.check_key IS '验收规则项的稳定键。';
COMMENT ON COLUMN runtime.gate_check.verifier_version IS '执行该检查的验收器版本。';
COMMENT ON COLUMN runtime.gate_check.required IS '本项验收是否为必须项。';
COMMENT ON COLUMN runtime.gate_check.verdict IS '业务准入或 Gate 检查的裁决。';
COMMENT ON COLUMN runtime.gate_check.evidence_ref IS '检查证据的受控引用。';
COMMENT ON COLUMN runtime.gate_check.checked_at IS '验收检查时间。';

COMMENT ON COLUMN runtime.run_event.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN runtime.run_event.run_id IS '所属 Run 标识。';
COMMENT ON COLUMN runtime.run_event.seq IS '所属会话或 Run 中的顺序号。';
COMMENT ON COLUMN runtime.run_event.event_id IS '领域事件的幂等标识。';
COMMENT ON COLUMN runtime.run_event.event_type IS '事件名称。';
COMMENT ON COLUMN runtime.run_event.schema_version IS '事件或信封结构版本。';
COMMENT ON COLUMN runtime.run_event.payload IS '受控的事件载荷。';
COMMENT ON COLUMN runtime.run_event.occurred_at IS '事件或审计事实发生时间。';

COMMENT ON COLUMN runtime.run_command.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN runtime.run_command.run_id IS '所属 Run 标识。';
COMMENT ON COLUMN runtime.run_command.actor_user_id IS '触发本轮或命令的用户标识。';
COMMENT ON COLUMN runtime.run_command.command_key IS '用户运行控制命令的幂等键。';
COMMENT ON COLUMN runtime.run_command.command_type IS '取消或对账等运行命令类型。';
COMMENT ON COLUMN runtime.run_command.request_hash IS '规范化请求指纹，用于排查重复键冲突。';
COMMENT ON COLUMN runtime.run_command.receipt IS '供重复请求返回的已持久化回执。';
COMMENT ON COLUMN runtime.run_command.created_at IS '记录创建时间。';

COMMENT ON COLUMN runtime.workflow_skill.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN runtime.workflow_skill.id IS '本表实体标识，通常与租户组成主键。';
COMMENT ON COLUMN runtime.workflow_skill.skill_key IS '业务可引用的稳定 Skill 键。';
COMMENT ON COLUMN runtime.workflow_skill.display_name IS '面向使用者的显示名称。';
COMMENT ON COLUMN runtime.workflow_skill.status IS '本表对象在其生命周期中的当前状态。';
COMMENT ON COLUMN runtime.workflow_skill.revision IS '实体修订号。';
COMMENT ON COLUMN runtime.workflow_skill.created_at IS '记录创建时间。';

COMMENT ON COLUMN runtime.workflow_skill_version.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN runtime.workflow_skill_version.id IS '本表实体标识，通常与租户组成主键。';
COMMENT ON COLUMN runtime.workflow_skill_version.skill_id IS 'SOP Skill 的稳定标识。';
COMMENT ON COLUMN runtime.workflow_skill_version.version IS '表内实体版本或状态修订号。';
COMMENT ON COLUMN runtime.workflow_skill_version.intent_definition_id IS 'SOP 绑定的已发布意图定义。';
COMMENT ON COLUMN runtime.workflow_skill_version.execution_mode IS 'Skill 可用的固定执行模式。';
COMMENT ON COLUMN runtime.workflow_skill_version.status IS '本表对象在其生命周期中的当前状态。';
COMMENT ON COLUMN runtime.workflow_skill_version.is_current IS '是否是新请求的默认版本。';
COMMENT ON COLUMN runtime.workflow_skill_version.source_ref IS '源文件、档案来源或源文档的引用。';
COMMENT ON COLUMN runtime.workflow_skill_version.admission_policy_ref IS 'SOP 业务准入策略引用。';
COMMENT ON COLUMN runtime.workflow_skill_version.compiled_dag IS '可执行的已编译固定 DAG。';
COMMENT ON COLUMN runtime.workflow_skill_version.compiled_digest IS '编译产物摘要。';
COMMENT ON COLUMN runtime.workflow_skill_version.reviewed_by IS 'SOP 版本审核人。';
COMMENT ON COLUMN runtime.workflow_skill_version.reviewed_at IS 'SOP 版本通过审核的时间。';
COMMENT ON COLUMN runtime.workflow_skill_version.published_by IS '发布 SOP 版本的操作者。';
COMMENT ON COLUMN runtime.workflow_skill_version.created_at IS '记录创建时间。';
COMMENT ON COLUMN runtime.workflow_skill_version.published_at IS '生效发布时间或消息确认投递时间。';

COMMENT ON COLUMN runtime.workflow_skill_trigger.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN runtime.workflow_skill_trigger.trigger_kind IS 'L0 触发器类型。';
COMMENT ON COLUMN runtime.workflow_skill_trigger.normalized_key IS '确定性触发器的规范化匹配键。';
COMMENT ON COLUMN runtime.workflow_skill_trigger.skill_id IS 'SOP Skill 的稳定标识。';

COMMENT ON COLUMN runtime.workflow_skill_tool.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN runtime.workflow_skill_tool.skill_version_id IS 'Skill 的发布版本标识。';
COMMENT ON COLUMN runtime.workflow_skill_tool.node_key IS '计划或 Skill 内稳定节点键。';
COMMENT ON COLUMN runtime.workflow_skill_tool.tool_id IS '工具稳定标识。';
COMMENT ON COLUMN runtime.workflow_skill_tool.tool_version_id IS '已绑定的具体工具版本。';

COMMENT ON COLUMN gateway.tool_server.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN gateway.tool_server.id IS '本表实体标识，通常与租户组成主键。';
COMMENT ON COLUMN gateway.tool_server.name IS '注册名称。';
COMMENT ON COLUMN gateway.tool_server.protocol IS '工具接入协议。';
COMMENT ON COLUMN gateway.tool_server.status IS '本表对象在其生命周期中的当前状态。';

COMMENT ON COLUMN gateway.tool.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN gateway.tool.id IS '本表实体标识，通常与租户组成主键。';
COMMENT ON COLUMN gateway.tool.name IS '注册名称。';
COMMENT ON COLUMN gateway.tool.display_name IS '面向使用者的显示名称。';
COMMENT ON COLUMN gateway.tool.description IS '注册的业务描述。';
COMMENT ON COLUMN gateway.tool.category IS '工具分类。';
COMMENT ON COLUMN gateway.tool.risk_level IS '管理端配置的工具风险等级。';
COMMENT ON COLUMN gateway.tool.status IS '本表对象在其生命周期中的当前状态。';
COMMENT ON COLUMN gateway.tool.current_version_id IS '当前生效版本标识。';
COMMENT ON COLUMN gateway.tool.revision IS '实体修订号。';

COMMENT ON COLUMN gateway.tool_version.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN gateway.tool_version.id IS '本表实体标识，通常与租户组成主键。';
COMMENT ON COLUMN gateway.tool_version.tool_id IS '工具稳定标识。';
COMMENT ON COLUMN gateway.tool_version.version_name IS '工具可公开引用的版本名称。';
COMMENT ON COLUMN gateway.tool_version.status IS '本表对象在其生命周期中的当前状态。';
COMMENT ON COLUMN gateway.tool_version.input_schema IS '工具参数的权威 JSON Schema。';
COMMENT ON COLUMN gateway.tool_version.output_schema IS '工具输出契约。';
COMMENT ON COLUMN gateway.tool_version.contract_hash IS '工具版本输入输出契约摘要。';
COMMENT ON COLUMN gateway.tool_version.published_at IS '生效发布时间或消息确认投递时间。';

COMMENT ON COLUMN gateway.tool_endpoint.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN gateway.tool_endpoint.id IS '本表实体标识，通常与租户组成主键。';
COMMENT ON COLUMN gateway.tool_endpoint.version_id IS '所绑定的工具版本主键。';
COMMENT ON COLUMN gateway.tool_endpoint.server_id IS '受管工具服务器标识。';
COMMENT ON COLUMN gateway.tool_endpoint.protocol IS '工具接入协议。';
COMMENT ON COLUMN gateway.tool_endpoint.target_ref IS '已注册的端点或路由目标引用。';
COMMENT ON COLUMN gateway.tool_endpoint.credential_ref IS 'Secret Manager 中的凭证引用。';
COMMENT ON COLUMN gateway.tool_endpoint.timeout_ms IS '下游工具调用超时，单位毫秒。';
COMMENT ON COLUMN gateway.tool_endpoint.async_capable IS '端点是否提供异步提交契约。';
COMMENT ON COLUMN gateway.tool_endpoint.idempotency_supported IS '下游端点是否支持幂等契约。';
COMMENT ON COLUMN gateway.tool_endpoint.enabled IS '当前配置是否启用。';

COMMENT ON COLUMN gateway.tool_permission.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN gateway.tool_permission.tool_id IS '工具稳定标识。';
COMMENT ON COLUMN gateway.tool_permission.subject_type IS '授权对象类型。';
COMMENT ON COLUMN gateway.tool_permission.subject_id IS '授权对象标识。';
COMMENT ON COLUMN gateway.tool_permission.action IS '动作编码或授权动作。';
COMMENT ON COLUMN gateway.tool_permission.effect IS '授权的允许或拒绝效果。';
COMMENT ON COLUMN gateway.tool_permission.enabled IS '当前配置是否启用。';

COMMENT ON COLUMN gateway.tool_policy.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN gateway.tool_policy.id IS '本表实体标识，通常与租户组成主键。';
COMMENT ON COLUMN gateway.tool_policy.tool_id IS '工具稳定标识。';
COMMENT ON COLUMN gateway.tool_policy.policy_version IS '准入或审批策略版本。';
COMMENT ON COLUMN gateway.tool_policy.kind IS '本表所定义的对象或操作子类型。';
COMMENT ON COLUMN gateway.tool_policy.rule IS '受约束的结构化策略表达式。';
COMMENT ON COLUMN gateway.tool_policy.enabled IS '当前配置是否启用。';

COMMENT ON COLUMN gateway.tool_execution.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN gateway.tool_execution.id IS '本表实体标识，通常与租户组成主键。';
COMMENT ON COLUMN gateway.tool_execution.tool_id IS '工具稳定标识。';
COMMENT ON COLUMN gateway.tool_execution.version_id IS '所绑定的工具版本主键。';
COMMENT ON COLUMN gateway.tool_execution.endpoint_id IS '工具版本的实际执行端点标识。';
COMMENT ON COLUMN gateway.tool_execution.run_id IS '所属 Run 标识。';
COMMENT ON COLUMN gateway.tool_execution.step_id IS '所属的运行节点标识。';
COMMENT ON COLUMN gateway.tool_execution.agent_id IS '调用链上的 Agent 标识。';
COMMENT ON COLUMN gateway.tool_execution.principal_id IS '已认证调用主体标识。';
COMMENT ON COLUMN gateway.tool_execution.trace_id IS '观测系统中的请求链路标识。';
COMMENT ON COLUMN gateway.tool_execution.idempotency_key IS '逻辑工具操作的幂等键。';
COMMENT ON COLUMN gateway.tool_execution.operation_hash IS '调用版本、身份及参数的绑定摘要。';
COMMENT ON COLUMN gateway.tool_execution.arguments_ref IS '实际调用参数的受控内容引用。';
COMMENT ON COLUMN gateway.tool_execution.status IS '本表对象在其生命周期中的当前状态。';
COMMENT ON COLUMN gateway.tool_execution.version IS '表内实体版本或状态修订号。';
COMMENT ON COLUMN gateway.tool_execution.next_at IS '下次可领取、重试或复核的时间。';
COMMENT ON COLUMN gateway.tool_execution.external_job_id IS '下游异步任务标识。';
COMMENT ON COLUMN gateway.tool_execution.downstream_request_id IS '下游系统用于回查的请求标识。';
COMMENT ON COLUMN gateway.tool_execution.result_ref IS '已授权输出的受控结果引用。';
COMMENT ON COLUMN gateway.tool_execution.error_code IS '执行或尝试的结构化错误码。';
COMMENT ON COLUMN gateway.tool_execution.lease_epoch IS '拒绝旧领取者提交的递增租约代次。';
COMMENT ON COLUMN gateway.tool_execution.lease_owner IS '当前任务领取者。';
COMMENT ON COLUMN gateway.tool_execution.lease_until IS '当前领取有效期。';
COMMENT ON COLUMN gateway.tool_execution.cancel_requested_at IS '收到取消请求的时间。';
COMMENT ON COLUMN gateway.tool_execution.created_at IS '记录创建时间。';
COMMENT ON COLUMN gateway.tool_execution.finished_at IS '当前运行、节点尝试或迭代终结的时间。';

COMMENT ON COLUMN gateway.tool_approval.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN gateway.tool_approval.id IS '本表实体标识，通常与租户组成主键。';
COMMENT ON COLUMN gateway.tool_approval.execution_id IS '网关工具执行标识。';
COMMENT ON COLUMN gateway.tool_approval.version_id IS '所绑定的工具版本主键。';
COMMENT ON COLUMN gateway.tool_approval.operation_hash IS '调用版本、身份及参数的绑定摘要。';
COMMENT ON COLUMN gateway.tool_approval.requester_principal_id IS '等待审批的原工具调用主体。';
COMMENT ON COLUMN gateway.tool_approval.approver_selector IS '从组织数据定位审批人的选择条件。';
COMMENT ON COLUMN gateway.tool_approval.policy_version IS '准入或审批策略版本。';
COMMENT ON COLUMN gateway.tool_approval.status IS '本表对象在其生命周期中的当前状态。';
COMMENT ON COLUMN gateway.tool_approval.expires_at IS '可使用或裁决的截止时间。';
COMMENT ON COLUMN gateway.tool_approval.decided_by IS '实际作出审批裁决的主体。';
COMMENT ON COLUMN gateway.tool_approval.decided_at IS '裁决时间。';
COMMENT ON COLUMN gateway.tool_approval.created_at IS '记录创建时间。';

COMMENT ON COLUMN gateway.callback_receipt.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN gateway.callback_receipt.server_id IS '受管工具服务器标识。';
COMMENT ON COLUMN gateway.callback_receipt.provider_event_id IS '下游生成的回调事件标识。';
COMMENT ON COLUMN gateway.callback_receipt.execution_id IS '网关工具执行标识。';
COMMENT ON COLUMN gateway.callback_receipt.payload_hash IS '下游回调内容摘要。';
COMMENT ON COLUMN gateway.callback_receipt.received_at IS '回调接收时间。';

COMMENT ON COLUMN gateway.audit_entry.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN gateway.audit_entry.id IS '本表实体标识，通常与租户组成主键。';
COMMENT ON COLUMN gateway.audit_entry.execution_id IS '网关工具执行标识。';
COMMENT ON COLUMN gateway.audit_entry.tool_id IS '工具稳定标识。';
COMMENT ON COLUMN gateway.audit_entry.actor_id IS '审计操作者标识。';
COMMENT ON COLUMN gateway.audit_entry.action IS '动作编码或授权动作。';
COMMENT ON COLUMN gateway.audit_entry.trace_id IS '观测系统中的请求链路标识。';
COMMENT ON COLUMN gateway.audit_entry.detail IS '审计操作的结构化详情。';
COMMENT ON COLUMN gateway.audit_entry.occurred_at IS '事件或审计事实发生时间。';

COMMENT ON COLUMN eventing.outbox.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN eventing.outbox.event_id IS '领域事件的幂等标识。';
COMMENT ON COLUMN eventing.outbox.event_type IS '事件名称。';
COMMENT ON COLUMN eventing.outbox.schema_version IS '事件或信封结构版本。';
COMMENT ON COLUMN eventing.outbox.aggregate_type IS '事件所属业务聚合类型。';
COMMENT ON COLUMN eventing.outbox.aggregate_id IS '事件所属业务聚合标识。';
COMMENT ON COLUMN eventing.outbox.aggregate_version IS '事件对应业务聚合的修订号。';
COMMENT ON COLUMN eventing.outbox.stream IS 'Redis Streams 投递目标。';
COMMENT ON COLUMN eventing.outbox.trace_id IS '观测系统中的请求链路标识。';
COMMENT ON COLUMN eventing.outbox.correlation_id IS '贯穿同一业务链路的关联标识。';
COMMENT ON COLUMN eventing.outbox.causation_id IS '引发本事件的上游事件标识。';
COMMENT ON COLUMN eventing.outbox.payload IS '受控的事件载荷。';
COMMENT ON COLUMN eventing.outbox.occurred_at IS '事件或审计事实发生时间。';
COMMENT ON COLUMN eventing.outbox.next_at IS '下次可领取、重试或复核的时间。';
COMMENT ON COLUMN eventing.outbox.attempts IS '已尝试领取或投递次数。';
COMMENT ON COLUMN eventing.outbox.lease_epoch IS '拒绝旧领取者提交的递增租约代次。';
COMMENT ON COLUMN eventing.outbox.lease_owner IS '当前任务领取者。';
COMMENT ON COLUMN eventing.outbox.lease_until IS '当前领取有效期。';
COMMENT ON COLUMN eventing.outbox.published_at IS '生效发布时间或消息确认投递时间。';
COMMENT ON COLUMN eventing.outbox.message_id IS '流消息标识。';
COMMENT ON COLUMN eventing.outbox.last_error IS '最近一次发布或领取错误摘要。';

COMMENT ON COLUMN eventing.inbox.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN eventing.inbox.consumer_name IS '消费处理器标识。';
COMMENT ON COLUMN eventing.inbox.event_id IS '领域事件的幂等标识。';
COMMENT ON COLUMN eventing.inbox.processed_at IS '消费事务已完成的时间。';

COMMENT ON COLUMN eventing.delivery_failure.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN eventing.delivery_failure.consumer_name IS '消费处理器标识。';
COMMENT ON COLUMN eventing.delivery_failure.event_id IS '领域事件的幂等标识。';
COMMENT ON COLUMN eventing.delivery_failure.source_stream IS '消费失败对应的原流。';
COMMENT ON COLUMN eventing.delivery_failure.source_message_id IS '消费失败的原始流消息标识。';
COMMENT ON COLUMN eventing.delivery_failure.envelope IS '待排查消息的原始事件信封。';
COMMENT ON COLUMN eventing.delivery_failure.failure_code IS '消费失败的结构化原因。';
COMMENT ON COLUMN eventing.delivery_failure.failure_count IS '消费失败次数。';
COMMENT ON COLUMN eventing.delivery_failure.updated_at IS '最近一次业务更新的时间。';
COMMENT ON COLUMN eventing.delivery_failure.resolved_at IS '消费失败记录解决时间。';

COMMENT ON COLUMN memory.conversation_summary.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN memory.conversation_summary.conversation_id IS '所属会话标识。';
COMMENT ON COLUMN memory.conversation_summary.version IS '表内实体版本或状态修订号。';
COMMENT ON COLUMN memory.conversation_summary.history_revision IS '原文发生变化时递增的会话代次。';
COMMENT ON COLUMN memory.conversation_summary.covered_through_seq IS '对话摘要覆盖的最终消息序号。';
COMMENT ON COLUMN memory.conversation_summary.source_digest IS '生成摘要的源记录摘要。';
COMMENT ON COLUMN memory.conversation_summary.summarizer_version IS '摘要生成器或提示版本。';
COMMENT ON COLUMN memory.conversation_summary.summary_ref IS '滚动摘要正文的受控引用。';
COMMENT ON COLUMN memory.conversation_summary.created_at IS '记录创建时间。';

COMMENT ON COLUMN memory.profile_item.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN memory.profile_item.user_id IS '当前记录的业务用户标识。';
COMMENT ON COLUMN memory.profile_item.item_key IS '用户档案字段键。';
COMMENT ON COLUMN memory.profile_item.value IS '按档案字段约束存放的 JSON 值。';
COMMENT ON COLUMN memory.profile_item.source_type IS '档案事实的来源类型。';
COMMENT ON COLUMN memory.profile_item.source_ref IS '源文件、档案来源或源文档的引用。';
COMMENT ON COLUMN memory.profile_item.revision IS '实体修订号。';
COMMENT ON COLUMN memory.profile_item.updated_at IS '最近一次业务更新的时间。';
COMMENT ON COLUMN memory.profile_item.expires_at IS '可使用或裁决的截止时间。';

COMMENT ON COLUMN memory.task_episode.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN memory.task_episode.id IS '本表实体标识，通常与租户组成主键。';
COMMENT ON COLUMN memory.task_episode.source_run_id IS '生成脱敏案例的原 Run 标识，应用在提炼时校验。';
COMMENT ON COLUMN memory.task_episode.owner_user_id IS '会话或记忆的所属用户。';
COMMENT ON COLUMN memory.task_episode.scope IS '长期记忆的可见范围。';
COMMENT ON COLUMN memory.task_episode.scenario_key IS '任务案例的场景分类键。';
COMMENT ON COLUMN memory.task_episode.status IS '本表对象在其生命周期中的当前状态。';
COMMENT ON COLUMN memory.task_episode.revision IS '实体修订号。';
COMMENT ON COLUMN memory.task_episode.episode_ref IS '脱敏后的任务案例正文位置。';
COMMENT ON COLUMN memory.task_episode.created_at IS '记录创建时间。';
COMMENT ON COLUMN memory.task_episode.expires_at IS '可使用或裁决的截止时间。';

COMMENT ON COLUMN memory.knowledge_document.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN memory.knowledge_document.id IS '本表实体标识，通常与租户组成主键。';
COMMENT ON COLUMN memory.knowledge_document.collection_key IS '知识集合键。';
COMMENT ON COLUMN memory.knowledge_document.source_key IS '知识来源的外部稳定键。';
COMMENT ON COLUMN memory.knowledge_document.access_policy_ref IS '知识集合访问策略引用。';

COMMENT ON COLUMN memory.knowledge_version.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN memory.knowledge_version.document_id IS '知识文档稳定标识。';
COMMENT ON COLUMN memory.knowledge_version.version IS '表内实体版本或状态修订号。';
COMMENT ON COLUMN memory.knowledge_version.status IS '本表对象在其生命周期中的当前状态。';
COMMENT ON COLUMN memory.knowledge_version.source_ref IS '源文件、档案来源或源文档的引用。';
COMMENT ON COLUMN memory.knowledge_version.content_digest IS '原始内容完整性摘要。';
COMMENT ON COLUMN memory.knowledge_version.created_at IS '记录创建时间。';
COMMENT ON COLUMN memory.knowledge_version.published_at IS '生效发布时间或消息确认投递时间。';

COMMENT ON COLUMN memory.knowledge_chunk.tenant_id IS '可信租户标识，用于隔离业务记录。';
COMMENT ON COLUMN memory.knowledge_chunk.document_id IS '知识文档稳定标识。';
COMMENT ON COLUMN memory.knowledge_chunk.document_version IS '指定的知识文档版本。';
COMMENT ON COLUMN memory.knowledge_chunk.ordinal IS '同一轮的候选或同一版本的分块序号。';
COMMENT ON COLUMN memory.knowledge_chunk.chunk_ref IS '知识分块正文引用。';
COMMENT ON COLUMN memory.knowledge_chunk.content_digest IS '原始内容完整性摘要。';

CREATE TABLE intent.turn_execution (
    tenant_id text NOT NULL,
    conversation_id uuid NOT NULL,
    turn_id uuid NOT NULL,
    candidate_ordinal integer NOT NULL CHECK (candidate_ordinal >= 0),
    mode text NOT NULL CHECK (mode IN ('DIRECT', 'PLAN_AND_EXECUTE', 'WORKFLOW')),
    policy_version text NOT NULL,
    decision_reason text NOT NULL,
    tool_execution_id uuid,
    run_id uuid,
    blocked_reason text,
    PRIMARY KEY (tenant_id, conversation_id, turn_id, candidate_ordinal),
    FOREIGN KEY (tenant_id, conversation_id, turn_id) REFERENCES intent.turn (tenant_id, conversation_id, id),
    FOREIGN KEY (tenant_id, turn_id, candidate_ordinal) REFERENCES intent.candidate (tenant_id, turn_id, ordinal),
    FOREIGN KEY (tenant_id, tool_execution_id) REFERENCES gateway.tool_execution (tenant_id, id),
    FOREIGN KEY (tenant_id, run_id) REFERENCES runtime.run (tenant_id, id),
    CHECK (num_nonnulls(tool_execution_id, run_id, blocked_reason) = 1),
    CHECK (tool_execution_id IS NULL OR mode = 'DIRECT'),
    CHECK (run_id IS NULL OR mode IN ('PLAN_AND_EXECUTE', 'WORKFLOW'))
);
ALTER TABLE intent.turn ADD COLUMN trace_input jsonb CHECK (trace_input IS NULL OR jsonb_typeof(trace_input)='object');
CREATE INDEX task_input_source_candidate_idx ON intent.task_input (tenant_id, conversation_id, source_candidate_id);
COMMENT ON COLUMN intent.turn.trace_input IS '本轮实际的归一化文本与输入快照元数据，供授权会话查看。';
COMMENT ON TABLE intent.turn_execution IS '关联识别候选、执行目标或准入拒绝；执行正文由网关或运行时管理。';
COMMENT ON COLUMN intent.turn_execution.tenant_id IS '可信租户标识。';
COMMENT ON COLUMN intent.turn_execution.conversation_id IS '识别所在会话。';
COMMENT ON COLUMN intent.turn_execution.turn_id IS '触发派发的识别轮次。';
COMMENT ON COLUMN intent.turn_execution.candidate_ordinal IS '触发派发的候选在回执中的序号，从零开始。';
COMMENT ON COLUMN intent.turn_execution.mode IS '执行路由模式。';
COMMENT ON COLUMN intent.turn_execution.policy_version IS '执行路由策略版本。';
COMMENT ON COLUMN intent.turn_execution.decision_reason IS '路由命中已配置业务绑定的原因码。';
COMMENT ON COLUMN intent.turn_execution.tool_execution_id IS 'DIRECT 在 ToolGateway 中的执行标识。';
COMMENT ON COLUMN intent.turn_execution.run_id IS 'PLAN_AND_EXECUTE 在运行时中的 Run 标识。';
COMMENT ON COLUMN intent.turn_execution.blocked_reason IS '准入拒绝时保存的稳定原因码。';

COMMENT ON COLUMN runtime.workflow_skill_version.source_markdown IS '发布时保留的 SKILL.md 原文。';
CREATE TABLE IF NOT EXISTS intent.route_job (
    tenant_id text NOT NULL,
    turn_id uuid NOT NULL,
    ordinal integer NOT NULL CHECK (ordinal >= 0),
    conversation_id uuid NOT NULL,
    id uuid NOT NULL,
    mode text NOT NULL CHECK (mode IN ('DIRECT','CHAT','KNOWLEDGE')),
    owner_user_id text NOT NULL,
    agent_id text NOT NULL,
    roles jsonb NOT NULL CHECK (jsonb_typeof(roles)='array'),
    spec jsonb NOT NULL CHECK (jsonb_typeof(spec)='object'),
    status text NOT NULL CHECK (status IN ('QUEUED','WAITING_DEPENDENCY','WAITING_TOOL','SUCCEEDED','FAILED','BLOCKED')),
    reason text,
    tool_execution_id uuid,
    output jsonb,
    next_at timestamptz NOT NULL DEFAULT now(),
    lease_id uuid,
    lease_until timestamptz,
    deadline timestamptz NOT NULL DEFAULT now()+interval '30 minutes',
    PRIMARY KEY (tenant_id,turn_id,ordinal),
    UNIQUE (tenant_id,id),
    FOREIGN KEY (tenant_id,conversation_id,turn_id) REFERENCES intent.turn(tenant_id,conversation_id,id),
    FOREIGN KEY (tenant_id,turn_id,ordinal) REFERENCES intent.candidate(tenant_id,turn_id,ordinal),
    FOREIGN KEY (tenant_id,tool_execution_id) REFERENCES gateway.tool_execution(tenant_id,id),
    CHECK ((lease_id IS NULL) = (lease_until IS NULL))
);
CREATE INDEX IF NOT EXISTS route_job_due_idx ON intent.route_job(next_at)
    WHERE status IN ('QUEUED','WAITING_DEPENDENCY','WAITING_TOOL');
COMMENT ON TABLE intent.route_job IS '会话候选的依赖派发、聊天回复、知识检索及可恢复状态。';
COMMENT ON COLUMN intent.route_job.spec IS '绑定的工具版本、参数来源和前置候选序号，或当前候选问题。';
COMMENT ON COLUMN intent.route_job.output IS '本地保存的聊天答复。知识与工具结果通过网关按权限读取。';
COMMIT;

ALTER TABLE memory.task_episode ADD COLUMN summary_text text;
CREATE TABLE memory.knowledge_chunk_body (
    tenant_id text NOT NULL,
    document_id uuid NOT NULL,
    document_version integer NOT NULL,
    ordinal integer NOT NULL,
    body text NOT NULL CHECK (length(body) BETWEEN 1 AND 6000),
    PRIMARY KEY (tenant_id, document_id, document_version, ordinal),
    FOREIGN KEY (tenant_id, document_id, document_version, ordinal)
        REFERENCES memory.knowledge_chunk (tenant_id, document_id, document_version, ordinal) ON DELETE CASCADE
);
CREATE TABLE memory.index_job (
    tenant_id text NOT NULL,
    kind text NOT NULL CHECK (kind IN ('EPISODE', 'SEMANTIC')),
    source_key text NOT NULL,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_at timestamptz NOT NULL DEFAULT now(),
    lease_id uuid,
    lease_until timestamptz,
    indexed_at timestamptz,
    failed_at timestamptz,
    PRIMARY KEY (tenant_id, kind, source_key)
);
CREATE INDEX memory_index_due ON memory.index_job (next_at) WHERE indexed_at IS NULL AND failed_at IS NULL;
COMMENT ON COLUMN memory.task_episode.summary_text IS '自动沉淀的结构化案例摘要，供索引与出库使用。';
COMMENT ON TABLE memory.knowledge_chunk_body IS '知识分块正文，按租户、文档版本和分块序号存放。';
COMMENT ON COLUMN memory.knowledge_chunk_body.body IS '导入后保存在 PostgreSQL 的原文分块。';
COMMENT ON TABLE memory.index_job IS '记忆向量索引的持久化作业及重试状态。';
COMMENT ON COLUMN memory.index_job.source_key IS '案例 UUID 或文档 UUID、版本及分块序号组成的引用。';
COMMENT ON COLUMN memory.index_job.lease_until IS '索引 Worker 租约失效时间，到期可重新领取。';
COMMENT ON COLUMN memory.index_job.failed_at IS '索引重试耗尽的时间。';
