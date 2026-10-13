package com.galaplat.mfi.issuance.application;

import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPO;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPpidPO;
import com.galaplat.mfi.issuance.interfaces.ApplicationShardVo;
import com.galaplat.mfi.issuance.interfaces.ApplicationVo;

/**
 * 申请单聚合的 PO 到 VO 映射。
 *
 * <p>写侧（受理、分页列表）与读侧（详情、结果页）共用这一份：VO 字段一旦在两侧各写一遍，
 * 加一列就会只加在一边，前端拿到的详情比列表少字段这类问题就是这么来的。
 */
public final class ApplicationVoConverter {

    private ApplicationVoConverter() {
    }

    public static ApplicationVo toVo(ApplicationPO row) {
        ApplicationVo vo = new ApplicationVo();
        vo.setApplicationNo(row.getApplicationNo());
        vo.setProductModel(row.getProductModel());
        vo.setSpu(row.getSpu());
        vo.setAppleTechnology(row.getAppleTechnology());
        vo.setApplyQuantity(row.getApplyQuantity());
        vo.setPpidCount(row.getPpidCount());
        vo.setTokenCount(row.getTokenCount());
        vo.setStatus(row.getStatus());
        vo.setFailReason(row.getFailReason());
        vo.setApplicantName(row.getApplicantName());
        vo.setCreateTime(row.getCreateTime());
        vo.setFinishTime(row.getFinishTime());
        return vo;
    }

    public static ApplicationShardVo toShardVo(ApplicationPpidPO row) {
        ApplicationShardVo vo = new ApplicationShardVo();
        vo.setShardId(row.getId());
        vo.setPpid(row.getPpid());
        vo.setAllocateCount(row.getAllocateCount());
        vo.setStage(row.getStage());
        vo.setStageStatus(row.getStageStatus());
        vo.setRetryCount(row.getRetryCount());
        vo.setLastError(row.getLastError());
        vo.setNextExecuteTime(row.getNextExecuteTime());
        return vo;
    }

}
