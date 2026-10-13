package com.galaplat.mfi.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.galaplat.mfi.dao.entity.TokenPO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

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
     *
     * <p>必须排掉 {@code DESTROYED}：人工销毁只改 status，register_status 仍停在 PENDING。
     * 少了这个条件，销毁过后的分片被重放时会把已经在苹果侧没了的实体再注册一遍，注定失败。
     */
    @Select("""
            select id, auth_entity_id, accessory_uuid from t_token
            where tenant_id = #{tenantId} and shard_id = #{shardId} and deleted = 0
              and status <> 'DESTROYED'
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
     *
     * <p>与 {@link #selectUnregistered} 同一口径：人工销毁过的行不算"漏注册"，否则销毁之后再重放
     * 会卡在这道收口校验上，永远走不到 FINISH。
     */
    @Select("""
            select count(*) from t_token
            where tenant_id = #{tenantId} and shard_id = #{shardId} and deleted = 0
              and status <> 'DESTROYED'
              and register_status <> 'SUCCESS'
            """)
    int countUnregistered(@Param("tenantId") long tenantId, @Param("shardId") long shardId);

    /**
     * 本分片还没拿到制造序列号的 token id，按 id 升序。
     *
     * <p>只挑空行是 SN 幂等的全部凭据：FINISH 重试时已经落过号的行不再参与，于是"重复执行"最多是
     * 给新出现的空行补号，绝不会把某一行已经交给厂商的 SN 换成另一个。Redis 计数器单调，跳过的号不回收
     * （沿用 MES 的取舍：宁可断号也不重号）。
     *
     * <p>排掉 {@code DESTROYED}：销毁过的实体不会再进厂商产线，给它发号等于白白吃掉一个序列号。
     */
    @Select("""
            select id from t_token
            where tenant_id = #{tenantId} and shard_id = #{shardId} and deleted = 0
              and status <> 'DESTROYED'
              and (manufacture_sn is null or manufacture_sn = '')
            order by id asc
            """)
    List<Long> selectSnMissing(@Param("tenantId") long tenantId, @Param("shardId") long shardId);

    /**
     * 一条语句批量落号。{@code rows} 只需带 id 与 manufactureSn 两列。
     *
     * <p>WHERE 再带一次"SN 为空"：与 {@link #selectSnMissing} 之间可能有并发（例如人工重放把同一分片
     * 又跑了一遍），条件更新让后写的一方直接落空而不是覆盖已发出的序列号。
     */
    @Update("""
            <script>
            update t_token
            set manufacture_sn = case id
            <foreach collection="rows" item="r"> when #{r.id} then #{r.manufactureSn}</foreach>
            end
            where tenant_id = #{tenantId}
              and (manufacture_sn is null or manufacture_sn = '')
              and id in
            <foreach collection="rows" item="r" open="(" separator="," close=")">#{r.id}</foreach>
            </script>
            """)
    int assignSn(@Param("tenantId") long tenantId, @Param("rows") List<TokenPO> rows);

    /**
     * 申请单的 token 按状态计数，读侧进度页与销毁前置检查都用它。
     */
    @Select("""
            select status, register_status, count(*) as token_rows from t_token
            where tenant_id = #{tenantId} and application_id = #{applicationId} and deleted = 0
            group by status, register_status
            """)
    List<Map<String, Object>> selectStatusSummary(@Param("tenantId") long tenantId,
                                                  @Param("applicationId") long applicationId);

    /**
     * 销毁候选：只有苹果已下发、还没作为 used 实体注册回去的 token 能销毁（注册即绑定到具体配件，
     * 苹果侧不再接受 unused 销毁）。按 id 升序取，返回的 auth_entity_id 属敏感数据，只在内存与苹果请求里存在。
     */
    @Select("""
            <script>
            select id, auth_entity_id, ppid from t_token
            where tenant_id = #{tenantId} and deleted = 0 and status = 'ISSUED'
              and application_id = #{applicationId}
              <if test="ppid != null and ppid != ''">and ppid = #{ppid}</if>
            order by id asc
            limit #{limit}
            </script>
            """)
    List<TokenPO> selectDestroyCandidates(@Param("tenantId") long tenantId,
                                          @Param("applicationId") long applicationId,
                                          @Param("ppid") String ppid, @Param("limit") int limit);

    /**
     * 苹果确认销毁后才落状态。带着 {@code status = 'ISSUED'} 条件：并发下另一路已在本地把行改成
     * SCRAPPED/DESTROYED 时不覆盖，重复提交同批 id 也只是第二次影响 0 行。
     */
    @Update("""
            <script>
            update t_token
            set status = 'DESTROYED'
            where tenant_id = #{tenantId} and status = 'ISSUED'
              and id in
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int markDestroyed(@Param("tenantId") long tenantId, @Param("ids") List<Long> ids);

    /**
     * 导出用的 keyset 分页：只按主键往前翻，不用 offset。
     *
     * <p>MES 的异步导出走 PageHelper 的 {@code limit offset,n}，一万行时后面的页要把前面的行反复扫一遍，
     * 而导出正发生在大单上；按 id 游标每页代价只与本页行数有关。列清单显式写出，其中 encoded_token 是
     * 厂商拿走的那份材料，只能出现在导出文件里，绝不进日志与列表响应。
     */
    @Select("""
            <script>
            select id, ppid, auth_entity_id, accessory_uuid, secret_key, manufacture_sn, encoded_token,
                   status, register_status, download_time, register_time
            from t_token
            where tenant_id = #{tenantId} and deleted = 0 and id &gt; #{afterId}
              <if test="applicationId != null">and application_id = #{applicationId}</if>
              <if test="ppid != null and ppid != ''">and ppid = #{ppid}</if>
              <if test="status != null and status != ''">and status = #{status}</if>
            order by id asc
            limit #{limit}
            </script>
            """)
    List<TokenPO> selectExportPage(@Param("tenantId") long tenantId, @Param("afterId") long afterId,
                                   @Param("applicationId") Long applicationId, @Param("ppid") String ppid,
                                   @Param("status") String status, @Param("limit") int limit);

}
