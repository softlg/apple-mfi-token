package com.galaplat.mfi.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 租户可配业务参数。
 *
 * <p>这一组配置的存在的意义是把 MES 里散落成 Java 常量的业务数字收拢到一处：试产单次 100
 * （TokenApplyServiceImpl:36）、单文件 2000（constant/TokenConstant:90）、注册批量 1000
 * （TokenRegisterListener:33）、重试上限 2（constant/TokenConstant:84）。
 *
 * <p>MES 的 {@code apply.quantity_ratio}（订单量 × 系数）不再保留：新模型里没有产品订单概念，
 * 申请上限由 PPID 余量与本枚举的 APPLY_MAX_QUANTITY 共同决定。
 */
@Getter
@RequiredArgsConstructor
public enum TenantConfigKey {

    /** 单次申请数量上限，0 表示不额外限制，只受 PPID 余量约束 */
    APPLY_MAX_QUANTITY("0"),

    /** 苹果单文件 token 数 */
    APPLE_TOKENS_PER_FILE("2000"),

    /** 批量注册单次提交的 token 数，苹果上限 1000 */
    APPLE_REGISTER_BATCH_SIZE("1000"),

    /** 单阶段最大重试次数，耗尽后进死信队列 */
    PIPELINE_MAX_RETRY("3"),

    /** 同一租户同时在飞的苹果请求数，防止单租户占满消费者与被苹果限流 */
    PIPELINE_CONCURRENT_PER_TENANT("2"),

    /** 制造序列号前缀，留空则用 PPID 派生 */
    SERIAL_NUMBER_PREFIX("");

    private final String defaultValue;

    public int asInt(String value) {
        return Integer.parseInt(value);
    }

}
