package com.galaplat.mfi.credential;

import com.galaplat.mfi.apple.MfiSslContextBuilder;
import com.galaplat.mfi.common.api.ErrorCode;
import com.galaplat.mfi.common.exception.BizException;
import com.galaplat.mfi.dao.entity.TenantCredentialPO;
import com.galaplat.mfi.dao.mapper.TenantCredentialMapper;
import com.galaplat.mfi.model.vo.CredentialVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;

/**
 * 租户凭证的入库、读取与删除。明文证书与私钥只在本方法栈内存活，落库前一律加密。
 *
 * <p>替换成功的判定顺序：先解析 PEM → 再校验私钥与证书配对 → 再校验有效期，三项都过了才加密落库。
 * 任何一步失败都抛业务异常，库里的旧凭证不受影响。
 */
@Slf4j
@Service
public class CredentialService {

    private final TenantCredentialMapper credentialMapper;
    private final CredentialCipher cipher;

    public CredentialService(TenantCredentialMapper credentialMapper, CredentialCipher cipher) {
        this.credentialMapper = credentialMapper;
        this.cipher = cipher;
    }

    /**
     * 新增或替换凭证。每次成功替换 version+1，调用方（Controller）负责随后清掉客户端缓存。
     */
    @Transactional(rollbackFor = Exception.class)
    public CredentialVo replace(long tenantId, long operatorId, String certPem, String keyPem) {
        MfiSslContextBuilder.Material material = parseOrReject(certPem, keyPem);

        TenantCredentialPO existing = findRow(tenantId);
        TenantCredentialPO row = existing == null ? new TenantCredentialPO() : existing;
        row.setTenantId(tenantId);
        row.setCertCipher(cipher.encrypt(tenantId, CredentialCipher.FIELD_CERT, certPem));
        row.setKeyCipher(cipher.encrypt(tenantId, CredentialCipher.FIELD_KEY, keyPem));
        row.setEncryptKeyId(cipher.activeKeyId());
        row.setFingerprint(fingerprint(material.leaf()));
        row.setCertSubject(subject(material.leaf()));
        row.setCertNotAfter(LocalDateTime.ofInstant(material.leaf().getNotAfter().toInstant(), ZoneId.systemDefault()));
        row.setUpdateBy(operatorId);
        row.setVersion(existing == null ? 1 : existing.getVersion() + 1);

        if (existing == null) {
            credentialMapper.insert(row);
        } else {
            credentialMapper.updateById(row);
        }
        log.info("凭证已保存: tenantId={}, version={}, fingerprint={}", tenantId, row.getVersion(), row.getFingerprint());
        return toVo(row);
    }

    /** 凭证元数据；未配置时给明确的业务错误，让前端引导上传 */
    public CredentialVo metadata(long tenantId) {
        return toVo(requireRow(tenantId));
    }

    /**
     * 删除凭证。先 requireRow 保证"删不存在的东西"给出 B0404 而不是静默成功，再物理删掉密文行。
     */
    @Transactional(rollbackFor = Exception.class)
    public void remove(long tenantId) {
        TenantCredentialPO row = requireRow(tenantId);
        credentialMapper.deleteByTenant(tenantId);
        log.info("凭证已删除: tenantId={}, version={}", tenantId, row.getVersion());
    }

    /**
     * 供客户端工厂取明文材料。解密失败说明密文被搬到了别的租户或主密钥不对，属安全事件，单独记日志。
     */
    public CredentialMaterial material(long tenantId) {
        TenantCredentialPO row = requireRow(tenantId);
        String certPem;
        String keyPem;
        try {
            certPem = cipher.decrypt(tenantId, CredentialCipher.FIELD_CERT, row.getEncryptKeyId(), row.getCertCipher());
            keyPem = cipher.decrypt(tenantId, CredentialCipher.FIELD_KEY, row.getEncryptKeyId(), row.getKeyCipher());
        } catch (IllegalStateException e) {
            log.error("凭证解密失败，拒绝构造客户端: tenantId={}, fingerprint={}", tenantId, row.getFingerprint());
            throw new BizException(ErrorCode.CREDENTIAL_INVALID, "租户凭证无法解密，请重新上传证书与私钥");
        }
        return new CredentialMaterial(certPem, keyPem, row.getFingerprint(), row.getVersion());
    }

    /** 只读元数据用，缺行返回 null 而不抛错 */
    private TenantCredentialPO findRow(long tenantId) {
        return credentialMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<TenantCredentialPO>lambdaQuery()
                        .eq(TenantCredentialPO::getTenantId, tenantId));
    }

    private TenantCredentialPO requireRow(long tenantId) {
        TenantCredentialPO row = findRow(tenantId);
        if (row == null) {
            throw new BizException(ErrorCode.CREDENTIAL_MISSING, "该租户尚未配置 MFI 客户端证书，请先上传");
        }
        return row;
    }

    private MfiSslContextBuilder.Material parseOrReject(String certPem, String keyPem) {
        MfiSslContextBuilder.Material material;
        try {
            material = MfiSslContextBuilder.build(certPem, keyPem, null);
        } catch (IOException | GeneralSecurityException e) {
            throw new BizException(ErrorCode.INVALID_PARAM,
                    "证书或私钥解析失败：" + rootMessage(e) + "；请确认是 PEM 格式且私钥未加密");
        }
        if (!MfiSslContextBuilder.keyMatchesCertificate(material.leaf(), material.privateKey())) {
            throw new BizException(ErrorCode.INVALID_PARAM, "私钥与证书不配对，请核对是否为同一套材料");
        }
        LocalDateTime notAfter =
                LocalDateTime.ofInstant(material.leaf().getNotAfter().toInstant(), ZoneId.systemDefault());
        if (notAfter.isBefore(LocalDateTime.now())) {
            throw new BizException(ErrorCode.CREDENTIAL_EXPIRED, "证书已过期（到期时间 " + notAfter + "），请向苹果申请新证书");
        }
        return material;
    }

    private static String rootMessage(Throwable e) {
        Throwable cursor = e;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        if (message == null || message.isBlank()) {
            return cursor.getClass().getSimpleName();
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }

    private static String fingerprint(java.security.cert.X509Certificate certificate) {
        try {
            return HexFormat.of().withUpperCase()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                            .digest(certificate.getEncoded()));
        } catch (java.security.NoSuchAlgorithmException | java.security.cert.CertificateEncodingException e) {
            throw new IllegalStateException("证书指纹计算失败", e);
        }
    }

    private static String subject(java.security.cert.X509Certificate certificate) {
        String name = certificate.getSubjectX500Principal().getName();
        return name.length() <= 255 ? name : name.substring(0, 255);
    }

    private static CredentialVo toVo(TenantCredentialPO row) {
        CredentialVo vo = new CredentialVo();
        vo.setFingerprint(row.getFingerprint());
        vo.setCertSubject(row.getCertSubject());
        vo.setCertNotAfter(row.getCertNotAfter());
        vo.setVersion(row.getVersion());
        vo.setEncryptKeyId(row.getEncryptKeyId());
        vo.setUpdateTime(row.getUpdateTime());
        if (row.getCertNotAfter() != null) {
            vo.setDaysToExpire(Duration.between(LocalDateTime.now(), row.getCertNotAfter()).toDays());
        }
        return vo;
    }

}
