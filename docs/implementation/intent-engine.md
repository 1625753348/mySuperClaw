# Intent Engine

`storage,intent` 启动独立的会话识别 API。PostgreSQL 保存会话状态，JWT 提供租户、用户、Agent 和权限。加入 `gateway` 后，配置了执行绑定的意图可以派发。加入 `runtime` 并设置销售数据工具后，会话还能提交销售分析长任务。识别回执保留原有字段，并在 `executions` 返回已关联的执行进度、原因或结果。

从数据库角度跟踪一次对话和运行，参见 [会话、任务与运行的数据生命周期](persistence-lifecycle.md)。

## 调用

| 请求 | 权限和结果 |
| --- | --- |
| `POST /v1/conversations` | `intents.submit`，返回 `conversationId` |
| `POST /v1/conversations/{id}/turns` | `intents.submit` 与 `Idempotency-Key`，提交 `text` 或 `skillKey`，返回逐候选结果 |
| `GET /v1/conversations/{id}/turns/{turnId}` | `intents.read`，读取该会话的持久化回执 |
| `GET /v1/conversations/{id}/turns/{turnId}/artifacts` | `intents.read`，逐阶段读取该轮中间产物。工具、Run 的详情仍需对应的执行查看权限。 |

用 `{"text":"帮我订会议室"}` 创建补参任务。下一轮可提交 `{"text":"明天下午三点，八人","replyToMessageId":"上一轮 assistantMessageId"}`。回执保留同一个 `taskId`，返回新参数和 `READY`。一轮涉及两个待办时可用 `targetTaskId` 指定目标。时区由 `ENTERPRISE_INTENT_ZONE` 设置，默认 `Asia/Shanghai`。

审批追问「请补充单据编号」后，可以回复「单据编号12341532416」「报销单号码：00123456」等带标签的编号。Slot Extractor 提取 `documentId`，Context Resolver 将它合并到兼容的待补参任务。右栏可以看到同一个 `taskId`、`CONTINUATION`、`FilledSlots` 和来源 `CURRENT_TURN`。即使编号符合纯数字订单号的格式，单据标签也会阻止它被误填到订单任务。

当「交审批」这类简短回复只提供了可识别的业务线索、动作仍不明确时，系统返回 `NEEDS_CLARIFICATION / BUSINESS_ACTION_UNCLEAR`，并提示可以回复「提交审批」或「查询审批待办」。动作澄清轮没有 Task 和可执行 IR。用户明确动作后，才进入相应任务的参数校验。

「你是一个助手啊」这类普通聊天未命中专门意图时，走 `CHAT.SMALLTALK / CONVERSATIONAL_FALLBACK`。聊天模型启用后沿用现有异步对话任务，模型未启用或未返回结果时，页面保留即时的自然语言回应。缺对象或动作的业务请求仍会追问，明确但系统暂不支持的操作会给出能力说明。分类、路由原因与状态可在右栏调试产物中查看。

## 一轮请求如何完成意图识别

`Conversation` 是会话容器，`Turn` 是一次用户输入及其回执，`Task` 是可以跨轮补参的业务目标。一轮可以产生多个候选。聊天、动作澄清和历史回顾可以有 Turn 而无需业务 Task。Task 满足准入条件并完成派发后，才会关联 Tool Execution 或 Run。

1. **接入与读取会话。** `IntentController.turn()` 从 JWT 取得可信租户、用户、Agent 与权限，接收消息和 `Idempotency-Key`。`PostgresIntentStore.turn()` 按主体读取会话快照，检查幂等记录。通过 `replyToMessageId` 回复已关联的 Task 时，`withReplyTarget()` 在本会话内解析目标。如果同一幂等键对应的请求体不同，返回 HTTP 409。[IntentController](../../modules/access/src/main/java/com/example/enterpriseagent/access/http/IntentController.java#L51) · [PostgresIntentStore.turn](../../modules/infrastructure/src/main/java/com/example/enterpriseagent/infrastructure/intent/PostgresIntentStore.java#L137)
2. **归一化与前置规则。** `InputNormalizer.normalize()` 处理全半角、空白和英文大小写，`ContextAssembler.assemble()` 冻结会话中的任务、焦点和实体线索。`IntentEngine.analyze()` 优先处理安全拦截、清空、历史回顾、高危规则和任务控制。`SPLIT` 将复合消息拆成片段，`WorkflowIntake.resolve()` 逐段尝试已接入的复盘或退款 Skill，普通候选继续走分类流程。[InputNormalizer](../../modules/intent-engine/src/main/java/com/example/enterpriseagent/intent/InputNormalizer.java) · [ContextAssembler](../../modules/intent-engine/src/main/java/com/example/enterpriseagent/intent/context/ContextAssembler.java#L21) · [IntentEngine.analyze](../../modules/intent-engine/src/main/java/com/example/enterpriseagent/intent/IntentEngine.java#L101) · [WorkflowIntake](../../modules/intent-engine/src/main/java/com/example/enterpriseagent/intent/WorkflowIntake.java#L30)
3. **产生候选。** `IntentCatalog` 登记意图 ID、匹配规则、参数字段、风险和路由提示。`recognize()` 优先处理显式 Skill Key 和命中规则的短请求。启用分级策略时，`route()` 可调用 `IntentRecall.rank()` 做语义召回，必要时让意图模型在受控候选中选择。旧路径也可在部分规则冲突或找不到候选的业务输入上调用模型。调试时先看 `IntentCandidate.data.draft.classifierIntent`，它记录本轮初步分类。[IntentCatalog](../../modules/intent-engine/src/main/java/com/example/enterpriseagent/intent/definition/IntentCatalog.java) · [IntentEngine.recognize](../../modules/intent-engine/src/main/java/com/example/enterpriseagent/intent/IntentEngine.java#L283) · [IntentRecall.rank](../../modules/intent-engine/src/main/java/com/example/enterpriseagent/intent/routing/IntentRecall.java#L36)
4. **判断是新任务还是补参。** `ContextResolver.resolve()` 根据显式回复、新任务提示、焦点和兼容的待澄清任务定位目标。待办超过 30 分钟或落后超过 20 个会话修订时会失效。槽位回复可以借用目标任务的意图和定义版本，因此初步分类为 `UNKNOWN` 的「单据编号12341532416」仍可解析成原审批任务的 `CONTINUATION`。仍有多个兼容目标时返回 `TARGET_AMBIGUOUS`。[ContextResolver.resolve](../../modules/intent-engine/src/main/java/com/example/enterpriseagent/intent/context/ContextResolver.java#L21)
5. **提取、合并与校验参数。** `SlotExtractor.extract()` 按意图声明的字段处理显式 `arguments` 或本轮文本。`SlotMerger` 先继承历史参数，再按条件补入会话实体，最后用本轮参数覆盖。来源分别记为 `PRIOR_TASK`、`SESSION_ENTITY` 和 `CURRENT_TURN`。本轮非法值会移除对应旧值并产生 `INVALID_SLOTS`，必填字段缺失则产生 `REQUIRED_SLOTS`。通过槽位校验和识别层权限检查的一般业务请求产出 `ValidatedIntentIR`，并标为 `READY`。高危动作还需走授权和确认分支，存在前置结果依赖的候选进入 `WAITING_DEPENDENCY`。[SlotExtractor](../../modules/intent-engine/src/main/java/com/example/enterpriseagent/intent/definition/SlotExtractor.java) · [SlotMerger](../../modules/intent-engine/src/main/java/com/example/enterpriseagent/intent/context/SlotMerger.java) · [IntentEngine.apply](../../modules/intent-engine/src/main/java/com/example/enterpriseagent/intent/IntentEngine.java#L411) · [ValidatedIntentIR](../../modules/intent-engine/src/main/java/com/example/enterpriseagent/intent/ValidatedIntentIR.java)
6. **固定版本、保存结果与派发。** `ConfiguredWorkflowRoutes.bind()` 可为候选绑定已发布的 SOP 版本并检查其参数契约。`PostgresIntentStore` 把识别放在事务外，提交时锁住会话并复查修订号与幂等键，然后为 IR 填入 `inputVersion`，保存轮次、消息、候选、评分和任务输入。`DecisionService.resolve()` 按已校验的意图、版本和配置查找执行绑定，`PostgresIntentDispatch.enqueue()` 检查派发权限后提交网关调用或 Runtime Run。普通闲聊和知识问答由 `ConversationRouteJobs.enqueue()` 按配置进入异步路径。转交执行的结果可在后续读取回执时通过 `executions` 查看。[ConfiguredWorkflowRoutes.bind](../../modules/infrastructure/src/main/java/com/example/enterpriseagent/infrastructure/intent/ConfiguredWorkflowRoutes.java#L31) · [PostgresIntentStore.commit](../../modules/infrastructure/src/main/java/com/example/enterpriseagent/infrastructure/intent/PostgresIntentStore.java#L272) · [DecisionService.resolve](../../modules/decision/src/main/java/com/example/enterpriseagent/decision/DecisionService.java#L78) · [PostgresIntentDispatch.enqueue](../../modules/infrastructure/src/main/java/com/example/enterpriseagent/infrastructure/intent/PostgresIntentDispatch.java#L64) · [ConversationRouteJobs.enqueue](../../modules/infrastructure/src/main/java/com/example/enterpriseagent/infrastructure/intent/ConversationRouteJobs.java#L61)

识别使用的会话快照由 PostgreSQL 持久化，保存焦点、工作任务及简短的意图历史。聊天模型使用的正文窗口与摘要另有读取路径。它们和工作任务的槽位是不同的数据来源。[对话摘要怎么生成和使用](memory.md#对话摘要怎么生成和使用)

### Context Assembler 与 Context Resolver

它们是 `IntentEngine` 内部相邻的步骤。引擎先归一化和冻结基本会话状态。初步候选需要关联历史任务时，Assembler 再在本轮内补齐证据。Resolver 为各候选返回关系，之后 Slot Merger 与校验步骤才处理参数。

| 组件 | 输入和输出 | 实际读写 |
| --- | --- | --- |
| **Context Assembler** | 授权会话状态与 `ContextRequest` → 只读 `ContextSnapshot` | 基本快照来自 `intent.conversation_state`。需要任务关联时按任务 ID 加载最多 8 条可见消息、`intent.task` 的历史意图 ID 与输入版本、该版本上的开放槽位澄清。授权可读时附带仍活动的 `runtime.run` 状态。快照中的历史正文只在本轮内存使用，调试产物只持久化计数和结构化证据。[ContextAssembler](../../modules/intent-engine/src/main/java/com/example/enterpriseagent/intent/context/ContextAssembler.java) · [PostgreSQL 读取器](../../modules/infrastructure/src/main/java/com/example/enterpriseagent/infrastructure/intent/PostgresContextReader.java) |
| **Context Resolver** | 一个候选和同一份快照 → `ContextRelation` | 优先接受兼容的显式任务引用，识别独立请求，再按任务时效、意图和槽位检索候选。只有一个候选与开放澄清的问题匹配时，可据此消歧。多个候选仍兼容时返回 `AMBIGUOUS` 和候选 Task ID。取消和确认使用确定性目标规则。[ContextResolver](../../modules/intent-engine/src/main/java/com/example/enterpriseagent/intent/context/ContextResolver.java) |
| **槽位合并与提交** | 关系结论和已提取的槽位 → 新版 Task Input | Slot Merger 才合并旧值和本轮参数。事务重新核对会话修订号与目标输入版本，条件更新成功后才写入新版本。澄清 ID 按已验证的旧版本关联，便于追踪哪一轮回答了哪个问题。[SlotMerger](../../modules/intent-engine/src/main/java/com/example/enterpriseagent/intent/context/SlotMerger.java) · [PostgresIntentStore](../../modules/infrastructure/src/main/java/com/example/enterpriseagent/infrastructure/intent/PostgresIntentStore.java) |

右栏的 `ContextSnapshot.data.contextCounts` 包含 `recentMessageCount`、`pendingClarificationCount` 和受权限保护的 `activeRunCount`。纯系统回复、无可用任务的会话等场景可能只用基础快照。右栏 `ContextRelation` 可看到 `targetTaskId`、`targetIntentId`、`targetClarificationId`、`targetInputVersion`、`candidateTaskIds` 和 `evidence`。例如 `MATCHED_FIELDS:documentId` 说明本轮内容匹配目标的编号槽位，具体取值查看 `FilledSlots`。

两笔审批同时等编号时，「单据编号12341532416」会列出两个可能目标并追问，回复其中一笔或提供 `targetTaskId` 才能续接。另一个例子是「订单号是 SO001，商品有质量问题。另外帮我查询一下客户 C002」，退款候选沿用原 Task，客户查询单独建立 Task，其后执行仍取决于业务绑定及权限。这两条路径都由同一个引擎处理。当前关系消歧使用可解释规则和结构化证据，意图模型继续负责当前文本的候选识别。

### 用四轮审批看清楚在哪里停

| 用户输入 | 结果与原因 | Task 和参数 |
| --- | --- | --- |
| 把上周张三提交的那笔报销处理一下 | `NEEDS_CLARIFICATION / ACTION_AMBIGUOUS`，追问是查询还是提交审批。 | 暂无 Task，业务动作待确认。 |
| 交审批 | `NEEDS_CLARIFICATION / BUSINESS_ACTION_UNCLEAR`，提示回复「提交审批」或「查询审批待办」。 | 暂无 Task，审批动作尚未确认。 |
| 提交审批 | `APPROVAL.CREATE / NEEDS_CLARIFICATION`，原因是 `REQUIRED_SLOTS`。 | 创建审批 Task，缺少 `documentId`，没有可执行 IR。 |
| 单据编号12341532416 | `APPROVAL.CREATE / READY`，关系是 `CONTINUATION`。 | 沿用第 3 轮的 Task，把编号写入 IR 的 `arguments.documentId`，来源为 `CURRENT_TURN`。 |

这时可以通过右栏比对第 3、4 轮的 `taskId`、`ContextRelation`、`FilledSlots` 和 `ValidatedIntentIR`。是否实际发出审批请求，要继续看执行绑定、业务权限和 `executions` 的状态。[已接入的派发与配置](decision.md) · [四轮案例回归脚本](../../scripts/intent-smoke.mjs)

### 意图模型、聊天模型和 Planner 各在哪里

| 能力 | 当前触发条件和输出 |
| --- | --- |
| 意图模型 | `IntentEngine.choose()` 只传当前片段和候选 ID。LangChain4j 返回单个意图 ID、`UNKNOWN` 或 `out_of_scope`。服务端逐项校验候选范围和风险。参数抽取、任务关系和 IR 由上述代码处理。[LangChain4j 适配](../../modules/infrastructure/src/main/java/com/example/enterpriseagent/infrastructure/IntentConfiguration.java#L96) · [候选校验](../../modules/intent-engine/src/main/java/com/example/enterpriseagent/intent/IntentEngine.java#L381) |
| 聊天模型 | 已判为 `CHAT` 且模型启用时，由后台任务生成回复。例如「你是一个助手啊」走 `CONVERSATIONAL_FALLBACK`。启用意图模型和启用聊天模型使用各自的配置。[聊天模型配置](../../modules/infrastructure/src/main/java/com/example/enterpriseagent/infrastructure/IntentConfiguration.java#L202) |
| Runtime Planner | 已识别的开放任务还需要匹配规划绑定和通过派发准入。成功创建 Run 后，Planner 生成候选 DAG，运行时负责校验和执行。订单号补参、聊天、已绑定的单次查询和固定 SOP 各有自己的处理路径。[派发与准入](../../modules/infrastructure/src/main/java/com/example/enterpriseagent/infrastructure/intent/PostgresIntentDispatch.java#L131) · [通用 Planner](general-planner.md) |

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

`v1` 要求语义召回第一名的分数至少为 `0.35`，且领先第二名至少 `0.06`，才能作为可靠候选。这个门槛只用于接受召回结果。混合档可能进一步请求意图模型，可信规则也可能直接给出结果。动作不明确、任务目标歧义和参数缺失分别由各自的追问条件处理。查看右栏 `IntentCandidate.data.assessment` 时，`complexityScore` 表示分档输入，`matchConfidence` 可能来自规则常量、召回相似度或模型命中后的固定值。例如通过规则产生的澄清回执可能显示 `0.95`，该值仅是默认回执评分。调试追问时结合 `reason`、`policyVersion` 和 `modelRequested` 判断。[v1 配置](../../apps/agent-app/src/main/resources/application-intent.yml#L10) · [召回门槛与模型调用](../../modules/intent-engine/src/main/java/com/example/enterpriseagent/intent/IntentEngine.java#L332)

用 `INTENT_SMOKE_ROUTING=true` 运行独立的 `scripts/intent-smoke.mjs`，可以验证规则直达、多轮滞回、L1 同义词召回、高风险绕开模型及 LLM 白名单。加上 `INTENT_SMOKE_EMBEDDING=true` 可用本地模型夹具验证语义召回。

### 意图知识盲区复核

意图模型可以明确返回 `out_of_scope`，表示看得出用户要办一项业务，但候选都不适用。若可信规则和当前会话仍然找不到该动作，轮次记录 `MODEL_OUT_OF_SCOPE`。模型未启用、返回 `UNKNOWN`、匹配超时或明确需要补参的请求不进入这个清单。

用带 `intents.review` 权限的管理身份请求 `GET /v1/admin/intent-review/unmatched?limit=25`，读取本租户近 30 天内可见的原始提问。可传回上一页的 `nextBefore` 获取更早结果。同一轮最多出现一次，清单按现有会话轮次和正文投影生成。清空上下文或脱敏后，该记录不再返回原文。

产品或运营根据重复需求决定是否新增能力。现有目录中的通用意图通过评审后更新 `IntentCatalog`、对应槽位提取规则和路由绑定，再发布部署。已有发布流程的 SOP 则更新其定义契约和版本。复核接口只提供线索，候选目录不会由模型自行修改。

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
| StateUpdate / Response | [RunStore.StateUpdate](../../modules/agent-runtime/src/main/java/com/example/enterpriseagent/runtime/RunStore.java) 推进 Run。[ResponseComposer](../../modules/intent-engine/src/main/java/com/example/enterpriseagent/intent/ResponseComposer.java) 合并候选回复，并在读取回执时挂载执行服务提供的结果或 SSE 链接。 | `runtime.run`、`runtime.checkpoint`、`runtime.run_event` 保存可恢复历史。会话读回当前执行状态时从执行服务组装响应。 |

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
