package com.galaplat.mfi.identity.interfaces;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 成员列表项。绝不含 password_hash 等任何凭证字段。
 */
@Data
public class MemberVo {

    private Long memberId;

    private Long userId;

    private String account;

    private String name;

    private String email;

    private String phone;

    /** 1=该租户 owner 0=普通成员 */
    private Integer ownerFlag;

    /** 成员关系状态 1=启用 0=停用 */
    private Integer status;

    /** 账号本身状态，停用的账号在本租户即使启用也无法登录 */
    private Integer userStatus;

    private LocalDateTime joinTime;

}
