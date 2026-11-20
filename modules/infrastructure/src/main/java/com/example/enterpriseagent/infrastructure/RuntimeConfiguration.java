package com.example.enterpriseagent.infrastructure;

import com.example.enterpriseagent.infrastructure.runtime.EventBusMapper;
import com.example.enterpriseagent.infrastructure.runtime.GatewayRunTool;
import com.example.enterpriseagent.infrastructure.runtime.ModelRunPlanner;
import com.example.enterpriseagent.infrastructure.runtime.PostgresRuntimeOperations;
import com.example.enterpriseagent.infrastructure.runtime.GeneralPlannerAdapter;
import com.example.enterpriseagent.infrastructure.runtime.RunMapper;
import com.example.enterpriseagent.infrastructure.runtime.RunWorker;
import com.example.enterpriseagent.infrastructure.runtime.RuntimeEventPump;
import com.example.enterpriseagent.memory.working.CheckpointSaver;
import com.example.enterpriseagent.runtime.RunManager;
import com.example.enterpriseagent.runtime.RuntimeOperations;
import com.example.enterpriseagent.runtime.RunModel.PlannerContext;
import com.example.enterpriseagent.runtime.execution.ReportGraph;
import com.example.enterpriseagent.runtime.planner.RunPlanner;
import com.example.enterpriseagent.runtime.planner.GeneralPlanner;
import com.example.enterpriseagent.skill.SkillRegistry;
import com.example.enterpriseagent.toolgateway.registry.ToolContracts;
import com.example.enterpriseagent.toolgateway.execution.ToolExecutionService;
import com.example.enterpriseagent.toolgateway.registry.ToolRegistry;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import com.example.enterpriseagent.infrastructure.memory.MemoryLoop;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** API 提交与查询。独立 worker Profile 打开 DAG、规划与 Redis 事件消费者。 */
@Configuration(proxyBeanMethods = false)
@Profile("storage & gateway & runtime")
@MapperScan(basePackageClasses = RunMapper.class, annotationClass = Mapper.class)
public class RuntimeConfiguration {
    @Bean
    RuntimeOperations runtimeOperations(JdbcClient db, PlatformTransactionManager tx, RunManager runs) {
        return new PostgresRuntimeOperations(db, new TransactionTemplate(tx), runs);
    }

    @Bean
    RunManager runManager(RunMapper mapper, PlatformTransactionManager tx, CheckpointSaver saver, ObjectMapper json,
                          RunPlanner planner, ToolExecutionService gateway, GeneralPlanner generalPlanner,
                          ObjectProvider<MemoryLoop> memory) {
        ObjectMapper numbers = json.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        MemoryLoop loop = memory.getIfAvailable();
        return new RunManager(mapper, new TransactionTemplate(tx), saver, numbers, new ReportGraph(numbers),
                planner, new GatewayRunTool(gateway), generalPlanner, loop == null ? ignored -> {} : loop::capture);
    }

    @Bean
    GeneralPlanner generalPlanner(ObjectMapper json, ToolRegistry registry, SkillRegistry skills, ToolContracts contracts,
            PlatformTransactionManager tx,
            @Value("${enterprise.runtime.general-planner.enabled:false}") boolean enabled,
            @Value("${enterprise.runtime.general-planner.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${enterprise.runtime.general-planner.api-key:}") String key,
            @Value("${enterprise.runtime.general-planner.model:deepseek-flash}") String model) {
        if (!enabled) return new GeneralPlanner((actor, policy) -> List.of(), (actor, ir) -> null, null);
        if (key.isBlank()) throw new IllegalArgumentException("启用通用规划需要模型凭证");
        var builder = OpenAiChatModel.builder().apiKey(key).modelName(model).baseUrl(baseUrl)
                .timeout(Duration.ofSeconds(18)).maxRetries(0).maxTokens(2_048).temperature(0.0)
                .responseFormat("json_object");
        if ("api.deepseek.com".equalsIgnoreCase(URI.create(baseUrl).getHost())) {
            builder.customParameters(Map.of("thinking", Map.of("type", "disabled")));
        }
        var adapter = new GeneralPlannerAdapter(registry, skills, contracts, new TransactionTemplate(tx), builder.build(), json);
        return new GeneralPlanner(adapter::candidates, adapter::boundSkill, adapter::propose);
    }

    @Bean
    RunPlanner runPlanner(ObjectMapper json, @Value("${enterprise.runtime.planner.enabled:false}") boolean enabled,
            @Value("${enterprise.runtime.planner.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${enterprise.runtime.planner.api-key:}") String key,
            @Value("${enterprise.runtime.planner.model:deepseek-flash}") String model) {
        if (!enabled) {
            return new RunPlanner() {
                @Override public List<String> chooseDimensions(PlannerContext context) { return context.dimensions(); }
                @Override public String version() { return "deterministic-sales-v1"; }
            };
        }
        if (key.isBlank()) throw new IllegalArgumentException("启用长任务模型规划需要模型凭证");
        var builder = OpenAiChatModel.builder().apiKey(key).modelName(model).baseUrl(baseUrl)
                .timeout(Duration.ofSeconds(18)).maxRetries(0).maxTokens(320).temperature(0.0)
                .responseFormat("json_object");
        if ("api.deepseek.com".equalsIgnoreCase(URI.create(baseUrl).getHost())) {
            builder.customParameters(Map.of("thinking", Map.of("type", "disabled")));
        }
        return new ModelRunPlanner(builder.build(), json);
    }

    @Bean(destroyMethod = "close")
    @Profile("worker")
    RunWorker runWorker(RunManager manager, @Value("${enterprise.runtime.lease-seconds:45}") int leaseSeconds) {
        return new RunWorker(manager, leaseSeconds);
    }

    @Bean
    @Profile("worker")
    RuntimeEventPump runtimeEventPump(EventBusMapper mapper, StringRedisTemplate redis, PlatformTransactionManager tx) {
        return new RuntimeEventPump(mapper, redis, new TransactionTemplate(tx));
    }
}
