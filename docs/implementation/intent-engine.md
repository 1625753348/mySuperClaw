# Intent Engine

`storage,intent` 启动独立的会话识别 API。PostgreSQL 保存会话状态，JWT 提供租户、用户、Agent 和权限。加入 `gateway` 后，配置了执行绑定的意图可以派发。加入 `runtime` 并设置销售数据工具后，会话还能提交销售分析长任务。识别回执保留原有字段，并在 `executions` 返回已关联的执行进度、原因或结果。

## 调用

| 请求 | 权限和结果 |
| --- | --- |
| `POST /v1/conversations` | `intents.submit`，返回 `conversationId` |
| `POST /v1/conversations/{id}/turns` | `intents.submit` 与 `Idempotency-Key`，提交 `text` 或 `skillKey`，返回逐候选结果 |
| `GET /v1/conversations/{id}/turns/{turnId}` | `intents.read`，读取该会话的持久化回执 |
| `GET /v1/conversations/{id}/turns/{turnId}/artifacts` | `intents.read`，逐阶段读取该轮中间产物。工具、Run 的详情仍需对应的执行查看权限。 |

用 `{"text":"帮我订会议室"}` 创建补参任务。下一轮可提交 `{"text":"明天下午三点，八人","replyToMessageId":"上一轮 assistantMessageId"}`。回执保留同一个 `taskId`，返回新参数和 `READY`。一轮涉及两个待办时可用 `targetTaskId` 指定目标。时区由 `ENTERPRISE_INTENT_ZONE` 设置，默认 `Asia/Shanghai`。

审批追问「请补充单据编号」后，可以回复「单据编号12341532416」「报销单号码：00123456」等带标签的编号。Slot Extractor 提取 `documentId`，Context Resolver 将它合并到兼容的待补参任务。右栏可以看到同一个 `taskId`、`CONTINUATION`、`FilledSlots` 和来源 `CURRENT_TURN`。即使编号符合纯数字订单号的格式，单据标签也会阻止它被误填到订单任务。

## 识别与持久化

1. `IntentEngine` 做 Unicode 归一化和显式命令检查。`IntentCatalog` 保存中文关键词、混合英文、槽位、权限、风险和路由定义。候选按规则和关键词匹配分出识别策略。启用模型时，由 LangChain4j 在允许的候选里辅助消歧，实际得分档与策略保存在 `routing_assessment`。
2. `ContextResolver` 从当前会话查找焦点任务与可用待补参任务。用当前槽位覆盖旧值，多个可用目标时要求明确目标。跨候选的业务依赖以 `dependsOn` 表达，缺少前置结果就进入 `WAITING_DEPENDENCY`。
3. `SlotExtractor` 校验时间、人数、金额、编号及附件引用。高风险动作通过签名 JWT 的业务权限校验。高危确认绑定动作、参数和待确认任务，限时使用一次。`READY` 时返回 `IntentIR`，包括定义版本、参数和来源。
4. `PostgresIntentStore` 读取最新快照，在事务外识别。提交时检查会话修订号和幂等键，一并保存 Turn、消息、候选评分、输入版本、槽位追问与确认。会话窗口保留最近八轮的意图信号，更早的信号合并为摘要。任务关联受时间和轮数限制。`清空上下文` 会清理工作状态并关闭旧的补参和确认。

日程、审批、合同等条目目前提供识别和路由建议。`CHAT` 和 `KNOWLEDGE` 返回对话路由。具体业务权限和结果脱敏由已接入的执行服务处理。[已接入的派发与配置](decision.md)

### 助手身份、会话回顾和天气

| 输入 | 分类与执行 | 调试时查看 |
| --- | --- | --- |
| 你是谁？ | `SYSTEM.IDENTITY` 命中规则后直接回复助手身份。 | `ASSISTANT_IDENTITY`，轮次完成，无业务 Task。 |
| 我上两轮问你什么？ | `SYSTEM.RECALL_PREVIOUS_MESSAGE` 将范围拆成 `offset=0`、`limit=2`，按时间顺序读取当前会话的用户消息。倒数第 2 轮为 `offset=1`、`limit=1`。 | `ConversationHistorySelection` 显示轮次范围。查看历史无需业务 Task 或聊天模型。 |
| 将我近期的对话整理成文档 | `SYSTEM.CONVERSATION_DOCUMENT`，默认最近 5 轮，识别目标格式为 `MARKDOWN`。本阶段输出识别结果。 | `ConversationHistorySelection` 中查看范围和格式。 |
| 我要查查订单列表 | `ORDER.LIST`，列表不要求单个订单号。查询 `SO001` 详情仍为 `ORDER.QUERY`。 | 对比两个意图的 `ParameterSchema`、`SlotState` 与 `ValidatedIntentIR`。 |
| 北京天气怎么样？ | `WEATHER.QUERY` 校验城市，优先用已发布的天气 SOP；初始 Skill 缺失且配置了备用工具时，沿 `DIRECT` 通过 ToolGateway 查询。 | `SKILL_UNAVAILABLE_DIRECT_FALLBACK`。无可用来源时，回复暂时无法获取实时天气。 |

在 `enterprise.intent.routing.bindings` 的 `WORKFLOW` 条目下可配置 `fallback.tool-name`、`fallback.tool-version` 和 `fallback.parameters`，例如 `city: slot.city`。为备用目标发布只读查询工具并配置 ToolGateway 权限。备用派发限于原本可走 `DIRECT` 的正常风险请求，并沿用访问权限、参数校验和审计。已固定版本的任务、显式 SOP 组合以及契约校验失败仍依原路径处理。通用聊天模型可以解释天气来源不可用，实时天气数值由天气工具提供。

从备用查询切回 SOP 时，发布的 Skill 要沿用相同的 Intent Definition 参数契约。若要收紧城市枚举或改变参数结构，应发布新的 Definition 版本并同步更新路由映射。

会话回顾用同一套范围解析处理阿拉伯数字、中文数字和「刚才」「近期」等相对位置。范围为当前轮之前的 1 至 20 轮，模糊的「几轮」默认 5 轮。会话锁和修订号控制并发顺序，查询只使用当前授权会话。没有可见消息时返回 `CONVERSATION_HISTORY_EMPTY`，清空会话也会撤去以前回顾回复里的引用。

## 交接产物与源码

### 按部署策略选择识别路径

设置 `ENTERPRISE_INTENT_ROUTING_VERSION=v1` 可启用内置分级策略。也可在部署 YAML 的 `enterprise.intent.routing.versions` 声明版本、三个分档阈值、候选最低置信度和分差，再用 `active-version` 选择生效版本。未配置活动版本时继续使用已有的识别路径。策略通过部署配置发布，和控制台数据库中的业务准入规则各自管理。

| 默认区间 | 识别策略 |
| --- | --- |
| 小于 0.30 | 直接使用规则候选。显式命令、系统控制和高风险规则可提前完成 |
| 0.30 到 0.50，不含 0.50 | BM25 从已登记能力召回，本地字符向量余弦得分参与精排，按分数与分差接受候选 |
| 0.50 到 0.70，不含 0.70 | 同一 Task、策略版本和授权范围内沿用原策略。无可复用判定时先召回，分差不足时请求模型消歧 |
| 大于等于 0.70 | 将受控候选交给配置好的 LLM。Decision 继续独立决定是聊天、单次调用、固定工作流还是 Planner |

复杂度与置信度各自独立。复杂度使用多意图、开放式目标、长度和延续任务等特征。可在同一策略的 `examples` 中为已有意图增加同义说法，例如 `"[ORDER.QUERY]": "运单进度"`。历史轮次、任务和中间产物保存实际使用的策略、候选和版本。在 `enterprise.intent.routing.embedding` 配置 `enabled`、`base-url`、`api-key` 和 `model` 后，LangChain4j 会为目录与当前片段生成向量，融合语义余弦分数。目录向量缓存于进程内，Embedding 不可用时回退 BM25 与字符向量。

用 `INTENT_SMOKE_ROUTING=true` 运行独立的 `scripts/intent-smoke.mjs`，可以验证规则直达、多轮滞回、L1 同义词召回、高风险绕开模型及 LLM 白名单。加上 `INTENT_SMOKE_EMBEDDING=true` 可用本地模型夹具验证语义召回。

### 调试页面

用退款例子了解 Definition、参数 Schema、部署 YAML、业务规则和 Skill 固定 DAG 的接力。[从 Definition、规则到 SOP 和 DAG](sop-dag-definition-rules.md)

在已有工具管理台打开 `/tool-admin/index.html#chat`，进入「会话调试」。启用 `storage,gateway,intent` 后可使用会话 API。页面内单独连接会话 JWT，至少需要 `intents.submit` 和 `intents.read`。订单需要 `tools.invoke`，销售提交另需 `runs.submit`，查看 Run 需要 `runs.read`。

左侧每次发送新增一个 Turn。等待补参时可选择「回复此任务」，第二轮沿用同一个 Task ID。右侧按 `Conversation → Turn → Task → Tool Execution / Run` 显示关联。Task 同时关联多轮时可以跳转对比。展开阶段看原始 JSON，执行中会定时刷新所选轮次，长任务结束后可读取报告。刷新页面后重新连接凭证，即可恢复历史会话并按需加载更早的轮次。

每轮提交后，从回执取得 `turnId`，再按下列方式查看实际产物。响应的 `intake` 展示原始请求和归一化输入，`candidates[].stages` 按候选展示上下文、槽位、IR 和后续阶段。会话回复在 `response`。候选序号与普通回执的 `outcomes` 对应。

```powershell
curl.exe -H "Authorization: Bearer $env:INTENT_ACCESS_TOKEN" `
  "http://localhost:8080/v1/conversations/$conversationId/turns/$turnId/artifacts"
```

例如补参轮里，分类器可以先给出 `UNKNOWN`，上下文随后把 `SO001` 合并到第一轮的 `ORDER.QUERY`。这两个结论在 `IntentCandidate.data.draft.classifierIntent` 和 `ContextRelation` 分别可见。第一轮的 IR 标为 `NOT_PRODUCED`；销售分析可刷新查看 Planner 提案、当前计划节点与依赖、网关执行引用及 Run 状态。SSE 使用回执里的 `eventsUrl`。

旧轮次可能缺少归一化记录，返回 `UNAVAILABLE`。命中安全拦截时文本显示 `REDACTED`。清空上下文后，旧轮次的产物接口只返回轮次 ID 与 `REDACTED` 状态。ContextSnapshot 展示修订号、时间和条数，避免带出其他候选使用的会话内容。

| 交接产物 | 代码与用途 | 持久化位置 |
| --- | --- | --- |
| RawRequest / NormalizedInput | [IntentArtifacts](../../modules/intent-engine/src/main/java/com/example/enterpriseagent/intent/IntentArtifacts.java) 和 [InputNormalizer](../../modules/intent-engine/src/main/java/com/example/enterpriseagent/intent/InputNormalizer.java)。基于认证上下文封装请求和会话，再归一化文本。 | 原始消息与幂等回执分别在消息、正文与轮次表。归一化对象在本次分析内使用。 |
| IntentCandidate | [IntentEngine](../../modules/intent-engine/src/main/java/com/example/enterpriseagent/intent/IntentEngine.java) 选择领域、动作、识别策略和置信度。 | `intent.candidate.draft` 保存候选标识及字段线索，`outcome` 保存候选结论。 |
| ContextSnapshot / ContextRelation | [ContextAssembler](../../modules/intent-engine/src/main/java/com/example/enterpriseagent/intent/context/ContextAssembler.java) 使用已经按主体和 Agent 隔离的会话快照，Resolver 判断目标关联和歧义。 | 快照底稿在 `intent.conversation_state`。候选证据、目标 Task 及上一版输入号在 `intent.candidate.evidence`。 |
| ParameterSchema / FilledSlots / SlotState | [IntentArtifacts](../../modules/intent-engine/src/main/java/com/example/enterpriseagent/intent/IntentArtifacts.java) 声明契约，[SlotExtractor](../../modules/intent-engine/src/main/java/com/example/enterpriseagent/intent/definition/SlotExtractor.java) 提取并检查已识别字段。 | `intent.definition.parameter_schema`、`intent.task_input.slots` 和 `slot_state`。 |
| Validated Intent IR | 独立的 [ValidatedIntentIR](../../modules/intent-engine/src/main/java/com/example/enterpriseagent/intent/ValidatedIntentIR.java) DTO，由 Intent Engine 构建，保存 Task 输入时补上 `inputVersion`。Decision 用已经保存的 IR 选择执行。 | `intent.task_input.validated_ir`，HTTP JSON 路径为 `outcomes[].ir`。 |
| RouteDecision / PlanProposal / PlanVersion | [DecisionService](../../modules/decision/src/main/java/com/example/enterpriseagent/decision/DecisionService.java) 解析绑定，[RunManager](../../modules/agent-runtime/src/main/java/com/example/enterpriseagent/runtime/RunManager.java) 领取、校验和发布规划版本。 | `intent.route_decision` 记识别准入，`intent.turn_execution` 记派发策略版本、原因和目标。提案和计划在 `runtime.plan_proposal` 与 `runtime.plan`。 |
| ToolCall / ToolResult | [ToolAdapter.Call](../../modules/tool-gateway/src/main/java/com/example/enterpriseagent/toolgateway/adapter/ToolAdapter.java) 携带可信身份、执行键和 Trace ID，[ToolExecutionService.Result](../../modules/tool-gateway/src/main/java/com/example/enterpriseagent/toolgateway/execution/ToolExecutionService.java) 返回登记版本、状态、审批 ID 和可用输出。 | `gateway.tool_execution` 保存执行和版本，后台任务结果以加密方式写入 `gateway.tool_job`。 |
| StateUpdate / Response | [RunStore.StateUpdate](../../modules/agent-runtime/src/main/java/com/example/enterpriseagent/runtime/RunStore.java) 推进 Run，[ResponseComposer](../../modules/intent-engine/src/main/java/com/example/enterpriseagent/intent/ResponseComposer.java) 组合追问、执行结果或 SSE 链接。 | `runtime.run`、`runtime.checkpoint`、`runtime.run_event` 保存可恢复历史。会话读回当前执行状态时从执行服务组装响应。 |

例如「查一下订单」会返回 `NEEDS_CLARIFICATION`。用第一轮的回复 ID 补充「SO001」后，同一个 Task 的 IR 是第 2 版。

```json
{
  "domain": "ORDER",
  "action": "QUERY",
  "definitionVersion": 1,
  "taskId": "48273f80-3d6c-4c23-aa8a-c6b8024db239",
  "arguments": { "orderId": "SO001" },
  "provenance": { "orderId": "CURRENT_TURN" },
  "inputVersion": 2,
  "riskHint": "NORMAL",
  "responseMode": "RESULT"
}
```

## 启动与单独验收

已有库依次应用 `005-intent-conversations.sql` 和 `006-intent-dispatch.sql`。空白测试库使用 `database/schema.sql`。配置数据库连接和网关 JWT 的 issuer、audience、JWK 地址后运行以下命令。

```powershell
java -jar apps/agent-app/target/agent-app-0.1.0-SNAPSHOT.jar --spring.profiles.active=storage,intent
```

启用模型时设置 `ENTERPRISE_INTENT_MODEL_ENABLED=true` 和 `ENTERPRISE_INTENT_MODEL_API_KEY`，或者复用 `DEEPSEEK_API_KEY`。模型端点和型号可通过 `ENTERPRISE_INTENT_MODEL_BASE_URL` 与 `ENTERPRISE_INTENT_MODEL` 配置。默认使用规则和关键词。工作簿验收使用该默认设置。

```powershell
node scripts/intent-smoke.mjs apps/agent-app/target/agent-app-0.1.0-SNAPSHOT.jar $workbook target/intent-evaluation.md
```

`$workbook` 指向原始 XLSX。脚本通过 Python 标准库读取工作簿，也可读取导出的工作簿 JSON。`INTENT_PYTHON` 可指定解释器。省略工作簿时仅运行独立 API 验收。设置 `INTENT_SMOKE_MODEL=true` 可使用本地确定性模型响应验证 LangChain4j 适配和候选约束。[独立测试脚本](../../scripts/intent-smoke.mjs)

用 Runtime 联调脚本跑订单和销售分析时，可设置 `INTENT_ARTIFACTS_EXAMPLE=target/intent-artifacts-example.json`，导出服务端实际返回的两轮查订单和一轮销售分析产物。[端到端联调脚本](../../scripts/runtime-smoke.mjs)
