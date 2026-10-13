package com.galaplat.mfi.shared.tenant;

/**
 * 请求绑定租户时需要的成员关系与租户状态，由身份上下文提供实现。
 *
 * <p>共享内核不碰业务表：它只定义"这对 (租户, 用户) 现在还算有效吗、是不是 owner"，
 * 具体查哪张表由身份上下文决定。这样 {@code shared} 才真正位于依赖图的底部。
 */
public interface AccountBindingSource {

    /**
     * 成员关系与租户都有效时返回绑定快照，否则返回 null——此时不绑定也不报错，由业务层
     * {@link TenantAccess#requireTenantId()} 在使用点拒绝，好让 {@code /api/auth/**}
     * 这类无需租户的接口仍能访问。
     */
    TenantPrincipal find(long tenantId, long userId);

}
