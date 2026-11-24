package com.example.enterpriseagent.toolgateway.execution;

import com.example.enterpriseagent.toolgateway.GatewayCaller;
import com.example.enterpriseagent.toolgateway.GatewayException;
import com.example.enterpriseagent.toolgateway.adapter.ToolAdapter;
import com.example.enterpriseagent.toolgateway.registry.GatewayRepository;
import com.example.enterpriseagent.toolgateway.registry.ToolContracts;
import com.example.enterpriseagent.toolgateway.registry.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

import static com.example.enterpriseagent.toolgateway.GatewayException.Code.*;

/** 仅补记已发生的工具结果。此服务没有适配器调用入口。 */
public final class ToolReconciliationService {
    private final GatewayRepository store;
    private final ToolRegistry registry;
    private final ToolContracts contracts;
    private final ToolPolicyService policies;
    private final ToolExecutionService execution;
    private final ToolJobCipher cipher;

    public ToolReconciliationService(GatewayRepository store, ToolRegistry registry, ToolContracts contracts,
                                     ToolPolicyService policies, ToolExecutionService execution, ToolJobCipher cipher) {
        this.store = store;
        this.registry = registry;
        this.contracts = contracts;
        this.policies = policies;
        this.execution = execution;
        this.cipher = cipher;
    }

    public JsonNode confirmInTransaction(GatewayCaller operator, GatewayCaller owner, UUID run, UUID step,
                                         UUID executionId, JsonNode verifiedResult) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("补记工具结果需要在运行事务内执行");
        }
        String tenant = operator.tenantId();
        if (!tenant.equals(owner.tenantId())) throw new GatewayException(NOT_FOUND, "原执行关联不存在");
        if (!cipher.enabled()) throw new GatewayException(POLICY_REQUIRED, "恢复密钥未配置");
        var source = store.reconciliationSource(tenant, run, step, executionId);
        if (source == null) throw new GatewayException(CONFLICT, "原执行关联或加密恢复记录已不可用");

        // 与审批采用相同的工具锁、执行锁顺序，防止并发确认产生双份结果。
        var tool = ToolRegistry.active(registry.lock(operator, source.toolName()));
        registry.requirePermission(operator, tool.id(), "MANAGE");
        registry.requirePermission(owner, tool.id(), "EXECUTE");
        var row = store.lockExecution(tenant, executionId);
        if (row == null || !tool.id().equals(row.toolId()) || !owner.principalId().equals(row.principalId())
                || !owner.agentId().equals(row.agentId()) || !"UNKNOWN".equals(row.status())) {
            throw new GatewayException(CONFLICT, "工具执行已变化或当前不处于结果未知状态");
        }
        var version = ToolRegistry.published(registry.resolve(owner, tool, null, row.versionId()));
        var rules = policies.current(tenant, tool.id());
        checkOriginalRequest(tenant, owner, row, source, rules);
        if (!ToolJobService.approved(tool, rules.approval(), store.approvalForExecution(tenant, row.id()))) {
            throw new GatewayException(POLICY_REQUIRED, "当前策略下缺少原执行的有效审批");
        }
        var checked = execution.checkOutput(new ToolAdapter.Outcome("SUCCEEDED", verifiedResult.toString(), null), version, rules);
        if (checked.output() == null) throw new GatewayException(INVALID, "补记结果不符合原工具输出 Schema");

        String encrypted = cipher.seal(tenant, executionId, "result", checked.output().toString());
        if (store.confirmUnknownExecution(tenant, run, step, executionId) != 1
                || store.saveJobResult(tenant, executionId, encrypted) != 1) {
            throw new GatewayException(CONFLICT, "工具执行或加密恢复记录已变化");
        }
        registry.audit(operator, tool.id(), row.id(), "EXECUTION_RECONCILED_SUCCEEDED", source.traceId());
        store.emitExecutionChanged(tenant, row.id(), UUID.randomUUID());
        return checked.output();
    }

    private void checkOriginalRequest(String tenant, GatewayCaller owner, GatewayRepository.Execution row,
                                      GatewayRepository.ReconciliationSource source, ToolPolicyService.Rules rules) {
        if (source.encryptedRequest() == null) {
            if (rules.abac() != null) throw new GatewayException(POLICY_REQUIRED, "缺少原请求，无法复核当前数据权限");
            return;
        }
        JsonNode request = contracts.decode(cipher.open(tenant, row.id(), "request", source.encryptedRequest()));
        JsonNode arguments = request.path("arguments");
        String digest = ToolContracts.digest(row.versionId() + "\n" + contracts.arguments(arguments));
        if (!row.operationHash().equals(digest)) throw new GatewayException(CONFLICT, "原工具参数指纹不匹配");
        policies.authorize(rules, owner, arguments);
    }
}
