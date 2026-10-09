package com.galaplat.mfi.dao.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 租户上下文的隔离语义测试。这一组断言是整个多租户设计的地基：上下文缺失时必须失败，
 * 而不是降级成无条件查询。
 */
class TenantContextTest {

    private static final TenantPrincipal TENANT_A = new TenantPrincipal(1L, 11L, true);
    private static final TenantPrincipal TENANT_B = new TenantPrincipal(2L, 22L, false);

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("未绑定上下文时 requireTenantId 必须抛错，绝不返回默认租户")
    void requireTenantIdFailsClosed() {
        assertFalse(TenantContext.isBound());
        assertThrows(TenantNotBoundException.class, TenantContext::requireTenantId);
        assertThrows(TenantNotBoundException.class, TenantContext::current);
    }

    @Test
    @DisplayName("绑定后可读取租户与 owner 位")
    void bindThenRead() {
        TenantContext.bind(TENANT_B);
        assertTrue(TenantContext.isBound());
        assertEquals(2L, TenantContext.requireTenantId());
        assertEquals(22L, TenantContext.current().userId());
        assertFalse(TenantContext.current().owner());
    }

    @Test
    @DisplayName("runAs 执行完必须恢复原上下文，不能把内层租户泄漏给外层")
    void runAsRestoresPreviousPrincipal() {
        TenantContext.bind(TENANT_A);

        long inner = TenantContext.runAs(TENANT_B, TenantContext::requireTenantId);

        assertEquals(2L, inner);
        assertEquals(1L, TenantContext.requireTenantId(), "外层上下文必须被还原");
    }

    @Test
    @DisplayName("外层无上下文时 runAs 结束后要清空，而非遗留")
    void runAsClearsWhenNoPreviousPrincipal() {
        AtomicReference<Long> captured = new AtomicReference<>();
        TenantContext.runAs(TENANT_A, () -> captured.set(TenantContext.requireTenantId()));

        assertEquals(1L, captured.get());
        assertFalse(TenantContext.isBound());
        assertNull(TenantContext.currentOrNull());
    }

    @Test
    @DisplayName("Runnable 重载同样保证清理")
    void runAsRunnableClears() {
        TenantContext.runAs(TENANT_A, () -> assertTrue(TenantContext.isBound()));
        assertFalse(TenantContext.isBound());
    }

}
