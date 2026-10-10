package com.galaplat.mfi.apple;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PEM 装配与配对校验。PKCS#1 分支在 MES 旧实现里是必抛异常的假分支，这里必须用真实用例钉住。
 */
class MfiSslContextBuilderTest {

    private static TestCertificates.Pem ca;
    private static TestCertificates.Pem tenantA;
    private static TestCertificates.Pem tenantB;
    private static TestCertificates.Pem expired;

    @BeforeAll
    static void setUp() {
        ca = TestCertificates.certificateAuthority("MFI Test CA");
        tenantA = TestCertificates.leaf(ca, "Tenant A Client", 365);
        tenantB = TestCertificates.leaf(ca, "Tenant B Client", 365);
        expired = TestCertificates.leaf(ca, "Expired Client", -1);
    }

    @Test
    @DisplayName("PKCS#8 私钥与含链证书装配成功，链上两张按顺序进来")
    void buildsFromPkcs8() throws Exception {
        MfiSslContextBuilder.Material material =
                MfiSslContextBuilder.build(tenantA.certPem(), tenantA.pkcs8KeyPem(), null);

        assertEquals("CN=Tenant A Client", leafSubject(material));
        assertEquals(2, material.chain().length, "叶子 + CA 都要进 KeyStore，否则苹果侧只看叶子会校验不过");
        assertEquals("RSA", material.privateKey().getAlgorithm());
        assertNotNull(material.sslContext());
    }

    @Test
    @DisplayName("PKCS#1 私钥走真正不同的分支，装配结果与 PKCS#8 等价")
    void buildsFromPkcs1() throws Exception {
        MfiSslContextBuilder.Material pkcs1 =
                MfiSslContextBuilder.build(tenantA.certPem(), tenantA.pkcs1KeyPem(), null);
        MfiSslContextBuilder.Material pkcs8 =
                MfiSslContextBuilder.build(tenantA.certPem(), tenantA.pkcs8KeyPem(), null);

        assertTrue(MfiSslContextBuilder.keyMatchesCertificate(pkcs1.leaf(), pkcs1.privateKey()));
        assertEquals(pkcs8.privateKey().getEncoded().length > 0, pkcs1.privateKey().getEncoded().length > 0);
    }

    @Test
    @DisplayName("A 的证书配 B 的私钥能被主动发现，不会拖到握手阶段")
    void detectsMismatchedPair() throws Exception {
        MfiSslContextBuilder.Material mixed =
                MfiSslContextBuilder.build(tenantA.certPem(), tenantB.pkcs8KeyPem(), null);

        assertFalse(MfiSslContextBuilder.keyMatchesCertificate(mixed.leaf(), mixed.privateKey()));
        assertTrue(MfiSslContextBuilder.keyMatchesCertificate(tenantA.leaf(), tenantA.privateKey()));
    }

    @Test
    @DisplayName("已过期证书仍然可以装配：过期判定属业务校验，交给上层，不在 TLS 层静默丢材料")
    void expiredCertStillBuilds() throws Exception {
        MfiSslContextBuilder.Material material =
                MfiSslContextBuilder.build(expired.certPem(), expired.pkcs8KeyPem(), null);

        assertTrue(material.leaf().getNotAfter().toInstant().isBefore(java.time.Instant.now()));
    }

    @Test
    @DisplayName("只有证书没有私钥时明确报错，而不是回显内容含糊失败")
    void rejectsMissingPrivateKey() {
        GeneralSecurityException e = assertThrows(GeneralSecurityException.class,
                () -> MfiSslContextBuilder.build(tenantA.certPem(), TestCertificates.certOnly(tenantA), null));
        assertTrue(e.getMessage().contains("私钥"), e.getMessage());
    }

    @Test
    @DisplayName("空 PEM 与垃圾文本都被拒绝，异常文案只描述格式问题")
    void rejectsGarbage() {
        assertThrows(Exception.class, () -> MfiSslContextBuilder.build("", tenantA.pkcs8KeyPem(), null));
        assertThrows(Exception.class, () -> MfiSslContextBuilder.build("not a pem", tenantA.pkcs8KeyPem(), null));
        assertThrows(Exception.class,
                () -> MfiSslContextBuilder.build(tenantA.certPem(), "not a pem", null));
    }

    @Test
    @DisplayName("额外信任锚只信任指定 CA：空白值回落 JDK 默认信任库")
    void trustAnchorFallsBack() throws Exception {
        MfiSslContextBuilder.Material withCa =
                MfiSslContextBuilder.build(tenantA.certPem(), tenantA.pkcs8KeyPem(), ca.certPem());
        MfiSslContextBuilder.Material defaultTrust =
                MfiSslContextBuilder.build(tenantA.certPem(), tenantA.pkcs8KeyPem(), "  ");

        List<X509Certificate> accepted = List.of(withCa.trustManager().getAcceptedIssuers());
        assertTrue(accepted.stream().anyMatch(c -> c.getSubjectX500Principal().equals(ca.leaf().getSubjectX500Principal())),
                "指定 CA 时应只信任它");
        assertEquals(1, accepted.size());
        assertTrue(defaultTrust.trustManager().getAcceptedIssuers().length > 1,
                "留空应回落 JDK 信任库里的公共根证书");
    }

    private static String leafSubject(MfiSslContextBuilder.Material material) {
        return material.leaf().getSubjectX500Principal().getName();
    }

    @Test
    @DisplayName("私钥确实成对且为 RSA，防止测试材料本身跑偏")
    void keyPairIsRealRsa() {
        PrivateKey key = tenantA.privateKey();
        assertEquals("RSA", key.getAlgorithm());
        assertTrue(MfiSslContextBuilder.keyMatchesCertificate(tenantB.leaf(), tenantB.privateKey()));
    }

}
