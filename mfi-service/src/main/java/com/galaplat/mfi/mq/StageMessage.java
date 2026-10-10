package com.galaplat.mfi.mq;

import com.galaplat.mfi.common.enums.Stage;

/**
 * 阶段推进消息。
 *
 * <p>只带 ID：token 内容、PPID 清单、凭证一律不进消息体。消息会被 broker 落盘、进死信、被运维工具
 * 整条打印，任何业务载荷进去都等于泄露面扩大；消费者拿到 ID 回库读，顺带保证了"以库为准"。
 *
 * @param tenantId      租户，消费者用它绑定 {@code TenantContext}
 * @param applicationId 申请单
 * @param shardId       分片行 ID，阶段跃迁的 CAS 目标
 * @param stage         本次要执行的阶段（Stage 枚举名）
 * @param traceId       一次受理的跟踪号，用于把同步段与五个阶段的日志串起来
 */
public record StageMessage(long tenantId, long applicationId, long shardId, String stage, String traceId) {

    /**
     * 阶段名换成枚举：队列与延迟档位都按阶段决定，非法阶段名必须在投递前炸掉，
     * 不能让分片停在一个没人认识的阶段上反复投递失败。
     */
    public Stage stageEnum() {
        return Stage.valueOf(stage);
    }

}
