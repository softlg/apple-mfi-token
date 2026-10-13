package com.galaplat.mfi.issuance.interfaces;

import com.galaplat.mfi.shared.api.PageResult;
import com.galaplat.mfi.issuance.interfaces.TokenDestroyRequest;
import com.galaplat.mfi.issuance.interfaces.TokenExportRequest;
import com.galaplat.mfi.issuance.interfaces.TokenPageRequest;
import com.galaplat.mfi.issuance.interfaces.ExportTaskVo;
import com.galaplat.mfi.issuance.interfaces.TokenDestroyVo;
import com.galaplat.mfi.issuance.interfaces.TokenVo;
import com.galaplat.mfi.issuance.application.ExportService;
import com.galaplat.mfi.issuance.application.TokenDestroyService;
import com.galaplat.mfi.issuance.application.TokenQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Token 结果。读侧只给核对用的字段，凭证材料走导出任务。
 */
@Tag(name = "Token 结果")
@RestController
@RequestMapping("/api/tokens")
public class TokenController {

    private final TokenQueryService tokenQueryService;
    private final ExportService exportService;
    private final TokenDestroyService tokenDestroyService;

    public TokenController(TokenQueryService tokenQueryService, ExportService exportService,
                           TokenDestroyService tokenDestroyService) {
        this.tokenQueryService = tokenQueryService;
        this.exportService = exportService;
        this.tokenDestroyService = tokenDestroyService;
    }

    @Operation(summary = "分页查询",
            description = "按申请单号/PPID/状态/制造序列号筛选；响应内不含 token 原文与密钥")
    @GetMapping("/page")
    public PageResult<TokenVo> page(@Valid TokenPageRequest request) {
        return tokenQueryService.page(request);
    }

    @Operation(summary = "发起异步导出",
            description = "返回 PENDING 任务号，产物写好后再按 /api/export-tasks/{taskNo} 取件与下载；"
                    + "行数受服务端上限约束，超限截断")
    @PostMapping("/export")
    public ExportTaskVo export(@Valid @RequestBody TokenExportRequest request) {
        return exportService.create(request);
    }

    @Operation(summary = "销毁未使用实体（仅 owner，不可逆）",
            description = "把该申请单里已下载、还没注册回苹果的 token 提交苹果作废；"
                    + "必须把申请单号原样再传一次作为二次确认；单次最多 500 个，用 hasMore 判断是否还有剩余")
    @PostMapping("/destroy")
    public TokenDestroyVo destroy(@Valid @RequestBody TokenDestroyRequest request) {
        return tokenDestroyService.destroy(request);
    }

}
