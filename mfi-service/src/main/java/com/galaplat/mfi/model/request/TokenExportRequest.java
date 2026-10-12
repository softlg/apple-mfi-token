package com.galaplat.mfi.model.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * token 异步导出的筛选条件。
 *
 * <p>与分页列表同一组条件，但只到"单号 / PPID / 状态"为止：导出的是凭证材料，
 * 允许的筛选维度越多，一次误操作导出的范围就越难收回。全空即本租户全量（受行数上限约束）。
 */
@Data
public class TokenExportRequest {

    @Size(max = 64, message = "申请单号过长")
    private String applicationNo;

    @Size(max = 64, message = "PPID 过长")
    private String ppid;

    /** TokenStatus 枚举名 */
    @Size(max = 24, message = "状态取值过长")
    private String status;

    /** CSV / XLSX，留空按 CSV */
    @Size(max = 8, message = "导出格式取值过长")
    private String format;

}
