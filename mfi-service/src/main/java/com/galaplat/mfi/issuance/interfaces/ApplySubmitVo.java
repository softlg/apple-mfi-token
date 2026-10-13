package com.galaplat.mfi.issuance.interfaces;

import lombok.Data;

import java.util.List;

/**
 * 申请受理结果：同步段做完的事就是"扣到额度、落成单、排好分片"，异步推进的进度不在这里承诺。
 */
@Data
public class ApplySubmitVo {

    private String applicationNo;

    private String status;

    private Integer applyQuantity;

    private Integer ppidCount;

    /** 实际扣减出来的分片。与预览 plan 的区别：这里的每一行都已经在库里扣到了额度 */
    private List<ApplicationShardVo> shards;

    /** true 表示这次命中的是同 requestId 的重复提交，返回的是已存在的那张单 */
    private Boolean duplicated;

}
