package com.galaplat.mfi.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一个苹果认证实体（token）。
 *
 * <p>合并了 MES 的 t_token_detail 与 t_token_download_content：两表分开时推进要跨表判断
 * （MES 的 {@code existNotDownloadSuccess} 闸门），合并后一次条件 UPDATE 就能完成状态跃迁。
 *
 * <p>{@code authEntityId}、{@code encodedToken}、{@code accessoryUUID} 都是凭证材料，
 * 禁止进日志、错误消息与操作日志明细。
 */
@Data
@TableName("t_token")
public class TokenPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    private Long applicationId;

    /** 指向 {@link ApplicationPpidPO#getId()} */
    private Long shardId;

    private String ppid;

    /** 指向 {@link TokenFilePO#getId()} */
    private Long fileId;

    /** 苹果文件里的 Token ID */
    private String authEntityId;

    /** 注册时烧录到的配件 UUID，由本服务生成 */
    private String accessoryUUID;

    /** 苹果文件里的 CRC32（MES 明细表的同名字段填的就是它） */
    private String secretKey;

    /** 苹果文件里的 Base64 token 原文 */
    private String encodedToken;

    /** 制造序列号，P6 由 Redis 取号生成 */
    private String manufactureSn;

    /** ISSUED/REGISTERED/SCRAPPED/DESTROYED */
    private String status;

    /** NOT_REQUIRED/PENDING/PROCESSING/SUCCESS/FAILED */
    private String registerStatus;

    private LocalDateTime downloadTime;

    private LocalDateTime registerTime;

    /** 时间戳由库默认值与 ON UPDATE 维护：写策略 NEVER，避免把读出的旧值回写、抑制库侧自动递增 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;

    /** 时间戳由库默认值与 ON UPDATE 维护：写策略 NEVER，避免把读出的旧值回写、抑制库侧自动递增 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateTime;

    private Integer deleted;

}
