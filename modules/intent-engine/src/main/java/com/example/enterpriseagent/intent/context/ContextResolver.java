package com.example.enterpriseagent.intent.context;

import com.example.enterpriseagent.intent.IntentModel.State;
import com.example.enterpriseagent.intent.IntentModel.Task;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

import static com.example.enterpriseagent.intent.IntentArtifacts.*;

/** 从当前会话的已知任务中定位目标。找到多个兼容的待补参任务时保留歧义。 */
public final class ContextResolver {
    public static final long TASK_TTL_MS = 30 * 60 * 1000;
    private static final Pattern REPLACEMENT = Pattern.compile("改成|改为|换成|换个|呢[？?。]?$");
    private static final Pattern NEW_REQUEST = Pattern.compile(
            "^\\s*(?:(?:请|麻烦)\\s*)?(?:另外|顺便|再帮我|再发起|重新|另一个|新建)");

    /** 为单个候选选择历史目标。用于消歧的字段探测始终只读。 */
    public Resolution resolve(ContextSnapshot snapshot, UUID explicitId, String intent, String text,
                              boolean separateRequest, Function<Task, List<String>> probe) {
        State state = snapshot.state();
        long now = snapshot.capturedAt();
        Task explicit = find(state, explicitId);
        boolean modifying = REPLACEMENT.matcher(text).find();
        String relation = modifying ? "MODIFICATION" : "CONTINUATION";
        if (compatible(state, explicit, intent, now)) {
            return resolved(snapshot, explicit, relation, "EXPLICIT_TASK_REFERENCE", probe.apply(explicit));
        }
        if (explicitId != null) return noTarget("NEW_TASK", "EXPLICIT_TARGET_INCOMPATIBLE", List.of());
        if (intent != null && (separateRequest || NEW_REQUEST.matcher(text).find())) {
            return noTarget("NEW_TASK", "INDEPENDENT_REQUEST", List.of());
        }
        Task focus = find(state, state.focus());
        if (modifying && compatible(state, focus, intent, now)) {
            var fields = probe.apply(focus);
            if (!fields.isEmpty()) return resolved(snapshot, focus, relation, "FOCUS_MODIFICATION", fields);
        }
        var candidates = pending(state, intent, now).stream().filter(task -> collectingInput(snapshot, task))
                .map(task -> new Match(task, probe.apply(task)))
                .filter(match -> !match.fields().isEmpty()).toList();
        if (candidates.size() == 1) {
            var match = candidates.getFirst();
            return resolved(snapshot, match.task(), relation, "UNIQUE_COMPATIBLE_PENDING_TASK", match.fields());
        }
        if (candidates.size() > 1) {
            var expected = candidates.stream().filter(match -> expectsAnyField(snapshot, match)).toList();
            if (expected.size() == 1) {
                var match = expected.getFirst();
                return resolved(snapshot, match.task(), relation, "UNIQUE_MATCHING_CLARIFICATION", match.fields());
            }
            return noTarget("AMBIGUOUS", "MULTIPLE_COMPATIBLE_TASKS", candidates.stream().map(match -> match.task().id()).toList());
        }
        return noTarget("NEW_TASK", "NO_COMPATIBLE_PENDING_TASK", List.of());
    }

    public Resolution control(ContextSnapshot snapshot, UUID explicitId, String intent, String action) {
        State state = snapshot.state();
        if (explicitId != null) {
            Task task = find(state, explicitId);
            return usable(state, task, snapshot.capturedAt())
                    ? resolved(snapshot, task, action, "EXPLICIT_TASK_REFERENCE", List.of())
                    : noTarget(action, "TASK_UNAVAILABLE", List.of());
        }
        var recent = state.tasks().stream().filter(task -> task.touched() == state.revision())
                .filter(task -> usable(state, task, snapshot.capturedAt())).toList();
        if (intent == null && recent.size() > 1) {
            return noTarget("AMBIGUOUS", "MULTIPLE_RECENT_CONTROL_TARGETS", recent.stream().map(Task::id).toList());
        }
        Task task = "CONFIRM".equals(action) && intent == null ? find(state, state.focus()) : latest(state, intent);
        return usable(state, task, snapshot.capturedAt())
                ? resolved(snapshot, task, action, "RECENT_CONTROL_TARGET", List.of())
                : noTarget(action, "TASK_UNAVAILABLE", List.of());
    }

    public Resolution resolved(ContextSnapshot snapshot, Task task, String type, String reason, List<String> fields) {
        var stored = snapshot.details().tasks().get(task.id());
        var clarification = stored == null ? null : stored.clarification();
        var evidence = new java.util.ArrayList<String>();
        evidence.add(reason);
        if (!fields.isEmpty()) evidence.add("MATCHED_FIELDS:" + String.join(",", fields));
        if (clarification != null) evidence.add("OPEN_CLARIFICATION");
        if (stored != null && stored.run() != null) evidence.add("ACTIVE_RUN:" + stored.run().status());
        var relation = new ContextRelation(type, task.id(), stored == null ? null : stored.inputVersion(),
                List.copyOf(evidence), false, stored == null ? null : stored.intentId(),
                clarification == null ? null : clarification.id(), List.of(task.id()));
        return new Resolution(task, relation);
    }

    private static Resolution noTarget(String type, String evidence, List<UUID> candidates) {
        return new Resolution(null, new ContextRelation(type, null, null, List.of(evidence), "AMBIGUOUS".equals(type),
                null, null, candidates));
    }

    private boolean compatible(State state, Task task, String intent, long now) {
        return usable(state, task, now) && !"CANCELLED".equals(task.status())
                && (intent == null || intent.equals(task.intent()));
    }

    private static boolean expectsAnyField(ContextSnapshot snapshot, Match match) {
        var task = snapshot.details().tasks().get(match.task().id());
        return task != null && task.clarification() != null && match.fields().stream()
                .anyMatch(field -> task.clarification().missingSlots().contains(field) || task.clarification().invalidSlots().contains(field));
    }

    private static boolean collectingInput(ContextSnapshot snapshot, Task task) {
        var stored = snapshot.details().tasks().get(task.id());
        return stored == null || "COLLECTING_INPUT".equals(stored.status());
    }

    public record Resolution(Task task, ContextRelation relation) {}
    private record Match(Task task, List<String> fields) {}

    public Task find(State state, UUID id) {
        if (id == null) return null;
        return state.tasks().stream().filter(task -> id.equals(task.id())).findFirst().orElse(null);
    }

    public List<Task> pending(State state, String intent, long now) {
        return state.tasks().stream().filter(task -> "NEEDS_CLARIFICATION".equals(task.status()))
                .filter(task -> intent == null || intent.equals(task.intent()))
                .filter(task -> usable(state, task, now)).toList();
    }

    public Task latest(State state, String intent) {
        return state.tasks().stream().filter(task -> intent == null || intent.equals(task.intent()))
                .max(java.util.Comparator.comparingLong(Task::touched)).orElse(null);
    }

    public boolean usable(State state, Task task, long now) {
        return isUsable(state, task, now);
    }

    public static boolean isUsable(State state, Task task, long now) {
        return task != null && now - task.updatedAt() <= TASK_TTL_MS && state.revision() - task.touched() <= 20;
    }
}
