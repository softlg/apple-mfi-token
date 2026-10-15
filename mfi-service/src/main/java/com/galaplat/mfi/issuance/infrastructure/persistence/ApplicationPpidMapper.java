package com.galaplat.mfi.issuance.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPpidPO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 申请分片，同时是异步流水线的推进位。一次申请拆出的分片数通常个位数，但批量插入仍走多值语句：
 * 省下的是一次次往返，而分片必须在申请事务里一次性落库。
 *
 * <p>每条状态跃迁都写成"带前态条件的 UPDATE"：影响行数为 0 说明这一行已被别人推进过，消费者据此
 * 判定重复投递并直接 ACK。消息是至少送达一次（at-least-once）的，这是不重复调苹果、不重复扣额度的
 * 唯一防线，不依赖 broker 的去重能力。
 *
 * <p>跃迁一律带 {@code stage} 条件：只有当前阶段的持有者可以改写，慢消费者的迟到回调不能把已经前进
 * 到下一阶段的分片拽回去。
 */
@Mapper
public interface ApplicationPpidMapper extends BaseMapper<ApplicationPpidPO> {

    /**
     * 多值插入。列清单显式带 tenant_id，租户拦截器便不会再追加。
     * id 交给自增，createTime/updateTime 用库默认值。
     */
    @Insert("""
            <script>
            insert into t_application_ppid (tenant_id, application_id, ppid, ppid_id, allocate_count,
                                           stage, stage_status, retry_count)
            values
            <foreach collection="rows" item="r" separator=",">
            (#{r.tenantId}, #{r.applicationId}, #{r.ppid}, #{r.ppidId}, #{r.allocateCount},
             #{r.stage}, #{r.stageStatus}, 0)
            </foreach>
            </script>
            """)
    int insertBatch(@Param("rows") List<ApplicationPpidPO> rows);

    /**
     * 某张申请单的全部分片，按拆分时占用的额度行顺序返回，前端进度页与导出都用这个顺序。
     */
    @Select("""
            select * from t_application_ppid
            where tenant_id = #{tenantId} and application_id = #{applicationId} and deleted = 0
            order by id asc
            """)
    List<ApplicationPpidPO> selectByApplication(@Param("tenantId") long tenantId,
                                                @Param("applicationId") long applicationId);

    /**
     * 领取分片：PENDING -&gt; PROCESSING。返回 0 就是没抢到（重复消息，或别的消费者正在处理），调用方直接 ACK。
     */
    @Update("""
            update t_application_ppid
            set stage_status = 'PROCESSING'
            where tenant_id = #{tenantId} and id = #{shardId} and deleted = 0
              and stage = #{stage} and stage_status = 'PENDING'
            """)
    int claim(@Param("tenantId") long tenantId, @Param("shardId") long shardId, @Param("stage") String stage);

    /**
     * 阶段完成并前进。{@code toStage} 为 null 表示本阶段就是终点（FINISH），只把状态置成
     * {@code toStageStatus}；否则切到下一阶段并回到 PENDING。
     *
     * <p>{@code nextExecuteTime} 传"立即可执行"：下一阶段的消息要到本事务提交后才投递，万一投递失败，
     * 兜底扫描按这一列把它补发出去。
     */
    @Update("""
            <script>
            update t_application_ppid
            set stage_status = #{toStageStatus},
                retry_count = 0,
                last_error = null,
                next_execute_time = #{nextExecuteTime}
                <if test="toStage != null">, stage = #{toStage}</if>
                <if test="requestUuid != null">, apple_request_uuid = #{requestUuid}</if>
            where tenant_id = #{tenantId} and id = #{shardId} and deleted = 0
              and stage = #{fromStage} and stage_status = 'PROCESSING'
            </script>
            """)
    int advance(@Param("tenantId") long tenantId, @Param("shardId") long shardId,
                @Param("fromStage") String fromStage, @Param("toStage") String toStage,
                @Param("toStageStatus") String toStageStatus,
                @Param("requestUuid") String requestUuid,
                @Param("nextExecuteTime") LocalDateTime nextExecuteTime);

    /**
     * 安排下一次执行：回到 PENDING 并写入未来的 next_execute_time。"苹果还没生成文件"的等待与
     * "本次失败要退避"都走这里，区别只在 retryCount 是否递增——由调用方算好传入，方法本身不 +1，
     * 这样等待类跃迁不会消耗重试额度。
     */
    @Update("""
            update t_application_ppid
            set stage_status = 'PENDING',
                retry_count = #{retryCount},
                next_execute_time = #{nextExecuteTime},
                last_error = #{lastError}
            where tenant_id = #{tenantId} and id = #{shardId} and deleted = 0
              and stage = #{stage} and stage_status = 'PROCESSING'
            """)
    int schedule(@Param("tenantId") long tenantId, @Param("shardId") long shardId, @Param("stage") String stage,
                 @Param("retryCount") int retryCount, @Param("nextExecuteTime") LocalDateTime nextExecuteTime,
                 @Param("lastError") String lastError);

    /**
     * 终态失败：不再自动重试，等人工介入。同一时刻消息进死信队列留痕。
     */
    @Update("""
            update t_application_ppid
            set stage_status = 'DEAD',
                next_execute_time = null,
                last_error = #{lastError}
            where tenant_id = #{tenantId} and id = #{shardId} and deleted = 0
              and stage = #{stage} and stage_status = 'PROCESSING'
            """)
    int markDead(@Param("tenantId") long tenantId, @Param("shardId") long shardId,
                 @Param("stage") String stage, @Param("lastError") String lastError);

    /**
     * 心跳续租：只把 {@code update_time} 顶到当前时刻，不碰阶段、状态、重试计数与 next_execute_time。
     *
     * <p>{@link #claim} 之后到 {@link #advance} 之间，这一行没有任何写入，而 FILE_CONTENT 与 REGISTER
     * 两跳是按文件、按批在循环里推进的，耗时上界由文件数决定。兜底扫描按 {@code update_time} 判断
     * 消费者还在不在，不续租就会把一片正在正常推进的分片提前放回队列。
     *
     * <p>返回 0 表示这一行已经不在本消费者手里（被扫描复位、被别的消费者领取或已被改写终态），
     * 调用方必须立刻让位——继续循环就是两个消费者同一跳各调一次苹果。
     */
    @Update("""
            update t_application_ppid
            set update_time = now()
            where tenant_id = #{tenantId} and id = #{shardId} and deleted = 0
              and stage = #{stage} and stage_status = 'PROCESSING'
            """)
    int touchProcessing(@Param("tenantId") long tenantId, @Param("shardId") long shardId,
                        @Param("stage") String stage);

    /**
     * 兜底扫描：把"该跑却没跑"的分片捞出来补发。
     *
     * <p>必须 {@code @InterceptorIgnore} —— 扫描是跨租户的全局动作，而租户拦截器在上下文缺失时会直接抛错。
     * 代价是这条 SQL 自己就是隔离边界：只取推进需要的列，消费端拿到行以后仍按行上的 tenant_id 绑定上下文。
     *
     * <p>{@code deadline} 是"现在减去一个宽限窗口"而不是当前时间：正常推进的分片同样是
     * PENDING + next_execute_time=刚刚，而它的消息此刻正在 broker 里飞；按 now 比较就会让扫描与消息
     * 抢同一行。宽限窗口让只有真正被漏掉的行（提交后投递失败、broker 丢消息、消费者崩溃）才被补发。
     *
     * <p>{@code next_execute_time is null} 也要捞：申请事务里插入的初始分片不带这一列。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            select id, tenant_id, application_id, stage, stage_status, next_execute_time, retry_count
            from t_application_ppid
            where deleted = 0 and stage_status = 'PENDING'
              and (next_execute_time is null or next_execute_time <= #{deadline})
            order by next_execute_time asc, id asc
            limit #{limit}
            """)
    List<ApplicationPpidPO> selectDueForRecovery(@Param("deadline") LocalDateTime deadline,
                                                 @Param("limit") int limit);

    /**
     * 把"卡在 PROCESSING 超过上限"的分片放回 PENDING。
     *
     * <p>这是消费者崩溃或长时间挂死后唯一的自愈路径：行停在 PROCESSING，而
     * {@link #selectDueForRecovery} 只看 PENDING，捞不到它，于是这一片永远没人再碰。
     *
     * <p>{@code cutoff} 判的是"这一行最后一次被写入的时刻"（{@code update_time} 由库的 ON UPDATE 维护），
     * 所以按文件、按批推进的处理器必须用 {@link #touchProcessing} 续租，否则一次跨多个文件的正常执行
     * 会被误判成消费者已经不在了。
     *
     * <p>复位之后的重复执行，代价在五个阶段上并不对称：FILE_CONTENT 与 REGISTER 是增量的
     * （已成功的文件与批次会被跳过），重复只是白花一次调用；APPLY 在 request id 落库前被复位，则会向苹果
     * 再多要一批 token，那批额度只能靠与苹果门户对账发现。所以超时要留足余量，不要把这里当常规去重。
     *
     * <p>不改 retry_count：崩溃不是业务失败，把它算进重试次数会让一个稳定推进的申请因为一次进程重启就进死信。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            update t_application_ppid
            set stage_status = 'PENDING'
            where deleted = 0 and stage_status = 'PROCESSING' and update_time < #{cutoff}
            limit #{limit}
            """)
    int resetStaleProcessing(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    /**
     * 一张申请单的分片按阶段状态计数，聚合申请单状态用。投影成 Map 与 {@code PpIdMapper.selectPoolStats}
     * 的既有做法一致，省一个只在内部用的投影类。
     */
    @Select("""
            select stage, stage_status, count(*) as shard_rows
            from t_application_ppid
            where tenant_id = #{tenantId} and application_id = #{applicationId} and deleted = 0
            group by stage, stage_status
            """)
    List<Map<String, Object>> selectStageSummary(@Param("tenantId") long tenantId,
                                                 @Param("applicationId") long applicationId);

    /**
     * 人工重放的候选：只捞 DEAD（终态失败）。
     *
     * <p>成功、进行中与等待中的分片都不在内——重放的功能定位是"死信需要人工介入"的那一条出口，
     * 把还在推进的分片一起改只会造成同一跳两个消费者。
     */
    @Select("""
            select id, application_id, ppid, allocate_count, stage, stage_status, retry_count,
                   apple_request_uuid, last_error
            from t_application_ppid
            where tenant_id = #{tenantId} and application_id = #{applicationId} and deleted = 0
              and stage_status = 'DEAD'
            order by id asc
            """)
    List<ApplicationPpidPO> selectDeadByApplication(@Param("tenantId") long tenantId,
                                                    @Param("applicationId") long applicationId);

    /**
     * 重放：DEAD 按当前阶段放回 PENDING，重试计数归零。
     *
     * <p>{@code last_error} 与 {@code next_execute_time} 一起清空：留着旧的死因会让下一次成功看起来仍然可疑，
     * 而 {@code next_execute_time = null} 正是"待投递"的既有语义（申请事务插入的初始分片同样是 null），
     * 万一这次人工投递失败，兜底扫描会按这一列把它补上，不会静默丢单。
     *
     * <p>阶段不变、{@code apple_request_uuid} 保留：停在 APPLY 的分片重放时靠已有的 request id 短路，
     * 不会向苹果重复要一批 token——这是替代 MES 那 11 个补偿端点的关键，而不是一键重新申请。
     */
    @Update("""
            update t_application_ppid
            set stage_status = 'PENDING',
                retry_count = 0,
                last_error = null,
                next_execute_time = null
            where tenant_id = #{tenantId} and id = #{shardId} and deleted = 0
              and stage_status = 'DEAD'
            """)
    int resetForRetry(@Param("tenantId") long tenantId, @Param("shardId") long shardId);

}
