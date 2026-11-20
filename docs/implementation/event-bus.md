# event-bus 事件投递与消费

[EventBusStore](../../modules/event-bus/src/main/java/com/example/enterpriseagent/eventbus/outbox/EventBusStore.java) 定义信封和投递端口。[EventBusMapper](../../modules/infrastructure/src/main/java/com/example/enterpriseagent/infrastructure/runtime/EventBusMapper.java) 继承该端口。[RuntimeEventPump](../../modules/infrastructure/src/main/java/com/example/enterpriseagent/infrastructure/runtime/RuntimeEventPump.java) 通过 Worker Profile 启动，处理 `agent:events:run` 和 `agent:events:tool-execution`。

## 一次投递怎么流转

1. 业务事务将状态变化与 Outbox 一起提交。Relay 在事务之外投递 Redis Streams。
2. Relay 领取数据库租约，`XADD` 后按租约确认。失败则延迟重试，同一 event ID 可重复发布。
3. `XREADGROUP` 接收新消息。`XPENDING` 和 `XCLAIM` 接管遗留记录。消费者在同一事务写 Inbox 并把已关联工具节点的回查时间提前，再发 `XACK`。收到事件后仍由 Worker 查询网关权威状态。
4. SSE 从 `runtime.run_event` 补发，Worker 从 Run、Step 和 Checkpoint 恢复。Redis 不可用期间 PostgreSQL 的轮询仍可推进任务，SSE 也能按序读库。

Run 和 Tool Execution 事件投递满 12 次后进入重试耗尽列表。运维可按原事件 ID 重投，消费者仍用 Inbox 去重。保留期裁剪、通用 Skill 事件消费及外部系统回调需要单独接入。[运维操作](operations.md) · [投递协议与故障恢复](../../design/eventbus.md)
