package com.galaplat.mfi.identity.interfaces;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 邀请成员。本产品不做邮箱/邀请链接，只把已存在的账号拉进当前租户。
 */
@Data
public class InviteMemberRequest {

    @NotBlank(message = "登录账号不能为空")
    @Size(max = 64, message = "登录账号过长")
    private String account;

}
