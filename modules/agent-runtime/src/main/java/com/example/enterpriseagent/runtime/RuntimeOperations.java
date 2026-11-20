package com.example.enterpriseagent.runtime;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface RuntimeOperations {
    List<Delivery> deliveries(String tenant, boolean dead, int limit);
    List<UncertainRun> uncertainRuns(String tenant, int limit);
    void replay(String tenant, String user, UUID event, String reason);
    void close(String tenant, String user, UUID run, String reason);

    record Delivery(UUID eventId, String eventType, String stream, UUID aggregateId, int attempts, String error) {}
    record UncertainRun(UUID runId, UUID taskId, String reason, OffsetDateTime deadlineAt) {}
}
