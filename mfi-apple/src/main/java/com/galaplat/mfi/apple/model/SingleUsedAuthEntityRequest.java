package com.galaplat.mfi.apple.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 单个注册已使用认证实体（现场配置）。
 *
 * @param authEntity Base64 编码的认证实体内容，即下载文件里的 token 原文
 */
public record SingleUsedAuthEntityRequest(
        @JsonProperty("ppid") String ppid,
        @JsonProperty("uuid") String uuid,
        @JsonProperty("auth_entity") String authEntity) {
}
