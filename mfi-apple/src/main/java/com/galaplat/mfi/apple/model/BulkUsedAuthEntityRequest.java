package com.galaplat.mfi.apple.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * 批量注册已使用认证实体（工厂配置）。
 *
 * <p>{@code auth_entities} 的形状是苹果定的：每个元素是一个**只有一项**的 map，
 * key 为 auth_entity_id（token 编号），value 为该 token 烧录到的配件 UUID。
 * 单批上限 1000，超出必须自行分批。
 */
public record BulkUsedAuthEntityRequest(
        @JsonProperty("ppid") String ppid,
        @JsonProperty("auth_entities") List<Map<String, String>> authEntities) {

    public static final int MAX_BATCH_SIZE = 1000;

}
