package com.galaplat.mfi.ppid.infrastructure.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import lombok.Data;

/**
 * PPID 池导出行。列与导入模板保持同样的前三列（PPID / SPU / 产品型号），
 * 这样用户可以把导出结果删掉计数列改回导入文件；状态与时间列是只读信息，导入时会被忽略。
 */
@Data
public class PpIdExportRow {

    @ColumnWidth(24)
    @ExcelProperty(value = "PPID", index = 0)
    private String ppid;

    @ColumnWidth(20)
    @ExcelProperty(value = "SPU", index = 1)
    private String spu;

    @ColumnWidth(20)
    @ExcelProperty(value = "产品型号", index = 2)
    private String productModel;

    @ColumnWidth(14)
    @ExcelProperty(value = "可申请总量", index = 3)
    private Integer totalQuantity;

    @ColumnWidth(14)
    @ExcelProperty(value = "已申请量", index = 4)
    private Integer appliedQuantity;

    @ColumnWidth(14)
    @ExcelProperty(value = "剩余可申请", index = 5)
    private Integer remainingQuantity;

    @ColumnWidth(10)
    @ExcelProperty(value = "状态", index = 6)
    private String statusText;

    @ColumnWidth(30)
    @ExcelProperty(value = "备注", index = 7)
    private String remark;

}
