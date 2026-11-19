package com.example.enterpriseagent.toolgateway.registry;

import java.util.List;
import java.util.UUID;

/** 数据端口。所有业务查写都显式携带租户。 */
public interface GatewayRepository {

    Tool lockTool(String tenant, String name);
    List<AdminTool> adminCatalog(AdminSearch query);
    List<AdminVersion> adminVersions(String tenant, UUID toolId);
    int insertTool(ToolDraft row);
    UUID versionId(String tenant, UUID toolId, String version);
    Version version(String tenant, UUID id);
    int insertVersion(VersionDraft row);
    UUID upsertServer(String tenant, UUID id, String protocol);
    int insertEndpoint(EndpointDraft row);
    Endpoint enabledEndpoint(String tenant, UUID versionId);
    int publishVersion(String tenant, UUID id);
    int activateTool(String tenant, UUID toolId, UUID versionId, long revision);
    int disableTool(String tenant, UUID toolId, long revision);
    int setPermission(Permission row);
    boolean allowed(Authorization subject);
    boolean hasActivePolicy(String tenant, UUID toolId);
    List<Candidate> discover(Search query);
    List<Candidate> indexable(String tenant, String afterName, int limit);
    Candidate indexableTool(String tenant, UUID toolId);

    Execution findRequest(RequestKey key);
    Execution findExecution(String tenant, UUID id);
    int reserve(Reservation row);
    int finish(Finish row);
    int expire(String tenant, UUID id);
    int audit(Audit row);
    int enqueueChange(CatalogChange change);
    IndexEvent claimIndexEvent(String worker);
    int finishIndexEvent(IndexReceipt receipt);

    record Tool(UUID id, String name, String description, String riskLevel, String status,
                UUID currentVersionId, long revision, String category) {}
    record ToolDraft(String tenant, UUID id, String name, String description, String risk, String category) {}
    record AdminSearch(String tenant, String keyword, String protocol, String status, String after, int limit) {}
    record AdminTool(String name, String description, String category, String riskLevel, String status,
                     long revision, String currentVersion, String protocols, int versionCount) {}
    record AdminVersion(UUID id, String versionName, String status, String protocol, String targetRef, Integer timeoutMs) {}
    record Version(UUID id, UUID toolId, String versionName, String inputSchema, String outputSchema, String status) {}
    record VersionDraft(String tenant, UUID id, UUID toolId, String version, String inputSchema,
                        String outputSchema, String contractHash) {}
    record Endpoint(UUID id, String targetRef, int timeoutMs, String protocol) {}
    record EndpointDraft(String tenant, UUID id, UUID versionId, UUID serverId, String targetRef, int timeoutMs,
                         String protocol) {}
    record Permission(String tenant, UUID toolId, String subjectType, String subjectId, String action, String effect) {}
    record Authorization(String tenant, UUID toolId, String principal, String agent, String rolesJson, String action) {}
    record Search(String tenant, String principal, String agent, String rolesJson, String afterName, int limit) {}
    record Candidate(UUID toolId, UUID versionId, String name, String description, String versionName, String category,
                     String protocol, String targetRef, String inputSchema) {}

    record RequestKey(String tenant, UUID toolId, String agent, String principal, String idempotencyKey) {}
    record Execution(UUID id, UUID toolId, UUID versionId, String agentId, String principalId, String operationHash,
                     String status, String errorCode) {}
    record Reservation(String tenant, UUID id, UUID toolId, UUID versionId, UUID endpointId, String agent, String principal,
                       String traceId, String idempotencyKey, String operationHash, int timeoutMs) {}
    record Finish(String tenant, UUID id, String status, String errorCode) {}
    record Audit(String tenant, UUID id, UUID executionId, UUID toolId, String actor, String action, String traceId) {}
    record CatalogChange(String tenant, UUID eventId, UUID toolId, UUID versionId, long revision,
                         String eventType, String traceId) {}
    record IndexEvent(String tenant, UUID eventId, UUID toolId, long epoch) {}
    record IndexReceipt(String tenant, UUID eventId, long epoch, String worker, boolean indexed) {}
}
