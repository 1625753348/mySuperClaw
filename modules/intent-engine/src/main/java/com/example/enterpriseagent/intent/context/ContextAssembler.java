package com.example.enterpriseagent.intent.context;

import com.example.enterpriseagent.intent.IntentModel.Actor;
import com.example.enterpriseagent.intent.IntentModel.Outcome;
import com.example.enterpriseagent.intent.IntentModel.State;
import com.example.enterpriseagent.intent.IntentModel.Task;

import java.util.List;
import java.util.Map;

import static com.example.enterpriseagent.intent.IntentArtifacts.*;

/** 冻结经过会话归属校验的工作状态，分析阶段共享同一份只读视图。 */
public final class ContextAssembler {
    private final Reader reader;

    public ContextAssembler(Reader reader) { this.reader = reader; }

    public ContextSnapshot assemble(NormalizedInput input, State authorized, long now) {
        List<Task> tasks = authorized.tasks().stream()
                .map(task -> new Task(task.id(), task.intent(), Map.copyOf(task.slots()), task.status(), task.touched(),
                        task.updatedAt(), task.assessment(), task.confirmationHash(), task.definitionVersion(), task.workflowSkill())).toList();
        return new ContextSnapshot(input.raw().conversationId(), new State(authorized.revision(), tasks,
                authorized.focus(), Map.copyOf(authorized.entities()), List.copyOf(authorized.recent()),
                authorized.summary(), authorized.privacyRevision()), now, ContextDetails.EMPTY);
    }

    /** 关系解析需要持久化证据时再加载，快照的修订号和时间沿用初始视图。 */
    public ContextSnapshot attach(NormalizedInput input, ContextSnapshot snapshot, Source source) {
        if (source == null || snapshot.state().tasks().isEmpty()) return snapshot;
        var taskIds = snapshot.state().tasks().stream().filter(task -> !"CANCELLED".equals(task.status()))
                .map(Task::id).toList();
        if (taskIds.isEmpty()) return snapshot;
        var raw = input.raw();
        var details = source.load(new ContextRequest(raw.actor(), raw.conversationId(), raw.request().replyToMessageId(), taskIds));
        return new ContextSnapshot(snapshot.conversationId(), snapshot.state(), snapshot.capturedAt(), details);
    }

    public InputObservation observe(NormalizedInput input, ContextSnapshot snapshot) {
        var details = snapshot.details();
        ContextCounts counts = details == ContextDetails.EMPTY ? null : new ContextCounts(details.recentMessages().size(),
                (int) details.tasks().values().stream().filter(task -> task.clarification() != null).count(),
                input.raw().actor().scopes().contains("runs.read")
                        ? (int) details.tasks().values().stream().filter(task -> task.run() != null).count() : null);
        return new InputObservation(input.text(), snapshot.state().revision(), snapshot.capturedAt(),
                snapshot.state().tasks().size(), snapshot.state().recent().size(), null, counts);
    }

    public InputObservation enrich(Actor actor, InputObservation observed, List<Outcome> outcomes) {
        if (reader == null || outcomes.size() != 1) return observed;
        Outcome selected = outcomes.getFirst();
        boolean business = "READY".equals(selected.status()) && List.of("AGENT", "WORKFLOW").contains(selected.route());
        boolean question = "ROUTED".equals(selected.status()) && List.of("CHAT", "KNOWLEDGE").contains(selected.route());
        if (!business && !question) return observed;
        MemoryContext notes = reader.load(actor, business && actor.scopes().contains("runs.read") ? selected.intent() : null);
        return new InputObservation(observed.normalizedText(), observed.snapshotRevision(), observed.capturedAt(),
                observed.taskCount(), observed.recentTurnCount(), notes, observed.contextCounts());
    }

    @FunctionalInterface public interface Reader { MemoryContext load(Actor actor, String scenario); }
    @FunctionalInterface public interface Source { ContextDetails load(ContextRequest request); }
}
