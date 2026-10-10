package com.galaplat.mfi.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.galaplat.mfi.dao.entity.TokenFilePO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * token 文件（苹果下发的 xlsx）的登记与下载推进。
 *
 * <p>文件名列表由 {@code uk_file_tenant_request_name} 唯一约束兜住重复：同一个 request 被重复
 * 取列表时不会插出第二份文件，因此 FILE_NAME 阶段可以放心重跑。
 */
@Mapper
public interface TokenFileMapper extends BaseMapper<TokenFilePO> {

    /**
     * 登记文件名列表。用 {@code insert ignore} 而不是应用层查重：文件名列表可能被重复消费，
     * 而"已登记"从来不是错误，跳过即可。
     */
    @Insert("""
            <script>
            insert ignore into t_token_file (tenant_id, application_id, shard_id, ppid,
                                             apple_request_uuid, file_name, download_status, retry_count)
            values
            <foreach collection="rows" item="r" separator=",">
            (#{r.tenantId}, #{r.applicationId}, #{r.shardId}, #{r.ppid},
             #{r.appleRequestUuid}, #{r.fileName}, 'PENDING', 0)
            </foreach>
            </script>
            """)
    int insertBatchIgnore(@Param("rows") List<TokenFilePO> rows);

    /**
     * 本分片已登记的文件。按 id 升序，让重复下载的处理顺序稳定。
     */
    @Select("""
            select * from t_token_file
            where tenant_id = #{tenantId} and shard_id = #{shardId} and deleted = 0
            order by id asc
            """)
    List<TokenFilePO> selectByShard(@Param("tenantId") long tenantId, @Param("shardId") long shardId);

    /**
     * 标记下载成功并写入 token 数。条件里排除已 SUCCESS 的行，重复下载只会更新 0 行而不会把
     * download_time 抹成新的一次，便于判断"这一份是什么时候拿到的"。
     */
    @Update("""
            update t_token_file
            set download_status = 'SUCCESS',
                token_count = #{tokenCount},
                last_error = null,
                download_time = now()
            where tenant_id = #{tenantId} and id = #{fileId} and deleted = 0
              and download_status <> 'SUCCESS'
            """)
    int markDownloaded(@Param("tenantId") long tenantId, @Param("fileId") long fileId,
                       @Param("tokenCount") int tokenCount);

    /**
     * 记录本次下载失败。状态用条件表达式：重试还没耗尽仍然是 PENDING（等着再下载），
     * 只有调用方传入 exhausted 才落成 FAILED。
     */
    @Update("""
            update t_token_file
            set download_status = case when #{exhausted} = true then 'FAILED' else 'PENDING' end,
                retry_count = #{retryCount},
                last_error = #{lastError}
            where tenant_id = #{tenantId} and id = #{fileId} and deleted = 0
              and download_status <> 'SUCCESS'
            """)
    int markDownloadFailed(@Param("tenantId") long tenantId, @Param("fileId") long fileId,
                           @Param("retryCount") int retryCount, @Param("exhausted") boolean exhausted,
                           @Param("lastError") String lastError);

    /**
     * 还有多少文件没下载成功。0 才允许分片从 FILE_CONTENT 前进到 REGISTER。
     */
    @Select("""
            select count(*) from t_token_file
            where tenant_id = #{tenantId} and shard_id = #{shardId} and deleted = 0
              and download_status <> 'SUCCESS'
            """)
    int countNotDownloaded(@Param("tenantId") long tenantId, @Param("shardId") long shardId);

}
