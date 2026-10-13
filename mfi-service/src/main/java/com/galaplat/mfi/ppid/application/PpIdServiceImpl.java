package com.galaplat.mfi.ppid.application;

import com.alibaba.excel.EasyExcelFactory;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.galaplat.mfi.shared.api.ErrorCode;
import com.galaplat.mfi.shared.api.PageResult;
import com.galaplat.mfi.shared.api.BizException;
import com.galaplat.mfi.ppid.infrastructure.persistence.PpIdPO;
import com.galaplat.mfi.ppid.infrastructure.persistence.PpIdMapper;
import com.galaplat.mfi.ppid.infrastructure.excel.PpIdExportRow;
import com.galaplat.mfi.ppid.infrastructure.excel.PpIdImportRow;
import com.galaplat.mfi.ppid.interfaces.PpIdCreateRequest;
import com.galaplat.mfi.ppid.interfaces.PpIdPageRequest;
import com.galaplat.mfi.ppid.interfaces.PpIdUpdateRequest;
import com.galaplat.mfi.ppid.interfaces.PpIdAvailableVo;
import com.galaplat.mfi.ppid.interfaces.PpIdImportResultVo;
import com.galaplat.mfi.ppid.interfaces.PpIdPlanVo;
import com.galaplat.mfi.ppid.interfaces.PpIdRowErrorVo;
import com.galaplat.mfi.ppid.interfaces.PpIdStatsVo;
import com.galaplat.mfi.ppid.interfaces.PpIdVo;
import com.galaplat.mfi.shared.tenant.TenantAccess;
import com.galaplat.mfi.ppid.application.PpIdService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PPID 池实现。
 *
 * <p>与并发有关的三条判断都在这里，其余 CRUD 都是普通语句：
 * <ul>
 *   <li>余量归零的自动停用写在 {@code deduct} 的 SQL 里，本服务不参与，所以预览看到的池子与真实分配
 *       看到的池子始终一致。</li>
 *   <li>人工修改总量时把 {@code applied_quantity} 作为条件带上：分配线程只动 applied/remaining，人工只动
 *       total，这个快照条件让"人工改总量"不会覆盖掉中间刚发生的扣减。</li>
 *   <li>导入先全量校验再一次性写入，任一行不合法就整份拒绝，库里不会出现半份文件。</li>
 * </ul>
 */
@Slf4j
@Service
public class PpIdServiceImpl implements PpIdService {

    /** 与建表语句、请求校验一致的单行上限 */
    static final int QUANTITY_MAX = 1_000_000;
    static final int NAME_MAX_LEN = 64;
    private static final int MAX_IMPORT_ROWS = 20_000;
    private static final int MAX_EXPORT_ROWS = 20_000;
    /** 多值插入的分片大小：再大就逼近 max_allowed_packet，再小是白白多打往返 */
    private static final int INSERT_CHUNK = 500;
    private static final int ENABLED = PpIdPO.STATUS_ENABLED;
    private static final int DISABLED = PpIdPO.STATUS_DISABLED;

    private final PpIdMapper ppidMapper;

    public PpIdServiceImpl(PpIdMapper ppidMapper) {
        this.ppidMapper = ppidMapper;
    }

    @Override
    public PageResult<PpIdVo> page(PpIdPageRequest request) {
        TenantAccess.requireTenantId();
        Page<PpIdPO> page = Page.of(request.getPageNum(), request.getPageSize());
        ppidMapper.selectPage(page, buildFilter(request).orderByDesc(PpIdPO::getId));
        return PageResult.of(page, PpIdServiceImpl::toVo);
    }

    @Override
    public PpIdVo create(PpIdCreateRequest request) {
        long tenantId = TenantAccess.requireTenantId();
        PpIdPO row = new PpIdPO();
        row.setTenantId(tenantId);
        row.setPpid(requireText(request.getPpid(), "PPID"));
        row.setSpu(requireText(request.getSpu(), "SPU"));
        row.setProductModel(requireText(request.getProductModel(), "产品型号"));
        row.setTotalQuantity(request.getTotalQuantity());
        row.setAppliedQuantity(0);
        row.setRemainingQuantity(request.getTotalQuantity());
        row.setStatus(ENABLED);
        row.setRemark(trimToNull(request.getRemark()));
        row.setCreateBy(TenantAccess.requireUserId());
        try {
            ppidMapper.insert(row);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "该 PPID + SPU 已在池中，请直接在列表里修改这份额度");
        }
        log.info("PPID 额度新增: tenantId={}, total={}", tenantId, row.getTotalQuantity());
        return toVo(row);
    }

    @Override
    public PpIdVo update(Long id, PpIdUpdateRequest request) {
        PpIdPO row = requireRow(id);
        int applied = row.getAppliedQuantity();
        int total = request.getTotalQuantity();
        if (total < applied) {
            throw new BizException(ErrorCode.INVALID_PARAM,
                    "可申请总量不能小于已申请量 " + applied + "；已申请的额度不可在本系统撤销，需要回收请到苹果侧处理");
        }
        int remaining = total - applied;
        int status = request.getStatus();
        if (status == ENABLED && remaining <= 0) {
            throw new BizException(ErrorCode.INVALID_PARAM, "余量为 0 的额度无法启用；请先提高可申请总量，或保持停用");
        }
        int updated = ppidMapper.update(null, Wrappers.<PpIdPO>lambdaUpdate()
                .eq(PpIdPO::getId, id)
                .eq(PpIdPO::getAppliedQuantity, applied)
                .set(PpIdPO::getTotalQuantity, total)
                .set(PpIdPO::getRemainingQuantity, remaining)
                .set(PpIdPO::getStatus, status)
                .set(PpIdPO::getRemark, trimToNull(request.getRemark())));
        if (updated == 0) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "这份额度刚被申请占用，请刷新后重试");
        }
        log.info("PPID 额度修改: tenantId={}, ppidId={}, total={}, status={}", row.getTenantId(), id, total, status);
        return toVo(requireRow(id));
    }

    @Override
    public void disable(Long id) {
        PpIdPO row = requireRow(id);
        ppidMapper.update(null, Wrappers.<PpIdPO>lambdaUpdate()
                .eq(PpIdPO::getId, id)
                .set(PpIdPO::getStatus, DISABLED));
        log.info("PPID 额度停用: tenantId={}, ppidId={}, remaining={}", row.getTenantId(), id,
                row.getRemainingQuantity());
    }

    @Override
    public List<PpIdStatsVo> stats() {
        long tenantId = TenantAccess.requireTenantId();
        List<Map<String, Object>> rows = ppidMapper.selectPoolStats(tenantId);
        List<PpIdStatsVo> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            PpIdStatsVo vo = new PpIdStatsVo();
            vo.setProductModel(asString(row.get("product_model")));
            vo.setSpu(asString(row.get("spu")));
            vo.setPpidRows(asInt(row.get("ppid_rows")));
            vo.setEnabledRows(asInt(row.get("enabled_rows")));
            vo.setTotalQuantity(asInt(row.get("total_quantity")));
            vo.setAppliedQuantity(asInt(row.get("applied_quantity")));
            vo.setRemainingQuantity(asInt(row.get("remaining_quantity")));
            result.add(vo);
        }
        return result;
    }

    @Override
    public PpIdAvailableVo available(String productModel, String spu, Integer quantity) {
        long tenantId = TenantAccess.requireTenantId();
        String model = requireText(productModel, "产品型号");
        String spuValue = requireText(spu, "SPU");
        if (quantity == null || quantity < 1) {
            throw new BizException(ErrorCode.INVALID_PARAM, "预计申请数量至少为 1");
        }
        if (quantity > QUANTITY_MAX) {
            throw new BizException(ErrorCode.INVALID_PARAM, "预计申请数量上限 " + QUANTITY_MAX);
        }
        // 与 P4 分配同一条 SQL、同一个排序，所以这里的拆分可能就是申请时的真实拆分
        List<PpIdPO> pool = ppidMapper.selectAllocatable(tenantId, model, spuValue);
        int allocatable = pool.stream().mapToInt(PpIdPO::getRemainingQuantity).sum();

        PpIdAvailableVo vo = new PpIdAvailableVo();
        vo.setProductModel(model);
        vo.setSpu(spuValue);
        vo.setRequestedQuantity(quantity);
        vo.setAllocatableQuantity(allocatable);
        vo.setSufficient(allocatable >= quantity);
        vo.setPlan(buildPlan(pool, quantity));
        return vo;
    }

    @Override
    public List<PpIdPlanVo> planAllocation(String productModel, String spu, int quantity) {
        long tenantId = TenantAccess.requireTenantId();
        return buildPlan(ppidMapper.selectAllocatable(tenantId, requireText(productModel, "产品型号"),
                requireText(spu, "SPU")), quantity);
    }

    /**
     * 贪心拆分：余量多的先用、零头留后，拆出的分片数最少。
     */
    private List<PpIdPlanVo> buildPlan(List<PpIdPO> pool, int requestedQuantity) {        List<PpIdPlanVo> plan = new ArrayList<>();
        int need = requestedQuantity;
        for (PpIdPO row : pool) {
            if (need <= 0) {
                break;
            }
            int take = Math.min(need, row.getRemainingQuantity());
            PpIdPlanVo item = new PpIdPlanVo();
            item.setPpidId(row.getId());
            item.setPpid(row.getPpid());
            item.setQuantity(take);
            item.setRemainingBefore(row.getRemainingQuantity());
            plan.add(item);
            need -= take;
        }
        return plan;
    }

    private LambdaQueryWrapper<PpIdPO> buildFilter(PpIdPageRequest request) {
        return Wrappers.<PpIdPO>lambdaQuery()
                .like(hasText(request.getPpid()), PpIdPO::getPpid, trimToNull(request.getPpid()))
                .eq(hasText(request.getSpu()), PpIdPO::getSpu, trimToNull(request.getSpu()))
                .eq(hasText(request.getProductModel()), PpIdPO::getProductModel, trimToNull(request.getProductModel()))
                .eq(request.getStatus() != null, PpIdPO::getStatus, request.getStatus());
    }

    private PpIdPO requireRow(Long id) {
        PpIdPO row = ppidMapper.selectById(id);
        if (row == null) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "这份额度不存在，或不属于当前租户");
        }
        return row;
    }

    /** 文件里的一行原始数据，带上 Excel 界面里的行号（表头算第 1 行） */
    private record NumberedRow(int rowNo, PpIdImportRow row) {
    }

    /** 校验通过的行：行号留给冲突报错用，实体是准备入库的 */
    private record PendingRow(NumberedRow origin, PpIdPO entity) {
    }

    /**
     * 导入。整个方法在一个事务里，Excel 解析也在事务内：单次导入按 {@link #MAX_IMPORT_ROWS} 行封顶，
     * 占住连接的时间有上界，换来的是"不会出现半份文件入库"这种能向用户解释清楚的边界。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public PpIdImportResultVo importExcel(MultipartFile file) {
        long tenantId = TenantAccess.requireTenantId();
        long userId = TenantAccess.requireUserId();
        List<NumberedRow> rows = readRows(file);

        List<PpIdRowErrorVo> errors = new ArrayList<>();
        List<PendingRow> pending = new ArrayList<>(rows.size());
        Map<List<String>, Integer> firstSeenRow = new HashMap<>();
        for (NumberedRow numbered : rows) {
            PpIdImportRow row = numbered.row();
            List<String> issues = new ArrayList<>();
            String ppid = requireLength(row.getPpid(), "PPID", issues);
            String spu = requireLength(row.getSpu(), "SPU", issues);
            String model = requireLength(row.getProductModel(), "产品型号", issues);
            Integer total = parseQuantity(row.getTotalQuantity(), issues);
            if (ppid != null && spu != null) {
                Integer earlier = firstSeenRow.putIfAbsent(key(ppid, spu), numbered.rowNo());
                if (earlier != null) {
                    issues.add("与文件内第 " + earlier + " 行重复（PPID + SPU 相同）");
                }
            }
            if (issues.isEmpty()) {
                pending.add(new PendingRow(numbered,
                        toEntity(tenantId, userId, ppid, spu, model, total, row.getRemark())));
            } else {
                errors.add(PpIdRowErrorVo.of(numbered.rowNo(), ppid != null ? ppid : trimToNull(row.getPpid()),
                        String.join("；", issues)));
            }
        }
        rejectExistingPoolKeys(pending, errors);
        errors.sort(Comparator.comparingInt(PpIdRowErrorVo::getRowNo));

        PpIdImportResultVo result = new PpIdImportResultVo();
        result.setReadRows(rows.size());
        result.setErrors(errors);
        result.setRejectedRows(errors.size());
        if (!errors.isEmpty()) {
            result.setInsertedRows(0);
            log.warn("PPID 导入整份拒绝: tenantId={}, readRows={}, errorRows={}", tenantId, rows.size(), errors.size());
            return result;
        }
        int inserted = 0;
        for (int from = 0; from < pending.size(); from += INSERT_CHUNK) {
            List<PpIdPO> chunk = pending.subList(from, Math.min(from + INSERT_CHUNK, pending.size()))
                    .stream().map(PendingRow::entity).toList();
            inserted += ppidMapper.insertBatch(chunk);
        }
        result.setInsertedRows(inserted);
        log.info("PPID 导入完成: tenantId={}, readRows={}, insertedRows={}", tenantId, rows.size(), inserted);
        return result;
    }

    @Override
    public void writeTemplate(HttpServletResponse response) {
        TenantAccess.requireTenantId();
        writeExcel(response, "ppid-导入模板.xlsx", PpIdImportRow.class, List.of());
    }

    @Override
    public void export(PpIdPageRequest filter, HttpServletResponse response) {
        TenantAccess.requireTenantId();
        Long total = ppidMapper.selectCount(buildFilter(filter));
        if (total > MAX_EXPORT_ROWS) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "当前条件命中 " + total + " 行，超过单次导出上限 "
                    + MAX_EXPORT_ROWS + "；请加上产品型号 / SPU / 状态收窄条件后分批导出");
        }
        List<PpIdPO> rows = ppidMapper.selectList(buildFilter(filter)
                .orderByAsc(PpIdPO::getProductModel).orderByAsc(PpIdPO::getSpu).orderByAsc(PpIdPO::getId));
        List<PpIdExportRow> exportRows = rows.stream().map(PpIdServiceImpl::toExportRow).toList();
        writeExcel(response, "ppid-池.xlsx", PpIdExportRow.class, exportRows);
    }

    /**
     * 与库里既有额度的冲突。按 ppid 分片 IN 一次查完，一万行导入不会变成一万次往返。
     */
    private void rejectExistingPoolKeys(List<PendingRow> pending, List<PpIdRowErrorVo> errors) {
        if (pending.isEmpty()) {
            return;
        }
        Set<List<String>> existing = loadExistingKeys(pending.stream().map(p -> p.entity().getPpid()).toList());
        for (PendingRow row : pending) {
            if (existing.contains(key(row.entity().getPpid(), row.entity().getSpu()))) {
                errors.add(PpIdRowErrorVo.of(row.origin().rowNo(), row.entity().getPpid(),
                        "池里已存在这份额度（PPID + SPU 相同）；要改数量请在列表里修改，或先停用旧行再导入"));
            }
        }
    }

    private Set<List<String>> loadExistingKeys(Collection<String> ppids) {
        List<String> distinct = new ArrayList<>(new HashSet<>(ppids));
        Set<List<String>> keys = new HashSet<>();
        for (int from = 0; from < distinct.size(); from += INSERT_CHUNK) {
            List<String> chunk = distinct.subList(from, Math.min(from + INSERT_CHUNK, distinct.size()));
            List<PpIdPO> found = ppidMapper.selectList(Wrappers.<PpIdPO>lambdaQuery()
                    .select(PpIdPO::getPpid, PpIdPO::getSpu)
                    .in(PpIdPO::getPpid, chunk));
            for (PpIdPO po : found) {
                keys.add(key(po.getPpid(), po.getSpu()));
            }
        }
        return keys;
    }

    private List<NumberedRow> readRows(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_PARAM, "请选择要导入的 Excel 文件");
        }
        String name = file.getOriginalFilename();
        if (name == null || !(name.toLowerCase().endsWith(".xlsx") || name.toLowerCase().endsWith(".xls"))) {
            throw new BizException(ErrorCode.INVALID_PARAM, "只接受 .xlsx 或 .xls 文件");
        }
        RowCollector collector = new RowCollector();
        try (InputStream in = file.getInputStream()) {
            EasyExcelFactory.read(in, PpIdImportRow.class, collector).sheet(0).headRowNumber(1).doRead();
        } catch (IOException e) {
            log.warn("PPID 导入文件读取失败", e);
            throw new BizException(ErrorCode.INVALID_PARAM, "文件读取失败，请确认文件没有被 Excel 独占打开");
        } catch (RuntimeException e) {
            // 表头被改动、列错位、单元格类型不认识都会走到这里，统一成可读错误而不是 500
            log.warn("PPID 导入文件解析失败: name={}", name, e);
            throw new BizException(ErrorCode.INVALID_PARAM, "文件解析失败，请下载最新模板后按列填写");
        }
        if (collector.overflow) {
            throw new BizException(ErrorCode.BIZ_REJECTED,
                    "数据行超过单次导入上限 " + MAX_IMPORT_ROWS + "；请拆分文件后分批导入");
        }
        if (collector.rows.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_PARAM, "文件里没有找到数据行；表头之后的内容都是空的");
        }
        return collector.rows;
    }

    /**
     * 监听器只负责收集。行数超限不再抛异常而是置位后停止收集：EasyExcel 会把监听器抛出的异常再包一层，
     * 从里面抛业务异常就拿不回原始错误消息了。
     */
    private static final class RowCollector extends AnalysisEventListener<PpIdImportRow> {

        private final List<NumberedRow> rows = new ArrayList<>();
        private boolean overflow;

        @Override
        public void invoke(PpIdImportRow row, AnalysisContext context) {
            if (isBlank(row)) {
                return;
            }
            if (rows.size() >= MAX_IMPORT_ROWS) {
                overflow = true;
                return;
            }
            // rowIndex 是 0 起的物理行号，表头占第一行，所以界面行号要 +1
            rows.add(new NumberedRow(context.readRowHolder().getRowIndex() + 1, row));
        }

        @Override
        public void doAfterAllAnalysed(AnalysisContext context) {
            // 收集完由调用方统一校验，这里没有收尾动作
        }

        private static boolean isBlank(PpIdImportRow row) {
            return trimToNull(row.getPpid()) == null && trimToNull(row.getSpu()) == null
                    && trimToNull(row.getProductModel()) == null && trimToNull(row.getTotalQuantity()) == null
                    && trimToNull(row.getRemark()) == null;
        }
    }

    private static PpIdPO toEntity(long tenantId, long userId, String ppid, String spu, String productModel,
                                   Integer totalQuantity, String remark) {
        PpIdPO row = new PpIdPO();
        row.setTenantId(tenantId);
        row.setPpid(ppid);
        row.setSpu(spu);
        row.setProductModel(productModel);
        row.setTotalQuantity(totalQuantity);
        row.setAppliedQuantity(0);
        row.setRemainingQuantity(totalQuantity);
        row.setStatus(ENABLED);
        row.setRemark(trimToNull(remark));
        row.setCreateBy(userId);
        return row;
    }

    private static PpIdVo toVo(PpIdPO row) {
        PpIdVo vo = new PpIdVo();
        vo.setId(row.getId());
        vo.setPpid(row.getPpid());
        vo.setSpu(row.getSpu());
        vo.setProductModel(row.getProductModel());
        vo.setTotalQuantity(row.getTotalQuantity());
        vo.setAppliedQuantity(row.getAppliedQuantity());
        vo.setRemainingQuantity(row.getRemainingQuantity());
        vo.setStatus(row.getStatus());
        vo.setRemark(row.getRemark());
        vo.setCreateTime(row.getCreateTime());
        vo.setUpdateTime(row.getUpdateTime());
        return vo;
    }

    private static PpIdExportRow toExportRow(PpIdPO row) {
        PpIdExportRow vo = new PpIdExportRow();
        vo.setPpid(row.getPpid());
        vo.setSpu(row.getSpu());
        vo.setProductModel(row.getProductModel());
        vo.setTotalQuantity(row.getTotalQuantity());
        vo.setAppliedQuantity(row.getAppliedQuantity());
        vo.setRemainingQuantity(row.getRemainingQuantity());
        vo.setStatusText(row.getStatus() != null && row.getStatus() == ENABLED ? "可用" : "停用");
        vo.setRemark(row.getRemark());
        return vo;
    }

    /**
     * 单元格里的可申请总量。因为按文本读进来，被 Excel 存成数字的 "100.0" 也要认，但 100.5 就不是数量了。
     */
    private static Integer parseQuantity(String raw, List<String> issues) {
        String text = trimToNull(raw);
        if (text == null) {
            issues.add("可申请总量不能为空");
            return null;
        }
        BigDecimal value;
        try {
            value = new BigDecimal(text);
        } catch (NumberFormatException e) {
            issues.add("可申请总量不是数字：" + text);
            return null;
        }
        if (value.stripTrailingZeros().scale() > 0) {
            issues.add("可申请总量必须是整数：" + text);
            return null;
        }
        int quantity;
        try {
            quantity = value.intValueExact();
        } catch (ArithmeticException e) {
            issues.add("可申请总量超出可填范围：" + text);
            return null;
        }
        if (quantity < 1) {
            issues.add("可申请总量至少为 1，当前 " + quantity);
            return null;
        }
        if (quantity > QUANTITY_MAX) {
            issues.add("单行可申请总量上限 " + QUANTITY_MAX + "，当前 " + quantity + "；请拆成多行");
            return null;
        }
        return quantity;
    }

    private static String requireLength(String raw, String field, List<String> issues) {
        String text = trimToNull(raw);
        if (text == null) {
            issues.add(field + "不能为空");
            return null;
        }
        if (text.length() > NAME_MAX_LEN) {
            issues.add(field + "超过 " + NAME_MAX_LEN + " 个字符");
            return null;
        }
        return text;
    }

    private static String requireText(String raw, String field) {
        String text = trimToNull(raw);
        if (text == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, field + "不能为空");
        }
        return text;
    }

    /**
     * 模板与导出共用。文件名里的中文按 RFC 5987 传，否则部分浏览器会把中文名弄乱。
     */
    private static void writeExcel(HttpServletResponse response, String fileName, Class<?> head, List<?> rows) {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);
        try (OutputStream out = response.getOutputStream()) {
            EasyExcelFactory.write(out, head).autoCloseStream(Boolean.FALSE).sheet("PPID").doWrite(rows);
        } catch (IOException e) {
            log.error("Excel 写出失败: fileName={}", fileName, e);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "文件生成失败，请稍后重试");
        }
    }

    /** PPID + SPU 是池里的身份键。用二元 List 而不是拼串，省得分隔符与值内容冲突 */
    private static List<String> key(String ppid, String spu) {
        return List.of(ppid, spu);
    }

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        return text.isEmpty() ? null : text;
    }

    private static boolean hasText(String raw) {
        return trimToNull(raw) != null;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    /** count(*) 给 Long、sum() 给 DECIMAL，统一收成 Integer；聚合结果为 NULL 只出现在空分组，按 0 处理 */
    private static Integer asInt(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.valueOf(value.toString().trim());
    }

}
