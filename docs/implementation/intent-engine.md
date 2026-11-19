# intent-engine 意图识别与上下文

当前已有 [routing](../../modules/intent-engine/src/main/java/com/example/enterpriseagent/intent/routing/package-info.java)、[context](../../modules/intent-engine/src/main/java/com/example/enterpriseagent/intent/context/package-info.java)、[definition](../../modules/intent-engine/src/main/java/com/example/enterpriseagent/intent/definition/package-info.java) 三个包的职责说明。L0～L3、候选评估持久化和槽位处理待实现。已有的 [memory](memory.md) 库提供会话窗口与摘要的数据访问。

## 设计中的实现关系

1. `routing` 从归一化输入生成意图候选。明确 Skill Key 或命令可由 L0 命中；其它请求按四档选择检索、小模型或 LLM。这一层输出候选和识别依据。
2. `context` 中的 Assembler 计划读取最近消息、活动任务和待澄清记录。Resolver 使用快照为候选判断新建、续办、修改、取消或歧义。例如「订单号 SO001」可关联到上一轮待补参的退款任务。
3. `definition` 计划按固定定义版本组合参数。缺字段时产生澄清，校验完成后把 Validated Intent IR 交给 [decision](decision.md)。

这里的主要关系是处理步骤之间的组合与输入输出传递。每个 Turn 可以有多个候选，候选独立处理。[意图路由与四档策略](../../design/routing.md) · [多轮示例](../../design/walkthrough.md)
