// 本地 mTLS 桩：没有苹果测试证书时的替代 fixture。
//
//   java scripts/MfiStubServer.java                              # 默认 8443
//   java scripts/MfiStubServer.java 18443                        # 换端口
//
// 只依赖 JDK：证书与私钥由 scripts/gen-dev-certs.sh 生成在 .dev-certs/。
// 行为对齐苹果接口：强制要求客户端证书（needClientAuth），路径与返回字段名按官方文档。
//
// 流水线验收需要的剧情用 PPID 名字触发，桩自己按 requestId 记住各自的进度：
//   含 WAIT    —— 第一次取文件名回"20 秒后到货"，第二次才给清单（按 retry_download_after 等待）
//   含 WAITLONG —— 第一次回"两小时后到货"，超过最长延迟档位，用来验"到货后发现自己还早到，再排一次"
//   含 FAIL503  —— 申请接口回 5xx（退避重试，耗尽后进死信）
//   含 FAIL403  —— 申请接口回 4xx（永久失败，直接进死信）
// 文件下载按清单切成的两份真实 xlsx，两份行数之和等于申请量，因此 token 行数可以和分片的
// allocate_count 直接对账。
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MfiStubServer {

    /** 苹果文档里 retry_download_after 的格式：UTC，毫秒位有一但恒为 0 的一位 */
    private static final DateTimeFormatter UTC_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S");

    private static final Map<String, Request> REQUESTS = new ConcurrentHashMap<>();
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    /**
     * token 编号必须整个桩内唯一：库里的唯一键是 {@code (tenant_id, auth_entity_id)}，
     * 两笔申请若发回同样的编号，第二笔会被 {@code insert ignore} 静默吞掉，验收就对不上数了。
     * 苹果的真实编号本身就是全局唯一的，这里用申请的序号复刻这一点。
     */
    private record Request(String ppid, int count, AtomicInteger fileNameCalls, String tokenIdPrefix) {
    }

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
     * 按苹果文档的路径与字段名回内容，同时把看到的客户端证书主体打印出来——
     * 这一行就是"该租户的证书真的送到了对端"的直接证据。日志只打数量与 request id，不打 token 内容。
     */
    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        X509Certificate client =
                (X509Certificate) ((com.sun.net.httpserver.HttpsExchange) exchange).getSSLSession()
                        .getPeerCertificates()[0];
        String body = readBody(exchange);
        System.out.println("[stub] " + method + " " + path
                + " <- clientCert=" + client.getSubjectX500Principal().getName()
                + " notAfter=" + client.getNotAfter());

        if (path.endsWith("/authEntityRequests")) {
            applyRequest(exchange, body);
        } else if (path.startsWith("/api/v1.0/external/authEntities/info/")) {
            sendJson(exchange, 200, "{\"request_status\":\"COMPLETE\"}");
        } else if (path.startsWith("/api/v1.0/external/authEntities/")) {
            fileRoute(exchange, path);
        } else if (path.startsWith("/api/v1.0/external/bulk/usedAuthEntities")) {
            // 注册在苹果侧不可逆，桩只要回成功即可；失败剧情由 FILE/REGISTER 之外的阶段承担
            sendJson(exchange, 200, "{}");
        } else if (path.startsWith("/api/v1.0/external/status/")) {
            sendJson(exchange, 200, "{\"request_status\":\"COMPLETE\"}");
        } else {
            sendJson(exchange, 404, "{\"error_code\":\"STUB_NOT_ROUTED\",\"error_message\":\"未知路径\"}");
        }
    }

    private static void applyRequest(HttpExchange exchange, String body) throws IOException {
        String ppid = extract(body, "ppid");
        int count = Integer.parseInt(orDefault(extract(body, "requested_auth_entity_count"), "0"));
        if (ppid == null || count < 1) {
            sendJson(exchange, 400, "{\"error_code\":\"INVALID_REQUEST\",\"error_message\":\"ppid 或数量非法\"}");
            return;
        }
        if (ppid.contains("FAIL403")) {
            System.out.println("[stub] 故意拒绝申请: ppid=" + ppid + " code=403");
            sendJson(exchange, 403, "{\"error_code\":\"AUTH_ENTITY_INVALID\",\"error_message\":\"PPID 不被接受\"}");
            return;
        }
        if (ppid.contains("FAIL503")) {
            System.out.println("[stub] 故意服务不可用: ppid=" + ppid + " code=503");
            sendJson(exchange, 503, "{\"error_code\":\"SERVICE_UNAVAILABLE\",\"error_message\":\"稍后再试\"}");
            return;
        }
        long sequence = SEQUENCE.incrementAndGet();
        String requestId = "stub-" + sequence + "-" + System.currentTimeMillis();
        REQUESTS.put(requestId, new Request(ppid, count, new AtomicInteger(), "TST" + sequence + "-"));
        System.out.println("[stub] 受理申请: ppid=" + ppid + " requestId=" + requestId + " count=" + count);
        sendJson(exchange, 200, "{\"request_id\":\"" + requestId + "\"}");
    }

    private static void fileRoute(HttpExchange exchange, String path) throws IOException {
        String rest = path.substring("/api/v1.0/external/authEntities/".length());
        int slash = rest.indexOf('/');
        String requestId = slash < 0 ? rest : rest.substring(0, slash);
        String fileName = slash < 0 ? null : rest.substring(slash + 1);
        Request request = REQUESTS.get(requestId);
        if (request == null) {
            sendJson(exchange, 404, "{\"error_code\":\"REQUEST_NOT_FOUND\",\"error_message\":\"没有这笔申请\"}");
            return;
        }
        if (fileName == null) {
            fileNameList(exchange, requestId, request);
            return;
        }
        int[] range = partRange(fileName, request.count());
        byte[] xlsx = buildXlsx(request.tokenIdPrefix(), request.ppid(), range[0], range[1]);
        System.out.println("[stub] 下发文件: ppid=" + request.ppid() + " fileName=" + fileName
                + " rows=" + (range[1] - range[0] + 1));
        exchange.getResponseHeaders().add("Content-Type",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        exchange.getResponseHeaders().add("Content-Disposition", "attachment; filename=" + fileName);
        exchange.sendResponseHeaders(200, xlsx.length);
        try (var out = exchange.getResponseBody()) {
            out.write(xlsx);
        }
    }

    private static void fileNameList(HttpExchange exchange, String requestId, Request request) throws IOException {
        int call = request.fileNameCalls().incrementAndGet();
        boolean waitLong = request.ppid().contains("WAITLONG");
        boolean wait = waitLong || request.ppid().contains("WAIT");
        if (wait && call == 1) {
            // 短档：20 秒后到货，用来验"按 retry_download_after 等待且不消耗重试"
            // 长档：两小时，超过最长延迟档位，消费者只能到货后发现自己还早到、再排一次
            String readyAt = UTC_MILLIS.format(LocalDateTime.now(ZoneOffset.UTC)
                    .plusSeconds(waitLong ? 7200 : 20));
            System.out.println("[stub] 文件仍在生成: ppid=" + request.ppid() + " requestId=" + requestId
                    + " call=" + call + " readyAt(UTC)=" + readyAt);
            sendJson(exchange, 200, "{\"file_count\":0,\"file_name\":[],"
                    + "\"download_availability\":\"WAIT\",\"retry_download_after\":\"" + readyAt + "\"}");
            return;
        }
        // 苹果按每文件上限分片；桩固定两个文件，正好验证"逐文件推进"而不是一次性做完
        int first = firstPartSize(request.count());
        StringBuilder files = new StringBuilder("[\"stub-part-1.xlsx\"");
        int second = request.count() - first;
        if (second > 0) {
            files.append(",\"stub-part-2.xlsx\"");
        }
        files.append(']');
        int fileCount = second > 0 ? 2 : 1;
        System.out.println("[stub] 下发文件清单: ppid=" + request.ppid() + " requestId=" + requestId
                + " call=" + call + " files=" + fileCount + " count=" + request.count());
        sendJson(exchange, 200, "{\"file_count\":" + fileCount + ",\"file_name\":" + files
                + ",\"download_availability\":\"AVAILABLE\"}");
    }

    /**
     * 第 1 个文件的行数（向上取整），剩下的都归第 2 个文件。清单与下载必须用同一个切法，
     * 否则两个文件的行数之和与申请量对不上。
     */
    private static int firstPartSize(int count) {
        return count / 2 + count % 2;
    }

    /**
     * 按文件名算出该文件承载的行号区间（从 1 开始，闭区间）。count 为 1 时第二个文件不存在，
     * 返回空区间让下载接口只回表头。
     */
    private static int[] partRange(String fileName, int count) {
        int first = firstPartSize(count);
        boolean secondPart = fileName.endsWith("2.xlsx");
        return secondPart ? new int[]{first + 1, count} : new int[]{1, first};
    }

    /**
     * 手写最小 xlsx（inlineStr，无样式表），这样桩不必依赖 EasyExcel 的 classpath。
     * 表头与苹果一致：PPID / Token ID / Base64-encoded Token / CRC32 in HEX。
     */
    private static byte[] buildXlsx(String tokenIdPrefix, String ppid, int from, int to) {
        StringBuilder sheet = new StringBuilder(4096);
        sheet.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
                .append("<sheetData>");
        sheet.append(row(1, "PPID", "Token ID", "Base64-encoded Token", "CRC32 in HEX"));
        for (int index = Math.max(from, 1); index <= to; index++) {
            String tokenId = tokenIdPrefix + String.format("%07d", index);
            String encoded = Base64.getEncoder().encodeToString(
                    ("mfi-stub-token-" + ppid + "-" + index).getBytes(StandardCharsets.UTF_8));
            CRC32 crc = new CRC32();
            crc.update(encoded.getBytes(StandardCharsets.UTF_8));
            sheet.append(row(index + 1, ppid, tokenId, encoded,
                    String.format("%08X", crc.getValue())));
        }
        sheet.append("</sheetData></worksheet>");

        Map<String, String> parts = Map.of(
                "[Content_Types].xml", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>\
                        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">\
                        <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>\
                        <Default Extension="xml" ContentType="application/xml"/>\
                        <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>\
                        <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>\
                        </Types>""",
                "_rels/.rels", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>\
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
                        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>\
                        </Relationships>""",
                "xl/workbook.xml", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>\
                        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" \
                        xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">\
                        <sheets><sheet name="tokens" sheetId="1" r:id="rId1"/></sheets></workbook>""",
                "xl/_rels/workbook.xml.rels", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>\
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
                        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>\
                        </Relationships>""",
                "xl/worksheets/sheet1.xml", sheet.toString());

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> part : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException("桩生成 xlsx 失败", e);
        }
        return buffer.toByteArray();
    }

    private static String row(int number, String... cells) {
        StringBuilder xml = new StringBuilder("<row r=\"").append(number).append("\">");
        for (int index = 0; index < cells.length; index++) {
            String column = String.valueOf((char) ('A' + index));
            xml.append("<c r=\"").append(column).append(number).append("\" t=\"inlineStr\"><is><t>")
                    .append(escape(cells[index])).append("</t></is></c>");
        }
        return xml.append("</row>").toString();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String extract(String json, String field) {
        String key = "\"" + field + "\"";
        int at = json.indexOf(key);
        if (at < 0) {
            return null;
        }
        int colon = json.indexOf(':', at + key.length());
        int value = colon + 1;
        while (value < json.length() && (json.charAt(value) == ' ' || json.charAt(value) == '"')) {
            value++;
        }
        int end = value;
        while (end < json.length() && json.charAt(end) != '"' && json.charAt(end) != ','
                && json.charAt(end) != '}' && json.charAt(end) != ' ') {
            end++;
        }
        return end > value ? json.substring(value, end) : null;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
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
