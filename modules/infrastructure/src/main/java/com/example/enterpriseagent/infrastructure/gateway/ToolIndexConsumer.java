package com.example.enterpriseagent.infrastructure.gateway;

import com.example.enterpriseagent.toolgateway.discovery.ToolDiscovery;
import com.example.enterpriseagent.toolgateway.discovery.ToolSemanticSearch;
import com.example.enterpriseagent.toolgateway.registry.GatewayRepository;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.UUID;

/** 消费专属 PostgreSQL Outbox 队列。按当前版本重建索引，过时事件也可幂等收敛。 */
public final class ToolIndexConsumer {
    private final String worker = UUID.randomUUID().toString();
    private final GatewayRepository store;
    private final ToolDiscovery discovery;
    private final ToolSemanticSearch semantic;

    public ToolIndexConsumer(GatewayRepository store, ToolDiscovery discovery, ToolSemanticSearch semantic) {
        this.store = store;
        this.discovery = discovery;
        this.semantic = semantic;
    }

    @Scheduled(fixedDelayString = "${enterprise.gateway.discovery.index-interval-ms:1500}")
    public void refresh() {
        if (!semantic.enabled()) {
            return;
        }
        var event = store.claimIndexEvent(worker);
        if (event != null) {
            boolean indexed = discovery.indexPublished(event.tenant(), event.toolId());
            store.finishIndexEvent(new GatewayRepository.IndexReceipt(event.tenant(), event.eventId(), event.epoch(),
                    worker, indexed));
        }
    }
}
