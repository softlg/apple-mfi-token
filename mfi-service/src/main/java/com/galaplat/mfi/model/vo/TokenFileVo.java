package com.galaplat.mfi.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 苹果下发的一个 token 文件（xlsx）的下载结果。
 */
@Data
public class TokenFileVo {

    private Long fileId;

    private Long shardId;

    private String ppid;

    private String fileName;

    /** 解析入库的 token 数，下载未成功时为空 */
    private Integer tokenCount;

    /** PENDING/PROCESSING/SUCCESS/FAILED */
    private String downloadStatus;

    private Integer retryCount;

    private LocalDateTime downloadTime;

    /** 最近一次失败原因，成功下载后由 SQL 置空 */
    private String lastError;

}
