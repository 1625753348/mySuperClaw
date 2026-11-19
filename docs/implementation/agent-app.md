# agent-app 启动与装配

[EnterpriseAgentApplication](../../apps/agent-app/src/main/java/com/example/enterpriseagent/EnterpriseAgentApplication.java) 是可运行 JAR 的入口。这个类使用 `@SpringBootApplication` 和静态 `main`。它放在 `com.example.enterpriseagent` 根包，所以 Spring 能在子包扫描到存储配置。

## 依赖和 Profile

[应用 POM](../../apps/agent-app/pom.xml) 声明九个 Maven 模块依赖，加入 Spring Web、Actuator，并由 Spring Boot 插件打包。业务功能各有自己的模块，启动应用用构造器和 Bean 组装它们。

| 启动方式 | 实际效果 |
| --- | --- |
| 默认 | 启动 Web 容器，暴露 `/actuator/health`。默认配置排除 JDBC 数据源自动装配，并关闭 Redis 健康检查 |
| `storage` | 增加 PostgreSQL、Redis、Milvus 的连接配置，并启用 `MemoryStorageConfiguration` 中的记忆服务 |
| `worker` | 将 `spring.main.web-application-type` 设为 `none`。与运行时配置组合后启动常驻 Worker |
| `storage,worker` | 装配记忆存储和连接健康检查，以无 Web 容器模式启动 |
| `storage,gateway` | 装配 PostgreSQL 网关、评分发现和 HTTP／Local／MCP／gRPC 适配器，提供 Bearer JWT 保护的工具 API |
| `storage,gateway,runtime` | 提供已认证的 Run 提交、取消、状态、结果和 SSE |
| `storage,gateway,runtime,worker` | 常驻运行 DAG 领取器、规划及 Redis Streams 投递与唤醒 |

## 按这个顺序追源码

1. `main` 调用 `SpringApplication.run`。
2. Spring 从 [默认配置](../../apps/agent-app/src/main/resources/application.yml)、[storage 配置](../../apps/agent-app/src/main/resources/application-storage.yml)和 [worker 配置](../../apps/agent-app/src/main/resources/application-worker.yml)读取当前运行形态。
3. 启用 `storage` 时，Spring 创建连接客户端、Mapper 和 [基础设施模块](infrastructure.md)中的 Bean。每个 Memory Bean 的构造器获得自己的 Store 或 Saver。
4. `/actuator/health` 聚合已启用的健康组件。Milvus 由自定义 `HealthIndicator` 检查连接，PostgreSQL 与 Redis 使用 Spring Boot 的组件。
