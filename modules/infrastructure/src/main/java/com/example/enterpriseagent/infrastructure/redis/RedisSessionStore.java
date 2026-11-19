package com.example.enterpriseagent.infrastructure.redis;

import com.example.enterpriseagent.memory.session.SessionMemory;
import com.example.enterpriseagent.memory.store.MemoryStore;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/** 缓存键分别编码三段身份，避免租户和会话字符串中的分隔符产生冲突。 */
public final class RedisSessionStore implements MemoryStore<SessionMemory.Key, Optional<String>, SessionMemory.Entry> {

    private final StringRedisTemplate redis;

    public RedisSessionStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<String> load(SessionMemory.Key key) {
        return Optional.ofNullable(redis.opsForValue().get(redisKey(key)));
    }

    @Override
    public void save(SessionMemory.Entry value) {
        if (value.ttl().isNegative() || value.ttl().isZero()) {
            throw new IllegalArgumentException("会话 TTL 必须大于零");
        }
        redis.opsForValue().set(redisKey(value.key()), value.contextJson(), value.ttl());
    }

    private static String redisKey(SessionMemory.Key key) {
        return "enterprise-agent:session:" + segment(key.tenantId()) + ':' + segment(key.userId()) + ':' + segment(key.sessionId());
    }

    private static String segment(String value) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("会话身份字段不能为空");
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
