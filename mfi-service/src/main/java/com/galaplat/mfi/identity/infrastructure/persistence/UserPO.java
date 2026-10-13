package com.galaplat.mfi.identity.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户账号（全局表，不参与租户改写）。
 *
 * <p>一个账号可加入多个租户，因此它不属于任何单一租户；password_hash 只存 Argon2 哈希，
 * 任何接口都不得回显该列。
 */
@Data
@TableName("t_user")
public class UserPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录账号，全局唯一 */
    private String account;

    /** Argon2 密码哈希，绝不明文 */
    private String passwordHash;

    private String name;

    private String email;

    private String phone;

    /** 1=启用 0=停用 */
    private Integer status;

    private LocalDateTime lastLoginTime;

    /** 时间戳由库默认值与 ON UPDATE 维护：写策略 NEVER，避免把读出的旧值回写、抑制库侧自动递增 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;

    /** 时间戳由库默认值与 ON UPDATE 维护：写策略 NEVER，避免把读出的旧值回写、抑制库侧自动递增 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateTime;

    /** 逻辑删除位，由 global-config 的 logic-delete-field 统一接管 */
    private Integer deleted;

}
