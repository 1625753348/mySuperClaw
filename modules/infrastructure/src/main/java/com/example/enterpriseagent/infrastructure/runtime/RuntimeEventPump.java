package com.example.enterpriseagent.infrastructure.runtime;

import com.example.enterpriseagent.eventbus.outbox.EventBusStore;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionOperations;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** XADD 后才确认 Outbox。先提交 Inbox 和唤醒，再 XACK，允许重投和接管。 */
public final class RuntimeEventPump {
    private static final String RUNS = "agent:events:run";
    private static final String TOOLS = "agent:events:tool-execution";
    private static final String GROUP = "runtime-wakeup-v1";
    private static final Duration CLAIM_IDLE = Duration.ofSeconds(30);
    private final String worker = "runtime-bus-" + UUID.randomUUID();
    private final Set<String> ready = ConcurrentHashMap.newKeySet();
    private final EventBusStore store;
    private final StringRedisTemplate redis;
    private final StreamOperations<String, String, String> streams;
    private final TransactionOperations tx;

    public RuntimeEventPump(EventBusStore store, StringRedisTemplate redis, TransactionOperations tx) {
        this.store = store;
        this.redis = redis;
        this.streams = redis.opsForStream();
        this.tx = tx;
    }

    @Scheduled(fixedDelay = 400)
    public void publish() {
        for (int i = 0; i < 24; i++) {
            var event = store.claimOutbox(worker);
            if (event == null) return;
            String id = null;
            try {
                RecordId record = streams.add(event.stream(), fields(event));
                if (record != null) id = record.getValue();
            } catch (RuntimeException ex) {
                // 数据库保留待投递事件，运行队列仍可扫描。
            }
            store.settleOutbox(new EventBusStore.Settlement(event.tenant(), event.eventId(), worker, event.leaseEpoch(), id));
            if (id == null) return;
        }
    }

    @Scheduled(fixedDelay = 500)
    public void consume() {
        for (String stream : List.of(TOOLS, RUNS)) {
            try {
                ensureGroup(stream);
                var pending = streams.pending(stream, GROUP, Range.unbounded(), 24).stream()
                        .filter(p -> p.getElapsedTimeSinceLastDelivery().compareTo(CLAIM_IDLE) >= 0)
                        .map(PendingMessage::getId).toArray(RecordId[]::new);
                if (pending.length > 0) {
                    accept(stream, streams.claim(stream, GROUP, worker, CLAIM_IDLE, pending));
                }
                accept(stream, streams.read(Consumer.from(GROUP, worker), StreamReadOptions.empty().count(24),
                        StreamOffset.create(stream, ReadOffset.lastConsumed())));
            } catch (RuntimeException ex) {
                ready.remove(stream);
            }
        }
    }

    private void ensureGroup(String stream) {
        if (ready.contains(stream)) return;
        try {
            redis.execute((RedisCallback<String>) connection -> connection.streamCommands()
                    .xGroupCreate(stream.getBytes(StandardCharsets.UTF_8), GROUP, ReadOffset.from("0-0"), true));
        } catch (RedisSystemException ex) {
            if (!String.valueOf(ex.getMostSpecificCause().getMessage()).contains("BUSYGROUP")) throw ex;
        }
        ready.add(stream);
    }

    private void accept(String stream, List<MapRecord<String, String, String>> messages) {
        if (messages == null) return;
        for (var record : messages) {
            Map<String, String> body = record.getValue();
            EventBusStore.Receipt receipt;
            try {
                receipt = new EventBusStore.Receipt(body.get("tenantId"), UUID.fromString(body.get("eventId")), GROUP,
                        TOOLS.equals(stream) ? UUID.fromString(body.get("aggregateId")) : null);
                if (receipt.tenant() == null || receipt.tenant().isBlank()) throw new IllegalArgumentException();
            } catch (IllegalArgumentException | NullPointerException ex) {
                streams.acknowledge(stream, GROUP, record.getId());
                continue;
            }
            tx.executeWithoutResult(ignored -> {
                if (store.accept(receipt) == 1 && receipt.executionId() != null) store.wakeTool(receipt);
            });
            streams.acknowledge(stream, GROUP, record.getId());
        }
    }

    private static Map<String, String> fields(EventBusStore.Envelope event) {
        var map = new LinkedHashMap<String, String>();
        map.put("tenantId", event.tenant());
        map.put("eventId", event.eventId().toString());
        map.put("eventType", event.eventType());
        map.put("schemaVersion", Integer.toString(event.schemaVersion()));
        map.put("aggregateType", event.aggregateType());
        map.put("aggregateId", event.aggregateId().toString());
        map.put("aggregateVersion", Long.toString(event.aggregateVersion()));
        map.put("traceId", event.traceId());
        map.put("correlationId", event.correlationId());
        if (event.causationId() != null) map.put("causationId", event.causationId().toString());
        map.put("occurredAt", event.occurredAt().toString());
        map.put("payload", event.payload());
        return map;
    }
}
