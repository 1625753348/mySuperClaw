package com.example.enterpriseagent.infrastructure.runtime;

import com.example.enterpriseagent.eventbus.outbox.EventBusStore;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.UUID;

@Mapper
public interface EventBusMapper extends EventBusStore {
    String RUNS = "agent:events:run";
    String TOOLS = "agent:events:tool-execution";
    String SKILLS = "agent:events:workflow-skill";
    String GROUP = "runtime-wakeup-v1";
    String STREAMS = "stream IN ('" + RUNS + "','" + TOOLS + "','" + SKILLS + "')";

    @Select(value = """
            WITH due AS (
                SELECT tenant_id,event_id FROM eventing.outbox
                WHERE
            """ + STREAMS + """
                  AND published_at IS NULL
                  AND attempts<12
                  AND next_at<=now() AND (lease_until IS NULL OR lease_until<=now())
                ORDER BY next_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED
            )
            UPDATE eventing.outbox o SET attempts=o.attempts+1,lease_epoch=o.lease_epoch+1,
                lease_owner=#{worker},lease_until=now()+interval '30 seconds'
            FROM due WHERE o.tenant_id=due.tenant_id AND o.event_id=due.event_id
            RETURNING o.tenant_id AS tenant,o.event_id,o.event_type,o.schema_version,o.aggregate_type,o.aggregate_id,
                o.aggregate_version,o.stream,o.trace_id,o.correlation_id,o.causation_id,o.payload::text,o.occurred_at,o.lease_epoch
            """, affectData = true)
    Envelope claimOutbox(@Param("worker") String worker);

    @Update("""
            UPDATE eventing.outbox SET published_at=CASE WHEN CAST(#{messageId} AS text) IS NULL THEN NULL ELSE now() END,
              message_id=#{messageId},last_error=CASE WHEN CAST(#{messageId} AS text) IS NULL THEN 'STREAM_UNAVAILABLE' ELSE NULL END,
              next_at=CASE WHEN CAST(#{messageId} AS text) IS NULL THEN now()+least(attempts,30)*interval '1 second' ELSE next_at END,
              lease_owner=NULL,lease_until=NULL
            WHERE tenant_id=#{tenant} AND event_id=#{eventId} AND lease_owner=#{worker} AND lease_epoch=#{epoch}
            """)
    int settleOutbox(Settlement result);

    // 锁住来源，防止保留清理和消费同时进行。来源已清理的旧消息直接确认。
    @Select(value = """
            WITH source AS MATERIALIZED (
                SELECT tenant_id,event_id,aggregate_id FROM eventing.outbox
                WHERE tenant_id=#{tenant} AND event_id=#{eventId} AND stream=#{stream}
                  AND (stream<>'agent:events:workflow-skill' OR (event_type='WorkflowSkillPublished' AND schema_version=1))
                FOR KEY SHARE
            ), accepted AS (
                INSERT INTO eventing.inbox(tenant_id,consumer_name,event_id)
                SELECT tenant_id,#{group},event_id FROM source
                ON CONFLICT DO NOTHING RETURNING event_id
            )
            SELECT aggregate_id FROM source JOIN accepted USING(event_id)
            """, affectData = true)
    UUID accept(Receipt receipt);

    @Update("""
            UPDATE runtime.step s SET next_at=least(s.next_at,now())
            FROM gateway.tool_execution e
            WHERE e.tenant_id=#{tenant} AND e.id=#{executionId} AND s.tenant_id=e.tenant_id AND s.id=e.step_id
              AND s.run_id=e.run_id AND s.tool_execution_id=e.id AND s.status IN ('WAITING_APPROVAL','WAITING_EXTERNAL')
            """)
    int wakeTool(@Param("tenant") String tenant, @Param("executionId") UUID executionId);

    @Delete("""
            WITH eligible AS (
                SELECT o.tenant_id,o.event_id FROM eventing.outbox o
                JOIN eventing.inbox i USING(tenant_id,event_id)
                WHERE
            """ + STREAMS + """
                  AND i.consumer_name='runtime-wakeup-v1'
                  AND o.published_at < now()-#{days}*interval '1 day' AND o.lease_until IS NULL
                ORDER BY o.published_at,o.event_id LIMIT 500 FOR UPDATE OF o SKIP LOCKED
            ), removed AS (
                DELETE FROM eventing.outbox o USING eligible e
                WHERE o.tenant_id=e.tenant_id AND o.event_id=e.event_id
                RETURNING o.tenant_id,o.event_id
            )
            DELETE FROM eventing.inbox i USING removed r
            WHERE i.tenant_id=r.tenant_id AND i.event_id=r.event_id AND i.consumer_name='runtime-wakeup-v1'
            """)
    int pruneDelivered(@Param("days") int days);
}
