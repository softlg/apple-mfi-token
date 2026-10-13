package com.galaplat.mfi.identity.infrastructure;

import com.galaplat.mfi.identity.infrastructure.persistence.TenantMapper;
import com.galaplat.mfi.identity.infrastructure.persistence.TenantMemberMapper;
import com.galaplat.mfi.identity.infrastructure.persistence.TenantMemberPO;
import com.galaplat.mfi.identity.infrastructure.persistence.TenantPO;
import com.galaplat.mfi.shared.tenant.AccountBindingSource;
import com.galaplat.mfi.shared.tenant.TenantPrincipal;
import org.springframework.stereotype.Component;

/**
 * 请求绑定用的成员校验：每次请求回库确认"这个用户在这个租户下还有效吗、是不是 owner"。
 *
 * <p>owner 位不进会话缓存（见 {@link AccountBindingSource}），因为缓存过的权限无法即时回收：
 * 移除成员或转让 owner 后，对方已登录的会话仍会带着旧的 owner 位继续做高危操作。
 * 这里每次多付两趟主键查询，换取权限变更即时生效。
 */
@Component
public class AccountBindingAdapter implements AccountBindingSource {

    /** 有效：成员关系未解除且租户未停用。两处都是状态位，与成员服务同一口径 */
    private static final Integer ACTIVE = 1;

    private final TenantMemberMapper tenantMemberMapper;
    private final TenantMapper tenantMapper;

    public AccountBindingAdapter(TenantMemberMapper tenantMemberMapper, TenantMapper tenantMapper) {
        this.tenantMemberMapper = tenantMemberMapper;
        this.tenantMapper = tenantMapper;
    }

    @Override
    public TenantPrincipal find(long tenantId, long userId) {
        TenantMemberPO member = tenantMemberMapper.selectByTenantAndUser(tenantId, userId);
        if (member == null || !ACTIVE.equals(member.getStatus())) {
            return null;
        }
        TenantPO tenant = tenantMapper.selectById(tenantId);
        if (tenant == null || !ACTIVE.equals(tenant.getStatus())) {
            return null;
        }
        return new TenantPrincipal(tenantId, userId, ACTIVE.equals(member.getOwnerFlag()));
    }

}
