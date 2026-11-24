package com.example.enterpriseagent.decision;

import com.example.enterpriseagent.intent.IntentModel.Outcome;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
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

    public record Binding(Kind kind, int definitionVersion, String toolName, String version,
                          Map<String, String> parameters, String predecessor) {
        public Binding(Kind kind, int definitionVersion, String toolName, String version, String parameter) {
            this(kind, definitionVersion, toolName, version, Map.of(parameter, parameter), null);
        }

        public Binding {
            if (kind == null || kind == Kind.WORKFLOW || definitionVersion < 1 || toolName == null
                    || !toolName.matches("[A-Za-z0-9_-]{1,64}") || version == null || !version.matches("[A-Za-z0-9._-]{1,48}")) {
                throw new IllegalArgumentException("执行绑定需要合法的工具名称与固定版本");
            }
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
            if (parameters.entrySet().stream().anyMatch(e -> !e.getKey().matches("[A-Za-z][A-Za-z0-9_]{0,63}")
                    || !e.getValue().matches("(?:slot|result)\\.[A-Za-z][A-Za-z0-9_]{0,63}|[A-Za-z][A-Za-z0-9_]{0,63}"))) {
                throw new IllegalArgumentException("参数只允许绑定已声明的槽位或前置结果字段");
            }
            if (predecessor != null && (kind != Kind.DIRECT || !predecessor.matches("[A-Z][A-Z0-9_]*\\.[A-Z][A-Z0-9_]*"))
                    || predecessor == null && parameters.values().stream().anyMatch(value -> value.startsWith("result."))) {
                throw new IllegalArgumentException("前置结果绑定需要指定 DIRECT 的前置意图");
            }
        }
    }

    public record RouteDecision(Kind kind, String policyVersion, String reason,
                                String toolName, String toolVersion, Map<String, Object> arguments) {}

    public record Dependent(Binding binding, Integer predecessorOrdinal, Map<String, Object> slots, String blocked) {}

    private final Map<String, Binding> bindings;
    private final Map<String, Binding> workflowFallbacks;

    public DecisionService(Map<String, Binding> bindings) {
        this(bindings, Map.of());
    }

    public DecisionService(Map<String, Binding> bindings, Map<String, Binding> workflowFallbacks) {
        this.bindings = Map.copyOf(bindings);
        this.workflowFallbacks = Map.copyOf(workflowFallbacks);
    }

    public Optional<RouteDecision> resolve(Outcome outcome) {
        return resolve(null, outcome);
    }

    public Optional<RouteDecision> resolve(String tenant, Outcome outcome) {
        var ir = outcome.ir();
        if (!"READY".equals(outcome.status()) || ir == null || ir.inputVersion() == null || !outcome.dependsOn().isEmpty()) {
            return Optional.empty();
        }
        if ("WORKFLOW".equals(outcome.route()) && ir.workflowSkill() != null) {
            return Optional.of(new RouteDecision(Kind.WORKFLOW, "conversation-workflow-v1", "PUBLISHED_SKILL_BINDING",
                    null, null, Map.of()));
        }
        if ("SKILL_UNAVAILABLE_DIRECT_FALLBACK".equals(outcome.reason())) {
            Binding fallback = workflowFallbacks.get(tenant + ":" + outcome.intent());
            if (fallback == null || fallback.definitionVersion() != ir.definitionVersion() || !"NORMAL".equals(ir.riskHint())) {
                return Optional.empty();
            }
            return bind(fallback, ir.arguments(), Map.of()).map(plan -> new RouteDecision(plan.kind(),
                    plan.policyVersion(), outcome.reason(), plan.toolName(), plan.toolVersion(), plan.arguments()));
        }
        Binding binding = binding(tenant, ir.domain() + "." + ir.action());
        if (binding == null || ir.definitionVersion() != binding.definitionVersion() || binding.predecessor() != null
                || !"NORMAL".equals(ir.riskHint())) return Optional.empty();
        return bind(binding, ir.arguments(), Map.of());
    }

    public Optional<Dependent> dependency(String tenant, List<Outcome> outcomes, int ordinal) {
        Outcome outcome = outcomes.get(ordinal);
        Binding binding = binding(tenant, outcome.intent());
        var ir = outcome.ir();
        if (binding == null || binding.predecessor() == null || ir == null || ir.inputVersion() == null
                || binding.definitionVersion() != ir.definitionVersion() || !"NORMAL".equals(ir.riskHint())
                || !List.of("READY", "WAITING_DEPENDENCY").contains(outcome.status())) return Optional.empty();
        int parent = -1;
        for (int i = 0; i < ordinal; i++) {
            if (binding.predecessor().equals(outcomes.get(i).intent())) {
                if (parent >= 0) return Optional.of(new Dependent(binding, null, ir.arguments(), "DEPENDENCY_AMBIGUOUS"));
                parent = i;
            }
        }
        if (parent < 0) return Optional.of(new Dependent(binding, null, ir.arguments(), "DEPENDENCY_REQUIRED"));
        if (!outcome.dependsOn().isEmpty() && !outcome.dependsOn().equals(List.of(parent))) {
            return Optional.of(new Dependent(binding, parent, ir.arguments(), "DEPENDENCY_CONFLICT"));
        }
        return Optional.of(new Dependent(binding, parent, ir.arguments(), null));
    }

    public static Optional<RouteDecision> bind(Binding binding, Map<String, Object> slots, Map<String, Object> result) {
        var arguments = new LinkedHashMap<String, Object>();
        for (var field : binding.parameters().entrySet()) {
            String ref = field.getValue();
            Object value = ref.startsWith("result.") ? result.get(ref.substring(7))
                    : slots.get(ref.startsWith("slot.") ? ref.substring(5) : ref);
            if (value == null || value instanceof String text && text.isBlank()) return Optional.empty();
            arguments.put(field.getKey(), value);
        }
        return Optional.of(new RouteDecision(binding.kind(), "conversation-dispatch-v2", "CONFIGURED_INTENT_BINDING",
                binding.toolName(), binding.version(), Map.copyOf(arguments)));
    }

    private Binding binding(String tenant, String intent) {
        return bindings.getOrDefault(tenant + ":" + intent, bindings.get(intent));
    }
}
