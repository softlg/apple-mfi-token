package com.galaplat.mfi.model.vo;

import lombok.Data;

import java.util.List;

/**
 * 人工重放的结果。
 */
@Data
public class ApplicationRetryVo {

    private String applicationNo;

    /** 实际复位并重新入队的分片数；0 表示这张单没有终态失败分片，属于空操作 */
    private Integer replayedCount;

    /** 复位后的分片状态（stage 不变，stage_status 回到 PENDING） */
    private List<ApplicationShardVo> shards;

}
