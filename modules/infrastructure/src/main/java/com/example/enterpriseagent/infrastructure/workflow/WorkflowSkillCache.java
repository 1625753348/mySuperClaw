package com.example.enterpriseagent.infrastructure.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/** 跨实例共享的版本缓存。新、旧版本各用自己的键，旧事件无法覆盖新版本。 */
public final class WorkflowSkillCache {
    private final JdbcClient db;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Duration ttl;

    public WorkflowSkillCache(JdbcClient db, StringRedisTemplate redis, ObjectMapper json, Duration ttl) {
        if (ttl.isZero() || ttl.isNegative()) throw new IllegalArgumentException("Skill 缓存 TTL 必须大于零");
        this.db = db;
        this.redis = redis;
        this.json = json;
        this.ttl = ttl;
    }

    public Optional<JsonNode> read(String tenant, UUID version) {
        try {
            String body = redis.opsForValue().get(key(tenant, version));
            return body == null ? Optional.empty() : Optional.ofNullable(json.readTree(body)).filter(JsonNode::isObject);
        } catch (DataAccessException | JsonProcessingException ex) {
            return Optional.empty();
        }
    }

    public void remember(String tenant, UUID version, JsonNode dag) {
        try {
            redis.opsForValue().set(key(tenant, version), dag.toString(), ttl);
        } catch (DataAccessException ex) {
            // API 即使遇到 Redis 故障，也可以从数据库返回已发布的版本。
        }
    }

    public void warmCurrent(String tenant, UUID skill) {
        db.sql("""
                SELECT v.id,v.compiled_dag::text
                FROM runtime.workflow_skill s JOIN runtime.workflow_skill_version v
                  ON v.tenant_id=s.tenant_id AND v.skill_id=s.id
                WHERE s.tenant_id=? AND s.id=? AND s.status='ACTIVE' AND v.is_current
                """).params(tenant, skill)
                .query((rs, row) -> new Body(rs.getObject(1, UUID.class), rs.getString(2))).optional()
                // 这里透传 Redis 错误，回滚 Inbox，留给消费组再次尝试。
                .ifPresent(body -> redis.opsForValue().set(key(tenant, body.id()), body.dag(), ttl));
    }

    private static String key(String tenant, UUID version) {
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(tenant.getBytes(StandardCharsets.UTF_8));
        return "agent:workflow-skill:dag:v1:" + encoded + ":" + version;
    }

    private record Body(UUID id, String dag) {}
}
