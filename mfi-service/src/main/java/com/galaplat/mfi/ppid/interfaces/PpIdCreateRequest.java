package com.galaplat.mfi.ppid.interfaces;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增一份额度：同一 PPID + SPU 下苹果授予的可申请总量。租户 ID 取自会话上下文，不由请求传入。
 */
@Data
public class PpIdCreateRequest {

    /** 苹果产品计划 ID */
    @NotBlank(message = "PPID 不能为空")
    @Size(max = 64, message = "PPID 过长")
    private String ppid;

    @NotBlank(message = "SPU 不能为空")
    @Size(max = 64, message = "SPU 过长")
    private String spu;

    @NotBlank(message = "产品型号不能为空")
    @Size(max = 64, message = "产品型号过长")
    private String productModel;

    @NotNull(message = "可申请总量不能为空")
    @Min(value = 1, message = "可申请总量至少为 1")
    @Max(value = 1000000, message = "可申请总量过大，单行上限 1000000")
    private Integer totalQuantity;

    @Size(max = 255, message = "备注过长")
    private String remark;

}
