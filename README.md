# mySuperClaw · 企业级智能助手框架

**把现有业务接口接入一个能识别意图、多轮补参、按权限执行的智能助手。** 已支持的业务能力可以通过注册 Tool、明确 Intent Definition 与参数 Schema，再绑定执行路由来接入。固定流程可发布为 SOP Skill，开放任务可按授权范围交给 Planner。

适合将 OA、ERP、CRM 和企业内部服务接入统一的会话入口。项目提供工具管理台、会话调试页和可逐层查看的执行产物，可以从一句自然语言请求追到意图、任务、工具调用和业务回执。

## 框架分层

| 层 | 模块 | 职责 |
| --- | --- | --- |
| 接入 | `access` | 会话 API、可信租户和用户身份、管理 API、产物查询 |
| 意图理解 | `intent-engine` | 按需调用规则、语义召回或 LLM。通过 Context Assembler / Resolver 关联历史任务，合并槽位、处理追问、生成 Validated Intent IR |
| 策略与路由 | `decision` | 校验派发准入，将意图版本绑定到指定工具、固定 SOP 或 Planner |
| SOP 与执行 | `workflow-skill`、`agent-runtime` | 编译、校验和发布 Skill。执行固定或动态 DAG，处理审批挂起、Checkpoint 恢复、Hard-Gate 验收和 SSE 进度 |
| 工具执行 | `tool-gateway` | 管理工具契约、版本、搜索和发布。执行时再次校验权限、Schema、审批及幂等策略 |
| 状态与基础设施 | `memory`、`event-bus`、`infrastructure` | 持久化会话和运行状态，管理记忆、事件 Outbox、向量索引和对象存储适配 |

## 接入新业务的三个配置面

1. **注册 Tool。** 在 ToolGateway 登记工具名、输入输出 JSON Schema、端点别名和授权，发布固定版本。适配器覆盖 HTTP、MCP、本地 Java、unary gRPC、只读数据库查询和异步提交与回查。
2. **定义 Intent 与参数 Schema。** 声明业务领域、动作、版本、必填字段、字段来源和追问。例如 `EXPENSE.LIST@1` 可以接收可选的报销状态。对于目录里已存在的意图，可以沿用定义并配置规则与租户路由。新增自然语言能力时，当前通用入口还需要扩展 `IntentCatalog` 及相应的识别或抽参规则。SOP 可以通过 `SKILL.md` 声明配套的输入契约与固定 DAG。
3. **绑定路由。** 用配置指定租户、意图版本和目标版本，并映射槽位到工具参数。单次工具调用用 `DIRECT`，标准流程用 `WORKFLOW`，开放式目标按部署策略使用 Planner。发布后用最小权限会话验收查询、补参与执行回显。

例如「查询待办审批」识别为 `APPROVAL.LIST`，将「待办」归一成 `approvalStatus=pending`，再通过配置映射到 OA 的 `GET /api/approvals?status=pending`。回复和调试产物都能查看工具执行状态。[业务接入指南](docs/implementation/business-integration-guide.md) · [事务台 OA 接入实例](docs/implementation/oa-business-system-integration.md)

## 工程特点

- **部署配置支持复用。** 同一意图可以按租户绑定到不同业务系统。工具、Skill 和路由引用具体版本，便于测试、升级和回滚。
- **按任务复杂度执行。** 简单查询直达工具；多轮业务请求等待必填槽位；标准 SOP 固定节点与依赖；开放目标使用动态计划。
- **权限和业务执行可验证。** 网关依据调用者、租户和工具版本校验调用。审批、幂等、强校验和运行审计有独立的检查点及可读结果。
- **过程可追溯。** 调试页可沿 `Conversation → Turn → Task → Run → Tool Execution` 查看中间产物。长任务通过事件、检查点和 SSE 展示进度并支持恢复。

## 构建与运行

使用 Java 21、Spring Boot 3.5、Maven 多模块。LangChain4j 用于模型接入，LangGraph4j 用于图执行。存储层使用 PostgreSQL、MyBatis-Plus、Redis 和 Milvus，并提供对象存储适配。

```powershell
mvn verify
java -jar apps/agent-app/target/agent-app-0.1.0-SNAPSHOT.jar
```

默认启动可检查 `http://localhost:8080/actuator/health`。完整联调需要初始化数据库与基础服务，按用例启用 `storage,gateway,intent`。启用运行时能力还需 `runtime`，长任务另起同版本 Worker 并启用 `worker`。管理台与会话调试入口为 `/tool-admin/index.html`。[数据库与存储启动](database/README.md) · [长任务运行说明](docs/implementation/agent-runtime.md)

## 文档索引

- [业务接入指南](docs/implementation/business-integration-guide.md)
- [ToolGateway 管理、搜索和调用](docs/implementation/tool-gateway.md)
- [Intent Engine、多轮补参与中间产物](docs/implementation/intent-engine.md)
- [从 Definition、Schema 到 SOP 与 DAG](docs/implementation/sop-dag-definition-rules.md)
- [各模块实现与类关系](docs/implementation/README.md) · [系统设计](design/README.md) · [ER 图、建表 SQL 与数据字典](database/README.md)
