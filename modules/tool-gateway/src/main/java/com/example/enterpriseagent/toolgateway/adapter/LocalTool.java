package com.example.enterpriseagent.toolgateway.adapter;

import com.fasterxml.jackson.databind.JsonNode;

/** 企业自有 Java 工具通过 Spring Bean 显式注册，执行时可读取可信调用上下文。 */
public interface LocalTool {
    String name();

    JsonNode run(JsonNode arguments, ToolAdapter.Call context) throws Exception;
}
