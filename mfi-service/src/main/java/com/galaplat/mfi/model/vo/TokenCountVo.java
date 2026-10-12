package com.galaplat.mfi.model.vo;

import lombok.Data;

/**
 * token 按"生命周期状态 + 注册状态"的一个计数分组。
 */
@Data
public class TokenCountVo {

    /** TokenStatus 枚举名 */
    private String status;

    /** RegisterStatus 枚举名 */
    private String registerStatus;

    private Integer count;

}
