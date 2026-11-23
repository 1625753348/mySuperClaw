package com.example.enterpriseagent.infrastructure;

import com.example.enterpriseagent.infrastructure.runtime.MonitorDeliveryQueue;
import com.example.enterpriseagent.infrastructure.runtime.MonitorWorkflowDispatch;
import com.example.enterpriseagent.infrastructure.runtime.PostgresMonitorLoop;
import com.example.enterpriseagent.infrastructure.runtime.PostgresMonitorLoop.Binding;
import com.example.enterpriseagent.runtime.RunManager;
import com.example.enterpriseagent.skill.SkillRegistry;
import com.example.enterpriseagent.toolgateway.execution.ToolExecutionService;
import com.example.enterpriseagent.toolgateway.registry.ToolContracts;
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
                                    ToolExecutionService gateway, ToolRegistry tools, Settings settings,
                                    MonitorDeliveryQueue deliveryQueue, MonitorWorkflowDispatch workflows) {
        return new PostgresMonitorLoop(db, new TransactionTemplate(manager), nested(manager), json, gateway, tools,
                settings.readOnly() == null ? List.of() : settings.readOnly(), deliveryQueue, workflows);
    }

    @Bean
    MonitorWorkflowDispatch monitorWorkflowDispatch(JdbcClient db, PlatformTransactionManager manager, ObjectMapper json,
                                                     SkillRegistry skills, ToolContracts contracts, RunManager runs,
                                                     Settings settings) {
        return new MonitorWorkflowDispatch(db, new TransactionTemplate(manager), nested(manager), json, skills, contracts,
                runs, settings.workflowRemediation() == null ? List.of() : settings.workflowRemediation());
    }

    @Bean
    MonitorDeliveryQueue monitorDeliveryQueue(JdbcClient db, PlatformTransactionManager manager, ObjectMapper json,
                                              ToolExecutionService gateway, ToolRegistry tools, Settings settings) {
        return new MonitorDeliveryQueue(db, new TransactionTemplate(manager), nested(manager), json, gateway, tools,
                settings.notification() == null ? List.of() : settings.notification(),
                settings.remediation() == null ? List.of() : settings.remediation());
    }

    private static TransactionTemplate nested(PlatformTransactionManager manager) {
        var savepoint = new TransactionTemplate(manager);
        savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        return savepoint;
    }

    @Bean
    @Profile("worker")
    MonitorWorker monitorWorker(PostgresMonitorLoop monitor, MonitorDeliveryQueue deliveries, MonitorWorkflowDispatch workflows) {
        return new MonitorWorker(monitor, deliveries, workflows);
    }

    static final class MonitorWorker {
        private final PostgresMonitorLoop monitor;
        private final MonitorDeliveryQueue deliveries;
        private final MonitorWorkflowDispatch workflows;
        MonitorWorker(PostgresMonitorLoop monitor, MonitorDeliveryQueue deliveries, MonitorWorkflowDispatch workflows) {
            this.monitor = monitor;
            this.deliveries = deliveries;
            this.workflows = workflows;
        }

        @Scheduled(fixedDelayString = "${enterprise.monitor.poll-ms:500}")
        public void poll() { monitor.advance(); }

        @Scheduled(fixedDelayString = "${enterprise.monitor.poll-ms:500}")
        public void dispatch() { deliveries.advance(); }

        @Scheduled(fixedDelayString = "${enterprise.monitor.poll-ms:500}")
        public void startApprovedWorkflow() { workflows.advance(); }
    }

    @ConfigurationProperties("enterprise.monitor")
    public record Settings(List<Binding> readOnly, List<MonitorDeliveryQueue.Binding> notification,
                           List<MonitorDeliveryQueue.Binding> remediation,
                           List<MonitorWorkflowDispatch.Binding> workflowRemediation) {}
}
