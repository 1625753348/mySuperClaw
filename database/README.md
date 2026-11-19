# PostgreSQL 建库资料

| 文件 | 内容 |
| --- | --- |
| [ER 图](er.md) | 跨领域总览与分域实体关系图，可在 GitHub 查看 |
| [建表 SQL](schema.sql) | PostgreSQL 空库建表脚本，包含 Schema、表、约束、索引 |
| [数据字典](dictionary.md) | 字段含义、类型、可空、默认及生成规则、键、约束和索引 |

建议先从 ER 总览看 Task 到 Run、Step 和 ToolExecution 的关系，再按领域查看数据字典中的完整复合键。数据库脚本把会话与意图、运行与 SOP、工具网关、事件、记忆放在五个 PostgreSQL Schema 下。

如需在已创建的空白 PostgreSQL 数据库中加载结构，从项目根目录运行以下命令，并替换连接名称。

```powershell
psql -X --set=ON_ERROR_STOP=1 --dbname=enterprise_agent --file=database/schema.sql
```

脚本在单个事务中建表，失败时回滚。当前文件用于空库初始化，数据库角色、应用数据和后续版本的 Flyway 迁移在持久化接入阶段处理。Redis Streams、对象存储和知识向量索引由相应基础设施管理。业务事务、租户授权和启停规则见 [接口与数据模型](../design/contracts.md)。

## 存储启动

项目的本地 PostgreSQL 容器使用 `postgres:17-alpine` 和命名卷 `enterprise-agent-postgres-data`，宿主机端口为 `127.0.0.1:5432`。在 `database/.env.postgres.local`（已被 Git 排除）保存 `POSTGRES_DB=enterprise_agent`、`POSTGRES_USER=enterprise_agent` 与随机 `POSTGRES_PASSWORD`。在 Docker 所在的 Linux 或 WSL 环境执行 `sh database/start-postgres.sh`。首次初始化自动加载 `schema.sql`，再次执行会启动已有的同名项目容器并保留其数据卷。

从 Windows PowerShell 启动本地应用。

```powershell
$postgresPasswordLine = Get-Content database/.env.postgres.local |
    Where-Object { $_.StartsWith('POSTGRES_PASSWORD=') }
$env:ENTERPRISE_DB_PASSWORD = $postgresPasswordLine.Substring('POSTGRES_PASSWORD='.Length)
java -jar apps/agent-app/target/agent-app-0.1.0-SNAPSHOT.jar --spring.profiles.active=storage
```

| 配置 | 本地默认值 |
| --- | --- |
| `ENTERPRISE_DB_URL` | `jdbc:postgresql://127.0.0.1:5432/enterprise_agent` |
| `ENTERPRISE_DB_USER` | `enterprise_agent` |
| `ENTERPRISE_DB_PASSWORD` | 由本地凭证文件或部署环境提供 |
| `ENTERPRISE_REDIS_HOST`、`ENTERPRISE_REDIS_PORT` | `127.0.0.1`、`6379`，认证可通过 `ENTERPRISE_REDIS_PASSWORD` 配置 |
| `ENTERPRISE_MILVUS_URI`、`ENTERPRISE_MILVUS_TOKEN` | `http://127.0.0.1:19530`，令牌由部署环境提供 |
| `ENTERPRISE_MILVUS_COLLECTION`、`ENTERPRISE_EMBEDDING_DIMENSION` | `enterprise_agent_memory`、`384`。调用方提供对应维数的向量 |

`/actuator/health` 检查 PostgreSQL、Redis 和 Milvus。Milvus 首次索引或搜索时建集合，已有同名集合会复用。更换向量模型的维度时使用新的集合名。Embedding 生成、事件驱动的索引更新、消息正文对象存储和工作流框架的恢复入口属于后续业务接入。

## 六类记忆的实现

| 记忆 | 实现 |
| --- | --- |
| 会话 | `SessionMemory` 和 Redis TTL Store |
| 档案 | `ProfileMemory` 和 PostgreSQL Store，按精确键查找并检查修订号 |
| 对话 | `ConversationMemory` 组合摘要水位和最近消息引用，按历史代次识别无效摘要 |
| 工作 | `WorkingMemory` 和 PostgreSQL `CheckpointSaver`，按运行线程读快照并保留外置产物绑定 |
| 情节 | `EpisodicMemory` 用 Milvus 召回案例引用，再由 PostgreSQL 复核归属与可见状态 |
| 语义 | `SemanticMemory` 在 PostgreSQL 发布文档版本，在 Milvus 索引引用，召回后核对权限与当前版本 |

六类记忆继承 `AbstractMemory`，以各自的类型约束覆盖读写。工作记忆 Saver 可以参加调用方的 Run 事务。其它类型从 Store 获取数据。具体模块见 [记忆设计](../design/memory.md)。
