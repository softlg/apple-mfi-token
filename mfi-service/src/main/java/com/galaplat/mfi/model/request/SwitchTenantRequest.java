package com.galaplat.mfi.model.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 切换当前生效租户。只改会话里的租户选择，不涉及任何权限计算。
 */
@Data
public class SwitchTenantRequest {

    @NotNull(message = "租户 ID 不能为空")
    private Long tenantId;

}
