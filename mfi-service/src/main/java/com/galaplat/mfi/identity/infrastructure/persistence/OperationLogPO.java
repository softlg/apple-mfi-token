package com.galaplat.mfi.identity.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import com.galaplat.mfi.identity.domain.OperationAction;

/**
 * 操作与阶段跳转留痕。
 *
 * <p>{@code detail} 里禁止写 auth_entity_id、secret_key、凭证内容或 PPID 全量清单——这张表会被导出与
 * 排障脚本整行读出来。需要明细时按 applicationId / shardId 回业务表查。
 */
@Data
@TableName("t_operation_log")
public class OperationLogPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    private Long applicationId;

    private Long shardId;

    /** OperationAction 枚举名 */
    private String action;

    /** 系统动作可空，此时 operator_name 记系统来源 */
    private Long operatorUserId;

    private String operatorName;

    private Integer quantity;

    private String detail;

    /** 时间戳由库默认值与 ON UPDATE 维护：写策略 NEVER，避免把读出的旧值回写、抑制库侧自动递增 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;

}
