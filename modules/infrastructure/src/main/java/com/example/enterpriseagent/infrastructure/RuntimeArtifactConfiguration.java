package com.example.enterpriseagent.infrastructure;

import com.example.enterpriseagent.infrastructure.objectstore.ArtifactOffloadWorker;
import com.example.enterpriseagent.infrastructure.objectstore.S3ArtifactObjects;
import com.example.enterpriseagent.runtime.execution.ArtifactBodyReader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
@Profile("storage & gateway & runtime")
public class RuntimeArtifactConfiguration {
    @Bean
    public ArtifactBodyReader artifactBodyReader(ObjectProvider<S3ArtifactObjects> remote) {
        return (tenant, run, artifact) -> {
            if (artifact.contentRef().startsWith("pg:") && artifact.body() != null) return artifact.body();
            S3ArtifactObjects objects = remote.getIfAvailable();
            if (objects == null) throw new ArtifactBodyReader.Unavailable();
            return objects.restore(tenant, run, artifact);
        };
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "enterprise.artifacts.object-storage", name = "enabled", havingValue = "true")
    S3ArtifactObjects artifactObjects(
            @Value("${enterprise.artifacts.object-storage.endpoint:}") String endpoint,
            @Value("${enterprise.artifacts.object-storage.bucket:}") String bucket,
            @Value("${enterprise.artifacts.object-storage.region:us-east-1}") String region,
            @Value("${enterprise.artifacts.object-storage.access-key:}") String accessKey,
            @Value("${enterprise.artifacts.object-storage.secret-key:}") String secretKey) {
        return new S3ArtifactObjects(endpoint, bucket, region, accessKey, secretKey);
    }

    @Bean
    @Profile("worker")
    @ConditionalOnProperty(prefix = "enterprise.artifacts.object-storage", name = "enabled", havingValue = "true")
    ArtifactOffloadWorker artifactOffloadWorker(JdbcClient db, S3ArtifactObjects objects,
            @Value("${enterprise.artifacts.object-storage.threshold-bytes:65536}") int threshold,
            @Value("${enterprise.artifacts.object-storage.offload-enabled:true}") boolean offloadEnabled) {
        return new ArtifactOffloadWorker(db, objects, threshold, offloadEnabled);
    }
}
