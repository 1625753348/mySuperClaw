package com.example.enterpriseagent.toolgateway.execution;

import com.example.enterpriseagent.toolgateway.GatewayException;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 以租户和引用为边界解析部署托管凭证。工具目录、审计和参数均不承载明文。 */
public final class ToolSecrets {
    private static final Set<String> BEARER_PROTOCOLS = Set.of("HTTP", "MCP", "GRPC", "ASYNC");
    private final Map<String, Entry> entries;

    public ToolSecrets(Map<String, Entry> entries) {
        this.entries = Map.copyOf(entries);
        this.entries.forEach((ref, entry) -> {
            if (!ref.matches("[a-zA-Z0-9_-]{1,80}") || entry.allowedTenants() == null || entry.allowedTenants().isEmpty()
                    || entry.allowedTargets() == null || entry.allowedTargets().isEmpty()
                    || entry.bearerToken() == null || !entry.bearerToken().matches("[A-Za-z0-9._~+/-]+=*")) {
                throw new IllegalArgumentException("工具凭证配置需要有效引用、租户、协议端点与 Bearer 令牌");
            }
        });
    }

    public Credential resolve(String tenant, String protocol, String target, String ref) {
        if (ref == null) return null;
        if (!permits(tenant, protocol, target, ref)) {
            throw new GatewayException(GatewayException.Code.POLICY_REQUIRED, "工具凭证不可用或未授权");
        }
        return new Credential(entries.get(ref).bearerToken());
    }

    public boolean permits(String tenant, String protocol, String target, String ref) {
        if (ref == null) return true;
        Entry entry = entries.get(ref);
        return BEARER_PROTOCOLS.contains(protocol) && entry != null && entry.allowedTenants().contains(tenant)
                && entry.allowedTargets().contains(protocol + ":" + target);
    }

    public List<String> available(String tenant, String protocol) {
        if (!BEARER_PROTOCOLS.contains(protocol)) return List.of();
        return entries.keySet().stream().filter(ref -> entries.get(ref).allowedTenants().contains(tenant)
                        && entries.get(ref).allowedTargets().stream().anyMatch(target -> target.startsWith(protocol + ":")))
                .sorted().toList();
    }

    public record Entry(String bearerToken, Set<String> allowedTenants, Set<String> allowedTargets) {
        @Override public String toString() { return "[managed secret entry]"; }
    }

    public record Credential(String bearerToken) {
        @Override public String toString() { return "[managed credential]"; }
    }
}
