package com.galaplat.mfi.identity.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户 = 一家 MFI 被授权厂商（全局表，不参与租户改写）。
 *
 * <p>隔离单位本身不能带隔离条件，否则任何按 id 取租户名都要先有租户上下文，形成死锁。
 */
@Data
@TableName("t_tenant")
public class TenantPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 租户编码，全局唯一，对外展示用 */
    private String tenantCode;

    private String tenantName;

    /** 苹果 technology，决定 BaseURL 分流 */
    private String appleTechnology;

    /** 覆盖默认 BaseURL，仅联调桩或自建代理时使用 */
    private String appleBaseUrl;

    /** 1=启用 0=暂停 */
    private Integer status;

    /** 时间戳由库默认值与 ON UPDATE 维护：写策略 NEVER，避免把读出的旧值回写、抑制库侧自动递增 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;

    /** 时间戳由库默认值与 ON UPDATE 维护：写策略 NEVER，避免把读出的旧值回写、抑制库侧自动递增 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateTime;

    private Integer deleted;

}
