package com.galaplat.mfi.credential.infrastructure;

import com.galaplat.mfi.credential.config.CredentialProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AES-256-GCM 静态加密的核心性质：可用、防跨租户替换、防跨字段替换、防篡改、支持轮换。
 */
class CredentialCipherTest {

    private static final String KEY_V1 = base64Key();
    private static final String KEY_V0 = base64Key();

    @Test
    @DisplayName("同租户同字段可解，且同一明文两次加密得到不同密文")
    void roundTripWithRandomIv() {
        CredentialCipher cipher = cipher(Map.of("v1", KEY_V1), "v1");
        String first = cipher.encrypt(7L, CredentialCipher.FIELD_CERT, "-----BEGIN CERTIFICATE-----abc");
        String second = cipher.encrypt(7L, CredentialCipher.FIELD_CERT, "-----BEGIN CERTIFICATE-----abc");

        assertEquals("-----BEGIN CERTIFICATE-----abc",
                cipher.decrypt(7L, CredentialCipher.FIELD_CERT, "v1", first));
        assertNotEquals(first, second, "IV 每条随机，相同明文不得产生相同密文");
    }

    @Test
    @DisplayName("把 A 租户的密文搬到 B 租户解不开，AAD 绑住了租户")
    void ciphertextIsTenantBound() {
        CredentialCipher cipher = cipher(Map.of("v1", KEY_V1), "v1");
        String certOfTenantA = cipher.encrypt(1L, CredentialCipher.FIELD_CERT, "tenant-a-material");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> cipher.decrypt(2L, CredentialCipher.FIELD_CERT, "v1", certOfTenantA));
        assertTrue(e.getMessage().contains("解密失败"), e.getMessage());
    }

    @Test
    @DisplayName("证书密文当私钥密文用同样解不开，AAD 也绑住了字段")
    void ciphertextIsFieldBound() {
        CredentialCipher cipher = cipher(Map.of("v1", KEY_V1), "v1");
        String certCipher = cipher.encrypt(1L, CredentialCipher.FIELD_CERT, "cert-material");

        assertThrows(IllegalStateException.class,
                () -> cipher.decrypt(1L, CredentialCipher.FIELD_KEY, "v1", certCipher));
    }

    @Test
    @DisplayName("密文被篡改后解密失败，GCM 的认证标签生效")
    void tamperedCiphertextRejected() {
        CredentialCipher cipher = cipher(Map.of("v1", KEY_V1), "v1");
        String original = cipher.encrypt(1L, CredentialCipher.FIELD_KEY, "private-key-material");
        byte[] raw = Base64.getDecoder().decode(original);
        raw[raw.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(raw);

        assertThrows(IllegalStateException.class,
                () -> cipher.decrypt(1L, CredentialCipher.FIELD_KEY, "v1", tampered));
    }

    @Test
    @DisplayName("轮换后历史密文仍按自带 keyId 可解，新写入用新密钥")
    void supportsKeyRotation() {
        Map<String, String> ring = new LinkedHashMap<>();
        ring.put("v0", KEY_V0);
        ring.put("v1", KEY_V1);
        CredentialCipher withOld = cipher(Map.of("v0", KEY_V0), "v0");
        String legacy = withOld.encrypt(3L, CredentialCipher.FIELD_CERT, "legacy-material");
        assertEquals("v0", withOld.activeKeyId());

        CredentialCipher rotated = cipher(ring, "v1");
        assertEquals("legacy-material", rotated.decrypt(3L, CredentialCipher.FIELD_CERT, "v0", legacy));
        assertEquals("v1", rotated.activeKeyId());
    }

    @Test
    @DisplayName("用错密钥解不开：轮换时把旧密钥从环里删掉会让历史凭证失效")
    void wrongKeyRejected() {
        String legacy = cipher(Map.of("v0", KEY_V0), "v0")
                .encrypt(3L, CredentialCipher.FIELD_CERT, "legacy-material");
        CredentialCipher onlyNew = cipher(Map.of("v1", KEY_V1), "v1");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> onlyNew.decrypt(3L, CredentialCipher.FIELD_CERT, "v0", legacy));
        assertTrue(e.getMessage().contains("不在密钥环"), e.getMessage());
    }

    @Test
    @DisplayName("未配置主密钥直接拒绝启动，不允许悄悄不加密")
    void rejectsMissingKeyRing() {
        assertThrows(IllegalStateException.class, () -> cipher(Map.of(), "v1"));
        assertThrows(IllegalStateException.class, () -> cipher(Map.of("v1", KEY_V1), "v9"));
        assertThrows(IllegalStateException.class, () -> cipher(Map.of("v1", "not-base64!!"), "v1"));
        assertThrows(IllegalStateException.class, () -> cipher(Map.of("v1", shortKey()), "v1"));
    }

    @Test
    @DisplayName("留空的轮换槽位被忽略，不影响未启用轮换的部署启动")
    void ignoresBlankKeySlots() {
        Map<String, String> ring = new LinkedHashMap<>();
        ring.put("v1", KEY_V1);
        ring.put("v0", "");
        CredentialCipher cipher = cipher(ring, "v1");

        String encrypted = cipher.encrypt(1L, CredentialCipher.FIELD_CERT, "material");
        assertEquals("material", cipher.decrypt(1L, CredentialCipher.FIELD_CERT, "v1", encrypted));
    }

    private static CredentialCipher cipher(Map<String, String> keys, String activeKeyId) {
        CredentialProperties properties = new CredentialProperties();
        properties.setKeys(new LinkedHashMap<>(keys));
        properties.setActiveKeyId(activeKeyId);
        return new CredentialCipher(properties);
    }

    private static String base64Key() {
        byte[] material = new byte[32];
        new java.security.SecureRandom().nextBytes(material);
        return Base64.getEncoder().encodeToString(material);
    }

    private static String shortKey() {
        return Base64.getEncoder().encodeToString(new byte[16]);
    }

}
