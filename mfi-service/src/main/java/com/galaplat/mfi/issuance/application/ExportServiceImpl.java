package com.galaplat.mfi.issuance.application;

import com.alibaba.excel.EasyExcelFactory;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.support.ExcelTypeEnum;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.galaplat.mfi.shared.api.ErrorCode;
import com.galaplat.mfi.issuance.domain.ExportFormat;
import com.galaplat.mfi.issuance.domain.ExportTaskStatus;
import com.galaplat.mfi.identity.domain.OperationAction;
import com.galaplat.mfi.issuance.domain.TokenStatus;
import com.galaplat.mfi.shared.api.BizException;
import com.galaplat.mfi.issuance.config.ExportProperties;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPO;
import com.galaplat.mfi.issuance.infrastructure.persistence.ExportTaskPO;
import com.galaplat.mfi.identity.infrastructure.persistence.OperationLogPO;
import com.galaplat.mfi.issuance.infrastructure.persistence.TokenPO;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationMapper;
import com.galaplat.mfi.issuance.infrastructure.persistence.ExportTaskMapper;
import com.galaplat.mfi.identity.infrastructure.persistence.OperationLogMapper;
import com.galaplat.mfi.issuance.infrastructure.persistence.TokenMapper;
import com.galaplat.mfi.shared.tenant.TenantContext;
import com.galaplat.mfi.shared.tenant.TenantPrincipal;
import com.galaplat.mfi.issuance.infrastructure.excel.TokenExportRow;
import com.galaplat.mfi.issuance.interfaces.TokenExportRequest;
import com.galaplat.mfi.issuance.interfaces.ExportTaskVo;
import com.galaplat.mfi.shared.tenant.TenantAccess;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.FileSystemResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;

/**
 * token 异步导出。
 *
 * <p>三条硬约束：
 * <ol>
 *   <li>产物里带 token 原文与密钥，所以下载只认发起人，任务状态也只认发起人——同租户的另一名成员
 *       没有理由拿到别人发起导出的文件；</li>
 *   <li>读数据一律 keyset 分页且写完即丢，内存驻留只有一页，一万行不该变成一次 OOM；</li>
 *   <li>状态跃迁都是带前态的条件更新，与分片推进同一套纪律：进程重启后旧任务还留在库里，
 *       只有抢到 PROCESSING 的那次执行才允许写文件。</li>
 * </ol>
 */
@Slf4j
@Service
public class ExportServiceImpl implements ExportService {

    private static final DateTimeFormatter NO_DATE = DateTimeFormatter.ofPattern("yyMMdd");

    /** 与申请单号同一套字符集：去掉易混字符，人工报障时不容易念错 */
    private static final char[] NO_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Excel 双击打开 UTF-8 CSV 时靠 BOM 判断编码，缺了它中文表头会变乱码 */
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private static final String CSV_MEDIA = "text/csv";
    private static final String XLSX_MEDIA =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /**
     * 库里存的产物名允许的形状。任务号由本服务生成，这一层是"连库里的内容也不可信"的纵深防御：
     * 下载接口绝不让一次 {@code ..} 或路径分隔符逃出产物目录。
     */
    private static final Pattern STORED_NAME = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");

    private final ExportTaskMapper exportTaskMapper;
    private final TokenMapper tokenMapper;
    private final ApplicationMapper applicationMapper;
    private final OperationLogMapper operationLogMapper;
    private final ExportProperties properties;
    private final ObjectMapper objectMapper;
    private final Executor exportExecutor;

    public ExportServiceImpl(ExportTaskMapper exportTaskMapper, TokenMapper tokenMapper,
                             ApplicationMapper applicationMapper, OperationLogMapper operationLogMapper,
                             ExportProperties properties, ObjectMapper objectMapper,
                             @Qualifier("exportExecutor") Executor exportExecutor) {
        this.exportTaskMapper = exportTaskMapper;
        this.tokenMapper = tokenMapper;
        this.applicationMapper = applicationMapper;
        this.operationLogMapper = operationLogMapper;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.exportExecutor = exportExecutor;
    }

    @Override
    public ExportTaskVo create(TokenExportRequest request) {
        long tenantId = TenantAccess.requireTenantId();
        long userId = TenantAccess.requireUserId();
        ExportFormat format = parseFormat(request.getFormat());
        String status = parseStatus(request.getStatus());
        String ppid = trimToNull(request.getPpid());
        // 申请单号在受理时就换成内部 id 存进快照：执行线程不再认外部输入，也保证导出内容与发起那一刻看到的单一致
        Long applicationId = hasText(request.getApplicationNo())
                ? requireApplicationId(tenantId, request.getApplicationNo().trim()) : null;

        ExportTaskPO task = new ExportTaskPO();
        task.setTenantId(tenantId);
        task.setTaskNo(generateTaskNo());
        task.setRequestUserId(userId);
        task.setExportFormat(format.name());
        task.setParamSnapshot(writeSnapshot(new ExportParams(applicationId, ppid, status)));
        task.setStatus(ExportTaskStatus.PENDING.name());
        exportTaskMapper.insert(task);

        submit(tenantId, userId, task);
        log.info("导出任务已受理: tenantId={}, taskNo={}, userId={}, format={}",
                tenantId, task.getTaskNo(), userId, format);
        return toVo(task);
    }

    /**
     * 交给线程池。队列满就直接把任务判失败并回错误，而不是留一张永远 PENDING 的单：
     * 用户看到"任务繁忙"会重发，看到 PENDING 只会一直等。
     */
    private void submit(long tenantId, long userId, ExportTaskPO task) {
        try {
            exportExecutor.execute(() -> run(tenantId, task.getId(), userId));
        } catch (RejectedExecutionException e) {
            exportTaskMapper.markFailed(tenantId, task.getId(), "导出队列已满，请稍后重新发起");
            log.warn("导出任务被线程池拒绝: tenantId={}, taskNo={}", tenantId, task.getTaskNo());
            throw new BizException(ErrorCode.BIZ_REJECTED, "导出任务繁忙，请稍后重新发起");
        }
    }

    /**
     * 线程池里执行的一次导出。参数一律从库里读回：闭包只带三个 id，重启前后行为一致，
     * 也不会把请求线程的租户上下文带进工作线程。
     */
    void run(long tenantId, long taskId, long userId) {
        TenantContext.runAs(TenantPrincipal.system(tenantId), () -> doRun(tenantId, taskId, userId));
    }

    private void doRun(long tenantId, long taskId, long userId) {
        if (exportTaskMapper.markProcessing(tenantId, taskId) == 0) {
            log.info("导出任务已被领取或已结束，本次执行放弃: tenantId={}, taskId={}", tenantId, taskId);
            return;
        }
        ExportTaskPO task = exportTaskMapper.selectById(taskId);
        if (task == null) {
            log.warn("导出任务已不可见，放弃执行: tenantId={}, taskId={}", tenantId, taskId);
            return;
        }
        String storedName = task.getTaskNo() + "." + ExportFormat.valueOf(task.getExportFormat()).suffix();
        Path file = exportDir().resolve(storedName);
        try {
            ExportParams params = readSnapshot(task.getParamSnapshot());
            int rows = writePages(tenantId, task, params, file);
            if (exportTaskMapper.markSuccess(tenantId, taskId, storedName, rows) == 0) {
                // 已经被僵死扫描判过失败了，慢执行者不再改状态，也不留半个可下载的产物
                deleteQuietly(file);
                log.warn("导出任务已被判定僵死，丢弃产物: taskNo={}, rows={}", task.getTaskNo(), rows);
                return;
            }
            writeExportLog(tenantId, userId, task, params, rows);
            log.info("导出完成: tenantId={}, taskNo={}, rows={}, format={}",
                    tenantId, task.getTaskNo(), rows, task.getExportFormat());
        } catch (Exception e) {
            // 工作线程里没有全局异常处理器，任何漏出的异常都只会变成一行栈，所以必须自己落 FAILED
            log.error("导出任务失败: tenantId={}, taskNo={}", tenantId, task.getTaskNo(), e);
            // 半途失败的文件不留：任务已是 FAILED，下载接口拿不到它，留在磁盘上只是多一份凭证材料
            deleteQuietly(file);
            exportTaskMapper.markFailed(tenantId, taskId, summarize(e));
        }
    }

    /**
     * 分页读写。每页只驻留一页 POJO 与刚转出的行，写完就随下一轮循环失去引用；
     * {@code afterId} 按主键前进，所以代价只与本页行数有关，与已导出的总量无关。
     */
    private int writePages(long tenantId, ExportTaskPO task, ExportParams params, Path file) throws IOException {
        ExcelTypeEnum excelType = ExportFormat.valueOf(task.getExportFormat()) == ExportFormat.XLSX
                ? ExcelTypeEnum.XLSX : ExcelTypeEnum.CSV;
        int maxRows = Math.max(1, properties.getMaxRows());
        int pageSize = Math.max(1, Math.min(properties.getPageSize(), maxRows));
        Files.createDirectories(file.getParent());

        try (OutputStream out = Files.newOutputStream(file)) {
            if (excelType == ExcelTypeEnum.CSV) {
                out.write(UTF8_BOM);
            }
            // autoCloseStream(false)：流由外层 try 关，EasyExcel 的 close 只负责收尾自己那半
            try (ExcelWriter writer = EasyExcelFactory.write(out, TokenExportRow.class)
                    .excelType(excelType)
                    .autoCloseStream(false)
                    .charset(StandardCharsets.UTF_8)
                    .build()) {
                WriteSheet sheet = EasyExcelFactory.writerSheet(0).build();
                long afterId = 0L;
                int written = 0;
                while (written < maxRows) {
                    int limit = Math.min(pageSize, maxRows - written);
                    List<TokenPO> page = tokenMapper.selectExportPage(tenantId, afterId,
                            params.applicationId(), params.ppid(), params.status(), limit);
                    if (page.isEmpty()) {
                        break;
                    }
                    writer.write(toRows(page), sheet);
                    written += page.size();
                    afterId = page.get(page.size() - 1).getId();
                    if (page.size() < limit) {
                        break;
                    }
                    if (written >= maxRows) {
                        log.warn("导出行数触达上限并截断: taskNo={}, maxRows={}", task.getTaskNo(), maxRows);
                    }
                }
                return written;
            }
        }
    }

    @Override
    public ExportTaskVo status(String taskNo) {
        return toVo(requireOwnTask(taskNo));
    }

    @Override
    public ExportDownload download(String taskNo) {
        ExportTaskPO task = requireOwnTask(taskNo);
        if (!ExportTaskStatus.SUCCESS.name().equals(task.getStatus())) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "导出任务尚未完成，请稍后再试");
        }
        Path file = resolveStoredName(task.getFilePath());
        if (!Files.isReadable(file)) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "导出产物已不可用，请重新发起导出");
        }
        return new ExportDownload("tokens-" + task.getTaskNo() + "."
                + ExportFormat.valueOf(task.getExportFormat()).suffix(),
                mediaType(task.getExportFormat()), new FileSystemResource(file));
    }

    /**
     * 僵死任务扫描：执行进程被杀之后，任务会永远停在 PROCESSING，用户只能干等。
     *
     * <p>只置失败、不自动重跑：产物可能只写了一半，替用户重新发起一次导出等于替他拿走一份凭证材料。
     * 受理与提交线程池之间被杀的任务停在 PENDING，同样不会产生任何外部影响，用户重发即可。
     */
    @Scheduled(initialDelayString = "${mfi.export.scan-interval-millis:60000}",
            fixedDelayString = "${mfi.export.scan-interval-millis:60000}")
    public void failStaleTasks() {
        try {
            int rows = exportTaskMapper.failStaleProcessing(
                    LocalDateTime.now().minusMinutes(properties.getStaleTimeoutMinutes()), 50);
            if (rows > 0) {
                log.warn("发现僵死导出任务并判失败: count={}", rows);
            }
        } catch (Exception e) {
            log.error("导出任务扫描异常，下一轮继续", e);
        }
    }

    // ---- HELPERS ----

    /**
     * 任务号对外唯一标识一次导出。用随机段而不是自增 id：它会出现在下载链接里，
     * 自增号等于把租户量级和别人任务号的取值范围一起暴露出去。
     */
    private static String generateTaskNo() {
        StringBuilder suffix = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            suffix.append(NO_ALPHABET[RANDOM.nextInt(NO_ALPHABET.length)]);
        }
        return "E" + LocalDate.now().format(NO_DATE) + "-" + suffix;
    }

    private ExportTaskPO requireOwnTask(String taskNo) {
        long tenantId = TenantAccess.requireTenantId();
        long userId = TenantAccess.requireUserId();
        String no = trimToNull(taskNo);
        if (no == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "导出任务号不能为空");
        }
        ExportTaskPO task = exportTaskMapper.selectByTaskNo(tenantId, no);
        // 不存在与"是别人发起的"回同一句话：任务号是对外标识，区分两者等于给猜号的人一个反馈
        if (task == null || !Long.valueOf(userId).equals(task.getRequestUserId())) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "导出任务不存在，或不属于当前账号");
        }
        return task;
    }

    private Path exportDir() {
        return Path.of(properties.getDir()).toAbsolutePath().normalize();
    }

    /** 库里存的是相对名，拼路径前先确认它没带目录分隔符或 {@code ..} */
    private Path resolveStoredName(String storedName) {
        if (storedName == null || !STORED_NAME.matcher(storedName).matches()) {
            log.warn("导出产物路径不合法，拒绝下载: storedName={}", storedName);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "导出产物不可用，请重新发起导出");
        }
        Path base = exportDir();
        Path file = base.resolve(storedName).normalize();
        if (!file.startsWith(base)) {
            log.warn("导出产物路径越出产物目录，拒绝下载: storedName={}", storedName);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "导出产物不可用，请重新发起导出");
        }
        return file;
    }

    private static String mediaType(String format) {
        return ExportFormat.XLSX.name().equals(format) ? XLSX_MEDIA : CSV_MEDIA;
    }

    private String writeSnapshot(ExportParams params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "导出条件快照生成失败，请重试");
        }
    }

    private ExportParams readSnapshot(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) {
            return new ExportParams(null, null, null);
        }
        try {
            return objectMapper.readValue(snapshot, ExportParams.class);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "导出条件快照不可读，请重新发起导出");
        }
    }

    private static List<TokenExportRow> toRows(List<TokenPO> page) {
        List<TokenExportRow> rows = new ArrayList<>(page.size());
        for (TokenPO token : page) {
            TokenExportRow row = new TokenExportRow();
            row.setPpid(token.getPpid());
            row.setTokenId(token.getAuthEntityId());
            row.setAccessoryUuid(token.getAccessoryUUID());
            row.setManufactureSn(token.getManufactureSn());
            row.setSecretKey(token.getSecretKey());
            row.setEncodedToken(token.getEncodedToken());
            row.setStatus(token.getStatus());
            row.setRegisterStatus(token.getRegisterStatus());
            row.setDownloadTime(formatTime(token.getDownloadTime()));
            row.setRegisterTime(formatTime(token.getRegisterTime()));
            rows.add(row);
        }
        return rows;
    }

    private static String formatTime(LocalDateTime time) {
        return time == null ? "" : time.format(TIME);
    }

    /** 留痕只记数量与任务号：产物内容本身是凭证材料，一个字都不该出现在日志表里 */
    private void writeExportLog(long tenantId, long userId, ExportTaskPO task, ExportParams params, int rows) {
        OperationLogPO entry = new OperationLogPO();
        entry.setTenantId(tenantId);
        entry.setApplicationId(params.applicationId());
        entry.setAction(OperationAction.EXPORT.name());
        entry.setOperatorUserId(userId);
        entry.setQuantity(rows);
        entry.setDetail("导出 " + rows + " 行，格式 " + task.getExportFormat() + "，任务号 " + task.getTaskNo());
        operationLogMapper.insert(entry);
    }

    private Long requireApplicationId(long tenantId, String applicationNo) {
        ApplicationPO application = applicationMapper.selectOne(Wrappers.<ApplicationPO>lambdaQuery()
                .select(ApplicationPO::getId)
                .eq(ApplicationPO::getTenantId, tenantId)
                .eq(ApplicationPO::getApplicationNo, applicationNo));
        if (application == null) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "申请单不存在，或不属于当前租户");
        }
        return application.getId();
    }

    private static ExportFormat parseFormat(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return ExportFormat.CSV;
        }
        try {
            return ExportFormat.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.INVALID_PARAM, "不支持的导出格式: " + raw);
        }
    }

    private static String parseStatus(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return TokenStatus.valueOf(value.toUpperCase()).name();
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.INVALID_PARAM, "不支持的状态: " + raw);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (Exception e) {
            log.warn("丢弃导出产物失败: file={}", file, e);
        }
    }

    /** 失败原因会进库并被前端直接显示，压平并截断，不带栈 */
    private static String summarize(Exception e) {
        String message = e.getMessage();
        String flat = (e.getClass().getSimpleName() + ": " + (message == null ? "" : message))
                .replaceAll("\\s+", " ").trim();
        return flat.length() <= 480 ? flat : flat.substring(0, 480);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static ExportTaskVo toVo(ExportTaskPO task) {
        ExportTaskVo vo = new ExportTaskVo();
        vo.setTaskNo(task.getTaskNo());
        vo.setStatus(task.getStatus());
        vo.setExportFormat(task.getExportFormat());
        vo.setTotalRows(task.getTotalRows());
        vo.setFailReason(task.getFailReason());
        vo.setCreateTime(task.getCreateTime());
        vo.setFinishTime(task.getFinishTime());
        if (ExportTaskStatus.SUCCESS.name().equals(task.getStatus())) {
            vo.setDownloadUrl("/api/export-tasks/" + task.getTaskNo() + "/download");
        }
        return vo;
    }

    /**
     * 筛选条件快照。只在执行线程内部使用，不回显给前端、不进日志。
     *
     * @param applicationId 受理时已换成内部 id 的申请单，空表示全租户
     * @param ppid          PPID 精确匹配
     * @param status        TokenStatus 枚举名
     */
    record ExportParams(Long applicationId, String ppid, String status) {
    }

}
