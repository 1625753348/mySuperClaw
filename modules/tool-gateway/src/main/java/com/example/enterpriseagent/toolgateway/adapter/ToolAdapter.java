package com.example.enterpriseagent.toolgateway.adapter;

/** 调用平台已配置目标。适配器负责协议与网络结果归类。 */
public interface ToolAdapter {

    boolean configured(String targetRef);

    Outcome invoke(Call call);

    record Call(String targetRef, int timeoutMs, String arguments, String operationKey, String traceId) {}
    record Outcome(String status, String body, String errorCode) {}
}
