package com.galaplat.mfi.common.enums;

/**
 * Token 注册回苹果的状态。与 {@link TokenStatus} 分开，因为"注册中"与"已报废"是不同维度。
 */
public enum RegisterStatus {

    /** 该租户/申请未开启注册 */
    NOT_REQUIRED,
    PENDING,
    PROCESSING,
    SUCCESS,
    FAILED

}
