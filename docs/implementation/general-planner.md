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
            max-nodes: 3
            max-iterations: 2
```

通用入口要求意图已经完成补参并准入为 `AGENT`。配置的 `goal` 和 IR 的槽位名发给模型，槽位值从原 Task 输入版本按引用读取。发布后给每个工具节点保存工具版本 UUID。闲聊、DIRECT、已命中的固定 SOP 和现有销售报告路线继续由各自的入口处理。

## 财务报表的执行过程

1. 用户说「分析上季度财务报表」，Intent Engine 写入 `FINANCE.REPORT`、`period=上季度` 和输入版本。Run 受理时固定该输入版本与租户配置，并提交初始 PlanProposal。
2. Planner 取得当前身份可发现、可执行的工具及其契约。模型提出 `evidence → summary`，`evidence` 绑定 `input.period`，`summary` 绑定 `steps.evidence.output.evidence`。发布校验通过后追加 `acceptance`，形成可执行的 PlanVersion。
3. Worker 执行 `evidence`，在检查点记录结果引用。`summary` 在其前置完成后进入就绪状态，从检查点读取证据并调用已绑定版本的工具。重领原节点沿用原操作键。
4. Hard-Gate 根据 `required-fields` 检查结果中的缺失、空值、空白文本和空集合。第一次摘要若为空，验收记录写入 `runtime.gate_check`。预算允许时，下一次模型调用使用原目标、槽位名、重新检查过的候选工具和上轮缺失字段组装 Fresh Context，由服务端校验并发布新版本。新版本的 Step 有新的操作键。
5. 当必需字段全部通过，Run Manager 写入通过的迭代及结果引用。按会话轮次访问 `/v1/conversations/{conversationId}/turns/{turnId}/artifacts` 可查看 PlanProposal、PlanVersion、节点依赖、ToolCall、ToolResult、验收记录和状态。SSE 可查看版本切换与完成事件。

该模式的 Hard-Gate 验证结果字段的可用性。面向具体业务的正确性校验、SOP 作为动态 DAG 节点、SubAgent 节点和持续监控 Agent Loop 需要对应的执行契约。
