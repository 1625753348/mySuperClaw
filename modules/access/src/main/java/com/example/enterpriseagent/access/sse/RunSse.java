package com.example.enterpriseagent.access.sse;

import com.example.enterpriseagent.runtime.RunFailure;
import com.example.enterpriseagent.runtime.RunManager;
import com.example.enterpriseagent.runtime.RunModel.Actor;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** 游标始终从 PostgreSQL 回放，断线或 Redis 延迟都不会跳过事件。 */
public final class RunSse implements AutoCloseable {
    private final Semaphore slots = new Semaphore(64);
    private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final RunManager manager;

    public RunSse(RunManager manager) { this.manager = manager; }

    public SseEmitter open(Actor actor, UUID id, long after, Instant expiresAt) {
        long watermark = manager.view(actor, id).eventSeq();
        if (after < 0 || after > watermark) throw new RunFailure(RunFailure.Code.INVALID, "事件游标无效");
        if (!slots.tryAcquire()) throw new RunFailure(RunFailure.Code.BUSY, "SSE 连接已达上限");
        var emitter = new SseEmitter(65_000L);
        var closed = new AtomicBoolean();
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(error -> closed.set(true));
        try {
            executor.execute(() -> stream(emitter, actor, id, after, expiresAt, closed));
        } catch (RuntimeException ex) {
            slots.release();
            throw ex;
        }
        return emitter;
    }

    private void stream(SseEmitter emitter, Actor actor, UUID id, long seq, Instant expiresAt, AtomicBoolean closed) {
        Instant deadline = Instant.now().plusSeconds(60);
        if (expiresAt.isBefore(deadline)) deadline = expiresAt;
        Instant nextHeartbeat = Instant.MIN;
        try {
            while (!closed.get() && Instant.now().isBefore(deadline)) {
                var feed = manager.events(actor, id, seq);
                for (var event : feed.items()) {
                    if (closed.get() || !Instant.now().isBefore(deadline)) return;
                    emitter.send(SseEmitter.event().id(Long.toString(event.seq())).name(event.eventType())
                            .data(event.payload(), MediaType.APPLICATION_JSON));
                    seq = event.seq();
                }
                if (feed.terminal() && seq >= feed.lastSeq()) break;
                if (Instant.now().isAfter(nextHeartbeat)) {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                    nextHeartbeat = Instant.now().plusSeconds(10);
                }
                if (feed.items().isEmpty()) Thread.sleep(500);
            }
        } catch (IOException | IllegalStateException ex) {
            // 客户端离开只终止订阅。
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ex) {
            emitter.completeWithError(ex);
        } finally {
            emitter.complete();
            slots.release();
        }
    }

    @Override public void close() { executor.shutdownNow(); }
}
