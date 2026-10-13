package com.galaplat.mfi.identity.domain;

import com.galaplat.mfi.apple.MfiEndpoints;

/**
 * 租户与苹果调用有关的事实快照。
 *
 * <p>域名分流与 technology 兜底放在这里，是因为这两条规则原先在客户端工厂与申请服务各写了一遍：
 * 分流规则一旦不一致，申请单里快照的 technology 和实际打出去的端点会对不上，排障时无法自证。
 *
 * @param appleBaseUrl    租户显式覆盖的出口地址，可为空
 * @param appleTechnology HomeKit 等分流标识，可为空
 */
public record TenantProfile(String appleBaseUrl, String appleTechnology) {

    /** 租户配了覆盖地址就用它（本地 mTLS 桩、自建代理），否则按 technology 走官方域名 */
    public String baseUrl() {
        if (appleBaseUrl == null || appleBaseUrl.isBlank()) {
            return MfiEndpoints.baseUrlFor(technology());
        }
        return appleBaseUrl.trim();
    }

    /**
     * technology 缺省落 {@link MfiEndpoints#TECHNOLOGY_DEFAULT} 哨兵。列本身 NOT NULL，
     * 这里的兜底是给历史空值与直接写库的运维数据留的。
     */
    public String technology() {
        return appleTechnology == null || appleTechnology.isBlank()
                ? MfiEndpoints.TECHNOLOGY_DEFAULT
                : appleTechnology.trim();
    }

}
