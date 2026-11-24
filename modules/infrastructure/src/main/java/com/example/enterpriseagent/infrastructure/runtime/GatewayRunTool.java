package com.example.enterpriseagent.infrastructure.runtime;

import com.example.enterpriseagent.runtime.RunFailure;
import com.example.enterpriseagent.runtime.execution.RunTool;
import com.example.enterpriseagent.toolgateway.GatewayCaller;
import com.example.enterpriseagent.toolgateway.GatewayException;
import com.example.enterpriseagent.toolgateway.execution.ToolExecutionService;
import com.example.enterpriseagent.toolgateway.execution.ToolReconciliationService;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

import static com.example.enterpriseagent.runtime.RunModel.*;

/** 运行时的来源工具按既有网关策略授权、排队和回查。 */
public final class GatewayRunTool implements RunTool {
    private final ToolExecutionService gateway;
    private final ToolReconciliationService reconciliation;

    public GatewayRunTool(ToolExecutionService gateway, ToolReconciliationService reconciliation) {
        this.gateway = gateway;
        this.reconciliation = reconciliation;
    }

    @Override
    public void validate(Actor actor, Source source) {
        try {
            gateway.validateQueued(caller(actor), request(source));
        } catch (GatewayException ex) {
            var code = switch (ex.code()) {
                case FORBIDDEN, POLICY_REQUIRED -> RunFailure.Code.FORBIDDEN;
                case NOT_FOUND -> RunFailure.Code.NOT_FOUND;
                default -> RunFailure.Code.INVALID;
            };
            throw new RunFailure(code, ex.getMessage());
        }
    }

    @Override
    public ToolStatus submit(Actor actor, UUID runId, UUID stepId, Source source, String key) {
        return submitPinned(actor, runId, stepId, source, key, null);
    }

    @Override
    public ToolStatus submitPinned(Actor actor, UUID runId, UUID stepId, Source source, String key, UUID versionId) {
        try {
            return status(gateway.submitForRun(caller(actor), request(source), key, runId, stepId, versionId));
        } catch (GatewayException ex) {
            if (ex.code() == GatewayException.Code.RATE_LIMITED) {
                return new ToolStatus(null, "RETRY_WAIT", null, null, "TOOL_RATE_LIMITED");
            }
            return new ToolStatus(null, "FAILED", null, null, ex.code().name());
        }
    }

    @Override
    public ToolStatus read(Actor actor, UUID runId, UUID stepId, UUID executionId, boolean cancel) {
        try {
            return status(gateway.readForRun(caller(actor), runId, stepId, executionId, cancel));
        } catch (GatewayException ex) {
            return new ToolStatus(executionId, "UNKNOWN", null, null, ex.code().name());
        }
    }

    @Override
    public JsonNode confirmInTransaction(Actor operator, Actor owner, UUID runId, UUID stepId, UUID executionId,
                                         JsonNode verifiedResult) {
        try {
            return reconciliation.confirmInTransaction(caller(operator), caller(owner), runId, stepId, executionId, verifiedResult);
        } catch (GatewayException ex) {
            var code = switch (ex.code()) {
                case FORBIDDEN, POLICY_REQUIRED -> RunFailure.Code.FORBIDDEN;
                case NOT_FOUND -> RunFailure.Code.NOT_FOUND;
                case CONFLICT -> RunFailure.Code.CONFLICT;
                case RATE_LIMITED, UPSTREAM -> RunFailure.Code.BUSY;
                case INVALID -> RunFailure.Code.INVALID;
            };
            throw new RunFailure(code, ex.getMessage());
        }
    }

    private static GatewayCaller caller(Actor actor) {
        return new GatewayCaller(actor.tenant(), actor.user(), actor.agent(), actor.roles());
    }

    private static ToolExecutionService.Request request(Source source) {
        return new ToolExecutionService.Request(source.name(), source.version(), source.arguments());
    }

    private static ToolStatus status(ToolExecutionService.Result result) {
        return new ToolStatus(result.executionId(), result.status(), result.output(), result.approvalId(), result.errorCode());
    }
}
