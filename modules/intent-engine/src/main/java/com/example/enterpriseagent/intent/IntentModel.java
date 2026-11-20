package com.example.enterpriseagent.intent;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class IntentModel {
    private IntentModel() {}

    public record Actor(String tenant, String user, String agent, Set<String> scopes, Set<String> roles) {}
    public record Request(String text, String skillKey, Map<String, Object> arguments, UUID targetTaskId,
                          UUID replyToMessageId) {}
    public record Definition(String id, String route, String skill, String risk, String requiredScope,
                             List<String> required, List<String> optional) {}
    public record Assessment(double complexityScore, double matchConfidence, String band, String strategy,
                             boolean criticalRiskOverride, String comparisonKey) {}
    public record IntentIR(String domain, String action, int definitionVersion, UUID taskId,
                           Map<String, Object> arguments, Map<String, String> provenance) {}
    public record Outcome(String intent, String relation, UUID taskId, String status, String route,
                          String skillHint, Map<String, Object> slots, List<String> missing, List<String> invalid,
                          String question, String reason, List<Integer> dependsOn, Assessment assessment, IntentIR ir) {
        public Outcome withRelation(String relation) {
            return new Outcome(intent, relation, taskId, status, route, skillHint, slots, missing, invalid,
                    question, reason, dependsOn, assessment, ir);
        }
    }
    public record Task(UUID id, String intent, Map<String, Object> slots, String status, long touched,
                       long updatedAt, Assessment assessment, String confirmationHash) {}
    public record State(long revision, List<Task> tasks, UUID focus, Map<String, Object> entities,
                        List<String> recent, String summary, long privacyRevision) {
        public static State empty() {
            return new State(0, List.of(), null, Map.of(), List.of(), "", 0);
        }
    }
    public record Analysis(List<Outcome> outcomes, State state, String reply) {}
    public record Receipt(UUID turnId, UUID assistantMessageId, List<Outcome> outcomes, String reply) {}

    public interface Store {
        UUID create(Actor actor);
        Receipt turn(Actor actor, UUID conversation, Request request, String key);
        Receipt receipt(Actor actor, UUID conversation, UUID turn);
    }

    public static final class Failure extends RuntimeException {
        private final int status;
        public Failure(int status, String message) { super(message); this.status = status; }
        public int status() { return status; }
    }
}
