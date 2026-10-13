package com.galaplat.mfi.credential.interfaces;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户凭证元数据。绝不包含证书或私钥内容，只给可公开对账的指纹、Subject、到期时间与版本。
 */
@Data
public class CredentialVo {

    /** 证书 DER 的 SHA-256 指纹（hex） */
    private String fingerprint;

    private String certSubject;

    private LocalDateTime certNotAfter;

    /** 距到期天数，已过期为负数 */
    private Long daysToExpire;

    /** 凭证版本号，每次替换递增 */
    private Integer version;

    /** 加密所用主密钥标识，便于判断是否需要轮换 */
    private String encryptKeyId;

    private LocalDateTime updateTime;

}
