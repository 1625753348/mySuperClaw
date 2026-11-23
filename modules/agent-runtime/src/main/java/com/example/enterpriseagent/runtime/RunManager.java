package com.example.enterpriseagent.runtime;

import com.example.enterpriseagent.memory.working.CheckpointSaver;
import com.example.enterpriseagent.memory.working.WorkingMemory;
import com.example.enterpriseagent.runtime.execution.ReportGraph;
import com.example.enterpriseagent.runtime.execution.RunTool;
import com.example.enterpriseagent.runtime.planner.ReportPlans;
import com.example.enterpriseagent.runtime.planner.GeneralPlanner;
import com.example.enterpriseagent.runtime.planner.BusinessGate;
import com.example.enterpriseagent.runtime.planner.RunPlanner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static com.example.enterpriseagent.runtime.RunFailure.Code.*;
import static com.example.enterpriseagent.runtime.RunModel.*;
import static com.example.enterpriseagent.runtime.RunStore.*;

/** 计划、节点、检查点和事件由 Run Manager 在 PostgreSQL 短事务内一起推进。 */
public final class RunManager {
    private static final int FORMAT = 1;
    private static final int MAX_BODY = 262_144;
    private static final Set<String> LIVE = Set.of("RUNNING", "READY", "WAITING_APPROVAL", "WAITING_EXTERNAL", "RETRY_WAIT");
    private final RunStore store;
    private final TransactionOperations tx;
    private final CheckpointSaver saver;
    private final ObjectMapper json;
    private final ReportGraph graph;
    private final RunPlanner planner;
    private final RunTool tools;
    private final GeneralPlanner generalPlanner;
    private final Consumer<Run> completed;

    public RunManager(RunStore store, TransactionOperations tx, CheckpointSaver saver, ObjectMapper json,
                      ReportGraph graph, RunPlanner planner, RunTool tools, GeneralPlanner generalPlanner, Consumer<Run> completed) {
        this.store = store;
        this.tx = tx;
        this.saver = saver;
        this.json = json;
        this.graph = graph;
        this.planner = planner;
        this.tools = tools;
        this.generalPlanner = generalPlanner;
        this.completed = completed;
    }

    public View submit(Actor actor, Request request, String key) {
        key(key);
        Request input = validate(request);
        UUID id = tx.execute(ignored -> accept(actor, input, key));
        return view(actor, id);
    }

    /** 从会话入队时，将 Run 的受理和会话执行关联一起提交。 */
    public UUID enqueueInTransaction(Actor actor, Request request, String key) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("运行受理需要调用方启动事务");
        }
        key(key);
        return accept(actor, validate(request), key);
    }

    /** 会话提交的已选版本直接复制成 Plan，沿用现有节点租约、审批和检查点。 */
    public UUID enqueueFixedInTransaction(Actor actor, FixedPlan plan, String key) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("运行受理需要调用方启动事务");
        }
        key(key);
        UUID id = UUID.randomUUID();
        String digest = hash(write(plan));
        var submission = new Admission(actor.tenant(), actor.user(), actor.agent(), key, digest, write(actor.roles()),
                null, id, plan.taskId(), null, null, null, null, null, null,
                "WORKFLOW", UUID.randomUUID().toString(), null, 1, 1_800);
        if (store.reserveSubmission(submission) != 1) {
            var prior = store.receipt(actor.tenant(), actor.user(), actor.agent(), key);
            if (!digest.equals(prior.requestHash())) throw new RunFailure(CONFLICT, "工作流提交版本已变更");
            return prior.runId();
        }
        if (store.insertFixedAdmission(new FixedAdmission(actor.tenant(), id, plan.taskId(), plan.inputVersion(),
                plan.skillVersionId(), actor.user(), actor.agent(), submission.trace())) != 1) {
            throw new RunFailure(CONFLICT, "任务输入版本或工作流准入已变更");
        }
        store.insertPlan(new PlanVersion(actor.tenant(), id, 1, "WORKFLOW", null, digest, plan.skillVersionId()));
        Run run = store.lock(actor.tenant(), id);
        insertBoundNodes(run, 1, plan.nodes());
        long seq = event(run, "run.accepted", Map.of("status", "QUEUED", "mode", "WORKFLOW", "skillVersionId", plan.skillVersionId()));
        checkpoint(run, 1, seq, null);
        return id;
    }

    /** 绑定原会话 Task、输入和路由版本，在同一事务提交初始 PlanProposal。 */
    public UUID enqueueGeneralInTransaction(Actor actor, UUID task, int inputVersion, GeneralPlanner.Policy policy, String key) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("运行受理需要调用方启动事务");
        }
        if (!generalPlanner.enabled()) throw new RunFailure(CONFLICT, "通用规划模型未启用");
        key(key);
        UUID id = UUID.randomUUID();
        String snapshot = write(policy);
        String digest = hash(task + ":" + inputVersion + ":" + snapshot);
        var submission = new Admission(actor.tenant(), actor.user(), actor.agent(), key, digest, write(actor.roles()),
                null, id, task, null, null, null, null, null, null, "AGENT", UUID.randomUUID().toString(),
                GENERAL_GATE, policy.maxIterations(), 1_800);
        if (store.reserveSubmission(submission) != 1) {
            var prior = store.receipt(actor.tenant(), actor.user(), actor.agent(), key);
            if (!digest.equals(prior.requestHash())) throw new RunFailure(CONFLICT, "通用规划提交版本已变更");
            return prior.runId();
        }
        if (store.insertGeneralAdmission(new GeneralAdmission(actor.tenant(), id, task, inputVersion, actor.user(),
                actor.agent(), submission.trace(), snapshot, policy.maxIterations())) != 1) {
            throw new RunFailure(CONFLICT, "通用规划准入已变更");
        }
        Run run = store.lock(actor.tenant(), id);
        store.insertPlan(new PlanVersion(run.tenant(), id, 1, "BOOTSTRAP", null, hash("bootstrap")));
        requestPlan(run, "INITIAL", "INITIAL_PLAN");
        long seq = event(run, "run.accepted", Map.of("status", "QUEUED", "mode", "AGENT", "graph", GENERAL_GRAPH));
        checkpoint(run, 1, seq, null);
        return id;
    }

    private void insertBoundNodes(Run run, int version, List<FixedNode> nodes) {
        var ids = new LinkedHashMap<String, UUID>();
        nodes.forEach(node -> ids.put(node.id(), UUID.randomUUID()));
        for (FixedNode node : nodes) {
            UUID step = ids.get(node.id());
            String kind = node.subagent() != null ? "SUBAGENT"
                    : general(run) && node.result() ? "GATE" : node.toolName() == null ? "COMPUTE" : "TOOL";
            store.insertStep(new StepRow(run.tenant(), step, run.id(), version, node.id(), kind, write(node),
                    node.dependsOn().isEmpty() ? "READY" : "PENDING", "TOOL".equals(kind) ? step.toString() : null));
        }
        for (FixedNode node : nodes) for (String parent : node.dependsOn()) {
            store.insertDependency(new Dependency(run.tenant(), run.id(), version, ids.get(node.id()), ids.get(parent)));
        }
    }

    private UUID accept(Actor actor, Request input, String key) {
        String encoded = canonical(json.valueToTree(input)).toString();
        String hash = hash(encoded);
        UUID runId = UUID.randomUUID();
        boolean agent = "AGENT".equals(input.mode());
        var data = new Admission(actor.tenant(), actor.user(), actor.agent(), key, hash, write(actor.roles()),
                encoded, runId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), input.mode(), UUID.randomUUID().toString(),
                agent ? GATE : null, input.maxIterations(), input.deadlineSeconds());
        if (store.reserveSubmission(data) != 1) {
            Receipt old = store.receipt(actor.tenant(), actor.user(), actor.agent(), key);
            if (!hash.equals(old.requestHash())) throw new RunFailure(CONFLICT, "幂等键已用于其他任务输入");
            return old.runId();
        }
        if (input.source() != null) tools.validate(actor, input.source());
        store.insertAdmission(data);
        Run run = store.lock(actor.tenant(), runId);
        if (agent) {
            store.insertPlan(new PlanVersion(run.tenant(), runId, 1, "BOOTSTRAP", null, hash("bootstrap")));
            requestPlan(run, "INITIAL", "INITIAL_PLAN");
        } else {
            insertNodes(run, 1, "WORKFLOW", null,
                    ReportPlans.plan(input.dimensions(), input.dimensions(), Map.of(), input.source() != null));
        }
        long seq = event(run, "run.accepted", Map.of("status", "QUEUED", "mode", input.mode()));
        checkpoint(run, 1, seq, null);
        return runId;
    }

    public View view(Actor actor, UUID id) {
        Run run = owned(actor, id);
        var blockers = store.steps(run.tenant(), run.id(), run.activePlanVersion()).stream()
                .map(Step::status).filter(s -> s.startsWith("WAITING_") || "NEEDS_RECONCILIATION".equals(s))
                .distinct().sorted().toList();
        String path = "/v1/runs/" + run.id();
        return new View(run.id(), run.status(), run.lastEventSeq(), run.activePlanVersion(),
                run.gatePolicyVersion() == null ? 0 : run.activePlanVersion() - 1, blockers, run.terminalReason(),
                run.resultRef() == null ? null : path + "/result", path + "/events");
    }

    public Feed events(Actor actor, UUID id, long after) {
        Run run = owned(actor, id);
        return new Feed(run.lastEventSeq(), run.terminal(), store.events(run.tenant(), id, after, 100));
    }

    public record Feed(long lastSeq, boolean terminal, List<Event> items) {}

    public JsonNode result(Actor actor, UUID id) {
        Run run = owned(actor, id);
        if (run.resultRef() == null) throw new RunFailure(NOT_FOUND, "运行尚无可交付结果");
        if (bound(run)) {
            for (Step step : steps(run)) {
                if (!evidenceAccessible(actor, run, step)) {
                    throw new RunFailure(FORBIDDEN, "已失去工作流证据访问权限");
                }
            }
            return artifact(run, UUID.fromString(run.resultRef()));
        }
        Source source = parse(store.input(run.tenant(), run.taskId()), Request.class).source();
        if (source != null) tools.validate(actor, source);
        return artifact(run, UUID.fromString(run.resultRef()));
    }

    public View cancel(Actor actor, UUID id, String key) {
        key(key);
        tx.executeWithoutResult(ignored -> {
            owned(actor, id);
            Run run = store.lock(actor.tenant(), id);
            String old = store.command(run.tenant(), id, actor.user(), key);
            if (old != null && !"CANCEL".equals(old)) throw new RunFailure(CONFLICT, "幂等键已用于其他命令");
            if (old == null) {
                store.insertCommand(run.tenant(), id, actor.user(), key);
                startCancellation(run, "USER_CANCELLED");
            }
        });
        return view(actor, id);
    }

    public Work claim(String worker, int leaseSeconds) {
        return tx.execute(ignored -> {
            CandidateRun candidate = store.lockDueRun();
            if (candidate == null) return null;
            Run run = store.lock(candidate.tenant(), candidate.id());
            if (run.expired() && !run.cancelled()) {
                startCancellation(run, "DEADLINE_EXCEEDED");
                return null;
            }
            PlanProposal proposal = run.cancelled() ? null : store.dueProposal(run.tenant(), run.id());
            if (proposal != null) {
                if (store.claimProposal(run.tenant(), run.id(), proposal.id(), worker, leaseSeconds, plannerVersion(run)) != 1) return null;
                PlanProposal leased = new PlanProposal(proposal.id(), proposal.basePlanVersion(), proposal.kind(),
                        proposal.leaseEpoch() + 1, worker, proposal.attempts() + 1, proposal.triggerReason());
                if (run.plannerCalls() >= 4 || leased.attempts() > 2) {
                    abandonPlan(run, leased, "PLANNER_BUDGET_EXCEEDED");
                    return null;
                }
                store.countPlannerCall(run.tenant(), run.id());
                transition(run, "RUNNING", null);
                return work(run, null, leased);
            }
            Step step = store.dueStep(run.tenant(), run.id(), run.activePlanVersion(), run.cancelled());
            if (step == null || store.claimStep(run.tenant(), step.id(), worker, leaseSeconds) != 1) return null;
            if ("RUNNING".equals(step.status())) store.abandonAttempt(run.tenant(), step.id(), step.currentAttempt());
            store.startAttempt(run.tenant(), step.id(), worker);
            if (!run.cancelled() && !step.status().startsWith("WAITING_") && !"RETRY_WAIT".equals(step.status())) {
                transition(run, "RUNNING", null);
            }
            Step claimed = new Step(step.id(), step.nodeKey(), step.kind(), step.spec(), step.status(),
                    step.operationKey(), step.toolExecutionId(), step.observationId(),
                    step.currentAttempt() + 1, step.leaseEpoch() + 1, worker);
            return work(run, claimed, null);
        });
    }

    public void execute(Work item) {
        if (item.proposal() != null) {
            plan(item);
            return;
        }
        StepOutcome outcome;
        try {
            outcome = executeStep(item);
            if (outcome.value() != null && size(outcome.value().toString()) > MAX_BODY) {
                outcome = new StepOutcome("FAILED", null, outcome.toolId(), null, "ARTIFACT_TOO_LARGE", 0);
            }
        } catch (RunFailure ex) {
            outcome = StepOutcome.failure("INVALID_STEP_INPUT");
        } catch (RuntimeException ex) {
            // 传输故障期间无法判断网关是否已经预占，仍使用原 operation key 对账。
            outcome = waitsForExecution(item.step())
                    ? new StepOutcome("WAITING_EXTERNAL", null, null, null,
                        "TOOL".equals(item.step().kind()) ? "TOOL_STATUS_UNAVAILABLE" : "SUBAGENT_STATUS_UNAVAILABLE", 1_200)
                    : StepOutcome.failure("STEP_EXECUTION_FAILED");
        }
        StepOutcome settlement = outcome;
        tx.executeWithoutResult(ignored -> settleStep(item, settlement));
    }

    private StepOutcome executeStep(Work work) {
        Run run = work.run();
        Step step = work.step();
        if (run.cancelled() && !waitsForExecution(step)) return StepOutcome.cancelled();
        if (bound(run)) return executeFixed(work);
        if ("TOOL".equals(step.kind())) {
            ToolStatus status = step.toolExecutionId() != null || run.cancelled()
                    ? tools.read(work.actor(), run.id(), step.id(), step.toolExecutionId(), run.cancelled())
                    : tools.submit(work.actor(), run.id(), step.id(), work.request().source(), step.operationKey());
            return finishTool(status, false);
        }
        if (step.currentAttempt() > 3) return StepOutcome.failure("STEP_LEASE_LIMIT");
        NodeSpec spec = parse(step.spec(), NodeSpec.class);
        if (spec.reuseArtifact() != null) {
            artifact(run, spec.reuseArtifact());
            return StepOutcome.succeeded(null, null);
        }
        if ("source".equals(spec.action())) return StepOutcome.succeeded(work.request().rows(), null);

        Workspace workspace = restore(run);
        var input = json.createObjectNode();
        if ("dimension".equals(spec.action())) {
            input.put("dimension", spec.dimension()).set("source", workspace.body("source"));
        } else if ("report".equals(spec.action())) {
            input.put("goal", work.request().goal()).set("source", workspace.body("source"));
            var observations = input.putObject("observations");
            for (String key : spec.dependencies()) {
                if (!"source".equals(key)) observations.putObject(key).put("ref", workspace.ref(key).toString())
                        .set("value", workspace.body(key));
            }
        } else {
            input.set("report", workspace.body("report"));
            input.set("required", json.valueToTree(work.request().dimensions()));
            var evidence = input.putObject("evidence");
            spec.dependencies().stream().filter(key -> !"report".equals(key))
                    .forEach(key -> evidence.put(key, workspace.ref(key).toString()));
        }
        return StepOutcome.succeeded(graph.run(spec.action(), input), null);
    }

    private StepOutcome finishTool(ToolStatus status, boolean fixed) {
        return switch (status.status()) {
            case "SUCCEEDED" -> StepOutcome.succeeded(fixed ? status.output() : graph.normalizeRows(status.output()), status.executionId());
            case "WAITING_APPROVAL" -> new StepOutcome("WAITING_APPROVAL", null, status.executionId(), status.approvalId(), null, 2_000);
            case "QUEUED", "RUNNING", "WAITING_EXTERNAL" ->
                    new StepOutcome("WAITING_EXTERNAL", null, status.executionId(), null, null, 2_000);
            case "RETRY_WAIT" -> new StepOutcome("RETRY_WAIT", null, status.executionId(), null, "TOOL_RATE_LIMITED", 3_000);
            case "UNKNOWN" -> new StepOutcome("NEEDS_RECONCILIATION", null, status.executionId(), null, "TOOL_RESULT_UNKNOWN", 0);
            case "CANCELLED" -> new StepOutcome("CANCELLED", null, status.executionId(), null, null, 0);
            default -> new StepOutcome("FAILED", null, status.executionId(), null, status.reason(), 0);
        };
    }

    private StepOutcome executeFixed(Work work) {
        Run run = work.run();
        Step step = work.step();
        FixedNode spec = parse(step.spec(), FixedNode.class);
        if ("SUBAGENT".equals(step.kind())) {
            UUID child = store.childRun(run.tenant(), run.id(), step.id());
            if (child != null || run.cancelled()) return advanceChild(work, child);
            if (!ancestorEvidenceAvailable(work, spec)) return StepOutcome.failure("PARENT_EVIDENCE_UNAVAILABLE");
            UUID admitted = tx.execute(ignored -> admitChild(work, spec, resolveBoundInput(work, spec)));
            return advanceChild(work, admitted);
        }
        if ("TOOL".equals(step.kind()) && (step.toolExecutionId() != null || run.cancelled())) {
            return finishTool(tools.read(work.actor(), run.id(), step.id(), step.toolExecutionId(), run.cancelled()), true);
        }
        if (!"TOOL".equals(step.kind()) && step.currentAttempt() > 3) return StepOutcome.failure("STEP_LEASE_LIMIT");
        if (general(run) && !spec.dependsOn().isEmpty() && !ancestorEvidenceAvailable(work, spec)) {
            return StepOutcome.failure("PARENT_EVIDENCE_UNAVAILABLE");
        }
        var input = resolveBoundInput(work, spec);
        if ("TOOL".equals(step.kind())) {
            return finishTool(tools.submitPinned(work.actor(), run.id(), step.id(),
                    new Source(spec.toolName(), spec.toolVersion(), input), step.operationKey(), spec.toolVersionId()), true);
        }
        if ("GATE".equals(step.kind()) && general(run)) {
            JsonNode result = input.path("result");
            var failures = BusinessGate.evaluate(generalPolicy(run), result, work.fixedInput().path("arguments"));
            return StepOutcome.succeeded(json.valueToTree(Map.of(
                    "pass", failures.isEmpty(), "missing", List.copyOf(failures.keySet()), "failures", failures)), null);
        }
        try {
            return StepOutcome.succeeded(com.example.enterpriseagent.runtime.execution.FixedHandlers.execute(json, spec.handler(),
                    input, work.fixedInput().path("workflowContext").path("repository").asText()), null);
        } catch (RunFailure failure) {
            return StepOutcome.failure(failure.getMessage());
        }
    }

    private ObjectNode resolveBoundInput(Work work, FixedNode spec) {
        Workspace workspace = restore(work.run());
        var input = json.createObjectNode();
        spec.inputs().forEach((name, ref) -> {
            String[] path = ref.split("\\.");
            JsonNode value = switch (path[0]) {
                case "input" -> work.fixedInput().path("arguments").path(path[1]);
                case "context" -> work.fixedInput().path("workflowContext").path(path[1]);
                case "steps" -> path.length == 4 ? workspace.body(path[1]).path(path[3]) : workspace.body(path[1]);
                default -> throw invalid("无法解析工作流引用");
            };
            if (value.isMissingNode() || value.isNull()) throw invalid("工作流输入缺失");
            input.set(name, value);
        });
        return input;
    }

    /** 子 Run、首个计划和父子关系在持有父 Run 锁时一次提交，节点重领沿用原链接。 */
    private UUID admitChild(Work work, FixedNode spec, ObjectNode arguments) {
        Run parent = store.lock(work.run().tenant(), work.run().id());
        Step parentStep = work.step();
        UUID prior = store.childRun(parent.tenant(), parent.id(), parentStep.id());
        if (prior != null) return prior;
        if (parent.cancelled() || parent.expired() || parent.terminal() || !holdsLease(work, parent)) return null;
        String request = write(Map.of("arguments", arguments));
        if (size(request) > MAX_BODY) throw invalid("子任务输入过大");
        UUID child = UUID.randomUUID();
        int seconds = (int) Math.max(1, Duration.between(Instant.now(), parent.deadlineAt()).toSeconds());
        var actor = work.actor();
        var admission = new Admission(parent.tenant(), actor.user(), actor.agent(), "internal-subagent:" + child,
                hash(request), write(actor.roles()), request, child, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "WORKFLOW", parent.traceId(), null, 1, seconds, true);
        if (store.reserveSubmission(admission) != 1) throw new IllegalStateException("子任务受理冲突");
        store.insertAdmission(admission);
        Run run = store.lock(parent.tenant(), child);
        var inputRefs = new LinkedHashMap<String, String>();
        arguments.fieldNames().forEachRemaining(key -> inputRefs.put(key, "input." + key));
        var node = new FixedNode("specialist", null, spec.toolName(), spec.toolVersion(), spec.toolVersionId(), List.of(), inputRefs, true);
        store.insertPlan(new PlanVersion(run.tenant(), child, 1, "WORKFLOW", null, hash(write(node))));
        insertBoundNodes(run, 1, List.of(node));
        store.insertChildRun(parent.tenant(), parent.id(), parentStep.id(), child, spec.subagent());
        long seq = event(run, "run.accepted", Map.of("mode", "SUBAGENT", "parentRunId", parent.id(), "parentStepId", parentStep.id()));
        checkpoint(run, 1, seq, null);
        event(parent, "run.child_started", Map.of("node", parentStep.nodeKey(), "childRunId", child, "subagent", spec.subagent()));
        return child;
    }

    private StepOutcome advanceChild(Work work, UUID id) {
        if (id == null) {
            Run parent = store.lock(work.run().tenant(), work.run().id());
            return parent.cancelled() || parent.expired() ? StepOutcome.cancelled() : StepOutcome.failure("SUBAGENT_NOT_ADMITTED");
        }
        var actor = work.actor();
        Run child = owned(actor, id);
        boolean cancelled = work.run().cancelled();
        if (cancelled && !child.terminal()) {
            tx.executeWithoutResult(ignored -> startCancellation(store.lock(child.tenant(), id), "PARENT_CANCELLED"));
        }
        if (child.terminal()) {
            if (cancelled) return StepOutcome.cancelled();
            if ("SUCCEEDED".equals(child.status())) return StepOutcome.succeeded(result(actor, id), null);
            return StepOutcome.failure("SUBAGENT_" + child.status());
        }
        return new StepOutcome("WAITING_EXTERNAL", null, null, null,
                "NEEDS_RECONCILIATION".equals(child.status()) ? "SUBAGENT_NEEDS_RECONCILIATION" : null, 650);
    }

    private boolean evidenceAccessible(Actor actor, Run run, Step step) {
        if (step.toolExecutionId() != null) {
            return "SUCCEEDED".equals(tools.read(actor, run.id(), step.id(), step.toolExecutionId(), false).status());
        }
        if (!"SUBAGENT".equals(step.kind()) || !"SUCCEEDED".equals(step.status())) return true;
        UUID child = store.childRun(run.tenant(), run.id(), step.id());
        if (child == null) return false;
        try { return result(actor, child) != null; }
        catch (RunFailure denied) { return false; }
    }

    private static boolean waitsForExecution(Step step) {
        return "TOOL".equals(step.kind()) || "SUBAGENT".equals(step.kind());
    }

    private boolean ancestorEvidenceAvailable(Work work, FixedNode spec) {
        // SOP 的计算节点也继承来源权限。复核整条依赖链，防止撤权后继续交付派生产物。
        Run run = work.run();
        var byKey = new LinkedHashMap<String, Step>();
        steps(run).forEach(step -> byKey.put(step.nodeKey(), step));
        var pending = new ArrayDeque<>(spec.dependsOn());
        var visited = new HashSet<String>();
        while (!pending.isEmpty()) {
            String key = pending.removeFirst();
            if (!visited.add(key)) continue;
            Step parent = byKey.get(key);
            if (parent == null || !"SUCCEEDED".equals(parent.status())) return false;
            if (!evidenceAccessible(work.actor(), run, parent)) return false;
            pending.addAll(parse(parent.spec(), FixedNode.class).dependsOn());
        }
        return true;
    }

    private void settleStep(Work work, StepOutcome outcome) {
        Run run = store.lock(work.run().tenant(), work.run().id());
        Step step = work.step();
        if (run.terminal() || !holdsLease(work, run)) return;
        if (run.cancelled() && !waitsForExecution(step)) outcome = StepOutcome.cancelled();
        boolean fixed = bound(run);
        FixedNode fixedNode = fixed ? parse(step.spec(), FixedNode.class) : null;
        UUID observation = "SUCCEEDED".equals(outcome.status()) && !fixed ? parse(step.spec(), NodeSpec.class).reuseArtifact() : null;
        if ("SUCCEEDED".equals(outcome.status()) && outcome.value() != null) {
            observation = writeArtifact(run, step.id(), step.nodeKey(), run.activePlanVersion(),
                    (fixed ? fixedNode.result() && !general(run) : "report".equals(step.nodeKey())) ? "REPORT"
                            : "TOOL".equals(step.kind()) ? "TOOL_RESULT" : "OBSERVATION",
                    outcome.value());
        }
        if (store.closeStep(new StepChange(run.tenant(), step.id(), step.leaseOwner(), step.leaseEpoch(),
                outcome.status(), outcome.toolId(), observation, outcome.delay())) != 1) {
            throw new IllegalStateException("节点租约已变化");
        }
        String attempt = switch (outcome.status()) {
            case "WAITING_APPROVAL", "WAITING_EXTERNAL", "RETRY_WAIT" -> "SUSPENDED";
            case "FAILED", "NEEDS_RECONCILIATION" -> "FAILED";
            default -> "DONE";
        };
        store.finishAttempt(run.tenant(), step.id(), step.currentAttempt(), attempt, outcome.reason());
        if (!outcome.status().equals(step.status()) || observation != null) {
            var payload = json.createObjectNode().put("node", step.nodeKey()).put("status", outcome.status());
            if (outcome.toolId() != null) payload.put("executionId", outcome.toolId().toString());
            if (outcome.approvalId() != null) payload.put("approvalId", outcome.approvalId().toString());
            if (outcome.reason() != null) payload.put("reason", outcome.reason());
            long seq = event(run, "run.step_changed", payload);
            checkpoint(run, run.activePlanVersion(), seq, step.id());
        }
        if ("SUCCEEDED".equals(outcome.status()) && !run.cancelled()) {
            if (general(run) && "GATE".equals(step.kind())) {
                Step result = steps(run).stream().filter(candidate -> fixedNode.dependsOn().contains(candidate.nodeKey()))
                        .findFirst().orElseThrow();
                settleGate(run, generalPolicy(run).checkKeys(), outcome.value(), observation, result.observationId());
                return;
            }
            if (fixed && fixedNode.result()) {
                if (observation == null) finish(run, "FAILED", "WORKFLOW_RESULT_MISSING", null, null);
                else finish(run, "SUCCEEDED", null, observation, null);
                return;
            }
            if (!fixed && "gate".equals(step.nodeKey())) {
                gate(run, work.request(), outcome.value(), observation);
                return;
            }
            store.readyDependents(run.tenant(), run.id(), run.activePlanVersion());
        }
        summarize(run);
    }

    private void plan(Work work) {
        Run run = work.run();
        if (general(run)) {
            planGeneral(work);
            return;
        }
        var references = new LinkedHashMap<String, UUID>();
        var completed = new ArrayList<String>();
        store.latestObservations(run.tenant(), run.id()).forEach(artifact -> {
            references.put(artifact.artifactKey(), artifact.id());
            if (work.request().dimensions().contains(artifact.artifactKey())) completed.add(artifact.artifactKey());
        });
        List<String> missing = work.request().dimensions().stream().filter(key -> !completed.contains(key)).toList();
        var context = new PlannerContext(work.request().goal(), work.request().dimensions(), completed, missing,
                run.activePlanVersion(), run.maxIterations() - run.activePlanVersion(),
                4 - run.plannerCalls() - 1, references);
        Map<String, NodeSpec> nodes;
        try {
            List<String> picked = planner.chooseDimensions(context);
            nodes = ReportPlans.plan(picked, context.dimensions(), references, work.request().source() != null);
        } catch (RuntimeException ex) {
            tx.executeWithoutResult(ignored -> {
                Run locked = store.lock(run.tenant(), run.id());
                abandonPlan(locked, work.proposal(), "PLANNER_REJECTED");
            });
            return;
        }
        tx.executeWithoutResult(ignored -> {
            Run locked = store.lock(run.tenant(), run.id());
            PlanProposal proposal = work.proposal();
            if (locked.terminal() || locked.cancelled() || locked.activePlanVersion() != proposal.basePlanVersion()) return;
            int version = locked.activePlanVersion() + 1;
            JsonNode contextJson = json.valueToTree(context);
            UUID contextId = writeArtifact(locked, null, "planner-input-" + proposal.id(), 1, "PLAN_CONTEXT", contextJson);
            UUID draftId = writeArtifact(locked, null, "planner-draft-" + proposal.id(), 1, "PLAN_CONTEXT", json.valueToTree(nodes));
            if (store.finishProposal(new ProposalChange(run.tenant(), run.id(), proposal.id(), proposal.leaseOwner(),
                    proposal.leaseEpoch(), "PUBLISHED", hash(contextJson.toString()), contextId, draftId, "{\"valid\":true}")) != 1) {
                throw new IllegalStateException("规划租约已变化");
            }
            insertNodes(locked, version, "INITIAL".equals(proposal.kind()) ? "PLANNER" : "REPLAN", proposal.id(), nodes);
            store.activatePlan(run.tenant(), run.id(), version);
            store.insertIteration(new IterationRow(run.tenant(), run.id(), version - 1, version, hash(contextJson.toString()),
                    write(Map.of("context", contextId, "observations", references, "missing", missing))));
            long seq = event(locked, "run.plan_changed", Map.of("planVersion", version, "iteration", version - 1,
                    "reused", nodes.entrySet().stream().filter(e -> e.getValue().reuseArtifact() != null).map(Map.Entry::getKey).toList()));
            checkpoint(locked, version, seq, null);
        });
    }

    private void gate(Run run, Request request, JsonNode decision, UUID gateArtifact) {
        UUID result = steps(run).stream().filter(s -> "report".equals(s.nodeKey())).findFirst().orElseThrow().observationId();
        settleGate(run, request.dimensions(), decision, gateArtifact, result);
    }

    private void settleGate(Run run, List<String> required, JsonNode decision, UUID gateArtifact, UUID result) {
        boolean pass = decision.path("pass").asBoolean();
        List<String> missing = texts(decision.path("missing"));
        boolean agent = run.gatePolicyVersion() != null;
        int iteration = run.activePlanVersion() - 1;
        boolean canRepair = agent && !pass && iteration < run.maxIterations() && run.plannerCalls() < 4 && !run.expired();
        String verdict = pass ? "PASS" : canRepair ? "RETRYABLE_FAIL" : "TERMINAL_FAIL";
        if (agent) {
            for (String dimension : required) {
                store.insertGate(new GateRow(run.tenant(), run.id(), iteration, run.activePlanVersion(), dimension,
                        missing.contains(dimension) ? verdict : "PASS", gateArtifact));
            }
            store.finishIteration(run.tenant(), run.id(), iteration, verdict);
        }
        var summary = json.createObjectNode().put("verdict", verdict);
        summary.set("missing", json.valueToTree(missing));
        if (decision.has("failures")) summary.set("failures", decision.get("failures"));
        event(run, "run.gate_evaluated", summary);
        if (pass) {
            finish(run, "SUCCEEDED", null, result, agent ? iteration : null);
        } else if (canRepair) {
            requestPlan(run, "REPLAN", missing.stream().anyMatch(key -> key.startsWith("business."))
                    ? "HARD_GATE_BUSINESS_FAILED" : "HARD_GATE_MISSING_EVIDENCE");
            event(run, "run.replan_requested", summary);
        } else {
            finish(run, "FAILED", "HARD_GATE_BUDGET_EXHAUSTED", null, null);
        }
    }

    private void abandonPlan(Run run, PlanProposal proposal, String reason) {
        if (run.terminal() || run.cancelled()) return;
        if (store.finishProposal(new ProposalChange(run.tenant(), run.id(), proposal.id(), proposal.leaseOwner(),
                proposal.leaseEpoch(), "REJECTED", null, null, null, write(Map.of("reason", reason)))) == 1) {
            event(run, "run.plan_rejected", Map.of("reason", reason));
            finish(run, "FAILED", reason, null, null);
        }
    }

    private void requestPlan(Run run, String kind, String reason) {
        store.insertProposal(new ProposalRow(run.tenant(), run.id(), UUID.randomUUID(),
                run.activePlanVersion(), kind, reason, plannerVersion(run)));
    }

    private String plannerVersion(Run run) { return general(run) ? "langchain4j-general-v1" : planner.version(); }

    private void planGeneral(Work work) {
        Run run = work.run();
        PlanProposal proposal = work.proposal();
        GeneralPlanner.Published proposed;
        try {
            var failures = new LinkedHashMap<String, String>();
            store.generalGateFailures(run.tenant(), run.id(), run.activePlanVersion())
                    .forEach(check -> failures.put(check.key(), check.reason()));
            proposed = generalPlanner.propose(work.actor(), generalPolicy(run), work.fixedInput(),
                    failures, run.activePlanVersion());
        } catch (RuntimeException ex) {
            String reason = ex instanceof GeneralPlanner.Rejected ? ex.getMessage() : "PLANNER_MODEL_ERROR";
            tx.executeWithoutResult(ignored -> abandonPlan(store.lock(run.tenant(), run.id()), proposal, reason));
            return;
        }
        tx.executeWithoutResult(ignored -> {
            Run locked = store.lock(run.tenant(), run.id());
            if (locked.terminal() || locked.cancelled() || locked.activePlanVersion() != proposal.basePlanVersion()) return;
            if (locked.expired()) {
                startCancellation(locked, "DEADLINE_EXCEEDED");
                return;
            }
            int version = locked.activePlanVersion() + 1;
            // 规划模型输入中的实参由原始 IR 持有，审查视图只保存字段名和候选契约。
            ObjectNode manifest = json.valueToTree(proposed.context());
            manifest.remove("arguments");
            String digest = hash(write(proposed.context()));
            UUID context = writeArtifact(locked, null, "planner-input-" + proposal.id(), 1, "PLAN_CONTEXT", manifest);
            UUID draft = writeArtifact(locked, null, "planner-draft-" + proposal.id(), 1,
                    "PLAN_CONTEXT", json.valueToTree(proposed.draft()));
            if (store.finishProposal(new ProposalChange(run.tenant(), run.id(), proposal.id(), proposal.leaseOwner(),
                    proposal.leaseEpoch(), "PUBLISHED", digest, context, draft, "{\"valid\":true}")) != 1) return;
            String origin = "INITIAL".equals(proposal.kind()) ? "PLANNER" : "REPLAN";
            store.insertPlan(new PlanVersion(run.tenant(), run.id(), version, origin, proposal.id(), hash(write(proposed.nodes()))));
            insertBoundNodes(locked, version, proposed.nodes());
            store.activatePlan(run.tenant(), run.id(), version);
            store.insertIteration(new IterationRow(run.tenant(), run.id(), version - 1, version, digest,
                    write(Map.of("context", context, "missing", proposed.context().missing()))));
            long seq = event(locked, "run.plan_changed", Map.of("planVersion", version, "iteration", version - 1));
            checkpoint(locked, version, seq, null);
        });
    }

    private GeneralPlanner.Policy generalPolicy(Run run) {
        return parse(store.generalPolicy(run.tenant(), run.id()), GeneralPlanner.Policy.class);
    }

    private void insertNodes(Run run, int version, String origin, UUID proposal, Map<String, NodeSpec> nodes) {
        store.insertPlan(new PlanVersion(run.tenant(), run.id(), version, origin, proposal, hash(write(nodes))));
        var ids = new LinkedHashMap<String, UUID>();
        nodes.forEach((key, spec) -> {
            UUID id = UUID.randomUUID();
            ids.put(key, id);
            String kind = "tool".equals(spec.action()) ? "TOOL" : "gate".equals(spec.action()) ? "GATE" : "COMPUTE";
            store.insertStep(new StepRow(run.tenant(), id, run.id(), version, key, kind, write(spec),
                    spec.dependencies().isEmpty() ? "READY" : "PENDING", "TOOL".equals(kind) ? id.toString() : null));
        });
        nodes.forEach((key, spec) -> spec.dependencies().forEach(parent ->
                store.insertDependency(new Dependency(run.tenant(), run.id(), version, ids.get(key), ids.get(parent)))));
    }

    private void startCancellation(Run run, String reason) {
        if (run.terminal() || run.cancelled() || "NEEDS_RECONCILIATION".equals(run.status())) return;
        store.markCancellation(run.tenant(), run.id(), reason);
        store.cancelUnstarted(run.tenant(), run.id(), run.activePlanVersion());
        store.cancelProposals(run.tenant(), run.id());
        event(run, "run.cancel_requested", Map.of("reason", reason));
        summarize(store.lock(run.tenant(), run.id()));
    }

    private void summarize(Run run) {
        List<String> states = steps(run).stream().map(Step::status).toList();
        if (states.contains("NEEDS_RECONCILIATION")) {
            transition(run, "NEEDS_RECONCILIATION", "TOOL_RESULT_UNKNOWN");
            return;
        }
        if (run.cancelled()) {
            if (states.stream().noneMatch(LIVE::contains)) {
                String reason = run.terminalReason();
                finish(run, "DEADLINE_EXCEEDED".equals(reason) ? "FAILED" : "CANCELLED", reason, null, null);
            }
            return;
        }
        if (states.contains("FAILED")) {
            store.cancelUnstarted(run.tenant(), run.id(), run.activePlanVersion());
            if (steps(run).stream().noneMatch(s -> LIVE.contains(s.status()))) finish(run, "FAILED", "STEP_FAILED", null, null);
            return;
        }
        String next = states.contains("RUNNING") || states.contains("READY") ? "RUNNING"
                : states.contains("WAITING_APPROVAL") ? "WAITING_APPROVAL"
                : states.contains("WAITING_EXTERNAL") ? "WAITING_EXTERNAL"
                : states.contains("RETRY_WAIT") ? "WAITING_RETRY" : "RUNNING";
        transition(run, next, null);
    }

    /** 人工核对后关闭不明结果。本操作保留 Gateway 记录与幂等键。 */
    public void closeUncertainInTransaction(String tenant, UUID id) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("人工对账需要调用方启动事务");
        }
        Run run = store.lock(tenant, id);
        if (run == null) throw new RunFailure(NOT_FOUND, "运行不存在");
        if (!"NEEDS_RECONCILIATION".equals(run.status()) || steps(run).stream().anyMatch(step ->
                step.leaseOwner() != null || LIVE.contains(step.status()) && !"READY".equals(step.status()))) {
            throw new RunFailure(CONFLICT, "运行当前不能关闭对账");
        }
        if (store.failUncertainSteps(tenant, id, run.activePlanVersion()) == 0) {
            throw new RunFailure(CONFLICT, "运行没有待对账节点");
        }
        store.cancelUnstarted(tenant, id, run.activePlanVersion());
        long seq = event(run, "run.reconciled", Map.of("disposition", "CLOSED_AS_FAILED"));
        checkpoint(run, run.activePlanVersion(), seq, null);
        finish(run, "FAILED", "RECONCILED_CLOSED", null, null);
    }

    private void finish(Run run, String status, String reason, UUID result, Integer passed) {
        if (run.gatePolicyVersion() != null && run.activePlanVersion() > 1 && !"SUCCEEDED".equals(status)) {
            store.finishIteration(run.tenant(), run.id(), run.activePlanVersion() - 1,
                    "CANCELLED".equals(status) ? "CANCELLED" : "TERMINAL_FAIL");
        }
        store.updateRun(new StateUpdate(run.tenant(), run.id(), status, reason, result, passed));
        store.finishTask(run.tenant(), run.taskId(), "SUCCEEDED".equals(status) ? "COMPLETED" : status);
        if ("SUCCEEDED".equals(status) && !CHILD_GRAPH.equals(run.graphVersion())) completed.accept(run);
        var payload = json.createObjectNode().put("status", status);
        if (reason != null) payload.put("reason", reason);
        event(run, "run.finished", payload);
    }

    private void transition(Run run, String state, String reason) {
        if (!state.equals(run.status())) {
            store.updateRun(new StateUpdate(run.tenant(), run.id(), state, reason, null, null));
            event(run, "run.state_changed", Map.of("status", state));
        }
    }

    private void checkpoint(Run run, int version, long seq, UUID step) {
        var bindings = store.steps(run.tenant(), run.id(), version).stream().filter(s -> s.observationId() != null)
                .map(s -> new WorkingMemory.Binding(s.nodeKey(), s.observationId())).toList();
        saver.append(new WorkingMemory.Snapshot(run.tenant(), run.id(), seq, version, step, run.id().toString(), FORMAT,
                write(Map.of("planVersion", version, "eventSeq", seq)), bindings));
    }

    private Workspace restore(Run run) {
        var snapshot = saver.loadLatest(new WorkingMemory.Key(run.tenant(), run.id(), run.id().toString(), FORMAT))
                .orElseThrow();
        if (snapshot.planVersion() != run.activePlanVersion()) throw new IllegalStateException("检查点版本已变化");
        var references = new LinkedHashMap<String, UUID>();
        snapshot.bindings().forEach(item -> references.put(item.bindingKey(), item.artifactId()));
        return new Workspace(run, references);
    }

    private record StepOutcome(String status, JsonNode value, UUID toolId, UUID approvalId, String reason, int delay) {
        static StepOutcome succeeded(JsonNode value, UUID tool) { return new StepOutcome("SUCCEEDED", value, tool, null, null, 0); }
        static StepOutcome failure(String reason) { return new StepOutcome("FAILED", null, null, null, reason, 0); }
        static StepOutcome cancelled() { return new StepOutcome("CANCELLED", null, null, null, null, 0); }
    }

    private final class Workspace {
        final Run run;
        final Map<String, UUID> references;
        Workspace(Run run, Map<String, UUID> references) { this.run = run; this.references = references; }
        UUID ref(String key) {
            UUID value = references.get(key);
            if (value == null) throw new IllegalStateException("缺少依赖的检查点引用");
            return value;
        }
        JsonNode body(String key) { return artifact(run, ref(key)); }
    }

    private Work work(Run run, Step step, PlanProposal proposal) {
        Receipt receipt = store.submission(run.tenant(), run.id());
        var actor = new Actor(run.tenant(), run.userId(), run.agentId(), texts(parse(receipt.roles())));
        if (bound(run)) return new Work(run, actor, null, parse(store.fixedInput(run.tenant(), run.id())), step, proposal);
        return new Work(run, actor, parse(store.input(run.tenant(), run.taskId()), Request.class), null, step, proposal);
    }

    private static boolean general(Run run) { return GENERAL_GRAPH.equals(run.graphVersion()); }
    private static boolean bound(Run run) { return general(run) || FIXED_GRAPH.equals(run.graphVersion()) || CHILD_GRAPH.equals(run.graphVersion()); }

    private boolean holdsLease(Work work, Run run) {
        Step step = work.step();
        return run.activePlanVersion() == work.run().activePlanVersion()
                && steps(run).stream().anyMatch(current -> current.id().equals(step.id())
                && current.leaseEpoch() == step.leaseEpoch() && step.leaseOwner().equals(current.leaseOwner())
                && "RUNNING".equals(current.status()));
    }

    private Run owned(Actor actor, UUID id) {
        Run run = store.owned(actor.tenant(), actor.user(), actor.agent(), id);
        if (run == null) throw new RunFailure(NOT_FOUND, "运行不存在");
        return run;
    }

    private List<Step> steps(Run run) { return store.steps(run.tenant(), run.id(), run.activePlanVersion()); }

    private long event(Run run, String type, Object payload) {
        long seq = store.nextEvent(run.tenant(), run.id());
        store.appendEvent(new EventRow(run.tenant(), run.id(), seq, UUID.randomUUID(), type, run.traceId(), write(payload)));
        return seq;
    }

    private UUID writeArtifact(Run run, UUID step, String key, int version, String kind, JsonNode body) {
        UUID id = UUID.randomUUID();
        String value = body.toString();
        int bytes = size(value);
        if (bytes > MAX_BODY) throw new IllegalStateException("运行产物超出大小预算");
        store.insertArtifact(new ArtifactRow(run.tenant(), run.id(), id, step, key, version, kind, hash(value), bytes, value));
        return id;
    }

    private JsonNode artifact(Run run, UUID id) {
        Artifact item = store.artifact(run.tenant(), run.id(), id);
        if (item == null) throw new IllegalStateException("运行产物不存在");
        return parse(item.body());
    }

    private Request validate(Request input) {
        if (input.mode() == null || !Set.of("WORKFLOW", "AGENT").contains(input.mode())
                || input.goal() == null || input.goal().isBlank() || input.goal().length() > 400) throw invalid("模式或目标无效");
        List<String> dimensions = input.dimensions();
        if (dimensions == null || dimensions.isEmpty() || dimensions.size() > 3 || dimensions.stream().anyMatch(java.util.Objects::isNull)
                || !ReportGraph.DIMENSIONS.containsAll(dimensions) || new HashSet<>(dimensions).size() != dimensions.size()) {
            throw invalid("请选择互不重复的 region、product 或 customer");
        }
        boolean inline = input.rows() != null && !input.rows().isNull();
        if (inline == (input.source() != null)) throw invalid("rows 和 source 必须二选一");
        Source source = input.source();
        if (source != null && (source.name() == null || !source.name().matches("[A-Za-z0-9_-]{1,64}")
                || source.version() == null || !source.version().matches("[A-Za-z0-9._-]{1,48}")
                || source.arguments() == null || !source.arguments().isObject()
                || source.arguments().toString().getBytes(StandardCharsets.UTF_8).length > 32_768)) {
            throw invalid("工具来源需要固定版本和受限 JSON 参数");
        }
        int iterations = input.maxIterations() == null ? ("AGENT".equals(input.mode()) ? 2 : 1) : input.maxIterations();
        int deadline = input.deadlineSeconds() == null ? 300 : input.deadlineSeconds();
        if (iterations < 1 || iterations > 3 || ("WORKFLOW".equals(input.mode()) && iterations != 1)
                || deadline < 5 || deadline > 3600) throw invalid("轮次预算或截止时间无效");
        JsonNode rows = inline ? graph.normalizeRows(input.rows()) : null;
        if (rows != null && rows.toString().getBytes(StandardCharsets.UTF_8).length > 131_072) throw invalid("销售数据超出 128 KiB");
        return new Request(input.mode(), input.goal().strip(), List.copyOf(dimensions), rows, source, iterations, deadline);
    }

    private JsonNode canonical(JsonNode value) {
        if (value == null || value.isNull()) return NullNode.instance;
        if (value.isObject()) {
            var out = json.createObjectNode();
            var keys = new ArrayList<String>();
            value.fieldNames().forEachRemaining(keys::add);
            keys.stream().sorted().forEach(key -> out.set(key, canonical(value.get(key))));
            return out;
        }
        if (value.isArray()) {
            var out = json.createArrayNode();
            value.forEach(item -> out.add(canonical(item)));
            return out;
        }
        return value;
    }

    private JsonNode parse(String value) { return parse(value, JsonNode.class); }
    private <T> T parse(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("运行时 JSON 无法解析"); }
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("运行时 JSON 无法保存"); }
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static List<String> texts(JsonNode array) {
        var result = new ArrayList<String>();
        array.forEach(item -> result.add(item.asText()));
        return result;
    }
    private static void key(String key) {
        if (key == null || key.isBlank() || key.length() > 128) throw invalid("需要有效的 Idempotency-Key");
    }
    private static int size(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
    private static RunFailure invalid(String message) { return new RunFailure(INVALID, message); }
}
