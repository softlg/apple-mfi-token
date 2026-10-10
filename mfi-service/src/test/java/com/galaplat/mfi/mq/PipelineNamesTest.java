package com.galaplat.mfi.mq;

import com.galaplat.mfi.common.enums.Stage;
import com.galaplat.mfi.config.PipelineProperties;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 队列名与延迟档位的唯一来源，以及由它派生的拓扑。这里要保证的是"改配置就等于改拓扑"这条承诺成立：
 * 投递方、档位选择、声明三处必须永远算出同一批名字，否则延迟消息会投进一个没人声明的队列。
 */
class PipelineNamesTest {

    private static final List<Long> TIERS = List.of(60_000L, 300_000L, 1_800_000L, 3_600_000L);

    private static PipelineNames names(List<Long> tiers) {
        PipelineProperties properties = new PipelineProperties();
        properties.setDelayTiers(tiers);
        return new PipelineNames(properties);
    }

    @Test
    void everyStageHasItsOwnQueue() {
        PipelineNames names = names(TIERS);
        for (Stage stage : Stage.values()) {
            assertTrue(names.stageQueue(stage).startsWith("mfi.") && names.stageQueue(stage).endsWith(".queue"),
                    stage + " -> " + names.stageQueue(stage));
            // 消费端与扫描只认队列名，反查必须回到同一个阶段
            assertEquals(stage, names.stageOfQueue(names.stageQueue(stage)));
        }
    }

    @Test
    void delayTierIsRoundedUpSoMessagesNeverArriveEarly() {
        PipelineNames names = names(TIERS);
        // 90 秒落在 5 分钟档：只会晚到，不会早到
        assertTrue(names.delayQueue(Stage.FILE_NAME, 90_000L).endsWith(".5m"));
        assertTrue(names.delayQueue(Stage.APPLY, 60_000L).endsWith(".1m"));
        assertTrue(names.delayQueue(Stage.FILE_CONTENT, 1L).endsWith(".1m"));
        // 比最长档位还长（苹果说"三小时后再来"）取最长档，到货后由消费者再排一次
        assertTrue(names.delayQueue(Stage.FILE_NAME, 3 * 3_600_000L).endsWith(".1h"));
    }

    @Test
    void bucketNamesAreStableAcrossTtlLabels() {
        PipelineNames names = names(TIERS);
        Set<String> bucketNames = names.delayBuckets().stream()
                .map(PipelineNames.DelayBucket::queueName)
                .collect(Collectors.toSet());
        assertEquals(Stage.values().length * TIERS.size(), bucketNames.size());
        assertTrue(bucketNames.contains("mfi.delay.apply.1m"));
        assertTrue(bucketNames.contains("mfi.delay.register.1h"));
        assertTrue(bucketNames.stream().noneMatch(name -> name.contains(".queue.")), "队列名不该嵌进档位名");
    }

    @Test
    void unorderedTiersAreSortedBeforeTheyBecomeQueueNames() {
        PipelineNames names = names(List.of(3_600_000L, 60_000L, 300_000L));
        assertEquals(List.of(60_000L, 300_000L, 3_600_000L), names.tiers());
        // 向上取整依赖升序；乱序配置如果直接取第一个匹配就会选到最长档
        assertTrue(names.delayQueue(Stage.APPLY, 61_000L).endsWith(".5m"));
    }

    @Test
    void emptyTiersFailFastInsteadOfSilentlyDroppingDelays() {
        PipelineNames names = names(List.of());
        assertThrows(IllegalStateException.class, names::tiers);
    }

    @Test
    void unknownQueueDoesNotMapToAStage() {
        PipelineNames names = names(TIERS);
        assertThrows(IllegalArgumentException.class, () -> names.stageOfQueue("some.other.queue"));
    }

    @Test
    void topologyDeclaresStageDlqAndEveryDelayBucket() {
        PipelineNames names = names(TIERS);
        DirectExchange normal = new DirectExchange(RabbitTopology.EXCHANGE_NORMAL, true, false);
        DirectExchange delay = new DirectExchange(RabbitTopology.EXCHANGE_DELAY, true, false);
        Declarables declarables = new RabbitTopology().pipelineDeclarables(names, normal, delay);

        Map<String, Queue> queues = declarables.getDeclarables().stream()
                .filter(Queue.class::isInstance)
                .map(Queue.class::cast)
                .collect(Collectors.toMap(Queue::getName, queue -> queue));

        int stageQueues = Stage.values().length;
        assertEquals(stageQueues + 1 + names.delayBuckets().size(), queues.size(),
                "五个阶段队列 + 一个死信 + 全部延迟档位");
        for (PipelineNames.DelayBucket bucket : names.delayBuckets()) {
            Queue bucketQueue = queues.get(bucket.queueName());
            assertEquals((int) bucket.ttlMillis(), arg(bucketQueue, "x-message-ttl"),
                    bucket.queueName() + " 的 TTL 必须与档位一致，同队列内才不会队头阻塞");
            assertEquals(RabbitTopology.EXCHANGE_NORMAL, arg(bucketQueue, "x-dead-letter-exchange"));
            assertEquals(bucket.stageQueue(), arg(bucketQueue, "x-dead-letter-routing-key"));
            assertTrue(bucketQueue.isDurable(), "已受理的申请不能因 broker 重启而消失");
        }
        assertTrue(queues.containsKey(RabbitTopology.QUEUE_DLQ));
        // 阶段队列自身不带 TTL：即时投递就该立刻可消费
        assertEquals(null, arg(queues.get(RabbitTopology.QUEUE_APPLY), "x-message-ttl"));

        Set<String> delayBindings = declarables.getDeclarables().stream()
                .filter(Binding.class::isInstance)
                .map(Binding.class::cast)
                .filter(binding -> RabbitTopology.EXCHANGE_DELAY.equals(binding.getExchange()))
                .map(binding -> binding.getDestination() + "/" + binding.getRoutingKey())
                .collect(Collectors.toSet());
        assertEquals(names.delayBuckets().size(), delayBindings.size(), "每个档位都要挂上延迟 exchange");
        for (PipelineNames.DelayBucket bucket : names.delayBuckets()) {
            // 延迟 exchange 用档位队列名当 routing key，投递方才不必再维护第二张映射表
            assertTrue(delayBindings.contains(bucket.queueName() + "/" + bucket.queueName()),
                    bucket.queueName() + " 没有可路由的绑定");
        }
    }

    @Test
    void stageQueuesAreBoundToTheNormalExchangeByTheirOwnName() {
        PipelineNames names = names(TIERS);
        Declarables declarables = new RabbitTopology().pipelineDeclarables(names,
                new DirectExchange(RabbitTopology.EXCHANGE_NORMAL, true, false),
                new DirectExchange(RabbitTopology.EXCHANGE_DELAY, true, false));

        Map<String, String> normalBindings = declarables.getDeclarables().stream()
                .filter(Binding.class::isInstance)
                .map(Binding.class::cast)
                .filter(binding -> RabbitTopology.EXCHANGE_NORMAL.equals(binding.getExchange()))
                .collect(Collectors.toMap(Binding::getDestination, Binding::getRoutingKey));
        for (Stage stage : Stage.values()) {
            assertEquals(names.stageQueue(stage), normalBindings.get(names.stageQueue(stage)),
                    stage + " 的即时消息无处可去");
        }
        // 死信与延迟都靠 routing key 认路，少一个绑定就等于消息进不了队列
        assertEquals(RabbitTopology.QUEUE_DLQ, normalBindings.get(RabbitTopology.QUEUE_DLQ),
                "终态失败的消息进不了死信队列");
    }

    private static Object arg(Queue queue, String key) {
        return queue.getArguments() == null ? null : queue.getArguments().get(key);
    }

}
