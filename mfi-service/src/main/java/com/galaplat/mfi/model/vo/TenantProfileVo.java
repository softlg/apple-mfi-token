package com.galaplat.mfi.model.vo;

import lombok.Data;

/**
 * 租户基本信息与苹果调用参数。不含 tenant_id 之外的任何内部标识。
 */
@Data
public class TenantProfileVo {

    private String tenantCode;

    private String tenantName;

    /** 苹果 technology，空表示按 swa 默认分流 */
    private String appleTechnology;

    /** 生效的调用地址：租户覆盖值或按 technology 推出的官方地址 */
    private String effectiveBaseUrl;

    /** 租户显式覆盖的地址，空表示未覆盖 */
    private String appleBaseUrl;

    /** 1=启用 0=暂停 */
    private Integer status;

}
