package com.galaplat.mfi.issuance.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.galaplat.mfi.issuance.domain.ExportTaskStatus;
import com.galaplat.mfi.shared.api.BizException;
import com.galaplat.mfi.issuance.config.ExportProperties;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPO;
import com.galaplat.mfi.issuance.infrastructure.persistence.ExportTaskPO;
import com.galaplat.mfi.identity.domain.AuditFact;
import com.galaplat.mfi.identity.domain.OperationAction;
import com.galaplat.mfi.issuance.infrastructure.persistence.TokenPO;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationMapper;
import com.galaplat.mfi.issuance.infrastructure.persistence.ExportTaskMapper;
import com.galaplat.mfi.identity.application.AuditTrail;
import com.galaplat.mfi.issuance.infrastructure.persistence.TokenMapper;
import com.galaplat.mfi.shared.tenant.TenantContext;
import com.galaplat.mfi.shared.tenant.TenantPrincipal;
import com.galaplat.mfi.issuance.interfaces.TokenExportRequest;
import com.galaplat.mfi.issuance.interfaces.ExportTaskVo;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 异步导出。盯四件事：领取任务的 CAS、分页游标只前进不回头、产物只落在配置目录且只认发起人、
 * 以及任何失败都会把任务落到 FAILED 而不是留在 PROCESSING 等死。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExportServiceImplTest {

    private static final long TENANT_ID = 7L;
    private static final long USER_ID = 42L;
    private static final long OTHER_USER_ID = 43L;
    private static final long TASK_ID = 601L;
    private static final long APPLICATION_ID = 555L;
    private static final String TASK_NO = "E261012-TASK001";
    private static final String HEADER = "PPID,Token ID,配件UUID,制造序列号,Secret Key,Token,状态,注册状态,下载时间,注册时间";

    /** 实例字段：@BeforeEach 与用例方法参数各自注入的是不同的临时目录，只有字段是同一份 */
    @TempDir
    private Path exportDir;

    @Mock
    private ExportTaskMapper exportTaskMapper;
    @Mock
    private TokenMapper tokenMapper;
    @Mock
    private ApplicationMapper applicationMapper;
    @Mock
    private AuditTrail auditTrail;

    private ExportProperties properties;
    private ExportServiceImpl service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                ApplicationPO.class);
        TenantContext.bind(new TenantPrincipal(TENANT_ID, USER_ID, false));
        properties = new ExportProperties();
        properties.setDir(exportDir.toString());
        properties.setPageSize(2);
        properties.setMaxRows(1000);
        // 同步执行器：create() 里提交的任务会立刻在这条线程上跑完，断言不用等异步
        service = new ExportServiceImpl(exportTaskMapper, tokenMapper, applicationMapper, auditTrail,
                properties, new ObjectMapper(), Runnable::run);

        when(exportTaskMapper.insert(any(ExportTaskPO.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ExportTaskPO.class).setId(TASK_ID);
            return 1;
        });
        // 默认"抢不到任务"，让只关心受理逻辑的用例不会顺带把整份导出跑完
        when(exportTaskMapper.markProcessing(anyLong(), anyLong())).thenReturn(0);
        // 成功收口默认落定；只有"跑输了一次僵死判定"的用例把它改成 0
        when(exportTaskMapper.markSuccess(anyLong(), anyLong(), any(), anyInt())).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void createSnapshotsFiltersAndQueuesTheTask() {
        when(applicationMapper.selectOne(any())).thenReturn(application());
        TokenExportRequest request = new TokenExportRequest();
        request.setApplicationNo(" A261010-ABCDEFGH ");
        request.setStatus("issued");

        ExportTaskVo vo = service.create(request);

        ArgumentCaptor<ExportTaskPO> captor = ArgumentCaptor.forClass(ExportTaskPO.class);
        verify(exportTaskMapper).insert(captor.capture());
        ExportTaskPO task = captor.getValue();
        assertEquals(ExportTaskStatus.PENDING.name(), task.getStatus());
        assertEquals("CSV", task.getExportFormat());
        assertEquals(TENANT_ID, task.getTenantId().longValue());
        assertEquals(USER_ID, task.getRequestUserId().longValue());
        assertTrue(task.getTaskNo().startsWith("E"), task.getTaskNo());
        // 单号在受理时就换成内部 id：执行线程不再碰外部输入
        assertTrue(task.getParamSnapshot().contains("\"applicationId\":" + APPLICATION_ID),
                task.getParamSnapshot());
        assertTrue(task.getParamSnapshot().contains("ISSUED"), task.getParamSnapshot());
        assertEquals(task.getTaskNo(), vo.getTaskNo());
        assertNull(vo.getDownloadUrl());
        // 抢不到 PROCESSING 就不该读数据
        verify(tokenMapper, never()).selectExportPage(anyLong(), anyLong(), any(), any(), any(), anyInt());
    }

    @Test
    void createRejectsBadEnumsAndForeignApplicationBeforeTouchingTheTaskTable() {
        TokenExportRequest badStatus = new TokenExportRequest();
        badStatus.setStatus("NOT_A_STATUS");
        assertThrows(BizException.class, () -> service.create(badStatus));

        TokenExportRequest badFormat = new TokenExportRequest();
        badFormat.setFormat("PDF");
        assertThrows(BizException.class, () -> service.create(badFormat));

        TokenExportRequest foreignApplication = new TokenExportRequest();
        foreignApplication.setApplicationNo("A261010-NOPE");
        when(applicationMapper.selectOne(any())).thenReturn(null);
        BizException e = assertThrows(BizException.class, () -> service.create(foreignApplication));
        assertTrue(e.getMessage().contains("不属于当前租户"), e.getMessage());

        verify(exportTaskMapper, never()).insert(any(ExportTaskPO.class));
    }

    @Test
    void runPagesByKeysetAndWritesCsv() throws IOException {
        when(exportTaskMapper.markProcessing(TENANT_ID, TASK_ID)).thenReturn(1);
        when(exportTaskMapper.selectById(TASK_ID)).thenReturn(task("CSV"));
        when(tokenMapper.selectExportPage(eq(TENANT_ID), eq(0L), eq(null), eq(null), eq(null), eq(2)))
                .thenReturn(List.of(token(11L, "SN-A"), token(12L, "SN-B")));
        when(tokenMapper.selectExportPage(eq(TENANT_ID), eq(12L), eq(null), eq(null), eq(null), eq(2)))
                .thenReturn(List.of(token(31L, "SN-C")));

        service.run(TENANT_ID, TASK_ID, USER_ID);

        // 第三页不再需要：上一页不足整页就说明读完了
        verify(tokenMapper, times(2)).selectExportPage(eq(TENANT_ID), anyLong(), any(), any(), any(), anyInt());
        verify(exportTaskMapper).markSuccess(TENANT_ID, TASK_ID, TASK_NO + ".csv", 3);

        Path file = exportDir.resolve(TASK_NO + ".csv");
        assertTrue(Files.isReadable(file), file.toString());
        String csv = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("﻿"), "CSV 必须带 BOM，否则 Excel 双击打开是乱码");
        List<String> lines = csv.replace("﻿", "").lines().toList();
        assertEquals(4, lines.size(), csv);
        assertEquals(HEADER, lines.get(0));
        assertTrue(lines.get(1).contains("SN-A"), lines.get(1));
        assertTrue(lines.get(3).contains("SN-C"), lines.get(3));
        assertTrue(lines.get(1).contains("2026-10-12 10:30:00"), lines.get(1));

        ArgumentCaptor<AuditFact> logs = ArgumentCaptor.forClass(AuditFact.class);
        verify(auditTrail).append(logs.capture());
        assertEquals(OperationAction.EXPORT, logs.getValue().action());
        assertEquals(3, logs.getValue().quantity().intValue());
        // 留痕里绝不出现 token 原文与密钥
        assertFalse(logs.getValue().detail().contains("TOKEN-"), logs.getValue().detail());
        assertFalse(logs.getValue().detail().contains("CRC-"), logs.getValue().detail());
    }

    @Test
    void staleClaimDoesNotReadDataOrWriteAFile() throws IOException {
        when(exportTaskMapper.selectById(TASK_ID)).thenReturn(task("CSV"));

        service.run(TENANT_ID, TASK_ID, USER_ID);

        verify(tokenMapper, never()).selectExportPage(anyLong(), anyLong(), any(), any(), any(), anyInt());
        verify(exportTaskMapper, never()).markSuccess(anyLong(), anyLong(), any(), anyInt());
        try (var files = Files.list(exportDir)) {
            assertEquals(0, files.count(), "没抢到任务就不该留下产物");
        }
    }

    @Test
    void executionThatLostTheRaceDiscardsItsArtifact() {
        when(exportTaskMapper.markProcessing(TENANT_ID, TASK_ID)).thenReturn(1);
        when(exportTaskMapper.selectById(TASK_ID)).thenReturn(task("CSV"));
        when(tokenMapper.selectExportPage(eq(TENANT_ID), anyLong(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(token(11L, "SN-A")));
        // 已经被僵死扫描判过失败，慢执行者不能把它翻回成功
        when(exportTaskMapper.markSuccess(TENANT_ID, TASK_ID, TASK_NO + ".csv", 1)).thenReturn(0);

        service.run(TENANT_ID, TASK_ID, USER_ID);

        assertFalse(Files.exists(exportDir.resolve(TASK_NO + ".csv")));
        verify(auditTrail, never()).append(any(AuditFact.class));
    }

    @Test
    void failureLandsOnFailedInsteadOfStuckInProcessing() {
        when(exportTaskMapper.markProcessing(TENANT_ID, TASK_ID)).thenReturn(1);
        when(exportTaskMapper.selectById(TASK_ID)).thenReturn(task("CSV"));
        when(tokenMapper.selectExportPage(anyLong(), anyLong(), any(), any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("read boom"));

        service.run(TENANT_ID, TASK_ID, USER_ID);

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(exportTaskMapper).markFailed(eq(TENANT_ID), eq(TASK_ID), reason.capture());
        assertTrue(reason.getValue().contains("read boom"), reason.getValue());
        verify(exportTaskMapper, never()).markSuccess(anyLong(), anyLong(), any(), anyInt());
        verify(auditTrail, never()).append(any(AuditFact.class));
        // 半途文件不留在磁盘上：任务已经 FAILED，那份只有凭证材料、没人能下载
        assertFalse(Files.exists(exportDir.resolve(TASK_NO + ".csv")));
    }

    @Test
    void maxRowsTightensTheLastPageInsteadOfReadingAFullOne() {
        properties.setMaxRows(3);
        when(exportTaskMapper.markProcessing(TENANT_ID, TASK_ID)).thenReturn(1);
        when(exportTaskMapper.selectById(TASK_ID)).thenReturn(task("CSV"));
        when(tokenMapper.selectExportPage(anyLong(), anyLong(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(token(11L, "SN-A"), token(12L, "SN-B")), List.of(token(31L, "SN-C")));

        service.run(TENANT_ID, TASK_ID, USER_ID);

        ArgumentCaptor<Integer> limits = ArgumentCaptor.forClass(Integer.class);
        verify(tokenMapper, times(2)).selectExportPage(eq(TENANT_ID), anyLong(), any(), any(), any(),
                limits.capture());
        assertEquals(List.of(2, 1), limits.getAllValues());
        verify(exportTaskMapper).markSuccess(TENANT_ID, TASK_ID, TASK_NO + ".csv", 3);
    }

    @Test
    void statusAndArtifactAreScopedToTheRequester() throws IOException {
        ExportTaskPO mine = task("CSV");
        mine.setStatus(ExportTaskStatus.SUCCESS.name());
        mine.setFilePath(TASK_NO + ".csv");
        mine.setTotalRows(3);
        mine.setFinishTime(LocalDateTime.now());
        when(exportTaskMapper.selectByTaskNo(TENANT_ID, TASK_NO)).thenReturn(mine);
        Files.writeString(exportDir.resolve(TASK_NO + ".csv"), "x");

        ExportTaskVo vo = service.status(TASK_NO);
        assertEquals(3, vo.getTotalRows().intValue());
        assertEquals("/api/export-tasks/" + TASK_NO + "/download", vo.getDownloadUrl());

        var download = service.download(TASK_NO);
        assertEquals("tokens-" + TASK_NO + ".csv", download.fileName());
        assertEquals("text/csv", download.contentType());
        assertTrue(download.resource().isReadable());

        // 别人的任务与不存在的任务回同一句话，不给猜号的人反馈
        ExportTaskPO other = task("CSV");
        other.setRequestUserId(OTHER_USER_ID);
        when(exportTaskMapper.selectByTaskNo(TENANT_ID, "E261012-OTHER0")).thenReturn(other);
        BizException e = assertThrows(BizException.class, () -> service.status("E261012-OTHER0"));
        assertTrue(e.getMessage().contains("不属于当前账号"), e.getMessage());
        assertThrows(BizException.class, () -> service.status("E261012-MISSIN0"));

        ExportTaskPO running = task("CSV");
        running.setStatus(ExportTaskStatus.PROCESSING.name());
        when(exportTaskMapper.selectByTaskNo(TENANT_ID, "E261012-RUNIN00")).thenReturn(running);
        assertThrows(BizException.class, () -> service.download("E261012-RUNIN00"));
    }

    @Test
    void artifactNameFromTheTableCannotEscapeTheExportDir() {
        ExportTaskPO tampered = task("CSV");
        tampered.setStatus(ExportTaskStatus.SUCCESS.name());
        tampered.setFilePath("../leak.csv");
        when(exportTaskMapper.selectByTaskNo(TENANT_ID, TASK_NO)).thenReturn(tampered);

        assertThrows(BizException.class, () -> service.download(TASK_NO));
        assertFalse(Files.exists(exportDir.getParent().resolve("leak.csv")));
    }

    private static ExportTaskPO task(String format) {
        ExportTaskPO task = new ExportTaskPO();
        task.setId(TASK_ID);
        task.setTenantId(TENANT_ID);
        task.setTaskNo(TASK_NO);
        task.setRequestUserId(USER_ID);
        task.setExportFormat(format);
        task.setParamSnapshot("{\"applicationId\":null,\"ppid\":null,\"status\":null}");
        task.setStatus(ExportTaskStatus.PENDING.name());
        task.setCreateTime(LocalDateTime.now());
        return task;
    }

    private static ApplicationPO application() {
        ApplicationPO row = new ApplicationPO();
        row.setTenantId(TENANT_ID);
        row.setId(APPLICATION_ID);
        row.setApplicationNo("A261010-ABCDEFGH");
        return row;
    }

    private static TokenPO token(long id, String sn) {
        TokenPO row = new TokenPO();
        row.setId(id);
        row.setPpid("D9X4KQ2M");
        row.setAuthEntityId("AUTH-" + id);
        row.setAccessoryUUID("11111111-2222-3333-4444-5555555555" + id);
        row.setManufactureSn(sn);
        row.setSecretKey("CRC-" + id);
        row.setEncodedToken("TOKEN-" + sn);
        row.setStatus("ISSUED");
        row.setRegisterStatus("PENDING");
        row.setDownloadTime(LocalDateTime.of(2026, 10, 12, 10, 30, 0));
        return row;
    }

}
