package com.galaplat.mfi.apple.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 批量申请认证实体的请求体。
 *
 * @param ppid                     产品计划 ID
 * @param requestedAuthEntityCount 申请数量，苹果按每文件上限自行分片
 */
public record AuthEntityRequest(
        @JsonProperty("ppid") String ppid,
        @JsonProperty("requested_auth_entity_count") int requestedAuthEntityCount) {
}
