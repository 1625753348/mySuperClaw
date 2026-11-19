# 企业级智能助手工程

工程采用 Java 21、Spring Boot 3.5 和 Maven 多模块。当前已交付工程骨架、数据库结构、记忆持久化基础，以及 ToolGateway 的控制面发布、评分发现和多协议同步执行。各模块的 Java 包注释说明职责。

```text
enterprise-agent/
├── pom.xml                         Maven 聚合与版本配置
├── apps/
│   └── agent-app/                  Spring Boot 启动和模块装配
├── modules/
│   ├── access                      HTTP 接入与响应
│   ├── intent-engine               意图理解
│   ├── decision                    业务策略与执行路由
│   ├── workflow-skill              SOP Skill
│   ├── agent-runtime               运行时
│   ├── tool-gateway                工具网关
│   ├── event-bus                   事件总线
│   ├── memory                      记忆系统
│   └── infrastructure              基础设施适配
└── design/                        架构与数据库评审资料
```

## 模块如何配合

- `access` 接入请求，`intent-engine` 给出结构化意图，`decision` 决定准入和执行模式。
- `workflow-skill` 提供已经发布的固定 SOP。开放式任务由 `agent-runtime` 中的 L4 Planner 生成候选计划，运行时统一管理 DAG、Ralph 验收和恢复。
- `tool-gateway` 统一复核并执行 HTTP、MCP 等工具调用。`event-bus` 负责事件发布及消费。`memory` 管理对话视图和长期记忆。`infrastructure` 承接 PostgreSQL、Redis 和对象存储适配。
- 业务模块之间通过领域接口传递请求和结果。启动模块装配各实现，数据库事务边界按应用用例确定。LangChain4j 与 LangGraph4j 分别在模型调用和图执行阶段接入。

## 构建与核验

用 JDK 21 在根目录运行以下命令。

```powershell
mvn verify
java -jar apps/agent-app/target/agent-app-0.1.0-SNAPSHOT.jar
```

启动后可访问 `http://localhost:8080/actuator/health`。需要 PostgreSQL、Redis、Milvus 和六类记忆服务时启用 `storage`，配置方法见 [数据库与存储启动说明](database/README.md#存储启动)。`worker` 可与 `storage` 一起启用，Worker 尚未接入后台任务，初始化完成后会退出。

同时启用 `storage,gateway` 后，访问 `/tool-admin/index.html`，使用带 `tools.admin` 权限的 JWT 管理工具。[管理台使用说明](docs/implementation/tool-gateway.md#工具管理台)

## 实现顺序

1. 建立 Maven 工程、启动结构与模块边界。
2. 建立 PostgreSQL 表、MyBatis-Plus Store/Saver、Redis 会话与 Milvus 向量索引。
3. 落地 ToolGateway 管理台、混合发现、授权、审批续跑和审计，支持 HTTP、本地 Java、MCP、unary gRPC、只读 PostgreSQL 与异步回查。
4. 接入请求归一化和 L0 DIRECT，再接 SOP Skill、固定 DAG、审批恢复、事件投递、L4 规划与 Ralph。

查看 [各模块实现与继承关系](docs/implementation/README.md) 可按源码追踪装配和调用。网关启动、API 与模型测试见 [ToolGateway 实现](docs/implementation/tool-gateway.md)。完整模块定义和状态约束见 [设计评审入口](design/README.md)。ER 图、建表 SQL 和数据字典集中在 [数据库目录](database/README.md)。
