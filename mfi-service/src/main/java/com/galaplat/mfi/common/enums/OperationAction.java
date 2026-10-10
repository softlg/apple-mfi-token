package com.galaplat.mfi.common.enums;

/**
 * 操作留痕的动作名，与 {@code t_operation_log.action} 的取值一一对应。
 *
 * <p>集中成枚举是为了让五个阶段监听器与导出、销毁等入口写的是同一份词表：动作名一旦散落成各处字符串，
 * 排障时按 action 过滤就漏数据。
 */
public enum OperationAction {

    /** 申请受理（同步段成功落库） */
    APPLY,
    /** 阶段推进、重试或进入死信 */
    STAGE_ADVANCE,
    /** 人工重放 */
    RETRY,
    /** 凭证替换 */
    CREDENTIAL_UPDATE,
    /** 成员移除 */
    MEMBER_REMOVE,
    /** 销毁未使用实体 */
    DESTROY,
    /** 导出任务 */
    EXPORT;

}
