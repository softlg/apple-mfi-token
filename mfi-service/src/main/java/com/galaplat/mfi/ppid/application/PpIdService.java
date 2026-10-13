package com.galaplat.mfi.ppid.application;

import com.galaplat.mfi.shared.api.PageResult;
import com.galaplat.mfi.ppid.domain.PpIdAllocation;
import com.galaplat.mfi.ppid.interfaces.PpIdCreateRequest;
import com.galaplat.mfi.ppid.interfaces.PpIdPageRequest;
import com.galaplat.mfi.ppid.interfaces.PpIdUpdateRequest;
import com.galaplat.mfi.ppid.interfaces.PpIdAvailableVo;
import com.galaplat.mfi.ppid.interfaces.PpIdImportResultVo;
import com.galaplat.mfi.ppid.interfaces.PpIdStatsVo;
import com.galaplat.mfi.ppid.interfaces.PpIdVo;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * PPID 池：租户从苹果侧拿到的可申请额度在本系统里的唯一账本。
 *
 * <p>扣减（{@link #deduct}）由申请事务调用，而不是本服务的某个方法内部完成——额度必须与申请单、
 * 分片一起提交或回滚，所以调用方持有事务。本服务只保证扣减是数据库判定的条件更新。
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
     * <p>不加锁、不校验租户规则：判定余量的是随后的 {@link #deduct}。
     */
    List<PpIdAllocation> planAllocation(String productModel, String spu, int quantity);

    /**
     * 扣减一份额度的余量，由数据库用条件更新判定够不够：返回 false 就是这一份额度在这一刻不够或已被占用。
     *
     * <p>这里刻意不加 {@code @Transactional}：扣减必须参与调用方（申请）的事务，与申请单、分片同生同死。
     * 另起事务会把扣减变成不可回滚的独立提交，失败的申请就会吃掉额度。
     */
    boolean deduct(long tenantId, long ppidId, int quantity);

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
