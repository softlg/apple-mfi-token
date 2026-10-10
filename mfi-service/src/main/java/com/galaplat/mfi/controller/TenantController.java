package com.galaplat.mfi.controller;

import com.galaplat.mfi.model.request.TenantConfigUpdateRequest;
import com.galaplat.mfi.model.request.TenantProfileRequest;
import com.galaplat.mfi.model.vo.TenantConfigItemVo;
import com.galaplat.mfi.model.vo.TenantProfileVo;
import com.galaplat.mfi.security.TenantAccess;
import com.galaplat.mfi.service.tenant.TenantConfigService;
import com.galaplat.mfi.service.tenant.TenantProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 当前租户的信息与业务参数。租户 ID 一律取自会话上下文，不接受请求传入。
 */
@Tag(name = "租户设置")
@RestController
@RequestMapping("/api/tenant")
public class TenantController {

    private final TenantProfileService profileService;
    private final TenantConfigService configService;

    public TenantController(TenantProfileService profileService, TenantConfigService configService) {
        this.profileService = profileService;
        this.configService = configService;
    }

    @Operation(summary = "租户信息", description = "含生效的苹果调用地址")
    @GetMapping("/profile")
    public TenantProfileVo profile() {
        return profileService.profile(TenantAccess.requireTenantId());
    }

    @Operation(summary = "修改租户信息", description = "[owner] technology 或 BaseURL 变更会立即重建 MFI 客户端")
    @PutMapping("/profile")
    public TenantProfileVo updateProfile(@Valid @RequestBody TenantProfileRequest request) {
        TenantAccess.requireOwner();
        return profileService.updateProfile(TenantAccess.requireTenantId(), request);
    }

    @Operation(summary = "业务参数列表", description = "逐项给出生效值、全局默认值与被覆盖标记")
    @GetMapping("/config")
    public List<TenantConfigItemVo> config() {
        return configService.list(TenantAccess.requireTenantId());
    }

    @Operation(summary = "修改业务参数", description = "[owner] 增量提交，未提交的键保持原样；值留空表示回到全局默认")
    @PutMapping("/config")
    public List<TenantConfigItemVo> updateConfig(@Valid @RequestBody TenantConfigUpdateRequest request) {
        TenantAccess.requireOwner();
        return configService.update(TenantAccess.requireTenantId(), request.getValues());
    }

}
