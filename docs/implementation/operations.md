# 会话恢复与运行运维

已有库执行 [011 迁移](../../database/migrations/011-runtime-operations.sql)。会话恢复需要 `storage,intent`，运行运维需要 `storage,gateway,runtime`，事件重投由启用 `worker` 的实例投递。

| 操作 | API | 权限 |
| --- | --- | --- |
| 历史会话分页 | `GET /v1/conversations?limit=25&before=...` | `intents.read` |
| 历史轮次分页 | `GET /v1/conversations/{id}/turns?limit=30&before=...` | `intents.read` |
| 投递列表 | `GET /v1/admin/operations/outbox?dead=true` | `ops.read` |
| 重投单个事件 | `POST /v1/admin/operations/outbox/{eventId}/replay` | `ops.write` |
| 待对账列表 | `GET /v1/admin/operations/reconciliation` | `ops.read` |
| 核实后关闭 Run | `POST /v1/admin/operations/reconciliation/{runId}/close` | `ops.write` |

会话分页按创建时间和 UUID 排序，轮次分页按消息序号排序。客户端可用 `nextBefore` 读取更早的记录。后端按租户、用户和 Agent 校验归属，历史列表过滤已清空的轮次，选中轮次后仍按对应工具或 Run 权限读取执行结果。

管理页面中的「运行运维」可列出当前租户的投递与待对账运行。投递达到 12 次且租约失效后出现在重试耗尽列表。也可切换查看已发布事件并重投。重投保留原事件 ID 和载荷，消费者根据 Inbox 去重，正常发放新的 Redis Stream 消息 ID。

人工对账用于外部结果未知的 Run。操作者先在外部系统核对事实，再选择「关闭为失败」。服务端保留网关记录和操作键，关闭待对账节点并写入 Run 事件。重投和关闭请求都要提供 `reason`，审计随状态变更一起写入 `eventing.operator_action`。

## 预定义规则

已有库执行 [012 迁移](../../database/migrations/012-predefined-rules.sql)。使用带 `rules.admin` 的管理凭证打开「预定义规则」，选择已登记的低风险 DIRECT 意图及定义版本。填写参数 Schema、追问和允许的角色，先保存规则版本，再发布。同一意图定义只有一个已发布版本，发布新版本会归档旧版本。

例如给 `ORDER.QUERY@1` 配置订单号格式和运营角色，缺参时使用已发布的追问。参数或角色约束未通过会阻止派发，执行记录可看规则版本和原因。规则范围是当前租户下的只读约束，工具权限、审批与数据范围继续由 ToolGateway 的已发布策略执行。安全说明作为运维备注保存。

版本查询、草稿保存、发布分别使用 `GET /v1/admin/rules/{intent}/{definitionVersion}`、`PUT .../versions/{version}` 和 `POST .../versions/{version}/publish`。
