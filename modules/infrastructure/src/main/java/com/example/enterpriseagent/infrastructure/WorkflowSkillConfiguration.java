package com.example.enterpriseagent.infrastructure;

import com.example.enterpriseagent.infrastructure.workflow.PostgresSkillRegistry;
import com.example.enterpriseagent.skill.SkillRegistry;
import com.example.enterpriseagent.toolgateway.registry.ToolContracts;
import com.example.enterpriseagent.toolgateway.registry.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@Profile("storage & gateway")
public class WorkflowSkillConfiguration {
    @Bean
    SkillRegistry skillRegistry(JdbcClient db, PlatformTransactionManager tx, ToolRegistry tools,
                               ToolContracts contracts, ObjectMapper json) {
        return new PostgresSkillRegistry(db, new TransactionTemplate(tx), tools, contracts, json);
    }
}
