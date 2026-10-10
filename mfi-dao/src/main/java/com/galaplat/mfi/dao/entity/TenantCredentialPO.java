package com.galaplat.mfi.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户 MFI 客户端凭证（每租户一行，uk_credential_tenant）。
 *
 * <p>{@code certCipher} / {@code keyCipher} 是 AES-256-GCM 密文，明文永不入库、永不回显、永不进日志。
 * 展示只允许用 {@code fingerprint}/{@code certSubject}/{@code certNotAfter} 这三项元数据。
 */
@Data
@TableName("t_tenant_credential")
public class TenantCredentialPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    /** X.509 证书密文，Base64(iv || ciphertext || gcm_tag) */
    private String certCipher;

    /** 客户端私钥密文，同上 */
    private String keyCipher;

    /** 加密所用的主密钥标识，主密钥轮换后旧密文仍可解 */
    private String encryptKeyId;

    /** 证书 DER 的 SHA-256 指纹 */
    private String fingerprint;

    private String certSubject;

    private LocalDateTime certNotAfter;

    /** 每次替换递增，用于并发替换检测与审计 */
    private Integer version;

    /** 最后替换人 */
    private Long updateBy;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer deleted;

}
