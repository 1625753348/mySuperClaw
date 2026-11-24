# 从 Definition、规则到 SOP 和 DAG

用户输入 `/refund SO001` 时，系统要依次解决几个问题。用户想退款还是查订单？少了哪个字段？这个请求能否执行？如果要执行，先检查资格还是先提交退款？这些问题分别交给意图定义、参数 Schema、策略和固定 DAG。

| 概念 | 在项目中怎么用 |
| --- | --- |
| **Intent Definition** | 用 `领域.动作@版本` 标识一种业务请求。例如 `ORDER.REFUND@1` 定义退款申请。它关联参数契约，入口可以凭它识别和补参。 |
| **参数 Schema** | JSON Schema 描述对象有哪些字段、哪些必填、类型及取值约束。退款的入口要求 `orderId` 和 `reason`。任务保存的是校验过的值和来源。 |
| **业务规则** | 为匹配到的意图附加准入约束、参数限制或追问。例如租户的订单查询规则要求订单号符合格式，且提交者具有运营角色。 |
| **SOP** | 业务人员认可的标准步骤。退款 SOP 规定查资格、判断是否可退、申请审批并提交退款。 |
| **Skill** | 把可复用能力注册和版本化的发布单元。`SKILL.md` 的正文给人读，声明清单给编译器读。它声明触发方式、Definition、入参、固定 DAG、工具版本和准入策略引用。 |
| **固定 DAG** | 由 SOP 作者声明节点、依赖和参数绑定。发布后的版本可以被命令、会话绑定、监控审批和受配置约束的 Planner 复用。 |
| **动态 DAG** | 由 Planner 根据开放式目标和允许的工具生成。服务端校验后发布成可执行 Plan，运行时按依赖调度。 |

## 退款请求的两轮执行

1. **第一轮发 `/refund SO001`。** 规则命中显式命令，Registry 找到已发布的 `order.refund` 版本。Skill 声明的 `ORDER.REFUND@1` 和输入 Schema 确定当前缺 `reason`。回执保留 Task、已选 Skill 和追问，暂未创建 Run。
2. **第二轮回复「重复下单」。** Context Resolver 根据回复引用关联原 Task，合并新字段，按原契约校验后保存新版 Task Input 以及 `ValidatedIntentIR`。审核期间发布新 Skill 版本也不会替换本任务已经选定的版本。
3. **执行固定图。** Decision 选择 `WORKFLOW`，Run Manager 将已发布 DAG 复制成此次 Run 的 Plan 和 Step。`eligibility` 先查资格，`check_eligibility` 读取它的结果，`submit_refund` 等前两步通过才有机会受理。
4. **工具权限和审批。** ToolGateway 核对实际工具版本、租户和执行权限，再按工具输入 Schema 检查提交参数。高风险退款节点挂起等待独立审批，批准后同一个 Run 续跑。

这一条链路的源码起点是 [订单退款 Skill](../../skills/order-refund/SKILL.md)、[SkillCompiler](../../modules/workflow-skill/src/main/java/com/example/enterpriseagent/skill/compiler/SkillCompiler.java) 和 [RunManager](../../modules/agent-runtime/src/main/java/com/example/enterpriseagent/runtime/RunManager.java)。

## Definition Schema、Skill Schema 和工具 Schema

| 契约 | 谁消费 | 退款里的例子 |
| --- | --- | --- |
| 意图参数 Schema | Intent Engine、定义注册表 | 退款申请需要订单号和原因 |
| Skill 的 `inputSchema` | Skill 编译与发布、固定工作流路由 | 固定图接收 `orderId` 和 `reason`，并将字段传给节点。发布时核对所引用的 Definition 版本 |
| 网关工具的输入和输出 Schema | Skill 发布校验和 ToolGateway 调用 | 查资格工具只接收 `orderId`，输出布尔字段 `refundable`。提交退款工具接收两个字段并返回业务回执 |

普通订单查询可以只有 Definition 和工具绑定。系统从 Definition 取得查询参数，校验后走 `DIRECT`。开放式销售分析先确定目标和范围，选择 `PLAN_AND_EXECUTE` 后才让 Planner 生成 DAG。一个动态计划也可以在授权允许时使用已发布 Skill 内的固定步骤。

## YAML、预定义规则与 Skill 清单

Spring Boot 部署 YAML 配置租户的意图路由绑定，例如某个意图用哪个工具版本、固定 Skill 或 Planner 策略。控制台的「预定义规则」通过管理 API 发布规则版本到 PostgreSQL，目前针对低风险 `DIRECT` 意图提供参数约束、追问和角色限制。Skill 文档顶部可以有 YAML 元数据，当前编译器读取的是正文中的 JSON 围栏。三者的入口和生命周期各自独立。

看一轮请求时，可以按 `IntentCandidate → ParameterSchema → SlotState → ValidatedIntentIR → RouteDecision → PlanVersion → ToolResult` 顺序展开调试页右栏。简单查询通常只需要单次工具调用。SOP 或开放式任务进入 Runtime 后，可以继续查看节点依赖、审批与结果。

[参数从哪里来](../../design/intent-parameter-sources.md) · [会话路由配置](decision.md) · [预定义规则发布](operations.md#预定义规则) · [Skill 发布](workflow-skill.md) · [动态规划](general-planner.md)
