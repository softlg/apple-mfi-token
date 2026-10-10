package com.galaplat.mfi.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 苹果下发的一个 token 文件（xlsx）。一个 request 可能给多个文件，逐文件独立推进下载，
 * 这样单个文件失败只会重试那一个文件，而不是把整份申请重新下载。
 */
@Data
@TableName("t_token_file")
public class TokenFilePO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    private Long applicationId;

    /** 指向 {@link ApplicationPpidPO#getId()} */
    private Long shardId;

    private String ppid;

    private String appleRequestUuid;

    private String fileName;

    private Integer tokenCount;

    /** PENDING/PROCESSING/SUCCESS/FAILED */
    private String downloadStatus;

    private Integer retryCount;

    private String lastError;

    private LocalDateTime downloadTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer deleted;

}
