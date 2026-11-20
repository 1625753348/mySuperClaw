# decision 准入与执行路由

[DecisionService](../../modules/decision/src/main/java/com/example/enterpriseagent/decision/DecisionService.java) 读取已经校验并保存版本的 Validated Intent IR，为已绑定意图生成 `RouteDecision`。路由使用服务端配置中的工具名称和固定版本。提交 `DIRECT` 要求 `tools.invoke`，提交销售分析另需 `runs.submit`。

| 意图 | 模式 | 已接入的执行 |
| --- | --- | --- |
| `ORDER.QUERY` | `DIRECT` | 把 `orderId` 交给 ToolGateway 登记的只读订单工具。默认工具名 `orders_lookup`、版本 `1`，可用 `ENTERPRISE_INTENT_ORDER_TOOL` 和 `ENTERPRISE_INTENT_ORDER_VERSION` 修改。 |
| `SALES.ANALYZE` | `PLAN_AND_EXECUTE` | 补齐 `period` 后提交销售分析 Run。启用 `runtime`，设置 `ENTERPRISE_INTENT_SALES_TOOL`、`ENTERPRISE_INTENT_SALES_VERSION` 并注册接受 `period` 的工具。销售报告默认要求地区、产品、客户三个维度，沿用长任务的 Planner、Ralph 和 SSE。 |

已有库先执行 [006 迁移](../../database/migrations/006-intent-dispatch.sql)。两个模式都使用持久化网关作业，需要配置 `ENTERPRISE_GATEWAY_JOB_KEY`。DIRECT 自动由网关调度消费。销售分析另需运行独立的 Runtime Worker。现有独立工具 API 和结构化长任务 API 沿用原入口。[网关配置](tool-gateway.md#策略与续跑) · [Worker 启动](agent-runtime.md#本地运行)

[PostgresIntentDispatch](../../modules/infrastructure/src/main/java/com/example/enterpriseagent/infrastructure/intent/PostgresIntentDispatch.java) 将会话轮次、执行关联及入队在同一数据库事务提交。拒绝时回滚单个候选的入队并保存原因。重复提交同一个幂等键返回原轮次和执行 ID，可以读取新的进度。读取同一轮的 GET 时校验会话归属和结果权限。工具结果从网关实时读取并应用脱敏，由 `executions[].output` 返回。Run 的状态随回执返回，报告和 SSE 可以用 `resultUrl` 和 `eventsUrl` 获取。历史已完成的识别回执在部署后仍可以读取。

退款类固定 SOP 依赖 Skill 发布和固定 DAG 执行，当前尚未接入。未绑定的候选继续在会话返回其识别结论。[架构中的路由模式](../../design/architecture.md#按请求类型选择执行模式)
