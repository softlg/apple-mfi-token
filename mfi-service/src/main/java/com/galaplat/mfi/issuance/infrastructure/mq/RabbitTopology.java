package com.galaplat.mfi.issuance.infrastructure.mq;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JavaTypeMapper.TypePrecedence;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Token 流水线的 RabbitMQ 拓扑：五个阶段队列 + 按档位分开的延迟队列 + 一个死信队列。
 *
 * <p>延迟一律用 TTL + 死信回流，不装 {@code rabbitmq_delayed_message_exchange} 插件：本机与将来服务器
 * 都要少一个部署依赖，而插件的 x-delay 语义在这里并不比固定档位更有用。
 *
 * <p>所有队列都持久化：已受理的申请不能因为 broker 重启而消失。真丢了也能靠兜底扫描从库里补发，
 * 但那条路径要求分片一直停在 PENDING，重启期间消费者不在，把消息留在 broker 里明显更稳。
 */
@Configuration
public class RabbitTopology {

    /** 五个阶段的即时投递都走这一个 exchange，routing key 即阶段队列名 */
    public static final String EXCHANGE_NORMAL = "mfi.token.normal";

    /** 延迟投递走这里，routing key 即延迟队列名；消息到期由队列参数死信回 normal */
    public static final String EXCHANGE_DELAY = "mfi.token.delay";

    public static final String QUEUE_APPLY = "mfi.apply.queue";
    public static final String QUEUE_NAME = "mfi.name.queue";
    public static final String QUEUE_CONTENT = "mfi.content.queue";
    public static final String QUEUE_REGISTER = "mfi.register.queue";
    public static final String QUEUE_FINISH = "mfi.finish.queue";

    /** 重试耗尽或永久失败的分片消息。只留痕与告警，不自动重放——重放是人工决策，见 P6 的 retry 接口 */
    public static final String QUEUE_DLQ = "mfi.token.dlq";

    private static final List<String> STAGE_QUEUES =
            List.of(QUEUE_APPLY, QUEUE_NAME, QUEUE_CONTENT, QUEUE_REGISTER, QUEUE_FINISH);

    @Bean
    public DirectExchange tokenNormalExchange() {
        return new DirectExchange(EXCHANGE_NORMAL, true, false);
    }

    @Bean
    public DirectExchange tokenDelayExchange() {
        return new DirectExchange(EXCHANGE_DELAY, true, false);
    }

    /**
     * 阶段队列、死信队列与全部延迟档位队列一次声明完。
     *
     * <p>用 {@link Declarables} 而不是每个队列一个 {@code @Bean}：档位由配置驱动，20 个延迟队列
     * 写 20 个方法只会让"配置改了、拓扑没改"成为可能。
     */
    @Bean
    public Declarables pipelineDeclarables(PipelineNames names, DirectExchange tokenNormalExchange,
                                           DirectExchange tokenDelayExchange) {
        List<org.springframework.amqp.core.Declarable> declarables = new ArrayList<>();
        for (String queueName : STAGE_QUEUES) {
            Queue queue = QueueBuilder.durable(queueName).build();
            declarables.add(queue);
            declarables.add(BindingBuilder.bind(queue).to(tokenNormalExchange).with(queueName));
        }

        Queue dlq = QueueBuilder.durable(QUEUE_DLQ).build();
        declarables.add(dlq);
        declarables.add(BindingBuilder.bind(dlq).to(tokenNormalExchange).with(QUEUE_DLQ));

        for (PipelineNames.DelayBucket bucket : names.delayBuckets()) {
            // 同一队列内 TTL 相同，队头即最早到期，不存在长 TTL 堵住短 TTL 的问题；
            // 死信路由键显式写回本档位对应的阶段队列，延迟队列自身的名字不当路由键用
            Queue delayQueue = QueueBuilder.durable(bucket.queueName())
                    .ttl((int) bucket.ttlMillis())
                    .deadLetterExchange(EXCHANGE_NORMAL)
                    .deadLetterRoutingKey(bucket.stageQueue())
                    .build();
            declarables.add(delayQueue);
            declarables.add(BindingBuilder.bind(delayQueue).to(tokenDelayExchange).with(bucket.queueName()));
        }
        return new Declarables(declarables);
    }

    /**
     * 消息体用 JSON 而不是 JDK 序列化：后者会把 Java 类结构绑进 broker，滚动升级时消费端类一改就读不出旧消息。
     * 只声明转换器，RabbitTemplate 交给 Boot 自动装配，免得覆盖掉它带的 confirm/return 配置。
     *
     * <p>类型判定显式钉成 INFERRED：Jackson 默认把生产端的类全名写进 {@code __TypeId__} 头，若消费端按头反查，
     * 那么包名搬家或类改名都会让升级期间在途的消息抛 MessageConversionException 掉进死信。按监听方法的
     * 签名推断只认 StageMessage 这个契约，而消息体里本来就只有 ID。
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
        converter.setTypePrecedence(TypePrecedence.INFERRED);
        return converter;
    }

}
