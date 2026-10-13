package com.galaplat.mfi.apple;

import com.alibaba.excel.EasyExcelFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.galaplat.mfi.apple.model.AuthEntityRequest;
import com.galaplat.mfi.apple.model.BulkUsedAuthEntityRequest;
import com.galaplat.mfi.apple.model.DestroyUnusedRequest;
import com.galaplat.mfi.apple.model.SingleUsedAuthEntityRequest;
import com.galaplat.mfi.apple.model.TokenFileRow;
import com.galaplat.mfi.apple.response.FileNameListResponse;
import com.galaplat.mfi.apple.response.MfiResponse;
import com.galaplat.mfi.apple.response.TokenFileDownload;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 苹果 MFI Software Token API 客户端。一个实例只属于一个租户：证书与 BaseURL 都在构造时定死。
 *
 * <p>本类不感知数据库、租户上下文与 Spring，凭证由调用方以 PEM 文本传入并装配成 {@code httpClient}。
 * 日志只打数量、文件名与错误码；PPID、token 原文、auth_entity_id 清单一律不落日志（§6.3 红线）。
 */
@Slf4j
public class MfiTokenClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public MfiTokenClient(OkHttpClient httpClient, String baseUrl, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.objectMapper = objectMapper;
    }

    /**
     * 3.1.1 批量申请认证实体。返回的 request_id 是后续取文件名与查状态的唯一凭据。
     *
     * <p>这三个方法都只打数量不打 PPID：§6.3 的日志红线包含 PPID 明文，而调用方日志已经带了 shardId 与
     * 申请单号，PPID 回库一查就有。
     */
    public MfiResponse requestAuthEntities(String ppid, int count) throws IOException {
        log.info("申请认证实体: count={}", count);
        return post(MfiEndpoints.PATH_AUTH_ENTITY_REQUESTS, new AuthEntityRequest(ppid, count), MfiResponse.class);
    }

    /**
     * 3.1.2 取文件名列表。未就绪时苹果给 retry_download_after，调用方据此安排延迟重试。
     */
    public FileNameListResponse getFileNames(String requestId) throws IOException {
        return get(MfiEndpoints.PATH_AUTH_ENTITIES + "/" + requestId, FileNameListResponse.class);
    }

    /**
     * 3.1.3 下载 token 文件（xlsx）并解析成行。
     */
    public TokenFileDownload downloadTokenFile(String requestId, String fileName) throws IOException {
        Request httpRequest = new Request.Builder()
                .url(baseUrl + MfiEndpoints.PATH_AUTH_ENTITIES + "/" + requestId + "/" + fileName)
                .headers(commonHeaders())
                .get()
                .build();
        try (Response response = httpClient.newCall(httpRequest).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                return TokenFileDownload.failed(response.code(), readError(response));
            }
            List<TokenFileRow> rows = readTokenFile(body.byteStream());
            log.info("token 文件解析完成: fileName={}, rows={}", fileName, rows.size());
            return TokenFileDownload.ok(response.code(), rows);
        }
    }

    /**
     * 3.2.2.1 批量注册已使用（工厂配置）。单批最多 1000 个，超出必须由调用方分批。
     */
    public MfiResponse registerUsedAuthEntitiesBulk(String ppid, List<Map<String, String>> authEntities)
            throws IOException {
        if (authEntities.size() > BulkUsedAuthEntityRequest.MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("单批注册上限 " + BulkUsedAuthEntityRequest.MAX_BATCH_SIZE
                    + "，当前 " + authEntities.size());
        }
        log.info("批量注册已使用: count={}", authEntities.size());
        return post(MfiEndpoints.PATH_BULK_USED, new BulkUsedAuthEntityRequest(ppid, authEntities), MfiResponse.class);
    }

    /**
     * 3.2.3.1 单个注册已使用（现场配置）。苹果成功时返回空体，因此只按 HTTP 状态判定。
     */
    public MfiResponse registerUsedAuthEntitySingle(String ppid, String uuid, String authEntity) throws IOException {
        Request httpRequest = new Request.Builder()
                .url(baseUrl + MfiEndpoints.PATH_SINGLE_USED)
                .headers(commonHeaders())
                .post(RequestBody.create(write(new SingleUsedAuthEntityRequest(ppid, uuid, authEntity)), JSON))
                .build();
        try (Response response = httpClient.newCall(httpRequest).execute()) {
            MfiResponse result = new MfiResponse();
            result.setCode(response.code());
            if (!response.isSuccessful()) {
                MfiResponse errorBody = readError(response);
                result.setErrorCode(errorBody.getErrorCode());
                result.setErrorMessage(errorBody.getErrorMessage());
            }
            return result;
        }
    }

    /**
     * 3.5 销毁未使用的认证实体。不可逆。
     */
    public MfiResponse destroyUnusedAuthEntities(String ppid, List<String> authEntityIds) throws IOException {
        log.info("销毁未使用: count={}", authEntityIds.size());
        return post(MfiEndpoints.PATH_UNUSED, new DestroyUnusedRequest(ppid, authEntityIds), MfiResponse.class);
    }

    /**
     * 3.6 查询请求状态。
     */
    public MfiResponse queryRequestStatus(String requestId) throws IOException {
        return get(MfiEndpoints.PATH_STATUS + "/" + requestId, MfiResponse.class);
    }

    /**
     * 3.7 获取单个 token 信息。
     */
    public MfiResponse getTokenInfo(String authEntityId) throws IOException {
        return get(MfiEndpoints.PATH_AUTH_ENTITIES + "/info/" + authEntityId, MfiResponse.class);
    }

    private <T extends MfiResponse> T post(String path, Object payload, Class<T> responseType) throws IOException {
        Request request = new Request.Builder()
                .url(baseUrl + path)
                .headers(commonHeaders())
                .post(RequestBody.create(write(payload), JSON))
                .build();
        return execute(request, responseType);
    }

    private <T extends MfiResponse> T get(String path, Class<T> responseType) throws IOException {
        Request request = new Request.Builder()
                .url(baseUrl + path)
                .headers(commonHeaders())
                .get()
                .build();
        return execute(request, responseType);
    }

    private <T extends MfiResponse> T execute(Request request, Class<T> responseType) throws IOException {
        try (Response response = httpClient.newCall(request).execute()) {
            T parsed = parse(response, responseType);
            parsed.setCode(response.code());
            return parsed;
        }
    }

    /**
     * 解析 JSON 响应。非 2xx 时苹果可能给 JSON 错误体，也可能给网关的 HTML 或空体，
     * 后者必须降级成"状态码 + 截断文本"，不能让解析异常盖掉真实故障。
     */
    private <T extends MfiResponse> T parse(Response response, Class<T> responseType) throws IOException {
        String text = response.body() == null ? "" : response.body().string();
        T value = tryRead(text, responseType);
        if (value == null) {
            value = newEmpty(responseType);
            value.setErrorCode(String.valueOf(response.code()));
            value.setErrorMessage(summarize(text, response.message()));
        }
        return value;
    }

    private MfiResponse readError(Response response) throws IOException {
        return parse(response, MfiResponse.class);
    }

    private static <T extends MfiResponse> T newEmpty(Class<T> type) throws IOException {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("响应类型缺少无参构造: " + type.getName(), e);
        }
    }

    private <T extends MfiResponse> T tryRead(String text, Class<T> type) {
        if (text == null || text.isBlank() || text.charAt(0) != '{') {
            return null;
        }
        try {
            return objectMapper.readValue(text, type);
        } catch (IOException e) {
            return null;
        }
    }

    private String write(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (IOException e) {
            throw new IllegalStateException("MFI 请求体序列化失败", e);
        }
    }

    private static String summarize(String text, String fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200);
    }

    private Headers commonHeaders() {
        return new Headers.Builder()
                .add("Accept", "application/json")
                .add("Content-Type", "application/json")
                .build();
    }

    /**
     * 读 xlsx。沿用 MES 生产实测的按列序读取（不按表头匹配），并丢掉表头行与空行。
     */
    private static List<TokenFileRow> readTokenFile(InputStream inputStream) {
        List<TokenFileRow> raw = EasyExcelFactory.read(inputStream)
                .head(TokenFileRow.class)
                .headRowNumber(0)
                .sheet(0)
                .doReadSync();
        List<TokenFileRow> rows = new ArrayList<>(raw.size());
        for (TokenFileRow row : raw) {
            if (row != null && !"PPID".equals(row.getPpid())
                    && (hasText(row.getPpid()) || hasText(row.getTokenId()))) {
                rows.add(row);
            }
        }
        return rows;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

}
