package com.galaplat.mfi.common.api;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 统一错误码。
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    /** 成功 */
    SUCCESS("0", "成功"),

    /** 参数不合法 */
    INVALID_PARAM("A0400", "参数不合法"),

    /** 业务校验不通过 */
    BIZ_REJECTED("A0422", "业务校验不通过"),

    /** 未登录或登录态失效 */
    UNAUTHORIZED("A0401", "未登录或登录状态已失效"),

    /** 账号或密码不正确，不区分哪一个错，避免账号枚举 */
    BAD_CREDENTIALS("A0402", "账号或密码不正确"),

    /** 需要租户 owner 身份 */
    NOT_OWNER("A0403", "该操作仅限租户 owner"),

    /** 登录账号已被占用 */
    ACCOUNT_TAKEN("A0409", "登录账号已存在，请换一个"),

    /** 无生效租户，或已不属于该租户 */
    TENANT_UNAVAILABLE("A0410", "当前账号没有可用的租户上下文"),

    /** 租户尚未配置 MFI 客户端证书 */
    CREDENTIAL_MISSING("B0404", "租户尚未配置 MFI 客户端证书"),

    /** 租户 MFI 证书已过期 */
    CREDENTIAL_EXPIRED("B0410", "租户 MFI 客户端证书已过期"),

    /** 系统内部错误 */
    SYSTEM_ERROR("C0001", "系统内部错误"),

    /** 租户上下文缺失，属于编码缺陷，绝不允许降级为全表访问 */
    TENANT_NOT_BOUND("C0002", "租户上下文缺失，已拒绝执行数据访问");

    private final String code;
    private final String message;

}
