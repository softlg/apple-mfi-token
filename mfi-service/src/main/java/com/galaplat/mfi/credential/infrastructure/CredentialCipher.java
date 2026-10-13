package com.galaplat.mfi.credential.infrastructure;

import com.galaplat.mfi.credential.config.CredentialProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 租户凭证的静态加密：AES-256-GCM。
 *
 * <p>密文格式 {@code Base64(iv(12B) || ciphertext || gcm_tag(16B))}，IV 每条明文随机生成，
 * 不重复使用。GCM 是认证的，篡改密文或 AAD 不匹配都会解密失败。
 *
 * <p>AAD 绑 {@code tenantId + 字段名}：既防止把 A 租户的密文搬到 B 租户（跨租户密文替换），
 * 也防止把证书密文当私钥密文用。因此密文里没有明文长度之外的任何身份信息，替换成本极低。
 */
@Component
public class CredentialCipher {

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    /** AAD 里的字段名。用常量而不是散落的字符串字面量，避免加密与解密两侧写成不同值导致密文永远解不开 */
    public static final String FIELD_CERT = "cert";
    public static final String FIELD_KEY = "key";

    private final CredentialProperties properties;
    private final Map<String, SecretKeySpec> keyRing = new LinkedHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public CredentialCipher(CredentialProperties properties) {
        this.properties = properties;
        properties.getKeys().forEach((keyId, base64Key) -> {
            // 配置里的历史密钥槽位允许留空（尚未轮换），留空即不参与密钥环；否则会卡住没有轮换过的部署
            if (base64Key == null || base64Key.isBlank()) {
                return;
            }
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(base64Key.trim());
            } catch (IllegalArgumentException e) {
                // 配置写错要在启动时就报清楚是哪一把密钥，而不是抛一个没有上下文的 Base64 异常
                throw new IllegalStateException("mfi.credential.keys[" + keyId + "] 不是合法 Base64", e);
            }
            if (decoded.length != KEY_BYTES) {
                throw new IllegalStateException(
                        "mfi.credential.keys[" + keyId + "] 必须是 256 位（Base64 后 44 字符）AES 密钥");
            }
            keyRing.put(keyId, new SecretKeySpec(decoded, "AES"));
        });
        if (keyRing.isEmpty()) {
            throw new IllegalStateException("未配置任何主密钥：mfi.credential.keys 为空则无法保存租户凭证");
        }
        if (!keyRing.containsKey(properties.getActiveKeyId())) {
            throw new IllegalStateException(
                    "mfi.credential.active-key-id=" + properties.getActiveKeyId() + " 在密钥环里不存在");
        }
    }

    /** 新密文使用的密钥标识 */
    public String activeKeyId() {
        return properties.getActiveKeyId();
    }

    public String encrypt(long tenantId, String field, String plaintext) {
        SecretKeySpec key = keyRing.get(activeKeyId());
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(tenantId, field));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream out = new ByteArrayOutputStream(iv.length + encrypted.length);
            out.write(iv);
            out.write(encrypted);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (GeneralSecurityException | java.io.IOException e) {
            throw new IllegalStateException("凭证加密失败", e);
        }
    }

    /**
     * 按密文自带的密钥标识解密。主密钥轮换后，历史密文仍可解，直到下一次替换凭证才升级成新密钥。
     */
    public String decrypt(long tenantId, String field, String keyId, String cipherText) {
        SecretKeySpec key = keyRing.get(keyId);
        if (key == null) {
            throw new IllegalStateException("密文使用的主密钥 " + keyId + " 已不在密钥环里，无法解密");
        }
        byte[] payload = Base64.getDecoder().decode(cipherText);
        if (payload.length <= IV_LENGTH) {
            throw new IllegalStateException("密文长度非法");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, payload, 0, IV_LENGTH));
            cipher.updateAAD(aad(tenantId, field));
            return new String(cipher.doFinal(payload, IV_LENGTH, payload.length - IV_LENGTH),
                    StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("凭证解密失败：密文被篡改、租户不匹配或主密钥不对", e);
        }
    }

    private static byte[] aad(long tenantId, String field) {
        return ("mfi-credential|" + tenantId + "|" + field).getBytes(StandardCharsets.UTF_8);
    }

}
