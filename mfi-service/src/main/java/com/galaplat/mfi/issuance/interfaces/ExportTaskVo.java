package com.galaplat.mfi.issuance.interfaces;

import lombok.Data;

import java.time.LocalDateTime;
import com.galaplat.mfi.issuance.domain.ExportTaskStatus;

/**
 * 导出任务状态。
 *
 * <p>没有 {@code filePath}：产物在服务端磁盘上的位置不是租户该知道的东西，成功时给一个本服务的
 * 下载路径就够了。筛选条件快照也不回显——里面含 PPID，任务状态页用不上。
 */
@Data
public class ExportTaskVo {

    private String taskNo;

    /** ExportTaskStatus 枚举名 */
    private String status;

    private String exportFormat;

    private Integer totalRows;

    private String failReason;

    private LocalDateTime createTime;

    private LocalDateTime finishTime;

    /** 仅 SUCCESS 时有值 */
    private String downloadUrl;

}
