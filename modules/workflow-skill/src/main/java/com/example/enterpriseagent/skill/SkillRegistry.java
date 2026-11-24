package com.example.enterpriseagent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

public interface SkillRegistry {
    record Actor(String tenant, String user, String agent, List<String> roles) {}
    record Published(UUID id, String skillKey, int version, long revision, String status, boolean current,
                     String digest, JsonNode dag) {}
    record Summary(String skillKey, int version, long revision, String status) {}
    record Page(List<Summary> items, String nextCursor) {}
    Page list(Actor actor, String after, int limit);
    Published publish(Actor actor, String key, int version, Long expectedRevision, String markdown);
    Published get(Actor actor, String key, Integer version);
    Published resolve(Actor actor, String command);

    final class Failure extends RuntimeException {
        private final int status;
        public Failure(int status, String message) {
            super(message);
            this.status = status;
        }
        public int status() { return status; }
    }
}
