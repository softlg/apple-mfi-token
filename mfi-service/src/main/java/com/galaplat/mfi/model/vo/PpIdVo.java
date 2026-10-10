package com.galaplat.mfi.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * PPID 池里的一份额度。
 */
@Data
public class PpIdVo {

    private Long id;

    private String ppid;

    private String spu;

    private String productModel;

    private Integer totalQuantity;

    private Integer appliedQuantity;

    private Integer remainingQuantity;

    /** 1=可用 0=停用 */
    private Integer status;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

}
