package com.example.enterpriseagent.runtime.planner;

import com.example.enterpriseagent.runtime.RunFailure;
import com.example.enterpriseagent.runtime.RunModel.NodeSpec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.example.enterpriseagent.runtime.RunFailure.Code.INVALID;

/** 从白名单选择生成可执行的 DAG，并验证依赖图。 */
public final class ReportPlans {
    private ReportPlans() {}

    public static Map<String, NodeSpec> plan(List<String> selected, List<String> required,
                                             Map<String, UUID> reusable, boolean toolSource) {
        if (selected == null || selected.isEmpty() || selected.size() > required.size()
                || !required.containsAll(selected) || new HashSet<>(selected).size() != selected.size()) {
            throw new RunFailure(INVALID, "Planner 返回了无效的销售维度");
        }
        var active = new ArrayList<>(selected);
        required.stream().filter(key -> reusable.containsKey(key) && !active.contains(key)).forEach(active::add);
        var nodes = new LinkedHashMap<String, NodeSpec>();
        nodes.put("source", new NodeSpec(toolSource && !reusable.containsKey("source") ? "tool" : "source",
                null, reusable.get("source"), List.of()));
        for (String key : active) {
            nodes.put(key, new NodeSpec("dimension", key, reusable.get(key), List.of("source")));
        }
        var reportDeps = new ArrayList<String>();
        reportDeps.add("source");
        reportDeps.addAll(active);
        nodes.put("report", new NodeSpec("report", null, null, reportDeps));
        var gateDeps = new ArrayList<>(active);
        gateDeps.add("report");
        nodes.put("gate", new NodeSpec("gate", null, null, gateDeps));
        validate(nodes);
        return nodes;
    }

    static void validate(Map<String, NodeSpec> nodes) {
        var resolved = new HashSet<String>();
        while (resolved.size() < nodes.size()) {
            int before = resolved.size();
            nodes.forEach((key, node) -> {
                if (resolved.containsAll(node.dependencies())) resolved.add(key);
            });
            if (resolved.size() == before) throw new RunFailure(INVALID, "计划存在无效依赖或环");
        }
        if (nodes.size() > 6) throw new RunFailure(INVALID, "计划超出节点预算");
    }
}
