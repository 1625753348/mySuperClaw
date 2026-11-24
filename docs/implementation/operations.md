# 会话恢复与运行运维

已有库按需执行 [011 迁移](../../database/migrations/011-runtime-operations.sql) 和 [022 对账迁移](../../database/migrations/022-runtime-reconciliation.sql)。会话恢复需要 `storage,intent`，运行运维需要 `storage,gateway,runtime`。启用 `worker` 后，实例可重投事件和推进已恢复的 Run。

| 操作 | API | 权限 |
| --- | --- | --- |
| 历史会话分页 | `GET /v1/conversations?limit=25&before=...` | `intents.read` |
| 历史轮次分页 | `GET /v1/conversations/{id}/turns?limit=30&before=...` | `intents.read` |
| 投递列表 | `GET /v1/admin/operations/outbox?dead=true` | `ops.read` |
| 重投单个事件 | `POST /v1/admin/operations/outbox/{eventId}/replay` | `ops.write` |
| 待对账列表 | `GET /v1/admin/operations/reconciliation` | `ops.read` |
| 待对账节点 | `GET /v1/admin/operations/reconciliation/{runId}/steps` | `ops.read` |
| 核实后关闭 Run | `POST /v1/admin/operations/reconciliation/{runId}/close` | `ops.write` |
| 核实成功并续跑 | `POST /v1/admin/operations/reconciliation/{runId}/steps/{stepId}/confirm-success` | `ops.write` 与原工具的 `MANAGE` 权限 |

会话分页按创建时间和 UUID 排序，轮次分页按消息序号排序。客户端可用 `nextBefore` 读取更早的记录。后端按租户、用户和 Agent 校验归属，历史列表过滤已清空的轮次，选中轮次后仍按对应工具或 Run 权限读取执行结果。

管理页面中的「运行运维」可列出当前租户的投递与待对账运行。投递达到 12 次且租约失效后出现在重试耗尽列表。也可切换查看已发布事件并重投。重投保留原事件 ID 和载荷，消费者根据 Inbox 去重，正常发放新的 Redis Stream 消息 ID。

人工对账用于外部结果未知的 Run。操作者先通过外部流水或业务单据核对事实。进入管理台「运行运维」后，可以选择关闭为失败，也可以进入「核对步骤」，选中待对账节点，填写外部凭证引用、实际结果 JSON 和核对说明。提交成功确认需要 `ops.read`、`ops.write` 和原工具的 `MANAGE` 权限。`MANAGE` 可在工具权限页按主体授权。原执行 ID 由管理台从待对账节点读取，服务端重新核对归属和版本。事件重投和关闭操作的审计随状态变更一起写入 `eventing.operator_action`。

## 确认成功并恢复原 DAG

例子是销售工具提交后连接中断，Job 和 Run 分别停在 `UNKNOWN`、`NEEDS_RECONCILIATION`。运维用外部报表流水核实实际结果，先查待对账节点，取得 `stepId` 和 `executionId`，再向成功确认 API 提交下面的请求。字段取值以原工具版本的输出 Schema 为准。

```json
{
  "executionId": "dadecc8a-6fc8-444e-a75a-c21e75f595bd",
  "result": {
    "rows": [
      {"region": "华东", "product": "A", "customer": "企业", "previous": 120.25, "current": 90.25}
    ]
  },
  "evidenceRef": "erp:report-ledger:001",
  "reason": "已与业务系统的结算流水核对"
}
```

1. `RuntimeOperationsController` 从认证上下文取得租户和操作者。运行端锁住 Run，只处理当前计划内处于待对账状态的工具节点，要求请求中的执行 ID 与节点绑定的原执行完全一致。已取消或超过截止时间的运行无法续跑。
2. `ToolReconciliationService` 检查操作者的工具级 `MANAGE` 权限以及原调用者的 `EXECUTE` 权限。使用固定工具版本复核输出 Schema、审批和数据授权，并应用结果脱敏。带 ABAC 的工具需要加密原请求才能复核。如果旧记录的原请求已清理，平台会拒绝这类补记。
3. 网关将原执行改为 `SUCCEEDED`，把脱敏后的正文加密写入 `gateway.tool_job`。Run Manager 同事务将节点置回 `WAITING_EXTERNAL`，保存 `runtime.reconciliation_confirmation` 和 `run.reconciled` 事件。审计表保存操作者、原因、外部凭证引用和请求摘要，事件公开节点及执行 ID。
4. Worker 读取同一执行 ID 的结果，按正常节点结算流程写产物和检查点，解锁后续依赖。一次确认不再调用外部工具。如果同一个 Run 仍有其他待对账节点，Run 保持待对账，直至这些节点全部处理。

同一操作者对同一节点重复提交相同内容返回 `204`，只产生一条确认记录。并发中不同的确认内容会返回 `409`。原执行的审批记录，以及已结束的父 Run，都按既有生命周期保留。使用独立测试库运行 `node scripts/runtime-smoke.mjs <JAR> --reconciliation-only`，可验证并发重试、越权拒绝、脱敏和原 DAG 续跑。

[HTTP 入口](../../modules/access/src/main/java/com/example/enterpriseagent/access/http/RuntimeOperationsController.java) · [网关确认服务](../../modules/tool-gateway/src/main/java/com/example/enterpriseagent/toolgateway/execution/ToolReconciliationService.java) · [Run Manager](../../modules/agent-runtime/src/main/java/com/example/enterpriseagent/runtime/RunManager.java)

## 预定义规则

已有库执行 [012 迁移](../../database/migrations/012-predefined-rules.sql)。使用带 `rules.admin` 的管理凭证打开「预定义规则」，选择已登记的低风险 DIRECT 意图及定义版本。填写参数 Schema、追问和允许的角色，先保存规则版本，再发布。同一意图定义只有一个已发布版本，发布新版本会归档旧版本。

例如给 `ORDER.QUERY@1` 配置订单号格式和运营角色，缺参时使用已发布的追问。参数或角色约束未通过会阻止派发，执行记录可看规则版本和原因。规则范围是当前租户下的只读约束，工具权限、审批与数据范围继续由 ToolGateway 的已发布策略执行。安全说明作为运维备注保存。

版本查询、草稿保存、发布分别使用 `GET /v1/admin/rules/{intent}/{definitionVersion}`、`PUT .../versions/{version}` 和 `POST .../versions/{version}/publish`。
