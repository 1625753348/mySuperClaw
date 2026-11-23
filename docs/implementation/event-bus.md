# event-bus 事件投递与消费

[EventBusStore](../../modules/event-bus/src/main/java/com/example/enterpriseagent/eventbus/outbox/EventBusStore.java) 定义信封和投递端口。[EventBusMapper](../../modules/infrastructure/src/main/java/com/example/enterpriseagent/infrastructure/runtime/EventBusMapper.java) 继承该端口。[RuntimeEventPump](../../modules/infrastructure/src/main/java/com/example/enterpriseagent/infrastructure/runtime/RuntimeEventPump.java) 在 `storage,gateway,runtime,worker` 下处理 Run、Tool Execution 和 Workflow Skill 三条 Redis Stream。已有库可执行 [016 迁移](../../database/migrations/016-event-bus-retention.sql) 增加清理索引。

## 一次投递怎么流转

1. 业务事务将状态变化与 Outbox 一起提交。Relay 在事务之外投递 Redis Streams。
2. Relay 领取数据库租约，`XADD` 后按租约确认。失败则延迟重试，同一 event ID 可重复发布。
3. `XREADGROUP` 接收新消息。`XPENDING` 和 `XCLAIM` 接管遗留记录。消费者用事件 ID 回查 Outbox，再写 Inbox。工具事件提前安排关联节点回查网关，SOP 事件预热版本缓存。事务提交后才发 `XACK`。
4. SSE 从 `runtime.run_event` 补发，Worker 从 Run、Step 和 Checkpoint 恢复。Redis 不可用期间 PostgreSQL 的轮询仍可推进任务，SSE 也能按序读库。

## SOP 缓存怎么同步

发布事务同时更新当前版本、命令触发映射和 `WorkflowSkillPublished` Outbox。Worker 消费时按租户和 Skill ID 查询当前版本，把不可变的编译 DAG 写入 Redis。每个键包含租户和版本 UUID。旧事件迟到可预热当前版本，旧版本仍能按版本号读取。每次查版本或命令先从 PostgreSQL 确认映射和状态，再读取 DAG 缓存。未命中或 Redis 不可用时从 PostgreSQL 取正文。消费阶段遇到 Redis 错误时回滚 Inbox，等待消息被接管。缓存写入可安全重试。

## 保留与重投

| 设置 | 默认值 | 行为 |
| --- | --- | --- |
| `enterprise.event-bus.skill-cache-ttl` | `30m` | DAG 缓存 TTL，过期后按需回源 |
| `enterprise.event-bus.stream-retention-days` | 7 天 | Redis 6.2+ 按时间分批裁剪，每条 Stream 每批最多 500 条。所有消费组的最早 Pending 和最后投递位置共同限制裁剪边界 |
| `enterprise.event-bus.database-retention-days` | 30 天 | 按最近投递时间保留。每批最多删除 500 条已经投递且有 Inbox 的 Outbox 及对应 Inbox |
| `enterprise.event-bus.retention-interval-ms` | 60000 | 清理任务间隔 |

运维可查看三类事件的已投递和重试耗尽记录。投递重试上限为 12 次，重投沿用业务事件 ID，并生成新的 Redis 消息 ID。保留期内 Inbox 去重，清理后的残留消息因查不到 Outbox 而直接确认。未投递、重试耗尽、未消费的事件留在 PostgreSQL 供排查。清理只涉及这三条 Stream 及对应的 Outbox/Inbox，SSE 使用的 `runtime.run_event` 保留独立。外部系统回调需要单独接入。[运维操作](operations.md) · [投递协议与故障恢复](../../design/eventbus.md)

使用独立测试库和 Redis 逻辑库运行 `node scripts/runtime-smoke.mjs <JAR> --sop-events-only`，可验收 SOP 版本发布、事件投递、旧事件重投以及命令解析继续使用当前版本。
