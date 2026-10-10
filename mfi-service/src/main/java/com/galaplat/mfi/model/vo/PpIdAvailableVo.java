package com.galaplat.mfi.model.vo;

import lombok.Data;

import java.util.List;

/**
 * 一次"型号 + SPU + 数量"的可分配预览。与实际分配走同一份排序与同一份余量，
 * 所以这里的 {@code sufficient} 就是申请能否成立的预判。
 */
@Data
public class PpIdAvailableVo {

    private String productModel;

    private String spu;

    private Integer requestedQuantity;

    /** 该维度当前可分配的总量 */
    private Integer allocatableQuantity;

    /** 余量是否够本次申请 */
    private Boolean sufficient;

    /** 贪心拆分出的份额明细，余量多的先用 */
    private List<PpIdPlanVo> plan;

}
