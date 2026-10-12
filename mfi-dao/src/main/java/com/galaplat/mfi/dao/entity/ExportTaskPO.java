package com.galaplat.mfi.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 异步导出任务。token 量大时不能把导出塞进一次 HTTP 请求（MES 的同步下载在几千行时就把连接耗到超时），
 * 所以任务先落库、由线程池执行，读侧只查这一行。
 *
 * <p>{@code taskNo} 是对外唯一标识：自增 id 会暴露租户量级，而任务号会出现在下载链接与厂商沟通里。
 */
@Data
@TableName("t_export_task")
public class ExportTaskPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    private String taskNo;

    private Long requestUserId;

    /** CSV / XLSX */
    private String exportFormat;

    /** 请求参数快照（JSON，只含筛选条件与行数上限，绝不含 token 原文） */
    private String paramSnapshot;

    /** PENDING/PROCESSING/SUCCESS/FAILED */
    private String status;

    private String filePath;

    private Integer totalRows;

    private String failReason;

    /** 时间戳由库默认值与 ON UPDATE 维护：写策略 NEVER，避免把读出的旧值回写、抑制库侧自动递增 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;

    /** 时间戳由库默认值与 ON UPDATE 维护：写策略 NEVER，避免把读出的旧值回写、抑制库侧自动递增 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateTime;

    private LocalDateTime finishTime;

    private Integer deleted;

}
