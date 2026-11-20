package com.example.enterpriseagent.runtime;

import com.example.enterpriseagent.memory.working.CheckpointSaver;
import com.example.enterpriseagent.memory.working.WorkingMemory;
import com.example.enterpriseagent.runtime.execution.ReportGraph;
import com.example.enterpriseagent.runtime.execution.RunTool;
import com.example.enterpriseagent.runtime.planner.ReportPlans;
import com.example.enterpriseagent.runtime.planner.RunPlanner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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

    public RunManager(RunStore store, TransactionOperations tx, CheckpointSaver saver, ObjectMapper json,
                      ReportGraph graph, RunPlanner planner, RunTool tools) {
        this.store = store;
        this.tx = tx;
        this.saver = saver;
        this.json = json;
        this.graph = graph;
        this.planner = planner;
        this.tools = tools;
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
                if (store.claimProposal(run.tenant(), run.id(), proposal.id(), worker, leaseSeconds, planner.version()) != 1) return null;
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
            outcome = "TOOL".equals(item.step().kind())
                    ? new StepOutcome("WAITING_EXTERNAL", null, null, null, "TOOL_STATUS_UNAVAILABLE", 1_200)
                    : StepOutcome.failure("STEP_EXECUTION_FAILED");
        }
        StepOutcome settlement = outcome;
        tx.executeWithoutResult(ignored -> settleStep(item, settlement));
    }

    private StepOutcome executeStep(Work work) {
        Run run = work.run();
        Step step = work.step();
        if (run.cancelled() && !"TOOL".equals(step.kind())) return StepOutcome.cancelled();
        if ("TOOL".equals(step.kind())) {
            ToolStatus status = step.toolExecutionId() != null || run.cancelled()
                    ? tools.read(work.actor(), run.id(), step.id(), step.toolExecutionId(), run.cancelled())
                    : tools.submit(work.actor(), run.id(), step.id(), work.request().source(), step.operationKey());
            return switch (status.status()) {
                case "SUCCEEDED" -> StepOutcome.succeeded(graph.normalizeRows(status.output()), status.executionId());
                case "WAITING_APPROVAL" -> new StepOutcome("WAITING_APPROVAL", null, status.executionId(),
                        status.approvalId(), null, 2_000);
                case "QUEUED", "RUNNING", "WAITING_EXTERNAL" ->
                        new StepOutcome("WAITING_EXTERNAL", null, status.executionId(), null, null, 2_000);
                case "RETRY_WAIT" -> new StepOutcome("RETRY_WAIT", null, status.executionId(), null,
                        "TOOL_RATE_LIMITED", 3_000);
                case "UNKNOWN" -> new StepOutcome("NEEDS_RECONCILIATION", null, status.executionId(), null,
                        "TOOL_RESULT_UNKNOWN", 0);
                case "CANCELLED" -> new StepOutcome("CANCELLED", null, status.executionId(), null, null, 0);
                default -> new StepOutcome("FAILED", null, status.executionId(), null, status.reason(), 0);
            };
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

    private void settleStep(Work work, StepOutcome outcome) {
        Run run = store.lock(work.run().tenant(), work.run().id());
        Step step = work.step();
        if (run.terminal() || run.activePlanVersion() != work.run().activePlanVersion()) return;
        if (steps(run).stream().noneMatch(s -> s.id().equals(step.id()) && s.leaseEpoch() == step.leaseEpoch()
                && step.leaseOwner().equals(s.leaseOwner()) && "RUNNING".equals(s.status()))) return;
        if (run.cancelled() && !"TOOL".equals(step.kind())) outcome = StepOutcome.cancelled();
        NodeSpec spec = parse(step.spec(), NodeSpec.class);
        UUID observation = "SUCCEEDED".equals(outcome.status()) ? spec.reuseArtifact() : null;
        if ("SUCCEEDED".equals(outcome.status()) && outcome.value() != null) {
            observation = writeArtifact(run, step.id(), step.nodeKey(), run.activePlanVersion(),
                    "report".equals(step.nodeKey()) ? "REPORT" : "TOOL".equals(step.kind()) ? "TOOL_RESULT" : "OBSERVATION",
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
            if ("gate".equals(step.nodeKey())) {
                gate(run, work.request(), outcome.value(), observation);
                return;
            }
            store.readyDependents(run.tenant(), run.id(), run.activePlanVersion());
        }
        summarize(run);
    }

    private void plan(Work work) {
        Run run = work.run();
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
        boolean pass = decision.path("pass").asBoolean();
        List<String> missing = texts(decision.path("missing"));
        boolean agent = run.gatePolicyVersion() != null;
        int iteration = run.activePlanVersion() - 1;
        boolean canRepair = agent && !pass && iteration < run.maxIterations() && run.plannerCalls() < 4 && !run.expired();
        String verdict = pass ? "PASS" : canRepair ? "RETRYABLE_FAIL" : "TERMINAL_FAIL";
        if (agent) {
            for (String dimension : request.dimensions()) {
                store.insertGate(new GateRow(run.tenant(), run.id(), iteration, run.activePlanVersion(), dimension,
                        missing.contains(dimension) ? verdict : "PASS", gateArtifact));
            }
            store.finishIteration(run.tenant(), run.id(), iteration, verdict);
        }
        event(run, "run.gate_evaluated", Map.of("verdict", verdict, "missing", missing));
        if (pass) {
            UUID report = steps(run).stream().filter(s -> "report".equals(s.nodeKey())).findFirst().orElseThrow().observationId();
            finish(run, "SUCCEEDED", null, report, agent ? iteration : null);
        } else if (canRepair) {
            requestPlan(run, "REPLAN", "HARD_GATE_MISSING_EVIDENCE");
            event(run, "run.replan_requested", Map.of("missing", missing));
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
                run.activePlanVersion(), kind, reason, planner.version()));
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

    private void finish(Run run, String status, String reason, UUID result, Integer passed) {
        if (run.gatePolicyVersion() != null && run.activePlanVersion() > 1 && !"SUCCEEDED".equals(status)) {
            store.finishIteration(run.tenant(), run.id(), run.activePlanVersion() - 1,
                    "CANCELLED".equals(status) ? "CANCELLED" : "TERMINAL_FAIL");
        }
        store.updateRun(new StateUpdate(run.tenant(), run.id(), status, reason, result, passed));
        store.finishTask(run.tenant(), run.taskId(), "SUCCEEDED".equals(status) ? "COMPLETED" : status);
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
        return new Work(run, actor, parse(store.input(run.tenant(), run.taskId()), Request.class), step, proposal);
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
