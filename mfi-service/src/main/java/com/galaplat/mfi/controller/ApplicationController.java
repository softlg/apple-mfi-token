package com.galaplat.mfi.controller;

import com.galaplat.mfi.common.api.PageResult;
import com.galaplat.mfi.model.request.ApplyCreateRequest;
import com.galaplat.mfi.model.request.ApplicationPageRequest;
import com.galaplat.mfi.model.vo.ApplicationDetailVo;
import com.galaplat.mfi.model.vo.ApplicationRetryVo;
import com.galaplat.mfi.model.vo.ApplicationVo;
import com.galaplat.mfi.model.vo.ApplySubmitVo;
import com.galaplat.mfi.model.vo.TokenFileVo;
import com.galaplat.mfi.service.application.ApplicationQueryService;
import com.galaplat.mfi.service.application.ApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Token 申请。任何已登录成员都可发起——申请消耗的是本租户自己的额度，不属于"一改就让全租户挂掉"的配置，
 * 因此不设 owner 门槛。
 */
@Tag(name = "Token 申请")
@RestController
@RequestMapping("/api/applications")
public class ApplicationController {

    private final ApplicationService applicationService;
    private final ApplicationQueryService queryService;

    public ApplicationController(ApplicationService applicationService, ApplicationQueryService queryService) {
        this.applicationService = applicationService;
        this.queryService = queryService;
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

    @Operation(summary = "申请单详情",
            description = "五阶段进度、分片明细、token 计数与进度时间线一次返回；时间线只给最近的留痕")
    @GetMapping("/{applicationNo}")
    public ApplicationDetailVo detail(@PathVariable String applicationNo) {
        return queryService.detail(applicationNo);
    }

    @Operation(summary = "文件明细", description = "苹果下发的每份 xlsx 下了多少 token、失败过几次")
    @GetMapping("/{applicationNo}/files")
    public List<TokenFileVo> files(@PathVariable String applicationNo) {
        return queryService.files(applicationNo);
    }

    @Operation(summary = "人工重放失败分片",
            description = "只把终态失败（DEAD）的分片按当前阶段重新入队；已拿到的苹果 request id 会复用，"
                    + "不会重复申请、也不会再扣一次额度。没有失败分片时返回 0，不报错")
    @PostMapping("/{applicationNo}/retry")
    public ApplicationRetryVo retry(@PathVariable String applicationNo) {
        return applicationService.retry(applicationNo);
    }

}
