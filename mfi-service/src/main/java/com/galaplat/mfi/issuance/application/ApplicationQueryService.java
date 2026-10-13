package com.galaplat.mfi.issuance.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.galaplat.mfi.shared.api.ErrorCode;
import com.galaplat.mfi.issuance.domain.Stage;
import com.galaplat.mfi.issuance.domain.StageStatus;
import com.galaplat.mfi.shared.api.BizException;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPO;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPpidPO;
import com.galaplat.mfi.identity.infrastructure.persistence.OperationLogPO;
import com.galaplat.mfi.issuance.infrastructure.persistence.TokenFilePO;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationMapper;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPpidMapper;
import com.galaplat.mfi.identity.infrastructure.persistence.OperationLogMapper;
import com.galaplat.mfi.issuance.infrastructure.persistence.TokenFileMapper;
import com.galaplat.mfi.issuance.infrastructure.persistence.TokenMapper;
import com.galaplat.mfi.issuance.interfaces.ApplicationDetailVo;
import com.galaplat.mfi.identity.interfaces.OperationLogVo;
import com.galaplat.mfi.issuance.interfaces.StageProgressVo;
import com.galaplat.mfi.issuance.interfaces.TokenCountVo;
import com.galaplat.mfi.issuance.interfaces.TokenFileVo;
import com.galaplat.mfi.shared.tenant.TenantAccess;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 申请单的结果读侧：详情（含五阶段进度与时间线）与文件明细。
 *
 * <p>与写侧 {@link ApplicationService} 分开放，是因为这里的依赖全是只读查询，不碰额度、不发消息；
 * 两边共用的只有 VO 映射，见 {@link ApplicationVoConverter}。
 */
@Slf4j
@Service
public class ApplicationQueryService {

    /**
     * 时间线一次最多读多少条留痕。一张大单的分片可以上百个，五阶段加每次重试都往这张表写，
     * 进度页要的是"最近发生了什么"，不是整本流水账。
     */
    private static final int TIMELINE_LIMIT = 500;

    private final ApplicationMapper applicationMapper;
    private final ApplicationPpidMapper shardMapper;
    private final OperationLogMapper operationLogMapper;
    private final TokenFileMapper fileMapper;
    private final TokenMapper tokenMapper;

    public ApplicationQueryService(ApplicationMapper applicationMapper, ApplicationPpidMapper shardMapper,
                                   OperationLogMapper operationLogMapper, TokenFileMapper fileMapper,
                                   TokenMapper tokenMapper) {
        this.applicationMapper = applicationMapper;
        this.shardMapper = shardMapper;
        this.operationLogMapper = operationLogMapper;
        this.fileMapper = fileMapper;
        this.tokenMapper = tokenMapper;
    }

    /**
     * 申请单详情。分片、阶段进度、token 计数与时间线一次给全，顺序都以库里的 id 为准。
     */
    public ApplicationDetailVo detail(String applicationNo) {
        long tenantId = TenantAccess.requireTenantId();
        ApplicationPO application = requireApplication(tenantId, applicationNo);
        List<ApplicationPpidPO> shards = shardMapper.selectByApplication(tenantId, application.getId());

        ApplicationDetailVo vo = new ApplicationDetailVo();
        vo.setApplication(ApplicationVoConverter.toVo(application));
        vo.setShards(shards.stream().map(ApplicationVoConverter::toShardVo).toList());
        vo.setStages(stageProgress(shards));
        vo.setTokenSummary(tokenSummary(tenantId, application.getId()));
        vo.setTimeline(timeline(tenantId, application.getId(), shards));
        return vo;
    }

    /**
     * 一张单的文件明细：哪份 xlsx、多少个 token、什么时候下的、失败过几次。
     */
    public List<TokenFileVo> files(String applicationNo) {
        long tenantId = TenantAccess.requireTenantId();
        ApplicationPO application = requireApplication(tenantId, applicationNo);
        return fileMapper.selectByApplication(tenantId, application.getId()).stream()
                .map(ApplicationQueryService::toFileVo).toList();
    }

    /**
     * 五个阶段各算一行。口径是"分片走到哪"：
     * 已经走过去下一阶段的分片，在上一跳记 succeeded 而不再记 waiting——否则进度条上会出现
     * "APPLY 还有 3 个在等"而实际上这 3 个已经在下载文件了。
     */
    private static List<StageProgressVo> stageProgress(List<ApplicationPpidPO> shards) {
        List<StageProgressVo> rows = new ArrayList<>(Stage.values().length);
        for (Stage stage : Stage.values()) {
            int reached = 0;
            int succeeded = 0;
            int waiting = 0;
            int processing = 0;
            int dead = 0;
            for (ApplicationPpidPO shard : shards) {
                int compared = currentStage(shard).compareTo(stage);
                if (compared < 0) {
                    // 还没走到这一跳
                    continue;
                }
                reached++;
                if (compared > 0) {
                    succeeded++;
                    continue;
                }
                StageStatus status = currentStatus(shard);
                switch (status) {
                    case PENDING -> waiting++;
                    case PROCESSING -> processing++;
                    case SUCCESS -> succeeded++;
                    // DEAD 是等人工重放，FAILED 是本流水线不会写到的历史值，两者都按"卡住"展示
                    case DEAD, FAILED -> dead++;
                }
            }
            StageProgressVo row = new StageProgressVo();
            row.setStage(stage.name());
            row.setReached(reached);
            row.setSucceeded(succeeded);
            row.setWaiting(waiting);
            row.setProcessing(processing);
            row.setDead(dead);
            rows.add(row);
        }
        return rows;
    }

    private List<TokenCountVo> tokenSummary(long tenantId, long applicationId) {
        List<TokenCountVo> result = new ArrayList<>();
        for (Map<String, Object> row : tokenMapper.selectStatusSummary(tenantId, applicationId)) {
            TokenCountVo vo = new TokenCountVo();
            vo.setStatus(asString(row.get("status")));
            vo.setRegisterStatus(asString(row.get("register_status")));
            vo.setCount(asInt(row.get("token_rows")));
            result.add(vo);
        }
        return result;
    }

    /**
     * 时间线按时间正序返回，但截断取的是最近 {@link #TIMELINE_LIMIT} 条，所以先倒序查再翻转。
     *
     * <p>shardId 到 PPID 的对应关系用已在手的分片列表在内存里补，不为此 join 一次表：
     * 一个申请单的分片数是个位到几十的量级。
     */
    private List<OperationLogVo> timeline(long tenantId, long applicationId, List<ApplicationPpidPO> shards) {
        Map<Long, String> ppidByShard = new HashMap<>();
        for (ApplicationPpidPO shard : shards) {
            ppidByShard.put(shard.getId(), shard.getPpid());
        }
        List<OperationLogPO> rows = new ArrayList<>(
                operationLogMapper.selectRecentTimeline(tenantId, applicationId, TIMELINE_LIMIT));
        Collections.reverse(rows);
        List<OperationLogVo> timeline = new ArrayList<>(rows.size());
        for (OperationLogPO row : rows) {
            OperationLogVo vo = new OperationLogVo();
            vo.setAction(row.getAction());
            vo.setShardId(row.getShardId());
            vo.setPpid(row.getShardId() == null ? null : ppidByShard.get(row.getShardId()));
            // 系统动作没有操作人，留痕里的 operator_name 为空，展示成"系统"比空白好读
            vo.setOperatorName(row.getOperatorName() == null ? "系统" : row.getOperatorName());
            vo.setQuantity(row.getQuantity());
            vo.setDetail(row.getDetail());
            vo.setCreateTime(row.getCreateTime());
            timeline.add(vo);
        }
        return timeline;
    }

    /**
     * 按单号定位申请单。租户条件显式带上，与库里的改写条件同源，跨租户的单查不出来。
     */
    private ApplicationPO requireApplication(long tenantId, String applicationNo) {
        String value = applicationNo == null ? null : applicationNo.trim();
        if (value == null || value.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_PARAM, "申请单号不能为空");
        }
        ApplicationPO application = applicationMapper.selectOne(Wrappers.<ApplicationPO>lambdaQuery()
                .eq(ApplicationPO::getTenantId, tenantId)
                .eq(ApplicationPO::getApplicationNo, value));
        if (application == null) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "申请单不存在，或不属于当前租户");
        }
        return application;
    }

    private static TokenFileVo toFileVo(TokenFilePO row) {
        TokenFileVo vo = new TokenFileVo();
        vo.setFileId(row.getId());
        vo.setShardId(row.getShardId());
        vo.setPpid(row.getPpid());
        vo.setFileName(row.getFileName());
        vo.setTokenCount(row.getTokenCount());
        vo.setDownloadStatus(row.getDownloadStatus());
        vo.setRetryCount(row.getRetryCount());
        vo.setDownloadTime(row.getDownloadTime());
        vo.setLastError(row.getLastError());
        return vo;
    }

    /** 阶段与状态都由本服务的五个处理器写入，取值一定是枚举名；这里不防御非法值，出现即编码缺陷 */
    private static Stage currentStage(ApplicationPpidPO shard) {
        return Stage.valueOf(shard.getStage());
    }

    private static StageStatus currentStatus(ApplicationPpidPO shard) {
        return StageStatus.valueOf(shard.getStageStatus());
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer asInt(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

}
