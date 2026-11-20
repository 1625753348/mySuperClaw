---
name: order-refund
description: 验证订单是否可退，审批通过后提交一次退款并返回业务回执。
---

# 订单退款

显式命令为 `/refund`。订单号与退款原因均为必填。用户须具备 `refunds.submit`，网关复核工具执行权限。资格检查失败时结束流程。退款提交绑定高风险工具版本，审批使用 ToolGateway 的独立审批人和异步续跑。业务回执保留退款 ID 和下游状态。

```json
{
  "skillKey": "order.refund",
  "version": 1,
  "intentDefinition": "ORDER.REFUND@1",
  "executionMode": "WORKFLOW",
  "admissionPolicy": "order.refund.submit",
  "triggers": [{ "kind": "COMMAND", "key": "/refund" }],
  "inputSchema": {
    "type": "object",
    "required": ["orderId", "reason"],
    "additionalProperties": false,
    "properties": {
      "orderId": { "type": "string", "minLength": 1, "maxLength": 40 },
      "reason": { "type": "string", "minLength": 1, "maxLength": 300 }
    }
  },
  "nodes": [
    { "id": "eligibility", "tool": "refunds/eligibility@1", "dependsOn": [],
      "inputs": { "orderId": "input.orderId" }, "output": "RefundEligibility" },
    { "id": "check_eligibility", "handler": "refund.check_eligibility@1", "dependsOn": ["eligibility"],
      "inputs": { "eligibility": "steps.eligibility.output" }, "output": "RefundEligibility" },
    { "id": "submit_refund", "tool": "refunds/submit@1", "dependsOn": ["check_eligibility"],
      "inputs": { "orderId": "input.orderId", "reason": "input.reason" }, "output": "RefundReceipt" }
  ],
  "output": "steps.submit_refund.output"
}
```

工具注册名分别为 `refunds_eligibility` 和 `refunds_submit`。提交工具需声明 `HIGH` 或 `CRITICAL`，配置生效的审批规则和稳定幂等键。生产环境的下游需校验订单归属与退款条件，并将网关操作键作为业务幂等键。
