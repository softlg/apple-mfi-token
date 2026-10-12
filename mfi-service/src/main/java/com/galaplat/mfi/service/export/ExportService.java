package com.galaplat.mfi.service.export;

import com.galaplat.mfi.model.request.TokenExportRequest;
import com.galaplat.mfi.model.vo.ExportTaskVo;
import org.springframework.core.io.Resource;

/**
 * token 异步导出。
 *
 * <p>MES 是在请求线程里边查边写流，几千行就把连接耗到超时；这里把"发起"与"取件"拆开，
 * 请求线程只做校验与落库，读数据写盘都在线程池里。
 */
public interface ExportService {

    /**
     * 受理一次导出，返回 PENDING 任务。
     */
    ExportTaskVo create(TokenExportRequest request);

    /**
     * 任务状态。只允许发起人查看。
     */
    ExportTaskVo status(String taskNo);

    /**
     * 取产物。只允许发起人下载，且任务必须已经 SUCCESS。
     */
    ExportDownload download(String taskNo);

    /**
     * @param fileName     下载文件名（含扩展名）
     * @param contentType  媒体类型
     * @param resource     产物内容
     */
    record ExportDownload(String fileName, String contentType, Resource resource) {
    }

}
