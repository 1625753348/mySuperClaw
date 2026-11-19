package com.example.enterpriseagent.infrastructure;

import com.example.enterpriseagent.infrastructure.gateway.GatewayMapper;
import com.example.enterpriseagent.infrastructure.gateway.GrpcToolAdapter;
import com.example.enterpriseagent.infrastructure.gateway.GrpcToolBinding;
import com.example.enterpriseagent.infrastructure.gateway.MilvusToolSemanticSearch;
import com.example.enterpriseagent.infrastructure.gateway.ToolIndexConsumer;
import com.example.enterpriseagent.infrastructure.milvus.MilvusMemoryIndex;
import com.example.enterpriseagent.toolgateway.adapter.HttpToolAdapter;
import com.example.enterpriseagent.toolgateway.adapter.LocalTool;
import com.example.enterpriseagent.toolgateway.adapter.LocalToolAdapter;
import com.example.enterpriseagent.toolgateway.adapter.McpToolAdapter;
import com.example.enterpriseagent.toolgateway.adapter.ToolAdapter;
import com.example.enterpriseagent.toolgateway.adapter.ToolAdapterFactory;
import com.example.enterpriseagent.toolgateway.discovery.ToolDiscovery;
import com.example.enterpriseagent.toolgateway.discovery.ToolSemanticSearch;
import com.example.enterpriseagent.toolgateway.execution.ToolExecutionService;
import com.example.enterpriseagent.toolgateway.registry.ToolContracts;
import com.example.enterpriseagent.toolgateway.registry.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.milvus.v2.client.MilvusClientV2;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.util.List;
import java.util.Map;

/** 以独立 Profile 装配控制面、执行服务与数据库接口。 */
@Configuration(proxyBeanMethods = false)
@Profile("storage & gateway")
@MapperScan(basePackageClasses = GatewayMapper.class, annotationClass = Mapper.class)
@EnableConfigurationProperties(ToolGatewayConfiguration.Endpoints.class)
@EnableScheduling
public class ToolGatewayConfiguration {

    @Bean
    ToolContracts toolContracts(ObjectMapper json) {
        return new ToolContracts(json);
    }

    @Bean
    ToolAdapter httpToolAdapter(Endpoints endpoints) {
        return new HttpToolAdapter(endpoints.targets() == null ? Map.of() : endpoints.targets());
    }

    @Bean(destroyMethod = "close")
    LocalToolAdapter localToolAdapter(ObjectMapper json, ObjectProvider<LocalTool> tools) {
        return new LocalToolAdapter(json, tools.orderedStream().toList());
    }

    @Bean
    McpToolAdapter mcpToolAdapter(ObjectMapper json, ToolContracts contracts, Endpoints endpoints) {
        return new McpToolAdapter(json, contracts, endpoints.mcpServers() == null ? Map.of() : endpoints.mcpServers(),
                endpoints.mcpTools() == null ? Map.of() : endpoints.mcpTools());
    }

    @Bean
    GrpcToolAdapter grpcToolAdapter(ObjectProvider<GrpcToolBinding> bindings) {
        return new GrpcToolAdapter(bindings.orderedStream().toList());
    }

    @Bean
    ToolAdapterFactory toolAdapterFactory(List<ToolAdapter> adapters) {
        return new ToolAdapterFactory(adapters);
    }

    @Bean
    ToolSemanticSearch toolSemanticSearch(ObjectMapper json, MilvusClientV2 client,
            @Value("${enterprise.gateway.discovery.semantic-enabled}") boolean enabled,
            @Value("${enterprise.gateway.discovery.embedding-uri}") URI uri,
            @Value("${enterprise.gateway.discovery.api-key}") String apiKey,
            @Value("${enterprise.gateway.discovery.model}") String model,
            @Value("${enterprise.gateway.discovery.dimension}") int dimension,
            @Value("${enterprise.gateway.discovery.collection}") String collection,
            @Value("${enterprise.gateway.discovery.minimum-cosine}") double minimumCosine) {
        return new MilvusToolSemanticSearch(enabled, json, new MilvusMemoryIndex(client, collection, dimension),
                uri, apiKey, model, dimension, minimumCosine);
    }

    @Bean
    ToolDiscovery toolDiscovery(GatewayMapper mapper, ToolSemanticSearch semantic, ToolAdapterFactory adapters,
                                ObjectMapper json) {
        return new ToolDiscovery(mapper, semantic, adapters, json);
    }

    @Bean
    ToolIndexConsumer toolIndexConsumer(GatewayMapper mapper, ToolDiscovery discovery, ToolSemanticSearch semantic) {
        return new ToolIndexConsumer(mapper, discovery, semantic);
    }

    @Bean
    ToolRegistry toolRegistry(GatewayMapper mapper, PlatformTransactionManager tx,
                              ToolContracts contracts, ToolAdapterFactory adapters, ToolDiscovery discovery, ObjectMapper json) {
        return new ToolRegistry(mapper, localTransaction(tx), contracts, adapters, discovery, json);
    }

    @Bean
    ToolExecutionService toolExecutionService(GatewayMapper mapper, ToolRegistry registry,
                                              ToolContracts contracts, ToolAdapterFactory adapters,
                                              PlatformTransactionManager tx) {
        return new ToolExecutionService(mapper, registry, contracts, adapters, localTransaction(tx));
    }

    private static TransactionTemplate localTransaction(PlatformTransactionManager manager) {
        var transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction;
    }

    @ConfigurationProperties(prefix = "enterprise.gateway")
    public record Endpoints(Map<String, URI> targets, Map<String, McpToolAdapter.Server> mcpServers,
                            Map<String, McpToolAdapter.Binding> mcpTools) {}
}
