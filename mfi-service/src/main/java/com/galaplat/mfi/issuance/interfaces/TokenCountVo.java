package com.galaplat.mfi.issuance.interfaces;

import lombok.Data;
import com.galaplat.mfi.issuance.domain.RegisterStatus;
import com.galaplat.mfi.issuance.domain.TokenStatus;

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
