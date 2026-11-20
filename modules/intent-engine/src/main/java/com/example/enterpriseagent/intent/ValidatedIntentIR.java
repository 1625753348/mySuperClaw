package com.example.enterpriseagent.intent;

import java.util.Map;
import java.util.UUID;

/**
 * 意图理解交给 Decision 的 DTO，同时是会话回执 outcomes[].ir 的 JSON 契约。
 * definitionVersion 固定参数契约，inputVersion 固定本任务这一次已保存的输入。
 * riskHint 供策略判断，responseMode 声明调用方需要的响应形式。
 */
public record ValidatedIntentIR(String domain, String action, int definitionVersion, UUID taskId,
                                Map<String, Object> arguments, Map<String, String> provenance,
                                Integer inputVersion, String riskHint, ResponseMode responseMode) {
    public enum ResponseMode { RESULT, PROGRESS_AND_RESULT }

    public ValidatedIntentIR {
        arguments = Map.copyOf(arguments);
        provenance = Map.copyOf(provenance);
    }

    /** 保存任务输入时补上版本。只有带输入版本的 IR 可以提交给 Decision。 */
    public ValidatedIntentIR atVersion(int version) {
        if (version < 1) throw new IllegalArgumentException("Task 输入版本必须大于零");
        return new ValidatedIntentIR(domain, action, definitionVersion, taskId, arguments, provenance,
                version, riskHint, responseMode);
    }
}
