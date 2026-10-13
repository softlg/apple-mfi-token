package com.galaplat.mfi.shared.tenant;

/**
 * 在没有绑定租户上下文的情况下访问业务数据时抛出。
 *
 * <p>这是刻意设计的失败方式：宁可抛错，也绝不降级成"不加 tenant_id 条件"的全表查询。
 */
public class TenantNotBoundException extends IllegalStateException {

    public TenantNotBoundException(String message) {
        super(message);
    }

}
