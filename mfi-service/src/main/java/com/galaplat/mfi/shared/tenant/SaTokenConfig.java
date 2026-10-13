package com.galaplat.mfi.shared.tenant;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import com.galaplat.mfi.shared.tenant.TenantBindingInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 认证装配。这里刻意只装配"登录态校验"，不注册任何角色或权限校验 —— 本产品不做权限管理。
 */
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class SaTokenConfig implements WebMvcConfigurer {

    private final TenantBindingInterceptor tenantBindingInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 顺序有意义：先确立登录态，再据此绑定租户上下文
        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/register",
                        "/api/auth/login",
                        "/api/health");
        registry.addInterceptor(tenantBindingInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/register",
                        "/api/auth/login",
                        "/api/health");
    }

}
