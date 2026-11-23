package com.example.enterpriseagent.infrastructure.objectstore;

import com.example.enterpriseagent.runtime.RunModel.Artifact;
import com.example.enterpriseagent.runtime.execution.ArtifactBodyReader.Unavailable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

/** 私有 S3 Bucket 的正文访问，调用方始终用可信租户和 Run 生成对象键。 */
public final class S3ArtifactObjects implements AutoCloseable {
    // PostgreSQL 的 JSONB 文本呈现会加分隔空白，给 256 KiB 的节点产物留出转换空间。
    static final int MAX_OBJECT_BYTES = 524_288;
    private final S3Client client;
    private final String bucket;

    public S3ArtifactObjects(String endpoint, String bucket, String region, String accessKey, String secretKey) {
        URI uri = URI.create(endpoint);
        if (uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || bucket.isBlank() || region.isBlank() || accessKey.isBlank() || secretKey.isBlank()) {
            throw new IllegalArgumentException("启用产物对象存储需要有效端点、Bucket、区域和访问凭证");
        }
        client = S3Client.builder().endpointOverride(uri).region(Region.of(region)).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(2))
                        .socketTimeout(Duration.ofSeconds(3)))
                .overrideConfiguration(config -> config.apiCallAttemptTimeout(Duration.ofSeconds(4))
                        .apiCallTimeout(Duration.ofSeconds(8)))
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED).build();
        this.bucket = bucket;
    }

    void write(String key, byte[] body) {
        client.putObject(request -> request.bucket(bucket).key(key).contentType("application/json"), RequestBody.fromBytes(body));
    }

    public String restore(String tenant, UUID run, Artifact artifact) {
        String key = key(tenant, run, artifact.id());
        long expected = artifact.sizeBytes();
        if (!reference(key).equals(artifact.contentRef()) || expected < 1 || expected > MAX_OBJECT_BYTES) {
            throw new Unavailable();
        }
        try (var stream = client.getObject(request -> request.bucket(bucket).key(key))) {
            byte[] bytes = stream.readNBytes((int) expected + 1);
            if (bytes.length != expected || !digest(bytes).equals(artifact.contentDigest())) throw new Unavailable();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (SdkException | IOException ex) {
            throw new Unavailable();
        }
    }

    static String key(String tenant, UUID run, UUID artifact) {
        return "runtime/v1/" + digest(tenant.getBytes(StandardCharsets.UTF_8)) + "/" + run + "/" + artifact + ".json";
    }

    static String reference(String key) { return "obj:" + key; }

    static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Override
    public void close() { client.close(); }
}
