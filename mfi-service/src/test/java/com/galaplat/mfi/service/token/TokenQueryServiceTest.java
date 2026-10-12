package com.galaplat.mfi.service.token;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.galaplat.mfi.common.api.PageResult;
import com.galaplat.mfi.common.exception.BizException;
import com.galaplat.mfi.dao.entity.ApplicationPO;
import com.galaplat.mfi.dao.entity.TokenPO;
import com.galaplat.mfi.dao.mapper.ApplicationMapper;
import com.galaplat.mfi.dao.mapper.TokenMapper;
import com.galaplat.mfi.dao.tenant.TenantContext;
import com.galaplat.mfi.dao.tenant.TenantPrincipal;
import com.galaplat.mfi.model.request.TokenPageRequest;
import com.galaplat.mfi.model.vo.TokenVo;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * token 结果分页。这里要钉住的是"列表接口不带凭证材料"：一旦 encoded_token 出现在响应里，
 * 它就会随浏览器历史、网关日志和前端埋点一路漏出去，而这正是本服务要替厂商守住的东西。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TokenQueryServiceTest {

    private static final long TENANT_ID = 7L;
    private static final long APPLICATION_ID = 555L;

    @Mock
    private TokenMapper tokenMapper;
    @Mock
    private ApplicationMapper applicationMapper;

    private TokenQueryService service;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, TokenPO.class);
        TableInfoHelper.initTableInfo(assistant, ApplicationPO.class);
        TenantContext.bind(new TenantPrincipal(TENANT_ID, 42L, true));
        service = new TokenQueryService(tokenMapper, applicationMapper);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void querySelectsNoColumnThatCarriesCredentialMaterial() {
        List<AbstractWrapper<TokenPO, ?, ?>> captured = new ArrayList<>();
        when(tokenMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            captured.add(invocation.getArgument(1));
            return page(List.of(token(1L, "PPID-A", "SN-1", "REGISTERED", "SUCCESS")), 1);
        });

        service.page(request());

        String select = captured.get(0).getSqlSelect();
        assertTrue(select.contains("manufacture_sn"), select);
        assertFalse(select.contains("encoded_token"), select);
        assertFalse(select.contains("secret_key"), select);
        assertFalse(select.contains("auth_entity_id"), select);
        assertFalse(select.contains("accessory_uuid"), select);
    }

    @Test
    void pageRowExposesOnlyReconciliationFields() {
        TokenPO row = token(11L, "PPID-A", "E072783B10100001", "ISSUED", "PENDING");
        row.setEncodedToken("VE9LT04=");
        row.setSecretKey("crc32-secret");
        row.setAuthEntityId("entity-id");
        when(tokenMapper.selectPage(any(), any())).thenReturn(page(List.of(row), 10_000));

        PageResult<TokenVo> result = service.page(request());

        assertEquals(1, result.getRecords().size());
        TokenVo vo = result.getRecords().get(0);
        assertEquals(11L, vo.getId().longValue());
        assertEquals("E072783B10100001", vo.getManufactureSn());
        assertEquals("ISSUED", vo.getStatus());
        // 分页元信息原样透出，前端要靠 total 决定页数
        assertEquals(10_000, result.getTotal());
        assertEquals(20, result.getPageSize());
    }

    @Test
    void applicationNumberIsResolvedToItsIdBeforeFiltering() {
        when(applicationMapper.selectOne(any())).thenReturn(application());
        List<AbstractWrapper<TokenPO, ?, ?>> captured = new ArrayList<>();
        when(tokenMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            captured.add(invocation.getArgument(1));
            return page(List.of(), 0);
        });

        TokenPageRequest request = request();
        request.setApplicationNo("A261010-ABCDEFGH");
        request.setPpid("PPID-A");
        service.page(request);

        // 单号是对外标识，落到 token 查询里必须是内部主键：t_token 上没有 application_no 这一列，
        // 按单号字符串比对只会把条件静默丢掉，变成把整个租户的 token 都给出去
        verify(applicationMapper).selectOne(any());
        String segment = captured.get(0).getSqlSegment();
        assertTrue(segment.contains("tenant_id"), segment);
        assertTrue(segment.contains("application_id"), segment);
        assertTrue(segment.contains("ppid"), segment);
        assertFalse(segment.contains("application_no"), segment);
    }

    @Test
    void unknownApplicationNumberNeverReachesTokenTable() {
        when(applicationMapper.selectOne(any())).thenReturn(null);

        TokenPageRequest request = request();
        request.setApplicationNo("A261010-ZZZZZZZZ");
        BizException failure = assertThrows(BizException.class, () -> service.page(request));

        assertEquals("申请单不存在，或不属于当前租户", failure.getMessage());
        verify(tokenMapper, never()).selectPage(any(), any());
    }

    @Test
    void unsupportedStatusIsRejectedInsteadOfBecomingAQueryValue() {
        TokenPageRequest request = request();
        request.setStatus("NOT_A_STATUS");

        BizException failure = assertThrows(BizException.class, () -> service.page(request));

        assertEquals("不支持的状态: NOT_A_STATUS", failure.getMessage());
        verify(tokenMapper, never()).selectPage(any(), any());
    }

    @Test
    void unsupportedRegisterStatusIsRejected() {
        TokenPageRequest request = request();
        request.setRegisterStatus("MAYBE");

        assertThrows(BizException.class, () -> service.page(request));
    }

    private static TokenPageRequest request() {
        TokenPageRequest request = new TokenPageRequest();
        request.setPageNum(1);
        request.setPageSize(20);
        return request;
    }

    private static Page<TokenPO> page(List<TokenPO> records, long total) {
        Page<TokenPO> page = Page.of(1, 20);
        page.setRecords(records);
        page.setTotal(total);
        return page;
    }

    private static TokenPO token(long id, String ppid, String sn, String status, String registerStatus) {
        TokenPO row = new TokenPO();
        row.setId(id);
        row.setTenantId(TENANT_ID);
        row.setApplicationId(APPLICATION_ID);
        row.setPpid(ppid);
        row.setManufactureSn(sn);
        row.setStatus(status);
        row.setRegisterStatus(registerStatus);
        row.setDownloadTime(LocalDateTime.now());
        return row;
    }

    private static ApplicationPO application() {
        ApplicationPO application = new ApplicationPO();
        application.setId(APPLICATION_ID);
        application.setTenantId(TENANT_ID);
        application.setApplicationNo("A261010-ABCDEFGH");
        return application;
    }

}
