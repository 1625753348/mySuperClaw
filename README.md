# 企业级智能助手工程

这是实现的第一阶段。工程采用 Java 21、Spring Boot 3.5 和 Maven 多模块。业务模块已建目录，并通过 Java 包注释标明职责。当前可构建启动包和检查 HTTP 健康端点，业务逻辑将在后续阶段接入。

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

启动后可访问 `http://localhost:8080/actuator/health`。指定 `--spring.profiles.active=worker` 可检查无 Web 容器的进程配置。当前 Worker 尚未接入后台任务，初始化完成后会退出。

## 实现顺序

1. 建立 Maven 工程、启动结构与模块边界。
2. 落地首个 DIRECT 查询，从请求归一化、L0 注册和输入校验走到 ToolGateway。
3. 接入持久化、SOP Skill、固定 DAG 和审批恢复。
4. 接入事件投递、Run/SSE、L4 规划、Ralph 和对话记忆。

完整模块定义和状态约束见 [设计评审入口](design/README.md)。
