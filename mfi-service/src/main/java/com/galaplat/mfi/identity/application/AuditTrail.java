package com.galaplat.mfi.identity.application;

import com.galaplat.mfi.identity.domain.AuditFact;

import java.util.List;

/**
 * 操作与阶段跳转留痕。留痕是跨上下文的治理能力（申请、重放、导出、销毁都会写），
 * 但表结构与"只追加"的约束由身份上下文持有，其他上下文只见事实记录，不见 PO 与 Mapper。
 */
public interface AuditTrail {

    /**
     * 追加一条留痕。调用方负责让它和业务状态变更处在同一个事务里：留痕和业务写操作分叉，
     * 排障时就没有可信的时序。
     */
    void append(AuditFact fact);

    /**
     * 一张申请单最近的留痕，按发生顺序的**倒序**返回（库侧按自增 id 倒序取，上限由调用方给）。
     *
     * <p>必须有上限：一张拆成上百个 PPID 分片的单，五个阶段加上每次重试都是留痕的，
     * 无上限地读进内存只为渲染一页进度，不划算。
     */
    List<AuditFact> recentTimeline(long tenantId, long applicationId, int limit);

}
