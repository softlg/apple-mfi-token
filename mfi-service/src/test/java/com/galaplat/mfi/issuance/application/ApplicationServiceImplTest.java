package com.galaplat.mfi.issuance.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.galaplat.mfi.shared.api.BizException;
import com.galaplat.mfi.issuance.config.PipelineProperties;
import com.galaplat.mfi.credential.application.CredentialService;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPO;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPpidPO;
import com.galaplat.mfi.identity.domain.AuditFact;
import com.galaplat.mfi.identity.domain.OperationAction;
import com.galaplat.mfi.identity.domain.TenantProfile;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationMapper;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPpidMapper;
import com.galaplat.mfi.identity.application.AccountReader;
import com.galaplat.mfi.identity.application.AuditTrail;
import com.galaplat.mfi.identity.application.TenantProfileReader;
import com.galaplat.mfi.ppid.domain.PpIdAllocation;
import com.galaplat.mfi.shared.api.ErrorCode;
import com.galaplat.mfi.shared.tenant.TenantContext;
import com.galaplat.mfi.shared.tenant.TenantPrincipal;
import com.galaplat.mfi.issuance.interfaces.ApplyCreateRequest;
import com.galaplat.mfi.issuance.interfaces.ApplicationRetryVo;
import com.galaplat.mfi.issuance.interfaces.ApplicationShardVo;
import com.galaplat.mfi.issuance.interfaces.ApplySubmitVo;
import com.galaplat.mfi.issuance.infrastructure.mq.PipelineNames;
import com.galaplat.mfi.issuance.infrastructure.mq.RabbitTopology;
import com.galaplat.mfi.issuance.infrastructure.mq.ShardMessageProducer;
import com.galaplat.mfi.issuance.infrastructure.mq.StageMessage;
import com.galaplat.mfi.issuance.application.ApplicationServiceImpl;
import com.galaplat.mfi.ppid.application.PpIdService;
import com.galaplat.mfi.identity.application.TenantConfigService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
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
    private AuditTrail auditTrail;
    @Mock
    private AccountReader accountReader;
    @Mock
    private TenantProfileReader tenantProfiles;
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

        producer = new ShardMessageProducer(rabbitTemplate, new PipelineNames(new PipelineProperties()));
        service = new ApplicationServiceImpl(applicationMapper, shardMapper, auditTrail, accountReader,
                tenantProfiles, ppidService, configService, credentialService, producer, transactionTemplate);

        // TransactionTemplate 在单测里只承担"把回调跑一遍"，真正要验的是回调里登记的提交后回调
        doAnswer(invocation -> ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null))
                .when(transactionTemplate).execute(any());

        when(accountReader.displayName(USER_ID)).thenReturn("张三");
        when(tenantProfiles.find(TENANT_ID)).thenReturn(new TenantProfile(null, "HomeKit"));
        when(configService.intValue(eq(TENANT_ID), any())).thenReturn(0);
        when(applicationMapper.insert(any(ApplicationPO.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ApplicationPO.class).setId(APPLICATION_ID);
            return 1;
        });
        when(ppidService.deduct(anyLong(), anyLong(), anyInt())).thenReturn(true);
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
        verify(ppidService).deduct(TENANT_ID, 11L, 100);
        verify(ppidService).deduct(TENANT_ID, 12L, 20);
        verify(shardMapper).insertBatch(anyList());

        // 关键断言：受理成功不等于可以开工。此刻一条消息都不该离开进程
        verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class),
                any(MessagePostProcessor.class));

        commit();

        ArgumentCaptor<Object> payloads = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate, times(2)).convertAndSend(eq(RabbitTopology.EXCHANGE_NORMAL),
                eq(RabbitTopology.QUEUE_APPLY), payloads.capture(), any(MessagePostProcessor.class));
        List<Long> shardIds = payloads.getAllValues().stream().map(m -> ((StageMessage) m).shardId()).toList();
        assertEquals(List.of(901L, 902L), shardIds);
        // 消息体只带 ID：单号做跟踪号，PPID 与数量留在库里
        assertTrue(payloads.getAllValues().stream().allMatch(m -> ((StageMessage) m).traceId() != null));
    }

    @Test
    void deductConflictLeavesNoMessageToCommit() {
        when(ppidService.planAllocation("Model-A", "SPU-1", 120))
                .thenReturn(List.of(plan(11L, "PPID-BIG", 100), plan(12L, "PPID-SMALL", 20)));
        when(ppidService.deduct(TENANT_ID, 12L, 20)).thenReturn(false);

        BizException e = assertThrows(BizException.class, () -> service.submit(request(120)));
        assertTrue(e.getMessage().contains("刷新"), e.getMessage());

        // 抢不到额度就没登记过任何提交后回调，后面再怎么 commit 也不会发出消息
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
        verify(shardMapper, never()).insertBatch(anyList());
        verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class),
                any(MessagePostProcessor.class));
    }

    @Test
    void insufficientPoolRejectsBeforeAnyDeduct() {
        when(ppidService.planAllocation("Model-A", "SPU-1", 136))
                .thenReturn(List.of(plan(11L, "PPID-BIG", 100), plan(12L, "PPID-SMALL", 30)));

        BizException e = assertThrows(BizException.class, () -> service.submit(request(136)));
        assertTrue(e.getMessage().contains("还差 6"), e.getMessage());
        verify(ppidService, never()).deduct(anyLong(), anyLong(), anyInt());
        verify(applicationMapper, never()).insert(any(ApplicationPO.class));
    }

    @Test
    void tenantMaxQuantityGateRunsBeforeTouchingThePool() {
        when(configService.intValue(eq(TENANT_ID), any())).thenReturn(100);

        BizException e = assertThrows(BizException.class, () -> service.submit(request(101)));
        assertTrue(e.getMessage().contains("100"), e.getMessage());
        verify(ppidService, never()).planAllocation(any(), any(), anyInt());
        verify(ppidService, never()).deduct(anyLong(), anyLong(), anyInt());
    }

    @Test
    void expiredCredentialFailsFastWithoutConsumingQuota() {
        doThrow(new BizException(ErrorCode.CREDENTIAL_EXPIRED, "租户 MFI 客户端证书已过期，请先更换证书再申请"))
                .when(credentialService).requireUsable(TENANT_ID);

        assertThrows(BizException.class, () -> service.submit(request(10)));
        verify(ppidService, never()).deduct(anyLong(), anyLong(), anyInt());
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
        verify(ppidService, never()).deduct(anyLong(), anyLong(), anyInt());
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
        verify(ppidService, never()).deduct(anyLong(), anyLong(), anyInt());
    }

    @Test
    void producerRefusesToRegisterOutsideATransaction() {
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);

        assertThrows(IllegalStateException.class, () -> producer.publishAfterCommit(
                List.of(new StageMessage(TENANT_ID, 1L, 2L, "APPLY", "trace"))));
        verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class),
                any(MessagePostProcessor.class));
    }

    @Test
    void applicantNameAndTechnologyAreSnapshotted() {
        when(ppidService.planAllocation("Model-A", "SPU-1", 10)).thenReturn(List.of(plan(11L, "PPID-BIG", 10)));

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

    @Test
    void replayResetsDeadShardsAndRequeuesTheirCurrentStage() {
        when(applicationMapper.selectOne(any())).thenReturn(application("FAILED"));
        when(shardMapper.selectDeadByApplication(TENANT_ID, APPLICATION_ID))
                .thenReturn(List.of(deadShard(901L, "APPLY", "苹果 500 服务异常"),
                        deadShard(902L, "FILE_NAME", "下载超时")));
        when(shardMapper.resetForRetry(anyLong(), anyLong())).thenReturn(1);

        ApplicationRetryVo vo = service.retry("A261010-ABCDEFGH");

        assertEquals(2, vo.getReplayedCount());
        assertEquals(List.of(901L, 902L), vo.getShards().stream().map(ApplicationShardVo::getShardId).toList());
        // 返回复位后的状态：DEAD 已回到 PENDING，重试计数与旧死因一起归零/清空
        assertEquals(List.of("PENDING", "PENDING"),
                vo.getShards().stream().map(ApplicationShardVo::getStageStatus).toList());
        assertEquals(List.of(0, 0), vo.getShards().stream().map(ApplicationShardVo::getRetryCount).toList());
        assertTrue(vo.getShards().stream().allMatch(s -> s.getLastError() == null));

        verify(shardMapper).resetForRetry(TENANT_ID, 901L);
        verify(shardMapper).resetForRetry(TENANT_ID, 902L);
        verify(applicationMapper).reopen(TENANT_ID, APPLICATION_ID);

        // 投回各自停住的阶段队列，而不是从 APPLY 重跑一遍
        ArgumentCaptor<String> queues = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payloads = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate, times(2)).convertAndSend(eq(RabbitTopology.EXCHANGE_NORMAL),
                queues.capture(), payloads.capture(), any(MessagePostProcessor.class));
        assertEquals(List.of(RabbitTopology.QUEUE_APPLY, RabbitTopology.QUEUE_NAME), queues.getAllValues());
        assertEquals(List.of("APPLY", "FILE_NAME"),
                payloads.getAllValues().stream().map(m -> ((StageMessage) m).stage()).toList());
        assertEquals(List.of(901L, 902L),
                payloads.getAllValues().stream().map(m -> ((StageMessage) m).shardId()).toList());
        assertTrue(payloads.getAllValues().stream()
                .allMatch(m -> ((StageMessage) m).traceId().endsWith("#retry")));

        // 死因在复位前抄进留痕，复位之后库里就没了——排障要能看到"为什么重放"
        ArgumentCaptor<AuditFact> logs = ArgumentCaptor.forClass(AuditFact.class);
        verify(auditTrail, times(2)).append(logs.capture());
        AuditFact entry = logs.getAllValues().get(0);
        assertEquals(OperationAction.RETRY, entry.action());
        assertEquals("张三", entry.operatorName());
        assertEquals(901L, entry.shardId().longValue());
        assertTrue(entry.detail().contains("苹果 500 服务异常"), entry.detail());
    }

    @Test
    void replayPublishesOnlyAfterTheResetHasLanded() {
        when(applicationMapper.selectOne(any())).thenReturn(application("PARTIAL_FAILED"));
        when(shardMapper.selectDeadByApplication(TENANT_ID, APPLICATION_ID))
                .thenReturn(List.of(deadShard(901L, "REGISTER", "注册被拒")));
        when(shardMapper.resetForRetry(anyLong(), anyLong())).thenReturn(1);

        service.retry("A261010-ABCDEFGH");

        InOrder order = inOrder(shardMapper, applicationMapper, rabbitTemplate);
        order.verify(shardMapper).resetForRetry(TENANT_ID, 901L);
        order.verify(applicationMapper).reopen(TENANT_ID, APPLICATION_ID);
        order.verify(rabbitTemplate).convertAndSend(eq(RabbitTopology.EXCHANGE_NORMAL),
                eq(RabbitTopology.QUEUE_REGISTER), any(Object.class), any(MessagePostProcessor.class));
    }

    @Test
    void replaySkipsShardsSomebodyElseAlreadyMoved() {
        when(applicationMapper.selectOne(any())).thenReturn(application("FAILED"));
        when(shardMapper.selectDeadByApplication(TENANT_ID, APPLICATION_ID))
                .thenReturn(List.of(deadShard(901L, "APPLY", "旧失败"), deadShard(902L, "FINISH", "旧失败")));
        // 901 在两次操作之间已被别人复位，条件更新返回 0
        when(shardMapper.resetForRetry(TENANT_ID, 901L)).thenReturn(0);
        when(shardMapper.resetForRetry(TENANT_ID, 902L)).thenReturn(1);

        ApplicationRetryVo vo = service.retry("A261010-ABCDEFGH");

        assertEquals(1, vo.getReplayedCount());
        assertEquals(902L, vo.getShards().get(0).getShardId());
        ArgumentCaptor<Object> payloads = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate, times(1)).convertAndSend(eq(RabbitTopology.EXCHANGE_NORMAL),
                eq(RabbitTopology.QUEUE_FINISH), payloads.capture(), any(MessagePostProcessor.class));
        assertEquals(902L, ((StageMessage) payloads.getValue()).shardId());
        verify(auditTrail, times(1)).append(any(AuditFact.class));
    }

    @Test
    void replayWithoutDeadShardsIsANoOpInsteadOfAnError() {
        when(applicationMapper.selectOne(any())).thenReturn(application("SUCCESS"));
        when(shardMapper.selectDeadByApplication(TENANT_ID, APPLICATION_ID)).thenReturn(List.of());

        ApplicationRetryVo vo = service.retry("A261010-ABCDEFGH");

        assertEquals(0, vo.getReplayedCount());
        assertTrue(vo.getShards().isEmpty());
        // 连事务都不该开：第二次点击、或分片已被兜底扫描捞回，都不该产生任何写入与消息
        verify(transactionTemplate, never()).execute(any());
        verify(shardMapper, never()).resetForRetry(anyLong(), anyLong());
        verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class),
                any(MessagePostProcessor.class));
    }

    @Test
    void replayRefusesApplicationsThatAreNotInTheTenant() {
        when(applicationMapper.selectOne(any())).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> service.retry("A261010-NOPE"));
        assertTrue(e.getMessage().contains("不属于当前租户"), e.getMessage());
        verify(shardMapper, never()).selectDeadByApplication(anyLong(), anyLong());
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

    /** 终态失败的分片：重放只认这一种，且必须带得上一次停住的阶段与死因 */
    private static ApplicationPpidPO deadShard(long id, String stage, String lastError) {
        ApplicationPpidPO row = new ApplicationPpidPO();
        row.setId(id);
        row.setPpid("PPID-" + id);
        row.setAllocateCount(30);
        row.setStage(stage);
        row.setStageStatus("DEAD");
        row.setRetryCount(3);
        row.setLastError(lastError);
        return row;
    }

    private static ApplicationPO application(String status) {
        ApplicationPO row = new ApplicationPO();
        row.setTenantId(TENANT_ID);
        row.setId(APPLICATION_ID);
        row.setApplicationNo("A261010-ABCDEFGH");
        row.setStatus(status);
        row.setApplyQuantity(60);
        row.setPpidCount(2);
        return row;
    }

    private static PpIdAllocation plan(long ppidId, String ppid, int quantity) {
        return new PpIdAllocation(ppidId, ppid, quantity, quantity);
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
