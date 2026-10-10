package com.galaplat.mfi.model.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册：一次性创建用户、租户，并把创建者置为该租户的 owner。
 */
@Data
public class RegisterRequest {

    @NotBlank(message = "登录账号不能为空")
    @Pattern(regexp = "^[a-zA-Z0-9_.]{4,32}$", message = "登录账号需为 4-32 位字母、数字、下划线或点")
    private String account;

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度需为 8-64 位")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "密码需同时包含字母和数字")
    private String password;

    @NotBlank(message = "姓名不能为空")
    @Size(max = 64, message = "姓名过长")
    private String name;

    @Email(message = "邮箱格式不正确")
    @Size(max = 128, message = "邮箱过长")
    private String email;

    @Pattern(regexp = "^$|^\\+?[0-9-]{6,20}$", message = "手机号格式不正确")
    @Size(max = 32, message = "手机号过长")
    private String phone;

    @NotBlank(message = "租户名称不能为空")
    @Size(max = 128, message = "租户名称过长")
    private String tenantName;

}
