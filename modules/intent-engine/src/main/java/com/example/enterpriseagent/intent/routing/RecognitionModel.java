package com.example.enterpriseagent.intent.routing;

import java.util.List;
import java.util.Optional;

/** 模型仅在允许列表里消歧，身份、参数校验和准入由服务端负责。 */
@FunctionalInterface
public interface RecognitionModel {
    Optional<String> choose(String text, List<String> candidates);

    static RecognitionModel disabled() { return (text, candidates) -> Optional.empty(); }
}
