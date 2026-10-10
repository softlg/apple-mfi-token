package com.galaplat.mfi.controller;

import com.galaplat.mfi.common.api.PageResult;
import com.galaplat.mfi.model.request.ApplyCreateRequest;
import com.galaplat.mfi.model.request.ApplicationPageRequest;
import com.galaplat.mfi.model.vo.ApplicationVo;
import com.galaplat.mfi.model.vo.ApplySubmitVo;
import com.galaplat.mfi.service.application.ApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Token 申请。任何已登录成员都可发起——申请消耗的是本租户自己的额度，不属于"一改就让全租户挂掉"的配置，
 * 因此不设 owner 门槛。
 */
@Tag(name = "Token 申请")
@RestController
@RequestMapping("/api/applications")
public class ApplicationController {

    private final ApplicationService applicationService;

    public ApplicationController(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @Operation(summary = "发起申请",
            description = "同步段完成校验、扣额度与拆分；带上 requestId 可安全重试，重复提交返回同一张单")
    @PostMapping
    public ApplySubmitVo submit(@Valid @RequestBody ApplyCreateRequest request) {
        return applicationService.submit(request);
    }

    @Operation(summary = "申请单分页", description = "进度时间线与文件明细在结果接口里，这里只列单")
    @GetMapping("/page")
    public PageResult<ApplicationVo> page(@Valid ApplicationPageRequest request) {
        return applicationService.page(request);
    }

}
