package com.example.enterpriseagent.memory.working;

import java.util.Optional;

/** 平台工作流快照端口，调用方可在 Run 状态事务中追加快照。 */
public interface CheckpointSaver {

    Optional<WorkingMemory.Snapshot> loadLatest(WorkingMemory.Key key);

    void append(WorkingMemory.Snapshot snapshot);
}
