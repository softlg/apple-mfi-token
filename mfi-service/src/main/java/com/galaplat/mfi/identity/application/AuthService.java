package com.galaplat.mfi.identity.application;

import com.galaplat.mfi.identity.interfaces.LoginRequest;
import com.galaplat.mfi.identity.interfaces.RegisterRequest;
import com.galaplat.mfi.identity.interfaces.LoginVo;
import com.galaplat.mfi.identity.interfaces.ProfileVo;
import com.galaplat.mfi.identity.interfaces.TenantBriefVo;

/**
 * 认证与租户选择。本产品只做认证，不做角色与权限点。
 */
public interface AuthService {

    /** 注册：创建用户 + 创建租户 + 创建者置为 owner，并直接返回登录态 */
    LoginVo register(RegisterRequest request);

    LoginVo login(LoginRequest request);

    void logout();

    ProfileVo profile();

    /** 切换当前生效租户，返回切换结果 */
    TenantBriefVo switchTenant(Long tenantId);

}
