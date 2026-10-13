package com.galaplat.mfi.credential.domain;

import com.galaplat.mfi.apple.MfiTokenClient;

/**
 * 按租户取 Apple MFI 客户端，凭证上下文对外唯一的苹果调用入口。
 *
 * <p>调用方（申请流水线、人工重放、销毁、租户设置）只需要"给定租户能调苹果"，不需要知道背后是
 * 证书上下文缓存还是别的装配方式。端口放在 domain 而不是 infrastructure，是为了让跨上下文依赖
 * 落在稳定的那一侧：换掉缓存策略不该波及流水线。
 */
public interface AppleClientProvider {

    /**
     * 取该租户的客户端。凭证缺失、证书与私钥不配对、租户不可用都抛业务异常，绝不返回 null
     * 让调用方带病继续。
     */
    MfiTokenClient forTenant(long tenantId);

    /**
     * 凭证或租户出口变更后必须调用，否则旧客户端会带着过期材料继续打向改动前的端点。
     */
    void evict(long tenantId);

}
