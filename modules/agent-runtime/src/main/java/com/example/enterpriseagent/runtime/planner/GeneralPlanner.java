package com.example.enterpriseagent.runtime.planner;

import com.example.enterpriseagent.runtime.RunModel.Actor;
import com.example.enterpriseagent.runtime.RunModel.FixedNode;
import com.example.enterpriseagent.runtime.RunModel.SkillVersion;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** L4 只提议节点与引用。服务器绑定版本、校验 DAG，并加入最终验收节点。 */
public final class GeneralPlanner {
    public static final String GATE_NODE = "acceptance";
    private static final String FIELD = "[A-Za-z][A-Za-z0-9_]{0,63}";
    private final Catalog catalog;
    private final SkillCatalog skills;
    private final Function<Context, Draft> model;

    public GeneralPlanner(Catalog catalog, SkillCatalog skills, Function<Context, Draft> model) {
        this.catalog = catalog;
        this.skills = skills;
        this.model = model;
    }

    public boolean enabled() { return model != null; }

    public Published propose(Actor actor, Policy policy, JsonNode ir, Map<String, String> failures, int iteration) {
        JsonNode arguments = ir.path("arguments");
        List<Tool> candidates = catalog.candidates(actor, policy);
        JsonNode boundSkill = ir.path("workflowSkill");
        Skill skill = boundSkill.isObject() ? skills.load(actor, ir) : null;
        require(!boundSkill.isObject() || skill != null, "PLANNER_SKILL_UNAVAILABLE");
        require(!candidates.isEmpty() || skill != null, "PLANNER_NO_AUTHORIZED_TOOLS");
        var context = new Context(policy.goal(), arguments, candidates, skill == null ? null : skill.contract(),
                policy.requiredFields(), List.copyOf(failures.keySet()), policy.businessChecks(), failures, iteration);
        Draft draft = model.apply(context);
        require(draft != null && draft.nodes() != null && !draft.nodes().isEmpty() && draft.nodes().size() <= policy.maxNodes(),
                "PLANNER_NODE_BUDGET");
        var nodes = new LinkedHashMap<String, Node>();
        for (Node node : draft.nodes()) {
            require(node != null && node.id() != null && node.id().matches("[a-z][a-z0-9_-]{0,31}")
                    && !GATE_NODE.equals(node.id()) && nodes.putIfAbsent(node.id(), node) == null,
                    "PLANNER_NODE_ID");
            require((node.tool() == null) != (node.skill() == null) && node.dependsOn() != null && node.inputs() != null,
                    "PLANNER_NODE_SHAPE");
        }
        require(draft.result() != null && nodes.containsKey(draft.result()), "PLANNER_RESULT_REQUIRED");
        var tools = new LinkedHashMap<String, Tool>();
        int skillNodes = 0;
        for (Node node : nodes.values()) {
            if (node.skill() != null) {
                require(skill != null && skill.contract().key().equals(node.skill()) && ++skillNodes == 1
                                && node.dependsOn().isEmpty() && node.inputs().isEmpty(),
                        "PLANNER_SKILL_DENIED");
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
        require(skill == null || nodes.size() - 1 + skill.nodes().size() <= policy.maxNodes(), "PLANNER_EXPANDED_NODE_BUDGET");
        Set<String> used = new HashSet<>();
        visit(draft.result(), nodes, new HashSet<>(), used);
        require(used.size() == nodes.size(), "PLANNER_ORPHAN_NODE");
        var outputs = new LinkedHashMap<String, JsonNode>();
        for (Node node : nodes.values()) {
            outputs.put(node.id(), node.skill() == null ? tools.get(node.id()).outputSchema() : skill.contract().outputSchema());
        }
        for (Node node : nodes.values()) {
            if (node.tool() != null) validateBindings(node, arguments, tools.get(node.id()).inputSchema(), outputs);
        }
        JsonNode output = outputs.get(draft.result()).path("properties");
        require(policy.requiredFields().stream().allMatch(output::has), "PLANNER_RESULT_SCHEMA");
        require(policy.businessChecks().stream().allMatch(rule -> output.has(rule.field())), "PLANNER_BUSINESS_RESULT_SCHEMA");
        require(policy.businessChecks().stream().allMatch(rule -> rule.inputField() == null || arguments.hasNonNull(rule.inputField())),
                "PLANNER_GATE_INPUT_MISSING");
        var published = new ArrayList<FixedNode>();
        for (Node node : nodes.values()) {
            if (node.skill() != null) {
                expand(node.id(), skill, published);
                continue;
            }
            Tool tool = tools.get(node.id());
            published.add(new FixedNode(node.id(), null, tool.name(), tool.version(), tool.versionId(),
                    node.dependsOn(), node.inputs(), false));
        }
        published.add(new FixedNode(GATE_NODE, null, null, null, null, List.of(draft.result()),
                Map.of("result", "steps." + draft.result() + ".output"), true));
        return new Published(context, draft, List.copyOf(published));
    }

    /** 逻辑节点的 ID 保留给 SOP 出口，内部节点使用模型无法声明的命名空间。 */
    private static void expand(String id, Skill skill, List<FixedNode> plan) {
        var keys = new LinkedHashMap<String, String>();
        skill.nodes().forEach(node -> keys.put(node.id(), node.result() ? id : "_" + id + "--" + node.id()));
        var version = new SkillVersion(skill.contract().id(), skill.contract().key(), skill.contract().version());
        for (FixedNode node : skill.nodes()) {
            var inputs = new LinkedHashMap<String, String>();
            node.inputs().forEach((field, ref) -> {
                String[] path = ref.split("\\.");
                inputs.put(field, "steps".equals(path[0]) ? "steps." + keys.get(path[1]) + ".output" : ref);
            });
            plan.add(new FixedNode(keys.get(node.id()), node.handler(), node.toolName(), node.toolVersion(),
                    node.toolVersionId(), node.dependsOn().stream().map(keys::get).toList(), inputs, false, version, node.id()));
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
        schema.path("required").forEach(required -> require(node.inputs().containsKey(required.asText()), "PLANNER_REQUIRED_INPUT"));
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
                         List<BusinessGate.Rule> businessChecks) {
        public Policy {
            if (goal == null || goal.isBlank() || goal.length() > 512 || tools == null || tools.isEmpty() || tools.size() > 12
                    || requiredFields == null || requiredFields.isEmpty() || requiredFields.size() > 8
                    || maxNodes < 1 || maxNodes > 12 || maxIterations < 1 || maxIterations > 3
                    || tools.stream().anyMatch(name -> name == null || !name.matches("[A-Za-z0-9_-]{1,64}"))
                    || requiredFields.stream().anyMatch(field -> field == null || !field.matches(FIELD))
                    || new HashSet<>(tools).size() != tools.size() || new HashSet<>(requiredFields).size() != requiredFields.size()) {
                throw new IllegalArgumentException("通用规划策略无效");
            }
            tools = List.copyOf(tools);
            requiredFields = List.copyOf(requiredFields);
            businessChecks = businessChecks == null ? List.of() : List.copyOf(businessChecks);
            if (businessChecks.size() > 12
                    || businessChecks.stream().map(BusinessGate.Rule::id).distinct().count() != businessChecks.size()) {
                throw new IllegalArgumentException("业务验收规则数量过多或 ID 重复");
            }
        }

        public List<String> checkKeys() {
            var keys = new ArrayList<>(requiredFields);
            businessChecks.forEach(rule -> keys.add(rule.checkKey()));
            return List.copyOf(keys);
        }
    }
    public record Tool(String name, String description, String version, UUID versionId, JsonNode inputSchema, JsonNode outputSchema) {}
    public record SkillContract(UUID id, String key, int version, JsonNode outputSchema) {}
    public record Skill(SkillContract contract, List<FixedNode> nodes) {}
    public record Context(String goal, JsonNode arguments, List<Tool> tools, SkillContract skill, List<String> requiredFields,
                          List<String> missing, List<BusinessGate.Rule> businessChecks, Map<String, String> failures, int iteration) {}
    public record Node(String id, String tool, String skill, List<String> dependsOn, Map<String, String> inputs) {}
    public record Draft(List<Node> nodes, String result) {}
    public record Published(Context context, Draft draft, List<FixedNode> nodes) {}
}
