package com.galaplat.mfi.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户成员关系。owner_flag 是本产品唯一的高危操作区分位，不构成权限体系。
 *
 * <p>本表带 tenant_id 且**不在**全局白名单里，所以常规读写都会被租户拦截器改写。
 * 只有"按 user_id 查我加入了哪些租户"这一类查询必须显式绕开改写，见
 * {@link com.galaplat.mfi.dao.mapper.TenantMemberMapper#selectByUserId}。
 */
@Data
@TableName("t_tenant_member")
public class TenantMemberPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    private Long userId;

    /** 1=该租户的 owner，可换证书/改租户配置/移除成员/转让 owner */
    private Integer ownerFlag;

    /** 1=启用 0=停用 */
    private Integer status;

    private LocalDateTime joinTime;

    /** 时间戳由库默认值与 ON UPDATE 维护：写策略 NEVER，避免把读出的旧值回写、抑制库侧自动递增 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;

    /** 时间戳由库默认值与 ON UPDATE 维护：写策略 NEVER，避免把读出的旧值回写、抑制库侧自动递增 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateTime;

    private Integer deleted;

}
