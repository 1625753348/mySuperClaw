package com.example.enterpriseagent.toolgateway.execution;

import com.example.enterpriseagent.toolgateway.GatewayException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/** 恢复数据与结果带租户、执行 ID 和用途的认证数据，跨行置换时无法解密。 */
public final class ToolJobCipher {
    private static final int NONCE_BYTES = 12;
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public ToolJobCipher(String encoded) {
        if (encoded.isBlank()) {
            key = null;
            return;
        }
        byte[] raw = Base64.getDecoder().decode(encoded);
        if (raw.length != 32) throw new IllegalArgumentException("工具恢复密钥必须为 Base64 编码的 32 字节");
        key = new SecretKeySpec(raw, "AES");
    }

    public boolean enabled() { return key != null; }

    public String seal(String tenant, UUID id, String purpose, String value) {
        byte[] nonce = random.generateSeed(NONCE_BYTES);
        try {
            byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, tenant, id, purpose, nonce)
                    .doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length)
                    .put(nonce).put(encrypted).array());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("工具恢复数据加密失败");
        }
    }

    public String open(String tenant, UUID id, String purpose, String value) {
        try {
            ByteBuffer data = ByteBuffer.wrap(Base64.getDecoder().decode(value));
            byte[] nonce = new byte[NONCE_BYTES];
            data.get(nonce);
            byte[] encrypted = new byte[data.remaining()];
            data.get(encrypted);
            return new String(cipher(Cipher.DECRYPT_MODE, tenant, id, purpose, nonce).doFinal(encrypted),
                    StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException | java.nio.BufferUnderflowException ex) {
            throw new IllegalStateException("工具恢复数据无法验证");
        }
    }

    private Cipher cipher(int mode, String tenant, UUID id, String purpose, byte[] nonce) throws GeneralSecurityException {
        if (!enabled()) throw new GatewayException(GatewayException.Code.POLICY_REQUIRED, "网关尚未配置异步恢复密钥");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD((tenant.length() + ":" + tenant + ":" + id + ":" + purpose).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
