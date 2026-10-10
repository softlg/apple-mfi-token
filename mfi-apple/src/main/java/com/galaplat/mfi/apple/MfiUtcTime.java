package com.galaplat.mfi.apple;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 苹果给的两个时间字段（{@code retry_download_after}、{@code download_availability}）是 UTC 的
 * {@code yyyy-MM-dd HH:mm:ss.S}，不是"还要等多少秒"。移植 MES 的 UtcUtil 并改成直接产出下一次执行时刻。
 *
 * <p>在苹果给的时间上再加一分钟安全余量：苹果的"可下载时间"是它自己的生成完成时刻，两侧时钟与
 * 落盘顺序都有偏差，卡着点去取只会多撞一次未就绪。
 */
public final class MfiUtcTime {

    private static final Logger log = LoggerFactory.getLogger(MfiUtcTime.class);

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S");

    /** 字段缺失或解析不了时的兜底等待：与 MES 一致取 1 小时，宁可晚不可频繁空轮询 */
    private static final Duration DEFAULT_DELAY = Duration.ofHours(1);

    private static final Duration SAFETY_MARGIN = Duration.ofMinutes(1);

    private MfiUtcTime() {
    }

    /**
     * 把苹果的 UTC 时间换算成"本服务下一次去取的时刻"。
     *
     * @param utcText 苹果返回的原文，可为空
     * @return 未来的执行时刻；给出的时间已经过去时按兜底时长向后推，绝不返回过去的时间
     */
    public static LocalDateTime nextAttempt(String utcText) {
        LocalDateTime now = LocalDateTime.now();
        if (utcText == null || utcText.isBlank()) {
            return now.plus(DEFAULT_DELAY);
        }
        try {
            LocalDateTime parsed = LocalDateTime.parse(utcText.trim(), FORMATTER);
            LocalDateTime local = parsed.atZone(ZoneId.of("UTC"))
                    .withZoneSameInstant(ZoneId.systemDefault())
                    .toLocalDateTime()
                    .plus(SAFETY_MARGIN);
            return local.isAfter(now) ? local : now.plus(DEFAULT_DELAY);
        } catch (RuntimeException e) {
            log.warn("苹果时间字段无法解析，按默认时长等待: value={}, reason={}", utcText.trim(),
                    e.getClass().getSimpleName());
            return now.plus(DEFAULT_DELAY);
        }
    }

}
