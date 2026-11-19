package com.example.enterpriseagent.toolgateway.adapter;

import com.example.enterpriseagent.toolgateway.GatewayException;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 从已装配的协议适配器选择执行通道。每种协议只允许注册一个实现。 */
public final class ToolAdapterFactory {
    private final Map<String, ToolAdapter> byProtocol;

    public ToolAdapterFactory(List<ToolAdapter> adapters) {
        byProtocol = adapters.stream().collect(Collectors.toUnmodifiableMap(
                adapter -> adapter.protocol().name(), Function.identity()));
    }

    public ToolAdapter require(String protocol) {
        ToolAdapter adapter = protocol == null ? null : byProtocol.get(protocol);
        if (adapter == null) {
            throw new GatewayException(GatewayException.Code.INVALID, "工具协议尚未接入");
        }
        return adapter;
    }

    public boolean configured(String tenant, String protocol, String targetRef) {
        ToolAdapter adapter = protocol == null ? null : byProtocol.get(protocol);
        return adapter != null && targetRef != null && adapter.configuredForTenant(tenant, targetRef);
    }

    public List<String> targetRefs(String tenant, String protocol) {
        ToolAdapter adapter = require(protocol);
        return adapter.targetRefs().stream().filter(ref -> adapter.configuredForTenant(tenant, ref)).sorted().toList();
    }
}
