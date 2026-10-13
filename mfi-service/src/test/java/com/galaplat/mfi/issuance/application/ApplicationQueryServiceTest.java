package com.galaplat.mfi.issuance.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.galaplat.mfi.shared.api.BizException;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPO;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPpidPO;
import com.galaplat.mfi.identity.application.AuditTrail;
import com.galaplat.mfi.identity.domain.AuditFact;
import com.galaplat.mfi.identity.domain.OperationAction;
import com.galaplat.mfi.issuance.infrastructure.persistence.TokenFilePO;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationMapper;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPpidMapper;
import com.galaplat.mfi.issuance.infrastructure.persistence.TokenFileMapper;
import com.galaplat.mfi.issuance.infrastructure.persistence.TokenMapper;
import com.galaplat.mfi.shared.tenant.TenantContext;
import com.galaplat.mfi.shared.tenant.TenantPrincipal;
import com.galaplat.mfi.issuance.interfaces.ApplicationDetailVo;
import com.galaplat.mfi.issuance.interfaces.OperationLogVo;
import com.galaplat.mfi.issuance.interfaces.StageProgressVo;
import com.galaplat.mfi.issuance.interfaces.TokenFileVo;
import com.galaplat.mfi.issuance.interfaces.TokenCountVo;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 结果读侧的两条硬约定：阶段进度按"分片走到哪"计数，时间线只说事实不含凭证。
 *
 * <p>进度计数是前端唯一的进度来源，口径错了整个页面就错；时间线的 ppid 补齐靠内存里的分片列表，
 * 拼错就会把别人的 PPID 显示在这一行上。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApplicationQueryServiceTest {

    private static final long TENANT_ID = 7L;
    private static final long APPLICATION_ID = 555L;
    private static final String APPLICATION_NO = "A261010-ABCDEFGH";

    @Mock
    private ApplicationMapper applicationMapper;
    @Mock
    private ApplicationPpidMapper shardMapper;
    @Mock
    private AuditTrail auditTrail;
    @Mock
    private TokenFileMapper fileMapper;
    @Mock
    private TokenMapper tokenMapper;

    private ApplicationQueryService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApplicationPO.class);
        TenantContext.bind(new TenantPrincipal(TENANT_ID, 42L, true));
        service = new ApplicationQueryService(applicationMapper, shardMapper, auditTrail, fileMapper,
                tokenMapper);
        when(applicationMapper.selectOne(any())).thenReturn(application());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void stageProgressCountsWhereEachShardHasReached() {
        // 一片走完五阶段，一片还停在 FILE_NAME 等苹果生成文件
        when(shardMapper.selectByApplication(TENANT_ID, APPLICATION_ID)).thenReturn(List.of(
                shard(901L, "PPID-A", 100, "FINISH", "SUCCESS", null),
                shard(902L, "PPID-B", 20, "FILE_NAME", "PENDING", LocalDateTime.now().plusMinutes(5))));

        List<StageProgressVo> stages = service.detail(APPLICATION_NO).getStages();

        assertEquals(5, stages.size());
        assertEquals(List.of("APPLY", "FILE_NAME", "FILE_CONTENT", "REGISTER", "FINISH"),
                stages.stream().map(StageProgressVo::getStage).toList());
        // APPLY 这一跳两片都过去了：到达 2、完成 2，一个都不留
        assertEquals(2, stage(stages, "APPLY").getReached());
        assertEquals(2, stage(stages, "APPLY").getSucceeded());
        assertEquals(0, stage(stages, "APPLY").getWaiting());
        // FILE_NAME：一片已过、一片正在等延迟投递，不能把已过的算成"还在等"
        assertEquals(2, stage(stages, "FILE_NAME").getReached());
        assertEquals(1, stage(stages, "FILE_NAME").getSucceeded());
        assertEquals(1, stage(stages, "FILE_NAME").getWaiting());
        // 后面的几跳只有走完的那片到达过
        assertEquals(1, stage(stages, "FINISH").getReached());
        assertEquals(1, stage(stages, "FINISH").getSucceeded());
        assertEquals(1, stage(stages, "REGISTER").getReached());
        assertEquals(1, stage(stages, "REGISTER").getSucceeded());
        // 停在 FILE_NAME 的那片还没走到注册这一跳，什么都不算
        assertEquals(0, stage(stages, "REGISTER").getWaiting());
    }

    @Test
    void deadShardShowsAsStuckOnItsOwnStage() {
        when(shardMapper.selectByApplication(TENANT_ID, APPLICATION_ID)).thenReturn(List.of(
                shard(901L, "PPID-A", 100, "REGISTER", "DEAD", null)));

        List<StageProgressVo> stages = service.detail(APPLICATION_NO).getStages();

        assertEquals(1, stage(stages, "REGISTER").getDead());
        // 前面的跳已经过去了，卡点只记在当前这一跳上
        assertEquals(1, stage(stages, "APPLY").getSucceeded());
        assertEquals(0, stage(stages, "FILE_CONTENT").getDead());
    }

    @Test
    void timelineIsAscendingAndCarriesTheShardPpid() {
        when(shardMapper.selectByApplication(TENANT_ID, APPLICATION_ID)).thenReturn(List.of(
                shard(901L, "PPID-A", 100, "FILE_NAME", "PENDING", null)));
        // 留痕按 id 倒序取回（截断取的是最近若干条），读侧要翻成正序
        when(auditTrail.recentTimeline(eq(TENANT_ID), eq(APPLICATION_ID), eq(500)))
                .thenReturn(List.of(log(OperationAction.STAGE_ADVANCE, 901L, null,
                                "阶段 FILE_NAME 完成，进入 FILE_CONTENT"),
                        log(OperationAction.RETRY, 901L, null, "阶段 APPLY 第 1 次重试：苹果 503"),
                        log(OperationAction.APPLY, null, "张三", "产品型号 M / SPU S，拆为 1 份额度")));

        List<OperationLogVo> timeline = service.detail(APPLICATION_NO).getTimeline();

        assertEquals(List.of("APPLY", "RETRY", "STAGE_ADVANCE"),
                timeline.stream().map(OperationLogVo::getAction).toList());
        assertEquals("张三", timeline.get(0).getOperatorName());
        // 系统动作没有操作人，留痕里 operator_name 为空
        assertEquals("系统", timeline.get(1).getOperatorName());
        assertEquals("PPID-A", timeline.get(1).getPpid());
        // 申请受理是分片之上的动作，不带 PPID
        assertNull(timeline.get(0).getPpid());
    }

    @Test
    void tokenSummaryReadsTheSnakeCaseProjectionKeys() {
        Map<String, Object> row = new HashMap<>();
        row.put("status", "REGISTERED");
        row.put("register_status", "SUCCESS");
        row.put("token_rows", 18L);
        when(tokenMapper.selectStatusSummary(TENANT_ID, APPLICATION_ID)).thenReturn(List.of(row));

        List<TokenCountVo> summary = service.detail(APPLICATION_NO).getTokenSummary();

        assertEquals(1, summary.size());
        assertEquals("REGISTERED", summary.get(0).getStatus());
        assertEquals(18, summary.get(0).getCount());
    }

    @Test
    void filesCarryFileNameCountAndFailureHistory() {
        TokenFilePO file = new TokenFilePO();
        file.setId(77L);
        file.setShardId(901L);
        file.setPpid("PPID-A");
        file.setFileName("0001.xlsx");
        file.setTokenCount(2);
        file.setDownloadStatus("SUCCESS");
        file.setRetryCount(1);
        file.setDownloadTime(LocalDateTime.now());
        when(fileMapper.selectByApplication(TENANT_ID, APPLICATION_ID)).thenReturn(List.of(file));

        List<TokenFileVo> files = service.files(APPLICATION_NO);

        assertEquals(1, files.size());
        assertEquals("0001.xlsx", files.get(0).getFileName());
        assertEquals(2, files.get(0).getTokenCount());
        assertEquals("SUCCESS", files.get(0).getDownloadStatus());
        assertEquals(1, files.get(0).getRetryCount());
    }

    @Test
    void foreignOrMissingNumberIsRejected() {
        when(applicationMapper.selectOne(any())).thenReturn(null);

        BizException failure = assertThrows(BizException.class, () -> service.detail(APPLICATION_NO));

        // 别的租户的单与不存在的单给同一句话，不给出"存在但不给你看"这种可枚举的信号
        assertEquals("申请单不存在，或不属于当前租户", failure.getMessage());
    }

    @Test
    void blankNumberNeverReachesDatabase() {
        assertThrows(BizException.class, () -> service.files("   "));

        // 空单号直接拒，否则它会变成一次"本租户随便一张单"的解释
        verify(applicationMapper, never()).selectOne(any());
    }

    private static StageProgressVo stage(List<StageProgressVo> stages, String name) {
        return stages.stream().filter(row -> name.equals(row.getStage())).findFirst().orElseThrow();
    }

    private static ApplicationPO application() {
        ApplicationPO application = new ApplicationPO();
        application.setId(APPLICATION_ID);
        application.setTenantId(TENANT_ID);
        application.setApplicationNo(APPLICATION_NO);
        application.setProductModel("Model-A");
        application.setSpu("SPU-1");
        application.setApplyQuantity(120);
        application.setPpidCount(2);
        application.setTokenCount(118);
        application.setStatus("RUNNING");
        application.setApplicantName("张三");
        return application;
    }

    private static ApplicationPpidPO shard(long id, String ppid, int allocateCount, String stage,
                                           String stageStatus, LocalDateTime nextExecuteTime) {
        ApplicationPpidPO shard = new ApplicationPpidPO();
        shard.setId(id);
        shard.setTenantId(TENANT_ID);
        shard.setApplicationId(APPLICATION_ID);
        shard.setPpid(ppid);
        shard.setAllocateCount(allocateCount);
        shard.setStage(stage);
        shard.setStageStatus(stageStatus);
        shard.setRetryCount(0);
        shard.setNextExecuteTime(nextExecuteTime);
        return shard;
    }

    private static AuditFact log(OperationAction action, Long shardId, String operator, String detail) {
        return new AuditFact(TENANT_ID, APPLICATION_ID, shardId, action, null, operator, 1, detail,
                LocalDateTime.now());
    }

}
