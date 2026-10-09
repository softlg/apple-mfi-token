package com.galaplat.mfi.security;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.galaplat.mfi.dao.tenant.TenantContext;
import com.galaplat.mfi.dao.tenant.TenantPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 把登录态里的当前租户绑定到 {@link TenantContext}，请求结束后必须清理。
 *
 * <p>登录但尚未选定租户时不绑定，也不报错：由业务层的 {@code requireTenantId()} 在使用点拒绝，
 * 这样 {@code /api/auth/**} 这类无需租户的接口仍可正常访问。
 */
@Component
public class TenantBindingInterceptor implements HandlerInterceptor {

    /** sa-token 会话中当前生效租户的键 */
    public static final String SESSION_TENANT_ID = "currentTenantId";
    public static final String SESSION_OWNER_FLAG = "currentTenantOwner";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!StpUtil.isLogin()) {
            return true;
        }
        SaSession session = StpUtil.getSession(false);
        if (session == null) {
            return true;
        }
        Object tenantId = session.get(SESSION_TENANT_ID);
        if (!(tenantId instanceof Number number)) {
            return true;
        }
        boolean owner = Boolean.TRUE.equals(session.get(SESSION_OWNER_FLAG));
        TenantContext.bind(new TenantPrincipal(number.longValue(), StpUtil.getLoginIdAsLong(), owner));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // Tomcat 复用线程，漏清理会把上一个请求的租户带给下一个请求
        TenantContext.clear();
    }

}
