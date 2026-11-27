package com.example.enterpriseagent.infrastructure.skills;

import com.example.enterpriseagent.skill.agent.AgentSkills;
import com.example.enterpriseagent.toolgateway.execution.ToolExecutionService;
import com.example.enterpriseagent.toolgateway.registry.ToolContracts;
import com.example.enterpriseagent.toolgateway.registry.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@Profile("storage & gateway & agent-skills")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(AgentSkillConfiguration.Settings.class)
public class AgentSkillConfiguration {
    @Bean
    AgentSkills agentSkills(Settings settings, JdbcClient db, ToolRegistry registry, ToolContracts contracts,
            ToolExecutionService gateway, ObjectMapper json,
            @Value("${enterprise.agent-skills.model.api-key}") String key,
            @Value("${enterprise.agent-skills.model.base-url}") String baseUrl,
            @Value("${enterprise.agent-skills.model.name}") String name) {
        var catalog = new AgentSkillCatalog(settings.root(), settings.entries());
        if (key.isBlank()) throw new IllegalArgumentException("Agent Skills 需要配置支持 Tool Calling 的模型凭证");
        var builder = OpenAiChatModel.builder().apiKey(key).baseUrl(baseUrl).modelName(name)
                .timeout(Duration.ofSeconds(8)).maxRetries(0).maxTokens(1000).temperature(0.0);
        if ("api.deepseek.com".equalsIgnoreCase(URI.create(baseUrl).getHost())) {
            builder.customParameters(Map.of("thinking", Map.of("type", "disabled")));
        }
        return new AgentSkillRunner(catalog, new AgentSkillRuns(db), registry, contracts, gateway, builder.build(), json);
    }

    @ConfigurationProperties("enterprise.agent-skills")
    public record Settings(Path root, List<AgentSkillCatalog.Entry> entries) {}
}
