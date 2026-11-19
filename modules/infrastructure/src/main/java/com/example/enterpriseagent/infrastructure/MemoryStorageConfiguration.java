package com.example.enterpriseagent.infrastructure;

import com.example.enterpriseagent.infrastructure.milvus.MilvusMemoryIndex;
import com.example.enterpriseagent.infrastructure.persistence.MemoryMapper;
import com.example.enterpriseagent.infrastructure.persistence.PostgresCheckpointSaver;
import com.example.enterpriseagent.infrastructure.redis.RedisSessionStore;
import com.example.enterpriseagent.memory.conversation.ConversationMemory;
import com.example.enterpriseagent.memory.episode.EpisodicMemory;
import com.example.enterpriseagent.memory.profile.ProfileMemory;
import com.example.enterpriseagent.memory.semantic.SemanticMemory;
import com.example.enterpriseagent.memory.session.SessionMemory;
import com.example.enterpriseagent.memory.store.MemoryStore;
import com.example.enterpriseagent.memory.store.MemoryVectorIndex;
import com.example.enterpriseagent.memory.working.CheckpointSaver;
import com.example.enterpriseagent.memory.working.WorkingMemory;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/** storage Profile 将记忆服务装配到真实存储，业务模块只依赖端口。 */
@Configuration(proxyBeanMethods = false)
@Profile("storage")
@MapperScan(basePackageClasses = MemoryMapper.class, annotationClass = Mapper.class)
public class MemoryStorageConfiguration {

    @Bean(destroyMethod = "close")
    MilvusClientV2 milvusClient(@Value("${enterprise.milvus.uri}") String uri,
                                @Value("${enterprise.milvus.token}") String token) {
        var config = ConnectConfig.builder().uri(uri).connectTimeoutMs(3_000).rpcDeadlineMs(8_000);
        if (!token.isBlank()) {
            config.token(token);
        }
        return new MilvusClientV2(config.build());
    }

    @Bean
    HealthIndicator milvusHealthIndicator(MilvusClientV2 client) {
        return () -> {
            try {
                client.listCollections();
                return Health.up().build();
            } catch (RuntimeException ex) {
                return Health.down().build();
            }
        };
    }

    @Bean
    MemoryVectorIndex memoryVectorIndex(MilvusClientV2 client,
                                        @Value("${enterprise.milvus.collection}") String collection,
                                        @Value("${enterprise.milvus.dimension}") int dimension) {
        return new MilvusMemoryIndex(client, collection, dimension);
    }

    @Bean
    SessionMemory sessionMemory(StringRedisTemplate redis) {
        return new SessionMemory(new RedisSessionStore(redis));
    }

    @Bean
    ProfileMemory profileMemory(MemoryMapper mapper) {
        return new ProfileMemory(new Store<>(key -> Optional.ofNullable(mapper.profile(key)),
                item -> requireWrite(mapper.saveProfile(item), "用户档案修订号已经变化")));
    }

    @Bean
    EpisodicMemory episodicMemory(MemoryMapper mapper, MemoryVectorIndex index) {
        return new EpisodicMemory(new Store<>(key -> Optional.ofNullable(mapper.episode(key)),
                value -> requireWrite(mapper.saveEpisode(value), "案例来源无效或修订号已经变化")), index);
    }

    @Bean
    SemanticMemory semanticMemory(MemoryMapper mapper, PlatformTransactionManager transactions, MemoryVectorIndex index) {
        TransactionTemplate write = new TransactionTemplate(transactions);
        return new SemanticMemory(new Store<>(key -> Optional.ofNullable(mapper.knowledge(key)),
                publication -> write.executeWithoutResult(ignored -> {
                    requireWrite(mapper.lockDocument(publication), "知识来源键不能在发布时变更");
                    var version = publication.version();
                    if (mapper.lastKnowledgeVersion(version) >= version.version()) {
                        throw new IllegalStateException("新发布版本必须大于历史版本");
                    }
                    mapper.retireKnowledge(version);
                    mapper.publishKnowledge(version);
                    for (SemanticMemory.Chunk chunk : publication.chunks()) {
                        mapper.appendChunk(version, chunk);
                    }
                })), index);
    }

    @Bean
    ConversationMemory conversationMemory(MemoryMapper mapper, PlatformTransactionManager transactions) {
        TransactionTemplate read = new TransactionTemplate(transactions);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        TransactionTemplate write = new TransactionTemplate(transactions);
        return new ConversationMemory(new Store<>(key -> read.execute(ignored -> {
            var summary = Optional.ofNullable(mapper.summary(key));
            long watermark = summary.map(ConversationMemory.Summary::coveredThroughSeq).orElse(0L);
            return new ConversationMemory.Window(summary, mapper.window(key, watermark));
        }), summary -> write.executeWithoutResult(ignored -> {
            Long last = mapper.lockConversation(summary);
            if (last == null || last < summary.coveredThroughSeq()) {
                throw new IllegalStateException("会话代次或摘要覆盖范围无效");
            }
            requireWrite(mapper.appendSummary(summary), "已有更新的会话摘要");
        })));
    }

    @Bean
    CheckpointSaver checkpointSaver(MemoryMapper mapper, PlatformTransactionManager transactions) {
        return new PostgresCheckpointSaver(mapper, transactions);
    }

    @Bean
    WorkingMemory workingMemory(CheckpointSaver saver) {
        return new WorkingMemory(saver);
    }

    private static void requireWrite(int count, String message) {
        if (count != 1) {
            throw new IllegalStateException(message);
        }
    }

    private record Store<K, R, W>(Function<K,R> reader, Consumer<W> writer) implements MemoryStore<K,R,W> {
        @Override
        public R load(K key) {
            return reader.apply(key);
        }

        @Override
        public void save(W value) {
            writer.accept(value);
        }
    }
}
