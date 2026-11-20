package com.example.enterpriseagent.infrastructure;

import com.example.enterpriseagent.infrastructure.gateway.MilvusToolSemanticSearch;
import com.example.enterpriseagent.infrastructure.memory.MemoryLoop;
import com.example.enterpriseagent.memory.episode.EpisodicMemory;
import com.example.enterpriseagent.memory.profile.ProfileMemory;
import com.example.enterpriseagent.memory.semantic.SemanticMemory;
import com.example.enterpriseagent.memory.store.MemoryVectorIndex;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;

@Configuration(proxyBeanMethods = false)
@Profile("storage & gateway & memory-loop")
public class MemoryLoopConfiguration {
    @Bean
    MemoryLoop memoryLoop(JdbcClient db, PlatformTransactionManager tx, ObjectMapper json, ProfileMemory profiles,
            EpisodicMemory episodes, SemanticMemory semantic, MemoryVectorIndex index,
            @Value("${enterprise.gateway.discovery.embedding-uri}") String embeddingUri,
            @Value("${enterprise.gateway.discovery.api-key}") String key,
            @Value("${enterprise.gateway.discovery.model}") String model,
            @Value("${enterprise.milvus.dimension}") int dimension,
            @Value("${enterprise.memory.minimum-score:0.65}") double threshold) {
        var embeddings = new MilvusToolSemanticSearch(true, json, index, URI.create(embeddingUri), key, model, dimension, threshold);
        return new MemoryLoop(db, new TransactionTemplate(tx), json, profiles, episodes, semantic, index, embeddings::embed, threshold);
    }

    @Bean
    @Profile("worker")
    MemoryIndexWorker memoryIndexWorker(MemoryLoop loop) { return new MemoryIndexWorker(loop); }

    static final class MemoryIndexWorker {
        private final MemoryLoop loop;
        MemoryIndexWorker(MemoryLoop loop) { this.loop = loop; }

        @Scheduled(fixedDelayString = "${enterprise.memory.index-poll-ms:500}")
        public void poll() { loop.indexOne(); }
    }
}
