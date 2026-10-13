package com.galaplat.mfi.issuance.infrastructure.mq;

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
 * 消息已经进了 broker，苹果侧于是被申请了一笔库里不存在的量。申请受理路径因此把投递挂到事务提交回调上，
 * 回滚则一条都不发。
 *
 * <p>流水线内部的阶段间投递不挂事务：一次阶段推进是多条 autocommit 语句，中间还夹着苹果调用，
 * 不能为了"提交后再发"把事务横跨在网络请求上。这里的保证来自 CAS 已经独占住分片行——投递失败
 * 由兜底扫描按 next_execute_time 补发，而不是靠回滚保持一致。
 */
@Slf4j
@Component
public class ShardMessageProducer {

    /** 死信消息上的失败原因：只进 header，让运维在队列里直接看到"为什么死"，不改消息体形状 */
    public static final String HEADER_DEAD_REASON = "x-mfi-dead-reason";

    private final RabbitTemplate rabbitTemplate;
    private final PipelineNames names;

    public ShardMessageProducer(RabbitTemplate rabbitTemplate, PipelineNames names) {
        this.rabbitTemplate = rabbitTemplate;
        this.names = names;
    }

    /**
     * 申请受理后投递首批消息，事务提交后再发。必须在活动事务内调用——脱离事务调用意味着调用方漏了事务边界，
     * 那种写法正是 MES 出问题的形状，所以直接失败而不是静默降级为立即发送。
     *
     * <p>提交后投递失败不回滚申请单（单子已经生效），只记错误：分片仍是 PENDING，由兜底扫描补发。
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
                messages.forEach(message -> send(names.stageQueue(message.stageEnum()), message,
                        null, RabbitTopology.EXCHANGE_NORMAL));
            }
        });
    }

    /** 阶段推进完成后投递下一阶段的即时消息 */
    public void publishStage(StageMessage message) {
        send(names.stageQueue(message.stageEnum()), message, null, RabbitTopology.EXCHANGE_NORMAL);
    }

    /** 延迟投递：档位由 {@link PipelineNames} 按目标时长向上取整，所以只会晚到不会早到 */
    public void publishDelayed(StageMessage message, long delayMillis) {
        send(names.delayQueue(message.stageEnum(), delayMillis), message, null, RabbitTopology.EXCHANGE_DELAY);
    }

    /**
     * 重试耗尽或永久失败：消息进死信队列留痕。分片行本身已经是 DEAD，这条消息只给运维排查与人工重放用。
     */
    public void publishDead(StageMessage message, String reason) {
        send(RabbitTopology.QUEUE_DLQ, message, truncate(reason), RabbitTopology.EXCHANGE_NORMAL);
    }

    private void send(String routingKey, StageMessage message, String reason, String exchange) {
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, message, mq -> {
                if (reason != null) {
                    mq.getMessageProperties().setHeader(HEADER_DEAD_REASON, reason);
                }
                return mq;
            });
            log.info("分片消息已投递: queue={}, tenantId={}, applicationId={}, shardId={}, stage={}, traceId={}",
                    routingKey, message.tenantId(), message.applicationId(), message.shardId(),
                    message.stage(), message.traceId());
        } catch (Exception e) {
            log.error("分片消息投递失败，等待兜底扫描补发: queue={}, tenantId={}, applicationId={}, shardId={}, stage={}, traceId={}",
                    routingKey, message.tenantId(), message.applicationId(), message.shardId(),
                    message.stage(), message.traceId(), e);
        }
    }

    private static String truncate(String reason) {
        if (reason == null || reason.isBlank()) {
            return "未提供原因";
        }
        return reason.length() <= 500 ? reason : reason.substring(0, 500);
    }

}
