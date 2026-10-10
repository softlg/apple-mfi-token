package com.galaplat.mfi.model.vo;

import lombok.Data;

/**
 * 预览拆分出的一行：从哪份额度取多少。
 */
@Data
public class PpIdPlanVo {

    private Long ppidId;

    private String ppid;

    /** 本次从这份额度取走的数量 */
    private Integer quantity;

    /** 取走之前这份额度的余量，便于前端核对拆分是否合理 */
    private Integer remainingBefore;

}
