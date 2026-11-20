package com.example.enterpriseagent.infrastructure.intent;

import com.example.enterpriseagent.decision.DecisionService.Kind;
import com.example.enterpriseagent.intent.IntentModel.*;
import com.example.enterpriseagent.intent.ValidatedIntentIR;
import com.example.enterpriseagent.skill.SkillRegistry;
import com.example.enterpriseagent.toolgateway.registry.ToolContracts;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 入库之前固定配置映射选中的 Skill 版本。续轮沿用 Task 的已选版本。 */
public final class ConfiguredWorkflowRoutes {
    private final List<ConversationRoutingSettings.Route> routes;
    private final SkillRegistry skills;
    private final ToolContracts contracts;
    private final ObjectMapper json;

    public ConfiguredWorkflowRoutes(ConversationRoutingSettings settings, SkillRegistry skills, ToolContracts contracts, ObjectMapper json) {
        this.routes = settings.bindings().stream().filter(route -> route.mode() == Kind.WORKFLOW).toList();
        this.skills = skills;
        this.contracts = contracts;
        this.json = json;
    }

    Analysis bind(Actor actor, Analysis analysis) {
        if (routes.isEmpty() && analysis.state().tasks().stream().noneMatch(task -> task.workflowSkill() != null)) return analysis;
        var outcomes = new ArrayList<>(analysis.outcomes());
        State state = analysis.state();
        var tasks = new ArrayList<>(state.tasks());
        var replies = new ArrayList<String>();
        for (int i = 0; i < outcomes.size(); i++) {
            Outcome outcome = outcomes.get(i);
            if (outcome.taskId() == null || outcome.workflowSkill() != null
                    || !List.of("READY", "NEEDS_CLARIFICATION").contains(outcome.status())) continue;
            int taskIndex = find(tasks, outcome.taskId());
            if (taskIndex < 0 || tasks.get(taskIndex).touched() != state.revision()) continue;
            Task task = tasks.get(taskIndex);
            var route = routes.stream().filter(value -> value.tenant().equals(actor.tenant())
                    && value.intent().equals(outcome.intent()) && value.definitionVersion() == task.definitionVersion()).findFirst();
            if (route.isEmpty() && task.workflowSkill() == null) continue;
            if (outcome.ir() != null && !"NORMAL".equals(outcome.ir().riskHint())) continue;
            WorkflowSkill pinned = task.workflowSkill();
            String key = pinned == null ? route.orElseThrow().skillKey() : pinned.key();
            var published = published(actor, pinned, key);
            String required = task.intent() + "@" + task.definitionVersion();
            if (published == null || !required.equals(published.dag().path("intentDefinition").asText())
                    || !"workflow.read".equals(published.dag().path("admissionPolicy").asText())) {
                outcomes.set(i, unavailable(outcome, "配置的 Skill 版本当前不可用。"));
                tasks.set(taskIndex, blocked(task));
                replies.add(outcomes.get(i).question());
                continue;
            }
            var version = new WorkflowSkill(published.id(), key, published.version(), task.definitionVersion());
            var ir = outcome.ir();
            if (ir != null) {
                if (!contracts.matches(published.dag().path("inputSchema").toString(), json.valueToTree(ir.arguments()))) {
                    outcomes.set(i, unavailable(outcome, "已校验的意图参数与 Skill 契约不匹配。"));
                    tasks.set(taskIndex, blocked(task));
                    replies.add(outcomes.get(i).question());
                    continue;
                }
                ir = new ValidatedIntentIR(ir.domain(), ir.action(), ir.definitionVersion(), ir.taskId(), ir.arguments(),
                        ir.provenance(), ir.inputVersion(), ir.riskHint(), ValidatedIntentIR.ResponseMode.PROGRESS_AND_RESULT, version, null);
            }
            tasks.set(taskIndex, new Task(task.id(), task.intent(), task.slots(), task.status(), task.touched(),
                    task.updatedAt(), task.assessment(), task.confirmationHash(), task.definitionVersion(), version));
            outcomes.set(i, new Outcome(outcome.intent(), outcome.relation(), outcome.taskId(), outcome.status(), "WORKFLOW",
                    key, outcome.slots(), outcome.missing(), outcome.invalid(), outcome.question(), outcome.reason(),
                    outcome.dependsOn(), outcome.assessment(), ir, version, outcome.slotProvenance()));
        }
        String reply = String.join(" ", java.util.stream.Stream.concat(java.util.stream.Stream.of(analysis.reply()), replies.stream())
                .filter(text -> !text.isBlank()).toList());
        return new Analysis(outcomes, new State(state.revision(), tasks, state.focus(), state.entities(), state.recent(),
                state.summary(), state.privacyRevision()), reply, analysis.inputObservation(), analysis.classifiedIntents());
    }

    private SkillRegistry.Published published(Actor actor, WorkflowSkill pinned, String key) {
        try {
            var subject = new SkillRegistry.Actor(actor.tenant(), actor.user(), actor.agent(), List.copyOf(actor.roles()));
            var found = skills.get(subject, key, pinned == null ? null : pinned.version());
            return (pinned == null || pinned.id().equals(found.id())) && List.of("PUBLISHED","RETIRED").contains(found.status()) ? found : null;
        } catch (SkillRegistry.Failure ex) {
            if (ex.status() == 404) return null;
            throw ex;
        }
    }

    private static int find(List<Task> tasks, java.util.UUID id) {
        for (int i = 0; i < tasks.size(); i++) if (id.equals(tasks.get(i).id())) return i;
        return -1;
    }

    private static Outcome unavailable(Outcome outcome, String question) {
        return new Outcome(outcome.intent(), outcome.relation(), outcome.taskId(), "UNSUPPORTED", "NONE", null,
                outcome.slots(), List.of(), List.of(), question, "CONFIGURED_SKILL_UNAVAILABLE",
                List.of(), outcome.assessment(), null);
    }

    private static Task blocked(Task task) {
        return new Task(task.id(), task.intent(), task.slots(), "NEEDS_CLARIFICATION", task.touched(),
                task.updatedAt(), task.assessment(), task.confirmationHash(), task.definitionVersion(), task.workflowSkill());
    }
}
