package com.galaplat.mfi.service.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.galaplat.mfi.common.exception.BizException;
import com.galaplat.mfi.credential.CredentialService;
import com.galaplat.mfi.dao.entity.ApplicationPO;
import com.galaplat.mfi.dao.entity.ApplicationPpidPO;
import com.galaplat.mfi.dao.entity.TenantPO;
import com.galaplat.mfi.dao.entity.UserPO;
import com.galaplat.mfi.dao.mapper.ApplicationMapper;
import com.galaplat.mfi.dao.mapper.ApplicationPpidMapper;
import com.galaplat.mfi.dao.mapper.OperationLogMapper;
import com.galaplat.mfi.dao.mapper.PpIdMapper;
import com.galaplat.mfi.dao.mapper.TenantMapper;
import com.galaplat.mfi.dao.mapper.UserMapper;
import com.galaplat.mfi.dao.tenant.TenantContext;
import com.galaplat.mfi.dao.tenant.TenantPrincipal;
import com.galaplat.mfi.model.request.ApplyCreateRequest;
import com.galaplat.mfi.model.vo.ApplySubmitVo;
import com.galaplat.mfi.model.vo.CredentialVo;
import com.galaplat.mfi.model.vo.PpIdPlanVo;
import com.galaplat.mfi.mq.RabbitTopology;
import com.galaplat.mfi.mq.ShardMessageProducer;
import com.galaplat.mfi.mq.StageMessage;
import com.galaplat.mfi.service.application.impl.ApplicationServiceImpl;
import com.galaplat.mfi.service.ppid.PpIdService;
import com.galaplat.mfi.service.tenant.TenantConfigService;
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
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 申请同步段。盯的是三件会丢钱的事：消息会不会在事务回滚后照样发出去、并发抢额度时会不会留下半张单、
 * 以及重复点击会不会扣走两份额度。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApplicationServiceImplTest {

    private static final long TENANT_ID = 7L;
    private static final long USER_ID = 42L;
    private static final long APPLICATION_ID = 555L;

    @Mock
    private ApplicationMapper applicationMapper;
    @Mock
    private ApplicationPpidMapper shardMapper;
    @Mock
    private PpIdMapper ppidMapper;
    @Mock
    private OperationLogMapper operationLogMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private TenantMapper tenantMapper;
    @Mock
    private PpIdService ppidService;
    @Mock
    private TenantConfigService configService;
    @Mock
    private CredentialService credentialService;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private RabbitTemplate rabbitTemplate;

    private ShardMessageProducer producer;
    private ApplicationServiceImpl service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApplicationPO.class);
        TenantContext.bind(new TenantPrincipal(TENANT_ID, USER_ID, true));

        producer = new ShardMessageProducer(rabbitTemplate);
        service = new ApplicationServiceImpl(applicationMapper, shardMapper, ppidMapper, operationLogMapper,
                userMapper, tenantMapper, ppidService, configService, credentialService, producer, transactionTemplate);

        // TransactionTemplate 在单测里只承担"把回调跑一遍"，真正要验的是回调里登记的提交后回调
        doAnswer(invocation -> ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null))
                .when(transactionTemplate).execute(any());

        when(userMapper.selectById(USER_ID)).thenReturn(user("张三"));
        when(tenantMapper.selectById(TENANT_ID)).thenReturn(tenant("HomeKit"));
        when(credentialService.metadata(TENANT_ID)).thenReturn(credential(LocalDateTime.now().plusDays(30)));
        when(configService.intValue(eq(TENANT_ID), any())).thenReturn(0);
        when(applicationMapper.insert(any(ApplicationPO.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ApplicationPO.class).setId(APPLICATION_ID);
            return 1;
        });
        when(ppidMapper.deduct(anyLong(), anyLong(), anyInt())).thenReturn(1);
        when(shardMapper.selectByApplication(TENANT_ID, APPLICATION_ID))
                .thenReturn(List.of(shard(901L, "PPID-BIG", 100), shard(902L, "PPID-SMALL", 20)));
        // 模拟请求线程：事务同步已激活，正是 TransactionTemplate 内部的真实状态
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TenantContext.clear();
    }

    @Test
    void messagesLeaveOnlyAfterCommit() {
        when(ppidService.planAllocation("Model-A", "SPU-1", 120))
                .thenReturn(List.of(plan(11L, "PPID-BIG", 100), plan(12L, "PPID-SMALL", 20)));

        ApplySubmitVo vo = service.submit(request(120));

        assertEquals(2, vo.getPpidCount());
        assertEquals(List.of(100, 20), vo.getShards().stream().map(s -> s.getAllocateCount()).toList());
        assertEquals(false, vo.getDuplicated());
        verify(ppidMapper).deduct(TENANT_ID, 11L, 100);
        verify(ppidMapper).deduct(TENANT_ID, 12L, 20);
        verify(shardMapper).insertBatch(anyList());

        // 关键断言：受理成功不等于可以开工。此刻一条消息都不该离开进程
        verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class));

        commit();

        ArgumentCaptor<Object> payloads = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate, times(2)).convertAndSend(eq(RabbitTopology.EXCHANGE_NORMAL),
                eq(RabbitTopology.QUEUE_APPLY), payloads.capture());
        List<Long> shardIds = payloads.getAllValues().stream().map(m -> ((StageMessage) m).shardId()).toList();
        assertEquals(List.of(901L, 902L), shardIds);
        // 消息体只带 ID：单号做跟踪号，PPID 与数量留在库里
        assertTrue(payloads.getAllValues().stream().allMatch(m -> ((StageMessage) m).traceId() != null));
    }

    @Test
    void deductConflictLeavesNoMessageToCommit() {
        when(ppidService.planAllocation("Model-A", "SPU-1", 120))
                .thenReturn(List.of(plan(11L, "PPID-BIG", 100), plan(12L, "PPID-SMALL", 20)));
        when(ppidMapper.deduct(TENANT_ID, 12L, 20)).thenReturn(0);

        BizException e = assertThrows(BizException.class, () -> service.submit(request(120)));
        assertTrue(e.getMessage().contains("刷新"), e.getMessage());

        // 抢不到额度就没登记过任何提交后回调，后面再怎么 commit 也不会发出消息
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
        verify(shardMapper, never()).insertBatch(anyList());
        verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class));
    }

    @Test
    void insufficientPoolRejectsBeforeAnyDeduct() {
        when(ppidService.planAllocation("Model-A", "SPU-1", 136))
                .thenReturn(List.of(plan(11L, "PPID-BIG", 100), plan(12L, "PPID-SMALL", 30)));

        BizException e = assertThrows(BizException.class, () -> service.submit(request(136)));
        assertTrue(e.getMessage().contains("还差 6"), e.getMessage());
        verify(ppidMapper, never()).deduct(anyLong(), anyLong(), anyInt());
        verify(applicationMapper, never()).insert(any(ApplicationPO.class));
    }

    @Test
    void tenantMaxQuantityGateRunsBeforeTouchingThePool() {
        when(configService.intValue(eq(TENANT_ID), any())).thenReturn(100);

        BizException e = assertThrows(BizException.class, () -> service.submit(request(101)));
        assertTrue(e.getMessage().contains("100"), e.getMessage());
        verify(ppidService, never()).planAllocation(any(), any(), anyInt());
        verify(ppidMapper, never()).deduct(anyLong(), anyLong(), anyInt());
    }

    @Test
    void expiredCredentialFailsFastWithoutConsumingQuota() {
        when(credentialService.metadata(TENANT_ID)).thenReturn(credential(LocalDateTime.now().minusDays(1)));

        assertThrows(BizException.class, () -> service.submit(request(10)));
        verify(ppidMapper, never()).deduct(anyLong(), anyLong(), anyInt());
        verify(applicationMapper, never()).insert(any(ApplicationPO.class));
    }

    @Test
    void sameRequestIdReturnsExistingOrderWithoutSecondDeduct() {
        ApplyCreateRequest request = request(10);
        request.setRequestId("req-1");
        ApplicationPO existing = new ApplicationPO();
        existing.setTenantId(TENANT_ID);
        existing.setId(777L);
        existing.setApplicationNo("A261010-ABCDEFGH");
        existing.setStatus("PENDING");
        existing.setApplyQuantity(10);
        existing.setPpidCount(1);
        when(applicationMapper.selectOne(any())).thenReturn(existing);
        when(shardMapper.selectByApplication(TENANT_ID, 777L)).thenReturn(List.of(shard(903L, "PPID-X", 10)));

        ApplySubmitVo vo = service.submit(request);

        assertEquals("A261010-ABCDEFGH", vo.getApplicationNo());
        assertEquals(true, vo.getDuplicated());
        verify(ppidMapper, never()).deduct(anyLong(), anyLong(), anyInt());
        verify(transactionTemplate, never()).execute(any());
    }

    @Test
    void uniqueKeyRaceRollsBackAndReturnsWinner() {
        ApplyCreateRequest request = request(120);
        request.setRequestId("req-2");
        when(ppidService.planAllocation("Model-A", "SPU-1", 120))
                .thenReturn(List.of(plan(11L, "PPID-BIG", 120)));
        ApplicationPO winner = new ApplicationPO();
        winner.setTenantId(TENANT_ID);
        winner.setId(888L);
        winner.setApplicationNo("A261010-ZZZZZZZZ");
        winner.setStatus("PENDING");
        winner.setApplyQuantity(120);
        winner.setPpidCount(1);
        // 第一次预查没有（对手还没提交），撞唯一键后再查就是它
        when(applicationMapper.selectOne(any())).thenReturn(null, winner);
        when(applicationMapper.insert(any(ApplicationPO.class)))
                .thenThrow(new DuplicateKeyException("uk_application_idempotency"));
        when(shardMapper.selectByApplication(TENANT_ID, 888L)).thenReturn(List.of(shard(904L, "PPID-Y", 120)));

        ApplySubmitVo vo = service.submit(request);

        assertEquals("A261010-ZZZZZZZZ", vo.getApplicationNo());
        assertEquals(true, vo.getDuplicated());
        // 插单先于扣减，所以这条路径连额度都没碰过
        verify(ppidMapper, never()).deduct(anyLong(), anyLong(), anyInt());
    }

    @Test
    void producerRefusesToRegisterOutsideATransaction() {
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);

        assertThrows(IllegalStateException.class, () -> producer.publishAfterCommit(
                List.of(new StageMessage(TENANT_ID, 1L, 2L, "APPLY", "trace"))));
        verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class));
    }

    @Test
    void applicantNameAndTechnologyAreSnapshotted() {
        when(ppidService.planAllocation("Model-A", "SPU-1", 10)).thenReturn(List.of(plan(11L, "PPID-BIG", 10)));
        when(tenantMapper.selectById(TENANT_ID)).thenReturn(tenant("HomeKit"));

        ArgumentCaptor<ApplicationPO> captor = ArgumentCaptor.forClass(ApplicationPO.class);
        service.submit(request(10));

        verify(applicationMapper).insert(captor.capture());
        ApplicationPO saved = captor.getValue();
        assertEquals("张三", saved.getApplicantName());
        assertEquals("HomeKit", saved.getAppleTechnology());
        assertEquals("PENDING", saved.getStatus());
        assertEquals(0, saved.getTokenCount());
        assertEquals(1, saved.getPpidCount());
        assertNotNull(saved.getApplicationNo());
        assertTrue(saved.getApplicationNo().startsWith("A"), saved.getApplicationNo());
    }


    private static UserPO user(String name) {
        UserPO row = new UserPO();
        row.setId(USER_ID);
        row.setAccount("zhangsan");
        row.setName(name);
        return row;
    }

    private static TenantPO tenant(String technology) {
        TenantPO row = new TenantPO();
        row.setId(TENANT_ID);
        row.setAppleTechnology(technology);
        return row;
    }

    private static CredentialVo credential(LocalDateTime notAfter) {
        CredentialVo vo = new CredentialVo();
        vo.setCertNotAfter(notAfter);
        return vo;
    }

    private static ApplicationPpidPO shard(long id, String ppid, int allocateCount) {
        ApplicationPpidPO row = new ApplicationPpidPO();
        row.setId(id);
        row.setPpid(ppid);
        row.setAllocateCount(allocateCount);
        row.setStage("APPLY");
        row.setStageStatus("PENDING");
        return row;
    }

    private static PpIdPlanVo plan(long ppidId, String ppid, int quantity) {
        PpIdPlanVo vo = new PpIdPlanVo();
        vo.setPpidId(ppidId);
        vo.setPpid(ppid);
        vo.setQuantity(quantity);
        return vo;
    }

    private static ApplyCreateRequest request(int quantity) {
        ApplyCreateRequest request = new ApplyCreateRequest();
        request.setProductModel("Model-A");
        request.setSpu("SPU-1");
        request.setApplyQuantity(quantity);
        return request;
    }

    /**
     * 触发已登记的提交后回调，等价于事务真正 commit。
     */
    private void commit() {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
    }

}
