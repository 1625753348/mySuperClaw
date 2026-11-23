# 持续监控 Agent Loop

监控定期调用已登记的只读工具，把数值与阈值比较。越阈和恢复时可以发送通知。进入异常时还可以发起处置审批，批准后由 ToolGateway 执行已配置的操作。监控周期、审批和通知各自保留进度。

## 准备

已有库按顺序执行 [015 监控迁移](../../database/migrations/015-agent-monitor.sql) 和 [017 投递迁移](../../database/migrations/017-monitor-delivery.sql) 中尚未执行的脚本。服务启用 `storage,gateway,runtime`，负责轮询和投递的实例另加 `worker`。部署人员按租户配置监控、通知和处置工具版本。

```yaml
enterprise:
  monitor:
    read-only:
      - tenant: demo
        tool: finance_metric
        version: '1'
    notification:
      - tenant: demo
        tool: finance_alert_notify
        version: '1'
    remediation:
      - tenant: demo
        tool: payment_review_create
        version: '2'
```

每次派发调用 ToolGateway，沿用当前的工具权限、输入校验、配额和审批。监控使用创建时的调用主体和角色快照。所有 Worker 使用相同的工具名单。监控端点用 `monitors.read` 查询、用 `monitors.write` 创建或变更状态。创建和恢复同时需要 `tools.invoke`。

## 创建和查看

调用 `PUT /v1/monitors/{UUID}`，重复提交同一配置会复用该标识。对同一标识提交不同配置会返回 `409`。

```json
{
  "tool": "finance_metric",
  "version": "1",
  "arguments": {"metric": "payment_delay"},
  "field": "value",
  "comparison": "ABOVE",
  "threshold": 10,
  "everySeconds": 30,
  "lifetimeSeconds": 3600,
  "action": "RECORD",
  "notifyWith": {"tool": "finance_alert_notify", "version": "1"},
  "remediateWith": {"tool": "payment_review_create", "version": "2"}
}
```

`ABOVE` 和 `BELOW` 分别使用严格大于和严格小于。间隔为 5 秒到 1 天，生命周期最长 30 天。用 `GET /v1/monitors/{id}/ticks` 查询最近轮次，`before` 传上一页最小序号可向前翻页。用 `POST /v1/monitors/{id}/pause` 或 `/resume` 调整状态。

| 阶段 | 行为 |
| --- | --- |
| 到期触发 | Worker 锁定一条到期记录，在同一事务预占工具执行、分配下一轮并更新活跃轮次。幂等键包含监控标识和轮次 |
| 正常或异常 | 轮次保存 `NORMAL` 或 `BREACH`。进入异常记录 `ALERT`，反复越阈仍保留轮次。恢复时记录 `RECOVERED` |
| 自动暂停 | `RECORD` 保留记录并继续。`PAUSE` 在越过阈值时暂停后续观测 |
| 通知 | 配置 `notifyWith` 后，`ALERT` 和 `RECOVERED` 各登记一次低风险通知。收件方由已登记的工具端点配置 |
| 处置审批 | 配置 `remediateWith` 后，每次进入异常登记一次处置。网关受理状态必须是 `WAITING_APPROVAL`，审批通过后才执行 |
| 异常保护 | 连续三次失败暂停。审批挂起、权限不可用或外部结果未知也暂停后续触发 |
| 手动暂停或到期 | 停止提交后续轮次，已提交的轮次仍会结算 |

两项工具都是可选的，已有监控可继续沿用原有配置。向通知和处置工具提交的参数固定为 `monitorId`（字符串）、`tickSeq`（整数）和 `signal`（`ALERT` 或 `RECOVERED`）。工具的输入 Schema 要声明这三个字段。工具端点按自己的凭证和授权处理通知、创建工单等动作。处置工具须配置 ToolGateway 的 `APPROVAL` 策略，审批人须有 `tools.approve` 与工具级 `APPROVE`，由独立审批人决定。

## 一次异常怎样流到审批和恢复

1. **第一轮观测到 20。** 阈值为 10，`PostgresMonitorLoop.finish` 将轮次写成 `BREACH / ALERT`。同一事务写入 `runtime.monitor_delivery` 的通知和处置记录。两条记录共用轮次号，由 `kind` 区分。数据库事务提交后，轮询 Worker 才能领取投递。
2. **Worker 接着登记工具执行。** `MonitorDeliveryQueue.advance` 使用 `FOR UPDATE SKIP LOCKED` 领取一条投递。它重新检查租户的工具版本名单，再由 ToolGateway 校验当前权限、Schema 和审批策略。在同一个事务里写入网关任务和投递的 `execution_id`。幂等键用监控 ID、轮次和投递类型生成，重启后还可找回原执行。
3. **审批人批准。** 首次进入异常的处置停在 `WAITING_APPROVAL`，通知可以独立完成。缺少审批策略时，即使网关暂时生成了一条可排队的执行，保存点也会回滚该预占，投递记为 `FAILED / TOOL_POLICY_REQUIRED`。审核通过后 ToolGateway Worker 复核当前授权，再调用配置里的下游。
4. **第二轮仍为 20，第三轮降到 2。** 第二轮继续记录 `BREACH`。第三轮产生 `RECOVERED` 并登记恢复通知。要看到两次异常周期分别申请审批，可再让指标越阈一次。

手动暂停或到期会阻止尚未提交的新处置。已经进入 ToolGateway 的申请按网关状态结算。配置 `PAUSE` 时，触发自动暂停的这次异常仍可创建相应的处置审批。通知或处置受理失败可在投递列表查看，后续指标观测按监控原配置运行。

## 审批后执行固定 SOP

已有库执行 [018 迁移](../../database/migrations/018-monitor-sop.sql)。发布 [监控告警复核 SKILL.md](../../skills/monitor-alert-review/SKILL.md)，在 `enterprise.monitor.workflow-remediation` 为租户登记明确组合。审批工具还需出现在原有的 `remediation` 名单。给审批工具配置可审阅的准入操作，业务处理放在已经发布的 DAG 中。

```yaml
enterprise:
  monitor:
    workflow-remediation:
      - tenant: demo
        skill-key: monitor.alert-review
        version: 1
        approval-tool: monitor_sop_gate
        approval-version: '1'
```

创建请求在原 `remediateWith` 指定匹配的审批工具，再加 `"remediationSop": {"skillKey": "monitor.alert-review", "version": 1}`。创建和恢复还需 `runs.submit`。现在支持 `workflow.read` 发布版本的固定 DAG。审批工具除了原来的 `monitorId`、`tickSeq`、`signal`，输入 Schema 还需接收 `skillKey`（字符串）、`skillVersion`（整数）和 `skillVersionId`（字符串），将准确的版本纳入审批操作摘要。SOP 的输入沿用三个轮次字段。

1. **监控创建时。** `MonitorWorkflowDispatch.pin` 查租户名单、发布状态和输入 Schema，将版本 UUID 存到 `runtime.monitor`。一轮进入异常时，`MonitorDeliveryQueue` 将同一版本复制到该轮的处置记录。
2. **审批期间。** `GET /v1/monitors/{id}/deliveries` 的 `workflow.status` 显示 `WAITING_APPROVAL`，`runId` 为空。即使此时发布了 v2，这条申请仍指向 v1。审批被拒绝时记录 `GATE_REJECTED`。
3. **审批工具执行成功后。** `MonitorWorkflowDispatch.advance` 核对审批状态、操作摘要、版本 UUID 和网关执行结果。然后在同一事务保存系统生成的 Task、带版本的 Intent IR、准入记录、Run 与投递关联。幂等键包含监控和轮次。每个 DAG 节点继续通过 ToolGateway 复核自己的权限。
4. **复核完成后。** `workflow.status` 来自对应 Run，成功时带 `resultUrl`。例如先查证据，再生成复核结果，接口可按轮次查看这条完整链路。结果正文和 SSE 沿用 Run 接口及 `runs.read` 权限。

审批完成和创建 Run 之间如果监控被手动暂停或到期，SOP 记录为 `SKIPPED`。已创建的 Run 可在原有运行接口查看、取消或对账。

## API 和库表对应关系

| 看什么 | API 或表 | 对应代码 |
| --- | --- | --- |
| 监控计划和游标 | `GET /v1/monitors/{id}`，`runtime.monitor` | `MonitorController` 接收请求，`PostgresMonitorLoop` 校验和调度 |
| 观测轮次与告警边沿 | `GET /v1/monitors/{id}/ticks`，`runtime.monitor_tick` | `PostgresMonitorLoop.settle` 读取指标，`finish` 写信号 |
| 投递及处置进度 | `GET /v1/monitors/{id}/deliveries`，`runtime.monitor_delivery` | `MonitorDeliveryQueue` 读取网关状态，`MonitorWorkflowDispatch` 衔接版本固定的 Run。用最早返回的 `seq` 作为 `before` 继续翻页 |
| 具体工具执行和审批 | `GET /v1/tools/executions/{executionId}`，`gateway.tool_execution` | `ToolExecutionService` 返回当前状态和可用的审批引用，审批人通过网关审批 API 处理 |

[监控调度](../../modules/infrastructure/src/main/java/com/example/enterpriseagent/infrastructure/runtime/PostgresMonitorLoop.java) · [通知与处置队列](../../modules/infrastructure/src/main/java/com/example/enterpriseagent/infrastructure/runtime/MonitorDeliveryQueue.java) · [表与字段](../../database/dictionary.md#runtimemonitor_delivery)

使用独立测试库运行 `node scripts/runtime-smoke.mjs <JAR> --monitor-only`。脚本用本地 HTTP 模拟指标、通知、准入和复核，覆盖异常与恢复、审批前后、缺少审批策略、监控归属隔离、等待审批时发布新版、旧版固定执行和拒绝审批。
