package com.example.enterpriseagent.toolgateway.execution;

import com.example.enterpriseagent.toolgateway.adapter.ToolAdapter;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/** 同一端点的熔断器按租户隔离。未知结果只有在下游能用原操作键防重时才能重试。 */
public final class ToolResilience {
    private static final Set<String> TRANSIENT_ERRORS = Set.of(
            "UPSTREAM_SERVER_ERROR", "UPSTREAM_TIMEOUT", "UPSTREAM_IO_ERROR",
            "MCP_TRANSPORT_ERROR", "GRPC_UNAVAILABLE", "GRPC_DEADLINE_EXCEEDED",
            "DB_TIMEOUT", "ASYNC_TRANSPORT_ERROR");
    private final ConcurrentHashMap<Key, Circuit> circuits = new ConcurrentHashMap<>();

    public ToolAdapter.Outcome invoke(String tenant, UUID endpoint, int timeout, boolean safeToRetry,
                                     ToolPolicyService.Retry policy, IntFunction<ToolAdapter.Outcome> dispatch) {
        if (policy == null) return dispatch.apply(timeout);
        Circuit circuit = circuits.compute(new Key(tenant, endpoint), (key, current) ->
                current != null && current.policy.equals(policy) ? current : new Circuit(policy));
        if (!circuit.enter()) return new ToolAdapter.Outcome("FAILED", null, "CIRCUIT_OPEN");
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
            circuit.complete(outcome == null || isTransient(outcome));
        }
    }

    private static boolean isTransient(ToolAdapter.Outcome outcome) {
        return "UNKNOWN".equals(outcome.status()) && outcome.errorCode() != null
                && TRANSIENT_ERRORS.contains(outcome.errorCode());
    }

    private record Key(String tenant, UUID endpoint) {}

    private static final class Circuit {
        final ToolPolicyService.Retry policy;
        private int failures;
        private long openUntil;
        private boolean probing;

        Circuit(ToolPolicyService.Retry policy) { this.policy = policy; }

        synchronized boolean enter() {
            if (openUntil == 0) return true;
            if (probing || System.nanoTime() - openUntil < 0) return false;
            probing = true;
            return true;
        }

        synchronized void complete(boolean failed) {
            if (failed && (++failures >= policy.failureThreshold() || probing)) {
                openUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(policy.openSeconds());
            } else if (!failed) {
                failures = 0;
                openUntil = 0;
            }
            probing = false;
        }
    }
}
