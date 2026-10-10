package com.galaplat.mfi.pipeline;

import com.galaplat.mfi.config.PipelineProperties;
import com.galaplat.mfi.dao.entity.ApplicationPpidPO;
import com.galaplat.mfi.dao.mapper.ApplicationPpidMapper;
import com.galaplat.mfi.mq.ShardMessageProducer;
import com.galaplat.mfi.mq.StageMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 兜底扫描：把"该跑却没跑"和"卡住在跑"的分片捞回来。
 *
 * <p>P4 的取舍是"申请事务提交后投递失败只记 ERROR"，P5 的取舍是"先写库再投递、投递失败不回滚"。
 * 两者都成立的前提就是这里：消息可以丢，状态不能死。没有这个扫描，任何一次 broker 抖动或消费者崩溃
 * 都会留下永远不动的分片，而这正是 MES 靠人工补偿端点（11 个）在填的坑。
 *
 * <p>两条互补的复位路径：
 * <ul>
 *   <li>PENDING 且 next_execute_time 已过宽限窗口 → 直接补发。覆盖投递失败、broker 丢消息。</li>
 *   <li>PROCESSING 且超过 processingTimeoutMinutes → 放回 PENDING，下一轮被上一条捞走。
 *       覆盖消费者崩溃。分两步而不是一步补发，是为了让"复位"与"补发"各自的判定条件保持简单。</li>
 * </ul>
 */
@Slf4j
@Component
public class PipelineScanner {

    /** 补发消息的 traceId 前缀，日志里一眼能区分"扫描补发"与"申请直达" */
    static final String TRACE_PREFIX = "recovery";

    private final ApplicationPpidMapper shardMapper;
    private final ShardMessageProducer producer;
    private final PipelineProperties properties;

    public PipelineScanner(ApplicationPpidMapper shardMapper, ShardMessageProducer producer,
                           PipelineProperties properties) {
        this.shardMapper = shardMapper;
        this.producer = producer;
        this.properties = properties;
    }

    @Scheduled(initialDelayString = "${mfi.pipeline.scan-interval-millis:30000}",
            fixedDelayString = "${mfi.pipeline.scan-interval-millis:30000}")
    public void scan() {
        try {
            int reset = resetStaleProcessing();
            int republished = republishDueShards();
            if (reset > 0 || republished > 0) {
                log.info("兜底扫描: 复位 PROCESSING {} 个，补发 PENDING {} 个", reset, republished);
            }
        } catch (Exception e) {
            // 扫描本身失败不能带崩调度线程：下一轮还会再试，这里只把问题暴露到日志
            log.error("兜底扫描异常，下一轮继续", e);
        }
    }

    private int republishDueShards() {
        LocalDateTime deadline = LocalDateTime.now().minusSeconds(properties.getScanGraceSeconds());
        List<ApplicationPpidPO> due = shardMapper.selectDueForRecovery(deadline, properties.getScanBatchSize());
        for (ApplicationPpidPO shard : due) {
            producer.publishStage(new StageMessage(shard.getTenantId(), shard.getApplicationId(),
                    shard.getId(), shard.getStage(), TRACE_PREFIX));
        }
        return due.size();
    }

    private int resetStaleProcessing() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(properties.getProcessingTimeoutMinutes());
        int rows = shardMapper.resetStaleProcessing(cutoff, properties.getScanBatchSize());
        if (rows > 0) {
            log.warn("发现卡死的分片并放回待处理: count={}, cutoff={}", rows, cutoff);
        }
        return rows;
    }

}
