package com.galaplat.mfi.apple;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;

/**
 * 测试内自造证书材料：不依赖 openssl，也不读磁盘文件。
 *
 * <p>P2 的验收点只能在真实握手上成立（PKCS#1 兜底分支、证书链装配、对端接受或拒绝客户端证书），
 * 用内存签发的自签 CA 即可跑通，且不需要任何苹果侧材料。
 */
final class TestCertificates {

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private static final BigInteger SERIAL = BigInteger.valueOf(System.nanoTime());

    /**
     * 一张证书与它的私钥。
     *
     * @param certPem  叶子证书 PEM，已由 CA 签发时自带 CA 段（即完整链）
     * @param pkcs8KeyPem {@code -----BEGIN PRIVATE KEY-----}
     * @param pkcs1KeyPem {@code -----BEGIN RSA PRIVATE KEY-----}，厂商常见交付格式
     */
    record Pem(X509Certificate leaf, PrivateKey privateKey, String certPem, String pkcs8KeyPem, String pkcs1KeyPem) {
    }

    private TestCertificates() {
    }

    static Pem certificateAuthority(String commonName) {
        try {
            KeyPair keyPair = newKeyPair();
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    new X500Name("CN=" + commonName),
                    SERIAL,
                    Date.from(Instant.now().minus(1, ChronoUnit.DAYS)),
                    Date.from(Instant.now().plus(3650, ChronoUnit.DAYS)),
                    new X500Name("CN=" + commonName),
                    keyPair.getPublic());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
            return toPem(toCertificate(builder.build(signer(keyPair.getPrivate()))), keyPair.getPrivate(), null);
        } catch (Exception e) {
            throw new IllegalStateException("生成测试 CA 失败", e);
        }
    }

    /**
     * 由 CA 签发叶子证书。
     *
     * @param sanDns             主机名；服务端证书必须含 localhost，否则 OkHttp 主机名校验会拒
     * @param notAfterOffsetDays 负数即签出已过期证书，用于验证入库前的有效期校验
     */
    static Pem leaf(Pem ca, String commonName, int notAfterOffsetDays, String... sanDns) {
        try {
            KeyPair keyPair = newKeyPair();
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    new X500Name(ca.leaf().getSubjectX500Principal().getName()),
                    SERIAL.add(BigInteger.ONE),
                    Date.from(Instant.now().minus(1, ChronoUnit.DAYS)),
                    Date.from(Instant.now().plus(notAfterOffsetDays, ChronoUnit.DAYS)),
                    new X500Name("CN=" + commonName),
                    keyPair.getPublic());
            if (sanDns.length > 0) {
                GeneralName[] names = new GeneralName[sanDns.length];
                for (int i = 0; i < sanDns.length; i++) {
                    names[i] = new GeneralName(GeneralName.dNSName, sanDns[i]);
                }
                builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(names));
            }
            return toPem(toCertificate(builder.build(signer(ca.privateKey()))), keyPair.getPrivate(), ca.certPem());
        } catch (Exception e) {
            throw new IllegalStateException("签发测试叶子证书失败", e);
        }
    }

    /** 只保留证书段，用于构造"只给证书不给私钥"这类输入 */
    static String certOnly(Pem pem) {
        int end = pem.certPem().indexOf("-----END CERTIFICATE-----");
        return pem.certPem().substring(0, end + "-----END CERTIFICATE-----".length()) + "\n";
    }

    private static KeyPair newKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME);
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static ContentSigner signer(PrivateKey key) throws Exception {
        return new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(key);
    }

    private static X509Certificate toCertificate(X509CertificateHolder holder) throws Exception {
        return new JcaX509CertificateConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .getCertificate(holder);
    }

    private static Pem toPem(X509Certificate leaf, PrivateKey privateKey, String caPem) throws Exception {
        String certPem = encode("CERTIFICATE", leaf.getEncoded());
        String pkcs8 = encode("PRIVATE KEY", privateKey.getEncoded());
        // PKCS#1：剥掉 PKCS#8 外层的 AlgorithmIdentifier，只留 RSAPrivateKey 的 DER
        PrivateKeyInfo info = PrivateKeyInfo.getInstance(privateKey.getEncoded());
        String pkcs1 = encode("RSA PRIVATE KEY", info.parsePrivateKey().toASN1Primitive().getEncoded());
        return new Pem(leaf, privateKey, certPem + (caPem == null ? "" : caPem), pkcs8, pkcs1);
    }

    private static String encode(String label, byte[] der) {
        String base64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + base64 + "\n-----END " + label + "-----\n";
    }

    /** 仅为链式调用保留：JcaX509v3CertificateBuilder 的 addExtension 返回自身，包装一下可读性更好 */
    private record X509v3BuilderHolder(JcaX509v3CertificateBuilder builder) {
    }

}
