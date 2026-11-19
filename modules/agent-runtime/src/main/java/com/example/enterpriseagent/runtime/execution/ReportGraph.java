package com.example.enterpriseagent.runtime.execution;

import com.example.enterpriseagent.runtime.RunFailure;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentState;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import static com.example.enterpriseagent.runtime.RunFailure.Code.INVALID;
import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/** LangGraph4j 运行单个已领取的节点。跨节点顺序、并发和断点由 PostgreSQL 管理。 */
public final class ReportGraph {
    public static final List<String> DIMENSIONS = List.of("region", "product", "customer");
    private static final BigDecimal LIMIT = new BigDecimal("1000000000000");
    private final ObjectMapper json;
    private final Map<String, CompiledGraph<AgentState>> nodes;

    public ReportGraph(ObjectMapper json) {
        this.json = json;
        nodes = Map.of("dimension", compile("dimension", this::aggregate),
                "report", compile("report", this::compose), "gate", compile("gate", this::verify));
    }

    public JsonNode run(String action, JsonNode input) {
        return nodes.get(action).invoke(Map.of("input", input.toString()))
                .flatMap(state -> state.<String>value("result")).map(this::parse).orElseThrow();
    }

    public ArrayNode normalizeRows(JsonNode input) {
        if (input == null) throw invalid("销售工具没有返回可恢复的结果");
        JsonNode rows = input.isObject() ? input.path("rows") : input;
        if (!rows.isArray() || rows.isEmpty() || rows.size() > 500) {
            throw invalid("销售数据需要 1 到 500 行");
        }
        var normalized = json.createArrayNode();
        for (JsonNode row : rows) {
            var entry = normalized.addObject();
            for (String dimension : DIMENSIONS) {
                String value = row.path(dimension).textValue();
                if (value == null || value.isBlank() || value.length() > 80) throw invalid("销售维度无效");
                entry.put(dimension, value.strip());
            }
            entry.put("previous", amount(row.path("previous")));
            entry.put("current", amount(row.path("current")));
        }
        return normalized;
    }

    private JsonNode aggregate(JsonNode input) {
        String dimension = input.path("dimension").asText();
        Map<String, Totals> groups = new TreeMap<>();
        input.path("source").forEach(row -> groups.merge(row.get(dimension).asText(),
                new Totals(row.get("previous").decimalValue(), row.get("current").decimalValue()), Totals::add));
        var output = json.createObjectNode().put("dimension", dimension);
        var entries = output.putArray("entries");
        groups.forEach((name, totals) -> writeTotals(entries.addObject().put("name", name), totals));
        return output;
    }

    private JsonNode compose(JsonNode input) {
        var report = json.createObjectNode().put("goal", input.path("goal").asText());
        var figures = report.putObject("dimensions");
        var evidence = report.putObject("evidence");
        Totals overall = new Totals(BigDecimal.ZERO, BigDecimal.ZERO);
        for (var row : input.path("source")) {
            overall = overall.add(new Totals(row.path("previous").decimalValue(), row.path("current").decimalValue()));
        }
        writeTotals(report.putObject("totals"), overall);
        input.path("observations").fields().forEachRemaining(it -> {
            figures.set(it.getKey(), it.getValue().path("value"));
            evidence.set(it.getKey(), it.getValue().path("ref"));
        });
        return report;
    }

    private JsonNode verify(JsonNode input) {
        var missing = json.createArrayNode();
        JsonNode report = input.path("report");
        JsonNode totals = report.path("totals");
        input.path("required").forEach(item -> {
            String key = item.asText();
            JsonNode entries = report.path("dimensions").path(key).path("entries");
            JsonNode evidence = report.path("evidence").path(key);
            JsonNode expected = input.path("evidence").path(key);
            if (entries.isEmpty() || !evidence.isTextual() || !evidence.equals(expected) || !consistent(entries, totals)) {
                missing.add(key);
            }
        });
        return json.createObjectNode().put("pass", missing.isEmpty()).set("missing", missing);
    }

    private static boolean consistent(JsonNode entries, JsonNode totals) {
        Totals sum = new Totals(BigDecimal.ZERO, BigDecimal.ZERO);
        for (JsonNode item : entries) {
            sum = sum.add(new Totals(item.path("previous").decimalValue(), item.path("current").decimalValue()));
        }
        return totals.path("previous").isNumber() && totals.path("current").isNumber()
                && sum.previous().compareTo(totals.path("previous").decimalValue()) == 0
                && sum.current().compareTo(totals.path("current").decimalValue()) == 0;
    }

    private static BigDecimal amount(JsonNode input) {
        if (!input.isNumber()) throw invalid("销售额必须是数字");
        BigDecimal amount = input.decimalValue();
        if (amount.signum() < 0 || amount.compareTo(LIMIT) > 0 || amount.stripTrailingZeros().scale() > 6) {
            throw invalid("销售额超出范围");
        }
        return amount;
    }

    private static void writeTotals(com.fasterxml.jackson.databind.node.ObjectNode node, Totals totals) {
        node.put("previous", totals.previous()).put("current", totals.current())
                .put("change", totals.current().subtract(totals.previous()));
    }

    private CompiledGraph<AgentState> compile(String name, Function<JsonNode, JsonNode> action) {
        try {
            return new StateGraph<AgentState>(AgentState::new)
                    .addNode(name, node_async(state -> {
                        String input = state.<String>value("input").orElseThrow();
                        return Map.of("result", action.apply(parse(input)).toString());
                    }))
                    .addEdge(START, name).addEdge(name, END).compile();
        } catch (Exception ex) {
            throw new IllegalStateException("无法编译销售分析节点 " + name, ex);
        }
    }

    private JsonNode parse(String text) {
        try {
            return json.readTree(text);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("节点状态不是 JSON");
        }
    }

    private static RunFailure invalid(String message) { return new RunFailure(INVALID, message); }
    private record Totals(BigDecimal previous, BigDecimal current) {
        Totals add(Totals other) { return new Totals(previous.add(other.previous), current.add(other.current)); }
    }
}
