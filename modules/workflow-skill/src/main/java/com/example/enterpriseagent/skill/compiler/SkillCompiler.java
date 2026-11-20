package com.example.enterpriseagent.skill.compiler;

import com.example.enterpriseagent.skill.SkillRegistry.Failure;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 编译项目 SKILL.md 的受限 JSON 清单。引用只能指向受信上下文、声明的输入和直接依赖。 */
public final class SkillCompiler {
    private static final Pattern BLOCK = Pattern.compile("(?ms)^```json[ \\t]*\\R(.*?)^```[ \\t]*(?:\\R|$)");
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{0,47}");
    private static final Set<String> CONTEXT = Set.of("repository", "ref", "from", "to");
    private static final Map<String, Handler> HANDLERS = Map.of(
            "retrospective.synthesize@1", new Handler(Map.of("issues", "IssueEvidence", "commits", "CommitEvidence"),
                    "RetrospectiveFindings"),
            "retrospective.render_markdown@1", new Handler(Map.of("project", "string", "from", "string", "to", "string",
                    "findings", "RetrospectiveFindings"), "MarkdownReport"));
    private final ObjectMapper json;

    public SkillCompiler(ObjectMapper json) {
        this.json = json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }

    public Manifest compile(String markdown, String key, int version) {
        require(markdown != null && markdown.length() <= 65_536, "SKILL.md 大小无效");
        var block = BLOCK.matcher(markdown);
        require(block.find(), "SKILL.md 需要一个 JSON 清单");
        String body = block.group(1);
        require(!block.find(), "SKILL.md 只允许一个 JSON 清单");
        Manifest spec;
        try {
            spec = json.readValue(body, Manifest.class);
        } catch (JsonProcessingException ex) {
            throw new Failure(400, "SKILL.md 清单字段或 JSON 格式无效");
        }
        require(spec != null && key.matches("[a-z][a-z0-9.-]{2,79}") && key.equals(spec.skillKey())
                && version > 0 && version == spec.version(), "路径与清单的 Skill Key 或版本不一致");
        require("WORKFLOW".equals(spec.executionMode()), "本发布入口支持 WORKFLOW");
        require(spec.intentDefinition() != null && spec.intentDefinition().matches("[A-Z][A-Z0-9_]{0,39}\\.[A-Z][A-Z0-9_]{0,39}@[1-9][0-9]{0,8}"),
                "意图定义需指定版本");
        require("project.retrospective.read".equals(spec.admissionPolicy()), "准入策略未注册");
        validateInput(spec.inputSchema());
        require(spec.triggers() != null && !spec.triggers().isEmpty() && spec.triggers().size() <= 12, "需要命令触发器");
        Set<String> commands = new HashSet<>();
        for (Trigger trigger : spec.triggers()) {
            require(trigger != null && "COMMAND".equals(trigger.kind()) && trigger.key() != null
                    && trigger.key().matches("/[a-z][a-z0-9-]{0,39}") && commands.add(trigger.key()), "命令触发器无效或重复");
        }
        require(spec.nodes() != null && !spec.nodes().isEmpty() && spec.nodes().size() <= 32, "节点数量无效");
        Map<String, Node> nodes = new LinkedHashMap<>();
        for (Node node : spec.nodes()) {
            require(node != null && node.id() != null && ID.matcher(node.id()).matches()
                    && nodes.putIfAbsent(node.id(), node) == null, "节点 ID 无效或重复");
            require((node.tool() != null) != (node.handler() != null), "每个节点必须指定一个工具或本地处理器");
            require(node.dependsOn() != null && node.dependsOn().stream().allMatch(java.util.Objects::nonNull)
                    && new HashSet<>(node.dependsOn()).size() == node.dependsOn().size()
                    && node.inputs() != null && node.output() != null, "节点依赖、输入或输出无效");
            if (node.tool() != null) require(node.tool().matches("[a-z][a-z0-9_-]*(?:/[a-z][a-z0-9_-]*)?@[A-Za-z0-9._-]{1,48}"),
                    "工具标识需要固定版本");
        }
        List<Node> sorted = new ArrayList<>();
        Set<String> done = new HashSet<>();
        while (sorted.size() < nodes.size()) {
            List<Node> ready = nodes.values().stream().filter(node -> !done.contains(node.id()) && done.containsAll(node.dependsOn())).toList();
            require(!ready.isEmpty(), "DAG 含环或引用未知依赖");
            ready.forEach(node -> {
                Map<String, String> types = inputTypes(node, spec.inputSchema(), nodes);
                if (node.handler() != null) {
                    Handler contract = HANDLERS.get(node.handler());
                    require(contract != null && contract.inputs().equals(types) && contract.output().equals(node.output()),
                            "本地处理器的输入或输出不匹配：" + node.id());
                } else {
                    require(types.values().stream().allMatch("string"::equals)
                            && Set.of("IssueEvidence", "CommitEvidence").contains(node.output()), "取证工具契约无效：" + node.id());
                }
                done.add(node.id());
                sorted.add(node);
            });
        }
        require(spec.output() != null && spec.output().matches("steps\\.[a-z][a-z0-9_-]*\\.output"), "最终输出引用无效");
        Node output = nodes.get(spec.output().split("\\.")[1]);
        require(output != null && "MarkdownReport".equals(output.output()), "最终输出必须为 MarkdownReport");
        Set<String> used = new HashSet<>();
        var visit = new ArrayList<>(List.of(output.id()));
        for (int i = 0; i < visit.size(); i++) {
            String id = visit.get(i);
            if (used.add(id)) visit.addAll(nodes.get(id).dependsOn());
        }
        require(used.size() == nodes.size(), "存在无法到达最终输出的节点");
        return new Manifest(spec.skillKey(), spec.version(), spec.intentDefinition(), spec.executionMode(),
                spec.admissionPolicy(), spec.triggers(), spec.inputSchema(), List.copyOf(sorted), spec.output());
    }

    private static void validateInput(JsonNode schema) {
        require(schema != null && schema.isObject() && "object".equals(schema.path("type").asText())
                && schema.path("properties").isObject() && schema.path("required").isArray()
                && schema.path("additionalProperties").isBoolean() && !schema.path("additionalProperties").asBoolean(),
                "输入必须列出属性、必填项并拒绝未声明参数");
        for (JsonNode field : schema.path("required")) {
            require(field.isTextual() && schema.path("properties").has(field.asText()), "必填项未声明");
        }
    }

    private static Map<String, String> inputTypes(Node node, JsonNode schema, Map<String, Node> nodes) {
        var types = new LinkedHashMap<String, String>();
        node.inputs().forEach((arg, ref) -> {
            require(ref != null, "输入引用不能为空");
            String[] parts = ref.split("\\.", -1);
            String type = null;
            if (parts.length == 2 && "context".equals(parts[0]) && CONTEXT.contains(parts[1])) type = "string";
            if (parts.length == 2 && "input".equals(parts[0])) type = schema.path("properties").path(parts[1]).path("type").textValue();
            if (parts.length == 3 && "steps".equals(parts[0]) && "output".equals(parts[2]) && node.dependsOn().contains(parts[1])) {
                type = nodes.get(parts[1]).output();
            }
            require(type != null, "节点引用无效：" + node.id() + "/" + arg);
            types.put(arg, type);
        });
        return types;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new Failure(400, message);
    }

    private record Handler(Map<String, String> inputs, String output) {}
    public record Trigger(String kind, String key) {}
    public record Node(String id, String tool, String handler, List<String> dependsOn, Map<String, String> inputs, String output) {}
    public record Manifest(String skillKey, int version, String intentDefinition, String executionMode,
                           String admissionPolicy, List<Trigger> triggers, JsonNode inputSchema, List<Node> nodes, String output) {}
}
