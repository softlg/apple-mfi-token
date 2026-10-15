package com.galaplat.mfi.issuance.application.pipeline.handler;

import com.galaplat.mfi.apple.MfiTokenClient;
import com.galaplat.mfi.apple.model.TokenFileRow;
import com.galaplat.mfi.apple.model.TokenFileDownload;
import com.galaplat.mfi.credential.domain.AppleClientProvider;
import com.galaplat.mfi.issuance.domain.Stage;
import com.galaplat.mfi.shared.api.BizException;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPO;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPpidMapper;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPpidPO;
import com.galaplat.mfi.issuance.infrastructure.persistence.TokenFilePO;
import com.galaplat.mfi.issuance.infrastructure.persistence.TokenPO;
import com.galaplat.mfi.issuance.infrastructure.persistence.TokenFileMapper;
import com.galaplat.mfi.issuance.infrastructure.persistence.TokenMapper;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * FILE_CONTENT 阶段。这里定着三条会直接影响对账的规矩：按文件推进（已成功的文件不重复下载），
 * 以及 accessory_uuid 在下载时就生成并随 token 落库（注册重试才能复用同一个配件 UUID），
 * 以及每轮之前必须确认分片租约还在自己手里（否则同一跳会出现两个消费者）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FileContentStageHandlerTest {

    private static final long TENANT_ID = 7L;
    private static final long APPLICATION_ID = 555L;
    private static final long SHARD_ID = 901L;
    private static final String REQUEST_ID = "req-20261010";
    private static final String FILE_NAME = "tokens-1.xlsx";

    @Mock
    private AppleClientProvider clientFactory;
    @Mock
    private MfiTokenClient client;
    @Mock
    private TokenFileMapper tokenFileMapper;
    @Mock
    private TokenMapper tokenMapper;
    @Mock
    private ApplicationPpidMapper shardMapper;

    private FileContentStageHandler handler;
    private ApplicationPpidPO shard;

    @BeforeEach
    void setUp() {
        handler = new FileContentStageHandler(clientFactory, tokenFileMapper, tokenMapper, shardMapper);
        shard = new ApplicationPpidPO();
        shard.setId(SHARD_ID);
        shard.setTenantId(TENANT_ID);
        shard.setApplicationId(APPLICATION_ID);
        shard.setPpid("C00EXAMPLEPPID");
        shard.setAllocateCount(3);
        shard.setStage(Stage.FILE_CONTENT.name());
        shard.setAppleRequestUuid(REQUEST_ID);
        when(clientFactory.forTenant(TENANT_ID)).thenReturn(client);
        // 默认续租成功，即"租约还在本消费者手里"；只有让位用例把它改成 0
        when(shardMapper.touchProcessing(TENANT_ID, SHARD_ID, Stage.FILE_CONTENT.name())).thenReturn(1);
    }

    @Test
    void noRegisteredFileIsPermanentFailure() {
        when(tokenFileMapper.selectByShard(TENANT_ID, SHARD_ID)).thenReturn(List.of());

        // 上一跳没登记任何文件就到不了这里，出现即数据被改坏，重试不会有货
        assertThrows(BizException.class, () -> handler.handle(context()));
        verifyNoInteractions(client);
    }

    @Test
    void downloadedFileIsNotFetchedAgain() throws IOException {
        when(tokenFileMapper.selectByShard(TENANT_ID, SHARD_ID))
                .thenReturn(List.of(file(11L, FILE_NAME, "SUCCESS", 0)));

        StageOutcome outcome = handler.handle(context());

        assertEquals(StageOutcome.Kind.ADVANCE, outcome.kind());
        verify(client, never()).downloadTokenFile(anyString(), anyString());
    }

    @Test
    void downloadFailureMarksTheFileAndRetries() throws IOException {
        when(tokenFileMapper.selectByShard(TENANT_ID, SHARD_ID))
                .thenReturn(List.of(file(11L, FILE_NAME, "PENDING", 1)));
        when(client.downloadTokenFile(REQUEST_ID, FILE_NAME))
                .thenReturn(TokenFileDownload.failed(503, null));

        assertThrows(IOException.class, () -> handler.handle(context()));

        verify(tokenFileMapper).markDownloadFailed(eq(TENANT_ID), eq(11L), eq(2), eq(false), anyString());
        verify(tokenMapper, never()).insertBatchIgnore(anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void tokensKeepCredentialColumnsAndGetTheirOwnAccessoryUuid() throws IOException {
        when(tokenFileMapper.selectByShard(TENANT_ID, SHARD_ID))
                .thenReturn(List.of(file(11L, FILE_NAME, "PENDING", 0)));
        when(client.downloadTokenFile(REQUEST_ID, FILE_NAME)).thenReturn(TokenFileDownload.ok(200, List.of(
                row("C00EXAMPLEPPID", "token-1", "BASE64-1", "AA11BB22"),
                // 苹果在 PPID 列回显为空时会用分片的 ppid 兜底，两种写法都必须落库成功
                row(null, "token-2", "BASE64-2", "CC33DD44"),
                row("C00EXAMPLEPPID", " ", "BASE64-3", "EE55FF66"))));

        StageOutcome outcome = handler.handle(context());

        assertEquals(StageOutcome.Kind.ADVANCE, outcome.kind());
        ArgumentCaptor<List<TokenPO>> landed = ArgumentCaptor.forClass(List.class);
        verify(tokenMapper).insertBatchIgnore(landed.capture());
        // 缺少 Token ID 的行无法注册，也撞不出唯一键语义，只能丢弃并留下差异供对账发现
        assertEquals(2, landed.getValue().size());

        TokenPO first = landed.getValue().get(0);
        assertEquals(TENANT_ID, first.getTenantId());
        assertEquals(APPLICATION_ID, first.getApplicationId());
        assertEquals(SHARD_ID, first.getShardId());
        assertEquals(11L, first.getFileId());
        assertEquals("token-1", first.getAuthEntityId());
        assertEquals("AA11BB22", first.getSecretKey());
        assertEquals("BASE64-1", first.getEncodedToken());
        assertNotNull(first.getAccessoryUUID());
        assertEquals("C00EXAMPLEPPID", landed.getValue().get(1).getPpid());
        // 注册是不可逆动作：每个 token 必须有自己独立的配件 UUID
        assertNotEquals(first.getAccessoryUUID(), landed.getValue().get(1).getAccessoryUUID());
        verify(tokenFileMapper).markDownloaded(TENANT_ID, 11L, 2);
    }

    @Test
    void bigFilesAreInsertedInChunks() throws IOException {
        List<TokenFileRow> rows = new ArrayList<>();
        for (int index = 0; index < 600; index++) {
            rows.add(row("C00EXAMPLEPPID", "token-" + index, "BASE64-" + index, "CRC" + index));
        }
        when(tokenFileMapper.selectByShard(TENANT_ID, SHARD_ID))
                .thenReturn(List.of(file(11L, FILE_NAME, "PENDING", 0)));
        when(client.downloadTokenFile(REQUEST_ID, FILE_NAME)).thenReturn(TokenFileDownload.ok(200, rows));

        handler.handle(context());

        // 一条 600 行的多值 INSERT 会撑爆 max_allowed_packet 并长时间锁表
        ArgumentCaptor<List<TokenPO>> chunks = ArgumentCaptor.forClass(List.class);
        verify(tokenMapper, times(2)).insertBatchIgnore(chunks.capture());
        assertEquals(500, chunks.getAllValues().get(0).size());
        assertEquals(100, chunks.getAllValues().get(1).size());
    }

    @Test
    void everyPendingFileOfTheShardIsFetched() throws IOException {
        when(tokenFileMapper.selectByShard(TENANT_ID, SHARD_ID)).thenReturn(List.of(
                file(11L, "tokens-1.xlsx", "SUCCESS", 0),
                file(12L, "tokens-2.xlsx", "FAILED", 2)));
        when(client.downloadTokenFile(REQUEST_ID, "tokens-2.xlsx"))
                .thenReturn(TokenFileDownload.ok(200, List.of(row("C00EXAMPLEPPID", "token-9", "B9", "CRC9"))));

        handler.handle(context());

        verify(client, times(1)).downloadTokenFile(eq(REQUEST_ID), anyString());
        verify(tokenFileMapper).markDownloaded(TENANT_ID, 12L, 1);
        verify(tokenFileMapper, never()).markDownloaded(eq(TENANT_ID), eq(11L), anyInt());
    }

    @Test
    void lostLeaseHandsTheShardBackAndStopsFetching() throws IOException {
        when(tokenFileMapper.selectByShard(TENANT_ID, SHARD_ID)).thenReturn(List.of(
                file(11L, "tokens-1.xlsx", "PENDING", 0),
                file(12L, "tokens-2.xlsx", "PENDING", 0)));
        when(client.downloadTokenFile(REQUEST_ID, "tokens-1.xlsx"))
                .thenReturn(TokenFileDownload.ok(200, List.of(row("C00EXAMPLEPPID", "token-9", "B9", "CRC9"))));
        // 第二次续租返回 0：分片已被兜底扫描复位或被别的消费者领走，继续下载就是两个消费者同一跳各调一次苹果
        when(shardMapper.touchProcessing(TENANT_ID, SHARD_ID, Stage.FILE_CONTENT.name())).thenReturn(1, 0);

        StageOutcome outcome = handler.handle(context());

        // 让位必须是等待而不是前进：这一跳还有文件没下，判成功会让后面的收口校验数到缺货
        assertEquals(StageOutcome.Kind.WAIT, outcome.kind());
        verify(client, times(1)).downloadTokenFile(eq(REQUEST_ID), anyString());
        verify(tokenFileMapper, never()).markDownloaded(eq(TENANT_ID), eq(12L), anyInt());
    }

    private static TokenFileRow row(String ppid, String tokenId, String encoded, String crc32) {
        TokenFileRow row = new TokenFileRow();
        row.setPpid(ppid);
        row.setTokenId(tokenId);
        row.setBase64EncodedToken(encoded);
        row.setCrc32(crc32);
        return row;
    }

    private static TokenFilePO file(long id, String fileName, String status, int retryCount) {
        TokenFilePO file = new TokenFilePO();
        file.setId(id);
        file.setTenantId(TENANT_ID);
        file.setApplicationId(APPLICATION_ID);
        file.setShardId(SHARD_ID);
        file.setPpid("C00EXAMPLEPPID");
        file.setAppleRequestUuid(REQUEST_ID);
        file.setFileName(fileName);
        file.setDownloadStatus(status);
        file.setRetryCount(retryCount);
        return file;
    }

    private StageContext context() {
        ApplicationPO application = new ApplicationPO();
        application.setId(APPLICATION_ID);
        application.setApplicationNo("A261010-ABCDEFGH");
        return new StageContext(TENANT_ID, Stage.FILE_CONTENT, application, shard, "A261010-ABCDEFGH");
    }

}
