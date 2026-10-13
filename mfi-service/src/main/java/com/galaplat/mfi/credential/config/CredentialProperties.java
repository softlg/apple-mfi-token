package com.galaplat.mfi.credential.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 凭证加密配置。主密钥只从环境变量注入，不落 git、不落库、不落日志。
 *
 * <p>{@code keys} 允许同时挂多把密钥：轮换时旧密钥保留在环里，历史密文按
 * {@code t_tenant_credential.encrypt_key_id} 找到对应密钥继续解密，新写入一律用
 * {@code activeKeyId}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mfi.credential")
public class CredentialProperties {

    /** 新密文使用的主密钥标识 */
    private String activeKeyId = "v1";

    /** keyId -> Base64 编码的 256 位 AES 密钥 */
    private Map<String, String> keys = new LinkedHashMap<>();

}
