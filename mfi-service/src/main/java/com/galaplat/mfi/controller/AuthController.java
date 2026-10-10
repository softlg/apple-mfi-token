package com.galaplat.mfi.controller;

import com.galaplat.mfi.model.request.LoginRequest;
import com.galaplat.mfi.model.request.RegisterRequest;
import com.galaplat.mfi.model.request.SwitchTenantRequest;
import com.galaplat.mfi.model.vo.LoginVo;
import com.galaplat.mfi.model.vo.ProfileVo;
import com.galaplat.mfi.model.vo.TenantBriefVo;
import com.galaplat.mfi.service.auth.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证与租户选择。register / login 免登录，其余由拦截器保证已登录。
 */
@Tag(name = "认证")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "注册", description = "创建用户 + 创建租户 + 创建者置为 owner，成功即登录")
    @PostMapping("/register")
    public LoginVo register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @Operation(summary = "登录", description = "账号密码登录，返回 sa-token 令牌与可用租户列表")
    @PostMapping("/login")
    public LoginVo login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public void logout() {
        authService.logout();
    }

    @Operation(summary = "当前用户资料", description = "账号信息 + 我所属的租户列表 + 当前生效租户")
    @GetMapping("/profile")
    public ProfileVo profile() {
        return authService.profile();
    }

    @Operation(summary = "切换当前租户", description = "只改会话里的租户选择，数据随上下文立即变化")
    @PostMapping("/switch-tenant")
    public TenantBriefVo switchTenant(@Valid @RequestBody SwitchTenantRequest request) {
        return authService.switchTenant(request.getTenantId());
    }

}
