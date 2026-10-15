package com.galaplat.mfi.issuance.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import com.galaplat.mfi.identity.domain.TenantConfigKey;

/**
 * 流水线运行参数。都是运维语义而不是业务语义，所以放配置文件而不进 {@code t_tenant_config}：
 * 延迟档位必须与队列拓扑一一对应（档位改动等于改队列名），不能由租户随时改。
 *
 * <p>业务侧的次数与并发上限（重试上限、租户并发）仍然读租户配置，见
 * {@link com.galaplat.mfi.identity.domain.TenantConfigKey}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mfi.pipeline")
public class PipelineProperties {

    /**
     * 延迟档位（毫秒），升序。每个档位是一段独立队列，队列级 {@code x-message-ttl} 固定。
     *
     * <p>为什么按档位分队列而不是往一个队列里塞不同 TTL 的消息：RabbitMQ 只能按队头判断过期，
     * 队头是一条 1 小时的消息时，它后面 1 分钟到期的重试消息会被堵住不动。MES 的延迟队列正是
     * 每条消息各设 expiration 的写法（{@code TokenMessageService:50}），同一次申请里两个不同
     * retryDownloadAfter 的分片就会互相拖累。档位分队列以后同一队列内 TTL 相同，队头即最早到期。
     *
     * <p>需要的时间向上取整到最近档位，所以只会晚到不会早到；超过最大档位的等待（苹果说"一小时后再来"）
     * 由消费者到货后再投一次下一档，等待本身不消耗重试次数。
     */
    private List<Long> delayTiers = List.of(60_000L, 300_000L, 1_800_000L, 3_600_000L);

    /** 兜底扫描间隔 */
    private long scanIntervalMillis = 30_000L;

    /**
     * 兜底扫描的宽限窗口：只补发 next_execute_time 早于"现在 - 该窗口"的分片。
     *
     * <p>不给宽限的话，扫描会和正在飞的正常消息抢同一行——每次阶段推进都把 next_execute_time 写成
     * "刚刚"（用于丢消息时自愈），按 now 比较等于每一跳都可能出现两条并发消息。CAS 能保证不重复调苹果，
     * 但没必要靠它做常规去重。
     */
    private int scanGraceSeconds = 90;

    /** 单次扫描最多补发多少分片，防止故障恢复时一次性把队列打满 */
    private int scanBatchSize = 200;

    /**
     * PROCESSING 的最长驻留时间。超过就认为持有它的消费者已经不在（进程被杀、机器重启、长时间挂死），
     * 由扫描复位成 PENDING。
     *
     * <p>判据是分片的 {@code update_time}，而按文件、按批推进的两个处理器会在每轮之前续租，所以这里真正
     * 约束的是"两次续租之间的最长间隔"——即单个文件的下载解析或单批注册的耗时，不是整片 token 的总耗时。
     * 30 分钟对一次苹果调用（读超时 5 分钟）留了足够余量；把它调小到比一次调用还短，就会出现同一跳
     * 两个消费者，而 APPLY 那一路的重复提交是直接花苹果额度的。
     */
    private int processingTimeoutMinutes = 30;

    /** 拿不到租户并发额度时，多久以后再试。这一段等待不算失败重试 */
    private long gateRetryMillis = 30_000L;
}
