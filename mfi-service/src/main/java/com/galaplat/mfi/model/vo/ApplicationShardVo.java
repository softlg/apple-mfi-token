package com.galaplat.mfi.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

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

    /** 下一次执行时间：PENDING 且晚于当前时间就是"在延迟队列里等苹果生成文件" */
    private LocalDateTime nextExecuteTime;

}
