package com.galaplat.mfi.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * 分片消息投递。
 *
 * <p>MES 的幽灵申请根因就在这里：它在 {@code @Transactional} 方法内部直接 convertAndSend，事务回滚了，
 * 消息已经进了 broker，苹果侧于是被申请了一笔库里不存在的量。这里把投递挂到事务提交的回调上，
 * 回滚则一条都不发。
 */
@Slf4j
@Component
public class ShardMessageProducer {

    private final RabbitTemplate rabbitTemplate;

    public ShardMessageProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 事务提交后再发。必须在活动事务内调用——脱离事务调用意味着调用方漏了事务边界，
     * 那种写法正是 MES 出问题的形状，所以直接失败而不是静默降级为立即发送。
     *
     * <p>提交后投递失败不回滚申请单（单子已经生效），只记错误：分片仍是 PENDING，
     * 由 P5 的兜底扫描按 next_execute_time 补发。
     */
    public void publishAfterCommit(List<StageMessage> messages) {
        if (messages.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("消息投递必须在申请事务内登记，否则回滚后消息已发出");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                messages.forEach(ShardMessageProducer.this::send);
            }
        });
    }

    private void send(StageMessage message) {
        try {
            rabbitTemplate.convertAndSend(RabbitTopology.EXCHANGE_NORMAL,
                    RabbitTopology.QUEUE_APPLY, message);
            log.info("分片消息已投递: tenantId={}, applicationId={}, shardId={}, stage={}, traceId={}",
                    message.tenantId(), message.applicationId(), message.shardId(), message.stage(), message.traceId());
        } catch (Exception e) {
            log.error("分片消息投递失败，等待兜底扫描补发: tenantId={}, applicationId={}, shardId={}, stage={}, traceId={}",
                    message.tenantId(), message.applicationId(), message.shardId(), message.stage(), message.traceId(), e);
        }
    }

}
