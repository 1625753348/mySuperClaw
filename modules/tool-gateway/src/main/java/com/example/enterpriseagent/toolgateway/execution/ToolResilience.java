package com.example.enterpriseagent.toolgateway.execution;

import com.example.enterpriseagent.toolgateway.adapter.ToolAdapter;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/** 同一端点的熔断器按租户隔离。未知结果只有在下游能用原操作键防重时才能重试。 */
public final class ToolResilience {
    private static final Set<String> TRANSIENT_ERRORS = Set.of(
            "UPSTREAM_SERVER_ERROR", "UPSTREAM_TIMEOUT", "UPSTREAM_IO_ERROR",
            "MCP_TRANSPORT_ERROR", "GRPC_UNAVAILABLE", "GRPC_DEADLINE_EXCEEDED",
            "DB_TIMEOUT", "ASYNC_TRANSPORT_ERROR");
    private final State states;

    public ToolResilience(State states) {
        this.states = states;
    }

    public ToolAdapter.Outcome invoke(String tenant, UUID endpoint, int timeout, boolean safeToRetry,
                                     ToolPolicyService.Retry policy, IntFunction<ToolAdapter.Outcome> dispatch) {
        if (policy == null) return dispatch.apply(timeout);
        var key = new Key(tenant, endpoint);
        Permit permit;
        try {
            permit = states.enter(key, policy.version(), timeout);
        } catch (StateUnavailable ex) {
            // 共享状态故障时停止派发，避免各实例各自绕过熔断。
            return new ToolAdapter.Outcome("FAILED", null, "CIRCUIT_STATE_UNAVAILABLE");
        }
        if (permit == null) return new ToolAdapter.Outcome("FAILED", null, "CIRCUIT_OPEN");
        int attempts = safeToRetry ? Math.min(policy.maxAttempts(), Math.max(1, timeout / 100)) : 1;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        ToolAdapter.Outcome outcome = null;
        try {
            for (int attempt = 0; attempt < attempts; attempt++) {
                long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remaining <= 0 && outcome != null) break;
                int budget = Math.max(1, (int) (remaining / (attempts - attempt)));
                outcome = dispatch.apply(budget);
                if (!isTransient(outcome)) break;
            }
            return outcome;
        } finally {
            try {
                states.complete(key, permit, policy, outcome == null || isTransient(outcome));
            } catch (StateUnavailable ex) {
                // 上游可能已执行。不得用共享状态故障覆盖真实执行结果。
            }
        }
    }

    private static boolean isTransient(ToolAdapter.Outcome outcome) {
        return "UNKNOWN".equals(outcome.status()) && outcome.errorCode() != null
                && TRANSIENT_ERRORS.contains(outcome.errorCode());
    }

    public record Key(String tenant, UUID endpoint) {}
    public record Permit(String epoch, UUID operation) {}

    public interface State {
        Permit enter(Key key, int policyVersion, int timeoutMs);
        void complete(Key key, Permit permit, ToolPolicyService.Retry policy, boolean failed);
    }

    public static final class StateUnavailable extends RuntimeException {}
}
