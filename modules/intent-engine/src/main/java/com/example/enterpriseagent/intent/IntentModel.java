package com.example.enterpriseagent.intent;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.example.enterpriseagent.intent.IntentArtifacts.ParameterSchema;
import static com.example.enterpriseagent.intent.IntentArtifacts.InputObservation;

public final class IntentModel {
    private IntentModel() {}

    public record Actor(String tenant, String user, String agent, Set<String> scopes, Set<String> roles) {}
    public record Request(String text, String skillKey, Map<String, Object> arguments, UUID targetTaskId,
                          UUID replyToMessageId) {}
    public record Definition(String id, int version, String route, String skill, String risk, String requiredScope,
                             List<String> required, List<String> optional) {
        public ParameterSchema parameterSchema() {
            return new ParameterSchema(version, required, optional);
        }
    }
    public record Assessment(double complexityScore, double matchConfidence, String band, String strategy,
                             boolean criticalRiskOverride, String comparisonKey) {}
    public record WorkflowSkill(UUID id, String key, int version, int definitionVersion) {}
    public record WorkflowContext(String repository, String ref, String from, String to) {}
    public record Outcome(String intent, String relation, UUID taskId, String status, String route,
                          String skillHint, Map<String, Object> slots, List<String> missing, List<String> invalid,
                          String question, String reason, List<Integer> dependsOn, Assessment assessment, ValidatedIntentIR ir,
                          WorkflowSkill workflowSkill, Map<String, String> slotProvenance) {
        public Outcome(String intent, String relation, UUID taskId, String status, String route, String skillHint,
                       Map<String, Object> slots, List<String> missing, List<String> invalid, String question, String reason,
                       List<Integer> dependsOn, Assessment assessment, ValidatedIntentIR ir) {
            this(intent, relation, taskId, status, route, skillHint, slots, missing, invalid, question, reason, dependsOn,
                    assessment, ir, null, ir == null ? Map.of() : ir.provenance());
        }

        public Outcome {
            slotProvenance = slotProvenance == null ? (ir == null ? Map.of() : ir.provenance()) : Map.copyOf(slotProvenance);
        }

        public Outcome withRelation(String relation) {
            return new Outcome(intent, relation, taskId, status, route, skillHint, slots, missing, invalid,
                    question, reason, dependsOn, assessment, ir, workflowSkill, slotProvenance);
        }

        public Outcome withIR(ValidatedIntentIR value) {
            return new Outcome(intent, relation, taskId, status, route, skillHint, slots, missing, invalid,
                    question, reason, dependsOn, assessment, value, workflowSkill, slotProvenance);
        }
    }
    public record Task(UUID id, String intent, Map<String, Object> slots, String status, long touched,
                       long updatedAt, Assessment assessment, String confirmationHash, int definitionVersion,
                       WorkflowSkill workflowSkill) {
        public Task(UUID id, String intent, Map<String, Object> slots, String status, long touched, long updatedAt,
                    Assessment assessment, String confirmationHash, int definitionVersion) {
            this(id, intent, slots, status, touched, updatedAt, assessment, confirmationHash, definitionVersion, null);
        }
        public Task {
            // 旧快照未记录版本，旧目录中全部定义均为第 1 版。
            if (definitionVersion == 0) definitionVersion = 1;
        }
    }
    public record State(long revision, List<Task> tasks, UUID focus, Map<String, Object> entities,
                        List<String> recent, String summary, long privacyRevision) {
        public static State empty() {
            return new State(0, List.of(), null, Map.of(), List.of(), "", 0);
        }
    }
    public record Analysis(List<Outcome> outcomes, State state, String reply, InputObservation inputObservation,
                           Map<Integer, String> classifiedIntents) {}
    public record Receipt(UUID turnId, UUID assistantMessageId, List<Outcome> outcomes, String reply,
                          List<Execution> executions) {
        public Receipt {
            executions = executions == null ? List.of() : List.copyOf(executions);
        }
    }

    /** 执行结果由网关或运行时授权后即时读取，识别回执只持久化原始识别结果。 */
    public record Execution(int candidateOrdinal, String mode, UUID targetId, String status, String reason,
                            UUID approvalId, Object output, String resultUrl, String eventsUrl) {}

    public interface Store {
        UUID create(Actor actor);
        Receipt turn(Actor actor, UUID conversation, Request request, String key);
        Receipt receipt(Actor actor, UUID conversation, UUID turn);
        IntentArtifacts.TurnArtifacts artifacts(Actor actor, UUID conversation, UUID turn);
    }

    public static final class Failure extends RuntimeException {
        private final int status;
        public Failure(int status, String message) { super(message); this.status = status; }
        public int status() { return status; }
    }
}
