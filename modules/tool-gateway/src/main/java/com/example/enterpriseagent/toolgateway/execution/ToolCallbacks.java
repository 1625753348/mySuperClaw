package com.example.enterpriseagent.toolgateway.execution;

import com.example.enterpriseagent.toolgateway.GatewayException;
import com.example.enterpriseagent.toolgateway.adapter.ToolAdapter;
import com.example.enterpriseagent.toolgateway.registry.ToolContracts;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.support.TransactionOperations;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static com.example.enterpriseagent.toolgateway.GatewayException.Code.*;

/** 验证供应商签名并保存密文结果，执行状态由原有租约 Worker 结算。 */
public final class ToolCallbacks {
    public static final int MAX_BODY_BYTES = 131_072;
    private static final int MAX_CLOCK_SKEW_SECONDS = 300;
    private final Map<String, Provider> providers;
    private final Store store;
    private final ToolJobCipher cipher;
    private final ToolContracts contracts;
    private final ObjectMapper json;
    private final TransactionOperations tx;
    private final Clock clock;

    public ToolCallbacks(Map<String, Provider> providers, Store store, ToolJobCipher cipher, ToolContracts contracts,
                         ObjectMapper json, TransactionOperations tx, Clock clock) {
        this.providers = Map.copyOf(providers);
        this.store = store;
        this.cipher = cipher;
        this.contracts = contracts;
        this.json = json;
        this.tx = tx;
        this.clock = clock;
        if (!this.providers.isEmpty() && !cipher.enabled()) throw new IllegalArgumentException("异步回调需要配置恢复密钥");
        this.providers.forEach((name, provider) -> {
            if (!name.matches("[a-z][a-z0-9-]{0,63}")) throw new IllegalArgumentException("异步供应商标识无效");
        });
    }

    public void accept(String name, String timestamp, String signature, byte[] raw) {
        Provider provider = providers.get(name);
        if (provider == null) throw new GatewayException(NOT_FOUND, "异步供应商未注册");
        if (raw.length > MAX_BODY_BYTES) throw invalid();
        verify(provider, name, timestamp, signature, raw);
        JsonNode body;
        try {
            body = json.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);
        } catch (IOException ex) {
            throw invalid();
        }
        if (body == null || !body.isObject()) throw invalid();
        UUID execution = parseId(body.path("executionId").textValue());
        String id = body.path("jobId").textValue();
        if (id == null || !id.matches("[A-Za-z0-9._-]{1,128}")) throw invalid();
        String payload = terminalPayload(body);
        tx.executeWithoutResult(ignored -> persist(provider, name, execution, id, payload));
    }

    private void persist(Provider provider, String name, UUID execution, String id, String payload) {
        String tenant = provider.tenant();
        Target target = store.lockTarget(tenant, execution);
        if (target == null || !provider.targetRef().equals(target.targetRef())) throw notFound();
        // 如果回调早于 202 回执入库，供应商需要用同一结果重发。
        if (target.externalJobId() == null) throw new GatewayException(CONFLICT, "异步回执尚未持久化");
        if (!id.equals(target.externalJobId())) throw notFound();
        String digest = ToolContracts.digest(payload);
        var previous = store.identity(tenant, execution);
        if (previous != null) {
            if (name.equals(previous.provider()) && digest.equals(previous.digest())) return;
            throw new GatewayException(CONFLICT, "异步执行已有不同的回调结果");
        }
        if (!"WAITING_EXTERNAL".equals(target.status())) return;
        if (target.expired()) throw new GatewayException(CONFLICT, "异步执行已超过结算期限");
        String sealed = cipher.seal(tenant, execution, "callback", payload);
        store.insert(new Receipt(tenant, execution, name, digest, sealed));
        store.wake(tenant, execution);
    }

    ToolAdapter.Outcome outcome(String tenant, UUID execution) {
        String sealed = store.payload(tenant, execution);
        if (sealed == null) return null;
        JsonNode body;
        try {
            body = json.readTree(cipher.open(tenant, execution, "callback", sealed));
        } catch (IOException | IllegalStateException ex) {
            return new ToolAdapter.Outcome("UNKNOWN", null, "ASYNC_CALLBACK_UNREADABLE");
        }
        return "SUCCEEDED".equals(body.path("status").textValue())
                ? new ToolAdapter.Outcome("SUCCEEDED", body.path("result").toString(), null)
                : new ToolAdapter.Outcome("FAILED", null, "ASYNC_PROVIDER_FAILED");
    }

    private String terminalPayload(JsonNode body) {
        String status = body.path("status").textValue();
        if ("SUCCEEDED".equals(status) && body.has("result")) {
            return contracts.arguments(json.createObjectNode().put("status", status).set("result", body.get("result")));
        }
        if ("FAILED".equals(status) && !body.hasNonNull("result")) return json.createObjectNode().put("status", status).toString();
        throw invalid();
    }

    private void verify(Provider provider, String name, String timestamp, String signature, byte[] raw) {
        if (timestamp == null || !timestamp.matches("[0-9]{1,12}") || signature == null
                || !signature.matches("sha256=[a-fA-F0-9]{64}")) throw unauthorized();
        long now = clock.instant().getEpochSecond();
        long sent = Long.parseLong(timestamp);
        if (sent < now - MAX_CLOCK_SKEW_SECONDS || sent > now + MAX_CLOCK_SKEW_SECONDS) throw unauthorized();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(provider.signingKey()), "HmacSHA256"));
            mac.update((name + "\n" + timestamp + "\n").getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(mac.doFinal(raw), HexFormat.of().parseHex(signature.substring(7)))) throw unauthorized();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("JDK 不支持 HMAC-SHA256");
        }
    }

    private static UUID parseId(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw invalid();
        }
        return UUID.fromString(value);
    }

    private static GatewayException unauthorized() { return new GatewayException(FORBIDDEN, "异步回调签名无效"); }
    private static GatewayException invalid() { return new GatewayException(INVALID, "异步回调格式无效"); }
    private static GatewayException notFound() { return new GatewayException(NOT_FOUND, "关联的异步执行不存在"); }

    public interface Store {
        Target lockTarget(String tenant, UUID execution);
        Identity identity(String tenant, UUID execution);
        void insert(Receipt receipt);
        void wake(String tenant, UUID execution);
        String payload(String tenant, UUID execution);
    }

    public record Provider(String tenant, String targetRef, String signingKey) {
        public Provider {
            if (tenant == null || tenant.isBlank() || targetRef == null || targetRef.isBlank()
                    || signingKey == null || signingKey.length() > 128) {
                throw new IllegalArgumentException("异步供应商需要绑定租户、受控端点和签名凭证");
            }
            int length;
            try {
                length = Base64.getDecoder().decode(signingKey).length;
            } catch (IllegalArgumentException ex) {
                length = 0;
            }
            if (length < 32) throw new IllegalArgumentException("异步回调签名凭证须为至少 32 字节的 Base64");
        }

        @Override public String toString() { return "[managed async callback provider]"; }
    }
    public record Target(String targetRef, String externalJobId, String status, boolean expired) {}
    public record Identity(String provider, String digest) {}
    public record Receipt(String tenant, UUID execution, String provider, String digest, String encrypted) {
        @Override public String toString() { return "[encrypted async callback]"; }
    }
}
