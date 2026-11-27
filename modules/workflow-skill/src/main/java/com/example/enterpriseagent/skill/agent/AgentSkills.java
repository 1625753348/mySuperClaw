package com.example.enterpriseagent.skill.agent;

import java.util.List;
import java.util.UUID;

/** 开放标准技能的宿主入口，与固定 DAG 的 SkillRegistry 分开。 */
public interface AgentSkills {
    List<Summary> list(Actor actor);

    Result run(Actor actor, Request request, String idempotencyKey);

    Result status(Actor actor, UUID invocationId);

    record Actor(String tenant, String user, String agent, List<String> roles) {}
    record Summary(String name, String description, String version) {}
    record Request(String message, String skill, UUID conversationId) {}
    record Result(UUID invocationId, String status, String skill, String answer, UUID executionId, String reason) {}

    final class Failure extends RuntimeException {
        private final int status;

        public Failure(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() { return status; }
    }
}
