package com.galaplat.mfi.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/**
 * token 导出的一行。列序即契约：厂商的贴片机按列位取数，改顺序等于改接口。
 *
 * <p>时间列用字符串而不是 {@code LocalDateTime}：CSV 与 XLSX 走的是两条写值路径，格式化成字符串
 * 之后两边输出完全一致，导出文件不必再依赖某个转换器版本的默认格式。
 *
 * <p>状态列输出枚举名而不是中文：这份文件是给机器与厂商对账用的，中文一旦改词就会让对方的
 * 导入脚本静默错分类。
 */
@Data
public class TokenExportRow {

    @ExcelProperty(value = "PPID", index = 0)
    private String ppid;

    @ExcelProperty(value = "Token ID", index = 1)
    private String tokenId;

    @ExcelProperty(value = "配件UUID", index = 2)
    private String accessoryUuid;

    @ExcelProperty(value = "制造序列号", index = 3)
    private String manufactureSn;

    @ExcelProperty(value = "Secret Key", index = 4)
    private String secretKey;

    @ExcelProperty(value = "Token", index = 5)
    private String encodedToken;

    @ExcelProperty(value = "状态", index = 6)
    private String status;

    @ExcelProperty(value = "注册状态", index = 7)
    private String registerStatus;

    @ExcelProperty(value = "下载时间", index = 8)
    private String downloadTime;

    @ExcelProperty(value = "注册时间", index = 9)
    private String registerTime;

}
