package com.galaplat.mfi.ppid.interfaces;

import com.galaplat.mfi.shared.api.PageResult;
import com.galaplat.mfi.ppid.interfaces.PpIdCreateRequest;
import com.galaplat.mfi.ppid.interfaces.PpIdPageRequest;
import com.galaplat.mfi.ppid.interfaces.PpIdUpdateRequest;
import com.galaplat.mfi.ppid.interfaces.PpIdAvailableVo;
import com.galaplat.mfi.ppid.interfaces.PpIdImportResultVo;
import com.galaplat.mfi.ppid.interfaces.PpIdStatsVo;
import com.galaplat.mfi.ppid.interfaces.PpIdVo;
import com.galaplat.mfi.ppid.application.PpIdService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * PPID 池。任何已登录成员都可读写本租户的额度，这里没有 owner 门槛：额度是申请用的账本，
 * 不是"能让全租户申请立即挂掉"的那类配置，所以不受 owner 位保护。
 */
@Tag(name = "PPID 池")
@RestController
@RequestMapping("/api/ppid")
public class PpIdController {

    private final PpIdService ppidService;

    public PpIdController(PpIdService ppidService) {
        this.ppidService = ppidService;
    }

    @Operation(summary = "分页查询", description = "PPID 模糊匹配，产品型号/SPU/状态精确匹配")
    @GetMapping("/page")
    public PageResult<PpIdVo> page(@Valid PpIdPageRequest request) {
        return ppidService.page(request);
    }

    @Operation(summary = "池子总览", description = "按 产品型号 + SPU 聚合，用于选池与余量核对")
    @GetMapping("/stats")
    public List<PpIdStatsVo> stats() {
        return ppidService.stats();
    }

    @Operation(summary = "可分配预览", description = "与实际分配同序同余量，sufficient=false 时申请必定失败")
    @GetMapping("/available")
    public PpIdAvailableVo available(@RequestParam String productModel, @RequestParam String spu,
                                     @RequestParam Integer quantity) {
        return ppidService.available(productModel, spu, quantity);
    }

    @Operation(summary = "新增额度", description = "PPID + SPU 构成身份键，重复提交会被拒绝")
    @PostMapping
    public PpIdVo create(@Valid @RequestBody PpIdCreateRequest request) {
        return ppidService.create(request);
    }

    @Operation(summary = "修改额度", description = "总量不得小于已申请量；并发扣过则要求刷新重试")
    @PutMapping("/{id}")
    public PpIdVo update(@PathVariable Long id, @Valid @RequestBody PpIdUpdateRequest request) {
        return ppidService.update(id, request);
    }

    @Operation(summary = "停用额度", description = "只置停用不删数据：已申请量要留在库里对账")
    @DeleteMapping("/{id}")
    public void disable(@PathVariable Long id) {
        ppidService.disable(id);
    }

    @Operation(summary = "Excel 导入", description = "整份文件或留或走：任一行不合法则不入库，errors 给出行号与原因")
    @PostMapping("/import")
    public PpIdImportResultVo importExcel(@RequestPart("file") MultipartFile file) {
        return ppidService.importExcel(file);
    }

    @Operation(summary = "下载导入模板", description = "列序即契约，不要增删或调换列")
    @GetMapping("/template")
    public void template(HttpServletResponse response) {
        ppidService.writeTemplate(response);
    }

    @Operation(summary = "导出", description = "按当前筛选条件导出，命中行数超上限时拒绝并要求收窄条件")
    @GetMapping("/export")
    public void export(@Valid PpIdPageRequest filter, HttpServletResponse response) {
        ppidService.export(filter, response);
    }

}
