package com.galaplat.mfi.apple.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 销毁未使用的认证实体。不可逆，调用方必须已经拿到租户的明确确认。
 */
public record DestroyUnusedRequest(
        @JsonProperty("ppid") String ppid,
        @JsonProperty("auth_entity_ids") List<String> authEntityIds) {
}
