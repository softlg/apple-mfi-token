package com.galaplat.mfi.pipeline;

import com.galaplat.mfi.mq.RabbitTopology;
import com.galaplat.mfi.mq.ShardMessageProducer;
import com.galaplat.mfi.mq.StageMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * 死信队列消费者：只做一件事——把"哪个分片为什么死了"打成一条可被日志告警抓到的 ERROR。
 *
 * <p>不自动重放。分片进 DEAD 意味着重试已耗尽或属于永久失败，自动重放等于把一个需要人来判断的问题
 * 变成持续的苹果调用。人工重放走 P6 的 {@code POST /api/applications/{no}/retry}。
 *
 * <p>也不 ACK 之后再抛异常：容器已经是 auto ACK，抛出去只会让消息消失或重投，两种都不是这里的意图。
 */
@Slf4j
@Component
public class DeadLetterListener {

    @RabbitListener(queues = RabbitTopology.QUEUE_DLQ)
    public void onDead(StageMessage message, @Header(name = ShardMessageProducer.HEADER_DEAD_REASON,
            defaultValue = "未提供原因") String reason) {
        log.error("需要人工介入-分片已进入死信: tenantId={}, applicationId={}, shardId={}, stage={}, reason={}, traceId={}",
                message.tenantId(), message.applicationId(), message.shardId(), message.stage(),
                reason, message.traceId());
    }

}
