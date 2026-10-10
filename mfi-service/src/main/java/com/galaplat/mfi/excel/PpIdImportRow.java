package com.galaplat.mfi.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import lombok.Data;

/**
 * PPID 导入文件的一行。
 *
 * <p>所有字段都声明成 String 而不是 Integer：Excel 单元格的实际类型由用户决定，"100" 可能是数字、
 * 可能是文本、也可能被存成 100.0，交给 EasyExcel 做类型转换就会在读取阶段直接抛异常，整个文件读不完，
 * 也就给不出"第 N 行错了"的承诺。类型解析与范围校验统一放在服务层，按行产出错误信息。
 *
 * <p>{@code index} 生效时 EasyExcel 按列位取数而忽略表头文字，因此模板列序是契约的一部分，
 * 改列序等于改模板，必须同步改这里。
 */
@Data
public class PpIdImportRow {

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
    private String totalQuantity;

    @ColumnWidth(30)
    @ExcelProperty(value = "备注", index = 4)
    private String remark;

}
