// 本地 mTLS 桩：没有苹果测试证书时的替代 fixture。
//
//   java scripts/MfiStubServer.java                              # 默认 8443
//   java scripts/MfiStubServer.java 18443                        # 换端口
//
// 只依赖 JDK：证书与私钥由 scripts/gen-dev-certs.sh 生成在 .dev-certs/。
// 行为对齐苹果接口：强制要求客户端证书（needClientAuth），路径与返回字段名按官方文档。
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

public class MfiStubServer {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8443;
        Path certs = Path.of(".dev-certs");

        X509Certificate serverCert = readCert(certs.resolve("server.crt"));
        X509Certificate caCert = readCert(certs.resolve("ca.crt"));
        PrivateKey serverKey = readPkcs8(certs.resolve("server.pk8.pem"));

        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        keyStore.setKeyEntry("server", serverKey, new char[0], new X509Certificate[]{serverCert, caCert});

        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("ca", caCert);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, new char[0]);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", port), 16);
        server.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
            @Override
            public void configure(com.sun.net.httpserver.HttpsParameters parameters) {
                SSLParameters ssl = getSSLContext().getDefaultSSLParameters();
                ssl.setNeedClientAuth(true);
                parameters.setSSLParameters(ssl);
            }
        });
        server.createContext("/api/v1.0/external/", MfiStubServer::handle);
        server.setExecutor(null);
        server.start();
        System.out.println("mTLS 桩已启动: https://localhost:" + port + " (需客户端证书，仅信任 .dev-certs/ca.crt)");
    }

    /**
     * 按苹果文档的字段名回固定内容，同时把看到的客户端证书主体打印出来——
     * 这一行就是"该租户的证书真的送到了对端"的直接证据。
     */
    private static void handle(HttpExchange exchange) throws java.io.IOException {
        String path = exchange.getRequestURI().getPath();
        X509Certificate client =
                (X509Certificate) ((com.sun.net.httpserver.HttpsExchange) exchange).getSSLSession()
                        .getPeerCertificates()[0];
        System.out.println("[stub] " + exchange.getRequestMethod() + " " + path
                + " <- clientCert=" + client.getSubjectX500Principal().getName()
                + " notAfter=" + client.getNotAfter());

        String body;
        if (path.endsWith("/authEntityRequests")) {
            body = "{\"request_id\":\"stub-" + System.currentTimeMillis() + "\"}";
        } else if (path.startsWith("/api/v1.0/external/authEntities/") && path.length() > 34 && !path.contains("/info/")) {
            body = "{\"file_count\":1,\"file_name\":[\"stub_tokens.xlsx\"],"
                    + "\"download_availability\":\"AVAILABLE\",\"retry_download_after\":0}";
        } else if (path.startsWith("/api/v1.0/external/status/")) {
            body = "{\"request_status\":\"COMPLETE\"}";
        } else {
            body = "{\"error_code\":\"STUB_OK\",\"error_message\":\"matched without error\"}";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static X509Certificate readCert(Path pem) throws Exception {
        try (InputStream in = Files.newInputStream(pem)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    private static PrivateKey readPkcs8(Path pem) throws Exception {
        String text = Files.readString(pem);
        String base64 = text.replaceAll("-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

}
