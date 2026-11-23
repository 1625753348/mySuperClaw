package com.example.enterpriseagent.infrastructure;

import com.example.enterpriseagent.infrastructure.workflow.PostgresSkillRegistry;
import com.example.enterpriseagent.infrastructure.workflow.WorkflowSkillCache;
import com.example.enterpriseagent.skill.SkillRegistry;
import com.example.enterpriseagent.toolgateway.registry.ToolContracts;
import com.example.enterpriseagent.toolgateway.registry.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@Profile("storage & gateway")
public class WorkflowSkillConfiguration {
    @Bean
    WorkflowSkillCache workflowSkillCache(JdbcClient db, StringRedisTemplate redis, ObjectMapper json,
                                          @Value("${enterprise.event-bus.skill-cache-ttl:30m}") Duration ttl) {
        return new WorkflowSkillCache(db, redis, json, ttl);
    }

    @Bean
    SkillRegistry skillRegistry(JdbcClient db, PlatformTransactionManager tx, ToolRegistry tools,
                               ToolContracts contracts, ObjectMapper json, WorkflowSkillCache cache) {
        return new PostgresSkillRegistry(db, new TransactionTemplate(tx), tools, contracts, json, cache);
    }
}
