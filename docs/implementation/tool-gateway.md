# tool-gateway 工具治理

当前模块已有 [registry](../../modules/tool-gateway/src/main/java/com/example/enterpriseagent/toolgateway/registry/package-info.java)、[execution](../../modules/tool-gateway/src/main/java/com/example/enterpriseagent/toolgateway/execution/package-info.java)、[adapter](../../modules/tool-gateway/src/main/java/com/example/enterpriseagent/toolgateway/adapter/package-info.java) 三个包的职责说明。网关服务和 HTTP/MCP 适配器待实现。表结构已包含工具、版本、授权、执行与审批记录。

## 设计中的实现关系

- Registry 按工具稳定标识与版本提供执行契约。
- Execution 用可信主体和实际参数校验权限、Schema、审批和幂等，再委托协议适配器发请求。
- Adapter 处理 HTTP 或 MCP 协议细节，执行记录回传结果引用或等待状态，由 Runtime 判断后续节点。

例如退款节点携带固定工具版本和稳定操作键。审批结果回到 Gateway 后，Gateway 再核对权限与参数绑定，然后决定是否实际派发。[ToolGateway 治理](../../design/architecture.md#toolgateway-治理) · [工具 API 契约](../../design/contracts.md#工具网关接口)
