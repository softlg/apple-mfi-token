package com.galaplat.mfi.pipeline.handler;

import com.galaplat.mfi.common.enums.Stage;
import com.galaplat.mfi.common.exception.BizException;
import com.galaplat.mfi.dao.entity.ApplicationPO;
import com.galaplat.mfi.dao.entity.ApplicationPpidPO;
import com.galaplat.mfi.dao.entity.TokenPO;
import com.galaplat.mfi.dao.mapper.TokenMapper;
import com.galaplat.mfi.pipeline.StageContext;
import com.galaplat.mfi.pipeline.StageOutcome;
import com.galaplat.mfi.sn.SnAllocator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * FINISH 阶段的收口校验：到这一步必须"有货且都注册过"，否则分片不能记成功。
 * 到货数量少于分配量是苹果侧的真实情况，只留差异不判失败。SN 的幂等取舍也在这里验。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FinishStageHandlerTest {

    private static final long TENANT_ID = 7L;
    private static final long APPLICATION_ID = 555L;
    private static final long SHARD_ID = 901L;
    private static final String PPID = "C00EXAMPLEPPID";

    @Mock
    private TokenMapper tokenMapper;

    @Mock
    private SnAllocator snAllocator;

    private FinishStageHandler handler;
    private ApplicationPpidPO shard;

    @BeforeEach
    void setUp() {
        handler = new FinishStageHandler(tokenMapper, snAllocator);
        shard = new ApplicationPpidPO();
        shard.setId(SHARD_ID);
        shard.setTenantId(TENANT_ID);
        shard.setApplicationId(APPLICATION_ID);
        shard.setPpid(PPID);
        shard.setAllocateCount(20);
        shard.setStage(Stage.FINISH.name());
    }

    @Test
    void emptyShardCannotFinish() {
        when(tokenMapper.countByShard(TENANT_ID, SHARD_ID)).thenReturn(0);

        // 走到 FINISH 却一个 token 都没有，说明前面的阶段被绕过或数据被改坏
        assertThrows(BizException.class, () -> handler.handle(context()));
    }

    @Test
    void unregisteredTokensBlockFinish() {
        when(tokenMapper.countByShard(TENANT_ID, SHARD_ID)).thenReturn(20);
        when(tokenMapper.countUnregistered(TENANT_ID, SHARD_ID)).thenReturn(3);

        assertThrows(BizException.class, () -> handler.handle(context()));
    }

    @Test
    void shortDeliveryStillFinishes() {
        when(tokenMapper.countByShard(TENANT_ID, SHARD_ID)).thenReturn(18);
        when(tokenMapper.countUnregistered(TENANT_ID, SHARD_ID)).thenReturn(0);

        // 苹果给少了不足以判失败，对账与导出会以这一行为准
        assertEquals(StageOutcome.Kind.ADVANCE, handler.handle(context()).kind());
    }

    @Test
    void fullDeliveryFinishes() {
        when(tokenMapper.countByShard(TENANT_ID, SHARD_ID)).thenReturn(20);
        when(tokenMapper.countUnregistered(TENANT_ID, SHARD_ID)).thenReturn(0);

        StageOutcome outcome = handler.handle(context());

        assertEquals(StageOutcome.Kind.ADVANCE, outcome.kind());
        assertEquals(Stage.FINISH, outcome.stage());
    }

    @Test
    void missingSerialNumbersAreFilledInIdOrder() {
        givenDelivered(20);
        when(tokenMapper.selectSnMissing(TENANT_ID, SHARD_ID)).thenReturn(List.of(41L, 42L));
        when(snAllocator.allocate(TENANT_ID, PPID, 2)).thenReturn(List.of("SN-A", "SN-B"));
        List<List<TokenPO>> batches = captureAssignments();

        assertEquals(StageOutcome.Kind.ADVANCE, handler.handle(context()).kind());

        assertEquals(1, batches.size());
        List<TokenPO> rows = batches.get(0);
        assertEquals(2, rows.size());
        assertEquals(41L, rows.get(0).getId().longValue());
        assertEquals("SN-A", rows.get(0).getManufactureSn());
        assertEquals(42L, rows.get(1).getId().longValue());
        assertEquals("SN-B", rows.get(1).getManufactureSn());
    }

    @Test
    void fullyNumberedShardAsksForNoSerials() {
        givenDelivered(20);
        when(tokenMapper.selectSnMissing(TENANT_ID, SHARD_ID)).thenReturn(List.of());

        assertEquals(StageOutcome.Kind.ADVANCE, handler.handle(context()).kind());

        // FINISH 重放时不重新取号：号一旦交给厂商就不能再换，一次 INCRBY 都不该发生
        verifyNoInteractions(snAllocator);
        verify(tokenMapper, never()).assignSn(anyLong(), anyList());
    }

    @Test
    void serialsAreTakenOnlyAfterBothChecksPass() {
        when(tokenMapper.countByShard(TENANT_ID, SHARD_ID)).thenReturn(20);
        when(tokenMapper.countUnregistered(TENANT_ID, SHARD_ID)).thenReturn(3);

        assertThrows(BizException.class, () -> handler.handle(context()));

        // 还没注册完就取号，等于把一段号烧在一次不会成功的执行上
        verifyNoInteractions(snAllocator);
    }

    @Test
    void partialAssignmentIsLoggedNotFailed() {
        givenDelivered(20);
        when(tokenMapper.selectSnMissing(TENANT_ID, SHARD_ID)).thenReturn(List.of(41L, 42L, 43L));
        when(snAllocator.allocate(TENANT_ID, PPID, 3)).thenReturn(List.of("SN-A", "SN-B", "SN-C"));
        // 并发下有行刚被别的执行落过号，条件更新只写进 2 行
        when(tokenMapper.assignSn(eq(TENANT_ID), anyList())).thenReturn(2);

        assertEquals(StageOutcome.Kind.ADVANCE, handler.handle(context()).kind());
    }

    @Test
    void assignmentIsChunkedToBoundStatementSize() {
        givenDelivered(1200);
        List<Long> ids = new ArrayList<>(1200);
        List<String> serials = new ArrayList<>(1200);
        for (int i = 0; i < 1200; i++) {
            ids.add(1000L + i);
            serials.add("SN-" + i);
        }
        when(tokenMapper.selectSnMissing(TENANT_ID, SHARD_ID)).thenReturn(ids);
        when(snAllocator.allocate(TENANT_ID, PPID, 1200)).thenReturn(serials);
        List<List<TokenPO>> batches = captureAssignments();

        assertEquals(StageOutcome.Kind.ADVANCE, handler.handle(context()).kind());

        // 分段只是为了限制一条 case-when 的体积；取号仍是一次，号段保持连续
        verify(snAllocator, times(1)).allocate(eq(TENANT_ID), eq(PPID), anyInt());
        assertEquals(3, batches.size());
        assertEquals(500, batches.get(0).size());
        assertEquals(500, batches.get(1).size());
        assertEquals(200, batches.get(2).size());
        assertEquals("SN-1199", batches.get(2).get(199).getManufactureSn());
    }

    /**
     * 让 mock 把每次 assignSn 收到的行抄一份出来。
     *
     * <p>用 answer 而不是 ArgumentCaptor：处理器传的是 {@code subList} 视图，捕获到的引用会在后续分段
     * 时跟着底层列表变化，断言拿到的就不是那一段的内容了。
     */
    private List<List<TokenPO>> captureAssignments() {
        List<List<TokenPO>> batches = new ArrayList<>();
        when(tokenMapper.assignSn(anyLong(), anyList())).thenAnswer(invocation -> {
            List<TokenPO> batch = invocation.getArgument(1);
            batches.add(new ArrayList<>(batch));
            return batch.size();
        });
        return batches;
    }

    private void givenDelivered(int tokens) {
        when(tokenMapper.countByShard(TENANT_ID, SHARD_ID)).thenReturn(tokens);
        when(tokenMapper.countUnregistered(TENANT_ID, SHARD_ID)).thenReturn(0);
        shard.setAllocateCount(tokens);
    }

    private StageContext context() {
        ApplicationPO application = new ApplicationPO();
        application.setId(APPLICATION_ID);
        application.setApplicationNo("A261010-ABCDEFGH");
        return new StageContext(TENANT_ID, Stage.FINISH, application, shard, "A261010-ABCDEFGH");
    }

}
