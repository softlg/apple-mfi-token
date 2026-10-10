package com.galaplat.mfi.model.vo;

import lombok.Data;

/**
 * 导入文件里的一行错误定位：行号直接对应 Excel 界面里的行号（表头算第 1 行），用户不用数偏移。
 */
@Data
public class PpIdRowErrorVo {

    /** Excel 行号，1 起，含表头 */
    private Integer rowNo;

    /** 出错行的 PPID，便于原文件里搜索；行本身读不到 PPID 时为空 */
    private String ppid;

    private String message;

    public static PpIdRowErrorVo of(int rowNo, String ppid, String message) {
        PpIdRowErrorVo vo = new PpIdRowErrorVo();
        vo.rowNo = rowNo;
        vo.ppid = ppid;
        vo.message = message;
        return vo;
    }

}
