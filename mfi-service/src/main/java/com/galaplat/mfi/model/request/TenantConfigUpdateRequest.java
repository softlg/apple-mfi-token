package com.galaplat.mfi.model.request;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 修改租户业务参数。键必须是 {@code TenantConfigKey} 里的枚举名，值按各键的格式校验。
 */
@Data
public class TenantConfigUpdateRequest {

    @NotEmpty(message = "至少提交一个配置项")
    private Map<String, String> values = new LinkedHashMap<>();

}
