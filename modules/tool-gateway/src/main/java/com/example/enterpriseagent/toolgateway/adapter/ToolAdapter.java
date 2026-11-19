package com.example.enterpriseagent.toolgateway.adapter;

import com.example.enterpriseagent.toolgateway.GatewayCaller;

import java.util.Set;

/** 调用平台已配置目标。适配器负责协议与网络结果归类。 */
public interface ToolAdapter {

    Protocol protocol();
    Set<String> targetRefs();

    boolean configured(String targetRef);

    default boolean configuredForTenant(String tenantId, String targetRef) {
        return configured(targetRef);
    }

    Outcome invoke(Call call);

    enum Protocol { HTTP, LOCAL, MCP, GRPC }

    record Call(String targetRef, int timeoutMs, String arguments, String operationKey, String traceId,
                GatewayCaller caller) {}
    record Outcome(String status, String body, String errorCode) {}
}
