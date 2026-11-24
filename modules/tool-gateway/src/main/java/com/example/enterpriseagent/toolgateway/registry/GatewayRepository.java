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
    List<PolicyRow> activePolicies(String tenant, UUID toolId);
    int disablePolicy(String tenant, UUID toolId, String kind);
    int insertPolicy(PolicyDraft row);
    int consumeUsage(UsageWindow window);
    List<Candidate> discover(Search query);
    List<Candidate> indexable(String tenant, String afterName, int limit);
    Candidate indexableTool(String tenant, UUID toolId);

    Execution findRequest(RequestKey key);
    Execution findExecution(String tenant, UUID id);
    UUID linkedExecution(String tenant, UUID run, UUID step);
    ReconciliationSource reconciliationSource(String tenant, UUID run, UUID step, UUID execution);
    int confirmUnknownExecution(String tenant, UUID run, UUID step, UUID execution);
    RuntimeStep lockRuntimeStep(RuntimeRequest request);
    RuntimeStep lockLinkedRun(String tenant, UUID execution);
    int cancelLinked(String tenant, UUID id);
    int cancelOpenApproval(String tenant, UUID id);
    int emitExecutionChanged(String tenant, UUID id, UUID eventId);
    int reserve(Reservation row);
    int finish(Finish row);
    int expire(String tenant, UUID id);
    int insertJob(JobPayload row);
    int insertApproval(ApprovalDraft row);
    ApprovalView approvalForExecution(String tenant, UUID executionId);
    ApprovalView approval(String tenant, UUID approvalId);
    Execution lockExecution(String tenant, UUID id);
    int decideApproval(ApprovalDecision row);
    int afterApproval(String tenant, UUID id, String status);
    String readJobResult(String tenant, UUID id);
    JobClaim claimJob(String worker);
    int settleJob(JobSettlement row);
    int saveJobResult(String tenant, UUID id, String encryptedResult);
    int expireApprovalBatch();
    int expireJobLeases();
    int cleanupJobs();
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
    record Endpoint(UUID id, String targetRef, int timeoutMs, String protocol, String credentialRef,
                    boolean idempotencySupported) {}
    record EndpointDraft(String tenant, UUID id, UUID versionId, UUID serverId, String targetRef, int timeoutMs,
                         String protocol, String credentialRef, boolean asyncCapable, boolean idempotencySupported) {}
    record Permission(String tenant, UUID toolId, String subjectType, String subjectId, String action, String effect) {}
    record Authorization(String tenant, UUID toolId, String principal, String agent, String rolesJson, String action) {}
    record PolicyRow(String kind, int policyVersion, String rule) {}
    record PolicyDraft(String tenant, UUID toolId, UUID id, String kind, String rule) {}
    record UsageWindow(String tenant, UUID toolId, String scope, String subject, String unit, int limit) {}
    record Search(String tenant, String principal, String agent, String rolesJson, String afterName, int limit) {}
    record Candidate(UUID toolId, UUID versionId, String name, String description, String versionName, String category,
                     String protocol, String targetRef, String inputSchema, String credentialRef) {}

    record RequestKey(String tenant, UUID toolId, String agent, String principal, String idempotencyKey) {}
    record Execution(UUID id, UUID toolId, UUID versionId, String agentId, String principalId, String operationHash,
                     String status, String errorCode) {}
    record Reservation(String tenant, UUID id, UUID toolId, UUID versionId, UUID endpointId, String agent, String principal,
                       String traceId, String idempotencyKey, String operationHash, int timeoutMs, String status,
                       UUID runId, UUID stepId) {}
    record Finish(String tenant, UUID id, String status, String errorCode) {}
    record Audit(String tenant, UUID id, UUID executionId, UUID toolId, String actor, String action, String traceId) {}
    record CatalogChange(String tenant, UUID eventId, UUID toolId, UUID versionId, long revision,
                         String eventType, String traceId) {}
    record IndexEvent(String tenant, UUID eventId, UUID toolId, long epoch) {}
    record IndexReceipt(String tenant, UUID eventId, long epoch, String worker, boolean indexed) {}
    record JobPayload(String tenant, UUID id, String encryptedRequest) {}
    record ApprovalDraft(String tenant, UUID id, UUID executionId, UUID versionId, String operationHash,
                         String requester, String policyVersion, int ttlSeconds) {}
    record ApprovalView(UUID id, UUID executionId, UUID toolId, String toolName, String traceId, String requester,
                        String policyVersion, String status, String decidedBy, boolean expired) {}
    record ApprovalDecision(String tenant, UUID id, String actor, String status) {}
    record JobClaim(String tenant, UUID id, UUID toolId, String toolName, UUID versionId, UUID endpointId, String agent,
                    String principal, String traceId, String operationHash, String phase, long epoch,
                    String encryptedRequest, String externalJobId, boolean expired) {}
    record JobSettlement(String tenant, UUID id, String worker, long epoch, String status, String errorCode,
                         String externalJobId, int pollAfterMs) {}
    record RuntimeRequest(String tenant, UUID run, UUID step, String agent, String principal, String key) {}
    record RuntimeStep(boolean stopped) {}
    record ReconciliationSource(String toolName, String traceId, String encryptedRequest) {}
}
