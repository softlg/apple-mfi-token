package com.galaplat.mfi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 导出运行参数。都是运维语义（线程数、磁盘位置、行数上限），不进租户配置：
 * 一个租户把上限调到千万行会耗尽整个进程的堆与磁盘，而这不是它能决定的事。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mfi.export")
public class ExportProperties {

    /**
     * 产物目录。必须是本服务独占的目录：下载接口按"目录 + 库里存的相对名"拼路径，
     * 共用目录等于把别人的文件也变成可下载对象。
     */
    private String dir = "./export";

    /**
     * 每页读取行数。必须明显小于单页 token 的常驻大小——导出走 keyset 分页，
     * 内存里同时只保留一页 POJO 与刚转换出的那一批行。
     */
    private int pageSize = 1000;

    /**
     * 单个任务的最大行数上限。超限直接截断并记 WARN：这条上限是磁盘与耗时的兜底，
     * 不是业务配额——真要全量就分型号/分单多次导出。
     */
    private int maxRows = 100_000;

    /** 同时执行的导出任务数。导出是"读库 + 写盘"，线程数受数据库连接池与磁盘限制，不给太大 */
    private int workerSize = 2;

    /** 等待队列长度。队列满即拒绝新任务，而不是让任务无限堆积到最后全部超时 */
    private int queueCapacity = 50;

    /**
     * PROCESSING 的最长驻留时间。超过即判定执行进程已不在（重启、OOM、机器下线），置 FAILED。
     * 必须大于最慢一次真实导出，否则会把还在写的任务判死、留下半个文件。
     */
    private int staleTimeoutMinutes = 30;

    /** 僵死任务扫描间隔 */
    private long scanIntervalMillis = 60_000L;

}
