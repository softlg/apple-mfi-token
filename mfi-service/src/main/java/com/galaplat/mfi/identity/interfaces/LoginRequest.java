package com.galaplat.mfi.identity.interfaces;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 账号密码登录。
 */
@Data
public class LoginRequest {

    @NotBlank(message = "登录账号不能为空")
    @Size(max = 64, message = "登录账号过长")
    private String account;

    @NotBlank(message = "密码不能为空")
    @Size(max = 128, message = "密码过长")
    private String password;

}
