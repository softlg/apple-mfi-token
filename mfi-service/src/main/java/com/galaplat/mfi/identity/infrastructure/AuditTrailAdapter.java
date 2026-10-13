package com.galaplat.mfi.identity.infrastructure;

import com.galaplat.mfi.identity.application.AuditTrail;
import com.galaplat.mfi.identity.domain.AuditFact;
import com.galaplat.mfi.identity.domain.OperationAction;
import com.galaplat.mfi.identity.infrastructure.persistence.OperationLogMapper;
import com.galaplat.mfi.identity.infrastructure.persistence.OperationLogPO;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 留痕落 {@code t_operation_log}。这里做的是事实记录与持久化行之间的映射，
 * 让写留痕的业务代码不必知道这张表的存在。
 */
@Component
public class AuditTrailAdapter implements AuditTrail {

    private final OperationLogMapper operationLogMapper;

    public AuditTrailAdapter(OperationLogMapper operationLogMapper) {
        this.operationLogMapper = operationLogMapper;
    }

    @Override
    public void append(AuditFact fact) {
        operationLogMapper.insert(toRow(fact));
    }

    @Override
    public List<AuditFact> recentTimeline(long tenantId, long applicationId, int limit) {
        return operationLogMapper.selectRecentTimeline(tenantId, applicationId, limit).stream()
                // 查询按租户条件过滤，所以行里的 tenantId 用入参回填；时间线只渲染动作与摘要，租户归属来自请求上下文
                .map(row -> toFact(tenantId, row))
                .toList();
    }

    private static OperationLogPO toRow(AuditFact fact) {
        OperationLogPO entry = new OperationLogPO();
        entry.setTenantId(fact.tenantId());
        entry.setApplicationId(fact.applicationId());
        entry.setShardId(fact.shardId());
        entry.setAction(fact.action().name());
        entry.setOperatorUserId(fact.operatorUserId());
        entry.setOperatorName(fact.operatorName());
        entry.setQuantity(fact.quantity());
        entry.setDetail(fact.detail());
        return entry;
    }

    private static AuditFact toFact(long tenantId, OperationLogPO row) {
        return new AuditFact(tenantId, row.getApplicationId(), row.getShardId(),
                com.galaplat.mfi.identity.domain.OperationAction.valueOf(row.getAction()),
                row.getOperatorUserId(), row.getOperatorName(), row.getQuantity(), row.getDetail(),
                row.getCreateTime());
    }

}
