package com.galaplat.mfi.dao.mybatis;

import com.galaplat.mfi.dao.tenant.TenantContext;
import com.galaplat.mfi.dao.tenant.TenantNotBoundException;
import com.galaplat.mfi.dao.tenant.TenantPrincipal;
import net.sf.jsqlparser.expression.LongValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 租户 SQL 改写的表判定测试。
 *
 * <p>白名单只有 t_user 与 t_tenant 两张全局表；任何新增业务表被误加进白名单，都会在这里暴露。
 */
class MfiTenantLineHandlerTest {

    private final MfiTenantLineHandler handler = new MfiTenantLineHandler();

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("全局表跳过租户过滤，业务表一律过滤")
    void ignoreTableWhitelist() {
        assertTrue(handler.ignoreTable("t_user"));
        assertTrue(handler.ignoreTable("t_tenant"));
        assertTrue(handler.ignoreTable("`t_user`"), "反引号包裹的表名也要识别");

        assertFalse(handler.ignoreTable("t_ppid"));
        assertFalse(handler.ignoreTable("t_token"));
        assertFalse(handler.ignoreTable("t_application"));
        assertFalse(handler.ignoreTable("t_application_ppid"));
        assertFalse(handler.ignoreTable("t_tenant_member"));
        assertFalse(handler.ignoreTable("t_tenant_credential"));
        assertFalse(handler.ignoreTable("t_tenant_config"));
        assertFalse(handler.ignoreTable("t_token_file"));
        assertFalse(handler.ignoreTable("t_operation_log"));
        assertFalse(handler.ignoreTable("t_export_task"));
    }

    @Test
    @DisplayName("白名单只允许两张表")
    void globalTablesSizeIsCapped() {
        assertEquals(2, MfiTenantLineHandler.GLOBAL_TABLES.size());
    }

    @Test
    @DisplayName("租户列名与取值类型固定")
    void tenantColumnAndValue() {
        assertEquals("tenant_id", handler.getTenantIdColumn());

        TenantContext.bind(new TenantPrincipal(42L, 7L, false));
        LongValue value = (LongValue) handler.getTenantId();
        assertEquals(42L, value.getValue());
    }

    @Test
    @DisplayName("无上下文时取租户 ID 必须抛错")
    void tenantIdFailsClosed() {
        assertThrows(TenantNotBoundException.class, handler::getTenantId);
    }

}
