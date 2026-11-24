package com.example.enterpriseagent.infrastructure.gateway;

import com.example.enterpriseagent.toolgateway.execution.ToolPolicyService;
import com.example.enterpriseagent.toolgateway.execution.ToolResilience;
import com.example.enterpriseagent.toolgateway.registry.ToolContracts;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.UUID;

/** Redis 脚本原子维护租户、端点与策略版本对应的共享熔断状态。 */
public final class RedisToolCircuit implements ToolResilience.State {
    private static final RedisScript<String> ENTER =
            RedisScript.of(new ClassPathResource("tool-gateway/circuit-enter.lua"), String.class);
    private static final RedisScript<Long> COMPLETE =
            RedisScript.of(new ClassPathResource("tool-gateway/circuit-complete.lua"), Long.class);
    private final StringRedisTemplate redis;

    public RedisToolCircuit(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public ToolResilience.Permit enter(ToolResilience.Key key, int policyVersion, int timeoutMs) {
        UUID operation = UUID.randomUUID();
        try {
            String epoch = redis.execute(ENTER, List.of(key(key)), Integer.toString(policyVersion),
                    operation.toString(), Long.toString(timeoutMs + 5_000L));
            return epoch == null ? null : new ToolResilience.Permit(epoch, operation);
        } catch (DataAccessException ex) {
            throw new ToolResilience.StateUnavailable();
        }
    }

    @Override
    public void complete(ToolResilience.Key key, ToolResilience.Permit permit, ToolPolicyService.Retry policy, boolean failed) {
        try {
            redis.execute(COMPLETE, List.of(key(key)), Integer.toString(policy.version()), permit.epoch(),
                    permit.operation().toString(), failed ? "1" : "0", Integer.toString(policy.failureThreshold()),
                    Long.toString(policy.openSeconds() * 1_000L));
        } catch (DataAccessException ex) {
            throw new ToolResilience.StateUnavailable();
        }
    }

    private static String key(ToolResilience.Key key) {
        return "agent:gateway:circuit:v1:" + ToolContracts.digest(key.tenant()) + ":" + key.endpoint();
    }
}
