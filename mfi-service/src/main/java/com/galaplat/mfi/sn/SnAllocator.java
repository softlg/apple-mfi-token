package com.galaplat.mfi.sn;

import com.galaplat.mfi.common.enums.TenantConfigKey;
import com.galaplat.mfi.common.exception.BizException;
import com.galaplat.mfi.service.tenant.TenantConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 制造序列号取号：一次 {@code INCRBY} 占住连续的一段流水，再按 id 顺序发号。
 *
 * <p>key 的后段就是 SN 的日期前段本身（{@code 企业段 + PPID 后6位 + 年字母 + 月日}），即**计数器粒度
 * 严格等于 SN 前缀粒度**。这一点与计划 §5.6 里"按 {@code ppid:spu} 分计数器"的写法不同，是有意为之：
 * 同一 PPID 在本仓库允许挂多个 SPU（{@code uk_ppid_tenant_ppid_spu}），而 SN 格式里没有 SPU 段，
 * 按 SPU 拆计数器会让两个 SPU 各自从 0001 开始、编出完全相同的 SN。SPU 拆 key 只解决"跳号难以解释"，
 * 代价是重号——重号比断号严重得多，厂商拿同一串 SN 刷两件产品时无法追溯。
 *
 * <p>宁可断号也不重号：号段在 Redis 占住之后、落库之前进程死掉，这段号就作废，不回收。
 */
@Slf4j
@Component
public class SnAllocator {

    /** 生产日按厂商所在时区取，不能用 JVM 默认时区：容器里常是 UTC，月末与年末边界会差出一天 */
    private static final ZoneId SN_DATE_ZONE = ZoneId.of("Asia/Shanghai");

    private static final String KEY_FORMAT = "mfi:sn:%d:%s";

    /** key 的日期段本身就在前缀里，TTL 只要长过一天即可；留 7 天是为了让排障时还能看到当天计数器 */
    private static final Duration KEY_TTL = Duration.ofDays(7);

    /** 流水号位数，沿用 MES 的 4 位：前段 12 字符 + 4 位流水 = 16 字符 SN */
    private static final int SEQUENCE_LENGTH = 4;

    private static final long SEQUENCE_MAX = 9_999L;

    private final StringRedisTemplate redisTemplate;
    private final TenantConfigService configService;

    public SnAllocator(StringRedisTemplate redisTemplate, TenantConfigService configService) {
        this.redisTemplate = redisTemplate;
        this.configService = configService;
    }

    /**
     * 连续取 {@code count} 个 SN。
     *
     * @return 按流水号升序的 SN，调用方按顺序与待落号的 token id 配对
     */
    public List<String> allocate(long tenantId, String ppid, int count) {
        return allocate(tenantId, ppid, count, LocalDate.now(SN_DATE_ZONE));
    }

    /** 生产日期单独入参只为测试：跨年/跨月边界的前缀必须能按指定日期断言，不能依赖跑测试的当天。 */
    List<String> allocate(long tenantId, String ppid, int count, LocalDate productionDate) {
        if (count <= 0) {
            throw new BizException("取号数量必须大于 0");
        }
        String prefix = SnUtil.prefix(ppid, configService.value(tenantId, TenantConfigKey.SERIAL_NUMBER_PREFIX),
                productionDate);
        String key = String.format(KEY_FORMAT, tenantId, prefix);

        Long last = redisTemplate.opsForValue().increment(key, count);
        if (last == null) {
            // Redis 客户端不通时抛的是 DataAccessException，到这里为 null 只可能是序列化问题，两种都不能吞
            throw new BizException("制造序列号取号失败，请稍后重试");
        }
        redisTemplate.expire(key, KEY_TTL);

        long first = last - count + 1;
        if (last > SEQUENCE_MAX) {
            // 与 MES 一致：超长不截断，让 SN 变长而不是丢号。撞到这里说明单个 PPID 单日产量超过 1 万，
            // 需要厂商确认新的流水位数，不能由服务端擅自截断成重复的 4 位。
            log.warn("SN 流水号已超出 {} 位，序列号长度会超过 16: tenantId={}, range={}-{}",
                    SEQUENCE_LENGTH, tenantId, first, last);
        }
        List<String> serials = new ArrayList<>(count);
        for (long sequence = first; sequence <= last; sequence++) {
            serials.add(prefix + String.format("%0" + SEQUENCE_LENGTH + "d", sequence));
        }
        // 日志里不带前缀与 key：前段含 PPID 后 6 位，属租户数据。要定位计数器时按号段回库看 manufacture_sn 即可。
        log.info("SN 取号成功: tenantId={}, count={}, range={}-{}", tenantId, count, first, last);
        return serials;
    }

}
