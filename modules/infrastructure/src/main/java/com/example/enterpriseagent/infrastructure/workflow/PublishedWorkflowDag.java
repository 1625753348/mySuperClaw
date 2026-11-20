package com.example.enterpriseagent.infrastructure.workflow;

import com.example.enterpriseagent.runtime.RunModel.FixedNode;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** 从权威编译产物恢复节点和固定工具版本。 */
public final class PublishedWorkflowDag {
    private PublishedWorkflowDag() {}

    public static List<FixedNode> nodes(JsonNode dag) {
        var bindings = dag.path("toolBindings");
        String result = dag.path("output").asText();
        var nodes = new ArrayList<FixedNode>();
        for (JsonNode node : dag.path("nodes")) {
            String id = node.path("id").asText();
            JsonNode binding = bindings.path(id);
            var refs = new LinkedHashMap<String, String>();
            node.path("inputs").fields().forEachRemaining(entry -> refs.put(entry.getKey(), entry.getValue().asText()));
            var parents = new ArrayList<String>();
            node.path("dependsOn").forEach(parent -> parents.add(parent.asText()));
            nodes.add(new FixedNode(id, node.path("handler").textValue(), binding.path("toolName").textValue(),
                    binding.path("version").textValue(), binding.isMissingNode() ? null : UUID.fromString(binding.path("versionId").asText()),
                    List.copyOf(parents), refs, ("steps." + id + ".output").equals(result)));
        }
        return List.copyOf(nodes);
    }
}
