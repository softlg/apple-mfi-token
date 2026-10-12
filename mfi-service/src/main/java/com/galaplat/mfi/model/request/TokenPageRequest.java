package com.galaplat.mfi.model.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * token 结果分页筛选。
 *
 * <p>四个条件都可以空，全空就是本租户全量倒序翻。这里不接受 applicationId：内部主键一旦成为查询参数，
 * 就等于承认可以用它跨租户猜别人的单。
 */
@Data
public class TokenPageRequest {

    @Min(value = 1, message = "页码从 1 开始")
    private Integer pageNum = 1;

    @Min(value = 1, message = "每页至少 1 条")
    @Max(value = 200, message = "每页最多 200 条")
    private Integer pageSize = 20;

    @Size(max = 64, message = "申请单号过长")
    private String applicationNo;

    @Size(max = 64, message = "PPID 过长")
    private String ppid;

    /** TokenStatus 枚举名 */
    @Size(max = 24, message = "状态取值过长")
    private String status;

    /** RegisterStatus 枚举名 */
    @Size(max = 24, message = "注册状态取值过长")
    private String registerStatus;

    /** 制造序列号精确匹配：厂商拿一串 SN 回来核对到货与注册情况 */
    @Size(max = 64, message = "制造序列号过长")
    private String manufactureSn;

}
