package com.example.enterpriseagent.decision;

import com.example.enterpriseagent.intent.IntentModel.Outcome;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 将已校验的意图映射到部署时登记的执行目标，统一检查派发所需的权限。 */
public final class DecisionService {
    public enum Kind {
        DIRECT(Set.of("tools.invoke")),
        WORKFLOW(Set.of("tools.invoke", "runs.submit")),
        PLAN_AND_EXECUTE(Set.of("tools.invoke", "runs.submit"));

        private final Set<String> scopes;

        Kind(Set<String> scopes) {
            this.scopes = scopes;
        }

        public boolean maySubmit(Set<String> granted) {
            return granted.containsAll(scopes);
        }

        public boolean mayRead(Set<String> granted) {
            return granted.contains("tools.invoke") &&
                    (this == DIRECT || granted.contains("runs.read"));
        }
    }

    public record Binding(Kind kind, int definitionVersion, String toolName, String version, String parameter) {
        public Binding {
            if (!toolName.matches("[A-Za-z0-9_-]{1,64}") || !version.matches("[A-Za-z0-9._-]{1,48}")) {
                throw new IllegalArgumentException("执行绑定需要合法的工具名称与固定版本");
            }
        }
    }

    public record RouteDecision(Kind kind, String policyVersion, String reason,
                                String toolName, String toolVersion, Map<String, String> arguments) {}

    private final Map<String, Binding> bindings;

    public DecisionService(Map<String, Binding> bindings) {
        this.bindings = Map.copyOf(bindings);
    }

    public Optional<RouteDecision> resolve(Outcome outcome) {
        var ir = outcome.ir();
        if (!"READY".equals(outcome.status()) || ir == null || ir.inputVersion() == null || !outcome.dependsOn().isEmpty()) {
            return Optional.empty();
        }
        if ("WORKFLOW".equals(outcome.route()) && ir.workflowSkill() != null) {
            return Optional.of(new RouteDecision(Kind.WORKFLOW, "conversation-workflow-v1", "PUBLISHED_SKILL_BINDING",
                    null, null, Map.of()));
        }
        Binding binding = bindings.get(ir.domain() + "." + ir.action());
        if (binding == null || ir.definitionVersion() != binding.definitionVersion()) return Optional.empty();
        if (!(ir.arguments().get(binding.parameter()) instanceof String value) || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new RouteDecision(binding.kind(), "conversation-dispatch-v1", "CONFIGURED_INTENT_BINDING",
                binding.toolName(), binding.version(),
                Map.of(binding.parameter(), value)));
    }
}
