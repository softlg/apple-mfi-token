package com.galaplat.mfi.mq;

import com.galaplat.mfi.common.enums.Stage;
import com.galaplat.mfi.config.PipelineProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 阶段与队列名的唯一映射来源。投递方、延迟档位选择与拓扑声明都从这里取名，
 * 三处不可能对不上——这是"改档位只改配置"能成立的前提。
 */
@Component
public class PipelineNames {

    /** 阶段队列名到阶段的反查表，消费者与兜底扫描用它把 queue 换回 Stage */
    private static final Map<Stage, String> STAGE_QUEUE = Map.of(
            Stage.APPLY, RabbitTopology.QUEUE_APPLY,
            Stage.FILE_NAME, RabbitTopology.QUEUE_NAME,
            Stage.FILE_CONTENT, RabbitTopology.QUEUE_CONTENT,
            Stage.REGISTER, RabbitTopology.QUEUE_REGISTER,
            Stage.FINISH, RabbitTopology.QUEUE_FINISH);

    private final PipelineProperties properties;

    public PipelineNames(PipelineProperties properties) {
        this.properties = properties;
    }

    public String stageQueue(Stage stage) {
        String queue = STAGE_QUEUE.get(stage);
        if (queue == null) {
            throw new IllegalArgumentException("阶段没有对应队列: " + stage);
        }
        return queue;
    }

    public Stage stageOfQueue(String queueName) {
        return STAGE_QUEUE.entrySet().stream()
                .filter(entry -> entry.getValue().equals(queueName))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("队列不属于任何阶段: " + queueName));
    }

    /**
     * 全部延迟队列声明。每个 (阶段, 档位) 一个队列，死信回流的路由键就是该阶段的队列名。
     */
    public List<DelayBucket> delayBuckets() {
        List<DelayBucket> buckets = new ArrayList<>();
        for (Stage stage : Stage.values()) {
            String stageQueue = stageQueue(stage);
            for (Long tier : tiers()) {
                buckets.add(new DelayBucket(delayQueueName(stageQueue, tier), tier, stageQueue));
            }
        }
        return buckets;
    }

    /**
     * 选延迟档位：取第一个不小于目标时长的档位；比最长档位还长时取最长档位，到货后消费者发现
     * 仍未到点，会再投一次——等待不消耗重试次数，所以长时间等待只是多绕一圈，不会把分片耗死。
     */
    public String delayQueue(Stage stage, long delayMillis) {
        List<Long> tiers = tiers();
        for (Long tier : tiers) {
            if (tier >= delayMillis) {
                return delayQueueName(stageQueue(stage), tier);
            }
        }
        return delayQueueName(stageQueue(stage), tiers.get(tiers.size() - 1));
    }

    public List<Long> tiers() {
        List<Long> tiers = new ArrayList<>(properties.getDelayTiers());
        if (tiers.isEmpty()) {
            throw new IllegalStateException("mfi.pipeline.delay-tiers 不能为空，否则延迟与重试无处投递");
        }
        tiers.sort(Long::compareTo);
        return tiers;
    }

    /**
     * 档位标签只用于队列名，要稳定可预测：同一个 ttl 在不同进程里必须算出同一个名字。
     */
    private static String delayQueueName(String stageQueue, long ttlMillis) {
        return "mfi.delay." + stageQueue.replace("mfi.", "").replace(".queue", "") + "." + label(ttlMillis);
    }

    private static String label(long ttlMillis) {
        if (ttlMillis % 3_600_000L == 0) {
            return (ttlMillis / 3_600_000L) + "h";
        }
        if (ttlMillis % 60_000L == 0) {
            return (ttlMillis / 60_000L) + "m";
        }
        return (ttlMillis / 1000L) + "s";
    }

    /**
     * @param queueName 延迟队列名
     * @param ttlMillis 该队列的固定消息存活时间
     * @param stageQueue 到期后死信回流到的阶段队列
     */
    public record DelayBucket(String queueName, long ttlMillis, String stageQueue) {
    }

}
