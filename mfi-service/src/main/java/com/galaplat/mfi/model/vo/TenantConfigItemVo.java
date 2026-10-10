package com.galaplat.mfi.model.vo;

import lombok.Data;

/**
 * 一项租户业务参数的生效情况。
 */
@Data
public class TenantConfigItemVo {

    /** 配置键，等于 TenantConfigKey 枚举名 */
    private String key;

    /** 实际生效值：租户有行用行值，没行用全局默认 */
    private String effectiveValue;

    /** 全局默认值 */
    private String defaultValue;

    /** 租户是否显式覆盖过 */
    private Boolean overridden;

    private String remark;

}
