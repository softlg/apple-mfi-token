package com.galaplat.mfi.credential.domain;

/**
 * 租户侧的苹果出口地址，由身份上下文提供实现。
 *
 * <p>凭证上下文不直接读租户表：它只问"这个租户该打哪儿"。域名分流规则（租户覆盖优先，否则按
 * technology 走官方域名）属于租户事实，留在身份上下文，避免两处各写一份而分流结果不一致。
 */
public interface TenantEndpointSource {

    /**
     * 该租户生效的苹果 BaseURL。租户不存在或已删除时返回 null，由调用方给出业务错误。
     */
    String baseUrl(long tenantId);

}
