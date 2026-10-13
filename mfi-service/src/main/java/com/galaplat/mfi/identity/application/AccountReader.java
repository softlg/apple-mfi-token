package com.galaplat.mfi.identity.application;

/**
 * 账号展示名的只读入口。业务单据要留下发起人姓名快照，但不该因此认识用户表。
 */
public interface AccountReader {

    /**
     * 姓名优先、账号兜底；账号已注销或查不到时返回可读占位——单据上的发起人不能是空的。
     */
    String displayName(long userId);

}
