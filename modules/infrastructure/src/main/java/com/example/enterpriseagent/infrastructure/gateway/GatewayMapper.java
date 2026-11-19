package com.example.enterpriseagent.infrastructure.gateway;

import com.example.enterpriseagent.toolgateway.registry.GatewayRepository;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

/** 与现有 gateway 表对齐。SQL 中的所有调用方字段均使用绑定参数。 */
@Mapper
public interface GatewayMapper extends GatewayRepository {

    String MATCH_SUBJECT = """
            ((p.subject_type='USER' AND p.subject_id=#{principal})
             OR (p.subject_type='AGENT' AND p.subject_id=#{agent})
             OR (p.subject_type='ROLE' AND jsonb_exists(CAST(#{rolesJson} AS jsonb),p.subject_id)))
            """;
    String EXECUTION_COLUMNS = "id,tool_id,version_id,agent_id,principal_id,operation_hash,status,error_code";
    String APPROVAL_VIEW = """
            SELECT a.id,a.execution_id,e.tool_id,t.name AS tool_name,e.trace_id,a.requester_principal_id AS requester,
                   a.policy_version,a.status,a.decided_by,a.expires_at<=now() AS expired
            FROM gateway.tool_approval a
            JOIN gateway.tool_execution e ON e.tenant_id=a.tenant_id AND e.id=a.execution_id
            JOIN gateway.tool t ON t.tenant_id=e.tenant_id AND t.id=e.tool_id
            WHERE a.tenant_id=#{tenant}
            """;
    String ACTIVE_CATALOG = """
            SELECT t.id AS tool_id,v.id AS version_id,t.name,t.description,v.version_name,t.category,
                   e.protocol,e.target_ref,v.input_schema::text,e.credential_ref
            FROM gateway.tool t
            JOIN gateway.tool_version v ON v.tenant_id=t.tenant_id AND v.id=t.current_version_id
            JOIN LATERAL (
                SELECT ep.protocol,ep.target_ref,ep.credential_ref FROM gateway.tool_endpoint ep
                JOIN gateway.tool_server s ON s.tenant_id=ep.tenant_id AND s.id=ep.server_id
                WHERE ep.tenant_id=t.tenant_id AND ep.version_id=v.id
                  AND ep.enabled AND s.status='ENABLED' AND (NOT ep.async_capable OR ep.protocol='ASYNC')
                ORDER BY ep.id LIMIT 1
            ) e ON true
            WHERE t.tenant_id=#{tenant} AND t.status='ACTIVE' AND v.status='PUBLISHED'
            """;
    String CATALOG_ORDER = " AND t.name>#{afterName} ORDER BY t.name LIMIT #{limit}";

    @Override
    @Select("""
            SELECT id,name,description,risk_level,status,current_version_id,revision,category
            FROM gateway.tool WHERE tenant_id=#{tenant} AND name=#{name} FOR UPDATE
            """)
    Tool lockTool(@Param("tenant") String tenant, @Param("name") String name);

    @Override
    @Select("""
            SELECT t.name,t.description,t.category,t.risk_level,t.status,t.revision,
                   current.version_name AS current_version,stats.protocols,stats.version_count
            FROM gateway.tool t
            LEFT JOIN gateway.tool_version current ON current.tenant_id=t.tenant_id AND current.id=t.current_version_id
            JOIN LATERAL (
                SELECT string_agg(DISTINCT e.protocol,',' ORDER BY e.protocol) AS protocols,
                       count(DISTINCT v.id)::int AS version_count
                FROM gateway.tool_version v
                LEFT JOIN gateway.tool_endpoint e ON e.tenant_id=v.tenant_id AND e.version_id=v.id
                WHERE v.tenant_id=t.tenant_id AND v.tool_id=t.id
            ) stats ON true
            WHERE t.tenant_id=#{tenant} AND t.name>#{after}
              AND (#{status}='' OR t.status=#{status})
              AND (#{protocol}='' OR (#{protocol}='UNBOUND' AND stats.protocols IS NULL)
                   OR #{protocol}=ANY(string_to_array(stats.protocols,',')))
              AND (#{keyword}='' OR strpos(lower(t.name),lower(#{keyword}))>0
                   OR strpos(lower(t.description),lower(#{keyword}))>0
                   OR strpos(lower(coalesce(t.category,'')),lower(#{keyword}))>0)
            ORDER BY t.name LIMIT #{limit}
            """)
    List<AdminTool> adminCatalog(AdminSearch query);

    @Override
    @Select("""
            SELECT v.id,v.version_name,v.status,e.protocol,e.target_ref,e.timeout_ms
            FROM gateway.tool_version v
            LEFT JOIN LATERAL (
                SELECT ep.protocol,ep.target_ref,ep.timeout_ms FROM gateway.tool_endpoint ep
                WHERE ep.tenant_id=v.tenant_id AND ep.version_id=v.id
                ORDER BY ep.enabled DESC,ep.id LIMIT 1
            ) e ON true
            WHERE v.tenant_id=#{tenant} AND v.tool_id=#{toolId}
            ORDER BY v.published_at DESC NULLS FIRST,v.version_name DESC
            """)
    List<AdminVersion> adminVersions(@Param("tenant") String tenant, @Param("toolId") UUID toolId);

    @Override
    @Insert("""
            INSERT INTO gateway.tool(tenant_id,id,name,description,risk_level,status,category)
            VALUES(#{tenant},#{id},#{name},#{description},#{risk},'DRAFT',#{category})
            ON CONFLICT(tenant_id,name) DO NOTHING
            """)
    int insertTool(ToolDraft row);

    @Override
    @Select("""
            SELECT id FROM gateway.tool_version
            WHERE tenant_id=#{tenant} AND tool_id=#{toolId} AND version_name=#{version}
            """)
    UUID versionId(@Param("tenant") String tenant, @Param("toolId") UUID toolId, @Param("version") String version);

    @Override
    @Select("""
            SELECT id,tool_id,version_name,input_schema::text,output_schema::text,status
            FROM gateway.tool_version WHERE tenant_id=#{tenant} AND id=#{id}
            """)
    Version version(@Param("tenant") String tenant, @Param("id") UUID id);

    @Override
    @Insert("""
            INSERT INTO gateway.tool_version(tenant_id,id,tool_id,version_name,status,input_schema,output_schema,contract_hash)
            VALUES(#{tenant},#{id},#{toolId},#{version},'DRAFT',CAST(#{inputSchema} AS jsonb),CAST(#{outputSchema} AS jsonb),#{contractHash})
            ON CONFLICT(tenant_id,tool_id,version_name) DO NOTHING
            """)
    int insertVersion(VersionDraft row);

    @Override
    @Select(value = """
            INSERT INTO gateway.tool_server(tenant_id,id,name,protocol,status)
            VALUES(#{tenant},#{id},concat('gateway-',lower(#{protocol})) ,#{protocol},'ENABLED')
            ON CONFLICT(tenant_id,name) DO UPDATE SET name=excluded.name
            WHERE tool_server.protocol=excluded.protocol AND tool_server.status='ENABLED'
            RETURNING id
            """, affectData = true)
    UUID upsertServer(@Param("tenant") String tenant, @Param("id") UUID id, @Param("protocol") String protocol);

    @Override
    @Insert("""
            INSERT INTO gateway.tool_endpoint(tenant_id,id,version_id,server_id,protocol,target_ref,timeout_ms,
                credential_ref,async_capable,idempotency_supported)
            VALUES(#{tenant},#{id},#{versionId},#{serverId},#{protocol},#{targetRef},#{timeoutMs},
                #{credentialRef},#{asyncCapable},#{idempotencySupported})
            """)
    int insertEndpoint(EndpointDraft row);

    @Override
    @Select("""
            SELECT e.id,e.target_ref,e.timeout_ms,e.protocol,e.credential_ref,e.idempotency_supported FROM gateway.tool_endpoint e
            JOIN gateway.tool_server s ON s.tenant_id=e.tenant_id AND s.id=e.server_id
            WHERE e.tenant_id=#{tenant} AND e.version_id=#{versionId}
              AND e.enabled AND s.status='ENABLED' AND (NOT e.async_capable OR e.protocol='ASYNC')
            ORDER BY e.id LIMIT 1
            """)
    Endpoint enabledEndpoint(@Param("tenant") String tenant, @Param("versionId") UUID versionId);

    @Override
    @Update("""
            UPDATE gateway.tool_version SET status='PUBLISHED',published_at=now()
            WHERE tenant_id=#{tenant} AND id=#{id} AND status='DRAFT'
            """)
    int publishVersion(@Param("tenant") String tenant, @Param("id") UUID id);

    @Override
    @Update("""
            UPDATE gateway.tool SET status='ACTIVE',current_version_id=#{versionId},revision=revision+1
            WHERE tenant_id=#{tenant} AND id=#{toolId} AND revision=#{revision}
            """)
    int activateTool(@Param("tenant") String tenant, @Param("toolId") UUID toolId,
                     @Param("versionId") UUID versionId, @Param("revision") long revision);

    @Override
    @Update("""
            UPDATE gateway.tool SET status='DISABLED',revision=revision+1
            WHERE tenant_id=#{tenant} AND id=#{toolId} AND revision=#{revision}
            """)
    int disableTool(@Param("tenant") String tenant, @Param("toolId") UUID toolId, @Param("revision") long revision);

    @Override
    @Insert("""
            INSERT INTO gateway.tool_permission(tenant_id,tool_id,subject_type,subject_id,action,effect)
            VALUES(#{tenant},#{toolId},#{subjectType},#{subjectId},#{action},#{effect})
            ON CONFLICT(tenant_id,tool_id,subject_type,subject_id,action)
            DO UPDATE SET effect=excluded.effect,enabled=true
            """)
    int setPermission(Permission row);

    @Override
    @Select("""
            SELECT coalesce(bool_or(p.effect='ALLOW') AND NOT bool_or(p.effect='DENY'),false)
            FROM gateway.tool_permission p
            WHERE p.tenant_id=#{tenant} AND p.tool_id=#{toolId} AND p.action=#{action} AND p.enabled AND
            """ + MATCH_SUBJECT)
    boolean allowed(Authorization subject);

    @Override
    @Select("""
            SELECT kind,policy_version,rule::text FROM gateway.tool_policy
            WHERE tenant_id=#{tenant} AND tool_id=#{toolId} AND enabled ORDER BY kind
            """)
    List<PolicyRow> activePolicies(@Param("tenant") String tenant, @Param("toolId") UUID toolId);

    @Override
    @Update("UPDATE gateway.tool_policy SET enabled=false WHERE tenant_id=#{tenant} AND tool_id=#{toolId} AND kind=#{kind} AND enabled")
    int disablePolicy(@Param("tenant") String tenant, @Param("toolId") UUID toolId, @Param("kind") String kind);

    @Override
    @Insert("""
            INSERT INTO gateway.tool_policy(tenant_id,id,tool_id,kind,policy_version,rule)
            SELECT #{tenant},#{id},#{toolId},#{kind},coalesce(max(policy_version),0)+1,CAST(#{rule} AS jsonb)
            FROM gateway.tool_policy WHERE tenant_id=#{tenant} AND tool_id=#{toolId} AND kind=#{kind}
            """)
    int insertPolicy(PolicyDraft row);

    @Override
    @Insert("""
            INSERT INTO gateway.tool_usage_window(tenant_id,tool_id,scope,subject_id,unit,window_start,used)
            VALUES(#{tenant},#{toolId},#{scope},#{subject},#{unit},
              CASE WHEN #{unit}='MINUTE' THEN date_trunc('minute',now(),'UTC') ELSE date_trunc('day',now(),'UTC') END,1)
            ON CONFLICT (tenant_id,tool_id,scope,subject_id,unit,window_start)
            DO UPDATE SET used=tool_usage_window.used+1 WHERE tool_usage_window.used<#{limit}
            """)
    int consumeUsage(UsageWindow window);

    @Delete("""
            DELETE FROM gateway.tool_usage_window WHERE window_start < now()-interval '2 days'
            """)
    int cleanupUsage();

    @Override
    @Select(ACTIVE_CATALOG + """
              AND (t.risk_level IN ('LOW','MEDIUM') OR EXISTS (
                   SELECT 1 FROM gateway.tool_policy a
                   WHERE a.tenant_id=t.tenant_id AND a.tool_id=t.id AND a.enabled AND a.kind='APPROVAL'))
              AND NOT EXISTS (SELECT 1 FROM gateway.tool_policy g
                  WHERE g.tenant_id=t.tenant_id AND g.tool_id=t.id AND g.enabled
                  AND g.kind='ABAC' AND (
                       (jsonb_exists(g.rule,'roles') AND NOT jsonb_exists_any(g.rule->'roles',
                         ARRAY(SELECT jsonb_array_elements_text(CAST(#{rolesJson} AS jsonb)))))
                    OR (jsonb_exists(g.rule,'agents') AND NOT jsonb_exists(g.rule->'agents',#{agent})))
                  )
              AND (SELECT coalesce(bool_or(p.effect='ALLOW') AND NOT bool_or(p.effect='DENY'),false)
                   FROM gateway.tool_permission p WHERE p.tenant_id=t.tenant_id AND p.tool_id=t.id
                     AND p.action='DISCOVER' AND p.enabled AND
            """ + MATCH_SUBJECT + """
              )
              AND (SELECT coalesce(bool_or(p.effect='ALLOW') AND NOT bool_or(p.effect='DENY'),false)
                   FROM gateway.tool_permission p WHERE p.tenant_id=t.tenant_id AND p.tool_id=t.id
                     AND p.action='EXECUTE' AND p.enabled AND
            """ + MATCH_SUBJECT + ")" + CATALOG_ORDER)
    List<Candidate> discover(Search query);

    @Override
    @Select(ACTIVE_CATALOG + CATALOG_ORDER)
    List<Candidate> indexable(@Param("tenant") String tenant, @Param("afterName") String afterName,
                             @Param("limit") int limit);

    @Override
    @Select(ACTIVE_CATALOG + " AND t.id=#{toolId}")
    Candidate indexableTool(@Param("tenant") String tenant, @Param("toolId") UUID toolId);

    @Override
    @Select("SELECT " + EXECUTION_COLUMNS + """
             FROM gateway.tool_execution WHERE tenant_id=#{tenant} AND tool_id=#{toolId}
              AND agent_id=#{agent} AND principal_id=#{principal} AND idempotency_key=#{idempotencyKey}
            """)
    Execution findRequest(RequestKey key);

    @Override
    @Select("SELECT " + EXECUTION_COLUMNS + " FROM gateway.tool_execution WHERE tenant_id=#{tenant} AND id=#{id}")
    Execution findExecution(@Param("tenant") String tenant, @Param("id") UUID id);

    @Override
    @Insert("""
            INSERT INTO gateway.tool_execution(tenant_id,id,tool_id,version_id,endpoint_id,agent_id,principal_id,
                trace_id,idempotency_key,operation_hash,arguments_ref,status,lease_epoch,lease_owner,lease_until)
            VALUES(#{tenant},#{id},#{toolId},#{versionId},#{endpointId},#{agent},#{principal},#{traceId},
                #{idempotencyKey},#{operationHash},concat('sha256:',#{operationHash}),#{status},
                CASE WHEN #{status}='RUNNING' THEN 1 ELSE 0 END,
                CASE WHEN #{status}='RUNNING' THEN 'gateway-http' ELSE NULL END,
                CASE WHEN #{status}='RUNNING' THEN now()+((#{timeoutMs}+60000)*interval '1 millisecond') ELSE NULL END)
            ON CONFLICT(tenant_id,agent_id,principal_id,tool_id,idempotency_key) DO NOTHING
            """)
    int reserve(Reservation row);

    @Override
    @Update("""
            UPDATE gateway.tool_execution SET status=#{status},error_code=#{errorCode},version=version+1,
              finished_at=CASE WHEN #{status} IN ('SUCCEEDED','FAILED') THEN now() ELSE NULL END,
              lease_owner=NULL,lease_until=NULL
            WHERE tenant_id=#{tenant} AND id=#{id} AND status='RUNNING' AND lease_epoch=1 AND lease_owner='gateway-http'
            """)
    int finish(Finish row);

    @Override
    @Update("""
            UPDATE gateway.tool_execution SET status='UNKNOWN',error_code='DISPATCH_LEASE_EXPIRED',
              lease_owner=NULL,lease_until=NULL,version=version+1
            WHERE tenant_id=#{tenant} AND id=#{id} AND status='RUNNING' AND lease_until<now()
            """)
    int expire(@Param("tenant") String tenant, @Param("id") UUID id);

    @Override
    @Insert("""
            INSERT INTO gateway.tool_job(tenant_id,execution_id,encrypted_request,deadline_at)
            VALUES(#{tenant},#{id},#{encryptedRequest},now()+interval '1 day')
            """)
    int insertJob(JobPayload row);

    @Override
    @Insert("""
            INSERT INTO gateway.tool_approval(tenant_id,id,execution_id,version_id,operation_hash,requester_principal_id,
                approver_selector,policy_version,status,expires_at)
            VALUES(#{tenant},#{id},#{executionId},#{versionId},#{operationHash},#{requester},
                '{"action":"APPROVE","separationOfDuties":true}',#{policyVersion},'PENDING',now()+#{ttlSeconds}*interval '1 second')
            """)
    int insertApproval(ApprovalDraft row);

    @Override
    @Select(APPROVAL_VIEW + " AND a.execution_id=#{executionId} ORDER BY a.created_at DESC LIMIT 1")
    ApprovalView approvalForExecution(@Param("tenant") String tenant, @Param("executionId") UUID executionId);

    @Override
    @Select(APPROVAL_VIEW + " AND a.id=#{approvalId}")
    ApprovalView approval(@Param("tenant") String tenant, @Param("approvalId") UUID approvalId);

    @Override
    @Select("SELECT " + EXECUTION_COLUMNS + " FROM gateway.tool_execution WHERE tenant_id=#{tenant} AND id=#{id} FOR UPDATE")
    Execution lockExecution(@Param("tenant") String tenant, @Param("id") UUID id);

    @Override
    @Update("""
            UPDATE gateway.tool_approval SET status=#{status},decided_by=#{actor},decided_at=now()
            WHERE tenant_id=#{tenant} AND id=#{id} AND status='PENDING'
              AND ((#{status}='EXPIRED')=(expires_at<=now()))
            """)
    int decideApproval(ApprovalDecision row);

    @Override
    @Update("""
            UPDATE gateway.tool_execution SET status=#{status},version=version+1,next_at=now(),
              finished_at=CASE WHEN #{status} IN ('REJECTED','EXPIRED') THEN now() ELSE NULL END
            WHERE tenant_id=#{tenant} AND id=#{id} AND status='WAITING_APPROVAL'
            """)
    int afterApproval(@Param("tenant") String tenant, @Param("id") UUID id, @Param("status") String status);

    @Override
    @Select("SELECT encrypted_result FROM gateway.tool_job WHERE tenant_id=#{tenant} AND execution_id=#{id}")
    String readJobResult(@Param("tenant") String tenant, @Param("id") UUID id);

    // 开始派发使用 RUNNING。若进程在外部副作用后崩溃，过期转 UNKNOWN。
    // 已有外部任务的轮询为只读，可以在租约过期后由别的进程领取。
    @Override
    @Select(value = """
            WITH due AS (
                SELECT e.tenant_id,e.id,e.status AS phase,t.name AS tool_name,j.encrypted_request,j.deadline_at<=now() AS expired
                FROM gateway.tool_execution e
                JOIN gateway.tool_job j ON j.tenant_id=e.tenant_id AND j.execution_id=e.id
                JOIN gateway.tool t ON t.tenant_id=e.tenant_id AND t.id=e.tool_id
                WHERE e.status IN ('QUEUED','WAITING_EXTERNAL') AND e.next_at<=now()
                  AND (e.lease_until IS NULL OR e.lease_until<now())
                ORDER BY e.next_at,e.id LIMIT 1 FOR UPDATE OF e SKIP LOCKED
            )
            UPDATE gateway.tool_execution e SET status=CASE WHEN due.phase='QUEUED' THEN 'RUNNING' ELSE due.phase END,
                version=e.version+1,lease_epoch=e.lease_epoch+1,lease_owner=#{worker},lease_until=now()+interval '90 seconds'
            FROM due WHERE e.tenant_id=due.tenant_id AND e.id=due.id
            RETURNING e.tenant_id AS tenant,e.id,e.tool_id,due.tool_name,e.version_id,e.endpoint_id,e.agent_id AS agent,
                      e.principal_id AS principal,e.trace_id,e.operation_hash,due.phase,e.lease_epoch AS epoch,
                      due.encrypted_request,e.external_job_id,due.expired
            """, affectData = true)
    JobClaim claimJob(@Param("worker") String worker);

    @Override
    @Update("""
            UPDATE gateway.tool_execution SET status=#{status},error_code=#{errorCode},version=version+1,
              external_job_id=coalesce(#{externalJobId},external_job_id),
              next_at=now()+#{pollAfterMs}*interval '1 millisecond',
              finished_at=CASE WHEN #{status} IN ('SUCCEEDED','FAILED','BLOCKED','EXPIRED') THEN now() ELSE NULL END,
              lease_owner=NULL,lease_until=NULL
            WHERE tenant_id=#{tenant} AND id=#{id} AND status IN ('RUNNING','WAITING_EXTERNAL')
              AND lease_owner=#{worker} AND lease_epoch=#{epoch}
            """)
    int settleJob(JobSettlement row);

    @Override
    @Update("""
            UPDATE gateway.tool_job SET encrypted_result=#{encryptedResult},encrypted_request=NULL
            WHERE tenant_id=#{tenant} AND execution_id=#{id}
            """)
    int saveJobResult(@Param("tenant") String tenant, @Param("id") UUID id, @Param("encryptedResult") String encryptedResult);

    @Override
    @Update("""
            WITH due AS (
                SELECT e.tenant_id,e.id,a.id AS approval_id FROM gateway.tool_execution e
                JOIN gateway.tool_approval a ON a.tenant_id=e.tenant_id AND a.execution_id=e.id
                WHERE e.status='WAITING_APPROVAL' AND a.status='PENDING' AND a.expires_at<=now()
                LIMIT 100 FOR UPDATE OF e SKIP LOCKED
            ), expired AS (
                UPDATE gateway.tool_approval a SET status='EXPIRED',decided_at=now()
                FROM due WHERE a.tenant_id=due.tenant_id AND a.id=due.approval_id AND a.status='PENDING'
                RETURNING a.tenant_id,a.execution_id
            )
            UPDATE gateway.tool_execution e SET status='EXPIRED',finished_at=now(),version=e.version+1
            FROM expired WHERE e.tenant_id=expired.tenant_id AND e.id=expired.execution_id
            """)
    int expireApprovalBatch();

    @Override
    @Update("""
            UPDATE gateway.tool_execution e SET status='UNKNOWN',error_code='DISPATCH_LEASE_EXPIRED',
              lease_owner=NULL,lease_until=NULL,version=e.version+1
            FROM gateway.tool_job j WHERE j.tenant_id=e.tenant_id AND j.execution_id=e.id
              AND e.status='RUNNING' AND e.lease_until<now()
            """)
    int expireJobLeases();

    @Override
    @Delete("""
            DELETE FROM gateway.tool_job j USING gateway.tool_execution e
            WHERE j.tenant_id=e.tenant_id AND j.execution_id=e.id AND j.deadline_at<now()-interval '1 day'
              AND e.status IN ('SUCCEEDED','FAILED','BLOCKED','REJECTED','EXPIRED','UNKNOWN','CANCELLED')
            """)
    int cleanupJobs();

    @Override
    @Insert("""
            INSERT INTO gateway.audit_entry(tenant_id,id,execution_id,tool_id,actor_id,action,trace_id)
            VALUES(#{tenant},#{id},#{executionId},#{toolId},#{actor},#{action},#{traceId})
            """)
    int audit(Audit row);

    @Override
    @Insert("""
            INSERT INTO eventing.outbox(tenant_id,event_id,event_type,aggregate_type,aggregate_id,
                aggregate_version,stream,trace_id,correlation_id,payload)
            VALUES(#{tenant},#{eventId},#{eventType},'TOOL',#{toolId},#{revision},'agent:events:tool-index',
                #{traceId},#{traceId},jsonb_build_object('versionId',CAST(#{versionId} AS uuid)))
            """)
    int enqueueChange(CatalogChange change);

    @Override
    @Select(value = """
            WITH due AS (
                SELECT tenant_id,event_id FROM eventing.outbox
                WHERE stream='agent:events:tool-index' AND published_at IS NULL AND next_at<=now()
                  AND (lease_until IS NULL OR lease_until<now())
                ORDER BY next_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED
            )
            UPDATE eventing.outbox o SET attempts=o.attempts+1,lease_epoch=o.lease_epoch+1,
                lease_owner=#{worker},lease_until=now()+interval '60 seconds'
            FROM due WHERE o.tenant_id=due.tenant_id AND o.event_id=due.event_id
            RETURNING o.tenant_id,o.event_id,o.aggregate_id,o.lease_epoch
            """, affectData = true)
    IndexEvent claimIndexEvent(@Param("worker") String worker);

    @Override
    @Update("""
            UPDATE eventing.outbox SET
                published_at=CASE WHEN #{indexed} THEN now() ELSE NULL END,
                message_id=CASE WHEN #{indexed} THEN concat('tool-index:',event_id) ELSE NULL END,
                next_at=CASE WHEN #{indexed} THEN next_at ELSE now()+interval '30 seconds' END,
                last_error=CASE WHEN #{indexed} THEN NULL ELSE 'TOOL_INDEX_FAILED' END,
                lease_owner=NULL,lease_until=NULL
            WHERE tenant_id=#{tenant} AND event_id=#{eventId} AND lease_epoch=#{epoch} AND lease_owner=#{worker}
            """)
    int finishIndexEvent(IndexReceipt receipt);
}
