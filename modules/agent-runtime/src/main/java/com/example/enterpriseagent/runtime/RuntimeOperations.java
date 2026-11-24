package com.example.enterpriseagent.runtime;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface RuntimeOperations {
    List<Delivery> deliveries(String tenant, boolean dead, int limit);
    List<UncertainRun> uncertainRuns(String tenant, int limit);
    List<UncertainStep> uncertainSteps(String tenant, UUID run);
    void replay(String tenant, String user, UUID event, String reason);
    void close(String tenant, String user, UUID run, String reason);
    void confirmSuccess(RunModel.Actor operator, UUID run, UUID step, Confirmation confirmation);

    record Delivery(UUID eventId, String eventType, String stream, UUID aggregateId, int attempts, String error) {}
    record UncertainRun(UUID runId, UUID taskId, String reason, OffsetDateTime deadlineAt) {}
    record UncertainStep(UUID stepId, String node, UUID executionId, String tool, String toolVersion,
                         String executionStatus, String reason) {}
    record Confirmation(UUID executionId, JsonNode result, String evidenceRef, String reason) {}
}
