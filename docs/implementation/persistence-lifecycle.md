# 会话、任务与运行的数据生命周期

这份说明按当前代码走一遍。每个阶段列出写入时机、读取时机、使用者和主要字段。DDL、索引与字段约束以 [数据库建表 SQL](../../database/schema.sql) 和 [数据库字典](../../database/dictionary.md) 为准。

## 先认清一条数据链

```text
Conversation
  ├─ 当前 conversation_state
  └─ Turn
       ├─ 用户 Message、助手 Message → conversation_body
       └─ Candidate → ContextRelation
            ├─ 无业务 Task：澄清、历史回顾、闲聊、知识问答
            └─ Intent Record → Intent Definition
                 └─ Task → Task Input v1、v2、…
                      ├─ Clarification、Confirmation、Route Decision
                      └─ 按输入版本派发 → Tool Execution 或 Run
                           └─ Plan → Step → Attempt
                                └─ Checkpoint → Artifact → PostgreSQL 或对象存储正文
```

一个会话有多轮。一轮可以识别多个候选，补参也可以让两个不同的轮次沿用同一个 Task。一个 Task 的输入可以先后形成多个版本。提交运行时，Run 绑定一个确定的 `task_id + input_version`。由会话触发的工具执行、Run 或拒绝原因存于 `intent.turn_execution`。同一个 Run 内重规划会增加 Plan Version，节点重试只增加 Attempt。[意图与派发](intent-engine.md) · [运行时](agent-runtime.md)

## 一、进入会话，到意图和 Task 落库

| 时机 | 写入和结构 | 何时取出、给谁用 |
| --- | --- | --- |
| 创建会话 | 同一事务插入 `intent.conversation` 与 `intent.conversation_state`。前者保存 `owner_user_id`、`last_message_seq`、`history_revision`。后者保存 `agent_id`、`revision` 和初始 `snapshot JSONB`。 | 每轮提交、历史读取、右栏调试前按可信租户、会话、用户和 Agent 查询归属。 |
| 接收一轮请求 | 服务端构造 `RawRequest`、`NormalizedInput`。此时读取会话快照，按作用域和 `client_key` 检查既有回执与 `request_hash`。 | `IntentEngine` 在提交事务外归一化、识别候选。必要时 Context Assembler 按需读取有限的消息和任务证据，Context Resolver 对每个候选定位目标。 |
| 锁会话并提交轮次 | `intent.turn` 写入 `conversation_id`、`client_key`、`request_hash`、`receipt JSONB`、`trace_input JSONB`。普通识别 API 直接提交已完成的识别轮次。业务执行可能还在排队或等待补参、审批。 | 用同一幂等键重试时返回原轮次。历史列表读回执，产物接口读 `trace_input`，并按权限补充当前执行进度。 |
| 保存本轮消息 | `intent.message` 记录 `turn_id`、`seq`、`role`、`position`、`reply_to_id`、`body_ref`。实际正文在 `intent.conversation_body.body`，消息通过 `pg:<正文ID>` 引用。会话的 `last_message_seq` 随提交推进。 | 历史列表、会话回顾和聊天窗口按 `seq` 读取。显式回复按 `reply_to_id` 找历史轮次及其 Task。异步聊天的最终回答从路由作业读回。 |
| 保存识别结果 | 每个候选写 `intent.candidate`，关键字段是 `turn_id`、`ordinal`、`draft`、`relation`、`target_task_id`、`target_clarification_id`、`evidence`、`outcome`。另写 `intent.routing_assessment` 记录复杂度、匹配分和策略。 | 右栏区分最初的分类和上下文关联。运营审核从 `MODEL_OUT_OF_SCOPE` 的候选定位同轮可见的原文。 |
| 首次形成业务 Task | `intent.definition` 保存版本化参数契约，`intent.intent_record` 保存业务意图实例及 `origin_turn_id`、`definition_id`。随后写 `intent.task`，记录 `intent_id`、`status`、`input_version`。 | Context Resolver 用可用的历史任务定位补参或修改目标。第一个缺少必要参数的业务 Task 可以先入库并等待输入。纯闲聊、动作不明确的追问可以只有 Turn 和 Candidate。 |
| 新建或更新任务参数 | `intent.task_input` 按 `task_id + version` 追加，包含 `slots`、`slot_provenance`、`slot_state`、`validated_ir`、`source_candidate_id` 和可选 `workflow_skill_version_id`。`intent.task.input_version` 指向当前版本。 | 当前会话续接使用最新任务状态。历史回放按旧版本读。执行准入和运行接口使用已锁定版本的 IR 或固定工作流输入。 |
| 等待参数或人工确认 | 业务槽位缺失时写 `intent.clarification`，带 `task_id`、`input_version`、`question`、`status`、`expires_at`。高危确认写 `intent.confirmation`，绑定任务、版本、请求主体、操作摘要和有效期。 | 下轮读取当前输入版本上仍开放的问题。补齐、覆盖或取消时关闭旧问题。确认时复核操作摘要和参数版本。 |
| 确定执行路径 | `intent.route_decision` 保存绑定输入版本的准入结果、策略版本和执行模式。`intent.turn_execution` 关联候选序号与工具执行、Run 或拒绝原因。`intent.route_job` 保存依赖派发、聊天、知识问答的后台状态、输入规格、租约和可用输出。 | 网关、Run Manager 或会话 Worker 领取各自的任务。查询轮次回执时按权限汇集执行视图。 |

轮次提交时锁会话、复查幂等和修订号，并核对已关联任务的预期输入版本。会话末态在同一事务覆盖到 `conversation_state.snapshot`。例如本轮从修订号 10 开始，成功提交后的工作状态是 11，而本轮 `trace_input.snapshotRevision` 仍记 10。[会话写入事务](../../modules/infrastructure/src/main/java/com/example/enterpriseagent/infrastructure/intent/PostgresIntentStore.java) · [右栏产物读取](../../modules/infrastructure/src/main/java/com/example/enterpriseagent/infrastructure/intent/PostgresIntentArtifacts.java)

## 二、常见的 snapshot、state、version 分别是什么

| 名称 | 是否落库及结构 | 何时更新和恢复 |
| --- | --- | --- |
| 当前会话状态 `IntentModel.State` | PostgreSQL 的 `intent.conversation_state.snapshot`。结构含 `revision`、`tasks[]`、`focus`、`entities`、`recent[]`、`summary`、`privacyRevision`。 | 每轮提交时覆盖。下一轮开始时读取。窗口保留近期意图标签，槽位与任务状态用于多轮衔接。 |
| 本轮 `ContextSnapshot` | 运行期只读对象。含会话 ID、捕获时间、基本状态和按需加载的上下文证据。 | 本轮开始时冻结基础状态，按需补充现有任务的意图 ID、版本、开放澄清和可见 Run。旧轮次的完整上下文不会在下一轮反序列化重用。 |
| 识别观察 `InputObservation` | PostgreSQL 的 `intent.turn.trace_input`。含归一化文本、`snapshotRevision`、`capturedAt`、任务与消息窗口计数、可用的档案键和案例引用。 | 成功提交一轮时写入。旧轮次调试读取该记录。清空上下文时旧记录不再对接口可见。 |
| 任务输入版本 `Task Input` | PostgreSQL 的 `intent.task_input`，包含业务参数、来源、缺失/非法字段以及可选的 `ValidatedIntentIR`。 | 每次有效提交该任务输入时追加版本。Run 恢复依赖已绑定版本。 |
| 运行当前状态 `Run / Step State` | PostgreSQL 的 `runtime.run`、`runtime.step`。包括状态、活动计划版本、待派发时间、租约与结果引用。 | Worker 领取、等待审批、重试、重规划、取消、完成时更新。Worker 和查询 API 用它判断当前做到哪一步。 |
| 运行检查点 `WorkingMemory.Snapshot` | PostgreSQL 的 `runtime.checkpoint` 和 `runtime.checkpoint_artifact`。主记录包含 `run_id`、`seq`、`plan_version`、`graph_thread_key`、`format_version`、`graph_state`，绑定表把 `binding_key` 指向 `artifact_id`。 | 运行受理、计划发布、节点结算等检查点边界追加。Worker 按 Run、活动计划、线程和格式恢复最新兼容快照。 |
| 模型运行上下文 | 调用前在内存组合。聊天用正文窗口与摘要，Planner/Ralph 在需要时用经批准的输入、缺失项、预算及产物引用。 | 每次模型调用重新组装。持久化结构化输入、必要的规划提案和验收证据。 |

对应关系的例子如下。会话修订号记录识别层的并发进度，任务输入版本记录业务参数变化，Run 检查点序号记录同一个 Run 的恢复位置。三者独立递增。

```json
{
  "turnTrace": {
    "snapshotRevision": 10,
    "taskCount": 4,
    "recentTurnCount": 8
  },
  "taskInput": {
    "version": 2,
    "slots": { "orderId": "SO001" },
    "slotProvenance": { "orderId": "CURRENT_TURN" }
  },
  "runCheckpoint": {
    "seq": 12,
    "planVersion": 1,
    "graphState": { "planVersion": 1, "eventSeq": 12 },
    "bindings": [
      { "bindingKey": "source", "artifactId": "<已提交产物ID>" }
    ]
  }
}
```

上面的 JSON 是便于对照的摘录。三个对象写到不同表中。[运行检查点写入与恢复](../../modules/agent-runtime/src/main/java/com/example/enterpriseagent/runtime/RunManager.java) · [Checkpoint Saver](../../modules/infrastructure/src/main/java/com/example/enterpriseagent/infrastructure/persistence/PostgresCheckpointSaver.java)

## 三、从 Run 受理到完成

| 时间点 | PostgreSQL 表与核心字段 | 下一次在哪读取 |
| --- | --- | --- |
| 受理并排队 | `runtime.run` 保存 `task_id`、`input_version`、`route_decision_id`、`status`、`active_plan_version`、截止时间和预算。 `runtime.run_submission` 保存作用域内的 `request_key`、`request_hash`、`run_id`、角色快照。 | 接口幂等重放和 Worker 领取。固定 SOP 固定 Skill 版本，开放任务预留规划路径。 |
| 规划与发布 | `runtime.plan_proposal` 保存提案及其基于的版本。通过校验后新增 `runtime.plan`，写 `runtime.step` 和 `runtime.step_dependency`，更新活动版本。 | 调度只处理活动计划中的节点。依赖未完成的节点等待。固定 SOP 直接使用已发布的 DAG。 |
| 领取与重试 | `runtime.step` 写 `status`、`current_attempt`、`next_at` 及租约。`runtime.step_attempt` 记录 `attempt_no`、`lease_epoch`、Worker、尝试状态和错误码。 | Worker 查到期节点及租约，租约失效可以接管。旧执行者的结算要先验证租约。 |
| 提交工具与等待审批 | `gateway.tool_execution` 固定工具版本、操作者、幂等键、状态、结果引用。 `gateway.tool_job` 保存加密的请求或脱敏结果。`gateway.tool_approval` 保存审批状态、绑定操作摘要与有效期。 | 网关复核授权并执行。Runtime 用工具执行 ID 回查，等待审批或外部回调时释放 Worker。 `gateway.callback_receipt` 和 `gateway.async_callback` 为相应回调链路提供持久记录。 |
| 节点结算 | 状态更新连同 `runtime.context_artifact` 元数据、`runtime.artifact_body` 正文、相应 Checkpoint、Run Event 和 Outbox 按事务提交。 | 后续节点从 Checkpoint 恢复产物 ID，按 `content_ref` 读取所需正文。 |
| Hard Gate 与 Ralph | `runtime.iteration` 保存迭代、计划版本、上下文清单与验收状态。`runtime.gate_check` 保存逐项检查、证据引用和结论。需要重规划时追加提案和新版 Plan。 | 下一轮构造 Fresh Context 时读取已提交的证据、失败项和预算。达到边界时持久化最终结果。 |
| 最终结算 | `runtime.run` 更新最终状态与 `result_ref`，相应 Task 更新业务状态。启用记忆闭环时，符合条件的成功 Run 可写入案例和索引作业。 | 会话回执跟随 `runId` 查看状态。结果接口先授权，再读报告正文。 |

同类型节点可以按 DAG 并行调度。子任务和监控属于运行时附加链路，分别通过 `runtime.child_run` 记录父子关系，或通过 `runtime.monitor`、`runtime.monitor_tick`、`runtime.monitor_delivery` 保留监控定义、检查轮次和通知/处置投递。取消及对账操作通过 `runtime.run_command` 管理命令幂等，对账确认记录在 `runtime.reconciliation_confirmation`。[Run Manager](../../modules/agent-runtime/src/main/java/com/example/enterpriseagent/runtime/RunManager.java) · [Run SQL](../../modules/infrastructure/src/main/java/com/example/enterpriseagent/infrastructure/runtime/RunMapper.java) · [运维说明](operations.md)

### 产物卸载和恢复

节点结算时先写 PostgreSQL。启用对象存储卸载的 Worker 领取符合条件的产物，将正文上传到私有 S3 兼容 Bucket（可用 MinIO）。上传成功后在同一数据库事务校验租约、把 `content_ref` 从 `pg:` 改为 `obj:`、保存摘要和大小并移除内联正文。检查点中的 `artifact_id` 保持稳定。恢复时先从 PostgreSQL 查元数据和绑定，再按引用读取 PostgreSQL 或对象存储。外部对象在读取时核验摘要与大小。[对象存储与断点恢复](runtime-artifact-storage.md)

### EventBus 和 SSE

1. 运行、工具或 SOP 的业务事务同时写状态和 `eventing.outbox`。字段包括 `event_id`、聚合 ID/版本、`payload`、`next_at` 和租约。
2. Relay 读已提交 Outbox 并写 Redis Streams。消费者收到通知后回查权威行，用 `eventing.inbox` 按消费者和事件 ID 去重，事务提交后 ACK。失败使用 `eventing.delivery_failure`，人工重投或对账操作进入 `eventing.operator_action`。
3. Run 事件另写 `runtime.run_event`，按 `run_id + seq` 有序追加。SSE 客户端以 `Last-Event-ID` 重连时从这张表补发。Redis 短暂不可用期间，后台 PostgreSQL 轮询仍提供恢复路径。[EventBus 与保留策略](event-bus.md)

## 四、记忆何时写，何时读

Store 提供持久化或缓存的业务记忆读写。Saver 负责按 Run 追加和恢复检查点。同一个 Request 中构造的快照和模型上下文是两者读出的数据投影。

| 记忆类型 | 写入和更新 | 读取节点与结构 |
| --- | --- | --- |
| Session Memory，短期缓存 | `SessionMemory.write()` 调 `RedisSessionStore.save()`，存 JSON 和 TTL。键按租户、用户、Session ID 编码。 | 模块调用 `SessionMemory.read()` 时用同键读取。当前会话识别主链从 PostgreSQL 读会话状态。Redis Session Store 是可调用的基础设施，过期即返回空。 |
| 会话工作状态，短期使用、持久保存 | 每轮提交覆盖 `intent.conversation_state.snapshot`，包含焦点、任务、槽位线索、近期意图标签和压缩摘要。 | 下一轮解析前读取，用于任务衔接。清空上下文时增加会话历史代次并清除焦点和待办视图。 |
| Conversation Memory，滑动窗口 + 摘要 | 消息按轮持久化。CHAT 提交时尝试推进摘要水位，使用 `memory.conversation_summary` 的 `history_revision`、`covered_through_seq`、`summary_ref` 追加摘要。正文复用 `intent.conversation_body`。 | 聊天 Worker 在请求模型前读取兼容摘要与最近窗口。通过历史代次、轮次位置、可见性和对话类型筛选。 |
| Working Memory，断点恢复 | `PostgresCheckpointSaver.append()` 在运行事务中追加 `runtime.checkpoint` 和绑定。 | 恢复时按 `tenant_id + run_id + graph_thread_key + format_version` 查活动计划的检查点，再按 `artifact_id` 取产物。 |
| Profile Memory，跨会话 | 用户确认的偏好通过档案 API 写 `memory.profile_item`。键是租户、用户、`item_key`，含 `value JSONB`、`revision`、来源和可选过期时间。 | 部分单候选请求完成识别后可按需加载。聊天模型收到允许的偏好值，右栏只显示字段键。 |
| Episodic Memory，跨会话经验 | 记忆闭环启用后，成功结算创建 `memory.task_episode` 及 `memory.index_job`。存源 Run、归属、`scenario_key`、可见范围、引用和有效期。自动案例有效期 90 天。 | 已就绪的业务场景按需召回。Milvus 返回候选引用，Store 回查 PostgreSQL 验证归属、状态及有效期。调试按权限显示案例 ID。 |
| Semantic Memory，版本化知识 | 管理员发布文档时写 `memory.knowledge_document`、`memory.knowledge_version`、`memory.knowledge_chunk`、`memory.knowledge_chunk_body`。正文与索引任务先在 PostgreSQL 持久化。 | 知识问答 Worker 在用户具备读取权限时向 Milvus 查询，按得分取候选，再从 PostgreSQL 查当前版本、访问策略及正文。 |

Milvus 的集合保存租户、记忆类型、来源键与向量。它用于召回，版本、权限和正文来自 PostgreSQL。执行事件用 Redis Streams 传递。已发布 Skill DAG 还可以在 Redis 按租户和版本 UUID 缓存，版本选择以 PostgreSQL 为准。[记忆模块与存取接口](memory.md) · [Milvus 索引实现](../../modules/infrastructure/src/main/java/com/example/enterpriseagent/infrastructure/milvus/MilvusMemoryIndex.java)

## 五、定义、缓存与运营记录

| 类别 | 写入时机和结构 | 读取时机 |
| --- | --- | --- |
| 意图定义与规则 | 支持发布的 SOP 在发布事务登记 `intent.definition` 契约和 `runtime.workflow_skill_version` 原文/DAG，再保存工具和触发器映射。 `intent.predefined_rule` 保存绑定意图和定义版本的规则草稿与发布版本。 | Intent Engine 读取代码目录与已接入的 SOP 定义。业务派发按配置和有效版本读取规则，再把当前版本固定在输入或运行记录中。 |
| 工具定义与权限 | 工具注册、发布或授权变更写 `gateway.tool`、`gateway.tool_version`、`gateway.tool_endpoint`、`gateway.tool_permission`、`gateway.tool_policy` 等表。端点登记凭证引用。 | 发现和派发时解析已发布工具与版本。实际调用前复核身份、权限和参数。网关审计写入 `gateway.audit_entry`，限额计数可参照 `gateway.tool_usage_window`。 |
| 知识盲区人工复核 | 模型确认 `out_of_scope` 且没有可信匹配时，记录这一候选的原因。 | 有单独审核权限的人员按租户读取候选，并在近 30 天范围内沿同轮消息引用读取可见原文。 |
| 清空上下文 | 清除会话工作视图，增加历史代次，关闭未决问题和确认，撤销旧消息的可见正文引用，并清理未结算聊天作业。 | 后续历史、旧产物和知识盲区查询都按可见性读取。跨会话档案、独立运行和持久化审计按各自生命周期管理。 |

## 六、两轮退款到审批恢复的例子

| 时机 | 当场保存 | 之后谁来读 |
| --- | --- | --- |
| 「帮我办理订单退款」 | Turn 1、两条消息、退款候选、退款 Intent、Task、输入 v1，以及待补参问题。 | Turn 2 的 Assembler 在会话快照里找到历史退款 Task，并读取 v1 上仍开放的问题。 |
| 「订单号是 SO001，商品有质量问题」 | Turn 2 记录 `CONTINUATION` 和旧澄清证据。Task 沿用原 ID，追加输入 v2 和 IR，准入后关联退款 Run。 | Worker 从 Run 绑定的 v2 和已发布 Skill 准备第一个节点。 |
| 退款进入审批 | 网关写审批记录和可恢复工具作业。Run/Step 状态更新，必要的进度事件追加到 PostgreSQL。 | 审批人查询审批，Worker 或事件消费者在审批完成后恢复关联的工具步骤。 |
| Run 完成 | 节点产物、报告引用、最后的 Run/Task 状态和事件落库。可按配置异步卸载产物并沉淀经验案例。 | 用户按 `runId` 查结果。下次类似请求可在具备权限和索引有命中时看到案例引用。 |

## 从数据库追踪一轮

在测试库替换参数后，可以查看同一轮的候选、任务版本和执行链。表中的 `context_relation[0]` 包含关联依据。没有 Run ID 时，可检查 `tool_execution_id`、`blocked_reason` 或该轮的 `intent.route_job`。

```sql
SELECT t.id AS turn_id,
       (t.trace_input ->> 'snapshotRevision')::bigint AS read_revision,
       c.ordinal,
       c.draft ->> 'classifierIntent' AS classified_intent,
       c.evidence -> 0 AS context_relation,
       i.task_id,
       i.version AS input_version,
       e.tool_execution_id,
       e.run_id,
       e.blocked_reason
FROM intent.turn t
JOIN intent.candidate c ON c.tenant_id = t.tenant_id AND c.turn_id = t.id
LEFT JOIN intent.task_input i ON i.tenant_id = c.tenant_id
    AND i.conversation_id = c.conversation_id AND i.source_candidate_id = c.id
LEFT JOIN intent.turn_execution e ON e.tenant_id = c.tenant_id
    AND e.conversation_id = c.conversation_id AND e.turn_id = c.turn_id
    AND e.candidate_ordinal = c.ordinal
WHERE t.tenant_id = :tenant AND t.id = :turn_id
ORDER BY c.ordinal;
```
