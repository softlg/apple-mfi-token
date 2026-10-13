package com.galaplat.mfi.shared.tenant;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 把登录态里的当前租户解析成 {@link TenantContext}，请求结束后必须清理。
 *
 * <p>会话里只存"当前租户 ID"这一个偏好，owner 标志每次请求都回库取，不进会话缓存。
 * 原因是缓存过的权限无法即时回收：移除成员或转让 owner 后，对方已登录的会话仍会带着旧的
 * owner 位继续操作。这里多付一次主键查询，换取权限变更即时生效。
 *
 * <p>登录但尚未选定租户、或所选租户已失效时都不绑定、也不报错：由业务层的
 * {@code TenantAccess.requireTenantId()} 在使用点拒绝，这样 {@code /api/auth/**}
 * 这类无需租户的接口仍可正常访问。
 *
 * <p>成员与租户的有效性判定经 {@link AccountBindingSource} 取自身份上下文：共享内核不读业务表，
 * 它只负责会话解析与线程上下文的绑定和清理。
 */
@Component
public class TenantBindingInterceptor implements HandlerInterceptor {

    /** sa-token 会话中当前生效租户的键，仅表示选择偏好，不承载权限 */
    public static final String SESSION_TENANT_ID = "currentTenantId";

    private final AccountBindingSource accountBindingSource;

    public TenantBindingInterceptor(AccountBindingSource accountBindingSource) {
        this.accountBindingSource = accountBindingSource;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!StpUtil.isLogin()) {
            return true;
        }
        SaSession session = StpUtil.getSession(false);
        if (session == null || !(session.get(SESSION_TENANT_ID) instanceof Number number)) {
            return true;
        }
        long tenantId = number.longValue();
        long userId = StpUtil.getLoginIdAsLong();
        TenantPrincipal principal = accountBindingSource.find(tenantId, userId);
        if (principal != null) {
            TenantContext.bind(principal);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // Tomcat 复用线程，漏清理会把上一个请求的租户带给下一个请求
        TenantContext.clear();
    }

}
