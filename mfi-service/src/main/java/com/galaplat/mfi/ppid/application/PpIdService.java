package com.galaplat.mfi.ppid.application;

import com.galaplat.mfi.shared.api.PageResult;
import com.galaplat.mfi.ppid.interfaces.PpIdCreateRequest;
import com.galaplat.mfi.ppid.interfaces.PpIdPageRequest;
import com.galaplat.mfi.ppid.interfaces.PpIdUpdateRequest;
import com.galaplat.mfi.ppid.interfaces.PpIdAvailableVo;
import com.galaplat.mfi.ppid.interfaces.PpIdImportResultVo;
import com.galaplat.mfi.ppid.interfaces.PpIdPlanVo;
import com.galaplat.mfi.ppid.interfaces.PpIdStatsVo;
import com.galaplat.mfi.ppid.interfaces.PpIdVo;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import com.galaplat.mfi.ppid.infrastructure.excel.PpIdImportRow;
import com.galaplat.mfi.ppid.infrastructure.persistence.PpIdMapper;

/**
 * PPID 池：租户从苹果侧拿到的可申请额度在本系统里的唯一账本。
 *
 * <p>这里只管账，不管扣减。扣减（{@code PpIdMapper.deduct}）必须发生在申请事务里，与申请单、分片一起
 * 提交或回滚，所以它属于 P4 的申请服务而不是本服务。
 */
public interface PpIdService {

    PageResult<PpIdVo> page(PpIdPageRequest request);

    PpIdVo create(PpIdCreateRequest request);

    /**
     * 修改可申请总量、状态与备注。ppid / spu / 产品型号是身份，不提供修改入口。
     */
    PpIdVo update(Long id, PpIdUpdateRequest request);

    /**
     * 停用。额度行不会物理删除：已申请量要留在库里对账，余量归零的行也只是自动停用。
     */
    void disable(Long id);

    /**
     * 按 产品型号 + SPU 聚合的池子总览。
     */
    List<PpIdStatsVo> stats();

    /**
     * 可分配预览。与实际分配同一份排序、同一份余量，所以 {@code sufficient} 可以直接当作申请能否成立
     * 的预判，但它不加锁——真正的判定在申请事务里的条件更新。
     */
    PpIdAvailableVo available(String productModel, String spu, Integer quantity);

    /**
     * 贪心拆分的实际入口，申请事务用它决定要扣哪几份额度。与 {@link #available} 共用同一条池查询与
     * 同一个排序，所以预览看到的拆分就是申请会做的拆分——两处各写一套分配顺序，是 MES 里
     * "预估说够、提交说不够"的来源。
     *
     * <p>不加锁、不校验租户规则：判定余量的是随后的条件更新。
     */
    List<PpIdPlanVo> planAllocation(String productModel, String spu, int quantity);

    PpIdImportResultVo importExcel(MultipartFile file);

    /**
     * 下载导入模板（只有表头，列序与 {@code PpIdImportRow} 一致）。
     */
    void writeTemplate(HttpServletResponse response);

    /**
     * 按当前筛选条件导出。行数超过上限会拒绝，提示收窄条件而不是静默截断。
     */
    void export(PpIdPageRequest filter, HttpServletResponse response);

}
