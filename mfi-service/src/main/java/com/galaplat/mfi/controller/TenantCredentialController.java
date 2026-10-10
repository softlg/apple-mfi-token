package com.galaplat.mfi.controller;

import com.galaplat.mfi.client.MfiProbeService;
import com.galaplat.mfi.client.MfiClientFactory;
import com.galaplat.mfi.credential.CredentialService;
import com.galaplat.mfi.model.request.CredentialUploadRequest;
import com.galaplat.mfi.model.vo.CredentialVerifyVo;
import com.galaplat.mfi.model.vo.CredentialVo;
import com.galaplat.mfi.security.TenantAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户 MFI 凭证管理。明文 PEM 只在本请求内存活，落库即加密；任何接口都不回显证书与私钥内容。
 *
 * <p>写操作 [owner] 限定，且成功后必须清掉客户端缓存，否则旧 SSLContext 会继续被复用。
 */
@Tag(name = "租户凭证")
@RestController
@RequestMapping("/api/tenant/credential")
public class TenantCredentialController {

    private final CredentialService credentialService;
    private final MfiClientFactory clientFactory;
    private final MfiProbeService probeService;

    public TenantCredentialController(CredentialService credentialService, MfiClientFactory clientFactory,
                                      MfiProbeService probeService) {
        this.credentialService = credentialService;
        this.clientFactory = clientFactory;
        this.probeService = probeService;
    }

    @Operation(summary = "凭证元数据", description = "只有指纹、Subject、有效期与版本号，不含证书与私钥")
    @GetMapping
    public CredentialVo metadata() {
        return credentialService.metadata(TenantAccess.requireTenantId());
    }

    @Operation(summary = "上传/替换凭证", description = "[owner] 先校验 PEM、配对与有效期，三项都通过才加密落库")
    @PutMapping
    public CredentialVo replace(@Valid @RequestBody CredentialUploadRequest request) {
        long tenantId = TenantAccess.requireTenantId();
        TenantAccess.requireOwner();
        CredentialVo vo = credentialService.replace(tenantId, TenantAccess.requireUserId(),
                request.getCertPem(), request.getKeyPem());
        clientFactory.evict(tenantId);
        return vo;
    }

    @Operation(summary = "删除凭证", description = "[owner] 删除后该租户无法再发起任何苹果调用")
    @DeleteMapping
    public void remove() {
        long tenantId = TenantAccess.requireTenantId();
        TenantAccess.requireOwner();
        credentialService.remove(tenantId);
        clientFactory.evict(tenantId);
    }

    @Operation(summary = "连通性自检", description = "[owner] 用一次真实双向握手验证证书是否被对端接受")
    @PostMapping("/verify")
    public CredentialVerifyVo verify() {
        long tenantId = TenantAccess.requireTenantId();
        TenantAccess.requireOwner();
        return probeService.verify(tenantId);
    }

}
