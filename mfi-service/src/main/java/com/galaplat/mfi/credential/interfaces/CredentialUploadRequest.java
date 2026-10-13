package com.galaplat.mfi.credential.interfaces;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 上传/替换租户 MFI 客户端凭证。只接受 PEM 文本，服务端加密后落库，明文即刻丢弃。
 */
@Data
public class CredentialUploadRequest {

    @NotBlank(message = "证书 PEM 不能为空")
    @Size(max = 32767, message = "证书内容过长")
    private String certPem;

    @NotBlank(message = "私钥 PEM 不能为空")
    @Size(max = 32767, message = "私钥内容过长")
    private String keyPem;

}
