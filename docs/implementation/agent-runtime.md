# agent-runtime 长任务

目前可以通过结构化 API 运行销售分析。`WORKFLOW` 直接按请求指定的维度生成固定 DAG。`AGENT` 先生成 PlanProposal，再经服务端校验发布。两种模式可以接收行数据，也可以通过 ToolGateway 取得同样形状的数据。

## 本地运行

空库加载 `database/schema.sql`。已有库按顺序应用未执行的迁移，本阶段使用 `004-runtime-long-runs.sql`。启用存储需要 PostgreSQL、Redis 和 Milvus。JWT 复用网关的 issuer、audience 和 JWK 配置。API 和 Worker 使用同一个 JAR，数据库及配置必须一致。[数据库启动](../../database/README.md) · [网关启动配置](tool-gateway.md#启动配置)

```powershell
# API 端进程
java -jar apps/agent-app/target/agent-app-0.1.0-SNAPSHOT.jar --spring.profiles.active=storage,gateway,runtime

# 独立 Worker 进程
java -jar apps/agent-app/target/agent-app-0.1.0-SNAPSHOT.jar --spring.profiles.active=storage,gateway,runtime,worker
```

默认使用本地确定性 Planner。启用 LangChain4j 模型规划时在 Worker 配置 `ENTERPRISE_RUNTIME_MODEL_ENABLED=true`、`ENTERPRISE_RUNTIME_MODEL_API_KEY` 或 `DEEPSEEK_API_KEY`。模型使用 OpenAI 兼容接口，默认 `deepseek-flash`，可以设置 `ENTERPRISE_RUNTIME_MODEL_BASE_URL` 和 `ENTERPRISE_RUNTIME_MODEL`。模型只接收目标、允许维度、缺失项和产物引用。启用工具数据源时，API 与 Worker 都需要同一个 `ENTERPRISE_GATEWAY_JOB_KEY`。[运行配置](../../apps/agent-app/src/main/resources/application-runtime.yml)

## 请求和查询

| 操作 | 方法与权限 |
| --- | --- |
| 提交 | `POST /v1/runs`，需要 `runs.submit`、`Idempotency-Key`，使用工具来源时另需 `tools.invoke` |
| 查看状态 | `GET /v1/runs/{runId}`，需要 `runs.read` |
| 重连进度 | `GET /v1/runs/{runId}/events`，需要 `runs.read`。传 `Last-Event-ID` 或 `?after=序号` |
| 下载 JSON 报告 | `GET /v1/runs/{runId}/result`，需要 `runs.read` |
| 取消 | `POST /v1/runs/{runId}/cancel`，需要 `runs.submit`、`Idempotency-Key` |

提交的 JWT 租户、主体、Agent 都参与隔离。相同作用域下，同一个幂等键与同样的规范化请求返回原 Run，冲突请求返回 409。

```json
{
  "mode": "AGENT",
  "goal": "解释销售变动",
  "dimensions": ["region", "product"],
  "rows": [
    {"region":"华东","product":"A","customer":"企业","previous":120.25,"current":90.25},
    {"region":"华南","product":"B","customer":"个人","previous":80.50,"current":100.50}
  ],
  "maxIterations": 2,
  "deadlineSeconds": 300
}
```

可将 `rows` 删去，改用 `"source":{"name":"sales_query","version":"1","arguments":{"period":"2026-Q3"}}`。数据源工具需返回行数组或 `{"rows":[...]}`。工具继承原有的 RBAC、ABAC、审批、配额、脱敏和协议能力。状态事件中如有 `approvalId`，审批者可用带 `tools.approve` 的凭证向 `/v1/tools/approvals/{approvalId}/decisions` 提交裁决。[ToolGateway 审批接口](tool-gateway.md#策略与续跑)

客户端持有 JWT 和最近收到的事件序号即可恢复订阅。

```powershell
curl.exe -N -H "Authorization: Bearer $env:RUN_ACCESS_TOKEN" -H "Last-Event-ID: 5" `
  "http://localhost:8080/v1/runs/$runId/events"
```

## 一次运行怎么推进

1. 接入在一个事务内写入结构化意图记录、任务、输入版本、准入和 Run。入队、活动计划、RunEvent 和 Outbox 一并提交，HTTP 返回 202。
2. Worker 先锁 Run，再领取到期提案或就绪节点并提交租约。多个就绪维度各自计算，最多四个节点或规划任务同时运行。只有依赖全部成功，报告节点才会就绪。
3. 执行节点用 LangGraph4j 处理聚合、报表生成和 Hard Gate。节点提交后，Run Manager 同一事务追加产物元数据、正文、结构化快照、进度事件和 Outbox。快照只记录有效计划与产物引用，模型上下文和完整历史不会塞给后续节点。
4. ToolGateway 获得稳定的节点操作键，始终创建可恢复的持久调用。Runtime 按执行 ID 等待审批或下游回查，释放 Worker。ToolExecution 状态事件加快唤醒，Redis 不可用时由数据库轮询补齐。未知副作用进入 `NEEDS_RECONCILIATION`，保留关联记录。
5. AGENT 的 Hard Gate 逐项核对要求维度、报告汇总和证据引用。第一轮若缺少产品分析，Ralph 用已提交的来源与地区证据、失败项和剩余轮次构造 Fresh Context。修订计划复用合格产物，只补缺口并重生成报告。超过轮次、规划调用或时间预算时结算失败。
6. SSE 以 PostgreSQL 的 RunEvent 序号补发。它有心跳和单连接时限，客户端可以用最后收到的 ID 重连。取消停止新派发并取消待审批或未派发工具，在途调用仍需对账。

## 代码与数据

| 组件 | 实现关系 |
| --- | --- |
| [RunManager](../../modules/agent-runtime/src/main/java/com/example/enterpriseagent/runtime/RunManager.java) | 注入 `RunStore`、`CheckpointSaver`、`RunTool` 和 `RunPlanner`，拥有状态推进和预算 |
| [ReportGraph](../../modules/agent-runtime/src/main/java/com/example/enterpriseagent/runtime/execution/ReportGraph.java) | 编译纯计算 LangGraph4j 节点。Run Manager 从 Saver 恢复依赖，图只得到当前节点需要的数据 |
| [RunMapper](../../modules/infrastructure/src/main/java/com/example/enterpriseagent/infrastructure/runtime/RunMapper.java) | `extends RunStore`，用 MyBatis-Plus 代理执行 PostgreSQL SQL。`runtime.run_submission` 管理入口幂等 |
| [GatewayRunTool](../../modules/infrastructure/src/main/java/com/example/enterpriseagent/infrastructure/runtime/GatewayRunTool.java) | `implements RunTool`，使用内部运行绑定提交和回查工具 |
| [ModelRunPlanner](../../modules/infrastructure/src/main/java/com/example/enterpriseagent/infrastructure/runtime/ModelRunPlanner.java) | `implements RunPlanner`，模型选择维度。服务端编译受限 DAG |
| [RunSse](../../modules/access/src/main/java/com/example/enterpriseagent/access/sse/RunSse.java) | 按身份、游标及令牌有效期读取 `runtime.run_event` |

产物正文存于 PostgreSQL `runtime.artifact_body`，检查点以 `runtime.checkpoint_artifact` 引用元数据。验收检查和缺失项在 `runtime.iteration` 与 `runtime.gate_check` 中留痕。应用配置保留的角色是提交时的认证快照，工具权限和审批规则在派发前复核。通用 Intent Engine、SOP 编译后接入、人工对账入口、跨服务取消、外部对象存储、事件保留与管理界面属于后续工作。[EventBus 实现](event-bus.md) · [端到端联调脚本](../../scripts/runtime-smoke.mjs)
