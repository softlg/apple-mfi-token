package com.galaplat.mfi.model.vo;

import lombok.Data;

import java.util.List;

/**
 * Excel 导入结果。
 *
 * <p>语义是"整份文件或留或走"：只要有一行不合法，本次不入库任何数据，errors 给出每一问题行的行号与原因。
 * 这样做而不是边错边插，是因为重新导入同一份文件时必须得到可预期的结果——若上一次已经插进去一半，这一次
 * 那些行会以"已存在"报错，用户无法判断到底哪些进了库。
 */
@Data
public class PpIdImportResultVo {

    /** 读到的数据行数（不含表头与全空行） */
    private Integer readRows;

    /** 实际入库行数；有错误时恒为 0 */
    private Integer insertedRows;

    /** 问题行数，等于 errors 条数 */
    private Integer rejectedRows;

    private List<PpIdRowErrorVo> errors;

}
