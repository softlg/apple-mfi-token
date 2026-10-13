package com.galaplat.mfi.identity.infrastructure;

import com.galaplat.mfi.credential.domain.TenantEndpointSource;
import com.galaplat.mfi.identity.application.TenantProfileReader;
import com.galaplat.mfi.identity.domain.TenantProfile;
import com.galaplat.mfi.identity.infrastructure.persistence.TenantMapper;
import com.galaplat.mfi.identity.infrastructure.persistence.TenantPO;
import org.springframework.stereotype.Component;

/**
 * {@code t_tenant} 的只读适配器：同时满足身份上下文的读端口与凭证上下文的出口端口。
 *
 * <p>两个端口合并到一个实现，是因为它们问的是同一行的两列；分开写会多一条几乎相同的查询，
 * 而"租户改 BaseURL 后客户端要重新装配"这类问题，最怕出现两个不一致的读法。
 */
@Component
public class TenantProfileAdapter implements TenantProfileReader, TenantEndpointSource {

    private final TenantMapper tenantMapper;

    public TenantProfileAdapter(TenantMapper tenantMapper) {
        this.tenantMapper = tenantMapper;
    }

    @Override
    public TenantProfile find(long tenantId) {
        TenantPO tenant = tenantMapper.selectById(tenantId);
        if (tenant == null) {
            return null;
        }
        return new TenantProfile(tenant.getAppleBaseUrl(), tenant.getAppleTechnology());
    }

    @Override
    public String baseUrl(long tenantId) {
        TenantProfile profile = find(tenantId);
        return profile == null ? null : profile.baseUrl();
    }

}
