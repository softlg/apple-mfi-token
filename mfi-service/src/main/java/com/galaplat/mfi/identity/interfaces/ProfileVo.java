package com.galaplat.mfi.identity.interfaces;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 当前用户资料：账号信息 + 我所属的租户列表 + 当前生效租户。
 */
@Data
public class ProfileVo {

    private Long userId;

    private String account;

    private String name;

    private String email;

    private String phone;

    /** 账号状态 1=启用 0=停用 */
    private Integer status;

    private LocalDateTime lastLoginTime;

    /** 当前生效租户 ID，未选择或已失效时为空 */
    private Long currentTenantId;

    private List<TenantBriefVo> tenants;

}
