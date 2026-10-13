package com.galaplat.mfi.issuance.application;

import com.galaplat.mfi.identity.domain.OperationAction;
import com.galaplat.mfi.issuance.domain.Stage;
import com.galaplat.mfi.identity.domain.TenantConfigKey;
import com.galaplat.mfi.shared.api.BizException;
import com.galaplat.mfi.issuance.config.PipelineProperties;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPO;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPpidPO;
import com.galaplat.mfi.identity.infrastructure.persistence.OperationLogPO;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationMapper;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPpidMapper;
import com.galaplat.mfi.identity.infrastructure.persistence.OperationLogMapper;
import com.galaplat.mfi.issuance.infrastructure.mq.ShardMessageProducer;
import com.galaplat.mfi.issuance.infrastructure.mq.StageMessage;
import com.galaplat.mfi.identity.application.TenantConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.galaplat.mfi.issuance.domain.StageContext;
import com.galaplat.mfi.issuance.domain.StageHandler;
import com.galaplat.mfi.issuance.domain.StageOutcome;

/**
 * 流水线的状态机。盯的是三条会直接造成重复调苹果、丢进度或把分片耗死的规则：
 * 领取失败必须在调苹果之前停下、跃迁必须落库成功才投递下一跳、等待不能算成失败重试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StageRunnerTest {

    private static final long TENANT_ID = 7L;
    private static final long APPLICATION_ID = 555L;
    private static final long SHARD_ID = 901L;

    @Mock
    private ApplicationPpidMapper shardMapper;
    @Mock
    private ApplicationMapper applicationMapper;
    @Mock
    private OperationLogMapper operationLogMapper;
    @Mock
    private TenantConfigService configService;
    @Mock
    private TenantConcurrencyGate gate;
    @Mock
    private ShardMessageProducer producer;
    @Mock
    private ApplicationAggregator aggregator;

    private PipelineProperties properties;
    private StubHandler handler;
    private StageRunner runner;
    private ApplicationPpidPO shard;

    @BeforeEach
    void setUp() {
        properties = new PipelineProperties();
        properties.setDelayTiers(List.of(60_000L, 300_000L, 1_800_000L, 3_600_000L));
        properties.setGateRetryMillis(30_000L);

        handler = new StubHandler(Stage.APPLY);
        runner = new StageRunner(List.of(handler), shardMapper, applicationMapper, operationLogMapper,
                configService, gate, producer, aggregator, properties);

        shard = newShard();
        when(shardMapper.claim(anyLong(), anyLong(), anyString())).thenReturn(1);
        when(shardMapper.selectById(SHARD_ID)).thenReturn(shard);
        when(applicationMapper.selectById(APPLICATION_ID)).thenReturn(application());
        when(gate.tryAcquire(TENANT_ID)).thenReturn(true);
        when(configService.intValue(TENANT_ID, TenantConfigKey.PIPELINE_MAX_RETRY)).thenReturn(5);
        when(shardMapper.advance(anyLong(), anyLong(), anyString(), any(), anyString(), any(), any()))
                .thenReturn(1);
        when(shardMapper.schedule(anyLong(), anyLong(), anyString(), anyInt(), any(), any())).thenReturn(1);
        when(shardMapper.markDead(anyLong(), anyLong(), anyString(), any())).thenReturn(1);
    }

    @Test
    void duplicateDeliveryStopsBeforeApple() {
        when(shardMapper.claim(TENANT_ID, SHARD_ID, Stage.APPLY.name())).thenReturn(0);

        runner.run(message(Stage.APPLY));

        // 领取返回 0 就是唯一的去重点：后面的回库读、闸门、处理器一条都不许走到
        verify(shardMapper, never()).selectById(anyLong());
        verify(applicationMapper, never()).markRunning(anyLong(), anyLong());
        assertEquals(0, handler.calls.size());
        verify(producer, never()).publishStage(any());
        verify(producer, never()).publishDelayed(any(), anyLong());
    }

    @Test
    void claimedDeliveryPromotesApplicationToRunning() {
        runner.run(message(Stage.APPLY));

        // 读侧不能整条流水线跑完之前一直显示 PENDING；领取成功是"有人开始消费"的唯一凭据
        verify(applicationMapper).markRunning(TENANT_ID, APPLICATION_ID);
    }

    @Test
    void unknownStageIsNeitherProcessedNorRepublished() {
        runner.run(new StageMessage(TENANT_ID, APPLICATION_ID, SHARD_ID, "NOT_A_STAGE", "A261010-ABCDEFGH"));

        verify(shardMapper, never()).claim(anyLong(), anyLong(), anyString());
        verify(producer, never()).publishStage(any());
    }

    @Test
    void advanceIsPersistedBeforeTheNextStageMessage() {
        handler.outcome = StageOutcome.advance(Stage.APPLY, "req-1");

        runner.run(message(Stage.APPLY));

        InOrder order = inOrder(shardMapper, producer);
        order.verify(shardMapper).advance(eq(TENANT_ID), eq(SHARD_ID), eq(Stage.APPLY.name()),
                eq(Stage.FILE_NAME.name()), eq("PENDING"), eq("req-1"), any(LocalDateTime.class));
        ArgumentCaptor<StageMessage> sent = ArgumentCaptor.forClass(StageMessage.class);
        order.verify(producer).publishStage(sent.capture());
        assertEquals(Stage.FILE_NAME.name(), sent.getValue().stage());
        assertEquals(SHARD_ID, sent.getValue().shardId());
        assertEquals(TENANT_ID, sent.getValue().tenantId());
    }

    @Test
    void lostAdvanceRaceDoesNotPublish() {
        handler.outcome = StageOutcome.advance(Stage.APPLY, "req-1");
        when(shardMapper.advance(anyLong(), anyLong(), anyString(), any(), anyString(), any(), any()))
                .thenReturn(0);

        runner.run(message(Stage.APPLY));

        verify(producer, never()).publishStage(any());
        verify(operationLogMapper, never()).insert(any(OperationLogPO.class));
    }

    @Test
    void finishAdvanceIsTerminalAndAggregates() {
        // 处理器在构造时按 stage 登记，换阶段就得换一台 runner
        StubHandler finishHandler = new StubHandler(Stage.FINISH);
        finishHandler.outcome = StageOutcome.advance(Stage.FINISH);
        shard.setStage(Stage.FINISH.name());
        runner = runnerWith(finishHandler);

        runner.run(message(Stage.FINISH));

        verify(shardMapper).advance(eq(TENANT_ID), eq(SHARD_ID), eq(Stage.FINISH.name()), isNull(),
                eq("SUCCESS"), isNull(), any(LocalDateTime.class));
        verify(aggregator).aggregate(TENANT_ID, APPLICATION_ID);
        verify(producer, never()).publishStage(any());
        assertEquals(OperationAction.STAGE_ADVANCE.name(), firstLog().getAction());
    }

    @Test
    void businessFailureGoesStraightToDeadLetter() {
        handler.failure = new BizException("PPID 无效，苹果拒绝受理");

        runner.run(message(Stage.APPLY));

        verify(shardMapper).markDead(eq(TENANT_ID), eq(SHARD_ID), eq(Stage.APPLY.name()),
                eq("PPID 无效，苹果拒绝受理"));
        verify(producer).publishDead(any(StageMessage.class), eq("PPID 无效，苹果拒绝受理"));
        verify(aggregator).aggregate(TENANT_ID, APPLICATION_ID);
        // 永久失败不该再排重试
        verify(shardMapper, never()).schedule(anyLong(), anyLong(), anyString(), anyInt(), any(), any());
        assertEquals(OperationAction.RETRY.name(), firstLog().getAction());
    }

    @Test
    void transientFailureConsumesExactlyOneRetryAtTheFirstTier() {
        handler.failure = new IOException("苹果 503");

        runner.run(message(Stage.APPLY));

        ArgumentCaptor<Integer> retryCount = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<LocalDateTime> at = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(shardMapper).schedule(eq(TENANT_ID), eq(SHARD_ID), eq(Stage.APPLY.name()),
                retryCount.capture(), at.capture(), anyString());
        assertEquals(1, retryCount.getValue());
        assertTrue(!at.getValue().isBefore(LocalDateTime.now().plusSeconds(50))
                && !at.getValue().isAfter(LocalDateTime.now().plusSeconds(65)), "实际时刻=" + at.getValue());

        // 退避时长与档位对齐：第一次重试落在 1 分钟档
        ArgumentCaptor<Long> delay = ArgumentCaptor.forClass(Long.class);
        verify(producer).publishDelayed(any(StageMessage.class), delay.capture());
        assertTrue(delay.getValue() > 50_000L && delay.getValue() <= 60_000L, "实际延迟=" + delay.getValue());
        verify(producer, never()).publishDead(any(), any());
        assertEquals(OperationAction.RETRY.name(), firstLog().getAction());
        verify(shardMapper, never()).markDead(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void retryExhaustionBecomesDead() {
        shard.setRetryCount(4);
        handler.failure = new IOException("苹果 503");

        runner.run(message(Stage.APPLY));

        verify(shardMapper).markDead(eq(TENANT_ID), eq(SHARD_ID), eq(Stage.APPLY.name()), anyString());
        verify(shardMapper, never()).schedule(anyLong(), anyLong(), anyString(), anyInt(), any(), any());
    }

    @Test
    void waitingForAppleFilesKeepsTheRetryCount() {
        handler.outcome = StageOutcome.waitUntil(Stage.APPLY, LocalDateTime.now().plusHours(2), "苹果尚未生成文件");

        runner.run(message(Stage.APPLY));

        ArgumentCaptor<Integer> retryCount = ArgumentCaptor.forClass(Integer.class);
        verify(shardMapper).schedule(eq(TENANT_ID), eq(SHARD_ID), eq(Stage.APPLY.name()),
                retryCount.capture(), any(LocalDateTime.class), eq("苹果尚未生成文件"));
        // 等待不是失败：大文件等几个小时绝不该把分片耗进死信队列
        assertEquals(0, retryCount.getValue());
        verify(operationLogMapper, never()).insert(any(OperationLogPO.class));
    }

    @Test
    void longWaitRoundTripsThroughTheCoarsestTier() {
        handler.outcome = StageOutcome.waitUntil(Stage.APPLY, LocalDateTime.now().plusHours(2), "苹果尚未生成文件");

        runner.run(message(Stage.APPLY));

        ArgumentCaptor<Long> delay = ArgumentCaptor.forClass(Long.class);
        verify(producer).publishDelayed(any(StageMessage.class), delay.capture());
        // 超过最长档位也照样排得出：向上取整到 1 小时档，到货后消费者发现未到点再排一次
        assertTrue(delay.getValue() > 1_000_000L, "实际延迟=" + delay.getValue());
    }

    @Test
    void earlyArrivalIsHeldBackWithoutCallingApple() {
        LocalDateTime due = LocalDateTime.now().plusMinutes(10);
        shard.setNextExecuteTime(due);

        runner.run(message(Stage.APPLY));

        assertEquals(0, handler.calls.size());
        verify(shardMapper).schedule(eq(TENANT_ID), eq(SHARD_ID), eq(Stage.APPLY.name()), eq(0), eq(due),
                eq("早于计划执行时间"));
        verify(producer).publishDelayed(any(StageMessage.class), anyLong());
    }

    @Test
    void gateSaturationHoldsBackWithoutCallingApple() {
        when(gate.tryAcquire(TENANT_ID)).thenReturn(false);

        runner.run(message(Stage.APPLY));

        assertEquals(0, handler.calls.size());
        verify(shardMapper).schedule(eq(TENANT_ID), eq(SHARD_ID), eq(Stage.APPLY.name()), eq(0),
                any(LocalDateTime.class), eq("租户并发额度已满"));
        verify(gate, never()).release(anyLong());
    }

    @Test
    void mapperFailureNeverReachesTheAmqpContainer() {
        handler.outcome = StageOutcome.advance(Stage.APPLY, "req-1");
        when(shardMapper.advance(anyLong(), anyLong(), anyString(), any(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("数据库连接已断开"));

        // 抛给容器只会让消息在原队列无限重投，这里必须就地收敛
        assertDoesNotThrow(() -> runner.run(message(Stage.APPLY)));
        verify(producer, never()).publishStage(any());
    }

    @Test
    void missingShardAbandonsTheMessage() {
        when(shardMapper.selectById(SHARD_ID)).thenReturn(null);

        runner.run(message(Stage.APPLY));

        assertEquals(0, handler.calls.size());
        verify(shardMapper, never()).markDead(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void handlerWithoutRegistrationIsTerminalFailure() {
        runner = runnerWith();

        runner.run(message(Stage.APPLY));

        verify(shardMapper).markDead(eq(TENANT_ID), eq(SHARD_ID), eq(Stage.APPLY.name()),
                eq("阶段没有处理器: APPLY"));
    }

    private StageRunner runnerWith(StageHandler... stageHandlers) {
        return new StageRunner(List.of(stageHandlers), shardMapper, applicationMapper, operationLogMapper,
                configService, gate, producer, aggregator, properties);
    }

    private OperationLogPO firstLog() {
        ArgumentCaptor<OperationLogPO> captor = ArgumentCaptor.forClass(OperationLogPO.class);
        verify(operationLogMapper).insert(captor.capture());
        return captor.getValue();
    }

    private static StageMessage message(Stage stage) {
        return new StageMessage(TENANT_ID, APPLICATION_ID, SHARD_ID, stage.name(), "A261010-ABCDEFGH");
    }

    private static ApplicationPO application() {
        ApplicationPO application = new ApplicationPO();
        application.setId(APPLICATION_ID);
        application.setApplicationNo("A261010-ABCDEFGH");
        return application;
    }

    /** 分片行的默认值集中一处，避免每个用例重复十余个 setter */
    private static ApplicationPpidPO newShard() {
        ApplicationPpidPO row = new ApplicationPpidPO();
        row.setId(SHARD_ID);
        row.setTenantId(TENANT_ID);
        row.setApplicationId(APPLICATION_ID);
        row.setPpid("C00EXAMPLEPPID");
        row.setAllocateCount(20);
        row.setStage(Stage.APPLY.name());
        row.setStageStatus("PENDING");
        row.setRetryCount(0);
        return row;
    }

    /** 可编程的阶段处理器：只负责给出结论或抛出异常，状态机由被测的 StageRunner 决定 */
    private static final class StubHandler implements StageHandler {

        private final Stage stage;
        private StageOutcome outcome = StageOutcome.advance(Stage.APPLY);
        private Exception failure;
        private final List<StageContext> calls = new ArrayList<>();

        private StubHandler(Stage stage) {
            this.stage = stage;
        }

        @Override
        public Stage stage() {
            return stage;
        }

        @Override
        public StageOutcome handle(StageContext context) throws IOException {
            calls.add(context);
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (failure != null) {
                throw new IOException(failure);
            }
            return outcome;
        }
    }

}
