package com.example.enterpriseagent.infrastructure.intent;

import com.example.enterpriseagent.intent.routing.IntentRoutingPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** 启动时选择已部署的规则版本。版本重名或选择不存在的版本时拒绝启动。 */
@ConfigurationProperties("enterprise.intent.routing")
public record IntentRoutingSettings(String activeVersion, List<IntentRoutingPolicy> versions, Embedding embedding) {
    public IntentRoutingSettings {
        versions = versions == null ? List.of() : List.copyOf(versions);
        if (versions.stream().map(IntentRoutingPolicy::version).distinct().count() != versions.size()) {
            throw new IllegalArgumentException("意图分级策略版本重复");
        }
    }

    public IntentRoutingPolicy active() {
        if (activeVersion == null || activeVersion.isBlank()) return null;
        return versions.stream().filter(version -> activeVersion.equals(version.version())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到启用的意图分级策略版本：" + activeVersion));
    }

    public record Embedding(boolean enabled, String baseUrl, String apiKey, String model) {
        @Override public String toString() { return "Intent routing embedding settings"; }
    }
}
