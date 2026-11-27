package com.example.enterpriseagent.infrastructure.skills;

import com.example.enterpriseagent.skill.agent.AgentSkills.Actor;
import com.example.enterpriseagent.skill.agent.AgentSkills.Failure;
import com.example.enterpriseagent.skill.agent.AgentSkills.Result;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

final class AgentSkillRuns {
    private final JdbcClient db;

    AgentSkillRuns(JdbcClient db) { this.db = db; }

    void checkConversation(Actor actor, UUID conversation) {
        if (conversation == null) return;
        boolean visible = db.sql("""
                SELECT EXISTS (SELECT 1 FROM intent.conversation c JOIN intent.conversation_state s
                  ON (s.tenant_id,s.conversation_id)=(c.tenant_id,c.id)
                  WHERE c.tenant_id=? AND c.id=? AND c.owner_user_id=? AND s.agent_id=?)
                """).params(actor.tenant(), conversation, actor.user(), actor.agent()).query(Boolean.class).single();
        if (!visible) throw new Failure(404, "会话不存在");
    }

    Reservation reserve(Actor actor, UUID conversation, String key, String hash) {
        UUID id = UUID.randomUUID();
        int inserted = db.sql("""
                INSERT INTO agent_skill.invocation
                    (id,tenant_id,user_id,agent_id,conversation_id,request_key,request_digest,status)
                VALUES(:id,:tenant,:user,:agent,:conversation,:key,:digest,'RUNNING')
                ON CONFLICT (tenant_id,user_id,agent_id,request_key) DO NOTHING
                """).param("id", id).param("tenant", actor.tenant()).param("user", actor.user()).param("agent", actor.agent())
                .param("conversation", conversation).param("key", key).param("digest", hash).update();
        if (inserted == 1) return new Reservation(id, true);
        var row = db.sql("""
                SELECT id,request_digest FROM agent_skill.invocation
                WHERE tenant_id=? AND user_id=? AND agent_id=? AND request_key=?
                """).params(actor.tenant(), actor.user(), actor.agent(), key)
                .query((rs, n) -> new Existing(rs.getObject("id", UUID.class), rs.getString("request_digest"))).single();
        if (!row.hash().equals(hash)) throw new Failure(409, "此幂等键已用于另一请求");
        return new Reservation(row.id(), false);
    }

    Result get(Actor actor, UUID id) {
        return db.sql("""
                SELECT CASE WHEN status='RUNNING' AND created_at<now()-interval '5 minutes'
                            THEN 'INTERRUPTED' ELSE status END AS status,
                       skill_name,answer,execution_id,reason FROM agent_skill.invocation
                WHERE tenant_id=? AND user_id=? AND agent_id=? AND id=?
                """).params(actor.tenant(), actor.user(), actor.agent(), id)
                .query((rs, n) -> new Result(id, rs.getString("status"), rs.getString("skill_name"),
                        rs.getString("answer"), rs.getObject("execution_id", UUID.class), rs.getString("reason")))
                .optional().orElseThrow(() -> new Failure(404, "Agent Skill 执行不存在"));
    }

    void audit(UUID id, AgentSkillCatalog.Skill skill, String action, String resource) {
        db.sql("""
                INSERT INTO agent_skill.load_event(invocation_id,skill_name,skill_version,content_digest,action,resource_key)
                VALUES(:id,:name,:version,:digest,:action,:resource)
                """).param("id", id).param("name", skill.summary().name()).param("version", skill.summary().version())
                .param("digest", skill.digest()).param("action", action).param("resource", resource).update();
        if ("ACTIVATED".equals(action)) {
            db.sql("UPDATE agent_skill.invocation SET skill_name=? WHERE id=?")
                    .params(skill.summary().name(), id).update();
        }
    }

    void recordExecution(UUID id, UUID executionId) {
        db.sql("UPDATE agent_skill.invocation SET execution_id=? WHERE id=? AND status='RUNNING'")
                .params(executionId, id).update();
    }

    Result finish(Result result) {
        db.sql("""
                UPDATE agent_skill.invocation SET status=:status,skill_name=:skill,answer=:answer,
                  execution_id=:execution,reason=:reason,finished_at=now()
                WHERE id=:id AND status='RUNNING'
                """).param("id", result.invocationId()).param("status", result.status()).param("skill", result.skill())
                .param("answer", result.answer()).param("execution", result.executionId()).param("reason", result.reason()).update();
        return result;
    }

    record Reservation(UUID id, boolean fresh) {}
    private record Existing(UUID id, String hash) {}
}
