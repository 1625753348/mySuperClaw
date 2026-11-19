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
    protocol text NOT NULL CHECK (protocol IN ('HTTP', 'MCP', 'GRPC', 'DATABASE', 'RAG', 'SANDBOX')),
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
    kind text NOT NULL CHECK (kind IN ('ABAC', 'APPROVAL', 'QUOTA', 'RETRY')),
    rule jsonb NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, tool_id, kind, policy_version),
    FOREIGN KEY (tenant_id, tool_id) REFERENCES gateway.tool (tenant_id, id)
);
CREATE UNIQUE INDEX tool_policy_current_idx
    ON gateway.tool_policy (tenant_id, tool_id, kind) WHERE enabled;

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

COMMIT;
