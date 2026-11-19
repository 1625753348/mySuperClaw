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
