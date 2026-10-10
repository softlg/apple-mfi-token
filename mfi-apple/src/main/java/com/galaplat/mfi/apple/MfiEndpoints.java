package com.galaplat.mfi.apple;

import java.util.Set;

/**
 * 苹果 MFI Software Token API 的地址常量。
 *
 * <p>{@code technology} 为 HomeKit 时走 sauth-external，其余走 swa；租户可用
 * {@code t_tenant.apple_base_url} 覆盖整个前缀（本地 mTLS 桩与自建代理即靠它落地）。
 */
public final class MfiEndpoints {

    public static final String BASE_URL_SWA = "https://swa.apple.com:443";
    public static final String BASE_URL_SAUTH = "https://sauth-external.apple.com:443";

    private static final String API_VERSION = "/api/v1.0/external";

    /** 批量申请认证实体 */
    public static final String PATH_AUTH_ENTITY_REQUESTS = API_VERSION + "/authEntityRequests";
    /** 按请求 ID 取文件名 / 下载文件 / 查 token 信息 */
    public static final String PATH_AUTH_ENTITIES = API_VERSION + "/authEntities";
    /** 批量注册已使用（工厂配置） */
    public static final String PATH_BULK_USED = API_VERSION + "/bulk/usedAuthEntities";
    /** 单个注册已使用（现场配置） */
    public static final String PATH_SINGLE_USED = API_VERSION + "/single/usedAuthEntity";
    /** 销毁未使用 */
    public static final String PATH_UNUSED = API_VERSION + "/unusedAuthEntities";
    /** 查询请求状态 */
    public static final String PATH_STATUS = API_VERSION + "/status";

    public static final String TECHNOLOGY_HOMEKIT = "HomeKit";

    /**
     * "不指定 technology"的存储哨兵，等于 {@code t_tenant.apple_technology} 的列默认值。
     * 该列是 NOT NULL，留空只能落成它，分流效果与非 HomeKit 一致：走 swa。
     */
    public static final String TECHNOLOGY_DEFAULT = "DEFAULT";

    /** 苹果现有的四种 technology，大小写与空格按苹果文档原样 */
    public static final Set<String> TECHNOLOGIES = Set.of(
            TECHNOLOGY_HOMEKIT, "AirPlay Audio", "DockKit", "Find My network");

    private MfiEndpoints() {
    }

    /**
     * technology 决定分流；未知或空值一律按 swa，与苹果现有行为一致。
     */
    public static String baseUrlFor(String technology) {
        return TECHNOLOGY_HOMEKIT.equalsIgnoreCase(technology) ? BASE_URL_SAUTH : BASE_URL_SWA;
    }

    /** 空值与 {@link #TECHNOLOGY_DEFAULT} 表示不指定（走 swa），非空必须命中 {@link #TECHNOLOGIES} */
    public static boolean isValidTechnology(String technology) {
        return technology == null || technology.isBlank() || TECHNOLOGY_DEFAULT.equalsIgnoreCase(technology.trim())
                || TECHNOLOGIES.contains(technology.trim());
    }

}
