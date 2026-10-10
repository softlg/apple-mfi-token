package com.galaplat.mfi.model.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发起一次 token 申请。租户、发起人取自会话上下文，不由请求传入。
 *
 * <p>分配方式固定为"按 产品型号 + SPU 取余量最多的额度优先"，与可分配预览同一条规则；
 * 不提供人工指定 PPID 的入口——MES 里那套人工分配最后要靠补偿端点收拾超卖，这里从入口就堵掉。
 */
@Data
public class ApplyCreateRequest {

    @NotBlank(message = "产品型号不能为空")
    @Size(max = 64, message = "产品型号过长")
    private String productModel;

    @NotBlank(message = "SPU 不能为空")
    @Size(max = 64, message = "SPU 过长")
    private String spu;

    @NotNull(message = "申请数量不能为空")
    @Min(value = 1, message = "申请数量至少为 1")
    @Max(value = 100000, message = "单次申请数量过大，请分批提交")
    private Integer applyQuantity;

    /**
     * 客户端幂等键，建议每次表单提交生成一个 UUID。同租户内重复提交只会得到同一张申请单；
     * 留空则不去重（唯一索引不约束 NULL）。
     */
    @Size(max = 64, message = "幂等键过长")
    private String requestId;

}
