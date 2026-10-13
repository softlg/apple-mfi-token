package com.galaplat.mfi.issuance.interfaces;

import lombok.Data;

import java.time.LocalDateTime;
import com.galaplat.mfi.issuance.domain.ApplicationStatus;

/**
 * 申请单的一行。分片明细不在这里，按 applicationNo 单独取。
 */
@Data
public class ApplicationVo {

    private String applicationNo;

    private String productModel;

    private String spu;

    private String appleTechnology;

    private Integer applyQuantity;

    /** 拆出的 PPID 分片数 */
    private Integer ppidCount;

    /** 已成功落库的 token 数，异步流水线推进 */
    private Integer tokenCount;

    /** ApplicationStatus 枚举名 */
    private String status;

    private String failReason;

    private String applicantName;

    private LocalDateTime createTime;

    private LocalDateTime finishTime;

}
