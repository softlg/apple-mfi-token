package com.galaplat.mfi.identity.interfaces;

import lombok.Data;

import java.time.LocalDateTime;
import com.galaplat.mfi.identity.domain.OperationAction;

/**
 * 进度时间线上的一条留痕。
 *
 * <p>{@code detail} 直接来自 {@code t_operation_log}，那张表按约定不写凭证与 PPID 全量清单，
 * 所以可以给前端；租户归属由请求上下文保证，跨租户的单根本查不出来。
 */
@Data
public class OperationLogVo {

    /** OperationAction 枚举名 */
    private String action;

    private Long shardId;

    /** 分片对应的 PPID；非分片级动作（如申请受理）为空 */
    private String ppid;

    /** 系统动作没有操作人，这里显示来源标记 */
    private String operatorName;

    private Integer quantity;

    private String detail;

    private LocalDateTime createTime;

}
