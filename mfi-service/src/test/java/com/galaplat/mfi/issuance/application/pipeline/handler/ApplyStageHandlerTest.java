package com.galaplat.mfi.issuance.application.pipeline.handler;

import com.galaplat.mfi.apple.MfiTokenClient;
import com.galaplat.mfi.apple.model.MfiResponse;
import com.galaplat.mfi.credential.domain.AppleClientProvider;
import com.galaplat.mfi.issuance.domain.Stage;
import com.galaplat.mfi.shared.api.BizException;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPO;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPpidPO;
import com.galaplat.mfi.issuance.domain.StageContext;
import com.galaplat.mfi.issuance.domain.StageOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * APPLY 阶段的幂等与错误分类。这一跳直接扣苹果的额度，重复提交与"把永久错误当可重试"都是花钱的错。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApplyStageHandlerTest {

    private static final long TENANT_ID = 7L;
    private static final long APPLICATION_ID = 555L;
    private static final long SHARD_ID = 901L;

    @Mock
    private AppleClientProvider clientFactory;
    @Mock
    private MfiTokenClient client;

    private ApplyStageHandler handler;
    private ApplicationPpidPO shard;

    @BeforeEach
    void setUp() throws IOException {
        handler = new ApplyStageHandler(clientFactory);
        shard = new ApplicationPpidPO();
        shard.setId(SHARD_ID);
        shard.setTenantId(TENANT_ID);
        shard.setApplicationId(APPLICATION_ID);
        shard.setPpid("C00EXAMPLEPPID");
        shard.setAllocateCount(20);
        shard.setStage(Stage.APPLY.name());
        when(clientFactory.forTenant(TENANT_ID)).thenReturn(client);
    }

    @Test
    void alreadyAppliedShardAdvancesWithoutCallingApple() throws IOException {
        shard.setAppleRequestUuid("req-existing");

        StageOutcome outcome = handler.handle(context());

        assertEquals(StageOutcome.Kind.ADVANCE, outcome.kind());
        assertEquals("req-existing", outcome.requestUuid());
        verifyNoInteractions(client);
    }

    @Test
    void zeroQuantityIsPermanentFailure() {
        shard.setAllocateCount(0);

        // 脏数据重试多少次都一样，直接判死免得占着闸门
        assertThrows(BizException.class, () -> handler.handle(context()));
        verifyNoInteractions(client);
    }

    @Test
    void acceptedRequestWithoutIdIsRetried() throws IOException {
        when(client.requestAuthEntities("C00EXAMPLEPPID", 20)).thenReturn(new MfiResponse());

        // 苹果说成功了却没给 request id，后面三跳都无从进行；这属于响应异常，交给退避重试
        assertThrows(IOException.class, () -> handler.handle(context()));
    }

    @Test
    void clientErrorIsPermanentFailure() throws IOException {
        when(client.requestAuthEntities("C00EXAMPLEPPID", 20))
                .thenReturn(error(403, "auth_entity_invalid"));

        StageOutcome outcome = handler.handle(context());

        assertEquals(StageOutcome.Kind.DEAD, outcome.kind());
        assertEquals(Stage.APPLY, outcome.stage());
    }

    @Test
    void serverErrorIsRetryable() throws IOException {
        when(client.requestAuthEntities("C00EXAMPLEPPID", 20))
                .thenReturn(error(503, "service_unavailable"));

        assertThrows(IOException.class, () -> handler.handle(context()));
    }

    @Test
    void rateLimitedIsBackedOffNotKilled() throws IOException {
        when(client.requestAuthEntities("C00EXAMPLEPPID", 20)).thenReturn(error(429, "too_many_requests"));

        // 429 与 408 是"稍后再来"，不是配置错误：判成 DEAD 会让一次限流打死整片已扣额度的 token，
        // 只能人工重放。交给上层退避重试。
        assertThrows(IOException.class, () -> handler.handle(context()));
    }

    @Test
    void successAdvancesWithRequestId() throws IOException {
        MfiResponse response = new MfiResponse();
        response.setCode(200);
        response.setRequestId("req-20261010");
        when(client.requestAuthEntities("C00EXAMPLEPPID", 20)).thenReturn(response);

        StageOutcome outcome = handler.handle(context());

        assertEquals(StageOutcome.Kind.ADVANCE, outcome.kind());
        assertEquals("req-20261010", outcome.requestUuid());
    }

    private static MfiResponse error(int code, String errorCode) {
        MfiResponse response = new MfiResponse();
        response.setCode(code);
        response.setErrorCode(errorCode);
        response.setErrorMessage("苹果给的文案");
        return response;
    }

    private StageContext context() {
        ApplicationPO application = new ApplicationPO();
        application.setId(APPLICATION_ID);
        application.setApplicationNo("A261010-ABCDEFGH");
        return new StageContext(TENANT_ID, Stage.APPLY, application, shard, "A261010-ABCDEFGH");
    }

}
