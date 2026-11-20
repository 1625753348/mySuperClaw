# 接口与数据库设计

## 接入与运行接口

Tenant ID、User ID、Agent ID 来自 Spring Security 或内部可信服务凭证。路径和请求体中的 ID 仅用于定位，服务端按租户、会话所有者、分配的审批角色或管理权限复核访问范围。

当前会话提交以 HTTP 200 返回候选、可选的 Validated Intent IR 与已绑定的执行引用。订单查询和销售分析在后台派发，客户端读取同一轮回执获取新进度。下表也保留设计中的扩展接口。[当前识别 API](../docs/implementation/intent-engine.md#调用)

| 方法与路径 | 契约 |
| --- | --- |
| `POST /v1/runs` | 当前销售分析结构化入口，接受固定 `WORKFLOW` 或动态 `AGENT` 和明确的维度、行数据或工具来源，鉴权后返回 202 |
| `POST /v1/conversations` | 创建当前主体的会话，返回会话 ID |
| `POST /v1/conversations/{conversationId}/turns` | `Idempotency-Key` 必填。输入 `text` 或结构化 `skillKey + arguments`，可选 `replyToMessageId` 或 `targetTaskId`。分类和短查询在入口时限内完成可返回结果，进行中返回 202 与 Turn 查询地址 |
| `GET /v1/conversations/{conversationId}/turns/{turnId}` | 查询本轮回执，包含按候选顺序的澄清、拒绝、直接结果和 Run 引用。进行中的 Turn 可先返回已提交候选结果 |
| `GET /v1/conversations/{conversationId}/turns/{turnId}/artifacts` | 按候选输出中间产物、阶段状态和已授权执行信息。候选编号与回执一致 |
| `GET /v1/runs/{runId}` | 返回 Run 状态、`eventSeq`、活跃计划版本、当前迭代、阻塞原因、终态原因和结果引用 |
| `GET /v1/runs/{runId}/events` | SSE，使用 `Last-Event-ID`，首次连接可用 `after`。事件字段 ID 为 Run 内序号，关闭连接不改变任务 |
| `GET /v1/runs/{runId}/result` | 返回授权结果和产物描述。对象存储下载 URL 按权限短期签发 |
| `POST /v1/runs/{runId}/cancel` | 幂等提交取消并返回当前状态。下游仍在结算时返回 202 |
| `POST /v1/runs/{runId}/reconcile` | 受限对账入口。提交可追溯的下游结果证据、目标 execution ID 和期望版本，审计操作者 |
| `POST /v1/tasks/{taskId}/confirmations/{id}/decisions` | 消费操作确认，验证主体、明确动作、参数摘要、版本及有效期。自然语言确认经 Resolver 委托同一服务 |

Turn 回执示例，用户在同一轮补齐退款参数并提出新查询。

```json
{
  "turnId": "TURN-2",
  "status": "COMPLETED",
  "outcomes": [
    {
      "candidateId": "C-1",
      "relation": "CONTINUATION",
      "taskId": "TASK-1",
      "runId": "RUN-1",
      "status": "WAITING_APPROVAL",
      "eventsUrl": "/v1/runs/RUN-1/events"
    },
    {
      "candidateId": "C-2",
      "relation": "NEW_TASK",
      "taskId": "TASK-2",
      "runId": "RUN-2",
      "status": "SUCCEEDED",
      "resultRef": "RESULT-2"
    }
  ]
}
```

示例 ID 为示意，接口使用 UUID。单个候选缺参返回问题 ID 与缺失槽位，候选拒绝返回错误码，其他候选照常提交。Turn 的 `COMPLETED` 表示本轮候选处理已结算，后台 Run 仍可继续。相同幂等键与相同请求指纹复用原对象，指纹冲突返回 409。

L0～L3 是 Intent Engine 的按需能力，L4 在 Agent Runtime 内按需生成计划。内部 `candidate.draft` 可记录本轮使用的识别来源和草案，`task_input.validated_ir` 保存通过定义、合并和校验的业务意图。已发布的动态计划另有 `runtime.plan_proposal` 来源；普通命令、聊天或简单查询回执不会因此变更契约。[L0～L4 调用路径](architecture.md#l0-到-l4-按需调用)

`complexityScore` 和 `matchConfidence` 是内部路由评估项，前者选择识别方式，后者描述候选匹配。候选记录中的关系置信度另用于 L3。四档阈值、风险覆盖和前次判定引用保留在评估记录，准入及执行模式由 `intent.route_decision` 固定。[四档识别策略](routing.md)

## 工具网关接口

| 方法与路径 | 契约 |
| --- | --- |
| `POST /v1/tools/search` | 请求为 `query` 和 `limit`。返回调用方可用的当前发布版本，含 `protocol`、`score`、`bm25Score`、`semanticScore` |
| `GET /v1/tools/{name}/schema?version={version}` | 返回已审核的参数和结果契约、有权可见的说明及版本 ID |
| `POST /v1/tools/executions` | 传工具名、版本、参数和 `Idempotency-Key`。内部 Runtime 可信绑定 `runId/stepId`。返回 `executionId,status,resultRef,error`，等待中返回 202 |
| `GET /v1/tools/executions/{executionId}` | 工具状态和安全结果。由 Gateway 再校验结果查看权限 |
| `POST /v1/tools/approvals/{approvalId}/decisions` | `APPROVE` 或 `REJECT`，校验审批人、审批期限、参数绑定和并发状态。相同决定重送返回原结果，冲突决定返回 409 |
| `POST /internal/v1/tool-servers/{serverId}/callbacks` | 下游鉴权、签名和重放校验。优先采用提交前生成的 execution 关联令牌。仅支持外部 Job ID 的端点，映射未就绪时通过回查收敛 |
| `POST /v1/admin/tools` 和 `POST /v1/admin/tools/{name}/versions` | 管理端注册定义、版本、Schema 及已配置的端点别名 |
| `POST /v1/admin/tools/{name}/publish`、`/disable` | 携带期望修订号发布或停用工具。将已发布旧版本设为当前版本可回退 |
| `PUT /v1/admin/tools/{name}/permissions` | 设置 USER、AGENT 或 ROLE 的发现和执行权限 |
| `GET /v1/admin/tools/mcp/{server}/catalog?cursor={cursor}` | 浏览受管 MCP 服务的目录与输入输出 Schema，供管理员选取并发布 |
| `POST /v1/admin/tools/reindex?after={name}&limit={limit}` | 按租户分页回填当前发布版本的工具向量索引 |
| `POST /v1/admin/workflow-skills/{skillKey}/versions` | 注册 Skill 草案，提交受控 SKILL.md 引用，编译并返回校验结果 |
| `POST /v1/admin/workflow-skills/{skillKey}/versions/{version}/publish` | 按当前发布版本前置条件审核发布，原子更新触发器及版本，记录审核人和时间 |

当前可运行的 API、协议接入与待建设的审批及回调契约见 [ToolGateway 实现](../docs/implementation/tool-gateway.md)。

所有执行请求由 Gateway 权威校验版本。重复调用的持久化去重范围是 `tenantId + agentId + principalId + toolId + idempotencyKey`。规范化请求摘要绑定版本、参数和操作身份，有 Runtime 上下文时也绑定 Run 与 Step。审批绑定具体 execution、调用主体、所用版本和操作摘要。错误码例如 `TOOL_PERMISSION_DENIED`、`TOOL_SCHEMA_INVALID`、`TOOL_IDEMPOTENCY_CONFLICT`、`RUN_NEEDS_RECONCILIATION`、`GATE_BUDGET_EXCEEDED`。

## 数据模型与所有权

| Schema 和对象 | 数据口径 |
| --- | --- |
| `intent.conversation / turn / message` | 会话所有者、本轮处理及幂等回执、消息与回复引用。消息使用单调会话序号，历史更正和脱敏用历史代次失效派生视图 |
| `intent.definition / intent_record / candidate` | 意图 Schema 及路由配置、已绑定意图、每轮候选和关系证据。现有任务固定原定义版本 |
| `intent.routing_assessment` | Turn 或候选级复杂度、匹配置信度、分档、实际识别策略、归因及可比较的前次记录。表约束绑定同租户、会话、比较键和实际策略 |
| `intent.task / task_input` | 业务任务、任务引用及不可变的输入版本。当前版本外键在事务结束检查 |
| `intent.clarification / confirmation / route_decision` | 槽位澄清和目标消歧记录、意图级确认、准入策略决策。一个任务最多一个打开的槽位澄清和一个待确认操作 |
| `runtime.run / plan / step / step_dependency` | Run 绑定准入决策及输入版本，计划版本内保存 DAG，同一 Task 只允许一个未结束 Run |
| `runtime.workflow_skill / workflow_skill_version / workflow_skill_trigger / workflow_skill_tool` | 企业 SOP 稳定标识、编译发布、L0 触发器和工具版本绑定 |
| `runtime.plan_proposal` | 规划请求、触发原因、输入与草案引用、校验和发布结果。每个 Run 最多一个开放提案，动态计划须绑定同一 Run 的提案 |
| `runtime.step_attempt / checkpoint` | 领取与恢复记录、图状态和节点输出引用 |
| `runtime.context_artifact / checkpoint_artifact` | 外置上下文的不可变版本、内容校验摘要，以及 Checkpoint 到同一 Run 产物的绑定 |
| `runtime.iteration / gate_check` | Ralph 每轮输入快照摘要和检查结果。需要 Ralph 的成功 Run 必须引用当前计划的已通过迭代 |
| `runtime.run_event / run_command` | 面向 SSE 的 Run 内有序事件，以及取消、对账操作的幂等回执 |
| `gateway.tool* / callback_receipt / audit_entry` | 注册、版本、路由、授权、策略、调用、审批、下游通知去重和审计 |
| `eventing.outbox / inbox / delivery_failure` | 持久化待发布事件、消费去重记录和失败重放依据 |
| `memory.conversation_summary` | 滚动摘要版本、覆盖水位、生成模型或提示版本、源记录摘要及内容引用 |
| `memory.profile_item / task_episode` | 扩展的长期用户事实和受权限控制的跨任务经验 |
| `memory.knowledge_document / knowledge_version / knowledge_chunk` | 扩展的知识来源、文档版本与检索片段，向量索引可按已发布数据重建 |

所有领域外键带 `tenant_id`。访问限制由应用层在查询、任务领取、授权和结果返回时执行。部署时 API 和 Worker 使用各自的数据库角色，限制 Schema 和操作权限。大文本、密钥、完整凭证和敏感原始数据按权限存入 Secret Manager 或加密存储，SQL 和事件保留引用与批准的结构化字段。Redis 用于 Streams 和短期缓存，限流可由 Redis 支撑，副作用去重依赖 PostgreSQL 和下游保证。

## 应用事务约束

| 事务边界 | 约束 |
| --- | --- |
| Turn 领取与候选处理 | 先保存待分类 Turn 和原始消息引用，持久化分类候选后按序处理。候选与任务版本、关联证据和本候选回执同事务提交。检查 Turn lease epoch 和 Task revision，避免恢复时重复落地 |
| 接纳 Run | 预先生成 ID，在同一事务保存 ALLOW 决策、引用该决策的 Run、初始计划与待执行工作、Task 状态、RunEvent 及需要发布的 Outbox。AGENT 初始工作是带队列字段的规划请求 |
| 接纳 Skill | TaskInput 固定 Skill 版本。创建 Run 时校验其发布记录、启停状态、意图定义和准入。已退役版本仅按策略用于既有绑定任务。使用同一版本的编译 DAG 持久化 Plan，分支定义约束可跳过节点和下游可读取产物 |
| 规划发布 | Planner 输出的候选先保存为提案。Run Manager 锁定 Run，核对基础计划、上下文和旧计划在途工作。事务内保存新计划与依赖、推进有效版本、将提案设为已发布并写准备就绪事件 |
| 推进 DAG | 只领取当前有效计划中依赖满足的节点。表约束保证同计划边和禁止自环，Plan Validator 校验多节点环、数据依赖和允许的工具引用 |
| 竞争与租约 | 候选发现可批量扫描，实际领取和结算按 Run → Step → ToolExecution → Approval 的顺序锁定所涉及的行。在事务内再次校验租约、状态和取消标记；模型、HTTP 调用和 Streams 读写使用独立短事务或在事务外执行 |
| 事件和 Checkpoint | 在 Run 行锁下分配连续 `run_event.seq` 并递增状态版本。Checkpoint 需要同一事务保存时使用该提交对应的事件序号，同一事务多个事件时选最后一个。SSE 状态与游标用一致读 |
| 对话窗口与摘要 | 在持有会话行锁时分配消息序号，和消息或本轮回执同事务提交。摘要 Worker 锁外生成，提交前验证历史代次、源范围和当前摘要水位，防止旧结果覆盖新版本 |
| 上下文卸载 | 大内容先上传到受控不可变对象。引用只在步骤结果、产物元数据和 Checkpoint 绑定提交成功后可见。恢复与清理遵循有效计划、产物校验和引用保留条件 |
| Hard Gate | 检查器汇集新鲜证据并核对有效计划和所有必需项，同事务记录检查结论和迭代状态。只有已通过的当前计划迭代能被成功 Run 引用，数据库外键负责这层绑定，检查规则由应用执行 |
| 授权与审计 | 冻结版本化配置、校验审批状态与摘要、审批恢复校验实时授权、回调校验 Provider 与执行绑定，以及有效状态迁移由应用负责。关键业务写入同时保存审计或 Outbox |

DDL 使用 PostgreSQL 标准 Schema 与内置类型，UUID 由应用生成。SQL 适用于空白评审库。业务代码阶段再按模块整理 Flyway 迁移与权限脚本，并根据真实查询计划和保留周期评估索引、分区及归档。

读写策略、Store 和 Saver 的选择，以及数据保留范围详见 [记忆系统与持久化设计](memory.md)。
