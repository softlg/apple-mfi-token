package com.galaplat.mfi.model.vo;

import lombok.Data;

import java.util.List;

/**
 * 登录成功返回。令牌由 sa-token 生成（uuid），会话状态存 Redis。
 */
@Data
public class LoginVo {

    /** 前端回传令牌时使用的请求头名 */
    private String tokenName;

    private String tokenValue;

    private Long userId;

    private String account;

    private String name;

    /** 默认为其生效的租户；账号尚未加入任何租户时为空 */
    private TenantBriefVo currentTenant;

    private List<TenantBriefVo> tenants;

}
