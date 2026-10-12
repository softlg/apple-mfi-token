package com.galaplat.mfi.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次 token 申请的申请单。
 *
 * <p>申请可能拆到多份额度上，所以分片信息在 {@link ApplicationPpidPO}，本表只存聚合位：
 * 状态与成功数由各分片汇总而来，不在申请事务里猜测。
 */
@Data
@TableName("t_application")
public class ApplicationPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    /** 对外业务标识，全库唯一；跨租户查单靠它，因此不能是可猜的自增号 */
    private String applicationNo;

    private Long applicantUserId;

    /** 发起人姓名快照：成员被移除后申请单仍要说清是谁提的 */
    private String applicantName;

    private String productModel;

    private String spu;

    /** 发起时的 technology 快照，租户后来改了配置也不影响已受理的单 */
    private String appleTechnology;

    private Integer applyQuantity;

    private Integer ppidCount;

    /** 已成功落库的 token 数，由 FINISH 阶段推进 */
    private Integer tokenCount;

    /** ApplicationStatus 枚举名 */
    private String status;

    private String failReason;

    /** 时间戳由库默认值与 ON UPDATE 维护：写策略 NEVER，避免把读出的旧值回写、抑制库侧自动递增 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;

    /** 时间戳由库默认值与 ON UPDATE 维护：写策略 NEVER，避免把读出的旧值回写、抑制库侧自动递增 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateTime;

    private LocalDateTime finishTime;

    private Integer deleted;

    /** 客户端幂等键。为空表示不做去重，MySQL 唯一键不约束 NULL */
    private String idempotencyKey;

}
