package com.example.enterpriseagent.intent.context;

import com.example.enterpriseagent.intent.IntentModel.State;
import com.example.enterpriseagent.intent.IntentModel.Task;

import java.util.List;
import java.util.UUID;

/** 从当前会话的已知任务中定位目标。找到多个兼容的待补参任务时保留歧义。 */
public final class ContextResolver {
    public static final long TASK_TTL_MS = 30 * 60 * 1000;

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
        return task != null && now - task.updatedAt() <= TASK_TTL_MS && state.revision() - task.touched() <= 20;
    }
}
