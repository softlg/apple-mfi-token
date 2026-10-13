package com.galaplat.mfi.shared.tenant;

import com.galaplat.mfi.shared.api.ErrorCode;
import com.galaplat.mfi.shared.api.BizException;
import com.galaplat.mfi.shared.tenant.TenantContext;
import com.galaplat.mfi.shared.tenant.TenantPrincipal;

/**
 * 请求入口处的两道检查：有没有生效租户、是不是该租户的 owner。
 *
 * <p>本产品没有权限体系，owner_flag 是唯一的区分位，只圈住换证书、改租户配置、移除成员、
 * 转让 owner 这几类不可逆或高影响的操作。前端隐藏按钮不算防护，服务端必须逐调用检查。
 */
public final class TenantAccess {

    private TenantAccess() {
    }

    /**
     * 取当前生效租户。未绑定时返回业务错误而不是放行，避免退化成无租户条件的全表访问。
     */
    public static long requireTenantId() {
        TenantPrincipal principal = current();
        return principal.tenantId();
    }

    /**
     * 要求当前成员是该租户 owner。
     */
    public static void requireOwner() {
        TenantPrincipal principal = current();
        if (!principal.owner()) {
            throw new BizException(ErrorCode.NOT_OWNER, "该操作仅限租户 owner");
        }
    }

    public static long requireUserId() {
        return current().userId();
    }

    private static TenantPrincipal current() {
        if (!TenantContext.isBound()) {
            throw new BizException(ErrorCode.TENANT_UNAVAILABLE, "当前账号没有可用的租户上下文，请先登录并选择租户");
        }
        return TenantContext.current();
    }

}
