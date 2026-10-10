package com.galaplat.mfi.service.ppid;

import com.alibaba.excel.EasyExcelFactory;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.galaplat.mfi.common.exception.BizException;
import com.galaplat.mfi.dao.entity.PpIdPO;
import com.galaplat.mfi.dao.mapper.PpIdMapper;
import com.galaplat.mfi.dao.tenant.TenantContext;
import com.galaplat.mfi.dao.tenant.TenantPrincipal;
import com.galaplat.mfi.excel.PpIdImportRow;
import com.galaplat.mfi.model.request.PpIdUpdateRequest;
import com.galaplat.mfi.model.vo.PpIdAvailableVo;
import com.galaplat.mfi.model.vo.PpIdImportResultVo;
import com.galaplat.mfi.model.vo.PpIdPlanVo;
import com.galaplat.mfi.model.vo.PpIdRowErrorVo;
import com.galaplat.mfi.service.ppid.impl.PpIdServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PPID 池的分配预估与总量修改规则。这里盯的是两件会影响钱的事：预览的拆分是否就是申请时的拆分，
 * 以及人工改总量会不会覆盖掉并发的扣减。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PpIdServiceTest {

    private static final long TENANT_ID = 7L;
    private static final long USER_ID = 42L;

    @Mock
    private PpIdMapper ppidMapper;

    private PpIdServiceImpl service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), PpIdPO.class);
        TenantContext.bind(new TenantPrincipal(TENANT_ID, USER_ID, true));
        service = new PpIdServiceImpl(ppidMapper);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void availableSplitsGreedyBiggestFirst() {
        when(ppidMapper.selectAllocatable(TENANT_ID, "Model-A", "SPU-1"))
                .thenReturn(List.of(row(1L, "PPID-BIG", 30), row(2L, "PPID-SMALL", 5)));

        PpIdAvailableVo vo = service.available("Model-A", "SPU-1", 32);

        assertTrue(vo.getSufficient());
        assertEquals(35, vo.getAllocatableQuantity());
        assertEquals(List.of(30, 2), vo.getPlan().stream().map(PpIdPlanVo::getQuantity).toList());
        assertEquals(List.of("PPID-BIG", "PPID-SMALL"), vo.getPlan().stream().map(PpIdPlanVo::getPpid).toList());
    }

    @Test
    void availableReportsShortfallWithoutInventingQuantity() {
        when(ppidMapper.selectAllocatable(TENANT_ID, "Model-A", "SPU-1"))
                .thenReturn(List.of(row(1L, "PPID-BIG", 30), row(2L, "PPID-SMALL", 5)));

        PpIdAvailableVo vo = service.available("Model-A", "SPU-1", 40);

        assertFalse(vo.getSufficient());
        // 不够时计划里给的是"能给的都给"，让前端直接看出差多少
        assertEquals(35, vo.getPlan().stream().mapToInt(PpIdPlanVo::getQuantity).sum());
    }

    @Test
    void availableRejectsMissingPoolDimension() {
        assertThrows(BizException.class, () -> service.available("  ", "SPU-1", 10));
        assertThrows(BizException.class, () -> service.available("Model-A", "SPU-1", 0));
    }

    @Test
    void updateRejectsTotalBelowAlreadyApplied() {
        when(ppidMapper.selectById(1L)).thenReturn(ledger(1L, "PPID-1", 80, 60));

        PpIdUpdateRequest request = new PpIdUpdateRequest();
        request.setTotalQuantity(59);
        request.setStatus(1);

        BizException e = assertThrows(BizException.class, () -> service.update(1L, request));
        assertTrue(e.getMessage().contains("60"), e.getMessage());
        verify(ppidMapper, never()).update(ArgumentMatchers.any(), any());
    }

    @Test
    void updateRefusesToEnableZeroRemaining() {
        when(ppidMapper.selectById(1L)).thenReturn(ledger(1L, "PPID-1", 80, 80));

        PpIdUpdateRequest request = new PpIdUpdateRequest();
        request.setTotalQuantity(80);
        request.setStatus(1);

        BizException e = assertThrows(BizException.class, () -> service.update(1L, request));
        assertTrue(e.getMessage().contains("余量"), e.getMessage());
        verify(ppidMapper, never()).update(ArgumentMatchers.any(), any());
    }

    /**
     * 人工改总量必须带上"已申请量没变"的条件：分配线程随时在扣减，不加这个条件就会把中间刚扣掉的量
     * 按旧快照写回去，等于凭空多出额度。
     */
    @Test
    void updateCarriesAppliedQuantitySnapshotCondition() {
        when(ppidMapper.selectById(1L)).thenReturn(ledger(1L, "PPID-1", 80, 60));
        when(ppidMapper.update(ArgumentMatchers.isNull(), any())).thenReturn(1);

        PpIdUpdateRequest request = new PpIdUpdateRequest();
        request.setTotalQuantity(100);
        request.setStatus(1);
        service.update(1L, request);

        ArgumentCaptor<LambdaUpdateWrapper<PpIdPO>> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(ppidMapper).update(ArgumentMatchers.isNull(), captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("applied_quantity"), sql);
        // 条件里的已申请量与写回的新余量都来自同一份快照：100 - 60 = 40
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(60),
                captor.getValue().getParamNameValuePairs().toString());
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(40),
                captor.getValue().getParamNameValuePairs().toString());
    }

    @Test
    void updateAsksForRetryWhenConcurrentDeductWon() {
        when(ppidMapper.selectById(1L)).thenReturn(ledger(1L, "PPID-1", 80, 60));
        when(ppidMapper.update(ArgumentMatchers.isNull(), any())).thenReturn(0);

        PpIdUpdateRequest request = new PpIdUpdateRequest();
        request.setTotalQuantity(100);
        request.setStatus(1);

        BizException e = assertThrows(BizException.class, () -> service.update(1L, request));
        assertTrue(e.getMessage().contains("刷新"), e.getMessage());
    }

    @Test
    void disableOnlyChangesStatusAndKeepsLedger() {
        when(ppidMapper.selectById(1L)).thenReturn(ledger(1L, "PPID-1", 80, 60));
        when(ppidMapper.update(ArgumentMatchers.isNull(), any())).thenReturn(1);

        service.disable(1L);

        verify(ppidMapper).update(ArgumentMatchers.isNull(), any());
        verify(ppidMapper, never()).deleteById(anyLong());
    }

    /**
     * 导入的逐行定位。行号必须是用户在 Excel 里看到的行号（表头是第 1 行），否则报错没有意义。
     */
    @Test
    void importReportsEveryBadRowByExcelRowNumber() {
        when(ppidMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        List<PpIdImportRow> cells = List.of(
                imported("PPID-1", "SPU-1", "Model-A", "100"),
                imported("PPID-2", "  ", "Model-A", "100"),
                imported("PPID-3", "SPU-1", "Model-A", "12.5"),
                imported("PPID-1", "SPU-1", "Model-A", "50"),
                imported("PPID-5", "SPU-1", "Model-A", "0"));
        PpIdImportResultVo result = service.importExcel(workbook(cells));

        assertEquals(5, result.getReadRows());
        assertEquals(0, result.getInsertedRows());
        assertEquals(List.of(3, 4, 5, 6), result.getErrors().stream().map(PpIdRowErrorVo::getRowNo).toList());
        verify(ppidMapper, never()).insertBatch(anyList());
    }

    @Test
    void importRejectsRowAlreadyInPool() {
        when(ppidMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(row(9L, "PPID-1", 40)));

        PpIdImportResultVo result = service.importExcel(
                workbook(List.of(imported("PPID-1", "SPU-1", "Model-A", "100"))));

        assertEquals(0, result.getInsertedRows());
        assertTrue(result.getErrors().get(0).getMessage().contains("已存在"),
                result.getErrors().get(0).getMessage());
    }

    /**
     * 一万多行分片写入，而不是一行一个 INSERT；同时验证 "100.0" 这种被 Excel 存成数字的写法能认。
     */
    @Test
    void importWritesInChunksAndAcceptsDecimalStyledIntegers() {
        when(ppidMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(ppidMapper.insertBatch(anyList())).thenAnswer(invocation -> ((List<?>) invocation.getArgument(0)).size());

        List<PpIdImportRow> cells = new ArrayList<>();
        for (int i = 0; i < 1_200; i++) {
            cells.add(imported("PPID-" + i, "SPU-1", "Model-A", i % 3 == 0 ? "100.0" : "100"));
        }
        PpIdImportResultVo result = service.importExcel(workbook(cells));

        assertEquals(1_200, result.getInsertedRows());
        assertEquals(0, result.getRejectedRows());
        ArgumentCaptor<List<PpIdPO>> captor = ArgumentCaptor.forClass(List.class);
        verify(ppidMapper, org.mockito.Mockito.times(3)).insertBatch(captor.capture());
        assertEquals(List.of(500, 500, 200), captor.getAllValues().stream().map(List::size).toList());
        assertEquals(100, captor.getAllValues().get(0).get(0).getTotalQuantity());
    }

    @Test
    void importRejectsEmptyWorkbook() {
        assertThrows(BizException.class, () -> service.importExcel(workbook(List.of())));
        assertThrows(BizException.class, () -> service.importExcel(
                new MockMultipartFile("file", "ppid.csv", "text/csv", new byte[]{1})));
    }

    private static MockMultipartFile workbook(List<PpIdImportRow> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        EasyExcelFactory.write(out, PpIdImportRow.class).sheet("PPID").doWrite(rows);
        return new MockMultipartFile("file", "ppid.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
    }

    private static PpIdImportRow imported(String ppid, String spu, String productModel, String totalQuantity) {
        PpIdImportRow row = new PpIdImportRow();
        row.setPpid(ppid);
        row.setSpu(spu);
        row.setProductModel(productModel);
        row.setTotalQuantity(totalQuantity);
        return row;
    }

    private static PpIdPO row(Long id, String ppid, int remaining) {
        return row(id, ppid, remaining, remaining);
    }

    /** 账本行按 总量 + 已申请量 摆，余量算出来：直接写余量很容易把"已申请"当成"剩余" */
    private static PpIdPO ledger(long id, String ppid, int total, int applied) {
        return row(id, ppid, total - applied, total);
    }

    private static PpIdPO row(Long id, String ppid, int remaining, int total) {
        PpIdPO row = new PpIdPO();
        row.setId(id);
        row.setTenantId(TENANT_ID);
        row.setPpid(ppid);
        row.setSpu("SPU-1");
        row.setProductModel("Model-A");
        row.setTotalQuantity(total);
        row.setAppliedQuantity(total - remaining);
        row.setRemainingQuantity(remaining);
        row.setStatus(PpIdPO.STATUS_ENABLED);
        return row;
    }

}
