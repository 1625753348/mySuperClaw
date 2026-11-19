package com.example.enterpriseagent.toolgateway.execution;

import com.example.enterpriseagent.toolgateway.GatewayCaller;
import com.example.enterpriseagent.toolgateway.GatewayException;
import com.example.enterpriseagent.toolgateway.adapter.ToolAdapter;
import com.example.enterpriseagent.toolgateway.adapter.ToolAdapterFactory;
import com.example.enterpriseagent.toolgateway.registry.GatewayRepository;
import com.example.enterpriseagent.toolgateway.registry.ToolContracts;
import com.example.enterpriseagent.toolgateway.registry.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.support.TransactionOperations;

import java.util.UUID;

import static com.example.enterpriseagent.toolgateway.GatewayException.Code.*;
import static com.example.enterpriseagent.toolgateway.registry.GatewayRepository.*;

/** 预占、外部调用、结算分开。DB 唯一约束及短事务共同约束重复派发。 */
public final class ToolExecutionService {

    private final GatewayRepository store;
    private final ToolRegistry registry;
    private final ToolContracts contracts;
    private final ToolAdapterFactory adapters;
    private final ToolPolicyService policies;
    private final ToolSecrets secrets;
    private final ToolResilience resilience;
    private final ToolJobCipher cipher;
    private final ObjectMapper json;
    private final TransactionOperations transactions;

    public ToolExecutionService(GatewayRepository store, ToolRegistry registry, ToolContracts contracts,
                                ToolAdapterFactory adapters, ToolPolicyService policies, ToolSecrets secrets,
                                ToolResilience resilience, ToolJobCipher cipher, ObjectMapper json,
                                TransactionOperations transactions) {
        this.store = store;
        this.registry = registry;
        this.contracts = contracts;
        this.adapters = adapters;
        this.policies = policies;
        this.secrets = secrets;
        this.resilience = resilience;
        this.cipher = cipher;
        this.json = json;
        this.transactions = transactions;
    }

    public Result execute(GatewayCaller caller, Request request, String requestKey) {
        if (requestKey == null || requestKey.isBlank() || requestKey.length() > 128) {
            throw new GatewayException(INVALID, "需要有效的 Idempotency-Key");
        }
        String payload = contracts.arguments(request.arguments());
        Dispatch dispatch = transactions.execute(ignored -> prepare(caller, request, requestKey, payload));
        if (dispatch.previous() != null) {
            return storedResult(caller, dispatch.previous(), true);
        }

        Reservation reservation = dispatch.reservation();
        if (!"RUNNING".equals(reservation.status())) {
            return storedResult(caller, store.findExecution(caller.tenantId(), reservation.id()), false);
        }
        var response = invoke(caller, dispatch.endpoint(), reservation.id(), reservation.traceId(),
                payload, dispatch.rules(), null);
        Checked checked = checkOutput(response, dispatch.version(), dispatch.rules());
        Finish update = new Finish(caller.tenantId(), reservation.id(), checked.status(), checked.errorCode());
        Execution saved = transactions.execute(ignored -> {
            if (store.finish(update) == 1) {
                registry.audit(caller, reservation.toolId(), reservation.id(), "EXECUTION_" + update.status(), reservation.traceId());
            }
            return store.findExecution(caller.tenantId(), reservation.id());
        });
        return new Result(saved.id(), saved.versionId(), saved.status(), saved.errorCode(),
                "SUCCEEDED".equals(saved.status()) ? checked.output() : null, false, null);
    }

    public Result get(GatewayCaller caller, UUID executionId) {
        return transactions.execute(ignored -> {
            Execution row = store.findExecution(caller.tenantId(), executionId);
            if (row == null || !row.principalId().equals(caller.principalId()) || !row.agentId().equals(caller.agentId())) {
                throw new GatewayException(NOT_FOUND, "执行记录不存在");
            }
            registry.requirePermission(caller, row.toolId(), "EXECUTE");
            return storedResult(caller, expireIfNecessary(caller, row), false);
        });
    }

    private Dispatch prepare(GatewayCaller caller, Request request, String requestKey, String payload) {
        Tool tool = ToolRegistry.active(registry.lock(caller, request.toolName()));
        registry.requirePermission(caller, tool.id(), "EXECUTE");
        RequestKey key = new RequestKey(caller.tenantId(), tool.id(), caller.agentId(), caller.principalId(), requestKey);
        Execution previous = store.findRequest(key);
        Version version = registry.resolve(caller, tool, request.version(), previous == null ? null : previous.versionId());
        String digest = ToolContracts.digest(version.id() + "\n" + payload);
        if (previous != null) {
            if ((request.version() != null && !request.version().equals(version.versionName()))
                    || !digest.equals(previous.operationHash())) {
                throw new GatewayException(CONFLICT, "幂等键已用于其它版本或参数");
            }
            return new Dispatch(expireIfNecessary(caller, previous), null, null, null, null);
        }
        ToolRegistry.published(version);
        ToolPolicyService.Rules rules = policies.current(caller.tenantId(), tool.id());
        policies.authorize(rules, caller, request.arguments());
        if (needsApproval(tool) && rules.approval() == null) {
            throw new GatewayException(POLICY_REQUIRED, "工具需要策略或人工审批");
        }
        if (!contracts.matches(version.inputSchema(), request.arguments())) {
            throw new GatewayException(INVALID, "参数不符合该工具版本的输入 Schema");
        }
        Endpoint endpoint = registry.endpoint(caller, version);
        String status = rules.approval() != null ? "WAITING_APPROVAL" : "ASYNC".equals(endpoint.protocol()) ? "QUEUED" : "RUNNING";
        if (!"RUNNING".equals(status) && !cipher.enabled()) {
            throw new GatewayException(POLICY_REQUIRED, "工具需要配置异步恢复密钥");
        }
        policies.consume(rules, caller, tool.id());
        var reservation = new Reservation(caller.tenantId(), UUID.randomUUID(), tool.id(), version.id(), endpoint.id(),
                caller.agentId(), caller.principalId(), ToolRegistry.trace(), requestKey, digest, endpoint.timeoutMs(), status);
        if (store.reserve(reservation) != 1) {
            throw new GatewayException(CONFLICT, "工具请求已被另一调用预占");
        }
        registry.audit(caller, tool.id(), reservation.id(), "EXECUTION_RESERVED", reservation.traceId());
        if (!"RUNNING".equals(status)) {
            var requestSnapshot = json.createObjectNode();
            requestSnapshot.set("arguments", request.arguments());
            requestSnapshot.set("roles", json.valueToTree(caller.roles()));
            store.insertJob(new JobPayload(caller.tenantId(), reservation.id(),
                    cipher.seal(caller.tenantId(), reservation.id(), "request", requestSnapshot.toString())));
            if (rules.approval() != null) {
                store.insertApproval(new ApprovalDraft(caller.tenantId(), UUID.randomUUID(), reservation.id(), version.id(),
                        digest, caller.principalId(), Integer.toString(rules.approval().version()), rules.approval().ttlSeconds()));
                registry.audit(caller, tool.id(), reservation.id(), "APPROVAL_REQUESTED", reservation.traceId());
            }
        }
        return new Dispatch(null, reservation, version, endpoint, rules);
    }

    static boolean needsApproval(Tool tool) {
        return "HIGH".equals(tool.riskLevel()) || "CRITICAL".equals(tool.riskLevel());
    }

    ToolAdapter.Outcome invoke(GatewayCaller caller, Endpoint endpoint, UUID id, String traceId, String payload,
                               ToolPolicyService.Rules rules, String externalJobId) {
        ToolAdapter adapter = adapters.require(endpoint.protocol());
        ToolSecrets.Credential credential = secrets.resolve(caller.tenantId(), endpoint.protocol(),
                endpoint.targetRef(), endpoint.credentialRef());
        if (externalJobId != null) {
            if (!(adapter instanceof com.example.enterpriseagent.toolgateway.adapter.AsyncToolAdapter async)) {
                return new ToolAdapter.Outcome("BLOCKED", null, "ASYNC_ENDPOINT_CHANGED");
            }
            return async.poll(new ToolAdapter.Call(endpoint.targetRef(), endpoint.timeoutMs(), payload,
                    id.toString(), traceId, caller, credential), externalJobId);
        }
        return resilience.invoke(caller.tenantId(), endpoint.id(), endpoint.timeoutMs(),
                endpoint.idempotencySupported() || adapter.protocol() == ToolAdapter.Protocol.DATABASE,
                rules.retry(), timeout -> adapter.invoke(new ToolAdapter.Call(endpoint.targetRef(),
                        timeout, payload, id.toString(), traceId, caller, credential)));
    }

    Checked checkOutput(ToolAdapter.Outcome response, Version version, ToolPolicyService.Rules rules) {
        if (!"SUCCEEDED".equals(response.status())) {
            return new Checked(response.status(), response.errorCode(), null);
        }
        try {
            JsonNode output = contracts.decode(response.body());
            if (output == null || output.isMissingNode() || !contracts.matches(version.outputSchema(), output)) {
                throw new IllegalArgumentException("输出契约未满足");
            }
            return new Checked("SUCCEEDED", null, policies.redact(rules, output));
        } catch (RuntimeException ex) {
            return new Checked("FAILED", "OUTPUT_SCHEMA_INVALID", null);
        }
    }

    private Execution expireIfNecessary(GatewayCaller caller, Execution row) {
        if ("RUNNING".equals(row.status()) && store.expire(caller.tenantId(), row.id()) == 1) {
            registry.audit(caller, row.toolId(), row.id(), "EXECUTION_LEASE_EXPIRED", ToolRegistry.trace());
            return store.findExecution(caller.tenantId(), row.id());
        }
        return row;
    }

    private Result storedResult(GatewayCaller caller, Execution row, boolean replayed) {
        ApprovalView approval = "WAITING_APPROVAL".equals(row.status())
                ? store.approvalForExecution(caller.tenantId(), row.id()) : null;
        JsonNode output = null;
        if ("SUCCEEDED".equals(row.status()) && cipher.enabled()) {
            String result = store.readJobResult(caller.tenantId(), row.id());
            if (result != null) {
                output = contracts.decode(cipher.open(caller.tenantId(), row.id(), "result", result));
                output = policies.redact(policies.current(caller.tenantId(), row.toolId()), output);
            }
        }
        return new Result(row.id(), row.versionId(), row.status(), row.errorCode(), output, replayed,
                approval == null ? null : approval.id());
    }

    private record Dispatch(Execution previous, Reservation reservation, Version version, Endpoint endpoint,
                            ToolPolicyService.Rules rules) {}
    record Checked(String status, String errorCode, JsonNode output) {}
    public record Request(String toolName, String version, JsonNode arguments) {}
    public record Result(UUID executionId, UUID versionId, String status, String errorCode, JsonNode output,
                         boolean replayed, UUID approvalId) {}
}
