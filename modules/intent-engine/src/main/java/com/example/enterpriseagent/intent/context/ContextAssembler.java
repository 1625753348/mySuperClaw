package com.example.enterpriseagent.intent.context;

import com.example.enterpriseagent.intent.IntentArtifacts.ContextSnapshot;
import com.example.enterpriseagent.intent.IntentArtifacts.NormalizedInput;
import com.example.enterpriseagent.intent.IntentModel.State;
import com.example.enterpriseagent.intent.IntentModel.Task;

import java.util.List;
import java.util.Map;

/** 冻结经过会话归属校验的工作状态，分析阶段共享同一份只读视图。 */
public final class ContextAssembler {
    public ContextSnapshot assemble(NormalizedInput input, State authorized, long now) {
        List<Task> tasks = authorized.tasks().stream()
                .map(task -> new Task(task.id(), task.intent(), Map.copyOf(task.slots()), task.status(), task.touched(),
                        task.updatedAt(), task.assessment(), task.confirmationHash(), task.definitionVersion(), task.workflowSkill())).toList();
        return new ContextSnapshot(input.raw().conversationId(), new State(authorized.revision(), tasks,
                authorized.focus(), Map.copyOf(authorized.entities()), List.copyOf(authorized.recent()),
                authorized.summary(), authorized.privacyRevision()), now);
    }
}
