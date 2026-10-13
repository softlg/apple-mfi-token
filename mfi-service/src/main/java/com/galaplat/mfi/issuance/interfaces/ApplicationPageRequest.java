package com.galaplat.mfi.issuance.interfaces;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;
import com.galaplat.mfi.issuance.domain.ApplicationStatus;

/**
 * 申请单分页筛选。
 */
@Data
public class ApplicationPageRequest {

    @Min(value = 1, message = "页码从 1 开始")
    private Integer pageNum = 1;

    @Min(value = 1, message = "每页至少 1 条")
    @Max(value = 200, message = "每页最多 200 条")
    private Integer pageSize = 20;

    @Size(max = 64, message = "申请单号过长")
    private String applicationNo;

    @Size(max = 64, message = "产品型号过长")
    private String productModel;

    @Size(max = 64, message = "SPU 过长")
    private String spu;

    /** ApplicationStatus 枚举名 */
    @Size(max = 24, message = "状态取值过长")
    private String status;

}
