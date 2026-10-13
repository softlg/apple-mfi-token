package com.galaplat.mfi.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户级业务参数，键取自 {@code TenantConfigKey}，缺键即用全局默认值。
 */
@Data
@TableName("t_tenant_config")
public class TenantConfigPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    private String configKey;

    private String configValue;

    private String remark;

    /** 时间戳由库默认值与 ON UPDATE 维护：写策略 NEVER，避免把读出的旧值回写、抑制库侧自动递增 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;

    /** 时间戳由库默认值与 ON UPDATE 维护：写策略 NEVER，避免把读出的旧值回写、抑制库侧自动递增 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateTime;

    private Integer deleted;

}
