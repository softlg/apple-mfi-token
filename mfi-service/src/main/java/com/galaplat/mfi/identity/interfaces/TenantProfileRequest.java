package com.galaplat.mfi.identity.interfaces;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改租户基本信息。租户 ID 一律取自上下文，不接受请求传入。
 */
@Data
public class TenantProfileRequest {

    @NotBlank(message = "租户名称不能为空")
    @Size(max = 128, message = "租户名称过长")
    private String tenantName;

    /** 苹果 technology，决定 BaseURL 分流；AirPlay/DockKit/FindMy 走 swa，HomeKit 走 sauth */
    @Size(max = 32, message = "technology 过长")
    private String appleTechnology;

    /** 覆盖默认 BaseURL，仅本地 mTLS 桩或自建代理使用；留空表示用官方地址 */
    @Size(max = 255, message = "BaseURL 过长")
    @Pattern(regexp = "^$|^https://[^\\s]+$", message = "BaseURL 必须是 https:// 开头的完整地址")
    private String appleBaseUrl;

}
