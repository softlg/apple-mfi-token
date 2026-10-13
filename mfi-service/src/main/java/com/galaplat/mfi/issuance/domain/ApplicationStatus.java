package com.galaplat.mfi.issuance.domain;

/**
 * 申请单聚合状态，由 PPID 分片状态聚合得出。
 */
public enum ApplicationStatus {

    /** 已落库、消息尚未被消费 */
    PENDING,
    /** 至少一个分片在推进中 */
    RUNNING,
    /** 全部分片到达 FINISH/SUCCESS */
    SUCCESS,
    /** 部分分片成功、部分失败 */
    PARTIAL_FAILED,
    /** 全部分片失败或已进入 DEAD */
    FAILED;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == PARTIAL_FAILED;
    }

}
