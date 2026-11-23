# 持续监控 Agent Loop

监控按配置的周期调用一个已登记的只读工具。Worker 提取响应中的数值字段并与阈值比较。支持记录异常，以及出现异常时自动暂停监控。通过监控历史可以看到每一轮、首次越阈和恢复事件。

## 准备

已有库执行 [015 迁移](../../database/migrations/015-agent-monitor.sql)。服务启用 `storage,gateway,runtime`。负责轮询的实例另加 `worker`。部署人员按租户列出用于监控的低风险只读工具版本。

```yaml
enterprise:
  monitor:
    read-only:
      - tenant: demo
        tool: finance_metric
        version: '1'
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
  "action": "PAUSE"
}
```

`ABOVE` 和 `BELOW` 分别使用严格大于和严格小于。间隔为 5 秒到 1 天，生命周期最长 30 天。用 `GET /v1/monitors/{id}/ticks` 查询最近轮次，`before` 传上一页最小序号可向前翻页。用 `POST /v1/monitors/{id}/pause` 或 `/resume` 调整状态。

| 阶段 | 行为 |
| --- | --- |
| 到期触发 | Worker 锁定一条到期记录，在同一事务预占工具执行、分配下一轮并更新活跃轮次。幂等键包含监控标识和轮次 |
| 正常或异常 | 轮次保存 `NORMAL` 或 `BREACH`。进入异常记录 `ALERT`，反复越阈仍保留轮次。恢复时记录 `RECOVERED` |
| 自动处置 | `RECORD` 保留记录并继续。 `PAUSE` 在任一轮越过阈值时暂停后续触发 |
| 异常保护 | 连续三次失败暂停。审批挂起、权限不可用或外部结果未知也暂停后续触发 |
| 手动暂停或到期 | 停止提交后续轮次，已提交的轮次仍会结算 |

PostgreSQL 中的 `runtime.monitor` 保存游标和状态，`runtime.monitor_tick` 保存轮次及工具执行引用。Worker 重启后继续读取权威记录。当前告警交付为 API 可查的轮次记录，自动处置范围为暂停监控。
