package com.example.enterpriseagent.infrastructure;

import com.example.enterpriseagent.infrastructure.runtime.PostgresMonitorLoop;
import com.example.enterpriseagent.infrastructure.runtime.PostgresMonitorLoop.Binding;
import com.example.enterpriseagent.toolgateway.execution.ToolExecutionService;
import com.example.enterpriseagent.toolgateway.registry.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Configuration(proxyBeanMethods = false)
@Profile("storage & gateway & runtime")
@EnableConfigurationProperties(MonitorConfiguration.Settings.class)
public class MonitorConfiguration {
    @Bean
    PostgresMonitorLoop monitorLoop(JdbcClient db, PlatformTransactionManager manager, ObjectMapper json,
                                    ToolExecutionService gateway, ToolRegistry tools, Settings settings) {
        var savepoint = new TransactionTemplate(manager);
        savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        return new PostgresMonitorLoop(db, new TransactionTemplate(manager), savepoint, json, gateway, tools,
                settings.readOnly() == null ? List.of() : settings.readOnly());
    }

    @Bean
    @Profile("worker")
    MonitorWorker monitorWorker(PostgresMonitorLoop monitor) { return new MonitorWorker(monitor); }

    static final class MonitorWorker {
        private final PostgresMonitorLoop monitor;
        MonitorWorker(PostgresMonitorLoop monitor) { this.monitor = monitor; }

        @Scheduled(fixedDelayString = "${enterprise.monitor.poll-ms:500}")
        public void poll() { monitor.advance(); }
    }

    @ConfigurationProperties("enterprise.monitor")
    public record Settings(List<Binding> readOnly) {}
}
