# 从注册到执行 · ToolGateway 学习问答

这份文档按讨论顺序整理。起点是一个常见思路：先把自研函数、REST API、RPC 和 MCP 工具注册起来，再从目录召回 Top K，交给大模型。沿着它可以分别回答四个问题：有哪些工具、这轮选哪个、这次能不能执行、由谁真正调用。

## 1. Tool Registry 里放的是函数吗？

> 我把自定义函数的参数和实现都写好，也有业务系统 REST API、RPC 和 MCP，它们怎么一起注册？

Registry 登记的是**工具契约和执行绑定**。一条发布记录能回答：工具叫什么、输入输出是什么、当前可以用哪个版本、风险等级是什么、执行时交给哪个协议适配器。具体的方法实现或远端服务由适配器访问。

| 来源 | 发布时登记 | 执行时发生什么 |
| --- | --- | --- |
| 自定义方法，例如 `testHollywood` | 工具名、Schema、版本、Local 目标别名 | Local 适配器从已装配的 Spring Bean 找到对应 `LocalTool`，调用 Java 实现 |
| 业务 REST API | HTTP 目标别名、Schema、版本及凭证引用 | HTTP 适配器按部署配置对固定端点发请求。OA 会议室走 GET，部分创建工具走 POST |
| RPC 接口 | gRPC 服务、方法和部署绑定 | gRPC 适配器按受控绑定发请求，当前实现支持 unary gRPC |
| MCP 服务 | MCP 服务别名、远端工具名与审核过的契约 | 网关作为 MCP Client 向服务器发送 `tools/call` |

MCP 服务可以用统一协议对外列出工具及参数，也可以接收调用。此处有两次「发现」。管理员通过 MCP 的 `tools/list` 看**这台服务器提供什么**，再选取能力登记发布。运行时 Tool Discovery 搜的是**当前租户、当前主体可发现的企业工具目录**，该目录可以混合 HTTP、MCP 和本地工具。[MCP 工具规范](https://modelcontextprotocol.io/specification/2025-11-25/server/tools) · [适配器说明](tool-gateway.md#接自有-javamcp-和-rpc)

## 2. BM25 和向量召回做了什么？

> 我理解是先语义检索和 BM25 召回 Top K，再把它们绑定给大模型，对吗？

这描述的是一种动态调用接法。先把检索本身拆出来看：

1. Tool Discovery 从 PostgreSQL 读取符合租户、当前发布版本与发现权限的工具。
2. BM25 根据工具名、描述、分类和已审核参数说明做词法排序，适合匹配「会议室」或准确的工具名。
3. 启用语义索引时，可从 Milvus 召回表述不同但含义接近的候选，例如「找个开会的地方」和「查询会议室」。索引候选还需与可见的当前版本核对。
4. RRF 按两个分支的排名融合，结合工具名精确命中和查询词覆盖情况调整排序，返回 Top K。

**Top K 是候选名单。** 召回分数表达本次搜索的相关性，能否执行仍由网关独立判断。模型可以根据候选选工具，应用也可以把候选交给 Planner 生成 DAG，或者仅将它们显示在工具管理台。[发现流程与评分](tool-gateway.md#一件工具怎样从发布走到召回) · [ToolDiscovery 源码](../../modules/tool-gateway/src/main/java/com/example/enterpriseagent/toolgateway/discovery/ToolDiscovery.java)

## 3. 三个稳定入口和动态绑定是两种接法吗？

> 「给 Agent 绑定 `search_tools`、`get_tool_schema`、`execute_tool`，搜索后再动态绑定候选 Schema」这句话怎么理解？

**可以拆成 A、B 两种接法。任选一条就可以形成调用闭环。** 底层会用到相同的网关能力，应用也可以按任务组合使用。

| 网关入口的抽象名 | 当前仓库的 HTTP API | 作用 |
| --- | --- | --- |
| `search_tools` | `POST /v1/tools/search` | 根据描述返回有权发现的工具候选与版本 |
| `get_tool_schema` | `GET /v1/tools/{name}/schema?version=1` | 获取固定版本的输入输出契约 |
| `execute_tool` | `POST /v1/tools/executions` | 提交指定工具、版本和参数，由网关返回执行 ID、状态及可用结果 |

### A · 绑定通用入口，由模型决定何时搜索

模型可以先调用 `search_tools`，拿到候选后用 `get_tool_schema` 查看契约，最后发出 `execute_tool(name="oa_meeting_room_list", version="1", arguments={})`。在这条路径里，具体工具名一直是 `execute_tool` 的参数。三个入口可以一直出现在模型可见的函数列表里，模型可以在看完一次结果后再搜下一批。

### B · 应用先搜索，只绑定本轮具体工具

应用先搜 Top K、取候选版本的 Schema，再向模型绑定诸如 `oa_meeting_room_list`、`oa_approval_list` 的函数定义。模型发出 `oa_meeting_room_list({})` 调用请求后，ToolNode 找到同名的**代理函数**，代理用已绑定的工具名和版本将这次调用交给网关。此时模型无需另外调 `get_tool_schema`。

```text
A  模型 → search_tools → get_tool_schema → execute_tool → ToolGateway
B  应用 → 搜索和取 Schema → bind_tools(Top K)
        → 模型生成 tool_calls → ToolNode 调网关代理 → ToolGateway
```

`bind_tools` 把名称、描述和参数定义提供给模型。模型生成 `tool_calls` 后，由应用或框架执行并回传结果。**网关的权限和审计能覆盖到这次调用，取决于执行侧是否接进网关。** 如果 ToolNode 直接连到原始方法或远端客户端，这次调用就走了另一条执行路径。代理可以统一注入可信身份、固定工具版本和业务幂等键。[LangChain 工具调用](https://docs.langchain.com/oss/python/langchain/models#tool-calling) · [网关 API 实现](../../modules/access/src/main/java/com/example/enterpriseagent/access/http/ToolGatewayController.java)

## 4. 我们的系统采用 A、B，还是配置路由？

> 本系统没有模型的 `tool_calls`，那 Java 怎么知道是 `oa_meeting_room_list@1`？

**当前会话主链由配置路由、已发布 SOP 或 Planner 提案驱动执行。** 已存在的三个网关 API 可用于接入 A、B。主链路里的 Planner 生成结构化 DAG 提案，经校验后由 Worker 执行。

以「查询会议室」为例，有两次查表：

| 位置 | 查找键 | 查到什么 |
| --- | --- | --- |
| Decision Service 查路由表 | 已认证的租户与 `MEETING.ROOM_LIST` | `DIRECT`，工具 `oa_meeting_room_list`，工具版本 `1` |
| ToolGateway 查工具目录 | 租户、工具名、工具版本 | 已发布工具契约、HTTP 目标别名、凭证引用和治理配置 |

具体顺序是：

1. `IntentCatalog` 针对「查询」与「会议室」识别 `MEETING.ROOM_LIST`。这一定义没有必填槽位，校验通过后进入 `ValidatedIntentIR`。
2. OA 启动脚本将 `MEETING.ROOM_LIST@1` 绑定到 `oa_meeting_room_list@1`。两个 `1` 分别是意图定义版本与工具版本。Spring 将租户绑定加载到 Decision Service。
3. `DecisionService` 用租户和结构化意图查绑定，核对定义版本，从已校验槽位计算工具参数。会话派发交给网关。
4. `ToolGateway` 按名字与版本找到执行配置并完成校验，再由 HTTP 适配器请求 `GET /api/meeting-rooms`。如果绑定的是 Local，最后一步才是调用一个 Java 函数。

这条 `DIRECT` 链路无需在工具目录里搜索替代接口。移除该绑定后，仍可能得到 `MEETING.ROOM_LIST`，但这条路由无法派发。固定 SOP 则由已发布的节点指定工具与依赖。调试页的 `ToolCall` 是本系统的调用产物，模型的 `tool_calls` 是原生 Function Calling 的响应字段。[会议室意图](../../modules/intent-engine/src/main/java/com/example/enterpriseagent/intent/definition/IntentCatalog.java#L53) · [OA 路由配置](../../scripts/oa-console-integration.mjs#L103) · [决策实现](../../modules/decision/src/main/java/com/example/enterpriseagent/decision/DecisionService.java#L78) · [Planner 提案](general-planner.md)

## 5. 权限、Policy、幂等怎么落地？

> 最后不管是模型选的还是路由选的，权限、幂等和审批具体在哪里发生？

所有经过网关的执行请求都先得到由服务端认证形成的 `GatewayCaller`。模型输出可以提供业务参数。租户、用户、Agent、角色和端点凭证由运行环境与认证配置提供。

| 检查 | 例子 | 当前落点 |
| --- | --- | --- |
| `DISCOVER` | 可以在搜索里看到报销工具，也可以读取对应契约 | 查询和 Schema 读取按租户及工具授权过滤 |
| `EXECUTE` | 能看到一个工具，执行权限可能仍未授予。撤权后再次执行需要重新通过权限检查 | `ToolRegistry.requirePermission`，查询用户、Agent 和角色授权 |
| ABAC、Schema、限额 | 角色可以发起报销，业务值仍须满足策略。Schema 验证金额字段的形状、类型与范围。配额检查本次是否还有执行额度 | `ToolPolicyService`、`ToolContracts` |
| 审批 | OA 报销补齐参数后先进入 `WAITING_APPROVAL`。具备审批权限的另一主体批准后，原执行排队继续运行 | `gateway.tool_approval`、`gateway.tool_job`、`ToolJobService` |
| 幂等 | 同一次逻辑报销的超时重试沿用同一个键，读取原执行。同键换了金额或版本会得到 `409` | `ToolExecutionService.prepare` 在事务内核对原执行、固定版本和规范化参数指纹 |
| 调用与审计 | 受理后用受控凭证访问 OA，结算记录执行状态、版本和追踪 ID。输出按契约检验并脱敏 | 协议适配器、`gateway.tool_execution`、`gateway.audit_entry` |

需要分清三个 ID。模型返回的 `tool_call_id` 关联本次模型消息和结果。应用管理的 `Idempotency-Key` 识别同一个逻辑操作。网关生成的 `executionId` 供查询、审批和恢复。OA 报销还把该执行 ID 作为下游 `requestKey`，让 OA 自身可以防重。下游不提供幂等或明确查询契约时，超时结果不确定的写入需要先查状态或对账，审批通过后也应沿原执行续跑。[执行准入源码](../../modules/tool-gateway/src/main/java/com/example/enterpriseagent/toolgateway/execution/ToolExecutionService.java#L161) · [审批与续跑](tool-gateway.md#策略与续跑) · [OA 创建工具示例](oa-business-system-integration.md#6-接入会澄清的创建操作)

## 6. 企业里应该如何选？

> 真正的企业场景用 A、B，还是直接配置路由？

同一系统可以采用多条路径。微软 Copilot Studio 的公开文档同时描述了在预定义 Topic 中显式调用工具和由模型动态编排工具。Anthropic 将预定义工作流和 Agent 动态选工具作为不同任务形态，其 Tool Search 也展示了按需加载工具定义。[Microsoft 工具编排](https://learn.microsoft.com/en-us/microsoft-copilot-studio/advanced-generative-actions) · [Anthropic 的 Workflow 与 Agent](https://www.anthropic.com/engineering/building-effective-agents) · [Anthropic Tool Search](https://www.anthropic.com/research/advanced-tool-use)

| 任务 | 可以选的路径 | 选它的理由 |
| --- | --- | --- |
| 查询会议室、查订单状态 | `DIRECT` | 已知业务意图到工具的映射，参数齐全后可直接按绑定执行 |
| 退款、正式付款、需多步审核的提交 | 固定 SOP，或使用经网关审批的单工具路由 | 提交、核对、批准等步骤和业务限制可以明确写进契约 |
| 分析报销积压原因 | 受约束的 Planner | 根据可用工具和证据生成计划，之后按 DAG 执行和验收 |
| 跨系统排查，需要根据当前结果再找工具 | A | 模型可在询问和执行之间继续搜索或查看契约 |
| 需求已经能缩小到一个业务域，目录工具又很多 | B | 应用先筛出本轮候选，模型只需看少量具体 Schema |

学习时可以从会议室开始，把**查路由表 → 查工具目录 → 执行 HTTP**看透。再看 OA 报销，确认同一个工具从参数待补齐、审批挂起到批准续跑的状态变化。最后再学开放式工具发现和 Planner，这时更容易看清哪些是选工具方式，哪些是任意入口都应经过的网关规则。
