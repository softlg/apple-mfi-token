package com.galaplat.mfi.issuance.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import com.galaplat.mfi.issuance.domain.Stage;
import com.galaplat.mfi.issuance.domain.StageStatus;
import com.galaplat.mfi.ppid.infrastructure.persistence.PpIdPO;

/**
 * 申请单的一个 PPID 分片，同时是异步流水线的推进位。
 *
 * <p>这是对 MES {@code t_token_process_log} 的修正：那张表按 (operation_id, ppid) upsert 只留最新一步，
 * 既还原不了进度也判断不了卡点。本表一行 = 一个分片，阶段与状态由 CAS 跃迁，历史另记操作日志。
 */
@Data
@TableName("t_application_ppid")
public class ApplicationPpidPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    private Long applicationId;

    private String ppid;

    /** 指向 {@link PpIdPO#getId()}，回滚额度时要按这一行扣回去 */
    private Long ppidId;

    private Integer allocateCount;

    /** Stage 枚举名 */
    private String stage;

    /** StageStatus 枚举名 */
    private String stageStatus;

    private String appleRequestUuid;

    private Integer retryCount;

    /** 延迟与重试的下一次执行时间，也是兜底扫描的判据 */
    private LocalDateTime nextExecuteTime;

    private String lastError;

    /** 时间戳由库默认值与 ON UPDATE 维护：写策略 NEVER，避免把读出的旧值回写、抑制库侧自动递增 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;

    /** 时间戳由库默认值与 ON UPDATE 维护：写策略 NEVER，避免把读出的旧值回写、抑制库侧自动递增 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateTime;

    private Integer deleted;

}
