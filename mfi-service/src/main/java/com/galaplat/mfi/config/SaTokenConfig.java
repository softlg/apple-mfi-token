package com.galaplat.mfi.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.jwt.StpLogicJwtForSimple;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import com.galaplat.mfi.security.TenantBindingInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
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

    /**
     * 令牌采用 JWT 简单模式：无状态可水平扩展，会话数据仍走 sa-token 的 dao。
     */
    @Bean
    public StpLogic stpLogic() {
        return new StpLogicJwtForSimple();
    }

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
