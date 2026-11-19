package com.example.enterpriseagent.toolgateway.registry;

import com.example.enterpriseagent.toolgateway.GatewayException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;

/** 统一处理 JSON 契约及幂等指纹。仅允许本地 JSON Pointer 引用。 */
public final class ToolContracts {

    private final ObjectMapper json;
    private final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    public ToolContracts(ObjectMapper json) {
        this.json = json;
    }

    public String definition(JsonNode schema) {
        if (schema == null || !schema.isObject() || schema.toString().length() > 32_768) {
            throw invalid("工具 Schema 必须是大小不超过 32 KiB 的 JSON 对象");
        }
        checkReferences(schema);
        try {
            factory.getSchema(schema).initializeValidators();
        } catch (RuntimeException ex) {
            throw invalid("无法编译工具 Schema");
        }
        return canonical(schema).toString();
    }

    private void checkReferences(JsonNode node) {
        if (node.isObject()) {
            // 远程引用及可改变引用基址的元数据由平台控制。
            for (String key : new String[]{"$ref", "$dynamicRef", "$recursiveRef"}) {
                if (node.has(key) && !(node.get(key).asText().equals("#") || node.get(key).asText().startsWith("#/"))) {
                    throw invalid("Schema 引用必须指向当前文档");
                }
            }
            if (node.has("$id") || node.has("$vocabulary") || (node.has("$schema")
                    && !"https://json-schema.org/draft/2020-12/schema".equals(node.get("$schema").asText()))) {
                throw invalid("Schema 元数据或方言不在允许范围内");
            }
        }
        node.forEach(this::checkReferences);
    }

    public JsonNode decode(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException ex) {
            throw invalid("工具 JSON 格式无效");
        }
    }

    public boolean matches(String schema, JsonNode value) {
        return schema == null || factory.getSchema(decode(schema)).validate(value).isEmpty();
    }

    public String arguments(JsonNode input) {
        if (input == null || !input.isObject()) {
            throw invalid("arguments 必须是 JSON 对象");
        }
        String encoded = canonical(input).toString();
        if (encoded.getBytes(StandardCharsets.UTF_8).length > 131_072) {
            throw invalid("工具参数超过 128 KiB");
        }
        return encoded;
    }

    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            var fields = new ArrayList<String>();
            node.fieldNames().forEachRemaining(fields::add);
            fields.sort(Comparator.naturalOrder());
            var sorted = json.createObjectNode();
            fields.forEach(field -> sorted.set(field, canonical(node.get(field))));
            return sorted;
        }
        if (node.isArray()) {
            var result = json.createArrayNode();
            node.forEach(value -> result.add(canonical(value)));
            return result;
        }
        return node;
    }

    public static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JDK 不支持 SHA-256", ex);
        }
    }

    private static GatewayException invalid(String message) {
        return new GatewayException(GatewayException.Code.INVALID, message);
    }
}
