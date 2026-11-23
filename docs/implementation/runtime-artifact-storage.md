# 运行产物的对象存储与断点恢复

运行产物是节点完成后需要再次读取的正文，例如工具返回、维度汇总和最后的报告。检查点保存的是引用。即使正文从 PostgreSQL 搬到 S3 兼容对象存储，产物 ID、所属 Run 和检查点引用都保持稳定。

## 一次读写从哪里开始

| 时机 | 代码 | 写入或读取 |
| --- | --- | --- |
| 节点结算 | `RunManager.writeArtifact`、`RunMapper.insertArtifact` | 在结算事务内写入 `runtime.context_artifact` 和 `runtime.artifact_body`，同时推进节点状态、事件和检查点。元数据的 `content_ref` 以 `pg:` 开头 |
| 后台领取 | `ArtifactOffloadWorker.claim` | 从已提交且达到阈值的工具结果、观察和报告中领取一条，递增 `object_attempts`，把 `object_next_at` 作为 60 秒租约截止时间 |
| 上传 | `S3ArtifactObjects.write` | 在数据库事务外写入私有 Bucket。对象键由租户哈希、Run ID、产物 ID 生成。同一个产物重领后仍使用同一个键 |
| 确认 | `ArtifactOffloadWorker.offload` | 核对当前租约，在同一个 SQL 事务中把 `content_ref` 改为 `obj:`，保存上传字节的 SHA-256 与长度，再删除内联正文 |
| 断点续跑 | `RunManager.restore`、`CheckpointSaver`、`ArtifactBodyReader` | 恢复当前计划检查点和产物 ID。按租户、Run 和产物 ID 查元数据，再从 PostgreSQL 或对象存储读取正文 |
| 获取结果 | `GET /v1/runs/{id}/result` | 先校验 Run 归属及证据权限，再按相同路径读取 DTO。对象键和凭证留在服务端 |

例如一个销售分析节点写入 80 KiB 的工具返回，检查点记录 `source → artifactId`。如果下一节点先领取，它直接从 PostgreSQL 读取 JSON。如果 Worker 先完成搬运，下一节点会使用同一个 `artifactId` 自动获取对象，校验长度和 SHA-256，再将对象解析为同一份 JSON。重启后仍可按检查点继续。

## 故障与升级行为

- **上传前停机或上传失败。** PostgreSQL 正文仍可读取。租约超时后其他 Worker 可接管。上传失败时按退避时间继续尝试。
- **上传成功后、确认前停机。** 原正文仍在数据库。重领时会向同一个对象键写入同一份 JSON，再按当前租约确认。过期 Worker 的确认会被拒绝。
- **正文已卸载，远端不可读或摘要不符。** 正在执行的节点记录 `ARTIFACT_BODY_UNAVAILABLE` 并重试，受节点次数和运行截止时间约束。读取最终结果的 API 返回 HTTP 503，可在对象存储恢复后重新读取。
- **升级已有库。** 原有 `pg:` 正文仍可读取。打开 Worker 的卸载后，达到阈值的旧产物也可以逐条搬运。规划提案与验收证据继续使用内联正文，现有管理查询可直接读取它们。

## 配置和验证

已有数据库执行 [020 迁移](../../database/migrations/020-runtime-artifact-objects.sql)。请先创建私有 Bucket，再为所有运行 API 和 Worker 配置同一个 Bucket。凭证从部署环境注入。AWS SDK 使用路径式寻址访问 S3 兼容端点。 [S3 SDK 官方文档](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3.html)

```yaml
enterprise:
  artifacts:
    object-storage:
      enabled: true
      offload-enabled: true
      endpoint: ${ARTIFACT_S3_ENDPOINT}
      bucket: runtime-artifacts
      region: us-east-1
      access-key: ${ARTIFACT_S3_ACCESS_KEY}
      secret-key: ${ARTIFACT_S3_SECRET_KEY}
      threshold-bytes: 65536
      poll-ms: 1000
```

`enabled` 默认关闭。启用后，带 `worker` Profile 的实例负责搬运。将 `offload-enabled` 设为 `false` 可以暂停搬运并保留已有对象的读取能力。数据库备份和 Bucket 保留策略需要覆盖相同的运行历史。

用以下查询可以在数据库里对照同一个产物在搬运前后的状态。

```sql
SELECT a.id, a.artifact_key, a.content_ref, a.size_bytes, b.artifact_id IS NOT NULL AS inline_body
FROM runtime.context_artifact a
LEFT JOIN runtime.artifact_body b
  ON b.tenant_id = a.tenant_id AND b.run_id = a.run_id AND b.artifact_id = a.id
WHERE a.tenant_id = :tenant AND a.run_id = :run
ORDER BY a.created_at;
```

[卸载 Worker](../../modules/infrastructure/src/main/java/com/example/enterpriseagent/infrastructure/objectstore/ArtifactOffloadWorker.java) · [S3 适配器](../../modules/infrastructure/src/main/java/com/example/enterpriseagent/infrastructure/objectstore/S3ArtifactObjects.java) · [RunManager 源码](../../modules/agent-runtime/src/main/java/com/example/enterpriseagent/runtime/RunManager.java)
