package com.example.enterpriseagent.intent.routing;

import java.util.Map;

/** 部署发布的不可变识别策略版本。复杂度阈值和候选可信度是两个独立的量。 */
public record IntentRoutingPolicy(String version, double ruleBelow, double retrievalBelow, double hybridBelow,
                                  double minimumConfidence, double minimumMargin, Map<String, String> examples) {
    public IntentRoutingPolicy {
        if (version == null || !version.matches("[A-Za-z0-9._-]{1,64}")
                || !(ruleBelow > 0 && ruleBelow < retrievalBelow && retrievalBelow < hybridBelow && hybridBelow < 1)
                || !(minimumConfidence > 0 && minimumConfidence <= 1 && minimumMargin >= 0 && minimumMargin <= 1)) {
            throw new IllegalArgumentException("意图分级策略版本或阈值无效");
        }
        examples = examples == null ? Map.of() : Map.copyOf(examples);
    }

    public Band band(double score) {
        if (score < ruleBelow) return Band.RULE;
        if (score < retrievalBelow) return Band.RETRIEVAL_SMALL_MODEL;
        if (score < hybridBelow) return Band.HYSTERESIS;
        return Band.LARGE_MODEL;
    }

    public enum Band { RULE, RETRIEVAL_SMALL_MODEL, HYSTERESIS, LARGE_MODEL }
}
