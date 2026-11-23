package com.example.enterpriseagent.runtime.planner;

import com.example.enterpriseagent.runtime.RunModel.Actor;
import com.example.enterpriseagent.runtime.RunModel.FixedNode;
import com.example.enterpriseagent.runtime.RunModel.SkillVersion;
import com.example.enterpriseagent.runtime.RunModel.SubagentPolicy;
import com.example.enterpriseagent.runtime.RunModel.SubagentFailureAction;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.BiPredicate;

/** L4 只提议节点与引用。服务器绑定版本、校验 DAG，并加入最终验收节点。 */
public final class GeneralPlanner {
    public static final String GATE_NODE = "acceptance";
    public static final String VALIDATE_SKILL_INPUT = "skill.validate_input";
    private static final String FIELD = "[A-Za-z][A-Za-z0-9_]{0,63}";
    private final Catalog catalog;
    private final SkillCatalog skills;
    private final Function<Context, Draft> model;
    private final BiPredicate<JsonNode, Map<String, Object>> fallbackValidator;

    public GeneralPlanner(Catalog catalog, SkillCatalog skills, Function<Context, Draft> model,
                          BiPredicate<JsonNode, Map<String, Object>> fallbackValidator) {
        this.catalog = catalog;
        this.skills = skills;
        this.model = model;
        this.fallbackValidator = fallbackValidator;
    }

    public boolean enabled() { return model != null; }

    public Published propose(Actor actor, Policy policy, JsonNode ir, Map<String, String> failures, int iteration) {
        JsonNode arguments = ir.path("arguments");
        List<Tool> authorized = catalog.candidates(actor, policy);
        List<Tool> candidates = authorized.stream().filter(tool -> policy.tools().contains(tool.name())).toList();
        var delegates = new LinkedHashMap<String, Tool>();
        var delegatePolicies = new LinkedHashMap<String, SubagentPolicy>();
        policy.subagents().forEach(agent -> delegatePolicies.put(agent.name(), agent.resilience()));
        policy.subagents().forEach(agent -> authorized.stream().filter(tool -> tool.name().equals(agent.tool()))
                .findFirst().ifPresent(tool -> delegates.put(agent.name(), tool)));
        JsonNode boundSkill = ir.path("workflowSkill");
        Skill skill = boundSkill.isObject() ? skills.load(actor, ir) : null;
        require(!boundSkill.isObject() || skill != null, "PLANNER_SKILL_UNAVAILABLE");
        require(!candidates.isEmpty() || skill != null || !delegates.isEmpty(), "PLANNER_NO_AUTHORIZED_TOOLS");
        SkillContract skillContract = skill == null ? null : configureSkillInputs(skill, policy);
        var context = new Context(policy.goal(), arguments, candidates, skillContract,
                policy.requiredFields(), List.copyOf(failures.keySet()), policy.businessChecks(), failures, iteration,
                delegates.entrySet().stream().map(entry -> new DelegateContract(entry.getKey(), entry.getValue().description(),
                        entry.getValue().inputSchema(), entry.getValue().outputSchema(),
                        delegatePolicies.get(entry.getKey()).onFailure())).toList());
        Draft draft = model.apply(context);
        require(draft != null && draft.nodes() != null && !draft.nodes().isEmpty() && draft.nodes().size() <= policy.maxNodes(),
                "PLANNER_NODE_BUDGET");
        var nodes = new LinkedHashMap<String, Node>();
        for (Node node : draft.nodes()) {
            require(node != null && node.id() != null && node.id().matches("[a-z][a-z0-9_-]{0,31}")
                    && !GATE_NODE.equals(node.id()) && nodes.putIfAbsent(node.id(), node) == null,
                    "PLANNER_NODE_ID");
            int targets = (node.tool() == null ? 0 : 1) + (node.skill() == null ? 0 : 1) + (node.subagent() == null ? 0 : 1);
            require(targets == 1 && node.dependsOn() != null && node.inputs() != null,
                    "PLANNER_NODE_SHAPE");
        }
        require(draft.result() != null && nodes.containsKey(draft.result()), "PLANNER_RESULT_REQUIRED");
        var tools = new LinkedHashMap<String, Tool>();
        var selectedAgents = new HashSet<String>();
        var dropNodes = new HashSet<String>();
        int skillNodes = 0;
        for (Node node : nodes.values()) {
            if (node.skill() != null) {
                require(skillContract != null && skillContract.key().equals(node.skill()) && ++skillNodes == 1,
                        "PLANNER_SKILL_DENIED");
                require(node.inputs().isEmpty() && node.dependsOn().isEmpty()
                        || !skillContract.upstreamFields().isEmpty()
                        && skillContract.upstreamFields().containsAll(node.inputs().keySet()), "PLANNER_SKILL_INPUT_DENIED");
            } else if (node.subagent() != null) {
                Tool tool = delegates.get(node.subagent());
                require(tool != null && selectedAgents.add(node.subagent()), "PLANNER_SUBAGENT_DENIED");
                require(node.inputs().keySet().stream().allMatch(field -> field.matches(FIELD)), "PLANNER_INPUT_FIELD");
                var resilience = delegatePolicies.get(node.subagent());
                if (resilience.onFailure() == SubagentFailureAction.DROP) dropNodes.add(node.id());
                if (resilience.onFailure() == SubagentFailureAction.DEGRADE) {
                    require(fallbackValidator.test(tool.outputSchema(), resilience.fallback()), "PLANNER_SUBAGENT_FALLBACK_INVALID");
                }
                tools.put(node.id(), tool);
            } else {
                Tool tool = candidates.stream().filter(candidate -> candidate.name().equals(node.tool())).findFirst().orElse(null);
                require(tool != null, "PLANNER_TOOL_DENIED");
                tools.put(node.id(), tool);
            }
            var parents = new HashSet<>(node.dependsOn());
            require(parents.size() == node.dependsOn().size() && !parents.contains(null)
                    && !parents.contains(node.id()) && nodes.keySet().containsAll(parents), "PLANNER_DEPENDENCY");
        }
        require(skill == null || skillNodes == 1, "PLANNER_SKILL_REQUIRED");
        require(!dropNodes.contains(draft.result()) && nodes.values().stream().flatMap(node -> node.inputs().values().stream())
                   .noneMatch(ref -> ref != null && dropNodes.stream().anyMatch(drop -> ref.startsWith("steps." + drop + ".output."))),
                "PLANNER_SUBAGENT_DROP_DEPENDENCY");
        boolean dynamicSkill = nodes.values().stream().anyMatch(node -> node.skill() != null && !node.dependsOn().isEmpty());
        require(skill == null || nodes.size() - 1 + skill.nodes().size() + (dynamicSkill ? 1 : 0) <= policy.maxNodes(),
                "PLANNER_EXPANDED_NODE_BUDGET");
        Set<String> used = new HashSet<>();
        visit(draft.result(), nodes, new HashSet<>(), used);
        require(used.size() == nodes.size(), "PLANNER_ORPHAN_NODE");
        var outputs = new LinkedHashMap<String, JsonNode>();
        for (Node node : nodes.values()) {
            outputs.put(node.id(), node.skill() == null ? tools.get(node.id()).outputSchema() : skill.contract().outputSchema());
        }
        for (Node node : nodes.values()) {
            if (node.skill() == null) {
                var schema = tools.get(node.id()).inputSchema();
                schema.path("required").forEach(field -> require(node.inputs().containsKey(field.asText()), "PLANNER_REQUIRED_INPUT"));
                validateBindings(node, arguments, schema, outputs);
            } else if (!node.inputs().isEmpty()) {
                require(node.inputs().values().stream().allMatch(ref -> ref != null && ref.startsWith("steps.")),
                        "PLANNER_SKILL_INPUT_REFERENCE");
                validateBindings(node, arguments, skillContract.inputSchema(), outputs);
            }
        }
        JsonNode output = outputs.get(draft.result()).path("properties");
        require(policy.requiredFields().stream().allMatch(output::has), "PLANNER_RESULT_SCHEMA");
        require(policy.businessChecks().stream().allMatch(rule -> output.has(rule.field())), "PLANNER_BUSINESS_RESULT_SCHEMA");
        require(policy.businessChecks().stream().allMatch(rule -> rule.inputField() == null || arguments.hasNonNull(rule.inputField())),
                "PLANNER_GATE_INPUT_MISSING");
        var published = new ArrayList<FixedNode>();
        for (Node node : nodes.values()) {
            if (node.skill() != null) {
                expand(node, skill, published);
                continue;
            }
            Tool tool = tools.get(node.id());
            published.add(new FixedNode(node.id(), null, tool.name(), tool.version(), tool.versionId(),
                    node.dependsOn(), node.inputs(), false, null, null, node.subagent(), delegatePolicies.get(node.subagent())));
        }
        published.add(new FixedNode(GATE_NODE, null, null, null, null, List.of(draft.result()),
                Map.of("result", "steps." + draft.result() + ".output"), true));
        return new Published(context, draft, List.copyOf(published));
    }

    private static SkillContract configureSkillInputs(Skill skill, Policy policy) {
        var contract = skill.contract();
        var fields = policy.skillInputFields();
        require(fields.isEmpty() || skill.acceptsUpstream() && fields.stream().allMatch(contract.inputSchema().path("properties")::has),
                "PLANNER_SKILL_INPUT_POLICY");
        return new SkillContract(contract.id(), contract.key(), contract.version(), contract.outputSchema(),
                contract.inputSchema(), fields);
    }

    /** 逻辑节点的 ID 保留给 SOP 出口，内部节点使用模型无法声明的命名空间。 */
    private static void expand(Node selected, Skill skill, List<FixedNode> plan) {
        String id = selected.id();
        var keys = new LinkedHashMap<String, String>();
        skill.nodes().forEach(node -> keys.put(node.id(), node.result() ? id : "_" + id + "--" + node.id()));
        var version = new SkillVersion(skill.contract().id(), skill.contract().key(), skill.contract().version());
        String validator = selected.dependsOn().isEmpty() ? null : "_" + id + "--";
        if (validator != null) {
            // 校验节点只保存引用。执行时把上游字段合并到固定的 Task 实参，验证完整契约。
            plan.add(new FixedNode(validator, VALIDATE_SKILL_INPUT, null, null, null, selected.dependsOn(),
                    selected.inputs(), false, version, "$input", null, null, skill.contract().inputSchema()));
        }
        for (FixedNode node : skill.nodes()) {
            var inputs = new LinkedHashMap<String, String>();
            var parents = new ArrayList<>(node.dependsOn().stream().map(keys::get).toList());
            node.inputs().forEach((field, ref) -> {
                String[] path = ref.split("\\.");
                inputs.put(field, switch (path[0]) {
                    case "steps" -> "steps." + keys.get(path[1]) + ".output";
                    case "input" -> validator == null ? ref : "steps." + validator + ".output." + path[1];
                    default -> ref;
                });
            });
            if (validator != null && (parents.isEmpty() || node.inputs().values().stream().anyMatch(ref -> ref.startsWith("input.")))) {
                parents.add(validator);
            }
            plan.add(new FixedNode(keys.get(node.id()), node.handler(), node.toolName(), node.toolVersion(),
                    node.toolVersionId(), List.copyOf(parents), inputs, false, version, node.id()));
        }
    }

    private static void visit(String id, Map<String, Node> nodes, Set<String> active, Set<String> seen) {
        if (seen.contains(id)) return;
        require(active.add(id), "PLANNER_CYCLE");
        for (String parent : nodes.get(id).dependsOn()) visit(parent, nodes, active, seen);
        active.remove(id);
        seen.add(id);
    }

    private static void validateBindings(Node node, JsonNode arguments, JsonNode schema, Map<String, JsonNode> outputs) {
        for (var entry : node.inputs().entrySet()) {
            String ref = entry.getValue();
            JsonNode target = schema.path("properties").path(entry.getKey());
            require(!target.isMissingNode() && ref != null, "PLANNER_INPUT_FIELD");
            if (ref.matches("input\\." + FIELD)) {
                require(arguments.hasNonNull(ref.substring(6)), "PLANNER_INPUT_REFERENCE");
            } else {
                require(ref.matches("steps\\.[a-z][a-z0-9_-]{0,31}\\.output\\." + FIELD), "PLANNER_OUTPUT_REFERENCE");
                String[] path = ref.split("\\.");
                require(node.dependsOn().contains(path[1]), "PLANNER_OUTPUT_DEPENDENCY");
                JsonNode source = outputs.get(path[1]).path("properties").path(path[3]);
                require(!source.isMissingNode(), "PLANNER_OUTPUT_FIELD");
                if (source.path("type").isTextual() && target.path("type").isTextual()) {
                    require(source.path("type").equals(target.path("type"))
                            || "integer".equals(source.path("type").asText()) && "number".equals(target.path("type").asText()),
                            "PLANNER_REFERENCE_TYPE");
                }
            }
        }
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new Rejected(code);
    }

    public static final class Rejected extends RuntimeException {
        private Rejected(String code) { super(code); }
    }

    @FunctionalInterface public interface Catalog { List<Tool> candidates(Actor actor, Policy policy); }
    @FunctionalInterface public interface SkillCatalog { Skill load(Actor actor, JsonNode ir); }

    public record Policy(String goal, List<String> tools, List<String> requiredFields, int maxNodes, int maxIterations,
                         List<BusinessGate.Rule> businessChecks, List<Delegation> subagents, List<String> skillInputFields) {
        public Policy {
            subagents = subagents == null ? List.of() : List.copyOf(subagents);
            tools = tools == null ? List.of() : List.copyOf(tools);
            if (goal == null || goal.isBlank() || goal.length() > 512 || tools.size() > 12
                    || tools.isEmpty() && subagents.isEmpty() || subagents.size() > 4
                    || subagents.stream().map(Delegation::name).distinct().count() != subagents.size()
                    || requiredFields == null || requiredFields.isEmpty() || requiredFields.size() > 8
                    || maxNodes < 1 || maxNodes > 12 || maxIterations < 1 || maxIterations > 3
                    || tools.stream().anyMatch(name -> name == null || !name.matches("[A-Za-z0-9_-]{1,64}"))
                    || requiredFields.stream().anyMatch(field -> field == null || !field.matches(FIELD))
                    || new HashSet<>(tools).size() != tools.size() || new HashSet<>(requiredFields).size() != requiredFields.size()) {
                throw new IllegalArgumentException("通用规划策略无效");
            }
            requiredFields = List.copyOf(requiredFields);
            businessChecks = businessChecks == null ? List.of() : List.copyOf(businessChecks);
            skillInputFields = skillInputFields == null ? List.of() : List.copyOf(skillInputFields);
            if (skillInputFields.size() > 16 || skillInputFields.stream().anyMatch(field -> !field.matches(FIELD))
                    || skillInputFields.stream().distinct().count() != skillInputFields.size()) {
                throw new IllegalArgumentException("Skill 上游绑定字段无效");
            }
            if (businessChecks.size() > 12
                    || businessChecks.stream().map(BusinessGate.Rule::id).distinct().count() != businessChecks.size()) {
                throw new IllegalArgumentException("业务验收规则数量过多或 ID 重复");
            }
        }

        public List<String> catalogTools() {
            return java.util.stream.Stream.concat(tools.stream(), subagents.stream().map(Delegation::tool)).distinct().toList();
        }

        public List<String> checkKeys() {
            var keys = new ArrayList<>(requiredFields);
            businessChecks.forEach(rule -> keys.add(rule.checkKey()));
            return List.copyOf(keys);
        }
    }
    public record Delegation(String name, String tool, SubagentPolicy resilience) {
        public Delegation {
            if (name == null || !name.matches("[a-z][a-z0-9_-]{0,31}") || tool == null || !tool.matches("[A-Za-z0-9_-]{1,64}")) {
                throw new IllegalArgumentException("SubAgent 配置无效");
            }
            resilience = resilience == null ? new SubagentPolicy(null, null, null, null) : resilience;
        }
    }
    public record DelegateContract(String name, String description, JsonNode inputSchema, JsonNode outputSchema,
                                   SubagentFailureAction onFailure) {}
    public record Tool(String name, String description, String version, UUID versionId, JsonNode inputSchema, JsonNode outputSchema) {}
    public record SkillContract(UUID id, String key, int version, JsonNode outputSchema, JsonNode inputSchema,
                                List<String> upstreamFields) {}
    public record Skill(SkillContract contract, List<FixedNode> nodes, boolean acceptsUpstream) {}
    public record Context(String goal, JsonNode arguments, List<Tool> tools, SkillContract skill, List<String> requiredFields,
                          List<String> missing, List<BusinessGate.Rule> businessChecks, Map<String, String> failures, int iteration,
                          List<DelegateContract> subagents) {}
    public record Node(String id, String tool, String skill, String subagent, List<String> dependsOn, Map<String, String> inputs) {}
    public record Draft(List<Node> nodes, String result) {}
    public record Published(Context context, Draft draft, List<FixedNode> nodes) {}
}
