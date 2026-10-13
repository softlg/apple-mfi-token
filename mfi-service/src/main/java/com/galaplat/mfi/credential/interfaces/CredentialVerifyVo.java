package com.galaplat.mfi.credential.interfaces;

import lombok.Data;

/**
 * 苹果连通性自检结果。握手成功即说明证书链、mTLS 与网络都通，业务返回码另算。
 */
@Data
public class CredentialVerifyVo {

    /** TLS 双向握手与 HTTP 往返是否成功 */
    private Boolean reachable;

    private Integer httpStatus;

    /** 苹果返回的错误码/文案，握手失败时是本地异常摘要；不含任何凭证内容 */
    private String detail;

    private String fingerprint;

}
