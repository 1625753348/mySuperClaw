package com.example.enterpriseagent.infrastructure.objectstore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import software.amazon.awssdk.core.exception.SdkException;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/** 用数据库租约搬运正文。上传发生在领取和确认之间，期间正文仍可从 PostgreSQL 读取。 */
public final class ArtifactOffloadWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ArtifactOffloadWorker.class);
    private final JdbcClient db;
    private final S3ArtifactObjects objects;
    private final int threshold;
    private final boolean enabled;

    public ArtifactOffloadWorker(JdbcClient db, S3ArtifactObjects objects, int threshold, boolean enabled) {
        if (threshold < 4096 || threshold > 262_144) {
            throw new IllegalArgumentException("对象卸载阈值须在 4096 至 262144 字节之间");
        }
        this.db = db;
        this.objects = objects;
        this.threshold = threshold;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${enterprise.artifacts.object-storage.poll-ms:1000}")
    public void dispatch() {
        if (!enabled) return;
        claim().ifPresent(this::offload);
    }

    private Optional<Pending> claim() {
        return db.sql("""
                WITH candidate AS (
                    SELECT a.tenant_id,a.run_id,a.id
                    FROM runtime.context_artifact a JOIN runtime.artifact_body b
                      ON b.tenant_id=a.tenant_id AND b.run_id=a.run_id AND b.artifact_id=a.id
                    WHERE a.content_ref LIKE 'pg:%' AND a.kind IN ('TOOL_RESULT','OBSERVATION','REPORT')
                      AND a.artifact_key NOT IN ('gate','acceptance') AND a.size_bytes>=:threshold AND a.object_next_at<=now()
                    ORDER BY a.object_next_at,a.created_at LIMIT 1 FOR UPDATE OF a SKIP LOCKED
                ), leased AS (
                    UPDATE runtime.context_artifact a SET object_attempts=a.object_attempts+1,
                        object_next_at=now()+interval '60 seconds'
                    FROM candidate c WHERE a.tenant_id=c.tenant_id AND a.run_id=c.run_id AND a.id=c.id
                    RETURNING a.tenant_id,a.run_id,a.id,a.object_attempts
                )
                SELECT l.tenant_id,l.run_id,l.id,l.object_attempts,b.body::text
                FROM leased l JOIN runtime.artifact_body b
                  ON b.tenant_id=l.tenant_id AND b.run_id=l.run_id AND b.artifact_id=l.id
                """).param("threshold", threshold).query((rs, row) -> new Pending(rs.getString(1),
                rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getLong(4), rs.getString(5))).optional();
    }

    private void offload(Pending pending) {
        byte[] body = pending.body().getBytes(StandardCharsets.UTF_8);
        String key = S3ArtifactObjects.key(pending.tenant(), pending.run(), pending.id());
        if (body.length > S3ArtifactObjects.MAX_OBJECT_BYTES) {
            defer(pending, 900);
            return;
        }
        try {
            objects.write(key, body);
        } catch (SdkException ex) {
            if (pending.epoch() == 1 || (pending.epoch() & (pending.epoch() - 1)) == 0) {
                LOG.warn("无法上传运行产物，正文仍在 PostgreSQL [artifact={}, attempt={}]", pending.id(), pending.epoch());
            }
            defer(pending, Math.min(900, 10L << Math.min(7, pending.epoch() - 1)));
            return;
        }
        // 仅在持有当前租约时切换引用并删除内联正文，两项改动用一个 SQL 事务完成。
        db.sql("""
                WITH moved AS (
                    UPDATE runtime.context_artifact SET content_ref=:ref,content_digest=:digest,size_bytes=:size
                    WHERE tenant_id=:tenant AND run_id=:run AND id=:id AND object_attempts=:epoch
                      AND content_ref LIKE 'pg:%' AND object_next_at>now()
                    RETURNING tenant_id,run_id,id
                )
                DELETE FROM runtime.artifact_body b USING moved m
                WHERE b.tenant_id=m.tenant_id AND b.run_id=m.run_id AND b.artifact_id=m.id
                """).param("ref", S3ArtifactObjects.reference(key)).param("digest", S3ArtifactObjects.digest(body))
                .param("size", body.length).param("tenant", pending.tenant()).param("run", pending.run())
                .param("id", pending.id()).param("epoch", pending.epoch()).update();
    }

    private void defer(Pending pending, long seconds) {
        db.sql("""
                UPDATE runtime.context_artifact SET object_next_at=now()+:seconds*interval '1 second'
                WHERE tenant_id=:tenant AND run_id=:run AND id=:id AND object_attempts=:epoch AND content_ref LIKE 'pg:%'
                """).param("seconds", seconds).param("tenant", pending.tenant()).param("run", pending.run())
                .param("id", pending.id()).param("epoch", pending.epoch()).update();
    }

    private record Pending(String tenant, UUID run, UUID id, long epoch, String body) {}
}
