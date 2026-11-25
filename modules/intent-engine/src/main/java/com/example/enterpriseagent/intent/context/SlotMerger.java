package com.example.enterpriseagent.intent.context;

import com.example.enterpriseagent.intent.IntentArtifacts.FilledSlots;
import com.example.enterpriseagent.intent.IntentModel.Definition;
import com.example.enterpriseagent.intent.IntentModel.State;
import com.example.enterpriseagent.intent.IntentModel.Task;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** 由已解析的 Task 继承槽位，用当前轮的已校验参数覆盖。 */
public final class SlotMerger {
    private static final Pattern REFERENCE = Pattern.compile("它|他|她|这份|这笔|这个|刚才|那份|那张");

    public Merged merge(Definition definition, Task target, State state, Map<String, Object> entities, long now,
                        String text, FilledSlots extracted) {
        var values = new LinkedHashMap<String, Object>();
        var sources = new LinkedHashMap<String, String>();
        if (target != null) target.slots().forEach((key, value) -> {
            values.put(key, value);
            sources.put(key, "PRIOR_TASK");
        });
        if (REFERENCE.matcher(text).find()) {
            for (String field : List.of("person", "contractId", "documentId")) {
                if ((definition.required().contains(field) || definition.optional().contains(field))
                        && !values.containsKey(field) && entities.containsKey(field)
                        && state.tasks().stream().anyMatch(task -> ContextResolver.isUsable(state, task, now)
                        && Objects.equals(task.slots().get(field), entities.get(field)))) {
                    values.put(field, entities.get(field));
                    sources.put(field, "SESSION_ENTITY");
                }
            }
        }
        extracted.invalid().forEach(field -> {
            values.remove(field);
            sources.remove(field);
        });
        extracted.values().forEach((field, value) -> {
            values.put(field, value);
            sources.put(field, "CURRENT_TURN");
        });
        return new Merged(values, sources);
    }

    public record Merged(Map<String, Object> values, Map<String, String> provenance) {}
}
