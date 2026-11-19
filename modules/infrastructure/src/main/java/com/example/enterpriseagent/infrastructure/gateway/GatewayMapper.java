package com.example.enterpriseagent.infrastructure.gateway;

import com.example.enterpriseagent.toolgateway.registry.GatewayRepository;
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

    @Override
    @Select("""
            SELECT id,name,description,risk_level,status,current_version_id,revision
            FROM gateway.tool WHERE tenant_id=#{tenant} AND name=#{name} FOR UPDATE
            """)
    Tool lockTool(@Param("tenant") String tenant, @Param("name") String name);

    @Override
    @Insert("""
            INSERT INTO gateway.tool(tenant_id,id,name,description,risk_level,status)
            VALUES(#{tenant},#{id},#{name},#{description},#{risk},'DRAFT')
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
            VALUES(#{tenant},#{id},'gateway-http','HTTP','ENABLED')
            ON CONFLICT(tenant_id,name) DO UPDATE SET name=excluded.name
            WHERE tool_server.protocol='HTTP' AND tool_server.status='ENABLED'
            RETURNING id
            """, affectData = true)
    UUID upsertHttpServer(@Param("tenant") String tenant, @Param("id") UUID id);

    @Override
    @Insert("""
            INSERT INTO gateway.tool_endpoint(tenant_id,id,version_id,server_id,protocol,target_ref,timeout_ms)
            VALUES(#{tenant},#{id},#{versionId},#{serverId},'HTTP',#{targetRef},#{timeoutMs})
            """)
    int insertEndpoint(EndpointDraft row);

    @Override
    @Select("""
            SELECT e.id,e.target_ref,e.timeout_ms FROM gateway.tool_endpoint e
            JOIN gateway.tool_server s ON s.tenant_id=e.tenant_id AND s.id=e.server_id
            WHERE e.tenant_id=#{tenant} AND e.version_id=#{versionId} AND e.protocol='HTTP'
              AND e.enabled AND s.status='ENABLED' AND NOT e.async_capable AND e.credential_ref IS NULL
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
    @Select("SELECT EXISTS (SELECT 1 FROM gateway.tool_policy WHERE tenant_id=#{tenant} AND tool_id=#{toolId} AND enabled)")
    boolean hasActivePolicy(@Param("tenant") String tenant, @Param("toolId") UUID toolId);

    @Override
    @Select("""
            SELECT t.name,t.description,v.version_name FROM gateway.tool t
            JOIN gateway.tool_version v ON v.tenant_id=t.tenant_id AND v.id=t.current_version_id
            WHERE t.tenant_id=#{tenant} AND t.status='ACTIVE' AND v.status='PUBLISHED'
              AND (strpos(lower(t.name),#{query})>0 OR strpos(lower(t.description),#{query})>0)
              AND (SELECT coalesce(bool_or(p.effect='ALLOW') AND NOT bool_or(p.effect='DENY'),false)
                   FROM gateway.tool_permission p WHERE p.tenant_id=t.tenant_id AND p.tool_id=t.id
                     AND p.action='DISCOVER' AND p.enabled AND
            """ + MATCH_SUBJECT + """
              )
            ORDER BY t.name LIMIT #{limit}
            """)
    List<SearchHit> search(Search query);

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
                #{idempotencyKey},#{operationHash},concat('sha256:',#{operationHash}),'RUNNING',1,'gateway-http',
                now()+((#{timeoutMs}+60000)*interval '1 millisecond'))
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
            INSERT INTO gateway.audit_entry(tenant_id,id,execution_id,tool_id,actor_id,action,trace_id)
            VALUES(#{tenant},#{id},#{executionId},#{toolId},#{actor},#{action},#{traceId})
            """)
    int audit(Audit row);
}
