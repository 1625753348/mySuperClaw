package com.example.enterpriseagent.toolgateway.registry;

import java.util.List;
import java.util.UUID;

/** 数据端口。所有业务查写都显式携带租户。 */
public interface GatewayRepository {

    Tool lockTool(String tenant, String name);
    int insertTool(ToolDraft row);
    UUID versionId(String tenant, UUID toolId, String version);
    Version version(String tenant, UUID id);
    int insertVersion(VersionDraft row);
    UUID upsertHttpServer(String tenant, UUID id);
    int insertEndpoint(EndpointDraft row);
    Endpoint enabledEndpoint(String tenant, UUID versionId);
    int publishVersion(String tenant, UUID id);
    int activateTool(String tenant, UUID toolId, UUID versionId, long revision);
    int disableTool(String tenant, UUID toolId, long revision);
    int setPermission(Permission row);
    boolean allowed(Authorization subject);
    boolean hasActivePolicy(String tenant, UUID toolId);
    List<SearchHit> search(Search query);

    Execution findRequest(RequestKey key);
    Execution findExecution(String tenant, UUID id);
    int reserve(Reservation row);
    int finish(Finish row);
    int expire(String tenant, UUID id);
    int audit(Audit row);

    record Tool(UUID id, String name, String description, String riskLevel, String status,
                UUID currentVersionId, long revision) {}
    record ToolDraft(String tenant, UUID id, String name, String description, String risk) {}
    record Version(UUID id, UUID toolId, String versionName, String inputSchema, String outputSchema, String status) {}
    record VersionDraft(String tenant, UUID id, UUID toolId, String version, String inputSchema,
                        String outputSchema, String contractHash) {}
    record Endpoint(UUID id, String targetRef, int timeoutMs) {}
    record EndpointDraft(String tenant, UUID id, UUID versionId, UUID serverId, String targetRef, int timeoutMs) {}
    record Permission(String tenant, UUID toolId, String subjectType, String subjectId, String action, String effect) {}
    record Authorization(String tenant, UUID toolId, String principal, String agent, String rolesJson, String action) {}
    record Search(String tenant, String principal, String agent, String rolesJson, String query, int limit) {}
    record SearchHit(String name, String description, String versionName) {}

    record RequestKey(String tenant, UUID toolId, String agent, String principal, String idempotencyKey) {}
    record Execution(UUID id, UUID toolId, UUID versionId, String agentId, String principalId, String operationHash,
                     String status, String errorCode) {}
    record Reservation(String tenant, UUID id, UUID toolId, UUID versionId, UUID endpointId, String agent, String principal,
                       String traceId, String idempotencyKey, String operationHash, int timeoutMs) {}
    record Finish(String tenant, UUID id, String status, String errorCode) {}
    record Audit(String tenant, UUID id, UUID executionId, UUID toolId, String actor, String action, String traceId) {}
}
