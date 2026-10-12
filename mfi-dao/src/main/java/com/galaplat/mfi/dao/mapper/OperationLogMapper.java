package com.galaplat.mfi.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.galaplat.mfi.dao.entity.OperationLogPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 操作留痕。只追加、不修改，所以除了进度时间线的读取，没有更新与删除方法。
 */
@Mapper
public interface OperationLogMapper extends BaseMapper<OperationLogPO> {

    /**
     * 一张申请单的时间线，取最近 {@code limit} 条并按时间倒序返回（调用方需要正序时自己翻）。
     *
     * <p>必须有上限：一张拆成上百个 PPID 分片的单，五个阶段加上每次重试都是留痕的，
     * 无上限地读进内存只为渲染一页进度，不划算。列清单就是进度页要渲染的那几列，
     * 其中 detail 按本表的约定不含凭证与 PPID 全量清单，可以直接给前端。
     */
    @Select("""
            select id, action, shard_id, operator_user_id, operator_name, quantity, detail, create_time
            from t_operation_log
            where tenant_id = #{tenantId} and application_id = #{applicationId}
            order by id desc
            limit #{limit}
            """)
    List<OperationLogPO> selectRecentTimeline(@Param("tenantId") long tenantId,
                                              @Param("applicationId") long applicationId,
                                              @Param("limit") int limit);

}
