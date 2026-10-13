package com.galaplat.mfi.identity.interfaces;

import lombok.Data;

/**
 * 租户摘要，用于登录后的租户选择列表。
 */
@Data
public class TenantBriefVo {

    private Long tenantId;

    private String tenantCode;

    private String tenantName;

    /** 我在该租户是否为 owner */
    private Boolean owner;

    /** 是否为本次会话的生效租户 */
    private Boolean current;

}
