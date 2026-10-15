package com.galaplat.mfi.issuance.application.pipeline.handler;

import com.galaplat.mfi.apple.MfiTokenClient;
import com.galaplat.mfi.apple.model.FileNameListResponse;
import com.galaplat.mfi.credential.domain.AppleClientProvider;
import com.galaplat.mfi.issuance.domain.Stage;
import com.galaplat.mfi.shared.api.BizException;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPO;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPpidPO;
import com.galaplat.mfi.issuance.infrastructure.persistence.TokenFilePO;
import com.galaplat.mfi.issuance.infrastructure.persistence.TokenFileMapper;
import com.galaplat.mfi.issuance.domain.StageContext;
import com.galaplat.mfi.issuance.domain.StageOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FILE_NAME 阶段。这里唯一要守住的是"等"与"失败"的界限：苹果还在生成文件时按它给的时刻等待且不消耗
 * 重试次数，而缺 request id 这类脏数据必须立刻判死。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FileNameStageHandlerTest {

    private static final long TENANT_ID = 7L;
    private static final long APPLICATION_ID = 555L;
    private static final long SHARD_ID = 901L;
    private static final String REQUEST_ID = "req-20261010";

    @Mock
    private AppleClientProvider clientFactory;
    @Mock
    private MfiTokenClient client;
    @Mock
    private TokenFileMapper tokenFileMapper;

    private FileNameStageHandler handler;
    private ApplicationPpidPO shard;

    @BeforeEach
    void setUp() {
        handler = new FileNameStageHandler(clientFactory, tokenFileMapper);
        shard = new ApplicationPpidPO();
        shard.setId(SHARD_ID);
        shard.setTenantId(TENANT_ID);
        shard.setApplicationId(APPLICATION_ID);
        shard.setPpid("C00EXAMPLEPPID");
        shard.setAllocateCount(20);
        shard.setStage(Stage.FILE_NAME.name());
        shard.setAppleRequestUuid(REQUEST_ID);
        when(clientFactory.forTenant(TENANT_ID)).thenReturn(client);
    }

    @Test
    void missingRequestUuidIsPermanentFailure() {
        shard.setAppleRequestUuid(null);

        // APPLY 的成功标志就是这个 id，缺它说明数据被改坏，继续等只会空跑
        assertThrows(BizException.class, () -> handler.handle(context()));
    }

    @Test
    void filesNotReadyWaitsUntilApplesTimeWithoutTouchingTheTable() throws IOException {
        FileNameListResponse response = new FileNameListResponse();
        response.setRetryDownloadAfter("2099-01-02 03:04:05.0");
        when(client.getFileNames(REQUEST_ID)).thenReturn(response);

        StageOutcome outcome = handler.handle(context());

        assertEquals(StageOutcome.Kind.WAIT, outcome.kind());
        assertTrue(outcome.executeAt().isAfter(LocalDateTime.now().plusHours(1)),
                "苹果的 UTC 时刻必须换算成未来，否则会变成轮询风暴: " + outcome.executeAt());
        verify(tokenFileMapper, never()).insertBatchIgnore(anyList());
    }

    @Test
    void blankAvailabilityFallsBackToDefaultWait() throws IOException {
        when(client.getFileNames(REQUEST_ID)).thenReturn(new FileNameListResponse());

        StageOutcome outcome = handler.handle(context());

        assertEquals(StageOutcome.Kind.WAIT, outcome.kind());
        assertTrue(!outcome.executeAt().isBefore(LocalDateTime.now().plusMinutes(55))
                && !outcome.executeAt().isAfter(LocalDateTime.now().plusMinutes(65)),
                "缺字段按一小时等: " + outcome.executeAt());
    }

    @Test
    void rejectedFileListIsPermanentFailure() throws IOException {
        FileNameListResponse response = new FileNameListResponse();
        response.setCode(403);
        response.setErrorCode("request_not_found");
        when(client.getFileNames(REQUEST_ID)).thenReturn(response);

        StageOutcome outcome = handler.handle(context());

        assertEquals(StageOutcome.Kind.DEAD, outcome.kind());
    }

    @Test
    void rateLimitedFileListWaitsInsteadOfDying() throws IOException {
        FileNameListResponse response = new FileNameListResponse();
        response.setCode(429);
        response.setErrorCode("too_many_requests");
        when(client.getFileNames(REQUEST_ID)).thenReturn(response);

        // 429 是"请求太多，稍后再来"，判死会把已经花掉苹果额度的申请单永久作废
        StageOutcome outcome = handler.handle(context());

        assertEquals(StageOutcome.Kind.WAIT, outcome.kind());
    }

    @Test
    void serverErrorWithoutReadyTimeWaitsByDefault() throws IOException {
        FileNameListResponse response = new FileNameListResponse();
        response.setCode(503);
        response.setErrorMessage("service unavailable");
        when(client.getFileNames(REQUEST_ID)).thenReturn(response);

        // 5xx 也归入"再等一次"：等待不消耗重试次数，而绝不能当成文件清单为空直接前进
        StageOutcome outcome = handler.handle(context());
        assertEquals(StageOutcome.Kind.WAIT, outcome.kind());
    }

    @Test
    void fileNamesAreRegisteredWithShardIdentity() throws IOException {
        FileNameListResponse response = new FileNameListResponse();
        response.setFileNames(List.of("tokens-1.xlsx", " ", "tokens-2.xlsx"));
        when(client.getFileNames(REQUEST_ID)).thenReturn(response);

        StageOutcome outcome = handler.handle(context());

        assertEquals(StageOutcome.Kind.ADVANCE, outcome.kind());
        ArgumentCaptor<List<TokenFilePO>> rows = ArgumentCaptor.forClass(List.class);
        verify(tokenFileMapper).insertBatchIgnore(rows.capture());
        // 空文件名是苹果的占位，登记了只会在下载阶段撞出无意义的错误
        assertEquals(2, rows.getValue().size());
        TokenFilePO first = rows.getValue().get(0);
        assertEquals(TENANT_ID, first.getTenantId());
        assertEquals(APPLICATION_ID, first.getApplicationId());
        assertEquals(SHARD_ID, first.getShardId());
        assertEquals(REQUEST_ID, first.getAppleRequestUuid());
        assertEquals("tokens-1.xlsx", first.getFileName());
    }

    @Test
    void allBlankNamesAreRetriedInsteadOfAdvancingEmpty() throws IOException {
        FileNameListResponse response = new FileNameListResponse();
        response.setFileNames(List.of(" "));
        when(client.getFileNames(REQUEST_ID)).thenReturn(response);

        assertThrows(IOException.class, () -> handler.handle(context()));
        verify(tokenFileMapper, never()).insertBatchIgnore(anyList());
    }

    private StageContext context() {
        ApplicationPO application = new ApplicationPO();
        application.setId(APPLICATION_ID);
        application.setApplicationNo("A261010-ABCDEFGH");
        return new StageContext(TENANT_ID, Stage.FILE_NAME, application, shard, "A261010-ABCDEFGH");
    }

}
