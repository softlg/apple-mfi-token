package com.galaplat.mfi.issuance.domain;

/**
 * 阶段内状态。分片在某一阶段上的推进位置。
 *
 * <p>PENDING -&gt; PROCESSING -&gt; SUCCESS 为正常路径；失败且未耗尽重试次数回到 PENDING
 * 并设置 next_execute_time；耗尽则进入 DEAD 并投递死信队列等待人工介入。
 */
public enum StageStatus {

    PENDING,
    PROCESSING,
    SUCCESS,
    FAILED,
    DEAD;

    /**
     * 是否还可以被消费者领取。只有 PENDING 可领取，PROCESSING 由 CAS 独占。
     */
    public boolean isClaimable() {
        return this == PENDING;
    }

    public boolean isTerminalFailure() {
        return this == DEAD;
    }

}
