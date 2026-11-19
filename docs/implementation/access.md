# access HTTP 接入与 SSE

当前模块已有 [ToolGatewayController](../../modules/access/src/main/java/com/example/enterpriseagent/access/http/ToolGatewayController.java) 与 [ToolGatewaySecurity](../../modules/access/src/main/java/com/example/enterpriseagent/access/http/ToolGatewaySecurity.java)。`storage,gateway` Profile 下，HTTP 请求通过 Bearer JWT 验证后进入 [工具网关](tool-gateway.md)。会话请求、运行状态和 SSE 仍按下面的职责接入。`/actuator/health` 由启动模块引入的 Actuator 提供。

## 设计中的实现关系

| 包 | 接收 | 交给谁 |
| --- | --- | --- |
| `access.http` | 会话 ID、文本或 Skill 参数、幂等键 | 将 HTTP DTO 和可信身份转换为应用请求，交给意图处理用例 |
| `access.sse` | Run ID、身份、`Last-Event-ID` | 先验证访问范围，再从持久化 Run 事件补读和订阅 |

例如 `POST /v1/conversations/{conversationId}/turns` 需要把正文和用户身份分别绑定，随后提交 Turn。同步执行返回结果，异步处理可返回查询地址。SSE 断开只代表连接关闭，Run 的状态继续由 [agent-runtime](agent-runtime.md) 管理。[接口契约](../../design/contracts.md)
