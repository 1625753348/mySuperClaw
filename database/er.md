# 数据库实体关系图

先看总览，再看各域 ER。实体名称保留 Schema。图中省略重复展示的租户键和少用字段，完整复合键、约束及列清单见 [数据字典](dictionary.md)。源表是被引用方，边上的标签标注子表外键主要列。

## 跨领域总览

```mermaid
flowchart LR
    I[意图与任务] -->|任务参数与准入| R[运行编排]
    S[SOP Skill] -->|固定计划版本| R
    I -->|定义与 Skill 输入版本| S
    S -->|绑定工具版本| G[工具网关]
    R <-->|步骤与工具执行| G
    I -->|会话摘要| M[记忆]
    R -.->|脱敏案例来源| M
    R -.->|事务事件| E[Outbox / Inbox]
    G -.->|工具与审批事件| E
    S -.->|版本发布事件| E
```

总览箭头表达业务关联。虚线表示事件信封或应用追溯，通过标识关联。以下 ER 图只画实际数据库外键。跨域小实体表示外键目标，字段见其所属章节。

## 接入与意图

```mermaid
erDiagram
    direction LR
    INTENT_CONVERSATION["intent.conversation"] {
        uuid id PK
    }
    INTENT_TURN["intent.turn"] {
        uuid id PK
        uuid conversation_id FK
    }
    INTENT_MESSAGE["intent.message"] {
        uuid id PK
        uuid conversation_id FK
        uuid turn_id FK
        uuid reply_to_id FK
    }
    INTENT_DEFINITION["intent.definition"] {
        uuid id PK
    }
    INTENT_INTENT_RECORD["intent.intent_record"] {
        uuid id PK
        uuid conversation_id FK
        uuid origin_turn_id FK
        uuid definition_id FK
    }
    INTENT_TASK["intent.task"] {
        uuid id PK, FK
        uuid conversation_id FK
        uuid intent_id FK
        uuid parent_task_id FK
        integer input_version FK
    }
    INTENT_CANDIDATE["intent.candidate"] {
        uuid id PK
        uuid conversation_id FK
        uuid turn_id FK
        uuid target_task_id FK
        uuid target_clarification_id FK
        uuid resolves_question_id FK
    }
    INTENT_ROUTING_ASSESSMENT["intent.routing_assessment"] {
        uuid id PK
        uuid conversation_id FK
        uuid turn_id FK
        uuid candidate_id FK
        text comparison_key FK
        uuid prior_assessment_id FK
        text applied_strategy FK
    }
    INTENT_TASK_INPUT["intent.task_input"] {
        uuid task_id PK, FK
        integer version PK
        uuid conversation_id FK
        uuid source_candidate_id FK
        uuid workflow_skill_version_id FK
    }
    INTENT_CLARIFICATION["intent.clarification"] {
        uuid id PK
        uuid conversation_id FK
        uuid source_candidate_id FK
        uuid task_id FK
        integer input_version FK
    }
    INTENT_CONFIRMATION["intent.confirmation"] {
        uuid id PK
        uuid task_id FK
        integer input_version FK
    }
    INTENT_ROUTE_DECISION["intent.route_decision"] {
        uuid id PK
        uuid task_id FK
        integer input_version FK
    }
    INTENT_TURN_EXECUTION["intent.turn_execution"] {
        uuid turn_id PK, FK
        integer candidate_ordinal PK, FK
        uuid tool_execution_id FK
        uuid run_id FK
        text mode
    }
    INTENT_EXECUTED_RUN["runtime.run"]
    INTENT_TOOL_EXECUTION["gateway.tool_execution"]
    INTENT_TURN ||..o{ INTENT_TURN_EXECUTION : "conversation_id, turn_id"
    INTENT_CANDIDATE ||..o| INTENT_TURN_EXECUTION : "turn_id, candidate_ordinal"
    INTENT_EXECUTED_RUN o|..o{ INTENT_TURN_EXECUTION : "run_id"
    INTENT_TOOL_EXECUTION o|..o{ INTENT_TURN_EXECUTION : "tool_execution_id"
    RUNTIME_WORKFLOW_SKILL_VERSION["runtime.workflow_skill_version"]
    INTENT_CLARIFICATION o|..o{ INTENT_CANDIDATE : "conversation_id, resolves_question_id"
    INTENT_CLARIFICATION o|..o{ INTENT_CANDIDATE : "target_task_id, target_clarification_id"
    INTENT_TASK o|..o{ INTENT_CANDIDATE : "conversation_id, target_task_id"
    INTENT_TURN ||..o{ INTENT_CANDIDATE : "conversation_id, turn_id"
    INTENT_CANDIDATE ||..o{ INTENT_CLARIFICATION : "conversation_id, source_candidate_id"
    INTENT_TASK o|..o{ INTENT_CLARIFICATION : "conversation_id, task_id"
    INTENT_TASK_INPUT o|..o{ INTENT_CLARIFICATION : "task_id, input_version"
    INTENT_TASK_INPUT ||..o{ INTENT_CONFIRMATION : "task_id, input_version"
    INTENT_TURN ||..o{ INTENT_INTENT_RECORD : "conversation_id, origin_turn_id"
    INTENT_DEFINITION ||..o{ INTENT_INTENT_RECORD : "definition_id"
    INTENT_MESSAGE o|..o{ INTENT_MESSAGE : "conversation_id, reply_to_id"
    INTENT_TURN ||..o{ INTENT_MESSAGE : "conversation_id, turn_id"
    INTENT_TASK_INPUT ||..o{ INTENT_ROUTE_DECISION : "task_id, input_version"
    INTENT_ROUTING_ASSESSMENT o|..o{ INTENT_ROUTING_ASSESSMENT : "conversation_id, comparison_key, applied_strategy, prior_assessment_id"
    INTENT_TURN ||..o{ INTENT_ROUTING_ASSESSMENT : "conversation_id, turn_id"
    INTENT_CANDIDATE o|..o{ INTENT_ROUTING_ASSESSMENT : "turn_id, candidate_id"
    INTENT_TASK_INPUT ||..o| INTENT_TASK : "id, input_version"
    INTENT_INTENT_RECORD ||..o| INTENT_TASK : "conversation_id, intent_id"
    INTENT_TASK o|..o{ INTENT_TASK : "conversation_id, parent_task_id"
    INTENT_CANDIDATE ||..o{ INTENT_TASK_INPUT : "conversation_id, source_candidate_id"
    INTENT_TASK ||..o{ INTENT_TASK_INPUT : "conversation_id, task_id"
    RUNTIME_WORKFLOW_SKILL_VERSION o|..o{ INTENT_TASK_INPUT : "workflow_skill_version_id"
    INTENT_CONVERSATION ||..o{ INTENT_TURN : "conversation_id"
```

## 运行编排与恢复

```mermaid
erDiagram
    direction LR
    RUNTIME_RUN["runtime.run"] {
        uuid id PK, FK
        uuid task_id FK
        integer input_version FK
        uuid route_decision_id FK
        text route FK
        text admitted_verdict FK
        integer active_plan_version FK
        text gate_policy_version
        integer gate_pass_iteration FK
        text gate_pass_status FK
    }
    RUNTIME_PLAN["runtime.plan"] {
        uuid run_id PK, FK
        integer version PK
        text origin FK
        uuid proposal_id FK
        uuid workflow_skill_version_id FK
    }
    RUNTIME_PLAN_PROPOSAL["runtime.plan_proposal"] {
        uuid run_id PK, FK
        uuid id PK
        integer base_plan_version FK
    }
    RUNTIME_STEP["runtime.step"] {
        uuid id PK, FK
        uuid run_id FK
        integer plan_version FK
        uuid tool_execution_id FK
    }
    RUNTIME_STEP_DEPENDENCY["runtime.step_dependency"] {
        uuid run_id PK, FK
        integer plan_version PK, FK
        uuid step_id PK, FK
        uuid depends_on_step_id PK, FK
    }
    RUNTIME_STEP_ATTEMPT["runtime.step_attempt"] {
        uuid step_id PK, FK
        integer attempt_no PK
    }
    RUNTIME_CHECKPOINT["runtime.checkpoint"] {
        uuid run_id PK, FK
        bigint seq PK
        integer plan_version FK
        uuid after_step_id FK
    }
    RUNTIME_CONTEXT_ARTIFACT["runtime.context_artifact"] {
        uuid run_id PK, FK
        uuid id PK
        uuid step_id FK
    }
    RUNTIME_CHECKPOINT_ARTIFACT["runtime.checkpoint_artifact"] {
        uuid run_id PK, FK
        bigint checkpoint_seq PK, FK
        uuid artifact_id FK
        text binding_key PK
    }
    RUNTIME_ITERATION["runtime.iteration"] {
        uuid run_id PK, FK
        integer iteration_no PK
        integer plan_version FK
        text gate_policy_version FK
        text status
    }
    RUNTIME_GATE_CHECK["runtime.gate_check"] {
        uuid run_id PK, FK
        integer iteration_no PK, FK
        integer plan_version FK
        text check_key PK
    }
    RUNTIME_RUN_EVENT["runtime.run_event"] {
        uuid run_id PK, FK
        bigint seq PK
    }
    RUNTIME_RUN_SUBMISSION["runtime.run_submission"] {
        text user_id PK
        text agent_id PK
        text request_key PK
        uuid run_id FK
        text request_hash
    }
    RUNTIME_ARTIFACT_BODY["runtime.artifact_body"] {
        uuid run_id PK, FK
        uuid artifact_id PK, FK
        jsonb body
    }
    RUNTIME_RUN_COMMAND["runtime.run_command"] {
        uuid run_id PK, FK
        text actor_user_id PK
        text command_key PK
    }
    RUNTIME_WORKFLOW_SKILL_VERSION["runtime.workflow_skill_version"]
    INTENT_ROUTE_DECISION["intent.route_decision"]
    GATEWAY_TOOL_EXECUTION["gateway.tool_execution"]
    RUNTIME_STEP o|..o{ RUNTIME_CHECKPOINT : "run_id, plan_version, after_step_id"
    RUNTIME_PLAN ||..o{ RUNTIME_CHECKPOINT : "run_id, plan_version"
    RUNTIME_CONTEXT_ARTIFACT ||..o{ RUNTIME_CHECKPOINT_ARTIFACT : "run_id, artifact_id"
    RUNTIME_CHECKPOINT ||--o{ RUNTIME_CHECKPOINT_ARTIFACT : "run_id, checkpoint_seq"
    RUNTIME_RUN ||--o{ RUNTIME_CONTEXT_ARTIFACT : "run_id"
    RUNTIME_STEP o|..o{ RUNTIME_CONTEXT_ARTIFACT : "run_id, step_id"
    RUNTIME_ITERATION ||..o{ RUNTIME_GATE_CHECK : "run_id, iteration_no, plan_version"
    RUNTIME_RUN ||..o{ RUNTIME_ITERATION : "run_id, gate_policy_version"
    RUNTIME_PLAN ||..o{ RUNTIME_ITERATION : "run_id, plan_version"
    RUNTIME_PLAN_PROPOSAL o|..o| RUNTIME_PLAN : "run_id, proposal_id"
    RUNTIME_RUN ||--o{ RUNTIME_PLAN : "run_id"
    RUNTIME_WORKFLOW_SKILL_VERSION o|..o{ RUNTIME_PLAN : "workflow_skill_version_id, origin"
    RUNTIME_PLAN ||..o{ RUNTIME_PLAN_PROPOSAL : "run_id, base_plan_version"
    RUNTIME_PLAN ||..o| RUNTIME_RUN : "id, active_plan_version"
    RUNTIME_ITERATION o|..o| RUNTIME_RUN : "id, gate_pass_iteration, active_plan_version, gate_pass_status"
    INTENT_ROUTE_DECISION ||..o| RUNTIME_RUN : "task_id, input_version, route_decision_id, route, admitted_verdict"
    RUNTIME_RUN ||--o{ RUNTIME_RUN_COMMAND : "run_id"
    RUNTIME_RUN ||--o{ RUNTIME_RUN_EVENT : "run_id"
    RUNTIME_RUN ||..o| RUNTIME_RUN_SUBMISSION : "run_id"
    RUNTIME_CONTEXT_ARTIFACT ||--o| RUNTIME_ARTIFACT_BODY : "run_id, artifact_id"
    RUNTIME_PLAN ||..o{ RUNTIME_STEP : "run_id, plan_version"
    GATEWAY_TOOL_EXECUTION o|..o| RUNTIME_STEP : "tool_execution_id, run_id, id"
    RUNTIME_STEP ||--o{ RUNTIME_STEP_ATTEMPT : "step_id"
    RUNTIME_STEP ||--o{ RUNTIME_STEP_DEPENDENCY : "run_id, plan_version, depends_on_step_id"
    RUNTIME_STEP ||--o{ RUNTIME_STEP_DEPENDENCY : "run_id, plan_version, step_id"
```

## SOP 和固定工作流

```mermaid
erDiagram
    direction LR
    RUNTIME_WORKFLOW_SKILL["runtime.workflow_skill"] {
        uuid id PK
    }
    RUNTIME_WORKFLOW_SKILL_VERSION["runtime.workflow_skill_version"] {
        uuid id PK
        uuid skill_id FK
        uuid intent_definition_id FK
    }
    RUNTIME_WORKFLOW_SKILL_TRIGGER["runtime.workflow_skill_trigger"] {
        text trigger_kind PK
        text normalized_key PK
        uuid skill_id FK
    }
    RUNTIME_WORKFLOW_SKILL_TOOL["runtime.workflow_skill_tool"] {
        uuid skill_version_id PK, FK
        text node_key PK
        uuid tool_id FK
        uuid tool_version_id FK
    }
    GATEWAY_TOOL_VERSION["gateway.tool_version"]
    INTENT_DEFINITION["intent.definition"]
    RUNTIME_WORKFLOW_SKILL_VERSION ||--o{ RUNTIME_WORKFLOW_SKILL_TOOL : "skill_version_id"
    GATEWAY_TOOL_VERSION ||..o{ RUNTIME_WORKFLOW_SKILL_TOOL : "tool_id, tool_version_id"
    RUNTIME_WORKFLOW_SKILL ||..o{ RUNTIME_WORKFLOW_SKILL_TRIGGER : "skill_id"
    INTENT_DEFINITION ||..o{ RUNTIME_WORKFLOW_SKILL_VERSION : "intent_definition_id"
    RUNTIME_WORKFLOW_SKILL ||..o{ RUNTIME_WORKFLOW_SKILL_VERSION : "skill_id"
```

## 工具网关

```mermaid
erDiagram
    direction LR
    GATEWAY_TOOL_SERVER["gateway.tool_server"] {
        uuid id PK
        text protocol
    }
    GATEWAY_TOOL["gateway.tool"] {
        uuid id PK, FK
        uuid current_version_id FK
    }
    GATEWAY_TOOL_VERSION["gateway.tool_version"] {
        uuid id PK
        uuid tool_id FK
    }
    GATEWAY_TOOL_ENDPOINT["gateway.tool_endpoint"] {
        uuid id PK
        uuid version_id FK
        uuid server_id FK
        text protocol FK
    }
    GATEWAY_TOOL_PERMISSION["gateway.tool_permission"] {
        uuid tool_id PK, FK
        text subject_type PK
        text subject_id PK
        text action PK
    }
    GATEWAY_TOOL_POLICY["gateway.tool_policy"] {
        uuid id PK
        uuid tool_id FK
    }
    GATEWAY_TOOL_EXECUTION["gateway.tool_execution"] {
        uuid id PK
        uuid tool_id FK
        uuid version_id FK
        uuid endpoint_id FK
        uuid run_id FK
        uuid step_id FK
        text principal_id
        text operation_hash
    }
    GATEWAY_TOOL_APPROVAL["gateway.tool_approval"] {
        uuid id PK
        uuid execution_id FK
        uuid version_id FK
        text operation_hash FK
        text requester_principal_id FK
    }
    GATEWAY_TOOL_USAGE_WINDOW["gateway.tool_usage_window"] {
        uuid tool_id PK, FK
        text scope PK
        text subject_id PK
        text unit PK
        timestamptz window_start PK
        integer used
    }
    GATEWAY_TOOL_JOB["gateway.tool_job"] {
        uuid execution_id PK, FK
        text encrypted_request
        text encrypted_result
        timestamptz deadline_at
    }
    GATEWAY_CALLBACK_RECEIPT["gateway.callback_receipt"] {
        uuid server_id PK, FK
        text provider_event_id PK
        uuid execution_id FK
    }
    GATEWAY_AUDIT_ENTRY["gateway.audit_entry"] {
        uuid id PK
        uuid execution_id FK
        uuid tool_id FK
    }
    RUNTIME_STEP["runtime.step"]
    GATEWAY_TOOL_EXECUTION o|..o{ GATEWAY_AUDIT_ENTRY : "execution_id"
    GATEWAY_TOOL o|..o{ GATEWAY_AUDIT_ENTRY : "tool_id"
    GATEWAY_TOOL_EXECUTION ||..o{ GATEWAY_CALLBACK_RECEIPT : "execution_id"
    GATEWAY_TOOL_SERVER ||--o{ GATEWAY_CALLBACK_RECEIPT : "server_id"
    GATEWAY_TOOL_VERSION o|..o| GATEWAY_TOOL : "id, current_version_id"
    GATEWAY_TOOL_EXECUTION ||..o{ GATEWAY_TOOL_APPROVAL : "execution_id, version_id, operation_hash, requester_principal_id"
    GATEWAY_TOOL_EXECUTION ||--o| GATEWAY_TOOL_JOB : "execution_id"
    GATEWAY_TOOL_SERVER ||..o{ GATEWAY_TOOL_ENDPOINT : "server_id, protocol"
    GATEWAY_TOOL_VERSION ||..o{ GATEWAY_TOOL_ENDPOINT : "version_id"
    RUNTIME_STEP o|..o{ GATEWAY_TOOL_EXECUTION : "run_id, step_id"
    GATEWAY_TOOL_VERSION ||..o{ GATEWAY_TOOL_EXECUTION : "tool_id, version_id"
    GATEWAY_TOOL_ENDPOINT o|..o{ GATEWAY_TOOL_EXECUTION : "version_id, endpoint_id"
    GATEWAY_TOOL ||--o{ GATEWAY_TOOL_PERMISSION : "tool_id"
    GATEWAY_TOOL ||..o{ GATEWAY_TOOL_POLICY : "tool_id"
    GATEWAY_TOOL ||--o{ GATEWAY_TOOL_USAGE_WINDOW : "tool_id"
    GATEWAY_TOOL ||..o{ GATEWAY_TOOL_VERSION : "tool_id"
```

## 事件存储

```mermaid
erDiagram
    direction LR
    EVENTING_OUTBOX["eventing.outbox"] {
        uuid event_id PK
    }
    EVENTING_INBOX["eventing.inbox"] {
        text consumer_name PK
        uuid event_id PK
    }
    EVENTING_DELIVERY_FAILURE["eventing.delivery_failure"] {
        text consumer_name PK
        uuid event_id PK
    }
```

三张表独立保留。Outbox 和 Inbox 使用租户、消费方及事件标识完成关联与去重。

## 对话与长期记忆

```mermaid
erDiagram
    direction LR
    MEMORY_CONVERSATION_SUMMARY["memory.conversation_summary"] {
        uuid conversation_id PK, FK
        bigint version PK
    }
    MEMORY_PROFILE_ITEM["memory.profile_item"] {
        text user_id PK
        text item_key PK
    }
    MEMORY_TASK_EPISODE["memory.task_episode"] {
        uuid id PK
    }
    MEMORY_KNOWLEDGE_DOCUMENT["memory.knowledge_document"] {
        uuid id PK
    }
    MEMORY_KNOWLEDGE_VERSION["memory.knowledge_version"] {
        uuid document_id PK, FK
        integer version PK
    }
    MEMORY_KNOWLEDGE_CHUNK["memory.knowledge_chunk"] {
        uuid document_id PK, FK
        integer document_version PK, FK
        integer ordinal PK
    }
    INTENT_CONVERSATION["intent.conversation"]
    INTENT_CONVERSATION ||--o{ MEMORY_CONVERSATION_SUMMARY : "conversation_id"
    MEMORY_KNOWLEDGE_VERSION ||--o{ MEMORY_KNOWLEDGE_CHUNK : "document_id, document_version"
    MEMORY_KNOWLEDGE_DOCUMENT ||--o{ MEMORY_KNOWLEDGE_VERSION : "document_id"
```

## 监控信号、投递与工具执行

```mermaid
erDiagram
    MONITOR["runtime.monitor"]
    TICK["runtime.monitor_tick"]
    DELIVERY["runtime.monitor_delivery"]
    EXECUTION["gateway.tool_execution"]
    MONITOR ||--o{ TICK : "观测"
    TICK ||--o{ DELIVERY : "通知或处置"
    EXECUTION o|--o{ TICK : "指标读取"
    EXECUTION o|--o{ DELIVERY : "受理后关联"
```

`runtime.monitor_tick` 的执行标识对应指标读取。`runtime.monitor_delivery` 的执行标识对应后续通知或处置，可能在等待审批。关联查询均带租户标识。
