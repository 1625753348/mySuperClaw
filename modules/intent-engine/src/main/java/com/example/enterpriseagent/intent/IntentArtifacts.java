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

    public record IntentCandidate(Definition definition, double confidence, String strategy) {}

    public record ContextSnapshot(UUID conversationId, State state, long capturedAt) {}
    public record ContextRelation(String type, UUID targetTaskId, Integer targetInputVersion,
                                  List<String> evidence, boolean needsDisambiguation) {}
    public record InputObservation(String normalizedText, long snapshotRevision, long capturedAt,
                                   int taskCount, int recentTurnCount, MemoryContext memory) {}

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
