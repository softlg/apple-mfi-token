package com.galaplat.mfi.pipeline.handler;

import com.galaplat.mfi.common.enums.Stage;
import com.galaplat.mfi.common.exception.BizException;
import com.galaplat.mfi.dao.entity.ApplicationPO;
import com.galaplat.mfi.dao.entity.ApplicationPpidPO;
import com.galaplat.mfi.dao.mapper.TokenMapper;
import com.galaplat.mfi.pipeline.StageContext;
import com.galaplat.mfi.pipeline.StageOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * FINISH 阶段的收口校验：到这一步必须"有货且都注册过"，否则分片不能记成功。
 * 到货数量少于分配量是苹果侧的真实情况，只留差异不判失败。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FinishStageHandlerTest {

    private static final long TENANT_ID = 7L;
    private static final long APPLICATION_ID = 555L;
    private static final long SHARD_ID = 901L;

    @Mock
    private TokenMapper tokenMapper;

    private FinishStageHandler handler;
    private ApplicationPpidPO shard;

    @BeforeEach
    void setUp() {
        handler = new FinishStageHandler(tokenMapper);
        shard = new ApplicationPpidPO();
        shard.setId(SHARD_ID);
        shard.setTenantId(TENANT_ID);
        shard.setApplicationId(APPLICATION_ID);
        shard.setPpid("C00EXAMPLEPPID");
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

    private StageContext context() {
        ApplicationPO application = new ApplicationPO();
        application.setId(APPLICATION_ID);
        application.setApplicationNo("A261010-ABCDEFGH");
        return new StageContext(TENANT_ID, Stage.FINISH, application, shard, "A261010-ABCDEFGH");
    }

}
