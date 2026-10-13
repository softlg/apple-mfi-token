package com.galaplat.mfi.apple.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 苹果侧调用参数。
 *
 * <p>{@code trustedCaPem} 是给本地 mTLS 桩和自建代理用的额外信任锚，为空即使用 JDK 默认信任库
 * 访问真实苹果端点。它是公钥证书而非私钥，但仍不进 git，只在本地/环境变量里给。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mfi.apple")
public class AppleProperties {

    private String trustedCaPem;

}
