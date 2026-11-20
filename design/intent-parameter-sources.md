# 意图参数来源与补参设计

Intent Engine 先确定用户目标和候选意图，再为该候选选择参数契约。命中已发布的 Skill 时，Task 固定 Skill 版本并使用它声明的流程入参。没有命中 Skill 时，已登记的意图可以独立从 Definition Registry 取得参数 Schema。开放任务先确认业务目标和范围，等 Planner 选出工具后，再按实际工具版本的 Schema 绑定调用参数。

## 从哪里知道需要哪些参数

| 请求路径 | 契约权威 | 知道参数的时机 |
| --- | --- | --- |
| 已登记业务意图，例如 `ORDER.QUERY` | `IntentDefinition` 的版本化参数 Schema | 分类和上下文定位后、抽取槽位前。订单查询即使没有 Skill，也能确定需要 `orderId)。 |
| 已发布 Skill，例如 `github.project-retrospective` | 指定 Skill 版本的 `inputSchema`，发布时与关联意图定义核对 | 命中命令、Skill Key 或语义候选后。Task 记录已选版本，续轮补参沿用它。 |
| 开放任务，例如查找销售下降原因 | 入口按已有定义或通用目标契约确定目标、范围和交付物。具体节点参数由 Planner 选中的 ToolGateway 工具版本决定 | 入口准备 Validated Intent IR。每个节点在规划校验及派发前核对工具 Schema。 |
| 目标不明确或闲聊 | 目标不明确时先询问用户想做什么。闲聊使用聊天输入契约 | 目标明确后才加载业务 Schema。 |

`IntentDefinition` 定义完成业务目标所需的输入。Skill 定义固定流程的入参和节点如何取值。工具 Schema 定义某一次调用的参数。三种契约的字段名称可以不同，执行绑定负责转换。例如入口接受 `project`，后续通过可信映射得到 GitHub 工具要求的 `repository)。

## 值从哪里来

| 来源 | 示例 | 适用条件 |
| --- | --- | --- |
| `CURRENT_TURN` 本轮显式输入 | 用户输入 `SO001`，或表单提交 `orderId` | 自然语言提取值可定位到本轮证据。结构化字段同样接受校验。同轮提交的多个值互相矛盾时按业务规则消歧。 |
| `PRIOR_TASK` 同一任务的有效旧值 | 用户说「还是那张单」，回复锚点关联到上一轮的同一个订单 Task | 先由 Context Resolver 确认任务、归属和版本，继续同一任务时才复用。 |
| `SESSION_ENTITY` 当前会话里的实体引用 | 用户说「那份合同」 | 目标唯一、证据在有效窗口内，且当前业务字段允许使用会话实体。 |
| `VERIFIED_LOOKUP` 服务端查得的值 | 项目别名经过租户内的登记映射解析为 `owner/repo` | 映射唯一且调用方有权。歧义时追问，未获授权时返回对应状态。 |
| `SCHEMA_DEFAULT` 发布时声明的默认值 | 复盘 Skill 的 `days=30` | 该字段允许默认且用户没有明确提供，应用默认值后仍校验。 |
| `PRIOR_STEP_RESULT` 当前 Run 的前置产物 | 先取得 Issue 编号，再按编号读取评论 | 只给下游节点绑定，本节点必须声明依赖。引用限定在本 Run 和有效计划。 |
| `TRUSTED_CONTEXT` 认证或运行时上下文 | 租户、用户、Agent、受理时间窗、Run ID | 服务端注入，调用方输入和模型输出无法覆盖。工具凭证由网关按引用解析。 |

上述标识是建议的来源字典。现有 IR 已可记录本轮、同任务与会话实体来源，其他标识随对应能力接入。解析结果只记录业务参数、来源类型和必要的引用，追踪接口按权限展示。

## 合并与校验顺序

1. **定位任务与固定版本。** 根据当前候选、明确的 `targetTaskId`、回复锚点和有效的历史实体定位 Task。沿用任务时先加载该任务绑定的意图与 Skill 版本；新任务使用当前可用版本。
2. **按 Schema 提取。** 只接收当前契约声明的字段，先归一化再校验。模型可以提出候选值，系统保存它所依据的用户输入或引用。
3. **合并来源。** 对用户可编辑字段，以当前明确输入为准。字段没有在本轮出现时，可继承同一任务的有效值，再按字段规则尝试会话实体、已授权查找或声明的默认值。对服务端注入字段始终使用可信上下文。前置产物只供依赖它的节点。
4. **处理冲突和缺参。** 当前输入明确但格式错误时，把该字段标为 `INVALID` 并请用户更正，避免旧值或默认值掩盖错误。多个仓库候选或多张待补参订单难以定位时，先追问目标。仍缺必填值则记录 `NEEDS_CLARIFICATION`。等待前置节点的数据记为待依赖，先推进已就绪节点。
5. **输出和入队。** 校验通过后保存新的 Task 输入版本及 Validated Intent IR，交给 Decision。工作流和 Planner 的节点派发前，还要通过 ToolGateway 校验实际工具版本、参数、授权和审批。修订后的参数改变了已确认操作的语义时，重新核对确认或审批。

## 中间产物和持久化

| 产物 | 建议记录 | 关联位置 |
| --- | --- | --- |
| `ParameterSchema` | 意图或 Skill 版本、必填项、默认值与字段限制 | `intent.definition.parameter_schema`，或者已发布的 Skill 编译快照 |
| `FilledSlots` | 合并后的参数，以及每个字段的来源和必要证据引用 | `intent.task_input.slots`、`slot_provenance` |
| `SlotState` / Clarification | 哪些字段缺少、无效或有歧义，追问内容与关联 Task | `intent.task_input.slot_state`、`intent.clarification` |
| `ValidatedIntentIR` | 已校验业务参数、定义版本、Task 输入版本及响应要求 | `intent.task_input.validated_ir` |
| ToolCall 和前置产物 | 绑定后的工具版本、节点依赖、产物引用和执行结果 | `runtime.step`、`runtime.context_artifact`、`gateway.tool_execution` |

聊天调试页按会话轮次读取上述产物。补参会创建新输入版本，旧轮次继续保留原先的缺参原因和引用。完整工具凭证和受限产物由它们所属的服务管理。

## 例一 · 查订单，没有命中 Skill

| 轮次 | 内容 | 参数状态 |
| --- | --- | --- |
| 1 | 用户「查一下订单」 | 命中 `ORDER.QUERY`。定义要求 `orderId`，当前未提供。创建 Task，追问订单号。 |
| 2 | 用户回复「SO001」 | 根据回复锚点续接同一 Task。提取并校验 `orderId=SO001`，来源为 `CURRENT_TURN`。保存新的 Task 输入版本后走 `DIRECT`。 |
| 3 | 用户说「改成 SO002」 | 若仍绑定此任务，本轮明确值覆盖旧值，并对新版本重新校验和准入。 |

## 例二 · CRM 复盘，分别看两种路径

| 阶段 | 命中已发布复盘 Skill | 没命中 Skill，但目标明确且有授权工具 |
| --- | --- | --- |
| 识别 | 选择 `github.project-retrospective`，加载其 Skill 入参。 | L2 识别为复盘目标，从意图定义或通用目标输入取得项目、时间和 Markdown 交付要求。 |
| 补参 | 用户说「最近一个月」时按该 Skill 冻结为 30 天窗口。没说时间时使用已声明的默认值。仓库别名通过登记映射解析，歧义再追问。 | 用户已经给出项目名和最近一个月，先确立目标与范围。项目有多个仓库时请用户选定。 |
| 调用 | 固定 DAG 把可信仓库标识和冻结时间窗绑定到 Issue 读取与 Git 日志节点，综合节点消费两个结果。 | Planner 选定已授权工具后，从各版本的输入 Schema 获取 `repository`、`from`、`to` 或 `ref` 等节点参数。有可信映射、计划上下文或前置产物的参数直接绑定。 |

## 实现状态

当前仓库已支持从内置意图定义抽槽、多轮补参、持久化来源和查看中间产物。CRM 复盘和退款已经可以匹配已发布的 Skill，按版本固定 Task 并执行固定 DAG。其他业务的通用意图绑定、通用 Planner 的工具级补参，以及运行时对更多参数来源的统一绑定仍属于设计能力。

[现有 Intent Engine 与产物接口](../docs/implementation/intent-engine.md) · [Skill 发布实现](../docs/implementation/workflow-skill.md) · [Planner 设计](planner.md)
