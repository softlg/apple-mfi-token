package com.galaplat.mfi.apple;

import okhttp3.OkHttpClient;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.StringReader;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * 由 PEM 文本装配双向 TLS。租户证书是运行时传入的，因此不依赖任何全局配置或环境变量里的证书。
 *
 * <p>私钥同时接受 PKCS#8（{@code BEGIN PRIVATE KEY}）与 PKCS#1（{@code BEGIN RSA PRIVATE KEY}）：
 * 厂商从苹果拿到的常常是 PKCS#1，MES 旧实现的 PKCS#1 兜底分支把 PKCS#1 字节当成 PrivateKeyInfo 解析，
 * 实际必抛异常。这里用 BouncyCastle 的 PEMParser 按标签分派，两种格式走真正不同的分支。
 */
public final class MfiSslContextBuilder {

    /** 解析出的可用材料：证书链 + 私钥 + 已装配好的 OkHttp 客户端所需的三件套 */
    public record Material(X509Certificate leaf, X509Certificate[] chain, PrivateKey privateKey,
                           SSLContext sslContext, X509TrustManager trustManager) {
    }

    private static final String KEY_ALIAS = "mfi-client";

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private MfiSslContextBuilder() {
    }

    /**
     * 装配客户端证书。
     *
     * @param certPem         X.509 证书 PEM，允许带中间证书链
     * @param keyPem          PKCS#8 或 PKCS#1 私钥 PEM
     * @param trustedCaPem    额外只信任的根/CA PEM；为空则用 JDK 默认信任库（访问真实苹果端点）
     */
    public static Material build(String certPem, String keyPem, String trustedCaPem)
            throws IOException, GeneralSecurityException {
        List<X509Certificate> certificates = readCertificates(certPem);
        PrivateKey privateKey = readPrivateKey(keyPem);
        X509Certificate leaf = certificates.get(0);

        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        keyStore.setKeyEntry(KEY_ALIAS, privateKey, new char[0], certificates.toArray(new X509Certificate[0]));

        KeyManagerFactory keyManagerFactory =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, new char[0]);

        X509TrustManager trustManager = trustedCaPem == null || trustedCaPem.isBlank()
                ? defaultTrustManager()
                : singleCaTrustManager(trustedCaPem);

        SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
        sslContext.init(keyManagerFactory.getKeyManagers(), new javax.net.ssl.TrustManager[]{trustManager},
                new SecureRandom());
        return new Material(leaf, certificates.toArray(new X509Certificate[0]), privateKey, sslContext, trustManager);
    }

    /**
     * 用材料建 OkHttp 客户端。读取超时给到 5 分钟：token 文件是大体积 xlsx，默认 10 秒必然截断。
     */
    public static OkHttpClient createHttpClient(Material material) {
        return new OkHttpClient.Builder()
                .sslSocketFactory(material.sslContext().getSocketFactory(), material.trustManager())
                .connectTimeout(java.time.Duration.ofSeconds(15))
                .readTimeout(java.time.Duration.ofMinutes(5))
                .writeTimeout(java.time.Duration.ofMinutes(2))
                .retryOnConnectionFailure(true)
                .build();
    }

    /**
     * 校验私钥与证书是否配对。厂商最常犯的错是把 A 的证书配上 B 的私钥：JSSE 在 KeyManager 装配阶段
     * 遇到不配对只会静默丢弃该条目，握手时才报"证书不可用"，排查成本极高，所以在入库前主动验一次。
     *
     * @return 配对返回 true；不配对或算法不支持返回 false，不抛异常
     */
    public static boolean keyMatchesCertificate(X509Certificate certificate, PrivateKey privateKey) {
        String keyAlgorithm = privateKey.getAlgorithm();
        String signatureAlgorithm = switch (keyAlgorithm) {
            case "RSA" -> "SHA256withRSA";
            case "EC", "ECDSA" -> "SHA256withECDSA";
            default -> null;
        };
        if (signatureAlgorithm == null) {
            return false;
        }
        try {
            byte[] probe = "mfi-key-pair-check".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Signature signer = Signature.getInstance(signatureAlgorithm, BouncyCastleProvider.PROVIDER_NAME);
            signer.initSign(privateKey);
            signer.update(probe);
            byte[] signed = signer.sign();
            Signature verifier = Signature.getInstance(signatureAlgorithm, BouncyCastleProvider.PROVIDER_NAME);
            verifier.initVerify(certificate.getPublicKey());
            verifier.update(probe);
            return verifier.verify(signed);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    /**
     * 解析证书链，按出现顺序返回，第一张是叶子证书。链上有多张时全部进 KeyStore，
     * 否则苹果侧只看叶子会校验不过。
     */
    private static List<X509Certificate> readCertificates(String certPem) throws IOException, CertificateException {
        List<X509Certificate> certificates = new ArrayList<>();
        JcaX509CertificateConverter converter = new JcaX509CertificateConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME);
        try (PEMParser parser = new PEMParser(new StringReader(certPem))) {
            Object object;
            while ((object = parser.readObject()) != null) {
                if (object instanceof X509CertificateHolder holder) {
                    certificates.add(converter.getCertificate(holder));
                }
            }
        }
        if (certificates.isEmpty()) {
            throw new CertificateException("PEM 里没有解析到 X.509 证书");
        }
        return certificates;
    }

    /**
     * 私钥解析：PKCS#1 走 PEMKeyPair 分支，PKCS#8 走 PrivateKeyInfo 分支。
     */
    private static PrivateKey readPrivateKey(String keyPem) throws IOException, GeneralSecurityException {
        Object object;
        try (PEMParser parser = new PEMParser(new StringReader(keyPem))) {
            object = parser.readObject();
        }
        if (object == null) {
            throw new GeneralSecurityException("PEM 里没有解析到私钥，请确认包含 -----BEGIN ... PRIVATE KEY----- 头尾");
        }
        JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME);
        if (object instanceof PEMKeyPair keyPair) {
            return converter.getKeyPair(keyPair).getPrivate();
        }
        if (object instanceof PrivateKeyInfo info) {
            return converter.getPrivateKey(info);
        }
        throw new GeneralSecurityException("不支持的私钥类型: " + object.getClass().getName());
    }

    private static X509TrustManager defaultTrustManager() throws GeneralSecurityException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        return asX509(factory);
    }

    /**
     * 只信任指定 CA：给本地自签桩与自建代理用，避免把测试 CA 塞进 JDK 全局信任库。
     */
    private static X509TrustManager singleCaTrustManager(String caPem)
            throws IOException, GeneralSecurityException, CertificateException {
        List<X509Certificate> caCertificates = readCertificates(caPem);
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        for (int i = 0; i < caCertificates.size(); i++) {
            trustStore.setCertificateEntry("mfi-trusted-ca-" + i, caCertificates.get(i));
        }
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trustStore);
        return asX509(factory);
    }

    private static X509TrustManager asX509(TrustManagerFactory factory) {
        for (javax.net.ssl.TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) {
                return x509;
            }
        }
        throw new IllegalStateException("默认 TrustManagerFactory 未提供 X509TrustManager");
    }

}
