package com.galaplat.mfi.apple;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.galaplat.mfi.apple.response.MfiResponse;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用 JDK 自带 HttpsServer 起一个要求客户端证书的真实 TLS 服务端，跑通双向握手。
 *
 * <p>这是 P2 的核心验收：苹果接口只认 mTLS，本地不验一次就无法确信"每租户自带证书"成立。
 * 四条用例分别覆盖 PKCS#8 路径、PKCS#1 兜底路径、对端拒收陌生客户端证书、客户端拒收陌生服务端证书。
 */
class MfiMtlsHandshakeTest {

    private final AtomicInteger served = new AtomicInteger();
    private final AtomicReference<String> clientSubject = new AtomicReference<>();

    private HttpsServer server;
    private TestCertificates.Pem ca;
    private TestCertificates.Pem serverCert;
    private TestCertificates.Pem clientCert;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        ca = TestCertificates.certificateAuthority("MFI Test CA");
        serverCert = TestCertificates.leaf(ca, "localhost", 365, "localhost");
        clientCert = TestCertificates.leaf(ca, "MFI Tenant Test Client", 365);
        startStubServer(serverCert);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("PKCS#8 客户端证书完成双向握手并拿到 JSON")
    void pkcs8ClientHandshakeSucceeds() throws IOException {
        MfiResponse response = newClient(clientCert.certPem(), clientCert.pkcs8KeyPem())
                .queryRequestStatus("probe-request");

        assertEquals(200, response.getCode());
        assertEquals("COMPLETE", response.getRequestStatus());
        assertTrue(response.isSuccess(), "无错误字段才算成功");
        assertEquals(1, served.get());
    }

    @Test
    @DisplayName("PKCS#1 客户端证书同样握手成功，兜底分支不是死代码")
    void pkcs1ClientHandshakeSucceeds() throws IOException {
        MfiResponse response = newClient(clientCert.certPem(), clientCert.pkcs1KeyPem())
                .queryRequestStatus("probe-request");

        assertEquals(200, response.getCode());
        assertTrue(clientSubject.get().contains("MFI Tenant Test Client"),
                "服务端应当看到客户端证书主体，实际为: " + clientSubject.get());
    }

    @Test
    @DisplayName("对端不认的客户端证书在握手阶段失败，不会退化成匿名连接")
    void certificateFromAnotherAuthorityIsRejected() {
        TestCertificates.Pem rogueCa = TestCertificates.certificateAuthority("Rogue CA");
        TestCertificates.Pem rogueClient = TestCertificates.leaf(rogueCa, "Rogue Client", 365);
        MfiTokenClient client = newClientQuietly(rogueClient.certPem(), rogueClient.pkcs8KeyPem());

        assertThrows(IOException.class, () -> client.queryRequestStatus("probe-request"));
        assertEquals(0, served.get(), "握手未通过，请求根本不该进到业务处理");
    }

    @Test
    @DisplayName("服务端证书不被信任时同样拒绝，证明没有信任所有证书的旁路")
    void untrustedServerIsRejected() throws Exception {
        TestCertificates.Pem otherCa = TestCertificates.certificateAuthority("Other CA");
        TestCertificates.Pem otherServer = TestCertificates.leaf(otherCa, "localhost", 365, "localhost");
        startStubServer(otherServer);
        MfiTokenClient client = newClientQuietly(clientCert.certPem(), clientCert.pkcs8KeyPem());

        assertThrows(IOException.class, () -> client.queryRequestStatus("probe-request"));
    }

    private MfiTokenClient newClient(String certPem, String keyPem) throws IOException {
        try {
            MfiSslContextBuilder.Material material = MfiSslContextBuilder.build(certPem, keyPem, ca.certPem());
            return new MfiTokenClient(MfiSslContextBuilder.createHttpClient(material), baseUrl, new ObjectMapper());
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("测试证书装配失败", e);
        }
    }

    /** 用例只关心调用阶段的握手失败，装配阶段必须能成功 */
    private MfiTokenClient newClientQuietly(String certPem, String keyPem) {
        try {
            return newClient(certPem, keyPem);
        } catch (IOException e) {
            throw new IllegalStateException("测试客户端装配失败", e);
        }
    }

    private void startStubServer(TestCertificates.Pem material) throws Exception {
        SSLContext sslContext = serverSslContext(material);
        HttpsServer httpsServer = HttpsServer.create();
        httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
            @Override
            public void configure(HttpsParameters parameters) {
                SSLParameters sslParameters = sslContext.getDefaultSSLParameters();
                // 要求客户端证书：苹果接口就是这个行为，桩不强制等于没验 mTLS
                sslParameters.setNeedClientAuth(true);
                parameters.setSSLParameters(sslParameters);
            }
        });
        httpsServer.bind(new InetSocketAddress("127.0.0.1", 0), 16);
        httpsServer.createContext("/", exchange -> {
            served.incrementAndGet();
            java.security.cert.Certificate[] chain = ((HttpsExchange) exchange).getSSLSession().getPeerCertificates();
            clientSubject.set(chain.length == 0 ? null : ((X509Certificate) chain[0]).getSubjectX500Principal().getName());
            byte[] body = "{\"request_status\":\"COMPLETE\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        httpsServer.start();
        if (server != null) {
            server.stop(0);
        }
        server = httpsServer;
        baseUrl = "https://localhost:" + httpsServer.getAddress().getPort();
    }

    private SSLContext serverSslContext(TestCertificates.Pem material) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        keyStore.setKeyEntry("server", material.privateKey(), new char[0], new X509Certificate[]{material.leaf()});

        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("ca", ca.leaf());

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, new char[0]);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return sslContext;
    }

}
