package com.galaplat.mfi.issuance.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SN 前段规则。逐条对着 MES 的 {@code SnUtil.generateSnPrefix} 断言，因为厂商产线已经按这 16 位排产，
 * 移植时任何一段变化都会让对不上号的货整批报废。
 */
class SnUtilTest {

    private static final LocalDate PRODUCTION_DATE = LocalDate.of(2026, 10, 10);

    @Test
    void prefixFollowsMesRule() {
        // 企业段 E + PPID 后 6 位 + 年字母（2026→B）+ 月 + 日
        assertEquals("E072783B1010", SnUtil.prefix("ABCDEF072783", "", PRODUCTION_DATE));
        assertEquals("E072783B1010", SnUtil.prefix("ABCDEF072783", null, PRODUCTION_DATE));
    }

    @Test
    void blankEnterpriseCodeFallsBackToDefault() {
        assertEquals(SnUtil.DEFAULT_ENTERPRISE_CODE, "E");
    }

    @Test
    void configuredBrandReplacesEnterpriseCode() {
        assertEquals("MFG7072783B1010", SnUtil.prefix("ABCDEF072783", "MFG7", PRODUCTION_DATE));
    }

    @Test
    void yearCodeCyclesEverySixYears() {
        assertEquals("E072783A1010", SnUtil.prefix("ABCDEF072783", "", LocalDate.of(2025, 10, 10)));
        assertEquals("E072783B1010", SnUtil.prefix("ABCDEF072783", "", LocalDate.of(2026, 10, 10)));
        assertEquals("E072783A1010", SnUtil.prefix("ABCDEF072783", "", LocalDate.of(2031, 10, 10)));
    }

    @Test
    void yearBeforeCycleBaseStillMapsIntoAlphabet() {
        // MES 用 (year-25)%6，2024 这类早于基年的年份会得到负偏移并编出 '@'；这里按 6 年轮回归一
        assertEquals("E072783F1010", SnUtil.prefix("ABCDEF072783", "", LocalDate.of(2024, 10, 10)));
    }

    @Test
    void monthAndDayAreZeroPadded() {
        assertEquals("E072783B0105", SnUtil.prefix("ABCDEF072783", "", LocalDate.of(2026, 1, 5)));
    }

    @Test
    void separatorsAreStrippedBeforeTakingSuffix() {
        // 清洗后再截后 6 位：否则分隔符会算进 SN，两个不同 PPID 编出同一个前缀
        assertEquals("E072783B1010", SnUtil.prefix("AB-CD_0727 83", "", PRODUCTION_DATE));
    }

    @Test
    void shortPpidIsUsedAsIs() {
        assertEquals("E12345B1010", SnUtil.prefix("12345", "", PRODUCTION_DATE));
    }

    @Test
    void blankPpidIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SnUtil.prefix(" ", "", PRODUCTION_DATE));
        assertThrows(IllegalArgumentException.class, () -> SnUtil.prefix(null, "", PRODUCTION_DATE));
    }

}
