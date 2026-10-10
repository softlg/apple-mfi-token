package com.galaplat.mfi.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.galaplat.mfi.dao.entity.TokenPO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * token 认证实体。
 *
 * <p>这里的写方法都假设调用方已经把 {@code accessory_uuid} 与 token 原文一起落库：注册阶段只读不写
 * uuid，因此重试注册时提交给苹果的还是同一个配件 UUID，不会每次重试都换一个。
 */
@Mapper
public interface TokenMapper extends BaseMapper<TokenPO> {

    /**
     * 批量落库下载到的 token。{@code insert ignore} 靠 {@code uk_token_tenant_entity} 去重：
     * 同一个文件被重复下载（消费者崩溃后重投）时不会插出两份 token。
     *
     * <p>返回值可能小于入参行数，差值就是"库里已有"的行数，调用方只把它当日志量级，不当错误。
     */
    @Insert("""
            <script>
            insert ignore into t_token (tenant_id, application_id, shard_id, ppid, file_id,
                                        auth_entity_id, accessory_uuid, secret_key, encoded_token,
                                        status, register_status, download_time)
            values
            <foreach collection="rows" item="r" separator=",">
            (#{r.tenantId}, #{r.applicationId}, #{r.shardId}, #{r.ppid}, #{r.fileId},
             #{r.authEntityId}, #{r.accessoryUUID}, #{r.secretKey}, #{r.encodedToken},
             'ISSUED', 'PENDING', now())
            </foreach>
            </script>
            """)
    int insertBatchIgnore(@Param("rows") List<TokenPO> rows);

    /**
     * 本分片还没注册成功的 token，只取注册需要的三列。按 id 升序切批，保证同一分片重跑时
     * 批次划分稳定——苹果侧看到的顺序与上一次一致。
     */
    @Select("""
            select id, auth_entity_id, accessory_uuid from t_token
            where tenant_id = #{tenantId} and shard_id = #{shardId} and deleted = 0
              and register_status <> 'SUCCESS'
            order by id asc
            """)
    List<TokenPO> selectUnregistered(@Param("tenantId") long tenantId, @Param("shardId") long shardId);

    /**
     * 一批注册成功后按 id 落状态。只把 PENDING/FAILED 改成 REGISTERED，已成功的行不受影响，
     * 因此同一批重复提交不会把 register_time 刷新成更晚的时间。
     */
    @Update("""
            <script>
            update t_token
            set status = 'REGISTERED',
                register_status = 'SUCCESS',
                register_time = now()
            where tenant_id = #{tenantId}
              and register_status &lt;&gt; 'SUCCESS'
              and id in
              <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int markRegistered(@Param("tenantId") long tenantId, @Param("ids") List<Long> ids);

    /**
     * 标记某批 token 注册失败，留待下一次重试；已经是 SUCCESS 的行不改。
     */
    @Update("""
            <script>
            update t_token
            set register_status = 'FAILED'
            where tenant_id = #{tenantId}
              and register_status &lt;&gt; 'SUCCESS'
              and id in
              <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int markRegisterFailed(@Param("tenantId") long tenantId, @Param("ids") List<Long> ids);

    /**
     * 申请单已落库的 token 数，用于回写 t_application.token_count。
     */
    @Select("""
            select count(*) from t_token
            where tenant_id = #{tenantId} and application_id = #{applicationId} and deleted = 0
            """)
    int countByApplication(@Param("tenantId") long tenantId, @Param("applicationId") long applicationId);

    /**
     * 分片落到的 token 数。FINISH 阶段用它判断"这一片到底有没有到货"。
     */
    @Select("""
            select count(*) from t_token
            where tenant_id = #{tenantId} and shard_id = #{shardId} and deleted = 0
            """)
    int countByShard(@Param("tenantId") long tenantId, @Param("shardId") long shardId);

    /**
     * 分片还没注册成功的 token 数，用 COUNT 而不是把行捞回来数——一个分片可能几千个 token，
     * 只为了判断有没有漏注册不值得把 auth_entity_id 全读进内存。
     */
    @Select("""
            select count(*) from t_token
            where tenant_id = #{tenantId} and shard_id = #{shardId} and deleted = 0
              and register_status <> 'SUCCESS'
            """)
    int countUnregistered(@Param("tenantId") long tenantId, @Param("shardId") long shardId);

}
