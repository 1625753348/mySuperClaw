# 监控告警复核

接收首个越阈轮次，先查询业务指标明细，再生成可查的复核结果。部署时将两个低风险工具分别接到指标详情和复核输出服务，复核节点等待查询节点完成。监控审批工具单独登记，由监控模块先受理。

```json
{
  "skillKey": "monitor.alert-review",
  "version": 1,
  "intentDefinition": "MONITOR.ALERT_REVIEW@1",
  "executionMode": "WORKFLOW",
  "admissionPolicy": "workflow.read",
  "triggers": [{"kind": "COMMAND", "key": "/monitor-alert-review"}],
  "inputSchema": {
    "type": "object",
    "required": ["monitorId", "tickSeq", "signal"],
    "additionalProperties": false,
    "properties": {
      "monitorId": {"type": "string"},
      "tickSeq": {"type": "integer", "minimum": 1},
      "signal": {"type": "string", "enum": ["ALERT"]}
    }
  },
  "nodes": [
    {
      "id": "evidence",
      "tool": "monitor_sop_evidence@1",
      "dependsOn": [],
      "inputs": {"monitorId": "input.monitorId", "tickSeq": "input.tickSeq", "signal": "input.signal"},
      "output": "GenericResult"
    },
    {
      "id": "review",
      "tool": "monitor_sop_review@1",
      "dependsOn": ["evidence"],
      "inputs": {"monitorId": "input.monitorId", "tickSeq": "input.tickSeq", "signal": "input.signal"},
      "output": "GenericResult"
    }
  ],
  "output": "steps.review.output"
}
```
