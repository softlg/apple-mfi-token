package com.galaplat.mfi.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
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

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer deleted;

}
