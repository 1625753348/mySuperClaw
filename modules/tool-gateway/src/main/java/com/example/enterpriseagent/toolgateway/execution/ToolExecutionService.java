package com.example.enterpriseagent.toolgateway.execution;

import com.example.enterpriseagent.toolgateway.GatewayCaller;
import com.example.enterpriseagent.toolgateway.GatewayException;
import com.example.enterpriseagent.toolgateway.adapter.ToolAdapter;
import com.example.enterpriseagent.toolgateway.adapter.ToolAdapterFactory;
import com.example.enterpriseagent.toolgateway.registry.GatewayRepository;
import com.example.enterpriseagent.toolgateway.registry.ToolContracts;
import com.example.enterpriseagent.toolgateway.registry.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
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
    private final TransactionOperations transactions;

    public ToolExecutionService(GatewayRepository store, ToolRegistry registry, ToolContracts contracts,
                                ToolAdapterFactory adapters, TransactionOperations transactions) {
        this.store = store;
        this.registry = registry;
        this.contracts = contracts;
        this.adapters = adapters;
        this.transactions = transactions;
    }

    public Result execute(GatewayCaller caller, Request request, String requestKey) {
        if (requestKey == null || requestKey.isBlank() || requestKey.length() > 128) {
            throw new GatewayException(INVALID, "需要有效的 Idempotency-Key");
        }
        String payload = contracts.arguments(request.arguments());
        Dispatch dispatch = transactions.execute(ignored -> prepare(caller, request, requestKey, payload));
        if (dispatch.previous() != null) {
            return result(dispatch.previous(), null, true);
        }

        Reservation reservation = dispatch.reservation();
        var endpoint = dispatch.endpoint();
        var response = adapters.require(endpoint.protocol()).invoke(new ToolAdapter.Call(endpoint.targetRef(),
                endpoint.timeoutMs(), payload, reservation.id().toString(), reservation.traceId(), caller));
        JsonNode output = null;
        Finish finished = new Finish(caller.tenantId(), reservation.id(), response.status(), response.errorCode());
        if ("SUCCEEDED".equals(response.status())) {
            try {
                output = contracts.decode(response.body());
                if (output == null || output.isMissingNode() || !contracts.matches(dispatch.version().outputSchema(), output)) {
                    throw new IllegalArgumentException("输出契约未满足");
                }
            } catch (RuntimeException ex) {
                output = null;
                finished = new Finish(caller.tenantId(), reservation.id(), "FAILED", "OUTPUT_SCHEMA_INVALID");
            }
        }
        Finish update = finished;
        Execution saved = transactions.execute(ignored -> {
            if (store.finish(update) == 1) {
                registry.audit(caller, reservation.toolId(), reservation.id(), "EXECUTION_" + update.status(), reservation.traceId());
            }
            return store.findExecution(caller.tenantId(), reservation.id());
        });
        return result(saved, "SUCCEEDED".equals(saved.status()) ? output : null, false);
    }

    public Result get(GatewayCaller caller, UUID executionId) {
        return transactions.execute(ignored -> {
            Execution row = store.findExecution(caller.tenantId(), executionId);
            if (row == null || !row.principalId().equals(caller.principalId()) || !row.agentId().equals(caller.agentId())) {
                throw new GatewayException(NOT_FOUND, "执行记录不存在");
            }
            registry.requirePermission(caller, row.toolId(), "EXECUTE");
            return result(expireIfNecessary(caller, row), null, false);
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
            return new Dispatch(expireIfNecessary(caller, previous), null, null, null);
        }
        ToolRegistry.published(version);
        if ("HIGH".equals(tool.riskLevel()) || "CRITICAL".equals(tool.riskLevel())
                || store.hasActivePolicy(caller.tenantId(), tool.id())) {
            throw new GatewayException(POLICY_REQUIRED, "工具需要策略或人工审批");
        }
        if (!contracts.matches(version.inputSchema(), request.arguments())) {
            throw new GatewayException(INVALID, "参数不符合该工具版本的输入 Schema");
        }
        Endpoint endpoint = registry.endpoint(caller, version);
        var reservation = new Reservation(caller.tenantId(), UUID.randomUUID(), tool.id(), version.id(), endpoint.id(),
                caller.agentId(), caller.principalId(), ToolRegistry.trace(), requestKey, digest, endpoint.timeoutMs());
        if (store.reserve(reservation) != 1) {
            throw new GatewayException(CONFLICT, "工具请求已被另一调用预占");
        }
        registry.audit(caller, tool.id(), reservation.id(), "EXECUTION_RESERVED", reservation.traceId());
        return new Dispatch(null, reservation, version, endpoint);
    }

    private Execution expireIfNecessary(GatewayCaller caller, Execution row) {
        if ("RUNNING".equals(row.status()) && store.expire(caller.tenantId(), row.id()) == 1) {
            registry.audit(caller, row.toolId(), row.id(), "EXECUTION_LEASE_EXPIRED", ToolRegistry.trace());
            return store.findExecution(caller.tenantId(), row.id());
        }
        return row;
    }

    private static Result result(Execution row, JsonNode output, boolean replayed) {
        return new Result(row.id(), row.versionId(), row.status(), row.errorCode(), output, replayed);
    }

    private record Dispatch(Execution previous, Reservation reservation, Version version, Endpoint endpoint) {}
    public record Request(String toolName, String version, JsonNode arguments) {}
    public record Result(UUID executionId, UUID versionId, String status, String errorCode, JsonNode output,
                         boolean replayed) {}
}
