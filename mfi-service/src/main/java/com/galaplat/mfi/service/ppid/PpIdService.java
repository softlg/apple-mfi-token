package com.galaplat.mfi.service.ppid;

import com.galaplat.mfi.common.api.PageResult;
import com.galaplat.mfi.model.request.PpIdCreateRequest;
import com.galaplat.mfi.model.request.PpIdPageRequest;
import com.galaplat.mfi.model.request.PpIdUpdateRequest;
import com.galaplat.mfi.model.vo.PpIdAvailableVo;
import com.galaplat.mfi.model.vo.PpIdImportResultVo;
import com.galaplat.mfi.model.vo.PpIdStatsVo;
import com.galaplat.mfi.model.vo.PpIdVo;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

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
