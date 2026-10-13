package com.galaplat.mfi.issuance.domain;

import java.time.LocalDate;
import com.galaplat.mfi.issuance.infrastructure.sn.SnAllocator;

/**
 * 制造序列号（SN）的前段规则，移植自 MES 的 {@code SnUtil.generateSnPrefix}：
 * 企业段 + PPID 后 6 位 + 年字母 + 月 + 日，共 12 个字符，后接 4 位流水号得到 16 字符 SN。
 *
 * <p>年字母按 6 年一轮（25→A、26→B…31→A），所以同月同日在 6 年周期内会撞前缀。这不是本仓库新增的
 * 风险：SN 唯一性靠"前缀粒度 == 取号计数器粒度"保证（见 {@link SnAllocator}），撞前缀时计数器同一天
 * 不可能重复（日期段在 key 里）。跨 6 年的同日同前缀确实会重号，但那是苹果这条规则本身的重叠周期，
 * 真要改得先确认 §13.4 的 SN 规则，不能在这里单方面变更厂商已用的格式。
 */
public final class SnUtil {

    /** 企业段默认值：租户未配 SERIAL_NUMBER_PREFIX 时用它，与 MES 的固定 "E" 一致 */
    public static final String DEFAULT_ENTERPRISE_CODE = "E";

    private static final int PPID_SUFFIX_LENGTH = 6;

    /** 年编码轮回长度，与苹果/厂商约定一致 */
    private static final int YEAR_CODE_CYCLE = 6;

    /** 年编码起点：2025 年对应 A */
    private static final int YEAR_CODE_BASE = 25;

    private SnUtil() {
    }

    /**
     * 组装 SN 前段。
     *
     * @param ppid            完整 PPID，取后 6 位参与编码
     * @param enterpriseCode  企业段，空则用 {@link #DEFAULT_ENTERPRISE_CODE}
     * @param date            生产日期
     * @return 12 个字符的前段（PPID 不足 6 位时按其原样，长度会短一些）
     */
    public static String prefix(String ppid, String enterpriseCode, LocalDate date) {
        if (ppid == null || ppid.isBlank()) {
            throw new IllegalArgumentException("PPID 不能为空");
        }
        String brand = enterpriseCode == null || enterpriseCode.isBlank() ? DEFAULT_ENTERPRISE_CODE : enterpriseCode.trim();
        // 年编码用对日取模，负差值（如 2024 年）在 Java 里会得到负数，先归一
        int yearIndex = Math.floorMod(date.getYear() % 100 - YEAR_CODE_BASE, YEAR_CODE_CYCLE);
        char yearCode = (char) ('A' + yearIndex);
        return brand + ppidSuffix(ppid) + yearCode
                + String.format("%02d", date.getMonthValue())
                + String.format("%02d", date.getDayOfMonth());
    }

    /**
     * 取 PPID 末尾的字母数字段。先清洗再截断：PPID 从 Excel 进来时可能带空格或分隔符，
     * 直接 {@code substring} 会把分隔符算进 SN，两个不同 PPID 反而编出同一个前缀。
     */
    private static String ppidSuffix(String ppid) {
        String cleaned = ppid.trim().replaceAll("[^A-Za-z0-9]", "");
        if (cleaned.length() <= PPID_SUFFIX_LENGTH) {
            return cleaned;
        }
        return cleaned.substring(cleaned.length() - PPID_SUFFIX_LENGTH);
    }

}
