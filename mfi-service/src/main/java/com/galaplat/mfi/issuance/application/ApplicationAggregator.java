package com.galaplat.mfi.issuance.application;

import com.galaplat.mfi.issuance.domain.ApplicationStatus;
import com.galaplat.mfi.issuance.domain.Stage;
import com.galaplat.mfi.issuance.domain.StageStatus;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationMapper;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPpidMapper;
import com.galaplat.mfi.issuance.infrastructure.persistence.TokenMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 分片状态 → 申请单状态的聚合。
 *
 * <p>MES 的申请单状态由监听器各自"顺手改"，多分片并发时后写的一方覆盖前一方，出现过明明有分片
 * 失败但单据显示成功的读侧假象。这里把聚合收敛成一个方法，并且写成一条带条件的 UPDATE：
 * 状态没变化时不写库，终态也不会被迟到的重复聚合改回 RUNNING。
 */
@Slf4j
@Component
public class ApplicationAggregator {

    private final ApplicationPpidMapper shardMapper;
    private final ApplicationMapper applicationMapper;
    private final TokenMapper tokenMapper;

    public ApplicationAggregator(ApplicationPpidMapper shardMapper, ApplicationMapper applicationMapper,
                                 TokenMapper tokenMapper) {
        this.shardMapper = shardMapper;
        this.applicationMapper = applicationMapper;
        this.tokenMapper = tokenMapper;
    }

    /**
     * 按分片当前阶段汇总。必须在租户上下文里调用（分片计数与 token 计数都是租户表）。
     */
    public ApplicationStatus aggregate(long tenantId, long applicationId) {
        List<Map<String, Object>> summary = shardMapper.selectStageSummary(tenantId, applicationId);
        long finished = 0;
        long dead = 0;
        long inFlight = 0;
        for (Map<String, Object> row : summary) {
            String stage = String.valueOf(row.get("stage"));
            String status = String.valueOf(row.get("stage_status"));
            long count = ((Number) row.get("shard_rows")).longValue();
            if (StageStatus.DEAD.name().equals(status)) {
                dead += count;
            } else if (Stage.FINISH.name().equals(stage) && StageStatus.SUCCESS.name().equals(status)) {
                finished += count;
            } else {
                inFlight += count;
            }
        }

        ApplicationStatus status;
        if (inFlight > 0) {
            status = ApplicationStatus.RUNNING;
        } else if (dead == 0) {
            status = ApplicationStatus.SUCCESS;
        } else if (finished == 0) {
            status = ApplicationStatus.FAILED;
        } else {
            status = ApplicationStatus.PARTIAL_FAILED;
        }

        boolean terminal = status.isTerminal();
        String failReason = terminal && dead > 0 ? dead + " 个分片终态失败，详情见分片 last_error 与死信队列" : null;
        int tokenCount = tokenMapper.countByApplication(tenantId, applicationId);

        applicationMapper.aggregateProgress(tenantId, applicationId, status.name(), tokenCount,
                terminal ? LocalDateTime.now() : null, failReason);
        log.info("申请单状态已聚合: tenantId={}, applicationId={}, status={}, tokens={}, finished={}, dead={}, inFlight={}",
                tenantId, applicationId, status, tokenCount, finished, dead, inFlight);
        return status;
    }

}
