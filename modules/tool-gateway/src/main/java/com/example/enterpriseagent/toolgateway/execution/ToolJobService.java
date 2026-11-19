package com.example.enterpriseagent.toolgateway.execution;

import com.example.enterpriseagent.toolgateway.GatewayCaller;
import com.example.enterpriseagent.toolgateway.GatewayException;
import com.example.enterpriseagent.toolgateway.registry.GatewayRepository;
import com.example.enterpriseagent.toolgateway.registry.ToolContracts;
import com.example.enterpriseagent.toolgateway.registry.ToolRegistry;
import org.springframework.transaction.support.TransactionOperations;

import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

import static com.example.enterpriseagent.toolgateway.GatewayException.Code.*;
import static com.example.enterpriseagent.toolgateway.registry.GatewayRepository.*;

/** 持久化审批、领取后台执行并用租约围栏结算。批准与派发分开。 */
public final class ToolJobService {
    private static final String WAITING_EXTERNAL = "WAITING_EXTERNAL";
    private final String worker = "tool-job-" + UUID.randomUUID();
    private final GatewayRepository store;
    private final ToolRegistry registry;
    private final ToolContracts contracts;
    private final ToolPolicyService policies;
    private final ToolExecutionService execution;
    private final ToolJobCipher cipher;
    private final TransactionOperations transactions;

    public ToolJobService(GatewayRepository store, ToolRegistry registry, ToolContracts contracts,
                          ToolPolicyService policies, ToolExecutionService execution, ToolJobCipher cipher,
                          TransactionOperations transactions) {
        this.store = store;
        this.registry = registry;
        this.contracts = contracts;
        this.policies = policies;
        this.execution = execution;
        this.cipher = cipher;
        this.transactions = transactions;
    }

    public ApprovalView review(GatewayCaller reviewer, UUID id) {
        ApprovalView approval = approval(reviewer, id);
        registry.requirePermission(reviewer, approval.toolId(), "APPROVE");
        return approval;
    }

    public ApprovalView decide(GatewayCaller reviewer, UUID id, String decision) {
        if (decision == null || !Set.of("APPROVE", "REJECT").contains(decision)) {
            throw new GatewayException(INVALID, "审批决定需要 APPROVE 或 REJECT");
        }
        return transactions.execute(ignored -> {
            ApprovalView existing = approval(reviewer, id);
            Tool tool = registry.lock(reviewer, existing.toolName());
            registry.requirePermission(reviewer, tool.id(), "APPROVE");
            if (reviewer.principalId().equals(existing.requester())) {
                throw new GatewayException(FORBIDDEN, "申请人不能审批自己的执行");
            }
            Execution row = store.lockExecution(reviewer.tenantId(), existing.executionId());
            ApprovalView current = approval(reviewer, id);
            String next = "APPROVE".equals(decision) ? "APPROVED" : "REJECTED";
            if (!"PENDING".equals(current.status())) {
                if (next.equals(current.status()) && reviewer.principalId().equals(current.decidedBy())) return current;
                if ("EXPIRED".equals(current.status())) return current;
                throw new GatewayException(CONFLICT, "审批已由其他决定完成");
            }
            if (!"WAITING_APPROVAL".equals(row.status())) throw new GatewayException(CONFLICT, "执行已离开审批等待");
            if (current.expired()) next = "EXPIRED";
            String executionStatus = "APPROVED".equals(next) ? "QUEUED" : next;
            String actor = "EXPIRED".equals(next) ? null : reviewer.principalId();
            if (store.decideApproval(new ApprovalDecision(reviewer.tenantId(), id, actor, next)) != 1
                    || store.afterApproval(reviewer.tenantId(), row.id(), executionStatus) != 1) {
                throw new GatewayException(CONFLICT, "审批状态发生并发变更");
            }
            if (!"QUEUED".equals(executionStatus)) store.saveJobResult(reviewer.tenantId(), row.id(), null);
            registry.audit(reviewer, tool.id(), row.id(), "APPROVAL_" + next, current.traceId());
            store.emitExecutionChanged(reviewer.tenantId(), row.id(), UUID.randomUUID());
            return approval(reviewer, id);
        });
    }

    private ApprovalView approval(GatewayCaller caller, UUID id) {
        ApprovalView approval = store.approval(caller.tenantId(), id);
        if (approval == null) throw new GatewayException(NOT_FOUND, "审批不存在");
        return approval;
    }

    public void processOne() {
        if (!cipher.enabled()) return;
        JobClaim job = store.claimJob(worker);
        if (job == null) return;
        var owner = new GatewayCaller(job.tenant(), job.principal(), job.agent(), null);
        if (job.expired()) {
            settle(job, owner, WAITING_EXTERNAL.equals(job.phase()) ? "UNKNOWN" : "EXPIRED", "TOOL_JOB_DEADLINE", null, null);
            return;
        }
        Prepared prepared;
        try {
            prepared = prepare(job);
            if (prepared == null) {
                settle(job, owner, "CANCELLED", null, null, null);
                return;
            }
        } catch (GatewayException | IllegalStateException ex) {
            settle(job, owner, WAITING_EXTERNAL.equals(job.phase()) ? "UNKNOWN" : "BLOCKED",
                    "TOOL_JOB_REVALIDATION_FAILED", null, null);
            return;
        }
        var response = execution.invoke(prepared.caller(), prepared.endpoint(), job.id(), job.traceId(),
                prepared.arguments(), prepared.rules(), job.externalJobId());
        var checked = execution.checkOutput(response, prepared.version(), prepared.rules());
        String encrypted = checked.output() == null ? null :
                cipher.seal(job.tenant(), job.id(), "result", checked.output().toString());
        settle(job, prepared.caller(), checked.status(), checked.errorCode(), response.externalJobId(), encrypted);
    }

    private Prepared prepare(JobClaim job) {
        var context = contracts.decode(cipher.open(job.tenant(), job.id(), "request", job.encryptedRequest()));
        var roles = new ArrayList<String>();
        context.path("roles").forEach(role -> roles.add(role.asText()));
        var caller = new GatewayCaller(job.tenant(), job.principal(), job.agent(), roles);
        var arguments = context.path("arguments");
        String content = contracts.arguments(arguments);
        if (!ToolContracts.digest(job.versionId() + "\n" + content).equals(job.operationHash())) {
            throw new GatewayException(CONFLICT, "恢复参数与原始指纹不匹配");
        }
        return transactions.execute(ignored -> {
            RuntimeStep link = store.lockLinkedRun(job.tenant(), job.id());
            if (link != null && link.stopped() && !WAITING_EXTERNAL.equals(job.phase())) return null;
            Tool tool = ToolRegistry.active(registry.lock(caller, job.toolName()));
            registry.requirePermission(caller, tool.id(), "EXECUTE");
            Version version = ToolRegistry.published(registry.resolve(caller, tool, null, job.versionId()));
            Endpoint endpoint = registry.endpoint(caller, version);
            if (!endpoint.id().equals(job.endpointId()) || !contracts.matches(version.inputSchema(), arguments)
                    || (WAITING_EXTERNAL.equals(job.phase()) && job.externalJobId() == null)) {
                throw new GatewayException(CONFLICT, "恢复端点或参数与原执行不匹配");
            }
            ToolPolicyService.Rules rules = policies.current(caller.tenantId(), tool.id());
            policies.authorize(rules, caller, arguments);
            ApprovalView approval = store.approvalForExecution(caller.tenantId(), job.id());
            if (!approved(tool, rules.approval(), approval)) {
                throw new GatewayException(POLICY_REQUIRED, "审批策略已变化或缺少有效批准");
            }
            return new Prepared(caller, content, version, endpoint, rules);
        });
    }

    private static boolean approved(Tool tool, ToolPolicyService.Approval rule, ApprovalView approval) {
        if (rule == null) return approval == null && !ToolExecutionService.needsApproval(tool);
        return approval != null && "APPROVED".equals(approval.status())
                && Integer.toString(rule.version()).equals(approval.policyVersion());
    }

    private void settle(JobClaim job, GatewayCaller caller, String status, String code, String externalId, String encrypted) {
        transactions.executeWithoutResult(ignored -> {
            boolean waiting = WAITING_EXTERNAL.equals(status);
            if (store.settleJob(new JobSettlement(job.tenant(), job.id(), worker, job.epoch(), status, code,
                    externalId, waiting ? 1_000 : 0)) == 1) {
                if (!waiting) store.saveJobResult(job.tenant(), job.id(), encrypted);
                if (!waiting || externalId != null) {
                    registry.audit(caller, job.toolId(), job.id(), "EXECUTION_" + status, job.traceId());
                    store.emitExecutionChanged(job.tenant(), job.id(), UUID.randomUUID());
                }
            }
        });
    }

    public void recoverExpired() {
        if (!cipher.enabled()) return;
        store.expireApprovalBatch();
        store.expireJobLeases();
    }

    private record Prepared(GatewayCaller caller, String arguments, Version version, Endpoint endpoint,
                            ToolPolicyService.Rules rules) {}
}
