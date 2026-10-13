package com.galaplat.mfi.ppid.interfaces;

import lombok.Data;

/**
 * PPID 池按 产品型号 + SPU 聚合后的总览，前端选池与余量预估都读它。
 */
@Data
public class PpIdStatsVo {

    private String productModel;

    private String spu;

    /** 该维度下有多少份额度行 */
    private Integer ppidRows;

    /** 其中可分配的份额数（停用与余量归零的不算） */
    private Integer enabledRows;

    private Integer totalQuantity;

    private Integer appliedQuantity;

    private Integer remainingQuantity;

}
