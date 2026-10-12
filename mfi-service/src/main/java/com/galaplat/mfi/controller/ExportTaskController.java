package com.galaplat.mfi.controller;

import com.galaplat.mfi.model.vo.ExportTaskVo;
import com.galaplat.mfi.service.export.ExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 导出任务的状态与取件。发起入口在 {@code POST /api/tokens/export}，与 token 列表同一组筛选条件。
 *
 * <p>两个接口都只认发起人：产物里就是 token 原文与密钥，同租户的其他成员没有下载别人导出结果的理由。
 */
@Tag(name = "导出任务")
@RestController
@RequestMapping("/api/export-tasks")
public class ExportTaskController {

    private final ExportService exportService;

    public ExportTaskController(ExportService exportService) {
        this.exportService = exportService;
    }

    @Operation(summary = "任务状态", description = "成功时附带下载路径；产物在服务端的位置不回显")
    @GetMapping("/{taskNo}")
    public ExportTaskVo status(@PathVariable String taskNo) {
        return exportService.status(taskNo);
    }

    @Operation(summary = "下载产物", description = "任务未完成或产物已被清理时返回业务错误，不下半个文件")
    @GetMapping("/{taskNo}/download")
    public ResponseEntity<Resource> download(@PathVariable String taskNo) {
        ExportService.ExportDownload file = exportService.download(taskNo);
        String encoded = URLEncoder.encode(file.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.resource());
    }

}
