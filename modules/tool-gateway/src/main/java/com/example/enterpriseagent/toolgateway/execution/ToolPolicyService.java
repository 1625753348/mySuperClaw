package com.example.enterpriseagent.toolgateway.execution;

import com.example.enterpriseagent.toolgateway.GatewayCaller;
import com.example.enterpriseagent.toolgateway.GatewayException;
import com.example.enterpriseagent.toolgateway.registry.GatewayRepository;
import com.example.enterpriseagent.toolgateway.registry.ToolContracts;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.example.enterpriseagent.toolgateway.GatewayException.Code.*;

/** 读取工具的当前策略。规则采用有限字段与 JSON Pointer，管理员配置只解释数据。 */
public final class ToolPolicyService {
    private static final Map<String, Set<String>> FIELDS = Map.of(
            "ABAC", Set.of("roles", "agents", "values", "ownerPointer"),
            "QUOTA", Set.of("scope", "perMinute", "perDay"),
            "APPROVAL", Set.of("ttlSeconds"),
            "RETRY", Set.of("maxAttempts", "failureThreshold", "openSeconds"),
            "REDACTION", Set.of("remove")
    );

    private final GatewayRepository store;
    private final ToolContracts contracts;

    public ToolPolicyService(GatewayRepository store, ToolContracts contracts) {
        this.store = store;
        this.contracts = contracts;
    }

    public void validate(String kind, JsonNode rule) {
        Set<String> fields = FIELDS.get(kind);
        if (fields == null) throw invalid("未知工具策略类型");
        if (rule == null || rule.isNull()) return;
        if (!rule.isObject() || rule.isEmpty() || rule.toString().length() > 16_384) {
            throw invalid("策略需要非空 JSON 对象，且不超过 16 KiB");
        }
        rule.fieldNames().forEachRemaining(field -> {
            if (!fields.contains(field)) throw invalid("策略包含不支持的字段");
        });
        switch (kind) {
            case "ABAC" -> validateAccess(rule);
            case "QUOTA" -> {
                if (!Set.of("PRINCIPAL", "TENANT").contains(rule.path("scope").asText())) {
                    throw invalid("配额 scope 需要 PRINCIPAL 或 TENANT");
                }
                number(rule, "perMinute", 1, 100_000, 0);
                number(rule, "perDay", 1, 10_000_000, 0);
                if (!rule.has("perMinute") && !rule.has("perDay")) throw invalid("请配置分钟限流或日配额");
            }
            case "APPROVAL" -> number(rule, "ttlSeconds", 30, 86_400, 900);
            case "RETRY" -> {
                number(rule, "maxAttempts", 1, 3, 1);
                number(rule, "failureThreshold", 1, 50, 5);
                number(rule, "openSeconds", 1, 600, 30);
            }
            case "REDACTION" -> {
                strings(rule.path("remove"));
                rule.path("remove").forEach(path -> pointer(path.textValue()));
            }
            default -> throw invalid("未知工具策略类型");
        }
    }

    private static void validateAccess(JsonNode rule) {
        if (rule.has("roles")) strings(rule.get("roles"));
        if (rule.has("agents")) strings(rule.get("agents"));
        if (rule.has("ownerPointer")) pointer(rule.get("ownerPointer").asText(""));
        if (rule.has("values")) {
            JsonNode values = rule.get("values");
            if (!values.isObject() || values.isEmpty() || values.size() > 30) throw invalid("ABAC values 需要参数约束对象");
            values.fields().forEachRemaining(entry -> {
                pointer(entry.getKey());
                JsonNode allowed = entry.getValue();
                if (!allowed.isArray() || allowed.isEmpty() || allowed.size() > 50) throw invalid("ABAC 允许值需要非空数组");
                allowed.forEach(value -> {
                    if (!value.isValueNode() || value.isNull()) throw invalid("ABAC 允许值需要非空 JSON 标量");
                });
            });
        }
    }

    private static void strings(JsonNode values) {
        if (!values.isArray() || values.isEmpty() || values.size() > 50) throw invalid("策略字段需要非空字符串数组");
        values.forEach(value -> {
            if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > 256) {
                throw invalid("策略列表存在无效的字符串");
            }
        });
    }

    private static JsonPointer pointer(String value) {
        if (!value.startsWith("/") || value.length() > 256 || value.matches(".*~(?![01]).*")) {
            throw invalid("规则路径需要非根 JSON Pointer");
        }
        return JsonPointer.compile(value);
    }

    private static int number(JsonNode rule, String field, int min, int max, int fallback) {
        JsonNode value = rule.get(field);
        if (value == null) return fallback;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < min || value.intValue() > max) {
            throw invalid(field + " 超出策略允许的整数范围");
        }
        return value.intValue();
    }

    public Rules current(String tenant, UUID tool) {
        JsonNode abac = null;
        JsonNode quota = null;
        JsonNode redaction = null;
        Approval approval = null;
        Retry retry = null;
        for (var row : store.activePolicies(tenant, tool)) {
            JsonNode rule = contracts.decode(row.rule());
            try {
                validate(row.kind(), rule);
                if (rule == null || rule.isNull()) throw invalid("生效策略为空");
            } catch (GatewayException ex) {
                throw new GatewayException(POLICY_REQUIRED, "工具存在未迁移或无效的策略");
            }
            switch (row.kind()) {
                case "ABAC" -> abac = rule;
                case "QUOTA" -> quota = rule;
                case "REDACTION" -> redaction = rule;
                case "APPROVAL" -> approval = new Approval(row.policyVersion(), rule.path("ttlSeconds").asInt(900));
                case "RETRY" -> retry = new Retry(rule.path("maxAttempts").asInt(1),
                        rule.path("failureThreshold").asInt(5), rule.path("openSeconds").asInt(30));
            }
        }
        return new Rules(abac, quota, redaction, approval, retry);
    }

    public void authorize(Rules rules, GatewayCaller caller, JsonNode arguments) {
        JsonNode abac = rules.abac();
        if (abac == null) return;
        if (abac.has("roles") && !containsAny(abac.get("roles"), caller.roles())
                || abac.has("agents") && !containsAny(abac.get("agents"), List.of(caller.agentId()))
                || abac.has("ownerPointer") && !caller.principalId().equals(arguments.at(abac.get("ownerPointer").textValue()).textValue())) {
            throw denied();
        }
        abac.path("values").fields().forEachRemaining(entry -> {
            JsonNode value = arguments.at(entry.getKey());
            for (JsonNode allowed : entry.getValue()) {
                if (allowed.equals(value)) return;
            }
            throw denied();
        });
    }

    private static boolean containsAny(JsonNode values, List<String> actual) {
        for (JsonNode value : values) {
            if (actual.contains(value.textValue())) return true;
        }
        return false;
    }

    /** 在预占事务中计数。任一窗口超限则回滚整次计数及执行预占。 */
    public void consume(Rules rules, GatewayCaller caller, UUID tool) {
        JsonNode quota = rules.quota();
        if (quota == null) return;
        String scope = quota.path("scope").textValue();
        String subject = "PRINCIPAL".equals(scope) ? caller.principalId() : "";
        consumeWindow(caller, tool, scope, subject, "MINUTE", quota.path("perMinute").asInt());
        consumeWindow(caller, tool, scope, subject, "DAY", quota.path("perDay").asInt());
    }

    private void consumeWindow(GatewayCaller caller, UUID tool, String scope, String subject, String unit, int limit) {
        if (limit > 0 && store.consumeUsage(new GatewayRepository.UsageWindow(caller.tenantId(), tool, scope, subject, unit, limit)) != 1) {
            throw new GatewayException(RATE_LIMITED, "工具调用超过配置的限流或配额");
        }
    }

    public JsonNode redact(Rules rules, JsonNode output) {
        if (rules.redaction() == null) return output;
        JsonNode safe = output.deepCopy();
        rules.redaction().get("remove").forEach(path -> {
            JsonPointer target = pointer(path.textValue());
            JsonNode parent = safe.at(target.head());
            JsonPointer leaf = target.last();
            if (parent instanceof ObjectNode object) {
                object.remove(leaf.getMatchingProperty());
            } else if (parent instanceof ArrayNode array && leaf.mayMatchElement()
                    && leaf.getMatchingIndex() < array.size()) {
                array.set(leaf.getMatchingIndex(), NullNode.instance);
            }
        });
        return safe;
    }

    private static GatewayException invalid(String message) { return new GatewayException(INVALID, message); }
    private static GatewayException denied() { return new GatewayException(FORBIDDEN, "工具请求未通过属性访问策略"); }

    public record Rules(JsonNode abac, JsonNode quota, JsonNode redaction, Approval approval, Retry retry) {}
    public record Approval(int version, int ttlSeconds) {}
    public record Retry(int maxAttempts, int failureThreshold, int openSeconds) {}
}
