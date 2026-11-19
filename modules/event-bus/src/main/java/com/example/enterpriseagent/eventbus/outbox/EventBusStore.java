package com.example.enterpriseagent.eventbus.outbox;

import java.time.Instant;
import java.util.UUID;

/** 重放所需信封与租约。PostgreSQL 拥有投递事实与消费去重。 */
public interface EventBusStore {
    Envelope claimOutbox(String worker);
    int settleOutbox(Settlement result);
    int accept(Receipt receipt);
    int wakeTool(Receipt receipt);

    record Envelope(String tenant, UUID eventId, String eventType, int schemaVersion, String aggregateType,
                    UUID aggregateId, long aggregateVersion, String stream, String traceId, String correlationId,
                    UUID causationId, String payload, Instant occurredAt, long leaseEpoch) {}
    record Settlement(String tenant, UUID eventId, String worker, long epoch, String messageId) {}
    record Receipt(String tenant, UUID eventId, String group, UUID executionId) {}
}
