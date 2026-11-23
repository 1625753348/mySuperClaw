# 通用 Planner 与任务 DAG

会话中的一个业务意图落为一个 Task。Task 的已校验输入版本关联一个 Run，Run 有自己的 PlanVersion。Planner 把目标拆成多个 Step，并用依赖边描述哪些 Step 必须先完成。一个 Step 可以消费其直接依赖的输出字段。没有依赖关系的就绪 Step 可以由多个 Worker 并行领取。

| 模块 | 负责的交接产物 | 代码入口 |
| --- | --- | --- |
| Intent Engine 与路由 | 已校验的 Intent IR、执行模式、Task 与输入版本 | `PostgresIntentDispatch.generalRoute` |
| Planner | 候选 DAG、工具选择、节点输入引用、结果节点 | `GeneralPlanner.propose` |
| 发布校验 | 检查工具版本、节点预算、循环、必填字段、依赖和输出引用，加入 Hard-Gate | `GeneralPlanner` |
| Executor | 根据已发布节点恢复输入，调用 ToolGateway，把结果写入 Observation | `RunManager.executeFixed` |
| Run Manager | 领单、租约、节点状态、检查点、事件、计划版本、取消与恢复 | `RunManager`、`RunWorker` |
| ToolGateway | 逐次复核权限、参数、审批、配额和调用治理，处理执行幂等 | `GatewayRunTool` |

## 启用某个开放式意图

下面的配置假设同租户已发布两个具备对象形态输入输出契约的工具。给实际调用者配置工具的 DISCOVER、EXECUTE 权限，以及会话和 Run 凭证所需的 scopes。把可重跑的只读工具列入策略的 `tools`，并在网关标为 LOW。

```yaml
enterprise:
  runtime:
    general-planner:
      enabled: true
      base-url: ${PLANNER_BASE_URL}
      api-key: ${PLANNER_API_KEY}
      model: ${PLANNER_MODEL}
  intent:
    routing:
      planners:
        - tenant: demo
          intent: FINANCE.REPORT
          definition-version: 1
          policy:
            goal: 汇总财务证据并生成财务摘要
            tools: [finance_lookup, finance_summary]
            required-fields: [answer]
            business-checks:
              - id: reportPeriod
                field: reportPeriod
                operator: MATCH_INPUT
                input-field: period
              - id: evidenceCount
                field: evidenceCount
                operator: MIN_NUMBER
                minimum: 2
            max-nodes: 3
            max-iterations: 2
```

通用入口要求意图已经完成补参并准入为 `AGENT`。配置的 `goal` 和 IR 的槽位名发给模型，槽位值从原 Task 输入版本按引用读取。发布后给每个工具节点保存工具版本 UUID。闲聊、DIRECT、已命中的固定 SOP 和现有销售报告路线继续由各自的入口处理。

## 财务报表的执行过程

1. 用户说「分析上季度财务报表」，Intent Engine 写入 `FINANCE.REPORT`、`period=上季度` 和输入版本。Run 受理时固定该输入版本与租户配置，并提交初始 PlanProposal。
2. Planner 取得当前身份可发现、可执行的工具及其契约。模型提出 `evidence → summary`，`evidence` 绑定 `input.period`，`summary` 绑定 `steps.evidence.output.evidence`。发布校验通过后追加 `acceptance`，形成可执行的 PlanVersion。
3. Worker 执行 `evidence`，在检查点记录结果引用。`summary` 在其前置完成后进入就绪状态，从检查点读取证据并调用已绑定版本的工具。重领原节点沿用原操作键。
4. Hard-Gate 先检查 `required-fields`，再验收 `business-checks`。例如摘要虽然有内容，报表周期却错了，或工具返回的证据数小于 2，Run 都会记录失败。预算允许时，下一次模型调用使用原目标、槽位名、候选工具、验收规则与失败码组装 Fresh Context。服务端校验新计划后发布新版本。新版本的 Step 有新的操作键。
5. 所有验收项通过后，Run Manager 写入通过的迭代及结果引用。按会话轮次访问 `/v1/conversations/{conversationId}/turns/{turnId}/artifacts` 可查看 PlanProposal、PlanVersion、节点依赖、ToolCall、ToolResult、验收记录和状态。SSE 可查看版本切换与完成事件。

### 配置业务验收

验收范围和失败后的处理过程见 [业务级 Hard-Gate 讲解](business-hard-gate.md)。

通用计划在受理时将规则随策略保存到 `runtime.run.budget_limits`，续跑沿用同一策略。Planner 产出的最终节点必须声明规则要求的字段。验收节点从持久化产物读输出，并使用固定输入版本绑定 `MATCH_INPUT`。规则按各自 ID 写入 `runtime.gate_check`，失败码可以在会话产物 `StateUpdate.gateChecks` 和 SSE 中查看。没有通过且预算用尽时，Run 失败，结果地址返回 404。

| 运算符 | 比较条件 | 参数 |
| --- | --- | --- |
| `MATCH_INPUT` | 输出字段与已校验的 Task 槽位相等 | `input-field` |
| `MIN_NUMBER` | 输出数字大于等于阈值 | `minimum` |
| `MIN_ITEMS` | 输出数组元素个数达到阈值 | 整数 `minimum` |
| `EQUALS_TEXT` | 输出文本与固定值完全一致 | `expected` |
| `IS_TRUE` | 输出字段是布尔值 true | — |

例如 `MIN_NUMBER` 检验工具结果声明的计数。若业务要求独立核对证据源，应在发布工具和输出契约时明确证据来源。

## 在动态 DAG 里复用已发布的 SOP

给已绑定只读 SOP 的意图配置一个 `workflow-trigger`，让符合条件的请求进入 Planner。比如在 `enterprise.intent.routing.planners` 中添加以下条目。

```yaml
- tenant: demo
  intent: PROJECT.RETROSPECTIVE
  definition-version: 1
  workflow-trigger: 再生成管理汇总
  policy:
    goal: 先运行项目复盘 SOP，再将 Markdown 交给管理汇总工具
    tools: [retro_summary]
    required-fields: [summary]
    max-nodes: 5
    max-iterations: 1
```

用户说「帮我复盘最近一个月的『CRM系统重构』项目再生成管理汇总」。Intent Engine 先按已发布 SOP 校验参数，固定 Skill 版本、已授权仓库、分支和时间窗口。若第一轮缺少项目名，补参轮可以沿用同一 Task 原始请求的组合触发语。命令入口如 `/github-retro` 继续直接走固定 DAG。

Planner 收到 Skill 的标识、固定版本和输出契约，提出一个 `retro` 节点，并把汇总工具的输入绑定为 `steps.retro.output.markdown`。服务端展开发布时编译好的 SOP，保留已固定的工具版本和依赖，只有 SOP 的出口使用 `retro` 这个逻辑节点 ID。配额按展开后的节点数计算，系统追加的验收节点单独计入运行。

实际依赖为 `issues + commits → findings → retro → summary → acceptance`。取证节点遇到审批时在同一个 Run 内挂起。批准后仍执行原来的版本，汇总节点等待复盘成功才读取 Markdown。轮次的 `PlanVersion.nodes` 会显示 `skillVersion` 和 `skillNode`，可对照 `PlanProposal` 中的逻辑 DAG 查看。

当前支持每个计划复用一个从 Task 输入绑定的只读 SOP。服务端检查内部工具的固定版本和权限。[持续监控 Agent Loop](monitor-loop.md) 使用独立的周期和状态。

### 用前置节点输出给 SOP 传参

对通过 `workflow.read` 发布的 SOP，可在其租户的组合规划策略中显式增加 `skill-input-fields`，例如 `[city]`。原 Task 仍按原意图和已发布版本完成入参校验。Planner 只可用已声明的直接依赖结果覆盖允许的字段。

```json
{
  "id": "forecast",
  "skill": "weather.forecast",
  "dependsOn": ["normalize"],
  "inputs": { "city": "steps.normalize.output.city" }
}
```

例如用户提供北京，前置节点解析出服务地区上海。运行时等前置节点成功后，将上海合入原 Task 参数，再按发布版本的完整输入 Schema 校验。通过后，SOP 工具从该节点的持久化产物读取合并参数。原 Task 的 Intent IR 仍保留北京。

前置结果缺少绑定值时记录 `SKILL_INPUT_REFERENCE_MISSING`，合并后不符合契约时记录 `SKILL_INPUT_SCHEMA_INVALID`。这两种情况下 SOP 工具都保持未派发。会话右栏的 `PlanVersion.nodes` 可查看 `skillNode` 为 `$input` 的校验节点、输入引用和失败原因。该校验节点占用展开后的节点预算。

没有配置上游字段的 SOP 仍可走原有固定流程。具有单独仓库和取证窗口准入规则的 GitHub 复盘，继续使用受理时固定的范围。

## 委派 SubAgent

已有数据库执行 [013 迁移](../../database/migrations/013-subagent-runs.sql)。在目标租户的通用规划策略配置专员名称和登记的工具。

```yaml
subagents:
  - name: regional
    tool: finance_lookup
  - name: control
    tool: finance_check
```

Planner 从已授权的专员名称及契约生成 `subagent` 节点。节点和普通工具使用相同的槽位及依赖引用，例如 `input.period`，汇总节点可以绑定 `steps.check.output.verification`。服务端绑定具体工具版本后发布计划。每个 Run 的一个计划最多允许四种专员，同一专员只出现一次。

节点首次执行时创建独立子 Run，并在同一事务写入 `runtime.child_run`。租约接管会复用原有子 Run。无依赖的委派节点可以并行，汇总节点等依赖都成功才继续。审批由子 Run 挂起并继续，取消父 Run 会向运行中的子 Run 传递取消请求。子 Run 仍使用父请求的租户和调用主体，工具执行、结果回读和撤权由原有授权链校验。

会话右栏显示子 Run、状态和结果入口，父 Run 的 SSE 发出 `run.child_started`。首版的专员在子 Run 内执行一个固定版本的只读工具，委派深度为一层。

### 子任务失败后的仲裁

已有库执行 [014 迁移](../../database/migrations/014-subagent-resilience.sql)。给专员添加 `resilience` 即可配置单次子 Run 的超时和重试预算。父 Run 的剩余时间同时约束子 Run。

```yaml
subagents:
  - name: control
    tool: finance_check
    resilience:
      timeout-seconds: 15
      max-retries: 1
      on-failure: DEGRADE
      fallback:
        verification: 总账待核验
```

| 策略 | 重试耗尽后 |
| --- | --- |
| `FAIL` | 节点失败，父 Run 按既有状态机结算 |
| `DROP` | 节点以空产物完成。它只能作为顺序依赖，计划检查会拒绝读取它的字段或把它当成交付结果 |
| `DEGRADE` | 使用部署配置的结构化产物。发布前按专员输出 Schema 验证，父 Run 仍执行自己的 Hard-Gate |

默认 `FAIL`，不额外重试，期限沿用父 Run 的剩余时间。可额外重试 0 至 2 次。每次重试创建一个新子 Run，原父 Step 保持不变，重领同一次尝试复用其子 Run。只有已结算的已知技术失败进入兜底，例如无审批子 Run 的期限到期、输出 Schema 错误、熔断开启、本地容量耗尽或数据库查询失败。权限拒绝、审批路径和外部结果未知沿用现有治理。外部结果未知时，父节点失败并保留子 Run 的对账入口。

`PlanVersion.nodes` 包含 `childAttempts` 和最后的 `disposition`。SSE 记录新增子 Run 的尝试序号及重试、丢弃或降级的原因。降级内容应明确指出尚未核验的部分，便于下游生成准确的交付说明。
