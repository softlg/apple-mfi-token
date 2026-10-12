package com.galaplat.mfi.dao.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.galaplat.mfi.dao.entity.ExportTaskPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 导出任务。状态跃迁全部写成带前态的条件更新，与分片推进同一个纪律：任务可能被线程池执行两次
 * （进程重启后旧任务仍在库里），只有把 PENDING 抢到 PROCESSING 的那次才允许真的去读数据、写文件。
 */
@Mapper
public interface ExportTaskMapper extends BaseMapper<ExportTaskPO> {

    @Select("""
            select * from t_export_task
            where tenant_id = #{tenantId} and task_no = #{taskNo} and deleted = 0
            """)
    ExportTaskPO selectByTaskNo(@Param("tenantId") long tenantId, @Param("taskNo") String taskNo);

    /**
     * 领取任务：PENDING → PROCESSING。返回 0 表示这一份已经被别的线程（或重启前的自己）拿走，直接放弃。
     */
    @Update("""
            update t_export_task
            set status = 'PROCESSING'
            where tenant_id = #{tenantId} and id = #{id} and deleted = 0 and status = 'PENDING'
            """)
    int markProcessing(@Param("tenantId") long tenantId, @Param("id") long id);

    /**
     * 成功收口。只从 PROCESSING 前进：任务被判定为僵死并置 FAILED 之后，慢慢跑完的那一份不能又把它改成成功，
     * 否则用户看到的是"先失败后成功"的下载链接，比直接失败更难解释。
     */
    @Update("""
            update t_export_task
            set status = 'SUCCESS', file_path = #{filePath}, total_rows = #{totalRows},
                finish_time = now(), fail_reason = null
            where tenant_id = #{tenantId} and id = #{id} and deleted = 0 and status = 'PROCESSING'
            """)
    int markSuccess(@Param("tenantId") long tenantId, @Param("id") long id,
                    @Param("filePath") String filePath, @Param("totalRows") int totalRows);

    @Update("""
            update t_export_task
            set status = 'FAILED', fail_reason = #{failReason}, finish_time = now()
            where tenant_id = #{tenantId} and id = #{id} and deleted = 0 and status in ('PENDING', 'PROCESSING')
            """)
    int markFailed(@Param("tenantId") long tenantId, @Param("id") long id, @Param("failReason") String failReason);

    /**
     * 把"执行进程已经不在了"的僵死任务判失败：停在 PROCESSING 超过 cutoff 就说明那次执行再也不会回来了
     * （发布重启、OOM、机器下线），不处理就永远卡在 PROCESSING，用户只能干等。
     *
     * <p>跨租户扫描，因此必须 {@code @InterceptorIgnore}；条件里带 tenant_id 输出列由调用方按行绑定上下文。
     * 这里不重启执行，只置失败——导出文件可能只写了一半，重跑要用户重新发起，避免自动重放一份没被要求的下载。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            update t_export_task
            set status = 'FAILED', fail_reason = '执行进程已不在，任务未完成，请重新发起导出', finish_time = now()
            where deleted = 0 and status = 'PROCESSING' and update_time < #{cutoff}
            limit #{limit}
            """)
    int failStaleProcessing(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

}
