# 业务级 Hard-Gate 实现讲解

Hard-Gate 位于通用 Planner 的 DAG 出口。最终节点产生结果后，运行时从检查点恢复结果，在交付前使用本次 Run 固定的业务策略逐项验收。成功结果必须经过验收通过的迭代。

## 已实现的验收

| 检查 | 作用 | 示例 |
| --- | --- | --- |
| `required-fields` | 检查结果字段存在且有内容 | 财务结果必须有 `answer` |
| `MATCH_INPUT` | 将结果字段与同一版本的已校验输入比较 | `reportPeriod` 与 Task 的 `period` 一致 |
| `MIN_NUMBER` | 检查数字达到下限 | `evidenceCount` 至少为 2 |
| `MIN_ITEMS` | 检查数组元素数达到下限 | 来源数组至少包含两项 |
| `EQUALS_TEXT` | 检查文本与配置值完全一致 | `format` 为 `markdown` |
| `IS_TRUE` | 检查字段是布尔值 true | `verified` 为 true |

`MATCH_INPUT` 使用服务端固定的 Task 输入版本，数字按数值比较。其他规则读取最终结果声明的字段。若要核实证据是否来自指定系统，应由业务工具和输出契约保证来源，再配置对应的验收字段。

## 从规划到交付

1. 租户为意图配置 `business-checks`。Run 受理时将规则和预算快照存进 `runtime.run.budget_limits`。在同一个 Run 中重新规划仍沿用这一份配置。
2. Planner 校验最终节点的输出 Schema。业务验收字段必须在 Schema 中声明，`MATCH_INPUT` 关联的槽位必须出现在已校验的 Intent IR。通过后，运行时在 DAG 最后加入 `acceptance` 节点。
3. 工具正常结算后，验收节点读取产物并执行确定性规则。每项的判定保存在 `runtime.gate_check`，每轮结论保存在 `runtime.iteration`。失败原因保存在验收产物，供会话与 SSE 查看。
4. 还有预算时，系统向 Planner 提供原目标、允许的工具、规则和失败码，重新规划并发布新一版 DAG。仍然失败且预算耗尽时，Run 以 `HARD_GATE_BUDGET_EXHAUSTED` 结束。通过时，Run 记录交付结果引用。

以「分析上季度财务报表」为例，首轮摘要有内容，但报告写成本季度，且证据计数只有 1。验收分别产生 `INPUT_VALUE_MISMATCH` 和 `BELOW_MINIMUM`。新计划修正周期并取得足够证据后才交付。若报表持续使用错误周期，结果接口返回 `404`。

## 怎么查

- 会话右栏的 `PlanVersion` 中查看 `acceptance` 节点。`StateUpdate.gateChecks` 显示每一轮的检查项、结论和失败原因。
- 订阅 Run 的 SSE 可以读取 `run.gate_evaluated` 和 `run.replan_requested`，再对照新计划版本。
- 业务规则可以与 SubAgent 降级同时使用。降级产物要先满足专员输出契约，汇总后的结果仍经过主 DAG 的 Hard-Gate。

业务 Hard-Gate 当前应用于通用规划 DAG。持续监控的阈值属于每轮观测判定，使用自己的监控配置和轮次历史。

代码入口包括 [BusinessGate](../../modules/agent-runtime/src/main/java/com/example/enterpriseagent/runtime/planner/BusinessGate.java) 的规则定义与求值、[GeneralPlanner](../../modules/agent-runtime/src/main/java/com/example/enterpriseagent/runtime/planner/GeneralPlanner.java) 的契约和 DAG 校验，以及 [RunManager](../../modules/agent-runtime/src/main/java/com/example/enterpriseagent/runtime/RunManager.java) 的验收结算、重规划和结果发布。

[Planner 配置示例](general-planner.md#配置业务验收) · [集成测试](../../scripts/runtime-smoke.mjs)
