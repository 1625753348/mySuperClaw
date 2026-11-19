# agent-runtime 计划与执行

当前源码包括 [planner](../../modules/agent-runtime/src/main/java/com/example/enterpriseagent/runtime/planner/package-info.java)、[execution](../../modules/agent-runtime/src/main/java/com/example/enterpriseagent/runtime/execution/package-info.java)、[gate](../../modules/agent-runtime/src/main/java/com/example/enterpriseagent/runtime/gate/package-info.java)、[checkpoint](../../modules/agent-runtime/src/main/java/com/example/enterpriseagent/runtime/checkpoint/package-info.java) 的包说明。规划、节点调度、LangGraph4j Executor 和 Ralph 验收待接入。实际可读写的工作记忆和 Saver 已在 [memory](memory.md) 与 [infrastructure](infrastructure.md) 实现。

| 包 | 设计中的输入和输出 |
| --- | --- |
| `planner` | L4 读取已准入的目标与上下文，生成可校验的动态 PlanProposal |
| `execution` | Run Manager 调度单步、固定 SOP 或动态计划的就绪节点，执行器产出观察结果、错误或等待状态 |
| `gate` | 检查交付条件。失败可修复且仍有预算时，触发下一轮 Ralph |
| `checkpoint` | 和运行状态提交协作，按 Run、计划与图线程保存或恢复快照与产物引用 |

设计上执行器可通过 `WorkingMemory` 访问 `CheckpointSaver`。实际的 Saver 已支持同一 Run 事务中的快照和产物绑定写入，恢复时匹配当前有效计划及格式。下一阶段由 Run Manager 把存取动作接入实际运行生命周期。[Planner 与 DAG](../../design/planner.md) · [长任务与恢复](../../design/eventbus.md)
