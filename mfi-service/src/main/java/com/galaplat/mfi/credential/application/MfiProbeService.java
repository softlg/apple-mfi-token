package com.galaplat.mfi.credential.application;

import com.galaplat.mfi.apple.MfiTokenClient;
import com.galaplat.mfi.shared.api.BizException;
import com.galaplat.mfi.credential.application.CredentialService;
import com.galaplat.mfi.credential.interfaces.CredentialVerifyVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.UUID;
import com.galaplat.mfi.apple.model.MfiResponse;
import com.galaplat.mfi.credential.domain.AppleClientProvider;

/**
 * 凭证连通性自检：用一次真实的双向握手证明证书被对端接受。
 *
 * <p>探针选 3.6 查询请求状态并填入一个随机 UUID：它是 GET、无副作用，苹果必然回 4xx，
 * 而"拿到 HTTP 响应"本身就是结论——mTLS 握手已经过了。握手失败才会抛 IOException。
 *
 * <p>自检复用工厂缓存，不主动 evict：凭证替换/删除与 BaseURL 变更都已在各自入口 evict，
 * 只读探针再拆一次连接池就等于每次自检都重新握手，缓存失去意义。
 */
@Slf4j
@Service
public class MfiProbeService {

    private final AppleClientProvider clientFactory;
    private final CredentialService credentialService;

    public MfiProbeService(AppleClientProvider clientFactory, CredentialService credentialService) {
        this.clientFactory = clientFactory;
        this.credentialService = credentialService;
    }

    public CredentialVerifyVo verify(long tenantId) {
        String fingerprint = credentialService.metadata(tenantId).getFingerprint();
        MfiTokenClient client = clientFactory.forTenant(tenantId);

        CredentialVerifyVo vo = new CredentialVerifyVo();
        vo.setFingerprint(fingerprint);
        try {
            var response = client.queryRequestStatus(UUID.randomUUID().toString());
            vo.setReachable(true);
            vo.setHttpStatus(response.getCode());
            vo.setDetail(summarize(response));
        } catch (IOException | BizException e) {
            vo.setReachable(false);
            vo.setDetail(summarizeException(e));
        }
        log.info("凭证自检完成: tenantId={}, reachable={}, httpStatus={}",
                tenantId, vo.getReachable(), vo.getHttpStatus());
        return vo;
    }

    /**
     * 探针期望 4xx：状态码是握手成功的证据，至于业务错误码是否为"request 不存在"不影响结论。
     */
    private static String summarize(com.galaplat.mfi.apple.model.MfiResponse response) {
        if (response.isSuccess()) {
            return "对端已接受客户端证书，状态接口返回 " + response.getCode();
        }
        return "对端已接受客户端证书，接口返回 " + response.briefError();
    }

    /**
     * 只保留异常类名与最短原因。TLS 栈里可能带对端 DN、算法名等长文本，截断后仍然可读。
     */
    private static String summarizeException(Throwable e) {
        Throwable cursor = e;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getClass().getSimpleName()
                + (cursor.getMessage() == null ? "" : ": " + cursor.getMessage().replaceAll("\\s+", " ").trim());
        return message.length() <= 300 ? message : message.substring(0, 300);
    }

}
