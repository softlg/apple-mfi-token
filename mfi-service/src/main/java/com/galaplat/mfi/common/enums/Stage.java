package com.galaplat.mfi.common.enums;

/**
 * Token 申请链路的五个阶段，与苹果 MFI 接口调用顺序一一对应。
 */
public enum Stage {

    /** 向苹果请求认证实体 */
    APPLY,
    /** 取回文件名列表，苹果未生成完时按 retryDownloadAfter 延迟 */
    FILE_NAME,
    /** 逐文件下载 xlsx 并解析入库 */
    FILE_CONTENT,
    /** 批量注册已使用实体 */
    REGISTER,
    /** 分配制造序列号、聚合状态、通知 */
    FINISH;

    /**
     * 下一阶段，FINISH 为终态返回 null。
     */
    public Stage next() {
        return switch (this) {
            case APPLY -> FILE_NAME;
            case FILE_NAME -> FILE_CONTENT;
            case FILE_CONTENT -> REGISTER;
            case REGISTER -> FINISH;
            case FINISH -> null;
        };
    }

    public boolean isFinal() {
        return this == FINISH;
    }

}
