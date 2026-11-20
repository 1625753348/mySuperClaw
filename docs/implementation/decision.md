# decision 准入与执行路由

[DecisionService](../../modules/decision/src/main/java/com/example/enterpriseagent/decision/DecisionService.java) 读取已经校验并保存版本的 Validated Intent IR，为已绑定意图生成 `RouteDecision`。路由使用服务端配置中的工具名称和固定版本。提交 `DIRECT` 要求 `tools.invoke`，提交销售分析另需 `runs.submit`。

| 意图 | 模式 | 已接入的执行 |
| --- | --- | --- |
| `ORDER.QUERY` | `DIRECT` | 把 `orderId` 交给 ToolGateway 登记的只读订单工具。默认工具名 `orders_lookup`、版本 `1`，可用 `ENTERPRISE_INTENT_ORDER_TOOL` 和 `ENTERPRISE_INTENT_ORDER_VERSION` 修改。 |
| `SALES.ANALYZE` | `PLAN_AND_EXECUTE` | 补齐 `period` 后提交销售分析 Run。启用 `runtime`，设置 `ENTERPRISE_INTENT_SALES_TOOL`、`ENTERPRISE_INTENT_SALES_VERSION` 并注册接受 `period` 的工具。销售报告默认要求地区、产品、客户三个维度，沿用长任务的 Planner、Ralph 和 SSE。 |

已有库先执行 [006 迁移](../../database/migrations/006-intent-dispatch.sql)。两个模式都使用持久化网关作业，需要配置 `ENTERPRISE_GATEWAY_JOB_KEY`。DIRECT 自动由网关调度消费。销售分析另需运行独立的 Runtime Worker。现有独立工具 API 和结构化长任务 API 沿用原入口。[网关配置](tool-gateway.md#策略与续跑) · [Worker 启动](agent-runtime.md#本地运行)

[PostgresIntentDispatch](../../modules/infrastructure/src/main/java/com/example/enterpriseagent/infrastructure/intent/PostgresIntentDispatch.java) 将会话轮次、执行关联及入队在同一数据库事务提交。拒绝时回滚单个候选的入队并保存原因。重复提交同一个幂等键返回原轮次和执行 ID，可以读取新的进度。读取同一轮的 GET 时校验会话归属和结果权限。工具结果从网关实时读取并应用脱敏，由 `executions[].output` 返回。Run 的状态随回执返回，报告和 SSE 可以用 `resultUrl` 和 `eventsUrl` 获取。历史已完成的识别回执在部署后仍可以读取。

退款和 CRM 复盘的固定 SOP 已接入。未绑定的候选继续返回识别结论。[固定 Skill 执行](workflow-skill.md) · [架构中的路由模式](../../design/architecture.md#按请求类型选择执行模式)

## 租户路由、候选依赖和对话回复

部署可以用 `enterprise.intent.routing.bindings` 将特定租户的意图定义版本绑定到已注册工具或发布后的 Skill。原有订单和销售配置仍可使用。相同租户的显式绑定优先。固定 Skill 资源继续使用现有 Runtime Worker。

```yaml
enterprise:
  intent:
    routing:
      bindings:
        - tenant: company-a
          intent: CONTRACT.QUERY
          mode: DIRECT
          definition-version: 1
          tool-name: contract_lookup
          tool-version: "1"
          predecessor: ORDER.QUERY
          parameters:
            "[contractNumber]": result.contractId
        - tenant: company-a
          intent: CALENDAR.LIST
          mode: WORKFLOW
          definition-version: 1
          skill-key: calendar.list
      knowledge:
        - tenant: company-a
          tool-name: policy_search
          tool-version: "1"
```

普通 `DIRECT` 使用 `slot.<字段>` 或裸槽位名把业务输入映射到工具参数。带 `predecessor` 的绑定要求同一轮有一个明确的前置候选，它必须处在当前候选之前，并通过 `DIRECT` 成功取得结构化结果。允许用 `result.<字段>` 映射顶层字段。缺失字段、失败或未获授权的前置结果会阻止后续调用。网关仍按当前工具授权、输入 Schema 和审批策略受理。

配置型 `WORKFLOW` 用于低风险只读 Skill，其准入策略为 `workflow.read`、工具输出声明为 `GenericResult`。系统核对对应意图的版本和 Skill 契约，首次选中后持久固定该 Skill 版本。退款与 CRM 保留自身策略及上下文解析。动态规划目前仍由已接入的销售分析处理。

- `CHAT` 通过 `ENTERPRISE_INTENT_CHAT_ENABLED=true` 和 `ENTERPRISE_INTENT_CHAT_API_KEY` 等参数启用 LangChain4j 对话模型。仅发送本轮单独路由为闲聊的文本。
- `KNOWLEDGE` 将问题作为 `query` 交给该租户配置的工具，要求 `tools.invoke` 及具体工具授权。工具契约应返回 `answer` 和 `sources`，页面显示答案和引用。

[009 迁移](../../database/migrations/009-conversation-route-jobs.sql) 增加 `intent.route_job`。候选、派发作业与 Turn 一起提交，后台按租约推进。工具作业沿用网关的稳定幂等键，读取前置结果或知识答案时复核可见性。页面轮询原轮次的产物，不需要重新发送消息。
