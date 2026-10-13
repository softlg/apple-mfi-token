package com.galaplat.mfi.issuance.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.galaplat.mfi.apple.MfiTokenClient;
import com.galaplat.mfi.apple.model.MfiResponse;
import com.galaplat.mfi.credential.domain.AppleClientProvider;
import com.galaplat.mfi.shared.api.BizException;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPO;
import com.galaplat.mfi.identity.domain.AuditFact;
import com.galaplat.mfi.identity.domain.OperationAction;
import com.galaplat.mfi.issuance.infrastructure.persistence.TokenPO;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationMapper;
import com.galaplat.mfi.identity.application.AuditTrail;
import com.galaplat.mfi.issuance.infrastructure.persistence.TokenMapper;
import com.galaplat.mfi.shared.tenant.TenantContext;
import com.galaplat.mfi.shared.tenant.TenantPrincipal;
import com.galaplat.mfi.issuance.interfaces.TokenDestroyRequest;
import com.galaplat.mfi.issuance.interfaces.TokenDestroyVo;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 销毁未使用实体。这里盯的是不可逆动作的四条底线：非 owner 一律拒绝、二次确认必须逐字相同、
 * 只对 ISSUED 行提交、一批被拒就立刻停手并把已销毁的数量如实说出来。
 *
 * <p>批量切分用 120 行来测（单批上限 100），所以断言里能看到"同一 PPID 拆两批、第二个 PPID 单独一批"。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TokenDestroyServiceTest {

    private static final long TENANT_ID = 7L;
    private static final long OWNER_ID = 42L;
    private static final long APPLICATION_ID = 555L;
    private static final String APPLICATION_NO = "A261010-ABCDEFGH";
    private static final String PPID_A = "P6AAAAAA";
    private static final String PPID_B = "P6BBBBBB";

    @Mock
    private TokenMapper tokenMapper;
    @Mock
    private ApplicationMapper applicationMapper;
    @Mock
    private AuditTrail auditTrail;
    @Mock
    private AppleClientProvider clientFactory;
    @Mock
    private MfiTokenClient client;

    private TokenDestroyService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                ApplicationPO.class);
        TenantContext.bind(new TenantPrincipal(TENANT_ID, OWNER_ID, true));
        service = new TokenDestroyService(tokenMapper, applicationMapper, auditTrail, clientFactory);

        when(applicationMapper.selectOne(any())).thenReturn(application());
        when(clientFactory.forTenant(TENANT_ID)).thenReturn(client);
        // 落状态默认按提交数全额落地，只有"并发下已被别人改过"的用例才改成短数
        when(tokenMapper.markDestroyed(eq(TENANT_ID), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(1, List.class).size());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void destroyIsRefusedBeforeAnyReadForNonOwner() {
        TenantContext.bind(new TenantPrincipal(TENANT_ID, OWNER_ID, false));

        BizException e = assertThrows(BizException.class, () -> service.destroy(request(APPLICATION_NO)));
        assertTrue(e.getMessage().contains("owner"), e.getMessage());
        verifyNoInteractions(tokenMapper, applicationMapper, clientFactory, auditTrail);
    }

    @Test
    void confirmationMustEchoTheApplicationNumberVerbatim() {
        BizException mismatch = assertThrows(BizException.class,
                () -> service.destroy(request("A261010-WRONGXXX")));
        assertTrue(mismatch.getMessage().contains("二次确认"), mismatch.getMessage());

        assertThrows(BizException.class, () -> service.destroy(request(null)));

        verify(tokenMapper, never()).selectDestroyCandidates(anyLong(), anyLong(), any(), anyInt());
        verifyNoInteractions(clientFactory, auditTrail);
    }

    @Test
    void foreignApplicationIsRejectedWithoutTouchingApple() {
        when(applicationMapper.selectOne(any())).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> service.destroy(request(APPLICATION_NO)));
        assertTrue(e.getMessage().contains("不属于当前租户"), e.getMessage());
        verifyNoInteractions(clientFactory, tokenMapper, auditTrail);
    }

    @Test
    void candidatesAreGroupedByPpidBeforeBeingChunked() throws IOException {
        // 120 行同 PPID 再插 1 行另一个 PPID（故意插在中间）：单批 100，所以期望 A 两批、B 一批
        List<TokenPO> candidates = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            candidates.add(candidate(100L + i, PPID_A));
        }
        candidates.add(50, candidate(900L, PPID_B));
        when(tokenMapper.selectDestroyCandidates(eq(TENANT_ID), eq(APPLICATION_ID), eq(null), anyInt()))
                .thenReturn(candidates);
        when(client.destroyUnusedAuthEntities(anyString(), anyList())).thenReturn(ok());

        TokenDestroyVo vo = service.destroy(request(APPLICATION_NO));

        ArgumentCaptor<String> ppid = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<String>> ids = ArgumentCaptor.forClass(List.class);
        verify(client, times(3)).destroyUnusedAuthEntities(ppid.capture(), ids.capture());
        assertEquals(List.of(PPID_A, PPID_A, PPID_B), ppid.getAllValues());
        assertEquals(List.of(100, 20, 1), ids.getAllValues().stream().map(List::size).toList());
        // 提交给苹果的必须是认证实体编号，不是本地主键
        assertEquals("AUTH-100", ids.getAllValues().get(0).get(0));
        assertEquals(121, vo.getDestroyed());
        assertEquals(121, vo.getSelected());
        assertFalse(vo.isHasMore());
        assertNull(vo.getFailReason());

        ArgumentCaptor<AuditFact> logs = ArgumentCaptor.forClass(AuditFact.class);
        verify(auditTrail).append(logs.capture());
        assertEquals(OperationAction.DESTROY, logs.getValue().action());
        assertEquals(121, logs.getValue().quantity().intValue());
        assertEquals(APPLICATION_ID, logs.getValue().applicationId().longValue());
    }

    @Test
    void rejectedBatchStopsTheRestAndReportsWhatIsAlreadyGone() throws IOException {
        List<TokenPO> candidates = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            candidates.add(candidate(100L + i, PPID_A));
        }
        when(tokenMapper.selectDestroyCandidates(eq(TENANT_ID), eq(APPLICATION_ID), eq(null), anyInt()))
                .thenReturn(candidates);
        // 三批里第一批成功、第二批被拒，第三批根本不该发出去
        when(client.destroyUnusedAuthEntities(anyString(), anyList())).thenReturn(ok(), rejected());

        TokenDestroyVo vo = service.destroy(request(APPLICATION_NO));

        verify(client, times(2)).destroyUnusedAuthEntities(anyString(), anyList());
        verify(tokenMapper, times(1)).markDestroyed(eq(TENANT_ID), anyList());
        assertEquals(100, vo.getDestroyed(), "已销毁的 100 个必须如实报出来，不能被一次失败盖住");
        assertEquals(150, vo.getSelected());
        assertTrue(vo.isHasMore(), "被拒的批次还在库里是 ISSUED，得告诉用户可以再来一次");
        assertTrue(vo.getFailReason().contains("AUTH_ENTITY_NOT_UNUSED"), vo.getFailReason());

        ArgumentCaptor<AuditFact> logs = ArgumentCaptor.forClass(AuditFact.class);
        verify(auditTrail).append(logs.capture());
        assertEquals(100, logs.getValue().quantity().intValue());
        assertTrue(logs.getValue().detail().contains("中止"), logs.getValue().detail());
    }

    @Test
    void undeliverableRequestIsNotRetriedAndLeavesTheRowsIssued() throws IOException {
        when(tokenMapper.selectDestroyCandidates(eq(TENANT_ID), eq(APPLICATION_ID), eq(null), anyInt()))
                .thenReturn(List.of(candidate(101L, PPID_A)));
        when(client.destroyUnusedAuthEntities(anyString(), anyList())).thenThrow(new IOException("connect timed out"));

        TokenDestroyVo vo = service.destroy(request(APPLICATION_NO));

        verify(client, times(1)).destroyUnusedAuthEntities(anyString(), anyList());
        verify(tokenMapper, never()).markDestroyed(anyLong(), anyList());
        assertEquals(0, vo.getDestroyed());
        assertTrue(vo.isHasMore());
        assertTrue(vo.getFailReason().contains("未能送达"), vo.getFailReason());
        // 一次都没成功也要留痕：谁在什么时候试图销毁过什么，是审计要问的
        verify(auditTrail).append(any(AuditFact.class));
    }

    @Test
    void noCandidatesIsNotAnErrorAndNeverOpensAnAppleCall() {
        when(tokenMapper.selectDestroyCandidates(eq(TENANT_ID), eq(APPLICATION_ID), eq(PPID_B), anyInt()))
                .thenReturn(List.of());

        TokenDestroyVo vo = service.destroy(requestWithPpid(APPLICATION_NO, " " + PPID_B + " "));

        assertEquals(0, vo.getSelected());
        assertFalse(vo.isHasMore());
        assertNull(vo.getFailReason());
        verifyNoInteractions(clientFactory);
        verify(auditTrail, never()).append(any(AuditFact.class));
    }

    @Test
    void aFullPageOfCandidatesKeepsHasMoreSoTheOperatorClicksAgain() throws IOException {
        List<TokenPO> candidates = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            candidates.add(candidate(1000L + i, PPID_A));
        }
        when(tokenMapper.selectDestroyCandidates(eq(TENANT_ID), eq(APPLICATION_ID), eq(null), anyInt()))
                .thenReturn(candidates);
        when(client.destroyUnusedAuthEntities(anyString(), anyList())).thenReturn(ok());

        TokenDestroyVo vo = service.destroy(request(APPLICATION_NO));

        verify(client, times(5)).destroyUnusedAuthEntities(anyString(), anyList());
        assertEquals(500, vo.getDestroyed());
        assertTrue(vo.isHasMore(), "单次上限取满了，剩下的只能靠下一次请求");
    }

    @Test
    void auditTrailCarriesCountsOnlyNeverThePpidOrEntityIds() throws IOException {
        when(tokenMapper.selectDestroyCandidates(eq(TENANT_ID), eq(APPLICATION_ID), eq(null), anyInt()))
                .thenReturn(List.of(candidate(101L, PPID_A), candidate(102L, PPID_A)));
        when(client.destroyUnusedAuthEntities(anyString(), anyList())).thenReturn(ok());

        service.destroy(request(APPLICATION_NO));

        ArgumentCaptor<AuditFact> logs = ArgumentCaptor.forClass(AuditFact.class);
        verify(auditTrail).append(logs.capture());
        String detail = logs.getValue().detail();
        assertFalse(detail.contains(PPID_A), detail);
        assertFalse(detail.contains("AUTH-101"), detail);
    }

    @Test
    void aCandidateWithoutAuthEntityIdIsDataDamageNotASilentSkip() throws IOException {
        TokenPO damaged = candidate(101L, PPID_A);
        damaged.setAuthEntityId("  ");
        when(tokenMapper.selectDestroyCandidates(eq(TENANT_ID), eq(APPLICATION_ID), eq(null), anyInt()))
                .thenReturn(List.of(damaged));

        BizException e = assertThrows(BizException.class, () -> service.destroy(request(APPLICATION_NO)));
        assertTrue(e.getMessage().contains("tokenId=101"), e.getMessage());
        verifyNoInteractions(clientFactory);
        verify(auditTrail, never()).append(any(AuditFact.class));
    }

    /** 苹果的销毁请求按 PPID 定位额度，所以归堆结果必须与候选行的 PPID 一致 */
    private static TokenPO candidate(long id, String ppid) {
        TokenPO row = new TokenPO();
        row.setId(id);
        row.setTenantId(TENANT_ID);
        row.setApplicationId(APPLICATION_ID);
        row.setPpid(ppid);
        row.setAuthEntityId("AUTH-" + id);
        row.setStatus("ISSUED");
        row.setRegisterStatus("PENDING");
        return row;
    }

    private static MfiResponse ok() {
        return new MfiResponse();
    }

    private static MfiResponse rejected() {
        MfiResponse response = new MfiResponse();
        response.setCode(403);
        response.setErrorCode("AUTH_ENTITY_NOT_UNUSED");
        response.setErrorMessage("实体已被使用");
        return response;
    }

    private static TokenDestroyRequest request(String confirmApplicationNo) {
        return requestWithPpid(confirmApplicationNo, null);
    }

    private static TokenDestroyRequest requestWithPpid(String confirmApplicationNo, String ppid) {
        TokenDestroyRequest request = new TokenDestroyRequest();
        request.setApplicationNo(APPLICATION_NO);
        request.setConfirmApplicationNo(confirmApplicationNo);
        request.setPpid(ppid);
        return request;
    }

    private static ApplicationPO application() {
        ApplicationPO row = new ApplicationPO();
        row.setTenantId(TENANT_ID);
        row.setId(APPLICATION_ID);
        row.setApplicationNo(APPLICATION_NO);
        return row;
    }

}
