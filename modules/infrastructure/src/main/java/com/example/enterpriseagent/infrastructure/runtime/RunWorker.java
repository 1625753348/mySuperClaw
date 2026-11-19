package com.example.enterpriseagent.infrastructure.runtime;

import com.example.enterpriseagent.runtime.RunManager;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/** 小批领取，可独立运行的节点并行处理。消费者退出后由租约接管未完成节点。 */
public final class RunWorker implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(RunWorker.class.getName());
    private final String id = "run-worker-" + UUID.randomUUID();
    private final RunManager manager;
    private final int leaseSeconds;
    private final Semaphore slots = new Semaphore(4);
    private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public RunWorker(RunManager manager, int leaseSeconds) {
        if (leaseSeconds < 3 || leaseSeconds > 600) throw new IllegalArgumentException("运行租约需要 3 至 600 秒");
        this.manager = manager;
        this.leaseSeconds = leaseSeconds;
    }

    @Scheduled(fixedDelay = 200)
    public void poll() {
        for (int i = 0; i < 4 && slots.tryAcquire(); i++) {
            com.example.enterpriseagent.runtime.RunModel.Work work;
            try {
                work = manager.claim(id, leaseSeconds);
            } catch (RuntimeException ex) {
                slots.release();
                throw ex;
            }
            if (work == null) {
                slots.release();
                return;
            }
            try {
                executor.execute(() -> {
                    try {
                        manager.execute(work);
                    } catch (RuntimeException ex) {
                        LOG.log(System.Logger.Level.ERROR, "运行结算失败，等待节点租约接管。Run " + work.run().id()
                                + "，错误类型 " + ex.getClass().getSimpleName());
                    } finally {
                        slots.release();
                    }
                });
            } catch (RuntimeException ex) {
                slots.release();
                throw ex;
            }
        }
    }

    @Override public void close() { executor.shutdownNow(); }
}
