package com.example.enterpriseagent.memory.working;

import com.example.enterpriseagent.memory.AbstractMemory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 工作记忆按运行线程恢复结构化状态，单独使用 Saver。 */
public final class WorkingMemory extends AbstractMemory<WorkingMemory.Key,
        Optional<WorkingMemory.Snapshot>, WorkingMemory.Snapshot> {

    private final CheckpointSaver saver;

    public WorkingMemory(CheckpointSaver saver) {
        this.saver = saver;
    }

    @Override
    protected Optional<Snapshot> load(Key key) {
        return saver.loadLatest(key);
    }

    @Override
    protected void save(Snapshot snapshot) {
        saver.append(snapshot);
    }

    public record Key(String tenantId, UUID runId, String graphThreadKey, int formatVersion) {}
    public record Binding(String bindingKey, UUID artifactId) {}
    public record Snapshot(String tenantId, UUID runId, long seq, int planVersion, UUID afterStepId,
                           String graphThreadKey, int formatVersion, String graphStateJson, List<Binding> bindings) {}
}
