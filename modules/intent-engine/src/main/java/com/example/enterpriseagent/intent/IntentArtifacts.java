package com.example.enterpriseagent.intent;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.example.enterpriseagent.intent.IntentModel.*;

/** 意图分析各阶段交换的数据。请求身份只来自通过认证的接入层。 */
public final class IntentArtifacts {
    private IntentArtifacts() {}

    public record RawRequest(Actor actor, UUID conversationId, String idempotencyKey, Request request) {}
    public record NormalizedInput(RawRequest raw, String text) {}

    public record IntentCandidate(Definition definition, double confidence, String strategy, RoutingTrace routing, boolean outOfScope) {
        public IntentCandidate(Definition definition, double confidence, String strategy) {
            this(definition, confidence, strategy, null);
        }
        public IntentCandidate(Definition definition, double confidence, String strategy, RoutingTrace routing) {
            this(definition, confidence, strategy, routing, false);
        }
    }
    public record RoutingTrace(double score, String band, String policyVersion, List<String> candidates, boolean modelRequested) {}

    public record ContextRequest(Actor actor, UUID conversationId, UUID replyToMessageId, List<UUID> taskIds) {}
    public record RecentMessage(long sequence, String role, String content) {}
    public record PendingClarification(UUID id, int inputVersion, List<String> missingSlots, List<String> invalidSlots) {
        public PendingClarification {
            missingSlots = List.copyOf(missingSlots);
            invalidSlots = List.copyOf(invalidSlots);
        }
    }
    public record ActiveRun(UUID id, String status, int inputVersion) {}
    public record TaskContext(UUID intentId, int inputVersion, String status,
                              PendingClarification clarification, ActiveRun run) {}
    public record ContextDetails(List<RecentMessage> recentMessages, Map<UUID, TaskContext> tasks) {
        public static final ContextDetails EMPTY = new ContextDetails(List.of(), Map.of());
        public ContextDetails {
            recentMessages = List.copyOf(recentMessages);
            tasks = Map.copyOf(tasks);
        }
    }
    public record ContextSnapshot(UUID conversationId, State state, long capturedAt, ContextDetails details) {}
    public record ContextRelation(String type, UUID targetTaskId, Integer targetInputVersion,
                                  List<String> evidence, boolean needsDisambiguation, UUID targetIntentId,
                                  UUID targetClarificationId, List<UUID> candidateTaskIds) {
        public ContextRelation(String type, UUID targetTaskId, Integer targetInputVersion,
                               List<String> evidence, boolean needsDisambiguation) {
            this(type, targetTaskId, targetInputVersion, evidence, needsDisambiguation, null, null, List.of());
        }
    }
    public record ContextCounts(int recentMessageCount, int pendingClarificationCount, Integer activeRunCount) {}
    public record InputObservation(String normalizedText, long snapshotRevision, long capturedAt,
                                   int taskCount, int recentTurnCount, MemoryContext memory, ContextCounts contextCounts) {}

    /** 调试只保存被读取的档案键和授权案例引用。 */
    public record MemoryContext(List<String> profileKeys, List<String> episodeIds) {}

    public record ParameterSchema(int definitionVersion, List<String> required, List<String> optional) {}
    public record FilledSlots(Map<String, Object> values, List<String> invalid) {}
    public record SlotState(List<String> missing, List<String> invalid) {
        public boolean complete() {
            return missing.isEmpty() && invalid.isEmpty();
        }
    }

    /** 供同一会话的调用方查看。候选编号与回执的 outcomes 位置一致。 */
    public record TurnArtifacts(UUID turnId, String status, List<Stage> intake,
                                List<CandidateArtifacts> candidates, Stage response) {}
    public record CandidateArtifacts(int ordinal, List<Stage> stages) {}
    public record Stage(String artifact, String status, Object data) {}
}
