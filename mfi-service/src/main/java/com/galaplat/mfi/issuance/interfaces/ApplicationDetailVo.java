package com.galaplat.mfi.issuance.interfaces;

import lombok.Data;

import java.util.List;
import com.galaplat.mfi.identity.interfaces.OperationLogVo;
import com.galaplat.mfi.issuance.domain.Stage;

/**
 * 申请单详情：一张单的聚合位、五个阶段推进、分片明细、token 计数与进度时间线。
 *
 * <p>一次请求给全，是为了让进度页只打一个接口：五个阶段各自查一次的话，前端要按分片顺序拼，
 * 拼错的页面上会显示"已完成"其实还在等苹果。
 */
@Data
public class ApplicationDetailVo {

    private ApplicationVo application;

    /** 固定五行，按 Stage 声明顺序 */
    private List<StageProgressVo> stages;

    /** 按拆分时占用的额度行顺序 */
    private List<ApplicationShardVo> shards;

    private List<TokenCountVo> tokenSummary;

    /** 最近的时间线，按时间正序；超过上限时只给最近若干条 */
    private List<OperationLogVo> timeline;

}
