package com.galaplat.mfi.model.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改一份额度。ppid / spu / 产品型号是身份，不参与修改——它们构成唯一键，改等价于换了一份额度，
 * 应该删旧增新（这里用停用 + 新增），否则历史 token 会对不上来源。
 */
@Data
public class PpIdUpdateRequest {

    @NotNull(message = "可申请总量不能为空")
    @Min(value = 0, message = "可申请总量不能为负")
    @Max(value = 1000000, message = "可申请总量过大，单行上限 1000000")
    private Integer totalQuantity;

    /** 1=可用 0=停用；余量归零时数据库会自动停用，这里允许人工再启用 */
    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态只能是 0 或 1")
    @Max(value = 1, message = "状态只能是 0 或 1")
    private Integer status;

    @Size(max = 255, message = "备注过长")
    private String remark;

}
