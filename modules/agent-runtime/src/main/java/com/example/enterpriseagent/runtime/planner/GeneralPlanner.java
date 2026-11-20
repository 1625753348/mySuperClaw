package com.example.enterpriseagent.runtime.planner;

import com.example.enterpriseagent.runtime.RunModel.Actor;
import com.example.enterpriseagent.runtime.RunModel.FixedNode;
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
    private final Function<Context, Draft> model;

    public GeneralPlanner(Catalog catalog, Function<Context, Draft> model) {
        this.catalog = catalog;
        this.model = model;
    }

    public boolean enabled() { return model != null; }

    public Published propose(Actor actor, Policy policy, JsonNode arguments, List<String> missing, int iteration) {
        List<Tool> candidates = catalog.candidates(actor, policy);
        require(!candidates.isEmpty(), "PLANNER_NO_AUTHORIZED_TOOLS");
        var context = new Context(policy.goal(), arguments, candidates, policy.requiredFields(), missing, iteration);
        Draft draft = model.apply(context);
        require(draft != null && draft.nodes() != null && !draft.nodes().isEmpty() && draft.nodes().size() <= policy.maxNodes(),
                "PLANNER_NODE_BUDGET");
        var nodes = new LinkedHashMap<String, Node>();
        for (Node node : draft.nodes()) {
            require(node != null && node.id() != null && node.id().matches("[a-z][a-z0-9_-]{0,31}")
                    && !GATE_NODE.equals(node.id()) && nodes.putIfAbsent(node.id(), node) == null,
                    "PLANNER_NODE_ID");
            require(node.tool() != null && node.dependsOn() != null && node.inputs() != null, "PLANNER_NODE_SHAPE");
        }
        require(draft.result() != null && nodes.containsKey(draft.result()), "PLANNER_RESULT_REQUIRED");
        var tools = new LinkedHashMap<String, Tool>();
        for (Node node : nodes.values()) {
            Tool tool = candidates.stream().filter(candidate -> candidate.name().equals(node.tool())).findFirst().orElse(null);
            require(tool != null, "PLANNER_TOOL_DENIED");
            tools.put(node.id(), tool);
            var parents = new HashSet<>(node.dependsOn());
            require(parents.size() == node.dependsOn().size() && !parents.contains(null)
                    && !parents.contains(node.id()) && nodes.keySet().containsAll(parents), "PLANNER_DEPENDENCY");
        }
        Set<String> used = new HashSet<>();
        visit(draft.result(), nodes, new HashSet<>(), used);
        require(used.size() == nodes.size(), "PLANNER_ORPHAN_NODE");
        for (Node node : nodes.values()) validateBindings(node, arguments, tools);
        JsonNode output = tools.get(draft.result()).outputSchema().path("properties");
        require(policy.requiredFields().stream().allMatch(output::has), "PLANNER_RESULT_SCHEMA");
        var published = new ArrayList<FixedNode>();
        for (Node node : nodes.values()) {
            Tool tool = tools.get(node.id());
            published.add(new FixedNode(node.id(), null, tool.name(), tool.version(), tool.versionId(),
                    node.dependsOn(), node.inputs(), false));
        }
        published.add(new FixedNode(GATE_NODE, null, null, null, null, List.of(draft.result()),
                Map.of("result", "steps." + draft.result() + ".output"), true));
        return new Published(context, draft, List.copyOf(published));
    }

    private static void visit(String id, Map<String, Node> nodes, Set<String> active, Set<String> seen) {
        if (seen.contains(id)) return;
        require(active.add(id), "PLANNER_CYCLE");
        for (String parent : nodes.get(id).dependsOn()) visit(parent, nodes, active, seen);
        active.remove(id);
        seen.add(id);
    }

    private static void validateBindings(Node node, JsonNode arguments, Map<String, Tool> tools) {
        JsonNode schema = tools.get(node.id()).inputSchema();
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
                JsonNode source = tools.get(path[1]).outputSchema().path("properties").path(path[3]);
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

    public record Policy(String goal, List<String> tools, List<String> requiredFields, int maxNodes, int maxIterations) {
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
        }
    }
    public record Tool(String name, String description, String version, UUID versionId, JsonNode inputSchema, JsonNode outputSchema) {}
    public record Context(String goal, JsonNode arguments, List<Tool> tools, List<String> requiredFields,
                          List<String> missing, int iteration) {}
    public record Node(String id, String tool, List<String> dependsOn, Map<String, String> inputs) {}
    public record Draft(List<Node> nodes, String result) {}
    public record Published(Context context, Draft draft, List<FixedNode> nodes) {}
}
