package com.example.enterpriseagent.infrastructure.persistence;

import com.example.enterpriseagent.memory.working.CheckpointSaver;
import com.example.enterpriseagent.memory.working.WorkingMemory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

/** 实际追加检查点及其绑定，写入可参加调用方的 Run 事务。 */
public final class PostgresCheckpointSaver implements CheckpointSaver {

    private final MemoryMapper mapper;
    private final TransactionTemplate read;
    private final TransactionTemplate write;

    public PostgresCheckpointSaver(MemoryMapper mapper, PlatformTransactionManager transactions) {
        this.mapper = mapper;
        this.write = new TransactionTemplate(transactions);
        this.read = new TransactionTemplate(transactions);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public Optional<WorkingMemory.Snapshot> loadLatest(WorkingMemory.Key key) {
        return read.execute(ignored -> Optional.ofNullable(mapper.latestCheckpoint(key)).map(row ->
                new WorkingMemory.Snapshot(row.tenantId(), row.runId(), row.seq(), row.planVersion(),
                        row.afterStepId(), row.graphThreadKey(), row.formatVersion(), row.graphStateJson(),
                        mapper.bindings(row))));
    }

    @Override
    public void append(WorkingMemory.Snapshot snapshot) {
        write.executeWithoutResult(ignored -> {
            Integer activePlan = mapper.lockRun(snapshot);
            if (activePlan == null || activePlan != snapshot.planVersion() || mapper.appendCheckpoint(snapshot) != 1) {
                throw new IllegalStateException("运行计划或检查点序号已经变化");
            }
            for (WorkingMemory.Binding binding : snapshot.bindings()) {
                mapper.bindArtifact(snapshot, binding);
            }
        });
    }
}
