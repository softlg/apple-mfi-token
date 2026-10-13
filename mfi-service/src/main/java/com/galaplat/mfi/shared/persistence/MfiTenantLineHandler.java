package com.galaplat.mfi.shared.persistence;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.galaplat.mfi.shared.tenant.TenantContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;

import java.util.Set;

/**
 * 租户 SQL 改写处理器。业务表的 SELECT/UPDATE/DELETE 自动追加 tenant_id 条件，INSERT 自动填充
 * tenant_id，隔离由框架保证而非调用方自觉。
 *
 * <p>与 MES 的差异：MES 的同类拦截器由 {@code skipDataSourcesPermission} 控制且默认为 {@code true}
 * （等于关闭）。这里没有开关，上下文缺失时 {@link TenantContext#requireTenantId()} 直接抛错。
 */
public class MfiTenantLineHandler implements TenantLineHandler {

    /**
     * 不参与租户过滤的全局表。新增业务表一律不得加入此集合。
     */
    public static final Set<String> GLOBAL_TABLES = Set.of("t_user", "t_tenant");

    @Override
    public Expression getTenantId() {
        return new LongValue(TenantContext.requireTenantId());
    }

    @Override
    public String getTenantIdColumn() {
        return "tenant_id";
    }

    @Override
    public boolean ignoreTable(String tableName) {
        return GLOBAL_TABLES.contains(normalize(tableName));
    }

    private String normalize(String tableName) {
        if (tableName == null) {
            return "";
        }
        return tableName.replace("`", "").replace("\"", "").toLowerCase();
    }

}
