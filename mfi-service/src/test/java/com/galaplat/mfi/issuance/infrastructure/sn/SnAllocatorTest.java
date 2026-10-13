package com.galaplat.mfi.issuance.infrastructure.sn;

import com.galaplat.mfi.identity.domain.TenantConfigKey;
import com.galaplat.mfi.shared.api.BizException;
import com.galaplat.mfi.identity.application.TenantConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 取号：一次 INCRBY 占一段连续流水，key 的后段就是 SN 前缀本身。
 *
 * <p>"计数器粒度等于前缀粒度"是这里唯一要钉住的不变式——它同时保证不重号（同前缀只有一条计数器）
 * 和不断号（同前缀的两次批量取号首尾相接）。
 */
@ExtendWith(MockitoExtension.class)
class SnAllocatorTest {

    private static final long TENANT_ID = 7L;
    private static final String PPID = "ABCDEF072783";
    private static final String PREFIX = "E072783B1010";
    private static final String COUNTER_KEY = "mfi:sn:" + TENANT_ID + ":" + PREFIX;
    private static final LocalDate PRODUCTION_DATE = LocalDate.of(2026, 10, 10);

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private TenantConfigService configService;

    @Test
    void batchTakesContiguousRangeEndingAtIncrbyResult() {
        when(configService.value(TENANT_ID, TenantConfigKey.SERIAL_NUMBER_PREFIX)).thenReturn("");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // 库里已经用到 0002，本次占 3 个：0003~0005
        when(valueOperations.increment(COUNTER_KEY, 3L)).thenReturn(5L);

        List<String> serials = allocator().allocate(TENANT_ID, PPID, 3, PRODUCTION_DATE);

        assertEquals(List.of(PREFIX + "0003", PREFIX + "0004", PREFIX + "0005"), serials);
        verify(redisTemplate).expire(COUNTER_KEY, Duration.ofDays(7));
    }

    @Test
    void firstBatchOfTheDayStartsAtOne() {
        when(configService.value(TENANT_ID, TenantConfigKey.SERIAL_NUMBER_PREFIX)).thenReturn("E");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(COUNTER_KEY, 2L)).thenReturn(2L);

        assertEquals(List.of(PREFIX + "0001", PREFIX + "0002"),
                allocator().allocate(TENANT_ID, PPID, 2, PRODUCTION_DATE));
    }

    @Test
    void counterKeyIsScopedByTenant() {
        // 两个租户各自的计数器互不影响：否则一方批量取号会让另一方莫名跳号
        when(configService.value(eq(8L), eq(TenantConfigKey.SERIAL_NUMBER_PREFIX))).thenReturn("");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("mfi:sn:8:" + PREFIX, 1L)).thenReturn(9L);

        assertEquals(List.of(PREFIX + "0009"), allocator().allocate(8L, PPID, 1, PRODUCTION_DATE));
    }

    @Test
    void tenantBrandBecomesPartOfBothSnAndKey() {
        when(configService.value(TENANT_ID, TenantConfigKey.SERIAL_NUMBER_PREFIX)).thenReturn("MFG7");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("mfi:sn:7:MFG7072783B1010", 1L)).thenReturn(1L);

        assertEquals(List.of("MFG7072783B10100001"), allocator().allocate(TENANT_ID, PPID, 1, PRODUCTION_DATE));
    }

    @Test
    void nonPositiveCountNeverTouchesRedis() {
        BizException failure = assertThrows(BizException.class,
                () -> allocator().allocate(TENANT_ID, PPID, 0, PRODUCTION_DATE));

        assertEquals("取号数量必须大于 0", failure.getMessage());
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void missingCounterResultIsReportedAsFailure() {
        when(configService.value(TENANT_ID, TenantConfigKey.SERIAL_NUMBER_PREFIX)).thenReturn("");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(any(), anyLong())).thenReturn(null);

        assertThrows(BizException.class, () -> allocator().allocate(TENANT_ID, PPID, 5, PRODUCTION_DATE));
        verify(redisTemplate, never()).expire(eq(COUNTER_KEY), eq(Duration.ofDays(7)));
    }

    private SnAllocator allocator() {
        return new SnAllocator(redisTemplate, configService);
    }

}
