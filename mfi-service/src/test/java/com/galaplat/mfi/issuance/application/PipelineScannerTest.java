package com.galaplat.mfi.issuance.application;

import com.galaplat.mfi.issuance.domain.Stage;
import com.galaplat.mfi.issuance.config.PipelineProperties;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPpidPO;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPpidMapper;
import com.galaplat.mfi.issuance.infrastructure.mq.ShardMessageProducer;
import com.galaplat.mfi.issuance.infrastructure.mq.StageMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 兜底扫描。"先写库再投递、投递失败不回滚"与"提交后投递失败只记日志"两个取舍都靠这里才成立，
 * 所以要看住的是：宽限窗口真的减掉了、卡死分片先复位再由下一跳捞回、以及扫描自己绝不能带崩调度线程。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PipelineScannerTest {

    @Mock
    private ApplicationPpidMapper shardMapper;
    @Mock
    private ShardMessageProducer producer;

    private PipelineProperties properties;
    private PipelineScanner scanner;

    @BeforeEach
    void setUp() {
        properties = new PipelineProperties();
        properties.setScanGraceSeconds(90);
        properties.setScanBatchSize(200);
        properties.setProcessingTimeoutMinutes(30);
        scanner = new PipelineScanner(shardMapper, producer, properties);
    }

    @Test
    void dueShardsAreRepublishedWithTheirCurrentStage() {
        when(shardMapper.resetStaleProcessing(any(), anyInt())).thenReturn(0);
        when(shardMapper.selectDueForRecovery(any(), anyInt())).thenReturn(List.of(
                shard(901L, 7L, 555L, Stage.APPLY), shard(902L, 8L, 556L, Stage.FILE_CONTENT)));

        scanner.scan();

        ArgumentCaptor<StageMessage> sent = ArgumentCaptor.forClass(StageMessage.class);
        verify(producer, times(2)).publishStage(sent.capture());
        // 补发按行上的 tenant_id 与 stage，扫描不猜也不改状态
        assertEquals(8L, sent.getAllValues().get(1).tenantId());
        assertEquals(Stage.FILE_CONTENT.name(), sent.getAllValues().get(1).stage());
        assertEquals(PipelineScanner.TRACE_PREFIX, sent.getAllValues().get(1).traceId());
    }

    @Test
    void graceWindowIsSubtractedFromTheDeadline() {
        ArgumentCaptor<LocalDateTime> deadline = ArgumentCaptor.forClass(LocalDateTime.class);

        scanner.scan();

        verify(shardMapper).selectDueForRecovery(deadline.capture(), eq(200));
        LocalDateTime lower = LocalDateTime.now().minusSeconds(95);
        LocalDateTime upper = LocalDateTime.now().minusSeconds(85);
        assertTrue(!deadline.getValue().isBefore(lower) && !deadline.getValue().isAfter(upper),
                "宽限窗口没生效就会被正在飞的正常消息抢行: " + deadline.getValue());
    }

    @Test
    void staleProcessingIsResetBeforeTheNextSweepPicksItUp() {
        when(shardMapper.resetStaleProcessing(any(), anyInt())).thenReturn(3);
        when(shardMapper.selectDueForRecovery(any(), anyInt())).thenReturn(List.of());

        scanner.scan();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(shardMapper).resetStaleProcessing(cutoff.capture(), eq(200));
        assertTrue(cutoff.getValue().isBefore(LocalDateTime.now().minusMinutes(29)),
                "超时窗口必须按 processingTimeoutMinutes 往前推: " + cutoff.getValue());
        verify(producer, never()).publishStage(any());
    }

    @Test
    void scanFailureDoesNotKillTheScheduler() {
        when(shardMapper.resetStaleProcessing(any(), anyInt()))
                .thenThrow(new IllegalStateException("数据库不可用"));

        // 一次异常把调度线程带崩，等于此后所有丢消息都要人工补
        assertDoesNotThrow(() -> scanner.scan());
        verifyNoInteractions(producer);
    }

    @Test
    void brokerFailureLeavesTheRestForTheNextSweep() {
        when(shardMapper.selectDueForRecovery(any(), anyInt())).thenReturn(List.of(
                shard(901L, 7L, 555L, Stage.APPLY), shard(902L, 7L, 555L, Stage.APPLY)));
        doThrow(new IllegalStateException("broker 不可达")).when(producer).publishStage(any());

        // 补不完就留给下一轮：分片仍是 PENDING，扫描每 30 秒还会再来一次
        assertDoesNotThrow(() -> scanner.scan());
        verify(producer, times(1)).publishStage(any());
    }

    private static ApplicationPpidPO shard(long id, long tenantId, long applicationId, Stage stage) {
        ApplicationPpidPO row = new ApplicationPpidPO();
        row.setId(id);
        row.setTenantId(tenantId);
        row.setApplicationId(applicationId);
        row.setStage(stage.name());
        row.setStageStatus("PENDING");
        return row;
    }

}
