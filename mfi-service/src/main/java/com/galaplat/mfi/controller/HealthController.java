package com.galaplat.mfi.controller;

import com.galaplat.mfi.dao.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 存活探针。P0 阶段用来验证"应用能起来、免租户路径能访问"这两件事。
 */
@Tag(name = "系统")
@RestController
@RequestMapping("/api")
public class HealthController {

    @Operation(summary = "存活探针", description = "免登录可访问，用于确认服务与租户拦截装配正常")
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "app", "apple-mfi-token",
                "tenantContextBound", TenantContext.isBound()
        );
    }

}
