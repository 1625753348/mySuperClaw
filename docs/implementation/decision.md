# decision 准入与执行路由

当前源码提供 [policy](../../modules/decision/src/main/java/com/example/enterpriseagent/decision/policy/package-info.java) 和 [routing](../../modules/decision/src/main/java/com/example/enterpriseagent/decision/routing/package-info.java) 两个包的边界。策略实现和执行路由服务待接入。

设计中，`policy` 接收已校验意图和可信身份，按权限、业务风险及参数作出允许、拒绝或需要确认的决策。准入记录的表是 `intent.route_decision`。允许后，`routing` 根据业务定义选择 `DIRECT`、`WORKFLOW` 或 `PLAN_AND_EXECUTE`。查订单走单步，明确的退款 SOP 走固定 DAG，开放式分析才需要动态计划。

这两个职责通过决策结果衔接。运行时接收已经准入的执行模式，工具调用时 [ToolGateway](tool-gateway.md) 仍按具体工具、参数和实时授权复核。[架构中的路由模式](../../design/architecture.md#按请求类型选择执行模式)
