package com.galaplat.mfi.dao.tenant;

/**
 * 当前请求的租户身份快照。
 *
 * @param tenantId 生效租户
 * @param userId   登录用户
 * @param owner    是否租户 owner，唯一的高危操作区分位
 */
public record TenantPrincipal(long tenantId, long userId, boolean owner) {
}
