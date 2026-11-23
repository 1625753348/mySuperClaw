package com.example.enterpriseagent.runtime.planner;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** 业务验收由服务器执行。模型只获得固定规则和失败码。 */
public final class BusinessGate {
    private BusinessGate() {}

    public enum Operator { MATCH_INPUT, MIN_NUMBER, MIN_ITEMS, EQUALS_TEXT, IS_TRUE }

    public record Rule(String id, String field, Operator operator, String expected, BigDecimal minimum, String inputField) {
        public Rule {
            if (!identifier(id) || !identifier(field) || operator == null) {
                throw new IllegalArgumentException("业务验收规则名称、字段或运算符无效");
            }
            boolean valid = switch (operator) {
                case MATCH_INPUT -> identifier(inputField) && expected == null && minimum == null;
                case MIN_NUMBER -> minimum != null && expected == null && inputField == null;
                case MIN_ITEMS -> minimum != null && minimum.signum() > 0
                        && minimum.compareTo(BigDecimal.valueOf(10_000)) <= 0 && minimum.stripTrailingZeros().scale() <= 0
                        && expected == null && inputField == null;
                case EQUALS_TEXT -> expected != null && !expected.isBlank() && expected.length() <= 256
                        && minimum == null && inputField == null;
                case IS_TRUE -> expected == null && minimum == null && inputField == null;
            };
            if (!valid) throw new IllegalArgumentException("业务验收规则参数与运算符不匹配");
        }

        public String checkKey() { return "business." + id; }

        private static boolean identifier(String value) {
            return value != null && value.matches("[A-Za-z][A-Za-z0-9_]{0,63}");
        }
    }

    public static Map<String, String> evaluate(GeneralPlanner.Policy policy, JsonNode result, JsonNode arguments) {
        var failures = new LinkedHashMap<String, String>();
        for (String field : policy.requiredFields()) {
            if (empty(result.path(field))) failures.put(field, "REQUIRED_VALUE_MISSING");
        }
        for (Rule rule : policy.businessChecks()) {
            String failure = verify(rule, result.path(rule.field()), arguments);
            if (failure != null) failures.put(rule.checkKey(), failure);
        }
        return failures;
    }

    private static String verify(Rule rule, JsonNode value, JsonNode arguments) {
        if (empty(value)) return "RESULT_FIELD_MISSING";
        return switch (rule.operator()) {
            case MATCH_INPUT -> {
                JsonNode target = arguments.path(rule.inputField());
                if (empty(target)) yield "INPUT_REFERENCE_MISSING";
                boolean equal = value.isNumber() && target.isNumber()
                        ? value.decimalValue().compareTo(target.decimalValue()) == 0 : value.equals(target);
                yield equal ? null : "INPUT_VALUE_MISMATCH";
            }
            case MIN_NUMBER -> !value.isNumber() ? "EXPECTED_NUMBER"
                    : value.decimalValue().compareTo(rule.minimum()) < 0 ? "BELOW_MINIMUM" : null;
            case MIN_ITEMS -> !value.isArray() ? "EXPECTED_ARRAY"
                    : value.size() < rule.minimum().intValueExact() ? "TOO_FEW_ITEMS" : null;
            case EQUALS_TEXT -> value.isTextual() && value.textValue().equals(rule.expected()) ? null : "TEXT_MISMATCH";
            case IS_TRUE -> value.isBoolean() && value.booleanValue() ? null : "NOT_TRUE";
        };
    }

    private static boolean empty(JsonNode value) {
        return value.isMissingNode() || value.isNull() || value.isTextual() && value.asText().isBlank()
                || value.isContainerNode() && value.isEmpty();
    }
}
