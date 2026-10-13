package com.galaplat.mfi.ppid.domain;

import java.util.List;

/**
 * 贪心拆分出的一个取额度动作：从哪份额度取走多少。
 *
 * <p>它是 PPID 池对外的分配语义，申请上下文按这份计划扣额度、落分片。用领域记录而不是持久化对象，
 * 是为了让申请侧不需要认识 PPID 表和它的 Mapper。
 *
 * @param ppidId         额度行主键，条件更新（CAS 扣减）按这一行定位
 * @param ppid           额度号，落进分片与后续苹果调用
 * @param quantity       本次从这份额度取走的数量
 * @param remainingBefore 取走前这份额度的余量，供控制台核对拆分是否合理
 */
public record PpIdAllocation(long ppidId, String ppid, int quantity, int remainingBefore) {

    /** 这份计划可分配的总量 */
    public static int total(List<PpIdAllocation> plan) {
        return plan.stream().mapToInt(PpIdAllocation::quantity).sum();
    }

}
