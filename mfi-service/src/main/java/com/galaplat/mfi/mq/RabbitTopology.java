package com.galaplat.mfi.mq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑中"申请受理"这一段。
 *
 * <p>只用普通 exchange：延迟靠 TTL + 死信回流，不装 delayed-message 插件（部署面优先）。P5 会在同一个
 * exchange 上补齐后四个阶段的队列与延迟队列，本阶段只声明真正会被投递的 queue。
 */
@Configuration
public class RabbitTopology {

    /** 五个阶段的普通投递都走这一个 exchange，routing key 即阶段队列名 */
    public static final String EXCHANGE_NORMAL = "mfi.token.normal";

    public static final String QUEUE_APPLY = "mfi.apply.queue";

    /**
     * 持久队列：broker 重启不能丢掉已受理的申请，否则只能靠兜底扫描从库里补发。
     */
    @Bean
    public DirectExchange tokenNormalExchange() {
        return new DirectExchange(EXCHANGE_NORMAL, true, false);
    }

    @Bean
    public Queue applyQueue() {
        return QueueBuilder.durable(QUEUE_APPLY).build();
    }

    @Bean
    public Binding applyQueueBinding() {
        return BindingBuilder.bind(applyQueue()).to(tokenNormalExchange()).with(QUEUE_APPLY);
    }

    /**
     * 消息体用 JSON 而不是 JDK 序列化：后者会把 Java 类结构绑进 broker，滚动升级时消费端类一改就读不出旧消息。
     * 只声明转换器，RabbitTemplate 交给 Boot 自动装配，免得覆盖掉它带的 confirm/return 配置。
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

}
