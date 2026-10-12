package com.galaplat.mfi.common.enums;

/**
 * 异步导出任务状态，与 {@code t_export_task.status} 的取值一致。
 *
 * <p>和分片的 {@link StageStatus} 分开：导出任务没有"阶段"概念，也不会进死信，
 * 僵死任务由扫描直接判失败，用户重新发起一次即可。
 */
public enum ExportTaskStatus {

    /** 已受理，还没被线程池领走 */
    PENDING,
    /** 正在读数据、写文件 */
    PROCESSING,
    SUCCESS,
    FAILED;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED;
    }

}
