# memory 六类记忆的继承与读写

六类记忆都继承 [AbstractMemory](../../modules/memory/src/main/java/com/example/enterpriseagent/memory/AbstractMemory.java)。父类的泛型 `K` 是查找键，`R` 是读结果，`W` 是写入对象。它们可以各不相同。例如对话记忆读取一个 `Window`，写入的是 `Summary`。

```mermaid
classDiagram
    class AbstractMemory {
        +read(key)
        +write(value)
        #load(key)
        #save(value)
    }
    AbstractMemory <|-- SessionMemory
    AbstractMemory <|-- ProfileMemory
    AbstractMemory <|-- ConversationMemory
    AbstractMemory <|-- WorkingMemory
    AbstractMemory <|-- EpisodicMemory
    AbstractMemory <|-- SemanticMemory
    class MemoryStore {
        <<interface>>
        +load(key)
        +save(value)
    }
    class CheckpointSaver {
        <<interface>>
        +loadLatest(key)
        +append(snapshot)
    }
    class MemoryVectorIndex {
        <<interface>>
        +upsert(tenant, kind, source, vector)
        +search(tenant, kind, vector, limit)
    }
    SessionMemory --> MemoryStore
    ProfileMemory --> MemoryStore
    ConversationMemory --> MemoryStore
    EpisodicMemory --> MemoryStore
    SemanticMemory --> MemoryStore
    WorkingMemory --> CheckpointSaver
    EpisodicMemory --> MemoryVectorIndex
    SemanticMemory --> MemoryVectorIndex
```

## 父类统一入口，子类重写步骤

`read` 和 `write` 是 `public final` 方法，先用 `Objects.requireNonNull` 检查对象，再调用 `protected abstract load` 或 `save`。子类使用 `@Override` 实现父类约定的两个步骤，这在 Java 中叫**重写**。例如 `ProfileMemory.load` 委托给构造器注入的 `MemoryStore`，`WorkingMemory.load` 委托给 `CheckpointSaver.loadLatest`。

| 子类 | 查找键 `K` | 读结果 `R` / 写入对象 `W` | 当前存取行为 |
| --- | --- | --- | --- |
| [SessionMemory](../../modules/memory/src/main/java/com/example/enterpriseagent/memory/session/SessionMemory.java) | 租户、用户、会话 ID | `Optional<String>` / `Entry` | Redis 存 JSON 和 TTL，过期后读取为空 |
| [ProfileMemory](../../modules/memory/src/main/java/com/example/enterpriseagent/memory/profile/ProfileMemory.java) | 租户、用户、档案字段键 | `Optional<Item>` / `Item` | PostgreSQL 精确查询，跳过过期记录。写入时校验修订号单调增加 |
| [ConversationMemory](../../modules/memory/src/main/java/com/example/enterpriseagent/memory/conversation/ConversationMemory.java) | 租户、会话、用户、窗口条数 | `Window` / `Summary` | 同一读取事务组合有效摘要与摘要水位之后的消息引用。写入只追加新的摘要 |
| [WorkingMemory](../../modules/memory/src/main/java/com/example/enterpriseagent/memory/working/WorkingMemory.java) | 租户、Run、图线程、格式版本 | `Optional<Snapshot>` / `Snapshot` | Saver 追加工作记忆，恢复当前计划的最新兼容快照和产物绑定 |
| [EpisodicMemory](../../modules/memory/src/main/java/com/example/enterpriseagent/memory/episode/EpisodicMemory.java) | 租户、案例、用户和可信范围 | `Optional<Episode>` / `Episode` | 从已完成的 Run 形成案例。索引召回后核对案例状态、用户或租户可见性及有效期 |
| [SemanticMemory](../../modules/memory/src/main/java/com/example/enterpriseagent/memory/semantic/SemanticMemory.java) | 租户、文档、可信访问策略引用 | `Optional<Version>` / `Publication` | 发布文档版本和分块，索引键带文档版本，读取时复核访问策略和当前生效版本 |

## 三段实际调用链

**会话读写。** `SessionMemory.write(entry) → AbstractMemory.write → SessionMemory.save → RedisSessionStore.save`。Redis 键的三段身份分别做 Base64 URL 编码，TTL 由 `Entry` 提供。读取用相同的键，过期或查无记录时返回空。

**聊天窗口。** 例如有效摘要的 `coveredThroughSeq=32`，Store 从同一会话读 `seq > 32` 且正文仍可见的最近消息，按时间顺序返回。摘要的 `historyRevision` 必须与会话当前值匹配。窗口条数由 `ConversationMemory.Key` 检查在 1 到 200 之间。正文和摘要返回受控引用，摘要生成器和正文对象存储留待业务接入。

**检索长期记忆。** `EpisodicMemory` 和 `SemanticMemory` 使用 PostgreSQL Store 与 Milvus VectorIndex。启用 `memory-loop` 后，发布和成功结算事务先保存源记录与索引作业，Worker 再生成 Embedding 并写入 Milvus。召回从 Milvus 取得候选，逐个回查 PostgreSQL。知识旧版本、过期案例及其他用户的案例会在回查时过滤。

## 开启记忆闭环

在 API 和 Worker 加上 `memory-loop`，已有库执行 [010 迁移](../../database/migrations/010-memory-loop.sql)。Worker 还需启用 `worker`。通过 `ENTERPRISE_GATEWAY_EMBEDDING_URI`、`EMBEDDING_API_KEY`、`EMBEDDING_MODEL` 配置 Embedding，`ENTERPRISE_EMBEDDING_DIMENSION` 必须与模型输出及 Milvus 集合一致。`enterprise.memory.minimum-score` 控制知识召回阈值。

| 数据 | 入库和更新 | 出库 |
| --- | --- | --- |
| 档案 | 用户用 `memory.profile.write` 凭证调用 `PUT /v1/memory/profile/{key}`，提交 `value` 与递增的 `revision`。支持 `locale` 和 `reportStyle` | Context Assembler 按租户和用户精确读取，调试产物显示命中的字段名。闲聊模型仅接收允许的偏好值 |
| 任务案例 | Run 成功时，与结算事务一起写入 `memory.task_episode` 和 `memory.index_job`。自动案例是用户私有、有效期 90 天，摘要保存业务场景、执行模式和结果状态 | 单个已就绪的工作流或开放任务按场景召回案例。ContextSnapshot 显示有权限的历史案例 ID |
| 知识 | 知识管理员通过 `PUT /v1/memory/knowledge/{document}/versions/{version}` 提交 `sourceKey` 和 `chunks`。更新使用更高版本，正文存入 `memory.knowledge_chunk_body` | `memory.knowledge.read` 用户提问时走本地向量检索，返回摘录及版本引用。轮次只存引用，刷新时重新检查当前有效版本 |
| 索引作业 | Worker 领取 `memory.index_job` 租约。可按同一引用幂等 Upsert，暂时失败按次数延后，第六次失败写入 `failed_at` | Milvus 只索引向量与引用。案例索引的文本为业务场景标识，知识索引的文本为发布的知识分块 |

例如第一次分析财务报表成功后，下一次同类请求的 `ContextSnapshot.memory.episodeIds` 可以看到先前案例。知识问答以摘要式摘录返回来源。知识换版后，历史轮次引用如果全部失效会显示 `KNOWLEDGE_VERSION_UNAVAILABLE`。

继续看 [基础设施模块](infrastructure.md)里的 SQL、事务和 Bean 如何把这些接口接到真实存储。
