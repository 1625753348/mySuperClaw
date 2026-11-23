package com.example.enterpriseagent.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface MonitorLoop {
    View create(RunModel.Actor actor, UUID id, Spec spec);
    View get(RunModel.Actor actor, UUID id);
    List<View> list(RunModel.Actor actor);
    List<Tick> history(RunModel.Actor actor, UUID id, long before);
    List<Delivery> deliveries(RunModel.Actor actor, UUID id, long before);
    View setPaused(RunModel.Actor actor, UUID id, boolean paused);

    enum Comparison { ABOVE, BELOW }
    enum Action { RECORD, PAUSE }

    record Spec(String tool, String version, JsonNode arguments, String field, Comparison comparison,
                BigDecimal threshold, int everySeconds, int lifetimeSeconds, Action action,
                @JsonInclude(JsonInclude.Include.NON_NULL) ToolRef notifyWith,
                @JsonInclude(JsonInclude.Include.NON_NULL) ToolRef remediateWith) {}
    record ToolRef(String tool, String version) {}
    record View(UUID id, String tool, String version, String state, String reason, OffsetDateTime nextAt,
                OffsetDateTime expiresAt, long lastSeq, Long activeSeq, String lastSignal) {}
    record Tick(long seq, UUID executionId, String state, String signalEvent, String reason,
                OffsetDateTime startedAt, OffsetDateTime finishedAt) {}
    record Delivery(long seq, String kind, String signalEvent, String status, UUID executionId, String reason) {}
}
