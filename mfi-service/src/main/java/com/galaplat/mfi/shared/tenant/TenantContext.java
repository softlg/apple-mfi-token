package com.galaplat.mfi.shared.tenant;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 租户上下文。多租户隔离的唯一真值来源：Web 请求由拦截器绑定，MQ 消费者与异步线程用
 * {@link #runAs} 显式绑定。
 */
public final class TenantContext {

    private static final ThreadLocal<TenantPrincipal> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void bind(TenantPrincipal principal) {
        CURRENT.set(Objects.requireNonNull(principal, "tenant principal must not be null"));
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static boolean isBound() {
        return CURRENT.get() != null;
    }

    public static TenantPrincipal currentOrNull() {
        return CURRENT.get();
    }

    public static TenantPrincipal current() {
        TenantPrincipal principal = CURRENT.get();
        if (principal == null) {
            throw new TenantNotBoundException("租户上下文缺失，禁止执行无租户条件的数据访问");
        }
        return principal;
    }

    public static long requireTenantId() {
        return current().tenantId();
    }

    /**
     * 在指定租户上下文内执行，执行完恢复原上下文。MQ 消费者、异步任务与定时兜底扫描使用。
     */
    public static <T> T runAs(TenantPrincipal principal, Supplier<T> action) {
        TenantPrincipal previous = CURRENT.get();
        bind(principal);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static void runAs(TenantPrincipal principal, Runnable action) {
        runAs(principal, (Supplier<Void>) () -> {
            action.run();
            return null;
        });
    }

}
