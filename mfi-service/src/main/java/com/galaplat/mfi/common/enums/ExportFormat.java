package com.galaplat.mfi.common.enums;

/**
 * 导出产物格式。
 *
 * <p>默认 CSV：一万行 token 写成 XLSX 会把 POI 的整棵样式树压进内存，而厂商拿走的是纯数据，
 * 不需要格式。XLSX 留给"直接发给不会用 CSV 的人"这一种场景。
 */
public enum ExportFormat {

    CSV,
    XLSX;

    /** 文件扩展名，与格式同名小写 */
    public String suffix() {
        return name().toLowerCase();
    }

}
