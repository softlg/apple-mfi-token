package com.galaplat.mfi.shared.tenant;

/**
 * 当前请求的租户身份快照。
 *
 * @param tenantId 生效租户
 * @param userId   登录用户
 * @param owner    是否租户 owner，唯一的高危操作区分位
 */
public record TenantPrincipal(long tenantId, long userId, boolean owner) {

    /**
     * 系统身份：MQ 消费者与兜底扫描这类没有登录用户的执行体。
     *
     * <p>userId 固定 0（库里不存在 id=0 的用户）、owner=false：系统身份不享有 owner 高危权限，
     * 换证书、移除成员、销毁 token 这些只能来自真实 owner 的请求。
     */
    public static TenantPrincipal system(long tenantId) {
        return new TenantPrincipal(tenantId, 0L, false);
    }
}
