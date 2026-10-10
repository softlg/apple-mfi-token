package com.galaplat.mfi.credential;

/**
 * 解密后的凭证材料。
 *
 * <p>明文 PEM 只在内存里短暂存在，用于装配 TLS；绝不允许写日志、放进返回值或塞进任何 VO。
 * {@code toString} 因此被刻意压成指纹，避免 Lombok/record 默认实现把私钥带进日志。
 *
 * @param certPem     X.509 证书 PEM（可含中间证书链）
 * @param keyPem      PKCS#8 或 PKCS#1 私钥 PEM
 * @param fingerprint 证书 SHA-256 指纹，可安全展示与落日志
 * @param version     凭证版本号，用于判断缓存是否已过期
 */
public record CredentialMaterial(String certPem, String keyPem, String fingerprint, Integer version) {

    @Override
    public String toString() {
        return "CredentialMaterial{fingerprint=" + fingerprint + ", version=" + version + "}";
    }

}
