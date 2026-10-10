package com.galaplat.mfi.model.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 转让 owner：当前租户的 owner 位从操作人转移到目标成员。
 */
@Data
public class TransferOwnerRequest {

    @NotNull(message = "目标用户 ID 不能为空")
    private Long userId;

}
