package com.galaplat.mfi.client;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.galaplat.mfi.apple.MfiEndpoints;
import com.galaplat.mfi.apple.MfiSslContextBuilder;
import com.galaplat.mfi.apple.MfiTokenClient;
import com.galaplat.mfi.common.api.ErrorCode;
import com.galaplat.mfi.common.exception.BizException;
import com.galaplat.mfi.config.AppleProperties;
import com.galaplat.mfi.credential.CredentialMaterial;
import com.galaplat.mfi.credential.CredentialService;
import com.galaplat.mfi.dao.entity.TenantPO;
import com.galaplat.mfi.dao.mapper.TenantMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.concurrent.CompletionException;

/**
 * 按租户装配并复用 {@link MfiTokenClient}。
 *
 * <p>缓存的是"已握过手的证书上下文 + 连接池"，复用能省下每次调用的 TLS 开销。租户 2000 条 token
 * 文件动辄几十兆，连接复用不是可选项。
 *
 * <p>三条失效路径：凭证替换/删除由 Controller 显式 {@code evict}；租户改 BaseURL 时由租户服务
 * {@code evict}；30 分钟无访问被 TTL 清掉。TTL 同时是对明文私钥驻留内存时长的上限，
 * 这是这里保留过期时间而不只做容量上限的原因。
 */
@Slf4j
@Component
public class MfiClientFactory {

    /** 同时在用的租户数量级远小于此，上限只为兜住异常增长 */
    private static final int MAX_CACHED_TENANTS = 512;

    private final LoadingCache<Long, MfiTokenClient> cache;
    private final CredentialService credentialService;
    private final TenantMapper tenantMapper;
    private final AppleProperties appleProperties;

    /**
     * Apple 报文用独立的 ObjectMapper：全局那个可能被业务侧的 Jackson 定制（命名策略、日期格式）影响，
     * 而苹果的字段名是对外契约，不能被全局配置带偏。
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MfiClientFactory(CredentialService credentialService, TenantMapper tenantMapper,
                            AppleProperties appleProperties) {
        this.credentialService = credentialService;
        this.tenantMapper = tenantMapper;
        this.appleProperties = appleProperties;
        this.cache = Caffeine.newBuilder()
                .maximumSize(MAX_CACHED_TENANTS)
                .expireAfterAccess(Duration.ofMinutes(30))
                .build(this::build);
    }

    /**
     * 取该租户的客户端，必要时现装。装不出来一律抛业务异常，绝不返回 null 让调用方带病继续。
     */
    public MfiTokenClient forTenant(long tenantId) {
        try {
            return cache.get(tenantId);
        } catch (CompletionException e) {
            throw unwrap(e);
        }
    }

    /** 凭证或 BaseURL 变更后必须调用，否则旧客户端会带着过期材料继续复用 */
    public void evict(long tenantId) {
        cache.invalidate(tenantId);
    }

    private MfiTokenClient build(long tenantId) {
        CredentialMaterial material = credentialService.material(tenantId);
        MfiSslContextBuilder.Material sslMaterial;
        try {
            sslMaterial = MfiSslContextBuilder.build(material.certPem(), material.keyPem(),
                    appleProperties.getTrustedCaPem());
        } catch (IOException | GeneralSecurityException e) {
            // BC/JDK 的解析异常只描述格式与算法问题，不回显 PEM 内容，栈里也不会带私钥
            log.error("凭证装配 TLS 失败: tenantId={}, fingerprint={}", tenantId, material.fingerprint(), e);
            throw new BizException(ErrorCode.CREDENTIAL_INVALID, "凭证无法用于双向 TLS，请重新上传有效的证书与私钥");
        }
        // 库里的材料可能是历史数据，装配时再确认一次配对，避免拿到握手阶段才暴露的隐性问题
        if (!MfiSslContextBuilder.keyMatchesCertificate(sslMaterial.leaf(), sslMaterial.privateKey())) {
            log.error("装配时发现私钥与证书不配对: tenantId={}, fingerprint={}", tenantId, material.fingerprint());
            throw new BizException(ErrorCode.CREDENTIAL_INVALID, "租户凭证的证书与私钥不配对，请重新上传");
        }
        String baseUrl = resolveBaseUrl(tenantId);
        log.info("MFI 客户端已装配: tenantId={}, baseUrl={}, fingerprint={}",
                tenantId, baseUrl, material.fingerprint());
        return new MfiTokenClient(MfiSslContextBuilder.createHttpClient(sslMaterial), baseUrl, objectMapper);
    }

    /**
     * 租户显式配了 BaseURL 就用它（本地 mTLS 桩、自建代理），否则按 technology 走官方域名分流。
     */
    private String resolveBaseUrl(long tenantId) {
        TenantPO tenant = tenantMapper.selectById(tenantId);
        if (tenant == null) {
            throw new BizException(ErrorCode.TENANT_UNAVAILABLE, "租户不存在或已删除");
        }
        String configured = tenant.getAppleBaseUrl();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return MfiEndpoints.baseUrlFor(tenant.getAppleTechnology());
    }

    private static RuntimeException unwrap(CompletionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof BizException biz) {
            return biz;
        }
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new BizException(ErrorCode.SYSTEM_ERROR, "构造 MFI 客户端失败");
    }

}
