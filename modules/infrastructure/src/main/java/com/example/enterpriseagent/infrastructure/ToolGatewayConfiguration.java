package com.example.enterpriseagent.infrastructure;

import com.example.enterpriseagent.infrastructure.gateway.GatewayMapper;
import com.example.enterpriseagent.toolgateway.adapter.HttpToolAdapter;
import com.example.enterpriseagent.toolgateway.adapter.ToolAdapter;
import com.example.enterpriseagent.toolgateway.execution.ToolExecutionService;
import com.example.enterpriseagent.toolgateway.registry.ToolContracts;
import com.example.enterpriseagent.toolgateway.registry.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.util.Map;

/** 以独立 Profile 装配控制面、执行服务与数据库接口。 */
@Configuration(proxyBeanMethods = false)
@Profile("storage & gateway")
@MapperScan(basePackageClasses = GatewayMapper.class, annotationClass = Mapper.class)
@EnableConfigurationProperties(ToolGatewayConfiguration.Endpoints.class)
public class ToolGatewayConfiguration {

    @Bean
    ToolContracts toolContracts(ObjectMapper json) {
        return new ToolContracts(json);
    }

    @Bean
    ToolAdapter httpToolAdapter(Endpoints endpoints) {
        return new HttpToolAdapter(endpoints.targets() == null ? Map.of() : endpoints.targets());
    }

    @Bean
    ToolRegistry toolRegistry(GatewayMapper mapper, PlatformTransactionManager tx,
                              ToolContracts contracts, ToolAdapter adapter, ObjectMapper json) {
        return new ToolRegistry(mapper, localTransaction(tx), contracts, adapter, json);
    }

    @Bean
    ToolExecutionService toolExecutionService(GatewayMapper mapper, ToolRegistry registry,
                                              ToolContracts contracts, ToolAdapter adapter,
                                              PlatformTransactionManager tx) {
        return new ToolExecutionService(mapper, registry, contracts, adapter, localTransaction(tx));
    }

    private static TransactionTemplate localTransaction(PlatformTransactionManager manager) {
        var transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction;
    }

    @ConfigurationProperties(prefix = "enterprise.gateway")
    public record Endpoints(Map<String, URI> targets) {}
}
