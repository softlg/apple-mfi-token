package com.galaplat.mfi.issuance.interfaces;

import lombok.Data;
import com.galaplat.mfi.issuance.domain.Stage;

/**
 * 五个阶段中某一个的推进情况。
 *
 * <p>计数口径以"分片"为单位而不是 token 数：一个分片就是一次苹果调用链，进度页要回答的是
 * "这一跳还有几张单子在跑"，token 级的统计在结果页与导出里看。
 */
@Data
public class StageProgressVo {

    /** Stage 枚举名 */
    private String stage;

    /** 已经到达本阶段（含已经走过去）的分片数 */
    private Integer reached;

    /** 已经越过本阶段的分片数；FINISH 上表示"整条链路成功结束" */
    private Integer succeeded;

    /** 停在本阶段等待投递或延迟的分片数 */
    private Integer waiting;

    /** 本阶段正在执行的分片数 */
    private Integer processing;

    /** 本阶段终态失败、等人工重放的分片数 */
    private Integer dead;

}
