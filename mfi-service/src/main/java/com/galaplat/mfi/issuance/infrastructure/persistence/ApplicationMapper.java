package com.galaplat.mfi.issuance.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 申请单。通用 CRUD 之外只多一条聚合语句：申请单状态由分片状态算出来，写回必须是原子的单条 UPDATE，
 * 否则五个消费者同时聚合会互相覆盖。
 */
@Mapper
public interface ApplicationMapper extends BaseMapper<ApplicationPO> {

    /**
     * 回写聚合结果。{@code status <> #{status}} 既是幂等保护（同一状态重复聚合不再改写任何列），
     * 也保证终态一旦落定就不会被迟到的重复聚合拽回 RUNNING。
     *
     * <p>{@code finishTime} 与 {@code failReason} 只在有值时写：非终态聚合传 null，不能把已经落下的
     * 终态时间或失败原因清空。
     */
    @Update("""
            <script>
            update t_application
            set status = #{status},
                token_count = #{tokenCount}
                <if test="finishTime != null">, finish_time = #{finishTime}</if>
                <if test="failReason != null">, fail_reason = #{failReason}</if>
            where tenant_id = #{tenantId} and id = #{applicationId} and deleted = 0
              and status &lt;&gt; #{status}
            </script>
            """)
    int aggregateProgress(@Param("tenantId") long tenantId, @Param("applicationId") long applicationId,
                          @Param("status") String status, @Param("tokenCount") int tokenCount,
                          @Param("finishTime") LocalDateTime finishTime, @Param("failReason") String failReason);

    /**
     * 第一个真正被领取的阶段消息把申请单从 PENDING（已受理、还没人消费）推到 RUNNING（进行中）。
     *
     * <p>只在 {@code status = 'PENDING'} 时写，所以每条阶段消息最多产生一次 0 行影响的条件更新，
     * 既不会把聚合出来的终态改回进行中，也不会在五个阶段上造成写放大。
     */
    @Update("update t_application set status = 'RUNNING' "
            + "where tenant_id = #{tenantId} and id = #{applicationId} and deleted = 0 and status = 'PENDING'")
    int markRunning(@Param("tenantId") long tenantId, @Param("applicationId") long applicationId);

    /**
     * 人工重放后把单子拉回进行中。
     *
     * <p>终态一旦允许重放，上一轮的 {@code finish_time} 与 {@code fail_reason} 就成了过期信息，必须和状态
     * 一起清掉——否则页面会出现"进行中却写着失败原因"的自相矛盾，而 {@link #aggregateProgress} 出于
     * "不许把终态留痕抹掉"的考虑从不清这两列，只能由这次显式复位来做。
     *
     * <p>条件收窄到 FAILED / PARTIAL_FAILED：SUCCESS 不该被拽回进行中，本来就在推进的单则是 0 行影响。
     */
    @Update("update t_application set status = 'RUNNING', finish_time = null, fail_reason = null "
            + "where tenant_id = #{tenantId} and id = #{applicationId} and deleted = 0 "
            + "and status in ('FAILED','PARTIAL_FAILED')")
    int reopen(@Param("tenantId") long tenantId, @Param("applicationId") long applicationId);

}
