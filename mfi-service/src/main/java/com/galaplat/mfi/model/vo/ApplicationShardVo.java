package com.galaplat.mfi.model.vo;

import lombok.Data;

/**
 * 申请单的一个 PPID 分片。
 */
@Data
public class ApplicationShardVo {

    private Long shardId;

    private String ppid;

    private Integer allocateCount;

    /** Stage 枚举名 */
    private String stage;

    /** StageStatus 枚举名 */
    private String stageStatus;

    private Integer retryCount;

    private String lastError;

}
