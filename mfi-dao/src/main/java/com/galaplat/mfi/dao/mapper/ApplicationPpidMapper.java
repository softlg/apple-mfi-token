package com.galaplat.mfi.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.galaplat.mfi.dao.entity.ApplicationPpidPO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 申请分片。一次申请拆出的分片数通常个位数，但批量插入仍走多值语句：省下的是一次次往返，
 * 而分片必须在申请事务里一次性落库。
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

}
