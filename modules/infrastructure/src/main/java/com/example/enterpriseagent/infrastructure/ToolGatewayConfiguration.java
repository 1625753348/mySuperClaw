package com.example.enterpriseagent.infrastructure;

import com.example.enterpriseagent.infrastructure.gateway.GatewayMapper;
import com.example.enterpriseagent.infrastructure.gateway.DatabaseToolAdapter;
import com.example.enterpriseagent.infrastructure.gateway.DatabaseToolBinding;
import com.example.enterpriseagent.infrastructure.gateway.GrpcToolAdapter;
import com.example.enterpriseagent.infrastructure.gateway.GrpcToolBinding;
import com.example.enterpriseagent.infrastructure.gateway.MilvusToolSemanticSearch;
import com.example.enterpriseagent.infrastructure.gateway.PostgresToolCallbacks;
import com.example.enterpriseagent.infrastructure.gateway.RedisToolCircuit;
import com.example.enterpriseagent.infrastructure.gateway.ToolIndexConsumer;
import com.example.enterpriseagent.infrastructure.gateway.VaultSecretReader;
import com.example.enterpriseagent.infrastructure.milvus.MilvusMemoryIndex;
import com.example.enterpriseagent.toolgateway.adapter.HttpToolAdapter;
import com.example.enterpriseagent.toolgateway.adapter.AsyncToolAdapter;
import com.example.enterpriseagent.toolgateway.adapter.LocalTool;
import com.example.enterpriseagent.toolgateway.adapter.LocalToolAdapter;
import com.example.enterpriseagent.toolgateway.adapter.McpToolAdapter;
import com.example.enterpriseagent.toolgateway.adapter.ToolAdapter;
import com.example.enterpriseagent.toolgateway.adapter.ToolAdapterFactory;
import com.example.enterpriseagent.toolgateway.discovery.ToolDiscovery;
import com.example.enterpriseagent.toolgateway.discovery.ToolSemanticSearch;
import com.example.enterpriseagent.toolgateway.execution.ToolExecutionService;
import com.example.enterpriseagent.toolgateway.execution.ToolCallbacks;
import com.example.enterpriseagent.toolgateway.execution.ToolJobCipher;
import com.example.enterpriseagent.toolgateway.execution.ToolJobService;
import com.example.enterpriseagent.toolgateway.execution.ToolPolicyService;
import com.example.enterpriseagent.toolgateway.execution.ToolResilience;
import com.example.enterpriseagent.toolgateway.execution.ToolSecrets;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Clock;
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
    ToolAdapter httpToolAdapter(Endpoints endpoints, ObjectMapper json) {
        return new HttpToolAdapter(endpoints.targets() == null ? Map.of() : endpoints.targets(),
                endpoints.httpReadTargets() == null ? Map.of() : endpoints.httpReadTargets(), json);
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
    DatabaseToolAdapter databaseToolAdapter(ObjectMapper json, ObjectProvider<DatabaseToolBinding> bindings) {
        return new DatabaseToolAdapter(json, bindings.orderedStream().toList());
    }

    @Bean
    AsyncToolAdapter asyncToolAdapter(ObjectMapper json, Endpoints endpoints) {
        return new AsyncToolAdapter(json, endpoints.asyncTargets() == null ? Map.of() : endpoints.asyncTargets());
    }

    @Bean
    ToolSecrets toolSecrets(Endpoints endpoints, ObjectMapper json) {
        return new ToolSecrets(endpoints.secrets() == null ? Map.of() : endpoints.secrets(),
                endpoints.vault() == null ? null : new VaultSecretReader(endpoints.vault(), json));
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
                                ToolSecrets secrets, ObjectMapper json) {
        return new ToolDiscovery(mapper, semantic, adapters, secrets, json);
    }

    @Bean
    ToolIndexConsumer toolIndexConsumer(GatewayMapper mapper, ToolDiscovery discovery, ToolSemanticSearch semantic) {
        return new ToolIndexConsumer(mapper, discovery, semantic);
    }

    @Bean
    ToolRegistry toolRegistry(GatewayMapper mapper, PlatformTransactionManager tx,
                              ToolContracts contracts, ToolAdapterFactory adapters, ToolDiscovery discovery,
                              ToolPolicyService policies, ToolSecrets secrets, ObjectMapper json) {
        return new ToolRegistry(mapper, localTransaction(tx), contracts, adapters, discovery, policies, secrets, json);
    }

    @Bean
    ToolPolicyService toolPolicyService(GatewayMapper mapper, ToolContracts contracts) {
        return new ToolPolicyService(mapper, contracts);
    }

    @Bean
    UsageCleanup toolUsageCleanup(GatewayMapper mapper) {
        return new UsageCleanup(mapper);
    }

    record UsageCleanup(GatewayMapper mapper) {
        @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
        public void trim() {
            mapper.cleanupUsage();
            mapper.cleanupJobs();
        }
    }

    @Bean
    ToolJobCipher toolJobCipher(@Value("${enterprise.gateway.job-key:}") String key) {
        return new ToolJobCipher(key);
    }

    @Bean
    ToolCallbacks toolCallbacks(JdbcClient db, ToolJobCipher cipher, ToolContracts contracts, ObjectMapper json, Endpoints endpoints,
                                PlatformTransactionManager tx) {
        var providers = endpoints.callbacks() == null ? Map.<String, ToolCallbacks.Provider>of() : endpoints.callbacks();
        var targets = endpoints.asyncTargets() == null ? Map.<String, AsyncToolAdapter.Binding>of() : endpoints.asyncTargets();
        for (var provider : providers.values()) {
            var binding = targets.get(provider.targetRef());
            if (binding == null || !binding.allowedTenants().contains(provider.tenant())) {
                throw new IllegalArgumentException("异步供应商的租户和端点必须在已配置的异步目标名单内");
            }
        }
        return new ToolCallbacks(providers, new PostgresToolCallbacks(db), cipher, contracts, json, localTransaction(tx), Clock.systemUTC());
    }

    @Bean
    ToolJobService toolJobService(GatewayMapper mapper, ToolRegistry registry, ToolContracts contracts,
                                  ToolPolicyService policies, ToolExecutionService execution, ToolJobCipher cipher,
                                  PlatformTransactionManager tx, ToolCallbacks callbacks) {
        return new ToolJobService(mapper, registry, contracts, policies, execution, cipher, localTransaction(tx), callbacks);
    }

    @Bean
    JobSchedule toolJobScheduler(ToolJobService jobs) {
        return new JobSchedule(jobs);
    }

    @Bean
    ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(3);
        scheduler.setThreadNamePrefix("tool-gateway-");
        return scheduler;
    }

    record JobSchedule(ToolJobService jobs) {
        @Scheduled(fixedDelayString = "${enterprise.gateway.job-interval-ms:1500}")
        public void dispatch() {
            jobs.processOne();
        }

        @Scheduled(fixedDelay = 30_000)
        public void recover() {
            jobs.recoverExpired();
        }
    }

    @Bean
    ToolExecutionService toolExecutionService(GatewayMapper mapper, ToolRegistry registry,
                                              ToolContracts contracts, ToolAdapterFactory adapters, ToolPolicyService policies,
                                              ToolSecrets secrets, ToolJobCipher cipher, ObjectMapper json,
                                              PlatformTransactionManager tx, StringRedisTemplate redis) {
        return new ToolExecutionService(mapper, registry, contracts, adapters, policies, secrets,
                new ToolResilience(new RedisToolCircuit(redis)),
                cipher, json, localTransaction(tx));
    }

    private static TransactionTemplate localTransaction(PlatformTransactionManager manager) {
        var transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction;
    }

    @ConfigurationProperties(prefix = "enterprise.gateway")
    public record Endpoints(Map<String, URI> targets, Map<String, McpToolAdapter.Server> mcpServers,
                            Map<String, McpToolAdapter.Binding> mcpTools, Map<String, ToolSecrets.Entry> secrets,
                            Map<String, AsyncToolAdapter.Binding> asyncTargets, VaultSecretReader.Settings vault,
                            Map<String, ToolCallbacks.Provider> callbacks,
                            Map<String, HttpToolAdapter.ReadTarget> httpReadTargets) {}
}
