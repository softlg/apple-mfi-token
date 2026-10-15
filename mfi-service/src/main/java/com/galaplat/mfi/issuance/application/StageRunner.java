package com.galaplat.mfi.issuance.application;

import com.galaplat.mfi.identity.domain.AuditFact;
import com.galaplat.mfi.identity.domain.OperationAction;
import com.galaplat.mfi.issuance.domain.Stage;
import com.galaplat.mfi.issuance.domain.StageStatus;
import com.galaplat.mfi.identity.domain.TenantConfigKey;
import com.galaplat.mfi.shared.api.BizException;
import com.galaplat.mfi.issuance.config.PipelineProperties;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPO;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPpidPO;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationMapper;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPpidMapper;
import com.galaplat.mfi.shared.tenant.TenantContext;
import com.galaplat.mfi.shared.tenant.TenantPrincipal;
import com.galaplat.mfi.issuance.infrastructure.mq.ShardMessageProducer;
import com.galaplat.mfi.issuance.infrastructure.mq.StageMessage;
import com.galaplat.mfi.identity.application.AuditTrail;
import com.galaplat.mfi.identity.application.TenantConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import com.galaplat.mfi.issuance.domain.StageContext;
import com.galaplat.mfi.issuance.domain.StageHandler;
import com.galaplat.mfi.issuance.domain.StageOutcome;

/**
 * 阶段执行的唯一入口：领取分片、绑租户上下文、占并发额度、调处理器、按结论跃迁并投递。
 *
 * <p>三条不可让的约束写在这里，五个阶段共享：
 * <ol>
 *   <li><b>先 CAS 领取再干活</b>。领取返回 0 就不调苹果——重复投递、消费者重启、兜底扫描与延迟消息
 *       撞车都会走到这里，这是唯一的去重点。</li>
 *   <li><b>先写库再投递</b>。跃迁落库后投递失败只记日志，由兜底扫描按 next_execute_time 补发；
 *       反过来（先投递后写库）会让下一跳的消费者读到还没落定的状态。</li>
 *   <li><b>不向 AMQP 容器抛业务异常</b>。容器是 auto ACK 且关闭了 Spring 层重试，抛出去只会让消息
 *       在原队列重投。所有失败都收敛成 {@link StageOutcome}；兜不住的异常就地记错误，把分片留给
 *       扫描复位。</li>
 * </ol>
 *
 * <p>领取成功到跃迁落库之间，分片行只剩 {@code update_time} 能证明消费者还活着。单跳一次的阶段（APPLY、
 * FILE_NAME、FINISH）由苹果调用的超时天然界定；按文件、按批循环的两跳（FILE_CONTENT、REGISTER）必须在
 * 每轮之前续租，否则一片大申请会被兜底扫描误判成消费者已死并复位，出现同一跳两个消费者。
 */
@Slf4j
@Component
public class StageRunner {

    /**
     * 早到容差。延迟档位向上取整，消息可能比 next_execute_time 早到几十秒；只有明显早到才重排，
     * 免得边界上的正常投递被反复改期。
     */
    private static final int EARLY_TOLERANCE_SECONDS = 60;

    private final Map<Stage, StageHandler> handlers = new EnumMap<>(Stage.class);
    private final ApplicationPpidMapper shardMapper;
    private final ApplicationMapper applicationMapper;
    private final AuditTrail auditTrail;
    private final TenantConfigService configService;
    private final TenantConcurrencyGate gate;
    private final ShardMessageProducer producer;
    private final ApplicationAggregator aggregator;
    private final PipelineProperties properties;

    public StageRunner(List<StageHandler> stageHandlers, ApplicationPpidMapper shardMapper,
                       ApplicationMapper applicationMapper, AuditTrail auditTrail,
                       TenantConfigService configService, TenantConcurrencyGate gate,
                       ShardMessageProducer producer, ApplicationAggregator aggregator,
                       PipelineProperties properties) {
        stageHandlers.forEach(handler -> handlers.put(handler.stage(), handler));
        this.shardMapper = shardMapper;
        this.applicationMapper = applicationMapper;
        this.auditTrail = auditTrail;
        this.configService = configService;
        this.gate = gate;
        this.producer = producer;
        this.aggregator = aggregator;
        this.properties = properties;
    }

    /**
     * 消费一条阶段消息。MQ 线程没有 HTTP 拦截器，租户上下文必须在入口绑定，否则租户 SQL 改写会直接抛错
     * ——这也是消息只带 ID 的另一层意义：一切都以库里这一行的当前状态为准。
     */
    public void run(StageMessage message) {
        Stage stage;
        try {
            stage = message.stageEnum();
        } catch (IllegalArgumentException e) {
            log.error("未知阶段名，消息不处理也不重投: shardId={}, stage={}", message.shardId(), message.stage());
            return;
        }
        TenantContext.runAs(TenantPrincipal.system(message.tenantId()), () -> doRun(message, stage));
    }

    private void doRun(StageMessage message, Stage stage) {
        long tenantId = message.tenantId();
        long shardId = message.shardId();
        if (shardMapper.claim(tenantId, shardId, stage.name()) == 0) {
            log.info("分片未领取，判定为重复投递或已被推进: shardId={}, stage={}, traceId={}",
                    shardId, stage, message.traceId());
            return;
        }

        ApplicationPpidPO shard = shardMapper.selectById(shardId);
        ApplicationPO application = shard == null ? null : applicationMapper.selectById(shard.getApplicationId());
        if (shard == null || application == null) {
            log.error("分片或申请单已不可用，本阶段放弃: shardId={}, stage={}, tenantId={}", shardId, stage, tenantId);
            return;
        }
        applicationMapper.markRunning(tenantId, application.getId());

        boolean acquired = false;
        try {
            // 早到与闸门两条退路都在占额度之前：没干活就不该占着租户的苹果额度。
            // 代价是这一跳已经领走的状态必须先落回 PENDING（hold 里带 CAS），否则分片会卡在 PROCESSING。
            LocalDateTime due = shard.getNextExecuteTime();
            if (due != null && due.isAfter(LocalDateTime.now().plusSeconds(EARLY_TOLERANCE_SECONDS))) {
                log.info("分片早到，按原计划重排: shardId={}, stage={}, due={}", shardId, stage, due);
                hold(message, stage, nullToZero(shard.getRetryCount()), due, "早于计划执行时间");
                return;
            }
            if (!gate.tryAcquire(tenantId)) {
                int permits = configService.intValue(tenantId, TenantConfigKey.PIPELINE_CONCURRENT_PER_TENANT);
                log.info("租户并发额度已满，稍后再试: tenantId={}, shardId={}, stage={}, permits={}",
                        tenantId, shardId, stage, permits);
                hold(message, stage, nullToZero(shard.getRetryCount()),
                        LocalDateTime.now().plusNanos(properties.getGateRetryMillis() * 1_000_000L),
                        "租户并发额度已满");
                return;
            }
            acquired = true;

            StageContext context = new StageContext(tenantId, stage, application, shard, message.traceId());
            apply(dispatch(context), context, message);
        } catch (Exception e) {
            // 走到这里说明跃迁没做成：分片停在 PENDING 由兜底扫描补发，停在 PROCESSING 由超时复位捞回
            log.error("阶段处理出现未预期错误，状态留给兜底扫描: shardId={}, stage={}, traceId={}",
                    shardId, stage, message.traceId(), e);
        } finally {
            if (acquired) {
                gate.release(tenantId);
            }
        }
    }

    /**
     * 调处理器并把异常翻译成结论。异常语义见 {@link StageHandler}：业务异常是永久失败，
     * IO/运行时异常按退避重试，重试耗尽同样落 DEAD。
     */
    private StageOutcome dispatch(StageContext context) {
        StageHandler handler = handlers.get(context.stage());
        if (handler == null) {
            return StageOutcome.dead(context.stage(), "阶段没有处理器: " + context.stage());
        }
        try {
            return handler.handle(context);
        } catch (BizException e) {
            log.warn("阶段业务性失败，不再重试: shardId={}, stage={}, code={}, message={}",
                    context.shardId(), context.stage(), e.getErrorCode(), e.getMessage());
            return StageOutcome.dead(context.stage(), summarize(e.getMessage()));
        } catch (Exception e) {
            int retryCount = nullToZero(context.shard().getRetryCount());
            int maxRetry = configService.intValue(context.tenantId(), TenantConfigKey.PIPELINE_MAX_RETRY);
            String reason = summarize(e.getClass().getSimpleName() + ": " + e.getMessage());
            if (retryCount + 1 >= maxRetry) {
                log.error("阶段重试耗尽: shardId={}, stage={}, retryCount={}, maxRetry={}",
                        context.shardId(), context.stage(), retryCount, maxRetry, e);
                return StageOutcome.dead(context.stage(), reason);
            }
            LocalDateTime backoff = LocalDateTime.now().plusNanos(backoffNanos(retryCount));
            log.warn("阶段失败，安排退避重试: shardId={}, stage={}, nextRetry={}, at={}, cause={}",
                    context.shardId(), context.stage(), retryCount + 1, backoff, reason, e);
            return StageOutcome.retryAt(context.stage(), backoff, reason);
        }
    }

    private void apply(StageOutcome outcome, StageContext context, StageMessage message) {
        long tenantId = context.tenantId();
        long shardId = context.shardId();
        long applicationId = context.application().getId();
        Stage stage = context.stage();
        switch (outcome.kind()) {
            case ADVANCE -> {
                Stage next = stage.next();
                int rows = shardMapper.advance(tenantId, shardId, stage.name(),
                        next == null ? null : next.name(),
                        next == null ? StageStatus.SUCCESS.name() : StageStatus.PENDING.name(),
                        outcome.requestUuid(), LocalDateTime.now());
                if (rows == 0) {
                    log.warn("阶段跃迁被抢先，本跳不投递: shardId={}, stage={}", shardId, stage);
                    return;
                }
                writeStageLog(context, next);
                if (next == null) {
                    log.info("分片全阶段完成: shardId={}, applicationNo={}, status={}", shardId,
                            context.application().getApplicationNo(), aggregator.aggregate(tenantId, applicationId));
                } else {
                    producer.publishStage(new StageMessage(tenantId, applicationId, shardId,
                            next.name(), message.traceId()));
                }
            }
            case WAIT -> hold(message, stage, nullToZero(context.shard().getRetryCount()),
                    outcome.executeAt(), outcome.reason());
            case RETRY -> {
                int retryCount = nullToZero(context.shard().getRetryCount()) + 1;
                hold(message, stage, retryCount, outcome.executeAt(), outcome.reason());
                writeRetryLog(context, retryCount, outcome.reason());
            }
            case DEAD -> {
                if (shardMapper.markDead(tenantId, shardId, stage.name(),
                        summarize(outcome.reason())) == 0) {
                    log.warn("分片已不在 PROCESSING，跳过终态失败写入: shardId={}, stage={}", shardId, stage);
                    return;
                }
                writeDeadLog(context, outcome.reason());
                producer.publishDead(message, outcome.reason());
                log.warn("分片进入终态失败: shardId={}, stage={}, applicationId={}, reason={}",
                        shardId, stage, applicationId, outcome.reason());
                aggregator.aggregate(tenantId, applicationId);
            }
        }
    }

    /**
     * 排到未来时间再继续：先写库再延迟投递，顺序不能反。
     *
     * <p>{@code retryCount} 原样传回是有意为之：等待不是失败，苹果生成文件等了三个小时不应该消耗重试次数，
     * 否则一次大文件申请会在几个小时内被自己的等待耗进死信队列。
     *
     * <p>实际等待是"向上取整到最近的延迟档位"，所以比 {@code executeAt} 晚到一档是常态：闸门重排配置成
     * 30 秒，最小档位 1 分钟时它等 1 分钟。宁可晚到也不能没有队列可投——晚一分钟只是多等，
     * 而精确到毫秒的等待需要额外的档位队列与一次滚动升级。
     */
    private void hold(StageMessage message, Stage stage, int retryCount, LocalDateTime executeAt, String reason) {
        LocalDateTime due = executeAt == null ? LocalDateTime.now() : executeAt;
        if (shardMapper.schedule(message.tenantId(), message.shardId(), stage.name(), retryCount,
                due, summarize(reason)) == 0) {
            log.warn("分片重排被抢先: shardId={}, stage={}", message.shardId(), stage);
            return;
        }
        long delayMillis = Math.max(0L, Duration.between(LocalDateTime.now(), due).toMillis());
        producer.publishDelayed(new StageMessage(message.tenantId(), message.applicationId(),
                message.shardId(), stage.name(), message.traceId()), delayMillis);
    }

    /**
     * 退避时长直接取档位序列：第 N 次重试落在第 N 个档位上，超出档位数量用最长档位。
     * 退避值与队列档位对齐，才不会写出"退避 2 分钟但只有 1 分钟和 5 分钟档位"这种取整歧义。
     */
    private long backoffNanos(int retryCount) {
        List<Long> tiers = new ArrayList<>(properties.getDelayTiers());
        if (tiers.isEmpty()) {
            return properties.getGateRetryMillis() * 1_000_000L;
        }
        tiers.sort(Long::compareTo);
        int index = Math.min(Math.max(retryCount, 0), tiers.size() - 1);
        return tiers.get(index) * 1_000_000L;
    }

    /** 阶段留痕一律记成系统动作（operator 为空），与人工操作在时间线上天然区分开 */
    private void writeStageLog(StageContext context, Stage next) {
        auditTrail.append(AuditFact.system(context.tenantId(), context.application().getId(), context.shardId(),
                OperationAction.STAGE_ADVANCE, context.shard().getAllocateCount(),
                next == null ? "阶段 " + context.stage() + " 完成，分片结束"
                        : "阶段 " + context.stage() + " 完成，进入 " + next));
    }

    private void writeRetryLog(StageContext context, int retryCount, String reason) {
        auditTrail.append(AuditFact.system(context.tenantId(), context.application().getId(), context.shardId(),
                OperationAction.RETRY, retryCount,
                "阶段 " + context.stage() + " 第 " + retryCount + " 次重试：" + summarize(reason)));
    }

    private void writeDeadLog(StageContext context, String reason) {
        auditTrail.append(AuditFact.system(context.tenantId(), context.application().getId(), context.shardId(),
                OperationAction.RETRY, nullToZero(context.shard().getRetryCount()),
                "阶段 " + context.stage() + " 终态失败：" + summarize(reason)));
    }

    private static int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    /** 摘要要短：这一列会进 t_operation_log.detail 与 last_error，两处都是 VARCHAR 而且都要被人读 */
    private static String summarize(String text) {
        if (text == null || text.isBlank()) {
            return "未提供原因";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 240 ? flat : flat.substring(0, 240);
    }

}
