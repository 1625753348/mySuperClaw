# event-bus 事件投递与消费

当前源码有 [outbox](../../modules/event-bus/src/main/java/com/example/enterpriseagent/eventbus/outbox/package-info.java) 和 [consumer](../../modules/event-bus/src/main/java/com/example/enterpriseagent/eventbus/consumer/package-info.java) 两个包的说明，Redis Streams 的 Relay 和消费服务待接入。数据库已建立 `eventing.outbox`、`eventing.inbox` 和 `eventing.delivery_failure`。基础设施中的 Redis Store 已用于会话键值和 TTL。

## 设计中的实现关系

1. 业务事务将状态变化与 Outbox 一起提交。Relay 在事务之外投递 Redis Streams。
2. 消费者用 Inbox 在处理事务里去重，成功提交后确认流消息。重复通知时由事件标识和当前业务版本约束副作用。
3. Run 进度保存在 `runtime.run_event`，按序号供 SSE 补发。步骤恢复读取 Run、Step 与 Checkpoint。

三类存储的调用对象各自明确。Streams 传事件，Run 事件表提供进度历史，Saver 读写执行快照。[投递协议与故障恢复](../../design/eventbus.md)
