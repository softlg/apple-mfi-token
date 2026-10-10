package com.galaplat.mfi.apple;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 苹果的"稍后再来"是 UTC 时刻而不是时长。这里要守住两条：换算必须落到本机时区，且任何输入都不能
 * 换来一个过去的时间——否则延迟档位会被算成 0，分片就变成毫秒级的轮询风暴。
 */
class MfiUtcTimeTest {

    @Test
    void utcTextBecomesLocalTimePlusSafetyMargin() {
        LocalDateTime result = MfiUtcTime.nextAttempt("2099-01-02 03:04:05.0");
        LocalDateTime expected = LocalDateTime.parse("2099-01-02T03:04:05")
                .atZone(ZoneId.of("UTC"))
                .withZoneSameInstant(ZoneId.systemDefault())
                .toLocalDateTime()
                .plusMinutes(1);

        assertEquals(expected, result);
    }

    @Test
    void surroundingWhitespaceAndMissingMillisStillParse() {
        assertEquals(MfiUtcTime.nextAttempt("2099-01-02 03:04:05.0"),
                MfiUtcTime.nextAttempt("  2099-01-02 03:04:05.0  "));
    }

    @Test
    void missingFieldFallsBackToOneHour() {
        assertAboutNow(MfiUtcTime.nextAttempt(null), Duration.ofHours(1));
        assertAboutNow(MfiUtcTime.nextAttempt("   "), Duration.ofHours(1));
    }

    @Test
    void unparseableFieldFallsBackToOneHour() {
        // 苹果偶发把这里写成秒数或 ISO 格式，猜不出就只能等默认时长
        assertAboutNow(MfiUtcTime.nextAttempt("3600"), Duration.ofHours(1));
        assertAboutNow(MfiUtcTime.nextAttempt("2099-01-02T03:04:05Z"), Duration.ofHours(1));
    }

    @Test
    void pastTimeNeverReturnsInThePast() {
        // 苹果侧时钟偏慢或队列积压时会给出一个已经过去的时间点
        assertAboutNow(MfiUtcTime.nextAttempt("2000-01-02 03:04:05.0"), Duration.ofHours(1));
        assertTrue(MfiUtcTime.nextAttempt("2000-01-02 03:04:05.0").isAfter(LocalDateTime.now()));
    }

    private static void assertAboutNow(LocalDateTime actual, Duration expectedOffset) {
        LocalDateTime lower = LocalDateTime.now().plus(expectedOffset).minusSeconds(5);
        LocalDateTime upper = LocalDateTime.now().plus(expectedOffset).plusSeconds(5);
        assertTrue(!actual.isBefore(lower) && !actual.isAfter(upper), "实际时刻=" + actual);
    }

}
