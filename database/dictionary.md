# PostgreSQL 数据字典

本文按建表 SQL 实际加载后的 PostgreSQL 目录生成，包含 48 张表、496 个字段、305 条键与检查约束和 32 个独立索引。主键与唯一约束所创建的索引随约束列出。

字段说明中的「引用」表示位置或业务关联。物理外键以各表的外键列表为准。生成列由数据库赋值，应用写入时省略。时间戳使用带时区类型。

| 领域 | Schema | 表数 |
| --- | --- | ---: |
| 接入与意图 | `intent` | 12 |
| 运行编排与恢复 | `runtime` | 13 |
| SOP 和固定工作流 | `runtime` | 4 |
| 工具网关 | `gateway` | 10 |
| 事件存储 | `eventing` | 3 |
| 对话与长期记忆 | `memory` | 6 |

## 接入与意图

### intent.conversation

会话所有者与消息水位

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK | 可信租户标识，用于隔离业务记录 |
| `id` | `uuid` | 否 | — | PK | 本表实体标识，通常与租户组成主键 |
| `owner_user_id` | `text` | 否 | — | — | 会话或记忆的所属用户 |
| `last_message_seq` | `bigint` | 否 | `0` | — | 会话当前已分配的最大消息序号 |
| `history_revision` | `bigint` | 否 | `0` | — | 原文发生变化时递增的会话代次 |
| `created_at` | `timestamp with time zone` | 否 | `now()` | — | 记录创建时间 |

**约束**

- 主键 `conversation_pkey` `PRIMARY KEY (tenant_id, id)`
- 检查 `conversation_history_revision_check` `CHECK (history_revision >= 0)`
- 检查 `conversation_last_message_seq_check` `CHECK (last_message_seq >= 0)`

### intent.turn

每轮请求、幂等回执和处理进度

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK / UQ | 可信租户标识，用于隔离业务记录 |
| `id` | `uuid` | 否 | — | PK / UQ | 本表实体标识，通常与租户组成主键 |
| `conversation_id` | `uuid` | 否 | — | FK / UQ | 所属会话标识 |
| `actor_user_id` | `text` | 否 | — | UQ | 触发本轮或命令的用户标识 |
| `client_key` | `text` | 否 | — | UQ | 客户端提交幂等键 |
| `request_hash` | `text` | 否 | — | — | 规范化请求指纹，用于排查重复键冲突 |
| `status` | `text` | 否 | — | — | 本表对象在其生命周期中的当前状态 |
| `next_at` | `timestamp with time zone` | 否 | `now()` | — | 下次可领取、重试或复核的时间 |
| `lease_owner` | `text` | 是 | — | — | 当前任务领取者 |
| `lease_until` | `timestamp with time zone` | 是 | — | — | 当前领取有效期 |
| `lease_epoch` | `bigint` | 否 | `0` | — | 拒绝旧领取者提交的递增租约代次 |
| `attempts` | `integer` | 否 | `0` | — | 已尝试领取或投递次数 |
| `receipt` | `jsonb` | 是 | — | — | 供重复请求返回的已持久化回执 |
| `created_at` | `timestamp with time zone` | 否 | `now()` | — | 记录创建时间 |

**约束**

- 主键 `turn_pkey` `PRIMARY KEY (tenant_id, id)`
- 外键 `turn_tenant_id_conversation_id_fkey` `FOREIGN KEY (tenant_id, conversation_id) REFERENCES intent.conversation(tenant_id, id)`
- 唯一 `turn_tenant_id_conversation_id_actor_user_id_client_key_key` `UNIQUE (tenant_id, conversation_id, actor_user_id, client_key)`
- 唯一 `turn_tenant_id_conversation_id_id_key` `UNIQUE (tenant_id, conversation_id, id)`
- 检查 `turn_check` `CHECK ((lease_owner IS NULL) = (lease_until IS NULL))`
- 检查 `turn_check1` `CHECK (lease_epoch >= 0 AND attempts >= 0)`
- 检查 `turn_status_check` `CHECK (status = ANY (ARRAY['QUEUED'::text, 'CLASSIFYING'::text, 'PROCESSING'::text, 'COMPLETED'::text, 'FAILED'::text]))`

**独立索引**

- `CREATE INDEX turn_due_idx ON intent.turn USING btree (next_at, lease_until) WHERE (status = ANY (ARRAY['QUEUED'::text, 'CLASSIFYING'::text, 'PROCESSING'::text]))`

### intent.message

按会话排序的原文引用与脱敏位置

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK / UQ | 可信租户标识，用于隔离业务记录 |
| `id` | `uuid` | 否 | — | PK / UQ | 本表实体标识，通常与租户组成主键 |
| `conversation_id` | `uuid` | 否 | — | FK / UQ | 所属会话标识 |
| `turn_id` | `uuid` | 否 | — | FK / UQ | 所属用户输入轮次 |
| `seq` | `bigint` | 否 | — | UQ | 所属会话或 Run 中的顺序号 |
| `role` | `text` | 否 | — | — | 会话消息角色 |
| `position` | `integer` | 否 | — | UQ | 同一轮中的消息位置 |
| `body_ref` | `text` | 是 | — | — | 原始消息正文的受控引用 |
| `redacted_at` | `timestamp with time zone` | 是 | — | — | 消息正文被撤回或脱敏的时间 |
| `reply_to_id` | `uuid` | 是 | — | FK | 同一会话中被回复的消息 |
| `created_at` | `timestamp with time zone` | 否 | `now()` | — | 记录创建时间 |

**约束**

- 主键 `message_pkey` `PRIMARY KEY (tenant_id, id)`
- 外键 `message_tenant_id_conversation_id_reply_to_id_fkey` `FOREIGN KEY (tenant_id, conversation_id, reply_to_id) REFERENCES intent.message(tenant_id, conversation_id, id)`
- 外键 `message_tenant_id_conversation_id_turn_id_fkey` `FOREIGN KEY (tenant_id, conversation_id, turn_id) REFERENCES intent.turn(tenant_id, conversation_id, id)`
- 唯一 `message_tenant_id_conversation_id_id_key` `UNIQUE (tenant_id, conversation_id, id)`
- 唯一 `message_tenant_id_conversation_id_seq_key` `UNIQUE (tenant_id, conversation_id, seq)`
- 唯一 `message_tenant_id_turn_id_position_key` `UNIQUE (tenant_id, turn_id, "position")`
- 检查 `message_check` `CHECK (reply_to_id IS DISTINCT FROM id)`
- 检查 `message_check1` `CHECK ((redacted_at IS NULL) = (body_ref IS NOT NULL))`
- 检查 `message_position_check` `CHECK ("position" >= 0)`
- 检查 `message_role_check` `CHECK (role = ANY (ARRAY['USER'::text, 'ASSISTANT'::text]))`
- 检查 `message_seq_check` `CHECK (seq > 0)`

### intent.definition

意图参数契约与发布版本

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / UQ | 可信租户标识，用于隔离业务记录 |
| `id` | `uuid` | 否 | — | PK | 本表实体标识，通常与租户组成主键 |
| `domain` | `text` | 否 | — | UQ | 业务领域编码 |
| `action` | `text` | 否 | — | UQ | 动作编码或授权动作 |
| `version` | `integer` | 否 | — | UQ | 表内实体版本或状态修订号 |
| `status` | `text` | 否 | — | — | 本表对象在其生命周期中的当前状态 |
| `is_current` | `boolean` | 否 | `false` | — | 是否是新请求的默认版本 |
| `parameter_schema` | `jsonb` | 否 | — | — | 已发布的意图参数 JSON Schema |
| `route_config` | `jsonb` | 否 | `'{}'::jsonb` | — | 意图定义的路由配置 |
| `created_at` | `timestamp with time zone` | 否 | `now()` | — | 记录创建时间 |

**约束**

- 主键 `definition_pkey` `PRIMARY KEY (tenant_id, id)`
- 唯一 `definition_tenant_id_domain_action_version_key` `UNIQUE (tenant_id, domain, action, version)`
- 检查 `definition_check` `CHECK (NOT is_current OR status = 'PUBLISHED'::text)`
- 检查 `definition_parameter_schema_check` `CHECK (jsonb_typeof(parameter_schema) = 'object'::text)`
- 检查 `definition_status_check` `CHECK (status = ANY (ARRAY['DRAFT'::text, 'PUBLISHED'::text, 'RETIRED'::text]))`
- 检查 `definition_version_check` `CHECK (version > 0)`

**独立索引**

- `CREATE UNIQUE INDEX intent_current_idx ON intent.definition USING btree (tenant_id, domain, action) WHERE is_current`

### intent.intent_record

已绑定的业务意图

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK / UQ | 可信租户标识，用于隔离业务记录 |
| `id` | `uuid` | 否 | — | PK / UQ | 本表实体标识，通常与租户组成主键 |
| `conversation_id` | `uuid` | 否 | — | FK / UQ | 所属会话标识 |
| `origin_turn_id` | `uuid` | 否 | — | FK | 原始业务意图创建轮次 |
| `definition_id` | `uuid` | 否 | — | FK | 已固定的意图定义标识 |
| `created_at` | `timestamp with time zone` | 否 | `now()` | — | 记录创建时间 |

**约束**

- 主键 `intent_record_pkey` `PRIMARY KEY (tenant_id, id)`
- 外键 `intent_record_tenant_id_conversation_id_origin_turn_id_fkey` `FOREIGN KEY (tenant_id, conversation_id, origin_turn_id) REFERENCES intent.turn(tenant_id, conversation_id, id)`
- 外键 `intent_record_tenant_id_definition_id_fkey` `FOREIGN KEY (tenant_id, definition_id) REFERENCES intent.definition(tenant_id, id)`
- 唯一 `intent_record_tenant_id_conversation_id_id_key` `UNIQUE (tenant_id, conversation_id, id)`

**独立索引**

- `CREATE INDEX intent_recent_idx ON intent.intent_record USING btree (tenant_id, conversation_id, created_at DESC)`

### intent.task

用户业务任务及当前参数版本

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK / UQ | 可信租户标识，用于隔离业务记录 |
| `id` | `uuid` | 否 | — | PK / FK / UQ | 本表实体标识，通常与租户组成主键 |
| `conversation_id` | `uuid` | 否 | — | FK / UQ | 所属会话标识 |
| `intent_id` | `uuid` | 否 | — | FK / UQ | 对应的业务意图标识 |
| `parent_task_id` | `uuid` | 是 | — | FK | 后继任务关联的原任务 |
| `status` | `text` | 否 | — | — | 本表对象在其生命周期中的当前状态 |
| `input_version` | `integer` | 否 | — | FK | Task 参数版本 |
| `revision` | `bigint` | 否 | `0` | — | 实体修订号 |
| `created_at` | `timestamp with time zone` | 否 | `now()` | — | 记录创建时间 |
| `updated_at` | `timestamp with time zone` | 否 | `now()` | — | 最近一次业务更新的时间 |

**约束**

- 主键 `task_pkey` `PRIMARY KEY (tenant_id, id)`
- 外键 `task_current_input_fk` `FOREIGN KEY (tenant_id, id, input_version) REFERENCES intent.task_input(tenant_id, task_id, version) DEFERRABLE INITIALLY DEFERRED`
- 外键 `task_tenant_id_conversation_id_intent_id_fkey` `FOREIGN KEY (tenant_id, conversation_id, intent_id) REFERENCES intent.intent_record(tenant_id, conversation_id, id)`
- 外键 `task_tenant_id_conversation_id_parent_task_id_fkey` `FOREIGN KEY (tenant_id, conversation_id, parent_task_id) REFERENCES intent.task(tenant_id, conversation_id, id)`
- 唯一 `task_tenant_id_conversation_id_id_key` `UNIQUE (tenant_id, conversation_id, id)`
- 唯一 `task_tenant_id_intent_id_key` `UNIQUE (tenant_id, intent_id)`
- 检查 `task_check` `CHECK (parent_task_id IS DISTINCT FROM id)`
- 检查 `task_input_version_check` `CHECK (input_version > 0)`
- 检查 `task_revision_check` `CHECK (revision >= 0)`
- 检查 `task_status_check` `CHECK (status = ANY (ARRAY['COLLECTING_INPUT'::text, 'WAITING_CONFIRMATION'::text, 'READY'::text, 'EXECUTING'::text, 'COMPLETED'::text, 'FAILED'::text, 'CANCELLED'::text]))`

**独立索引**

- `CREATE INDEX task_context_idx ON intent.task USING btree (tenant_id, conversation_id, status, updated_at DESC)`

### intent.candidate

每轮候选、历史关联和处理结果

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK / UQ | 可信租户标识，用于隔离业务记录 |
| `id` | `uuid` | 否 | — | PK / UQ | 本表实体标识，通常与租户组成主键 |
| `conversation_id` | `uuid` | 否 | — | FK / UQ | 所属会话标识 |
| `turn_id` | `uuid` | 否 | — | FK / UQ | 所属用户输入轮次 |
| `ordinal` | `integer` | 否 | — | UQ | 同一轮的候选或同一版本的分块序号 |
| `draft` | `jsonb` | 否 | — | — | 结构化意图候选草案 |
| `relation` | `text` | 是 | — | — | 候选和历史任务之间的关系 |
| `target_task_id` | `uuid` | 是 | — | FK | 当前候选关联的目标 Task |
| `target_clarification_id` | `uuid` | 是 | — | FK | 关联的槽位澄清问题 |
| `resolves_question_id` | `uuid` | 是 | — | FK | 本候选试图解决的消歧问题 |
| `evidence` | `jsonb` | 否 | `'[]'::jsonb` | — | 关系判断采用的证据 |
| `confidence` | `numeric(5,4)` | 是 | — | — | 上下文关系判断的置信度 |
| `status` | `text` | 否 | — | — | 本表对象在其生命周期中的当前状态 |
| `outcome` | `jsonb` | 是 | — | — | 已提交的候选级处理结果 |

**约束**

- 主键 `candidate_pkey` `PRIMARY KEY (tenant_id, id)`
- 外键 `candidate_resolves_question_fk` `FOREIGN KEY (tenant_id, conversation_id, resolves_question_id) REFERENCES intent.clarification(tenant_id, conversation_id, id)`
- 外键 `candidate_target_question_fk` `FOREIGN KEY (tenant_id, target_task_id, target_clarification_id) REFERENCES intent.clarification(tenant_id, task_id, id)`
- 外键 `candidate_tenant_id_conversation_id_target_task_id_fkey` `FOREIGN KEY (tenant_id, conversation_id, target_task_id) REFERENCES intent.task(tenant_id, conversation_id, id)`
- 外键 `candidate_tenant_id_conversation_id_turn_id_fkey` `FOREIGN KEY (tenant_id, conversation_id, turn_id) REFERENCES intent.turn(tenant_id, conversation_id, id)`
- 唯一 `candidate_tenant_id_conversation_id_id_key` `UNIQUE (tenant_id, conversation_id, id)`
- 唯一 `candidate_tenant_id_turn_id_id_key` `UNIQUE (tenant_id, turn_id, id)`
- 唯一 `candidate_tenant_id_turn_id_ordinal_key` `UNIQUE (tenant_id, turn_id, ordinal)`
- 检查 `candidate_check` `CHECK ((relation <> ALL (ARRAY['CONTINUATION'::text, 'MODIFICATION'::text, 'CANCEL'::text, 'CONFIRM'::text])) OR target_task_id IS NOT NULL)`
- 检查 `candidate_check1` `CHECK (relation IS DISTINCT FROM 'NEW_TASK'::text OR target_task_id IS NULL)`
- 检查 `candidate_check2` `CHECK (target_clarification_id IS NULL OR target_task_id IS NOT NULL)`
- 检查 `candidate_confidence_check` `CHECK (confidence >= 0::numeric AND confidence <= 1::numeric)`
- 检查 `candidate_evidence_check` `CHECK (jsonb_typeof(evidence) = 'array'::text)`
- 检查 `candidate_ordinal_check` `CHECK (ordinal >= 0)`
- 检查 `candidate_relation_check` `CHECK (relation = ANY (ARRAY['NEW_TASK'::text, 'CONTINUATION'::text, 'MODIFICATION'::text, 'CANCEL'::text, 'CONFIRM'::text, 'AMBIGUOUS'::text]))`
- 检查 `candidate_status_check` `CHECK (status = ANY (ARRAY['DRAFT'::text, 'APPLIED'::text, 'NEEDS_CLARIFICATION'::text, 'FAILED'::text]))`

### intent.routing_assessment

复杂度分档、实际识别策略和滞回复用记录

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK / UQ | 可信租户标识，用于隔离业务记录 |
| `id` | `uuid` | 否 | — | PK / UQ | 本表实体标识，通常与租户组成主键 |
| `conversation_id` | `uuid` | 否 | — | FK / UQ | 所属会话标识 |
| `turn_id` | `uuid` | 否 | — | FK | 所属用户输入轮次 |
| `candidate_id` | `uuid` | 是 | — | FK | 本次路由评估所对应的候选 |
| `comparison_key` | `text` | 是 | — | FK / UQ | 约束滞回可比较范围的摘要键 |
| `prior_assessment_id` | `uuid` | 是 | — | FK | 滞回复用的前一次识别评估 |
| `complexity_score` | `numeric(7,6)` | 否 | — | — | 归一化识别复杂度 |
| `match_confidence` | `numeric(7,6)` | 是 | — | — | 候选匹配置信度，与复杂度分开计算 |
| `score_band` | `text` | 是 | 生成 `
CASE
    WHEN (complexity_score < 0.30) THEN 'RULE'::text
    WHEN (complexity_score < 0.50) THEN 'RETRIEVAL_SMALL_MODEL'::text
    WHEN (complexity_score < 0.70) THEN 'HYSTERESIS'::text
    ELSE 'LARGE_MODEL'::text
END` | — | 由复杂度计算出的识别分档 |
| `applied_strategy` | `text` | 否 | — | FK / UQ | 本次实际采用的识别策略 |
| `score_config_version` | `text` | 否 | — | — | 评分及校准配置版本 |
| `critical_risk_override` | `boolean` | 否 | `false` | — | 是否由确定性极高风险策略接管 |
| `reason_codes` | `jsonb` | 否 | `'[]'::jsonb` | — | 可审计的识别判断原因码 |
| `created_at` | `timestamp with time zone` | 否 | `now()` | — | 记录创建时间 |

**约束**

- 主键 `routing_assessment_pkey` `PRIMARY KEY (tenant_id, id)`
- 外键 `routing_assessment_tenant_id_conversation_id_comparison_ke_fkey` `FOREIGN KEY (tenant_id, conversation_id, comparison_key, applied_strategy, prior_assessment_id) REFERENCES intent.routing_assessment(tenant_id, conversation_id, comparison_key, applied_strategy, id)`
- 外键 `routing_assessment_tenant_id_conversation_id_turn_id_fkey` `FOREIGN KEY (tenant_id, conversation_id, turn_id) REFERENCES intent.turn(tenant_id, conversation_id, id)`
- 外键 `routing_assessment_tenant_id_turn_id_candidate_id_fkey` `FOREIGN KEY (tenant_id, turn_id, candidate_id) REFERENCES intent.candidate(tenant_id, turn_id, id)`
- 唯一 `routing_assessment_tenant_id_conversation_id_comparison_key_key` `UNIQUE (tenant_id, conversation_id, comparison_key, applied_strategy, id)`
- 检查 `routing_assessment_applied_strategy_check` `CHECK (applied_strategy = ANY (ARRAY['RULE'::text, 'RETRIEVAL_SMALL_MODEL'::text, 'HYBRID'::text, 'LARGE_MODEL'::text]))`
- 检查 `routing_assessment_check` `CHECK (prior_assessment_id IS DISTINCT FROM id)`
- 检查 `routing_assessment_check1` `CHECK (prior_assessment_id IS NULL OR comparison_key IS NOT NULL AND score_band = 'HYSTERESIS'::text)`
- 检查 `routing_assessment_check2` `CHECK (NOT critical_risk_override OR applied_strategy = 'RULE'::text AND prior_assessment_id IS NULL)`
- 检查 `routing_assessment_complexity_score_check` `CHECK (complexity_score >= 0::numeric AND complexity_score <= 1::numeric)`
- 检查 `routing_assessment_match_confidence_check` `CHECK (match_confidence >= 0::numeric AND match_confidence <= 1::numeric)`
- 检查 `routing_assessment_reason_codes_check` `CHECK (jsonb_typeof(reason_codes) = 'array'::text)`

**独立索引**

- `CREATE INDEX routing_assessment_comparable_idx ON intent.routing_assessment USING btree (tenant_id, conversation_id, comparison_key, created_at DESC) WHERE (comparison_key IS NOT NULL)`

### intent.task_input

不可变参数版本及已校验意图

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK | 可信租户标识，用于隔离业务记录 |
| `task_id` | `uuid` | 否 | — | PK / FK | 所属业务任务标识 |
| `version` | `integer` | 否 | — | PK | 表内实体版本或状态修订号 |
| `conversation_id` | `uuid` | 否 | — | FK | 所属会话标识 |
| `source_candidate_id` | `uuid` | 否 | — | FK | 导致本次持久化变更的候选 |
| `workflow_skill_version_id` | `uuid` | 是 | — | FK | Task 或 Plan 固定使用的 SOP Skill 版本 |
| `slots` | `jsonb` | 否 | — | — | 该输入版本的结构化槽位 |
| `slot_provenance` | `jsonb` | 否 | `'{}'::jsonb` | — | 各槽位的来源与证据位置 |
| `slot_state` | `jsonb` | 否 | `'{}'::jsonb` | — | 槽位待填、冲突或校验状态 |
| `validated_ir` | `jsonb` | 是 | — | — | 按定义校验后的结构化意图 |
| `created_at` | `timestamp with time zone` | 否 | `now()` | — | 记录创建时间 |

**约束**

- 主键 `task_input_pkey` `PRIMARY KEY (tenant_id, task_id, version)`
- 外键 `task_input_tenant_id_conversation_id_source_candidate_id_fkey` `FOREIGN KEY (tenant_id, conversation_id, source_candidate_id) REFERENCES intent.candidate(tenant_id, conversation_id, id)`
- 外键 `task_input_tenant_id_conversation_id_task_id_fkey` `FOREIGN KEY (tenant_id, conversation_id, task_id) REFERENCES intent.task(tenant_id, conversation_id, id)`
- 外键 `task_input_workflow_skill_fk` `FOREIGN KEY (tenant_id, workflow_skill_version_id) REFERENCES runtime.workflow_skill_version(tenant_id, id)`
- 检查 `task_input_slots_check` `CHECK (jsonb_typeof(slots) = 'object'::text)`
- 检查 `task_input_version_check1` `CHECK (version > 0)`

### intent.clarification

补槽或目标消歧问题

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK / UQ | 可信租户标识，用于隔离业务记录 |
| `id` | `uuid` | 否 | — | PK / UQ | 本表实体标识，通常与租户组成主键 |
| `conversation_id` | `uuid` | 否 | — | FK / UQ | 所属会话标识 |
| `source_candidate_id` | `uuid` | 否 | — | FK | 导致本次持久化变更的候选 |
| `task_id` | `uuid` | 是 | — | FK / UQ | 所属业务任务标识 |
| `input_version` | `integer` | 是 | — | FK | Task 参数版本 |
| `kind` | `text` | 否 | — | — | 本表所定义的对象或操作子类型 |
| `status` | `text` | 否 | — | — | 本表对象在其生命周期中的当前状态 |
| `question` | `jsonb` | 否 | — | — | 澄清问题与待回答字段 |
| `expires_at` | `timestamp with time zone` | 是 | — | — | 可使用或裁决的截止时间 |
| `created_at` | `timestamp with time zone` | 否 | `now()` | — | 记录创建时间 |

**约束**

- 主键 `clarification_pkey` `PRIMARY KEY (tenant_id, id)`
- 外键 `clarification_tenant_id_conversation_id_source_candidate_i_fkey` `FOREIGN KEY (tenant_id, conversation_id, source_candidate_id) REFERENCES intent.candidate(tenant_id, conversation_id, id)`
- 外键 `clarification_tenant_id_conversation_id_task_id_fkey` `FOREIGN KEY (tenant_id, conversation_id, task_id) REFERENCES intent.task(tenant_id, conversation_id, id)`
- 外键 `clarification_tenant_id_task_id_input_version_fkey` `FOREIGN KEY (tenant_id, task_id, input_version) REFERENCES intent.task_input(tenant_id, task_id, version)`
- 唯一 `clarification_tenant_id_conversation_id_id_key` `UNIQUE (tenant_id, conversation_id, id)`
- 唯一 `clarification_tenant_id_task_id_id_key` `UNIQUE (tenant_id, task_id, id)`
- 检查 `clarification_check` `CHECK (kind = 'SLOTS'::text AND task_id IS NOT NULL AND input_version IS NOT NULL OR kind = 'TARGET'::text AND task_id IS NULL AND input_version IS NULL)`
- 检查 `clarification_kind_check` `CHECK (kind = ANY (ARRAY['SLOTS'::text, 'TARGET'::text]))`
- 检查 `clarification_status_check` `CHECK (status = ANY (ARRAY['OPEN'::text, 'ANSWERED'::text, 'EXPIRED'::text, 'SUPERSEDED'::text, 'CANCELLED'::text]))`

**独立索引**

- `CREATE INDEX clarification_context_idx ON intent.clarification USING btree (tenant_id, conversation_id, created_at) WHERE (status = 'OPEN'::text)`
- `CREATE UNIQUE INDEX clarification_open_slot_idx ON intent.clarification USING btree (tenant_id, task_id) WHERE ((status = 'OPEN'::text) AND (kind = 'SLOTS'::text))`

### intent.confirmation

意图级用户确认

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK | 可信租户标识，用于隔离业务记录 |
| `id` | `uuid` | 否 | — | PK | 本表实体标识，通常与租户组成主键 |
| `task_id` | `uuid` | 否 | — | FK | 所属业务任务标识 |
| `input_version` | `integer` | 否 | — | FK | Task 参数版本 |
| `requester_user_id` | `text` | 否 | — | — | 发起意图级确认的用户标识 |
| `operation_hash` | `text` | 否 | — | — | 调用版本、身份及参数的绑定摘要 |
| `status` | `text` | 否 | — | — | 本表对象在其生命周期中的当前状态 |
| `expires_at` | `timestamp with time zone` | 否 | — | — | 可使用或裁决的截止时间 |
| `decided_at` | `timestamp with time zone` | 是 | — | — | 裁决时间 |
| `created_at` | `timestamp with time zone` | 否 | `now()` | — | 记录创建时间 |

**约束**

- 主键 `confirmation_pkey` `PRIMARY KEY (tenant_id, id)`
- 外键 `confirmation_tenant_id_task_id_input_version_fkey` `FOREIGN KEY (tenant_id, task_id, input_version) REFERENCES intent.task_input(tenant_id, task_id, version)`
- 检查 `confirmation_status_check` `CHECK (status = ANY (ARRAY['PENDING'::text, 'CONFIRMED'::text, 'EXPIRED'::text, 'SUPERSEDED'::text, 'CANCELLED'::text]))`

**独立索引**

- `CREATE INDEX confirmation_expiry_idx ON intent.confirmation USING btree (expires_at) WHERE (status = 'PENDING'::text)`
- `CREATE UNIQUE INDEX confirmation_pending_idx ON intent.confirmation USING btree (tenant_id, task_id) WHERE (status = 'PENDING'::text)`

### intent.route_decision

策略准入和实际执行模式

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK / UQ | 可信租户标识，用于隔离业务记录 |
| `id` | `uuid` | 否 | — | PK / UQ | 本表实体标识，通常与租户组成主键 |
| `task_id` | `uuid` | 否 | — | FK / UQ | 所属业务任务标识 |
| `input_version` | `integer` | 否 | — | FK / UQ | Task 参数版本 |
| `policy_version` | `text` | 否 | — | — | 准入或审批策略版本 |
| `verdict` | `text` | 否 | — | UQ | 业务准入或 Gate 检查的裁决 |
| `route` | `text` | 是 | — | UQ | 已选的执行路由 |
| `reasons` | `jsonb` | 否 | `'[]'::jsonb` | — | 策略判断依据 |
| `created_at` | `timestamp with time zone` | 否 | `now()` | — | 记录创建时间 |

**约束**

- 主键 `route_decision_pkey` `PRIMARY KEY (tenant_id, id)`
- 外键 `route_decision_tenant_id_task_id_input_version_fkey` `FOREIGN KEY (tenant_id, task_id, input_version) REFERENCES intent.task_input(tenant_id, task_id, version)`
- 唯一 `route_decision_tenant_id_task_id_input_version_id_route_ver_key` `UNIQUE (tenant_id, task_id, input_version, id, route, verdict)`
- 检查 `route_decision_check` `CHECK (verdict <> 'ALLOW'::text OR route IS NOT NULL)`
- 检查 `route_decision_route_check` `CHECK (route = ANY (ARRAY['DIRECT'::text, 'WORKFLOW'::text, 'AGENT'::text]))`
- 检查 `route_decision_verdict_check` `CHECK (verdict = ANY (ARRAY['ALLOW'::text, 'DENY'::text, 'REQUIRE_CONFIRMATION'::text]))`

## 运行编排与恢复

### runtime.run

已接纳的执行与运行预算

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK / UQ | 可信租户标识，用于隔离业务记录 |
| `id` | `uuid` | 否 | — | PK / FK / UQ | 本表实体标识，通常与租户组成主键 |
| `task_id` | `uuid` | 否 | — | FK / UQ | 所属业务任务标识 |
| `input_version` | `integer` | 否 | — | FK / UQ | Task 参数版本 |
| `route_decision_id` | `uuid` | 否 | — | FK | Run 所绑定的业务准入决定 |
| `route` | `text` | 否 | — | FK | 已选的执行路由 |
| `admitted_verdict` | `text` | 否 | `'ALLOW'::text` | FK | 入场许可断言 |
| `user_id` | `text` | 否 | — | — | 当前记录的业务用户标识 |
| `agent_id` | `text` | 否 | — | — | 调用链上的 Agent 标识 |
| `trace_id` | `text` | 否 | — | — | 观测系统中的请求链路标识 |
| `graph_version` | `text` | 否 | — | — | Run 使用的图定义版本 |
| `active_plan_version` | `integer` | 否 | — | FK | 当前有效计划版本 |
| `status` | `text` | 否 | — | — | 本表对象在其生命周期中的当前状态 |
| `version` | `bigint` | 否 | `0` | — | 表内实体版本或状态修订号 |
| `last_event_seq` | `bigint` | 否 | `0` | — | 同一 Run 已提交的最后一个进度事件序号 |
| `gate_policy_version` | `text` | 是 | — | UQ | 验收规则集版本 |
| `gate_pass_iteration` | `integer` | 是 | — | FK | Run 成功所依据的通过验收迭代 |
| `gate_pass_status` | `text` | 是 | 生成 `'PASS'::text` | FK | 用于外键断言的通过状态常量 |
| `max_iterations` | `integer` | 否 | `1` | — | 允许的最大迭代次数 |
| `budget_limits` | `jsonb` | 否 | `'{}'::jsonb` | — | 运行预算上限 |
| `budget_used` | `jsonb` | 否 | `'{}'::jsonb` | — | 已消耗的运行或迭代预算 |
| `deadline_at` | `timestamp with time zone` | 否 | — | — | 运行总截止时间 |
| `cancel_requested_at` | `timestamp with time zone` | 是 | — | — | 收到取消请求的时间 |
| `terminal_reason` | `text` | 是 | — | — | Run 终态的结构化原因 |
| `result_ref` | `text` | 是 | — | — | 已授权输出的受控结果引用 |
| `created_at` | `timestamp with time zone` | 否 | `now()` | — | 记录创建时间 |
| `finished_at` | `timestamp with time zone` | 是 | — | — | 当前运行、节点尝试或迭代终结的时间 |

**约束**

- 主键 `run_pkey` `PRIMARY KEY (tenant_id, id)`
- 外键 `run_active_plan_fk` `FOREIGN KEY (tenant_id, id, active_plan_version) REFERENCES runtime.plan(tenant_id, run_id, version) DEFERRABLE INITIALLY DEFERRED`
- 外键 `run_passed_iteration_fk` `FOREIGN KEY (tenant_id, id, gate_pass_iteration, active_plan_version, gate_pass_status) REFERENCES runtime.iteration(tenant_id, run_id, iteration_no, plan_version, status) DEFERRABLE INITIALLY DEFERRED`
- 外键 `run_tenant_id_task_id_input_version_route_decision_id_rout_fkey` `FOREIGN KEY (tenant_id, task_id, input_version, route_decision_id, route, admitted_verdict) REFERENCES intent.route_decision(tenant_id, task_id, input_version, id, route, verdict)`
- 唯一 `run_tenant_id_id_gate_policy_version_key` `UNIQUE (tenant_id, id, gate_policy_version)`
- 唯一 `run_tenant_id_task_id_input_version_key` `UNIQUE (tenant_id, task_id, input_version)`
- 检查 `run_active_plan_version_check` `CHECK (active_plan_version > 0)`
- 检查 `run_admitted_verdict_check` `CHECK (admitted_verdict = 'ALLOW'::text)`
- 检查 `run_budget_limits_check` `CHECK (jsonb_typeof(budget_limits) = 'object'::text)`
- 检查 `run_budget_used_check` `CHECK (jsonb_typeof(budget_used) = 'object'::text)`
- 检查 `run_check` `CHECK (gate_policy_version IS NULL OR route = 'AGENT'::text)`
- 检查 `run_check1` `CHECK ((status = 'SUCCEEDED'::text AND gate_policy_version IS NOT NULL) = (gate_pass_iteration IS NOT NULL))`
- 检查 `run_check2` `CHECK ((status = ANY (ARRAY['SUCCEEDED'::text, 'FAILED'::text, 'CANCELLED'::text])) = (finished_at IS NOT NULL))`
- 检查 `run_last_event_seq_check` `CHECK (last_event_seq >= 0)`
- 检查 `run_max_iterations_check` `CHECK (max_iterations > 0)`
- 检查 `run_route_check` `CHECK (route = ANY (ARRAY['DIRECT'::text, 'WORKFLOW'::text, 'AGENT'::text]))`
- 检查 `run_status_check` `CHECK (status = ANY (ARRAY['QUEUED'::text, 'RUNNING'::text, 'WAITING_APPROVAL'::text, 'WAITING_EXTERNAL'::text, 'WAITING_RETRY'::text, 'CANCEL_REQUESTED'::text, 'NEEDS_RECONCILIATION'::text, 'SUCCEEDED'::text, 'FAILED'::text, 'CANCELLED'::text]))`
- 检查 `run_version_check` `CHECK (version >= 0)`

**独立索引**

- `CREATE INDEX run_deadline_idx ON runtime.run USING btree (deadline_at) WHERE (finished_at IS NULL)`
- `CREATE UNIQUE INDEX run_one_active_per_task_idx ON runtime.run USING btree (tenant_id, task_id) WHERE (finished_at IS NULL)`

### runtime.plan

运行计划版本及来源

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK / UQ | 可信租户标识，用于隔离业务记录 |
| `run_id` | `uuid` | 否 | — | PK / FK / UQ | 所属 Run 标识 |
| `version` | `integer` | 否 | — | PK | 表内实体版本或状态修订号 |
| `origin` | `text` | 否 | — | FK | 计划来自直接执行、固定流程还是动态规划 |
| `proposal_id` | `uuid` | 是 | — | FK / UQ | 发布计划所关联的规划提案 |
| `workflow_skill_version_id` | `uuid` | 是 | — | FK | Task 或 Plan 固定使用的 SOP Skill 版本 |
| `definition_ref` | `text` | 是 | — | — | 内置流程或定义引用 |
| `plan_hash` | `text` | 否 | — | — | 计划内容的完整性摘要 |
| `created_at` | `timestamp with time zone` | 否 | `now()` | — | 记录创建时间 |

**约束**

- 主键 `plan_pkey` `PRIMARY KEY (tenant_id, run_id, version)`
- 外键 `plan_proposal_fk` `FOREIGN KEY (tenant_id, run_id, proposal_id) REFERENCES runtime.plan_proposal(tenant_id, run_id, id)`
- 外键 `plan_tenant_id_run_id_fkey` `FOREIGN KEY (tenant_id, run_id) REFERENCES runtime.run(tenant_id, id)`
- 外键 `plan_workflow_skill_fk` `FOREIGN KEY (tenant_id, workflow_skill_version_id, origin) REFERENCES runtime.workflow_skill_version(tenant_id, id, execution_mode)`
- 唯一 `plan_tenant_id_run_id_proposal_id_key` `UNIQUE (tenant_id, run_id, proposal_id)`
- 检查 `plan_check` `CHECK ((origin = ANY (ARRAY['PLANNER'::text, 'REPLAN'::text])) = (proposal_id IS NOT NULL))`
- 检查 `plan_origin_check` `CHECK (origin = ANY (ARRAY['DIRECT'::text, 'WORKFLOW'::text, 'BOOTSTRAP'::text, 'PLANNER'::text, 'REPLAN'::text]))`
- 检查 `plan_version_check` `CHECK (version > 0)`

### runtime.plan_proposal

L4 规划提案、校验和发布

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK | 可信租户标识，用于隔离业务记录 |
| `run_id` | `uuid` | 否 | — | PK / FK | 所属 Run 标识 |
| `id` | `uuid` | 否 | — | PK | 本表实体标识，通常与租户组成主键 |
| `base_plan_version` | `integer` | 否 | — | FK | 动态提案所基于的计划版本 |
| `kind` | `text` | 否 | — | — | 本表所定义的对象或操作子类型 |
| `trigger_reason` | `text` | 否 | — | — | 请求规划或重规划的原因 |
| `planner_version` | `text` | 否 | — | — | 生成提案所用的规划器版本 |
| `context_hash` | `text` | 是 | — | — | 组装上下文的摘要 |
| `context_ref` | `text` | 是 | — | — | 规划输入上下文引用 |
| `draft_ref` | `text` | 是 | — | — | 规划模型产出草案的受控引用 |
| `status` | `text` | 否 | — | — | 本表对象在其生命周期中的当前状态 |
| `validation_result` | `jsonb` | 是 | — | — | 计划提案的校验结果与原因 |
| `next_at` | `timestamp with time zone` | 否 | `now()` | — | 下次可领取、重试或复核的时间 |
| `lease_epoch` | `bigint` | 否 | `0` | — | 拒绝旧领取者提交的递增租约代次 |
| `lease_owner` | `text` | 是 | — | — | 当前任务领取者 |
| `lease_until` | `timestamp with time zone` | 是 | — | — | 当前领取有效期 |
| `attempts` | `integer` | 否 | `0` | — | 已尝试领取或投递次数 |
| `created_at` | `timestamp with time zone` | 否 | `now()` | — | 记录创建时间 |
| `settled_at` | `timestamp with time zone` | 是 | — | — | 提案处理结算时间 |

**约束**

- 主键 `plan_proposal_pkey` `PRIMARY KEY (tenant_id, run_id, id)`
- 外键 `plan_proposal_tenant_id_run_id_base_plan_version_fkey` `FOREIGN KEY (tenant_id, run_id, base_plan_version) REFERENCES runtime.plan(tenant_id, run_id, version)`
- 检查 `plan_proposal_attempts_check` `CHECK (attempts >= 0)`
- 检查 `plan_proposal_base_plan_version_check` `CHECK (base_plan_version > 0)`
- 检查 `plan_proposal_check` `CHECK ((status <> ALL (ARRAY['GENERATED'::text, 'PUBLISHED'::text])) OR context_hash IS NOT NULL AND context_ref IS NOT NULL AND draft_ref IS NOT NULL)`
- 检查 `plan_proposal_check1` `CHECK ((lease_owner IS NULL) = (lease_until IS NULL))`
- 检查 `plan_proposal_check2` `CHECK ((status = ANY (ARRAY['PUBLISHED'::text, 'REJECTED'::text, 'STALE'::text, 'CANCELLED'::text])) = (settled_at IS NOT NULL))`
- 检查 `plan_proposal_kind_check` `CHECK (kind = ANY (ARRAY['INITIAL'::text, 'REPLAN'::text]))`
- 检查 `plan_proposal_lease_epoch_check` `CHECK (lease_epoch >= 0)`
- 检查 `plan_proposal_status_check` `CHECK (status = ANY (ARRAY['REQUESTED'::text, 'GENERATED'::text, 'PUBLISHED'::text, 'REJECTED'::text, 'STALE'::text, 'CANCELLED'::text]))`

**独立索引**

- `CREATE INDEX plan_proposal_due_idx ON runtime.plan_proposal USING btree (next_at, lease_until) WHERE (status = ANY (ARRAY['REQUESTED'::text, 'GENERATED'::text]))`
- `CREATE UNIQUE INDEX plan_proposal_open_idx ON runtime.plan_proposal USING btree (tenant_id, run_id) WHERE (status = ANY (ARRAY['REQUESTED'::text, 'GENERATED'::text]))`

### runtime.step

DAG 节点、调度状态与租约

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK / UQ | 可信租户标识，用于隔离业务记录 |
| `id` | `uuid` | 否 | — | PK / FK / UQ | 本表实体标识，通常与租户组成主键 |
| `run_id` | `uuid` | 否 | — | FK / UQ | 所属 Run 标识 |
| `plan_version` | `integer` | 否 | — | FK / UQ | Run 内的计划版本 |
| `node_key` | `text` | 否 | — | UQ | 计划或 Skill 内稳定节点键 |
| `kind` | `text` | 否 | — | — | 本表所定义的对象或操作子类型 |
| `spec` | `jsonb` | 否 | — | — | 已校验的节点类型、参数及分支配置 |
| `status` | `text` | 否 | — | — | 本表对象在其生命周期中的当前状态 |
| `operation_key` | `text` | 是 | — | UQ | 跨重试保持稳定的逻辑操作键 |
| `tool_execution_id` | `uuid` | 是 | — | FK | 本步骤绑定的网关执行标识 |
| `observation_ref` | `text` | 是 | — | — | 已保存的节点观测结果引用 |
| `current_attempt` | `integer` | 否 | `0` | — | 当前步骤的尝试序号 |
| `next_at` | `timestamp with time zone` | 否 | `now()` | — | 下次可领取、重试或复核的时间 |
| `lease_epoch` | `bigint` | 否 | `0` | — | 拒绝旧领取者提交的递增租约代次 |
| `lease_owner` | `text` | 是 | — | — | 当前任务领取者 |
| `lease_until` | `timestamp with time zone` | 是 | — | — | 当前领取有效期 |
| `updated_at` | `timestamp with time zone` | 否 | `now()` | — | 最近一次业务更新的时间 |

**约束**

- 主键 `step_pkey` `PRIMARY KEY (tenant_id, id)`
- 外键 `step_tenant_id_run_id_plan_version_fkey` `FOREIGN KEY (tenant_id, run_id, plan_version) REFERENCES runtime.plan(tenant_id, run_id, version)`
- 外键 `step_tool_execution_fk` `FOREIGN KEY (tenant_id, tool_execution_id, run_id, id) REFERENCES gateway.tool_execution(tenant_id, id, run_id, step_id)`
- 唯一 `step_tenant_id_run_id_id_key` `UNIQUE (tenant_id, run_id, id)`
- 唯一 `step_tenant_id_run_id_operation_key_key` `UNIQUE (tenant_id, run_id, operation_key)`
- 唯一 `step_tenant_id_run_id_plan_version_id_key` `UNIQUE (tenant_id, run_id, plan_version, id)`
- 唯一 `step_tenant_id_run_id_plan_version_node_key_key` `UNIQUE (tenant_id, run_id, plan_version, node_key)`
- 检查 `step_check` `CHECK ((lease_owner IS NULL) = (lease_until IS NULL))`
- 检查 `step_check1` `CHECK ((kind = 'TOOL'::text) = (operation_key IS NOT NULL))`
- 检查 `step_check2` `CHECK (tool_execution_id IS NULL OR kind = 'TOOL'::text)`
- 检查 `step_current_attempt_check` `CHECK (current_attempt >= 0)`
- 检查 `step_kind_check` `CHECK (kind = ANY (ARRAY['TOOL'::text, 'COMPUTE'::text, 'GATE'::text]))`
- 检查 `step_lease_epoch_check` `CHECK (lease_epoch >= 0)`
- 检查 `step_status_check` `CHECK (status = ANY (ARRAY['PENDING'::text, 'READY'::text, 'RUNNING'::text, 'WAITING_APPROVAL'::text, 'WAITING_EXTERNAL'::text, 'RETRY_WAIT'::text, 'NEEDS_RECONCILIATION'::text, 'SUCCEEDED'::text, 'FAILED'::text, 'CANCELLED'::text, 'SUPERSEDED'::text, 'SKIPPED'::text]))`

**独立索引**

- `CREATE INDEX step_due_idx ON runtime.step USING btree (next_at, lease_until) WHERE (status = ANY (ARRAY['READY'::text, 'RUNNING'::text, 'RETRY_WAIT'::text, 'WAITING_APPROVAL'::text, 'WAITING_EXTERNAL'::text, 'NEEDS_RECONCILIATION'::text]))`
- `CREATE INDEX step_run_state_idx ON runtime.step USING btree (tenant_id, run_id, plan_version, status)`

### runtime.step_dependency

当前计划内的有向依赖

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK | 可信租户标识，用于隔离业务记录 |
| `run_id` | `uuid` | 否 | — | PK / FK | 所属 Run 标识 |
| `plan_version` | `integer` | 否 | — | PK / FK | Run 内的计划版本 |
| `step_id` | `uuid` | 否 | — | PK / FK | 所属的运行节点标识 |
| `depends_on_step_id` | `uuid` | 否 | — | PK / FK | 当前节点的前置步骤 |

**约束**

- 主键 `step_dependency_pkey` `PRIMARY KEY (tenant_id, run_id, plan_version, step_id, depends_on_step_id)`
- 外键 `step_dependency_tenant_id_run_id_plan_version_depends_on_s_fkey` `FOREIGN KEY (tenant_id, run_id, plan_version, depends_on_step_id) REFERENCES runtime.step(tenant_id, run_id, plan_version, id)`
- 外键 `step_dependency_tenant_id_run_id_plan_version_step_id_fkey` `FOREIGN KEY (tenant_id, run_id, plan_version, step_id) REFERENCES runtime.step(tenant_id, run_id, plan_version, id)`
- 检查 `step_dependency_check` `CHECK (step_id <> depends_on_step_id)`

**独立索引**

- `CREATE INDEX step_dependency_reverse_idx ON runtime.step_dependency USING btree (tenant_id, run_id, plan_version, depends_on_step_id)`

### runtime.step_attempt

节点领取和执行尝试

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK | 可信租户标识，用于隔离业务记录 |
| `step_id` | `uuid` | 否 | — | PK / FK | 所属的运行节点标识 |
| `attempt_no` | `integer` | 否 | — | PK | 节点尝试序号 |
| `lease_epoch` | `bigint` | 否 | — | — | 拒绝旧领取者提交的递增租约代次 |
| `worker_id` | `text` | 否 | — | — | 本次步骤尝试的 Worker 标识 |
| `status` | `text` | 否 | — | — | 本表对象在其生命周期中的当前状态 |
| `error_code` | `text` | 是 | — | — | 执行或尝试的结构化错误码 |
| `started_at` | `timestamp with time zone` | 否 | `now()` | — | 实际开始执行的时间 |
| `finished_at` | `timestamp with time zone` | 是 | — | — | 当前运行、节点尝试或迭代终结的时间 |

**约束**

- 主键 `step_attempt_pkey` `PRIMARY KEY (tenant_id, step_id, attempt_no)`
- 外键 `step_attempt_tenant_id_step_id_fkey` `FOREIGN KEY (tenant_id, step_id) REFERENCES runtime.step(tenant_id, id)`
- 检查 `step_attempt_attempt_no_check` `CHECK (attempt_no > 0)`
- 检查 `step_attempt_lease_epoch_check` `CHECK (lease_epoch > 0)`
- 检查 `step_attempt_status_check` `CHECK (status = ANY (ARRAY['RUNNING'::text, 'DONE'::text, 'FAILED'::text, 'LEASE_EXPIRED'::text, 'SUSPENDED'::text]))`

### runtime.checkpoint

可恢复的结构化快照

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK | 可信租户标识，用于隔离业务记录 |
| `run_id` | `uuid` | 否 | — | PK / FK | 所属 Run 标识 |
| `seq` | `bigint` | 否 | — | PK | 所属会话或 Run 中的顺序号 |
| `plan_version` | `integer` | 否 | — | FK | Run 内的计划版本 |
| `after_step_id` | `uuid` | 是 | — | FK | 保存快照前最后结算的步骤 |
| `graph_thread_key` | `text` | 否 | — | — | LangGraph4j 图执行线程关联键 |
| `format_version` | `integer` | 否 | — | — | 快照或产物格式版本 |
| `graph_state` | `jsonb` | 否 | — | — | 可恢复的结构化小型图状态 |
| `created_at` | `timestamp with time zone` | 否 | `now()` | — | 记录创建时间 |

**约束**

- 主键 `checkpoint_pkey` `PRIMARY KEY (tenant_id, run_id, seq)`
- 外键 `checkpoint_tenant_id_run_id_plan_version_after_step_id_fkey` `FOREIGN KEY (tenant_id, run_id, plan_version, after_step_id) REFERENCES runtime.step(tenant_id, run_id, plan_version, id)`
- 外键 `checkpoint_tenant_id_run_id_plan_version_fkey` `FOREIGN KEY (tenant_id, run_id, plan_version) REFERENCES runtime.plan(tenant_id, run_id, version)`
- 检查 `checkpoint_format_version_check` `CHECK (format_version > 0)`
- 检查 `checkpoint_graph_state_check` `CHECK (jsonb_typeof(graph_state) = 'object'::text)`
- 检查 `checkpoint_seq_check` `CHECK (seq > 0)`

**独立索引**

- `CREATE INDEX checkpoint_resume_idx ON runtime.checkpoint USING btree (tenant_id, run_id, graph_thread_key, plan_version, seq DESC)`

### runtime.context_artifact

外置产物的不可变版本与校验摘要

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK / UQ | 可信租户标识，用于隔离业务记录 |
| `run_id` | `uuid` | 否 | — | PK / FK / UQ | 所属 Run 标识 |
| `id` | `uuid` | 否 | — | PK | 本表实体标识，通常与租户组成主键 |
| `step_id` | `uuid` | 是 | — | FK | 所属的运行节点标识 |
| `artifact_key` | `text` | 否 | — | UQ | 同个 Run 内稳定的产物业务键 |
| `version` | `integer` | 否 | — | UQ | 表内实体版本或状态修订号 |
| `kind` | `text` | 否 | — | — | 本表所定义的对象或操作子类型 |
| `content_ref` | `text` | 否 | — | — | 私有存储中的内容引用 |
| `content_digest` | `text` | 否 | — | — | 原始内容完整性摘要 |
| `media_type` | `text` | 否 | — | — | 产物内容类型 |
| `format_version` | `integer` | 否 | — | — | 快照或产物格式版本 |
| `size_bytes` | `bigint` | 否 | — | — | 内容大小，单位字节 |
| `created_at` | `timestamp with time zone` | 否 | `now()` | — | 记录创建时间 |

**约束**

- 主键 `context_artifact_pkey` `PRIMARY KEY (tenant_id, run_id, id)`
- 外键 `context_artifact_tenant_id_run_id_fkey` `FOREIGN KEY (tenant_id, run_id) REFERENCES runtime.run(tenant_id, id)`
- 外键 `context_artifact_tenant_id_run_id_step_id_fkey` `FOREIGN KEY (tenant_id, run_id, step_id) REFERENCES runtime.step(tenant_id, run_id, id)`
- 唯一 `context_artifact_tenant_id_run_id_artifact_key_version_key` `UNIQUE (tenant_id, run_id, artifact_key, version)`
- 检查 `context_artifact_format_version_check` `CHECK (format_version > 0)`
- 检查 `context_artifact_kind_check` `CHECK (kind = ANY (ARRAY['INPUT_SNAPSHOT'::text, 'TOOL_RESULT'::text, 'OBSERVATION'::text, 'REPORT'::text, 'PLAN_CONTEXT'::text]))`
- 检查 `context_artifact_size_bytes_check` `CHECK (size_bytes >= 0)`
- 检查 `context_artifact_version_check` `CHECK (version > 0)`

### runtime.checkpoint_artifact

快照与同 Run 产物的绑定

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK | 可信租户标识，用于隔离业务记录 |
| `run_id` | `uuid` | 否 | — | PK / FK | 所属 Run 标识 |
| `checkpoint_seq` | `bigint` | 否 | — | PK / FK | Run 内的 Checkpoint 序号 |
| `artifact_id` | `uuid` | 否 | — | FK | 外置产物标识 |
| `binding_key` | `text` | 否 | — | PK | 快照内用于恢复产物的绑定键 |

**约束**

- 主键 `checkpoint_artifact_pkey` `PRIMARY KEY (tenant_id, run_id, checkpoint_seq, binding_key)`
- 外键 `checkpoint_artifact_tenant_id_run_id_artifact_id_fkey` `FOREIGN KEY (tenant_id, run_id, artifact_id) REFERENCES runtime.context_artifact(tenant_id, run_id, id)`
- 外键 `checkpoint_artifact_tenant_id_run_id_checkpoint_seq_fkey` `FOREIGN KEY (tenant_id, run_id, checkpoint_seq) REFERENCES runtime.checkpoint(tenant_id, run_id, seq)`

**独立索引**

- `CREATE INDEX checkpoint_artifact_usage_idx ON runtime.checkpoint_artifact USING btree (tenant_id, run_id, artifact_id)`

### runtime.iteration

Ralph 迭代上下文与结果

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK / UQ | 可信租户标识，用于隔离业务记录 |
| `run_id` | `uuid` | 否 | — | PK / FK / UQ | 所属 Run 标识 |
| `iteration_no` | `integer` | 否 | — | PK / UQ | 同一 Run 内的迭代号 |
| `plan_version` | `integer` | 否 | — | FK / UQ | Run 内的计划版本 |
| `gate_policy_version` | `text` | 否 | — | FK | 验收规则集版本 |
| `context_hash` | `text` | 否 | — | — | 组装上下文的摘要 |
| `context_manifest` | `jsonb` | 否 | — | — | 本轮上下文组成和受控引用 |
| `status` | `text` | 否 | — | UQ | 本表对象在其生命周期中的当前状态 |
| `budget_used` | `jsonb` | 否 | `'{}'::jsonb` | — | 已消耗的运行或迭代预算 |
| `created_at` | `timestamp with time zone` | 否 | `now()` | — | 记录创建时间 |
| `finished_at` | `timestamp with time zone` | 是 | — | — | 当前运行、节点尝试或迭代终结的时间 |

**约束**

- 主键 `iteration_pkey` `PRIMARY KEY (tenant_id, run_id, iteration_no)`
- 外键 `iteration_tenant_id_run_id_gate_policy_version_fkey` `FOREIGN KEY (tenant_id, run_id, gate_policy_version) REFERENCES runtime.run(tenant_id, id, gate_policy_version)`
- 外键 `iteration_tenant_id_run_id_plan_version_fkey` `FOREIGN KEY (tenant_id, run_id, plan_version) REFERENCES runtime.plan(tenant_id, run_id, version)`
- 唯一 `iteration_tenant_id_run_id_iteration_no_plan_version_key` `UNIQUE (tenant_id, run_id, iteration_no, plan_version)`
- 唯一 `iteration_tenant_id_run_id_iteration_no_plan_version_status_key` `UNIQUE (tenant_id, run_id, iteration_no, plan_version, status)`
- 检查 `iteration_check` `CHECK ((status = 'ACTIVE'::text) = (finished_at IS NULL))`
- 检查 `iteration_iteration_no_check` `CHECK (iteration_no > 0)`
- 检查 `iteration_status_check` `CHECK (status = ANY (ARRAY['ACTIVE'::text, 'PASS'::text, 'RETRYABLE_FAIL'::text, 'TERMINAL_FAIL'::text, 'CANCELLED'::text]))`

**独立索引**

- `CREATE UNIQUE INDEX iteration_active_idx ON runtime.iteration USING btree (tenant_id, run_id) WHERE (status = 'ACTIVE'::text)`

### runtime.gate_check

验收规则逐项判定的证据

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK | 可信租户标识，用于隔离业务记录 |
| `run_id` | `uuid` | 否 | — | PK / FK | 所属 Run 标识 |
| `iteration_no` | `integer` | 否 | — | PK / FK | 同一 Run 内的迭代号 |
| `plan_version` | `integer` | 否 | — | FK | Run 内的计划版本 |
| `check_key` | `text` | 否 | — | PK | 验收规则项的稳定键 |
| `verifier_version` | `text` | 否 | — | — | 执行该检查的验收器版本 |
| `required` | `boolean` | 否 | `true` | — | 本项验收是否为必须项 |
| `verdict` | `text` | 否 | — | — | 业务准入或 Gate 检查的裁决 |
| `evidence_ref` | `text` | 是 | — | — | 检查证据的受控引用 |
| `checked_at` | `timestamp with time zone` | 否 | `now()` | — | 验收检查时间 |

**约束**

- 主键 `gate_check_pkey` `PRIMARY KEY (tenant_id, run_id, iteration_no, check_key)`
- 外键 `gate_check_tenant_id_run_id_iteration_no_plan_version_fkey` `FOREIGN KEY (tenant_id, run_id, iteration_no, plan_version) REFERENCES runtime.iteration(tenant_id, run_id, iteration_no, plan_version)`
- 检查 `gate_check_verdict_check` `CHECK (verdict = ANY (ARRAY['PASS'::text, 'RETRYABLE_FAIL'::text, 'TERMINAL_FAIL'::text]))`

### runtime.run_event

可补发的 Run 进度事件

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK / UQ | 可信租户标识，用于隔离业务记录 |
| `run_id` | `uuid` | 否 | — | PK / FK | 所属 Run 标识 |
| `seq` | `bigint` | 否 | — | PK | 所属会话或 Run 中的顺序号 |
| `event_id` | `uuid` | 否 | — | UQ | 领域事件的幂等标识 |
| `event_type` | `text` | 否 | — | — | 事件名称 |
| `schema_version` | `integer` | 否 | `1` | — | 事件或信封结构版本 |
| `payload` | `jsonb` | 否 | `'{}'::jsonb` | — | 受控的事件载荷 |
| `occurred_at` | `timestamp with time zone` | 否 | `now()` | — | 事件或审计事实发生时间 |

**约束**

- 主键 `run_event_pkey` `PRIMARY KEY (tenant_id, run_id, seq)`
- 外键 `run_event_tenant_id_run_id_fkey` `FOREIGN KEY (tenant_id, run_id) REFERENCES runtime.run(tenant_id, id)`
- 唯一 `run_event_tenant_id_event_id_key` `UNIQUE (tenant_id, event_id)`
- 检查 `run_event_schema_version_check` `CHECK (schema_version > 0)`
- 检查 `run_event_seq_check` `CHECK (seq > 0)`

### runtime.run_command

取消和对账命令的幂等回执

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK | 可信租户标识，用于隔离业务记录 |
| `run_id` | `uuid` | 否 | — | PK / FK | 所属 Run 标识 |
| `actor_user_id` | `text` | 否 | — | PK | 触发本轮或命令的用户标识 |
| `command_key` | `text` | 否 | — | PK | 用户运行控制命令的幂等键 |
| `command_type` | `text` | 否 | — | — | 取消或对账等运行命令类型 |
| `request_hash` | `text` | 否 | — | — | 规范化请求指纹，用于排查重复键冲突 |
| `receipt` | `jsonb` | 否 | — | — | 供重复请求返回的已持久化回执 |
| `created_at` | `timestamp with time zone` | 否 | `now()` | — | 记录创建时间 |

**约束**

- 主键 `run_command_pkey` `PRIMARY KEY (tenant_id, run_id, actor_user_id, command_key)`
- 外键 `run_command_tenant_id_run_id_fkey` `FOREIGN KEY (tenant_id, run_id) REFERENCES runtime.run(tenant_id, id)`
- 检查 `run_command_command_type_check` `CHECK (command_type = ANY (ARRAY['CANCEL'::text, 'RECONCILE'::text]))`

## SOP 和固定工作流

### runtime.workflow_skill

企业 SOP 的稳定注册标识

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / UQ | 可信租户标识，用于隔离业务记录 |
| `id` | `uuid` | 否 | — | PK | 本表实体标识，通常与租户组成主键 |
| `skill_key` | `text` | 否 | — | UQ | 业务可引用的稳定 Skill 键 |
| `display_name` | `text` | 否 | — | — | 面向使用者的显示名称 |
| `status` | `text` | 否 | — | — | 本表对象在其生命周期中的当前状态 |
| `revision` | `bigint` | 否 | `0` | — | 实体修订号 |
| `created_at` | `timestamp with time zone` | 否 | `now()` | — | 记录创建时间 |

**约束**

- 主键 `workflow_skill_pkey` `PRIMARY KEY (tenant_id, id)`
- 唯一 `workflow_skill_tenant_id_skill_key_key` `UNIQUE (tenant_id, skill_key)`
- 检查 `workflow_skill_revision_check` `CHECK (revision >= 0)`
- 检查 `workflow_skill_status_check` `CHECK (status = ANY (ARRAY['DRAFT'::text, 'ACTIVE'::text, 'DISABLED'::text]))`

### runtime.workflow_skill_version

清单、编译 DAG 与审核发布记录

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK / UQ | 可信租户标识，用于隔离业务记录 |
| `id` | `uuid` | 否 | — | PK / UQ | 本表实体标识，通常与租户组成主键 |
| `skill_id` | `uuid` | 否 | — | FK / UQ | SOP Skill 的稳定标识 |
| `version` | `integer` | 否 | — | UQ | 表内实体版本或状态修订号 |
| `intent_definition_id` | `uuid` | 否 | — | FK | SOP 绑定的已发布意图定义 |
| `execution_mode` | `text` | 否 | — | UQ | Skill 可用的固定执行模式 |
| `status` | `text` | 否 | — | — | 本表对象在其生命周期中的当前状态 |
| `is_current` | `boolean` | 否 | `false` | — | 是否是新请求的默认版本 |
| `source_ref` | `text` | 否 | — | — | 源文件、档案来源或源文档的引用 |
| `admission_policy_ref` | `text` | 否 | — | — | SOP 业务准入策略引用 |
| `compiled_dag` | `jsonb` | 是 | — | — | 可执行的已编译固定 DAG |
| `compiled_digest` | `text` | 是 | — | — | 编译产物摘要 |
| `reviewed_by` | `text` | 是 | — | — | SOP 版本审核人 |
| `reviewed_at` | `timestamp with time zone` | 是 | — | — | SOP 版本通过审核的时间 |
| `published_by` | `text` | 是 | — | — | 发布 SOP 版本的操作者 |
| `created_at` | `timestamp with time zone` | 否 | `now()` | — | 记录创建时间 |
| `published_at` | `timestamp with time zone` | 是 | — | — | 生效发布时间或消息确认投递时间 |

**约束**

- 主键 `workflow_skill_version_pkey` `PRIMARY KEY (tenant_id, id)`
- 外键 `workflow_skill_version_tenant_id_intent_definition_id_fkey` `FOREIGN KEY (tenant_id, intent_definition_id) REFERENCES intent.definition(tenant_id, id)`
- 外键 `workflow_skill_version_tenant_id_skill_id_fkey` `FOREIGN KEY (tenant_id, skill_id) REFERENCES runtime.workflow_skill(tenant_id, id)`
- 唯一 `workflow_skill_version_tenant_id_id_execution_mode_key` `UNIQUE (tenant_id, id, execution_mode)`
- 唯一 `workflow_skill_version_tenant_id_skill_id_version_key` `UNIQUE (tenant_id, skill_id, version)`
- 检查 `workflow_skill_version_check` `CHECK (NOT is_current OR status = 'PUBLISHED'::text)`
- 检查 `workflow_skill_version_check1` `CHECK ((status <> ALL (ARRAY['REVIEWED'::text, 'PUBLISHED'::text, 'RETIRED'::text])) OR reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)`
- 检查 `workflow_skill_version_check2` `CHECK ((status <> ALL (ARRAY['PUBLISHED'::text, 'RETIRED'::text])) OR published_by IS NOT NULL AND published_at IS NOT NULL AND compiled_dag IS NOT NULL AND compiled_digest IS NOT NULL)`
- 检查 `workflow_skill_version_compiled_dag_check` `CHECK (jsonb_typeof(compiled_dag) = 'object'::text)`
- 检查 `workflow_skill_version_execution_mode_check` `CHECK (execution_mode = ANY (ARRAY['DIRECT'::text, 'WORKFLOW'::text]))`
- 检查 `workflow_skill_version_status_check` `CHECK (status = ANY (ARRAY['DRAFT'::text, 'REVIEWED'::text, 'PUBLISHED'::text, 'RETIRED'::text, 'DISABLED'::text]))`
- 检查 `workflow_skill_version_version_check` `CHECK (version > 0)`

**独立索引**

- `CREATE UNIQUE INDEX workflow_skill_active_version_idx ON runtime.workflow_skill_version USING btree (tenant_id, skill_id) WHERE is_current`

### runtime.workflow_skill_trigger

命令、表单或精确别名映射

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK | 可信租户标识，用于隔离业务记录 |
| `trigger_kind` | `text` | 否 | — | PK | L0 触发器类型 |
| `normalized_key` | `text` | 否 | — | PK | 确定性触发器的规范化匹配键 |
| `skill_id` | `uuid` | 否 | — | FK | SOP Skill 的稳定标识 |

**约束**

- 主键 `workflow_skill_trigger_pkey` `PRIMARY KEY (tenant_id, trigger_kind, normalized_key)`
- 外键 `workflow_skill_trigger_tenant_id_skill_id_fkey` `FOREIGN KEY (tenant_id, skill_id) REFERENCES runtime.workflow_skill(tenant_id, id)`
- 检查 `workflow_skill_trigger_trigger_kind_check` `CHECK (trigger_kind = ANY (ARRAY['COMMAND'::text, 'FORM_ACTION'::text, 'EXACT_ALIAS'::text]))`

### runtime.workflow_skill_tool

Skill 节点到工具版本的绑定

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK | 可信租户标识，用于隔离业务记录 |
| `skill_version_id` | `uuid` | 否 | — | PK / FK | Skill 的发布版本标识 |
| `node_key` | `text` | 否 | — | PK | 计划或 Skill 内稳定节点键 |
| `tool_id` | `uuid` | 否 | — | FK | 工具稳定标识 |
| `tool_version_id` | `uuid` | 否 | — | FK | 已绑定的具体工具版本 |

**约束**

- 主键 `workflow_skill_tool_pkey` `PRIMARY KEY (tenant_id, skill_version_id, node_key)`
- 外键 `workflow_skill_tool_tenant_id_skill_version_id_fkey` `FOREIGN KEY (tenant_id, skill_version_id) REFERENCES runtime.workflow_skill_version(tenant_id, id)`
- 外键 `workflow_skill_tool_tenant_id_tool_id_tool_version_id_fkey` `FOREIGN KEY (tenant_id, tool_id, tool_version_id) REFERENCES gateway.tool_version(tenant_id, tool_id, id)`

## 工具网关

### gateway.tool_server

HTTP、MCP 等受管工具服务

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / UQ | 可信租户标识，用于隔离业务记录 |
| `id` | `uuid` | 否 | — | PK / UQ | 本表实体标识，通常与租户组成主键 |
| `name` | `text` | 否 | — | UQ | 注册名称 |
| `protocol` | `text` | 否 | — | UQ | 工具接入协议 |
| `status` | `text` | 否 | — | — | 本表对象在其生命周期中的当前状态 |

**约束**

- 主键 `tool_server_pkey` `PRIMARY KEY (tenant_id, id)`
- 唯一 `tool_server_tenant_id_id_protocol_key` `UNIQUE (tenant_id, id, protocol)`
- 唯一 `tool_server_tenant_id_name_key` `UNIQUE (tenant_id, name)`
- 检查 `tool_server_protocol_check` `CHECK (protocol = ANY (ARRAY['HTTP'::text, 'MCP'::text, 'GRPC'::text, 'DATABASE'::text, 'RAG'::text, 'SANDBOX'::text, 'LOCAL'::text, 'ASYNC'::text]))`
- 检查 `tool_server_status_check` `CHECK (status = ANY (ARRAY['ENABLED'::text, 'DISABLED'::text]))`

### gateway.tool

工具身份、风险级别与当前版本

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK / UQ | 可信租户标识，用于隔离业务记录 |
| `id` | `uuid` | 否 | — | PK / FK | 本表实体标识，通常与租户组成主键 |
| `name` | `text` | 否 | — | UQ | 注册名称 |
| `display_name` | `text` | 是 | — | — | 面向使用者的显示名称 |
| `description` | `text` | 否 | — | — | 注册的业务描述 |
| `category` | `text` | 是 | — | — | 工具分类 |
| `risk_level` | `text` | 否 | — | — | 管理端配置的工具风险等级 |
| `status` | `text` | 否 | — | — | 本表对象在其生命周期中的当前状态 |
| `current_version_id` | `uuid` | 是 | — | FK | 当前生效版本标识 |
| `revision` | `bigint` | 否 | `0` | — | 实体修订号 |

**约束**

- 主键 `tool_pkey` `PRIMARY KEY (tenant_id, id)`
- 外键 `tool_current_version_fk` `FOREIGN KEY (tenant_id, id, current_version_id) REFERENCES gateway.tool_version(tenant_id, tool_id, id)`
- 唯一 `tool_tenant_id_name_key` `UNIQUE (tenant_id, name)`
- 检查 `tool_check` `CHECK (status <> 'ACTIVE'::text OR current_version_id IS NOT NULL)`
- 检查 `tool_revision_check` `CHECK (revision >= 0)`
- 检查 `tool_risk_level_check` `CHECK (risk_level = ANY (ARRAY['LOW'::text, 'MEDIUM'::text, 'HIGH'::text, 'CRITICAL'::text]))`
- 检查 `tool_status_check` `CHECK (status = ANY (ARRAY['DRAFT'::text, 'ACTIVE'::text, 'DISABLED'::text]))`

### gateway.tool_version

版本化输入输出契约

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK / UQ | 可信租户标识，用于隔离业务记录 |
| `id` | `uuid` | 否 | — | PK / UQ | 本表实体标识，通常与租户组成主键 |
| `tool_id` | `uuid` | 否 | — | FK / UQ | 工具稳定标识 |
| `version_name` | `text` | 否 | — | UQ | 工具可公开引用的版本名称 |
| `status` | `text` | 否 | — | — | 本表对象在其生命周期中的当前状态 |
| `input_schema` | `jsonb` | 否 | — | — | 工具参数的权威 JSON Schema |
| `output_schema` | `jsonb` | 是 | — | — | 工具输出契约 |
| `contract_hash` | `text` | 否 | — | — | 工具版本输入输出契约摘要 |
| `published_at` | `timestamp with time zone` | 是 | — | — | 生效发布时间或消息确认投递时间 |

**约束**

- 主键 `tool_version_pkey` `PRIMARY KEY (tenant_id, id)`
- 外键 `tool_version_tenant_id_tool_id_fkey` `FOREIGN KEY (tenant_id, tool_id) REFERENCES gateway.tool(tenant_id, id)`
- 唯一 `tool_version_tenant_id_tool_id_id_key` `UNIQUE (tenant_id, tool_id, id)`
- 唯一 `tool_version_tenant_id_tool_id_version_name_key` `UNIQUE (tenant_id, tool_id, version_name)`
- 检查 `tool_version_check` `CHECK ((status <> ALL (ARRAY['PUBLISHED'::text, 'DEPRECATED'::text])) OR published_at IS NOT NULL)`
- 检查 `tool_version_input_schema_check` `CHECK (jsonb_typeof(input_schema) = 'object'::text)`
- 检查 `tool_version_output_schema_check` `CHECK (jsonb_typeof(output_schema) = 'object'::text)`
- 检查 `tool_version_status_check` `CHECK (status = ANY (ARRAY['DRAFT'::text, 'VALIDATING'::text, 'TESTING'::text, 'PUBLISHED'::text, 'DEPRECATED'::text, 'DISABLED'::text]))`

### gateway.tool_endpoint

协议端点和凭证引用

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK / UQ | 可信租户标识，用于隔离业务记录 |
| `id` | `uuid` | 否 | — | PK / UQ | 本表实体标识，通常与租户组成主键 |
| `version_id` | `uuid` | 否 | — | FK / UQ | 所绑定的工具版本主键 |
| `server_id` | `uuid` | 否 | — | FK | 受管工具服务器标识 |
| `protocol` | `text` | 否 | — | FK | 工具接入协议 |
| `target_ref` | `text` | 否 | — | — | 已注册的端点或路由目标引用 |
| `credential_ref` | `text` | 是 | — | — | Secret Manager 中的凭证引用 |
| `timeout_ms` | `integer` | 否 | — | — | 下游工具调用超时，单位毫秒 |
| `async_capable` | `boolean` | 否 | `false` | — | 端点是否提供异步提交契约 |
| `idempotency_supported` | `boolean` | 否 | `false` | — | 下游端点是否支持幂等契约 |
| `enabled` | `boolean` | 否 | `true` | — | 当前配置是否启用 |

**约束**

- 主键 `tool_endpoint_pkey` `PRIMARY KEY (tenant_id, id)`
- 外键 `tool_endpoint_tenant_id_server_id_protocol_fkey` `FOREIGN KEY (tenant_id, server_id, protocol) REFERENCES gateway.tool_server(tenant_id, id, protocol)`
- 外键 `tool_endpoint_tenant_id_version_id_fkey` `FOREIGN KEY (tenant_id, version_id) REFERENCES gateway.tool_version(tenant_id, id)`
- 唯一 `tool_endpoint_tenant_id_version_id_id_key` `UNIQUE (tenant_id, version_id, id)`
- 检查 `tool_endpoint_timeout_ms_check` `CHECK (timeout_ms > 0)`

### gateway.tool_permission

用户、角色、Agent 和服务的工具授权

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK | 可信租户标识，用于隔离业务记录 |
| `tool_id` | `uuid` | 否 | — | PK / FK | 工具稳定标识 |
| `subject_type` | `text` | 否 | — | PK | 授权对象类型 |
| `subject_id` | `text` | 否 | — | PK | 授权对象标识 |
| `action` | `text` | 否 | — | PK | 动作编码或授权动作 |
| `effect` | `text` | 否 | — | — | 授权的允许或拒绝效果 |
| `enabled` | `boolean` | 否 | `true` | — | 当前配置是否启用 |

**约束**

- 主键 `tool_permission_pkey` `PRIMARY KEY (tenant_id, tool_id, subject_type, subject_id, action)`
- 外键 `tool_permission_tenant_id_tool_id_fkey` `FOREIGN KEY (tenant_id, tool_id) REFERENCES gateway.tool(tenant_id, id)`
- 检查 `tool_permission_action_check` `CHECK (action = ANY (ARRAY['DISCOVER'::text, 'EXECUTE'::text, 'APPROVE'::text, 'MANAGE'::text]))`
- 检查 `tool_permission_effect_check` `CHECK (effect = ANY (ARRAY['ALLOW'::text, 'DENY'::text]))`
- 检查 `tool_permission_subject_type_check` `CHECK (subject_type = ANY (ARRAY['USER'::text, 'ROLE'::text, 'AGENT'::text, 'SERVICE'::text]))`

**独立索引**

- `CREATE INDEX tool_permission_subject_idx ON gateway.tool_permission USING btree (tenant_id, subject_type, subject_id, action) WHERE enabled`

### gateway.tool_policy

工具约束、审批、限额与重试策略

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK / UQ | 可信租户标识，用于隔离业务记录 |
| `id` | `uuid` | 否 | — | PK | 本表实体标识，通常与租户组成主键 |
| `tool_id` | `uuid` | 否 | — | FK / UQ | 工具稳定标识 |
| `policy_version` | `integer` | 否 | — | UQ | 准入或审批策略版本 |
| `kind` | `text` | 否 | — | UQ | 本表所定义的对象或操作子类型 |
| `rule` | `jsonb` | 否 | — | — | 受约束的结构化策略表达式 |
| `enabled` | `boolean` | 否 | `true` | — | 当前配置是否启用 |

**约束**

- 主键 `tool_policy_pkey` `PRIMARY KEY (tenant_id, id)`
- 外键 `tool_policy_tenant_id_tool_id_fkey` `FOREIGN KEY (tenant_id, tool_id) REFERENCES gateway.tool(tenant_id, id)`
- 唯一 `tool_policy_tenant_id_tool_id_kind_policy_version_key` `UNIQUE (tenant_id, tool_id, kind, policy_version)`
- 检查 `tool_policy_kind_check` `CHECK (kind = ANY (ARRAY['ABAC'::text, 'APPROVAL'::text, 'QUOTA'::text, 'RETRY'::text, 'REDACTION'::text]))`
- 检查 `tool_policy_policy_version_check` `CHECK (policy_version > 0)`

**独立索引**

- `CREATE UNIQUE INDEX tool_policy_current_idx ON gateway.tool_policy USING btree (tenant_id, tool_id, kind) WHERE enabled`

### gateway.tool_usage_window

工具限流与配额窗口，按租户、工具及计数主体隔离。

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK | 工具所属租户 |
| `tool_id` | `uuid` | 否 | — | PK / FK | 被计数的工具 |
| `scope` | `text` | 否 | — | PK | `PRINCIPAL` 或 `TENANT` |
| `subject_id` | `text` | 否 | — | PK | 主体标识，租户计数时为空字符串 |
| `unit` | `text` | 否 | — | PK | `MINUTE` 或 `DAY` |
| `window_start` | `timestamptz` | 否 | — | PK | UTC 时间窗口起点 |
| `used` | `integer` | 否 | — | — | 窗口内取得执行资格的次数，必须大于零 |

`(tenant_id, tool_id)` 关联 `gateway.tool`。`tool_usage_window_cleanup_idx` 按 `window_start` 支持后台清理。

### gateway.tool_execution

调用身份、状态、幂等与下游关联

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK / UQ | 可信租户标识，用于隔离业务记录 |
| `id` | `uuid` | 否 | — | PK / UQ | 本表实体标识，通常与租户组成主键 |
| `tool_id` | `uuid` | 否 | — | FK / UQ | 工具稳定标识 |
| `version_id` | `uuid` | 否 | — | FK / UQ | 所绑定的工具版本主键 |
| `endpoint_id` | `uuid` | 是 | — | FK | 工具版本的实际执行端点标识 |
| `run_id` | `uuid` | 是 | — | FK / UQ | 所属 Run 标识 |
| `step_id` | `uuid` | 是 | — | FK / UQ | 所属的运行节点标识 |
| `agent_id` | `text` | 否 | — | UQ | 调用链上的 Agent 标识 |
| `principal_id` | `text` | 否 | — | UQ | 已认证调用主体标识 |
| `trace_id` | `text` | 否 | — | — | 观测系统中的请求链路标识 |
| `idempotency_key` | `text` | 否 | — | UQ | 逻辑工具操作的幂等键 |
| `operation_hash` | `text` | 否 | — | UQ | 调用版本、身份及参数的绑定摘要 |
| `arguments_ref` | `text` | 否 | — | — | 实际调用参数的受控内容引用 |
| `status` | `text` | 否 | — | — | 本表对象在其生命周期中的当前状态 |
| `version` | `bigint` | 否 | `0` | — | 表内实体版本或状态修订号 |
| `next_at` | `timestamp with time zone` | 否 | `now()` | — | 下次可领取、重试或复核的时间 |
| `external_job_id` | `text` | 是 | — | — | 下游异步任务标识 |
| `downstream_request_id` | `text` | 是 | — | — | 下游系统用于回查的请求标识 |
| `result_ref` | `text` | 是 | — | — | 已授权输出的受控结果引用 |
| `error_code` | `text` | 是 | — | — | 执行或尝试的结构化错误码 |
| `lease_epoch` | `bigint` | 否 | `0` | — | 拒绝旧领取者提交的递增租约代次 |
| `lease_owner` | `text` | 是 | — | — | 当前任务领取者 |
| `lease_until` | `timestamp with time zone` | 是 | — | — | 当前领取有效期 |
| `cancel_requested_at` | `timestamp with time zone` | 是 | — | — | 收到取消请求的时间 |
| `created_at` | `timestamp with time zone` | 否 | `now()` | — | 记录创建时间 |
| `finished_at` | `timestamp with time zone` | 是 | — | — | 当前运行、节点尝试或迭代终结的时间 |

**约束**

- 主键 `tool_execution_pkey` `PRIMARY KEY (tenant_id, id)`
- 外键 `tool_execution_tenant_id_run_id_step_id_fkey` `FOREIGN KEY (tenant_id, run_id, step_id) REFERENCES runtime.step(tenant_id, run_id, id)`
- 外键 `tool_execution_tenant_id_tool_id_version_id_fkey` `FOREIGN KEY (tenant_id, tool_id, version_id) REFERENCES gateway.tool_version(tenant_id, tool_id, id)`
- 外键 `tool_execution_tenant_id_version_id_endpoint_id_fkey` `FOREIGN KEY (tenant_id, version_id, endpoint_id) REFERENCES gateway.tool_endpoint(tenant_id, version_id, id)`
- 唯一 `tool_execution_tenant_id_agent_id_principal_id_tool_id_idem_key` `UNIQUE (tenant_id, agent_id, principal_id, tool_id, idempotency_key)`
- 唯一 `tool_execution_tenant_id_id_run_id_step_id_key` `UNIQUE (tenant_id, id, run_id, step_id)`
- 唯一 `tool_execution_tenant_id_id_version_id_operation_hash_princ_key` `UNIQUE (tenant_id, id, version_id, operation_hash, principal_id)`
- 检查 `tool_execution_check` `CHECK ((run_id IS NULL) = (step_id IS NULL))`
- 检查 `tool_execution_check1` `CHECK (external_job_id IS NULL OR endpoint_id IS NOT NULL)`
- 检查 `tool_execution_check2` `CHECK ((lease_owner IS NULL) = (lease_until IS NULL))`
- 检查 `tool_execution_check3` `CHECK ((status = ANY (ARRAY['SUCCEEDED'::text, 'FAILED'::text, 'REJECTED'::text, 'EXPIRED'::text, 'BLOCKED'::text, 'CANCELLED'::text])) = (finished_at IS NOT NULL))`
- 检查 `tool_execution_lease_epoch_check` `CHECK (lease_epoch >= 0)`
- 检查 `tool_execution_status_check` `CHECK (status = ANY (ARRAY['QUEUED'::text, 'RUNNING'::text, 'WAITING_APPROVAL'::text, 'WAITING_EXTERNAL'::text, 'RETRY_WAIT'::text, 'CANCEL_REQUESTED'::text, 'SUCCEEDED'::text, 'FAILED'::text, 'REJECTED'::text, 'EXPIRED'::text, 'BLOCKED'::text, 'CANCELLED'::text, 'UNKNOWN'::text]))`
- 检查 `tool_execution_version_check` `CHECK (version >= 0)`

**独立索引**

- `CREATE INDEX execution_due_idx ON gateway.tool_execution USING btree (next_at, lease_until) WHERE (status = ANY (ARRAY['QUEUED'::text, 'RUNNING'::text, 'RETRY_WAIT'::text, 'WAITING_EXTERNAL'::text, 'CANCEL_REQUESTED'::text, 'UNKNOWN'::text]))`
- `CREATE UNIQUE INDEX execution_external_idx ON gateway.tool_execution USING btree (tenant_id, endpoint_id, external_job_id) WHERE (external_job_id IS NOT NULL)`
- `CREATE UNIQUE INDEX execution_one_per_step_idx ON gateway.tool_execution USING btree (tenant_id, step_id) WHERE (step_id IS NOT NULL)`

### gateway.tool_approval

具体工具执行的审批绑定与裁决

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK | 可信租户标识，用于隔离业务记录 |
| `id` | `uuid` | 否 | — | PK | 本表实体标识，通常与租户组成主键 |
| `execution_id` | `uuid` | 否 | — | FK | 网关工具执行标识 |
| `version_id` | `uuid` | 否 | — | FK | 所绑定的工具版本主键 |
| `operation_hash` | `text` | 否 | — | FK | 调用版本、身份及参数的绑定摘要 |
| `requester_principal_id` | `text` | 否 | — | FK | 等待审批的原工具调用主体 |
| `approver_selector` | `jsonb` | 否 | — | — | 从组织数据定位审批人的选择条件 |
| `policy_version` | `text` | 否 | — | — | 准入或审批策略版本 |
| `status` | `text` | 否 | — | — | 本表对象在其生命周期中的当前状态 |
| `expires_at` | `timestamp with time zone` | 否 | — | — | 可使用或裁决的截止时间 |
| `decided_by` | `text` | 是 | — | — | 实际作出审批裁决的主体 |
| `decided_at` | `timestamp with time zone` | 是 | — | — | 裁决时间 |
| `created_at` | `timestamp with time zone` | 否 | `now()` | — | 记录创建时间 |

**约束**

- 主键 `tool_approval_pkey` `PRIMARY KEY (tenant_id, id)`
- 外键 `tool_approval_tenant_id_execution_id_version_id_operation__fkey` `FOREIGN KEY (tenant_id, execution_id, version_id, operation_hash, requester_principal_id) REFERENCES gateway.tool_execution(tenant_id, id, version_id, operation_hash, principal_id)`
- 检查 `tool_approval_check` `CHECK ((status = 'PENDING'::text) = (decided_at IS NULL))`
- 检查 `tool_approval_check1` `CHECK ((status <> ALL (ARRAY['APPROVED'::text, 'REJECTED'::text])) OR decided_by IS NOT NULL)`
- 检查 `tool_approval_status_check` `CHECK (status = ANY (ARRAY['PENDING'::text, 'APPROVED'::text, 'REJECTED'::text, 'EXPIRED'::text, 'CANCELLED'::text]))`

**独立索引**

- `CREATE INDEX tool_approval_expiry_idx ON gateway.tool_approval USING btree (expires_at) WHERE (status = 'PENDING'::text)`
- `CREATE UNIQUE INDEX tool_approval_pending_idx ON gateway.tool_approval USING btree (tenant_id, execution_id) WHERE (status = 'PENDING'::text)`

### gateway.tool_job

审批等待与异步轮询使用的加密恢复数据。

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK | 执行所属租户 |
| `execution_id` | `uuid` | 否 | — | PK / FK | 关联的工具执行 ID |
| `encrypted_request` | `text` | 是 | — | — | AES-GCM 加密的参数与已验证角色快照，结算后清空 |
| `encrypted_result` | `text` | 是 | — | — | AES-GCM 加密的脱敏结果 |
| `deadline_at` | `timestamptz` | 否 | — | — | 最晚恢复或轮询时间 |

`(tenant_id, execution_id)` 关联 `gateway.tool_execution`。`tool_job_retention_idx` 按 `deadline_at` 支持后台清理。

### gateway.callback_receipt

下游回调的去重收据

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK | 可信租户标识，用于隔离业务记录 |
| `server_id` | `uuid` | 否 | — | PK / FK | 受管工具服务器标识 |
| `provider_event_id` | `text` | 否 | — | PK | 下游生成的回调事件标识 |
| `execution_id` | `uuid` | 否 | — | FK | 网关工具执行标识 |
| `payload_hash` | `text` | 否 | — | — | 下游回调内容摘要 |
| `received_at` | `timestamp with time zone` | 否 | `now()` | — | 回调接收时间 |

**约束**

- 主键 `callback_receipt_pkey` `PRIMARY KEY (tenant_id, server_id, provider_event_id)`
- 外键 `callback_receipt_tenant_id_execution_id_fkey` `FOREIGN KEY (tenant_id, execution_id) REFERENCES gateway.tool_execution(tenant_id, id)`
- 外键 `callback_receipt_tenant_id_server_id_fkey` `FOREIGN KEY (tenant_id, server_id) REFERENCES gateway.tool_server(tenant_id, id)`

### gateway.audit_entry

工具相关的操作与安全审计

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK | 可信租户标识，用于隔离业务记录 |
| `id` | `uuid` | 否 | — | PK | 本表实体标识，通常与租户组成主键 |
| `execution_id` | `uuid` | 是 | — | FK | 网关工具执行标识 |
| `tool_id` | `uuid` | 是 | — | FK | 工具稳定标识 |
| `actor_id` | `text` | 否 | — | — | 审计操作者标识 |
| `action` | `text` | 否 | — | — | 动作编码或授权动作 |
| `trace_id` | `text` | 否 | — | — | 观测系统中的请求链路标识 |
| `detail` | `jsonb` | 否 | `'{}'::jsonb` | — | 审计操作的结构化详情 |
| `occurred_at` | `timestamp with time zone` | 否 | `now()` | — | 事件或审计事实发生时间 |

**约束**

- 主键 `audit_entry_pkey` `PRIMARY KEY (tenant_id, id)`
- 外键 `audit_entry_tenant_id_execution_id_fkey` `FOREIGN KEY (tenant_id, execution_id) REFERENCES gateway.tool_execution(tenant_id, id)`
- 外键 `audit_entry_tenant_id_tool_id_fkey` `FOREIGN KEY (tenant_id, tool_id) REFERENCES gateway.tool(tenant_id, id)`

**独立索引**

- `CREATE INDEX audit_execution_idx ON gateway.audit_entry USING btree (tenant_id, execution_id, occurred_at)`

## 事件存储

### eventing.outbox

与业务写入同事务保存的待发布消息

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK | 可信租户标识，用于隔离业务记录 |
| `event_id` | `uuid` | 否 | — | PK | 领域事件的幂等标识 |
| `event_type` | `text` | 否 | — | — | 事件名称 |
| `schema_version` | `integer` | 否 | `1` | — | 事件或信封结构版本 |
| `aggregate_type` | `text` | 否 | — | — | 事件所属业务聚合类型 |
| `aggregate_id` | `uuid` | 否 | — | — | 事件所属业务聚合标识 |
| `aggregate_version` | `bigint` | 否 | — | — | 事件对应业务聚合的修订号 |
| `stream` | `text` | 否 | — | — | Redis Streams 投递目标 |
| `trace_id` | `text` | 否 | — | — | 观测系统中的请求链路标识 |
| `correlation_id` | `text` | 否 | — | — | 贯穿同一业务链路的关联标识 |
| `causation_id` | `uuid` | 是 | — | — | 引发本事件的上游事件标识 |
| `payload` | `jsonb` | 否 | `'{}'::jsonb` | — | 受控的事件载荷 |
| `occurred_at` | `timestamp with time zone` | 否 | `now()` | — | 事件或审计事实发生时间 |
| `next_at` | `timestamp with time zone` | 否 | `now()` | — | 下次可领取、重试或复核的时间 |
| `attempts` | `integer` | 否 | `0` | — | 已尝试领取或投递次数 |
| `lease_epoch` | `bigint` | 否 | `0` | — | 拒绝旧领取者提交的递增租约代次 |
| `lease_owner` | `text` | 是 | — | — | 当前任务领取者 |
| `lease_until` | `timestamp with time zone` | 是 | — | — | 当前领取有效期 |
| `published_at` | `timestamp with time zone` | 是 | — | — | 生效发布时间或消息确认投递时间 |
| `message_id` | `text` | 是 | — | — | 流消息标识 |
| `last_error` | `text` | 是 | — | — | 最近一次发布或领取错误摘要 |

**约束**

- 主键 `outbox_pkey` `PRIMARY KEY (tenant_id, event_id)`
- 检查 `outbox_aggregate_version_check` `CHECK (aggregate_version > 0)`
- 检查 `outbox_attempts_check` `CHECK (attempts >= 0)`
- 检查 `outbox_check` `CHECK ((lease_owner IS NULL) = (lease_until IS NULL))`
- 检查 `outbox_check1` `CHECK ((published_at IS NULL) = (message_id IS NULL))`
- 检查 `outbox_lease_epoch_check` `CHECK (lease_epoch >= 0)`
- 检查 `outbox_schema_version_check` `CHECK (schema_version > 0)`

**独立索引**

- `CREATE INDEX outbox_due_idx ON eventing.outbox USING btree (next_at, lease_until) WHERE (published_at IS NULL)`
- `CREATE INDEX outbox_replay_idx ON eventing.outbox USING btree (stream, occurred_at, event_id)`

### eventing.inbox

消费事务的去重凭据

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK | 可信租户标识，用于隔离业务记录 |
| `consumer_name` | `text` | 否 | — | PK | 消费处理器标识 |
| `event_id` | `uuid` | 否 | — | PK | 领域事件的幂等标识 |
| `processed_at` | `timestamp with time zone` | 否 | `now()` | — | 消费事务已完成的时间 |

**约束**

- 主键 `inbox_pkey` `PRIMARY KEY (tenant_id, consumer_name, event_id)`

### eventing.delivery_failure

不可处理的消息及重放依据

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK | 可信租户标识，用于隔离业务记录 |
| `consumer_name` | `text` | 否 | — | PK | 消费处理器标识 |
| `event_id` | `uuid` | 否 | — | PK | 领域事件的幂等标识 |
| `source_stream` | `text` | 否 | — | — | 消费失败对应的原流 |
| `source_message_id` | `text` | 否 | — | — | 消费失败的原始流消息标识 |
| `envelope` | `jsonb` | 否 | — | — | 待排查消息的原始事件信封 |
| `failure_code` | `text` | 否 | — | — | 消费失败的结构化原因 |
| `failure_count` | `integer` | 否 | — | — | 消费失败次数 |
| `updated_at` | `timestamp with time zone` | 否 | `now()` | — | 最近一次业务更新的时间 |
| `resolved_at` | `timestamp with time zone` | 是 | — | — | 消费失败记录解决时间 |

**约束**

- 主键 `delivery_failure_pkey` `PRIMARY KEY (tenant_id, consumer_name, event_id)`
- 检查 `delivery_failure_failure_count_check` `CHECK (failure_count > 0)`

## 对话与长期记忆

### memory.conversation_summary

对话摘要的历史代次、覆盖水位和内容位置

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK / UQ | 可信租户标识，用于隔离业务记录 |
| `conversation_id` | `uuid` | 否 | — | PK / FK / UQ | 所属会话标识 |
| `version` | `bigint` | 否 | — | PK | 表内实体版本或状态修订号 |
| `history_revision` | `bigint` | 否 | — | UQ | 原文发生变化时递增的会话代次 |
| `covered_through_seq` | `bigint` | 否 | — | UQ | 对话摘要覆盖的最终消息序号 |
| `source_digest` | `text` | 否 | — | — | 生成摘要的源记录摘要 |
| `summarizer_version` | `text` | 否 | — | — | 摘要生成器或提示版本 |
| `summary_ref` | `text` | 否 | — | — | 滚动摘要正文的受控引用 |
| `created_at` | `timestamp with time zone` | 否 | `now()` | — | 记录创建时间 |

**约束**

- 主键 `conversation_summary_pkey` `PRIMARY KEY (tenant_id, conversation_id, version)`
- 外键 `conversation_summary_tenant_id_conversation_id_fkey` `FOREIGN KEY (tenant_id, conversation_id) REFERENCES intent.conversation(tenant_id, id)`
- 唯一 `conversation_summary_tenant_id_conversation_id_history_revi_key` `UNIQUE (tenant_id, conversation_id, history_revision, covered_through_seq)`
- 检查 `conversation_summary_covered_through_seq_check` `CHECK (covered_through_seq > 0)`
- 检查 `conversation_summary_history_revision_check` `CHECK (history_revision >= 0)`
- 检查 `conversation_summary_version_check` `CHECK (version > 0)`

### memory.profile_item

带来源和版本的用户档案字段

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK | 可信租户标识，用于隔离业务记录 |
| `user_id` | `text` | 否 | — | PK | 当前记录的业务用户标识 |
| `item_key` | `text` | 否 | — | PK | 用户档案字段键 |
| `value` | `jsonb` | 否 | — | — | 按档案字段约束存放的 JSON 值 |
| `source_type` | `text` | 否 | — | — | 档案事实的来源类型 |
| `source_ref` | `text` | 是 | — | — | 源文件、档案来源或源文档的引用 |
| `revision` | `bigint` | 否 | — | — | 实体修订号 |
| `updated_at` | `timestamp with time zone` | 否 | `now()` | — | 最近一次业务更新的时间 |
| `expires_at` | `timestamp with time zone` | 是 | — | — | 可使用或裁决的截止时间 |

**约束**

- 主键 `profile_item_pkey` `PRIMARY KEY (tenant_id, user_id, item_key)`
- 检查 `profile_item_revision_check` `CHECK (revision > 0)`
- 检查 `profile_item_source_type_check` `CHECK (source_type = ANY (ARRAY['USER_CONFIRMED'::text, 'BUSINESS_SYSTEM'::text, 'ADMIN'::text]))`

### memory.task_episode

审核后的可复用任务经验

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / UQ | 可信租户标识，用于隔离业务记录 |
| `id` | `uuid` | 否 | — | PK | 本表实体标识，通常与租户组成主键 |
| `source_run_id` | `uuid` | 否 | — | UQ | 生成脱敏案例的原 Run 标识，应用在提炼时校验 |
| `owner_user_id` | `text` | 否 | — | — | 会话或记忆的所属用户 |
| `scope` | `text` | 否 | — | — | 长期记忆的可见范围 |
| `scenario_key` | `text` | 否 | — | — | 任务案例的场景分类键 |
| `status` | `text` | 否 | — | — | 本表对象在其生命周期中的当前状态 |
| `revision` | `bigint` | 否 | — | — | 实体修订号 |
| `episode_ref` | `text` | 是 | — | — | 脱敏后的任务案例正文位置 |
| `created_at` | `timestamp with time zone` | 否 | `now()` | — | 记录创建时间 |
| `expires_at` | `timestamp with time zone` | 是 | — | — | 可使用或裁决的截止时间 |

**约束**

- 主键 `task_episode_pkey` `PRIMARY KEY (tenant_id, id)`
- 唯一 `task_episode_tenant_id_source_run_id_key` `UNIQUE (tenant_id, source_run_id)`
- 检查 `task_episode_check` `CHECK (status <> 'ACTIVE'::text OR episode_ref IS NOT NULL)`
- 检查 `task_episode_revision_check` `CHECK (revision > 0)`
- 检查 `task_episode_scope_check` `CHECK (scope = ANY (ARRAY['USER'::text, 'TENANT'::text]))`
- 检查 `task_episode_status_check` `CHECK (status = ANY (ARRAY['DRAFT'::text, 'ACTIVE'::text, 'REVOKED'::text]))`

**独立索引**

- `CREATE INDEX episode_scope_idx ON memory.task_episode USING btree (tenant_id, scope, owner_user_id, scenario_key) WHERE (status = 'ACTIVE'::text)`

### memory.knowledge_document

知识条目身份及访问策略

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / UQ | 可信租户标识，用于隔离业务记录 |
| `id` | `uuid` | 否 | — | PK | 本表实体标识，通常与租户组成主键 |
| `collection_key` | `text` | 否 | — | UQ | 知识集合键 |
| `source_key` | `text` | 否 | — | UQ | 知识来源的外部稳定键 |
| `access_policy_ref` | `text` | 否 | — | — | 知识集合访问策略引用 |

**约束**

- 主键 `knowledge_document_pkey` `PRIMARY KEY (tenant_id, id)`
- 唯一 `knowledge_document_tenant_id_collection_key_source_key_key` `UNIQUE (tenant_id, collection_key, source_key)`

### memory.knowledge_version

源文档有效版本和内容摘要

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK | 可信租户标识，用于隔离业务记录 |
| `document_id` | `uuid` | 否 | — | PK / FK | 知识文档稳定标识 |
| `version` | `integer` | 否 | — | PK | 表内实体版本或状态修订号 |
| `status` | `text` | 否 | — | — | 本表对象在其生命周期中的当前状态 |
| `source_ref` | `text` | 否 | — | — | 源文件、档案来源或源文档的引用 |
| `content_digest` | `text` | 否 | — | — | 原始内容完整性摘要 |
| `created_at` | `timestamp with time zone` | 否 | `now()` | — | 记录创建时间 |
| `published_at` | `timestamp with time zone` | 是 | — | — | 生效发布时间或消息确认投递时间 |

**约束**

- 主键 `knowledge_version_pkey` `PRIMARY KEY (tenant_id, document_id, version)`
- 外键 `knowledge_version_tenant_id_document_id_fkey` `FOREIGN KEY (tenant_id, document_id) REFERENCES memory.knowledge_document(tenant_id, id)`
- 检查 `knowledge_version_check` `CHECK (status <> 'ACTIVE'::text OR published_at IS NOT NULL)`
- 检查 `knowledge_version_status_check` `CHECK (status = ANY (ARRAY['DRAFT'::text, 'ACTIVE'::text, 'RETIRED'::text, 'REVOKED'::text]))`
- 检查 `knowledge_version_version_check` `CHECK (version > 0)`

**独立索引**

- `CREATE UNIQUE INDEX knowledge_active_version_idx ON memory.knowledge_version USING btree (tenant_id, document_id) WHERE (status = 'ACTIVE'::text)`

### memory.knowledge_chunk

版本内的分块和引用

| 字段 | 类型 | 可空 | 默认或生成规则 | 键 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tenant_id` | `text` | 否 | — | PK / FK | 可信租户标识，用于隔离业务记录 |
| `document_id` | `uuid` | 否 | — | PK / FK | 知识文档稳定标识 |
| `document_version` | `integer` | 否 | — | PK / FK | 指定的知识文档版本 |
| `ordinal` | `integer` | 否 | — | PK | 同一轮的候选或同一版本的分块序号 |
| `chunk_ref` | `text` | 否 | — | — | 知识分块正文引用 |
| `content_digest` | `text` | 否 | — | — | 原始内容完整性摘要 |

**约束**

- 主键 `knowledge_chunk_pkey` `PRIMARY KEY (tenant_id, document_id, document_version, ordinal)`
- 外键 `knowledge_chunk_tenant_id_document_id_document_version_fkey` `FOREIGN KEY (tenant_id, document_id, document_version) REFERENCES memory.knowledge_version(tenant_id, document_id, version)`
- 检查 `knowledge_chunk_ordinal_check` `CHECK (ordinal >= 0)`
