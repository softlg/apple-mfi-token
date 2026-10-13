package com.galaplat.mfi.identity.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.galaplat.mfi.apple.MfiEndpoints;
import com.galaplat.mfi.credential.infrastructure.client.MfiClientFactory;
import com.galaplat.mfi.shared.api.ErrorCode;
import com.galaplat.mfi.shared.api.BizException;
import com.galaplat.mfi.identity.infrastructure.persistence.TenantPO;
import com.galaplat.mfi.identity.infrastructure.persistence.TenantMapper;
import com.galaplat.mfi.identity.interfaces.TenantProfileRequest;
import com.galaplat.mfi.identity.interfaces.TenantProfileVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.galaplat.mfi.shared.tenant.TenantAccess;

/**
 * 租户基本信息与苹果调用参数。
 *
 * <p>{@code t_tenant} 是全局表，不会被租户拦截器改写，所以这里的 id 必须由调用方从上下文取，
 * 不能信请求体。所有入口都走 {@code TenantAccess.requireTenantId()}。
 */
@Slf4j
@Service
public class TenantProfileService {

    private final TenantMapper tenantMapper;
    private final MfiClientFactory clientFactory;

    public TenantProfileService(TenantMapper tenantMapper, MfiClientFactory clientFactory) {
        this.tenantMapper = tenantMapper;
        this.clientFactory = clientFactory;
    }

    public TenantProfileVo profile(long tenantId) {
        return toVo(requireTenant(tenantId));
    }

    /**
     * 修改租户信息。technology 或 BaseURL 变了会改变客户端的目标地址，必须同步 evict，
     * 否则旧客户端会继续打向改动前的端点。
     *
     * <p>这里刻意不用 {@code updateById}：它默认跳过 null 字段，"BaseURL 留空即回到官方地址"
     * 就永远清不掉旧的覆盖值。lambdaUpdate 的 set 会把 NULL 真正写进去。
     */
    public TenantProfileVo updateProfile(long tenantId, TenantProfileRequest request) {
        requireTenant(tenantId);
        String technology = emptyToNull(request.getAppleTechnology());
        if (!MfiEndpoints.isValidTechnology(technology)) {
            throw new BizException(ErrorCode.INVALID_PARAM,
                    "appleTechnology 只能是 " + MfiEndpoints.TECHNOLOGIES);
        }
        // apple_technology 是 NOT NULL 列，"不指定"只能落列默认值哨兵，分流效果与非 HomeKit 相同
        String storedTechnology = technology == null ? MfiEndpoints.TECHNOLOGY_DEFAULT : technology;
        String baseUrl = emptyToNull(request.getAppleBaseUrl());
        tenantMapper.update(null, Wrappers.<TenantPO>lambdaUpdate()
                .eq(TenantPO::getId, tenantId)
                .set(TenantPO::getTenantName, request.getTenantName().trim())
                .set(TenantPO::getAppleTechnology, storedTechnology)
                .set(TenantPO::getAppleBaseUrl, baseUrl));
        clientFactory.evict(tenantId);
        log.info("租户信息已更新: tenantId={}, technology={}, baseUrl={}", tenantId, storedTechnology, baseUrl);
        return toVo(requireTenant(tenantId));
    }

    private TenantPO requireTenant(long tenantId) {
        TenantPO tenant = tenantMapper.selectById(tenantId);
        if (tenant == null) {
            throw new BizException(ErrorCode.TENANT_UNAVAILABLE, "租户不存在或已删除");
        }
        return tenant;
    }

    private TenantProfileVo toVo(TenantPO tenant) {
        TenantProfileVo vo = new TenantProfileVo();
        vo.setTenantCode(tenant.getTenantCode());
        vo.setTenantName(tenant.getTenantName());
        vo.setAppleTechnology(tenant.getAppleTechnology());
        vo.setAppleBaseUrl(tenant.getAppleBaseUrl());
        vo.setStatus(tenant.getStatus());
        vo.setEffectiveBaseUrl(tenant.getAppleBaseUrl() == null || tenant.getAppleBaseUrl().isBlank()
                ? MfiEndpoints.baseUrlFor(tenant.getAppleTechnology())
                : tenant.getAppleBaseUrl());
        return vo;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

}
