package com.example.enterpriseagent.runtime;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 长任务的入参、状态与运行时端口契约。 */
public final class RunModel {
    private RunModel() {}

    public static final String GRAPH = "sales-report-v1";
    public static final String GATE = "sales-report-gate-v1";
    public static final String FIXED_GRAPH = "published-workflow-v1";
    public static final String GENERAL_GRAPH = "general-plan-v1";
    public static final String CHILD_GRAPH = "subagent-v1";
    public static final String GENERAL_GATE = "general-hard-gate-v1";

    public record Actor(String tenant, String user, String agent, List<String> roles) {
        public Actor { roles = List.copyOf(roles == null ? List.of() : roles); }
    }
    public record Source(String name, String version, JsonNode arguments) {}
    public record Request(String mode, String goal, List<String> dimensions, JsonNode rows, Source source,
                          Integer maxIterations, Integer deadlineSeconds) {}
    public record Run(String tenant, UUID id, UUID taskId, String userId, String agentId, String traceId, String route,
                      int activePlanVersion, String status, long lastEventSeq, String gatePolicyVersion,
                      int maxIterations, Instant deadlineAt, String terminalReason, String resultRef,
                      boolean cancelled, boolean expired, int plannerCalls, String graphVersion) {
        public boolean terminal() {
            return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
        }
    }
    public record View(UUID runId, String status, long eventSeq, int planVersion, int iteration,
                       List<String> blockers, String reason, String resultUrl, String eventsUrl) {}
    public record Event(long seq, String eventType, String payload) {}
    public record Receipt(UUID runId, String requestHash, String roles) {}
    public record CandidateRun(String tenant, UUID id) {}
    public record NodeSpec(String action, String dimension, UUID reuseArtifact, List<String> dependencies) {
        public NodeSpec { dependencies = List.copyOf(dependencies); }
    }
    public record FixedNode(String id, String handler, String toolName, String toolVersion, UUID toolVersionId,
                            List<String> dependsOn, Map<String, String> inputs, boolean result,
                            SkillVersion skillVersion, String skillNode, String subagent, SubagentPolicy resilience, JsonNode inputSchema) {
        public FixedNode(String id, String handler, String toolName, String toolVersion, UUID toolVersionId,
                         List<String> dependsOn, Map<String, String> inputs, boolean result, SkillVersion skillVersion,
                         String skillNode, String subagent, SubagentPolicy resilience) {
            this(id, handler, toolName, toolVersion, toolVersionId, dependsOn, inputs, result, skillVersion, skillNode,
                    subagent, resilience, null);
        }
        public FixedNode(String id, String handler, String toolName, String toolVersion, UUID toolVersionId,
                         List<String> dependsOn, Map<String, String> inputs, boolean result, SkillVersion skillVersion,
                         String skillNode, String subagent) {
            this(id, handler, toolName, toolVersion, toolVersionId, dependsOn, inputs, result, skillVersion, skillNode, subagent, null);
        }
        public FixedNode(String id, String handler, String toolName, String toolVersion, UUID toolVersionId,
                         List<String> dependsOn, Map<String, String> inputs, boolean result, SkillVersion skillVersion,
                         String skillNode) {
            this(id, handler, toolName, toolVersion, toolVersionId, dependsOn, inputs, result, skillVersion, skillNode, null);
        }
        public FixedNode(String id, String handler, String toolName, String toolVersion, UUID toolVersionId,
                         List<String> dependsOn, Map<String, String> inputs, boolean result) {
            this(id, handler, toolName, toolVersion, toolVersionId, dependsOn, inputs, result, null, null, null);
        }
    }
    public enum SubagentFailureAction { FAIL, DROP, DEGRADE }
    public record SubagentPolicy(Integer timeoutSeconds, Integer maxRetries, SubagentFailureAction onFailure,
                                 Map<String, Object> fallback) {
        public SubagentPolicy {
            timeoutSeconds = timeoutSeconds == null ? 1800 : timeoutSeconds;
            maxRetries = maxRetries == null ? 0 : maxRetries;
            onFailure = onFailure == null ? SubagentFailureAction.FAIL : onFailure;
            fallback = fallback == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(fallback));
            if (timeoutSeconds < 1 || timeoutSeconds > 1800 || maxRetries < 0 || maxRetries > 2
                    || (onFailure == SubagentFailureAction.DEGRADE) == fallback.isEmpty()) {
                throw new IllegalArgumentException("SubAgent 失败策略配置无效");
            }
        }
    }
    public record SkillVersion(UUID id, String key, int version) {}
    public record FixedPlan(UUID skillVersionId, UUID taskId, int inputVersion, List<FixedNode> nodes) {}
    public record Step(UUID id, String nodeKey, String kind, String spec, String status, String operationKey,
                       UUID toolExecutionId, UUID observationId, int currentAttempt, long leaseEpoch, String leaseOwner) {}
    public record PlanProposal(UUID id, int basePlanVersion, String kind, long leaseEpoch, String leaseOwner, int attempts,
                           String triggerReason) {}
    public record Artifact(UUID id, String artifactKey, String body) {}
    public record Work(Run run, Actor actor, Request request, JsonNode fixedInput, Step step, PlanProposal proposal) {}
    public record PlannerContext(String goal, List<String> dimensions, List<String> completed,
                                 List<String> missing, int iteration, int remainingIterations,
                                 int remainingPlannerCalls, Map<String, UUID> observations) {}
    public record ToolStatus(UUID executionId, String status, JsonNode output, UUID approvalId, String reason) {}
}
