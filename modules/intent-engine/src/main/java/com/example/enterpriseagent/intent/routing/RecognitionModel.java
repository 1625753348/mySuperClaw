package com.example.enterpriseagent.intent.routing;

import java.util.List;
import java.util.Optional;

/** 模型仅在允许列表里消歧，身份、参数校验和准入由服务端负责。 */
@FunctionalInterface
public interface RecognitionModel {
    Optional<String> choose(String text, List<String> candidates);

    default boolean enabled() { return true; }

    static RecognitionModel disabled() {
        return new RecognitionModel() {
            @Override public Optional<String> choose(String text, List<String> candidates) { return Optional.empty(); }
            @Override public boolean enabled() { return false; }
        };
    }
}
