package com.galaplat.mfi.identity.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.galaplat.mfi.shared.api.ErrorCode;
import com.galaplat.mfi.identity.domain.TenantConfigKey;
import com.galaplat.mfi.shared.api.BizException;
import com.galaplat.mfi.identity.infrastructure.persistence.TenantConfigPO;
import com.galaplat.mfi.identity.infrastructure.persistence.TenantConfigMapper;
import com.galaplat.mfi.identity.interfaces.TenantConfigItemVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 租户级业务参数：读时按"租户有行用行值，没行用全局默认"合并，写时按 key 增量 upsert。
 *
 * <p>这里刻意不缓存生效值：申请与流水线每次取配置都要读库，QPS 远低于苹果接口调用，
 * 换来的是改配置立即生效、无需失效通知。
 */
@Slf4j
@Service
public class TenantConfigService {

    /** 制造序列号前缀允许的字符，长度受苹果侧 SN 规则约束 */
    private static final Pattern SN_PREFIX_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{0,16}$");

    private final TenantConfigMapper configMapper;

    public TenantConfigService(TenantConfigMapper configMapper) {
        this.configMapper = configMapper;
    }

    /** 全量配置项及其生效情况，前端一页展示 */
    public List<TenantConfigItemVo> list(long tenantId) {
        Map<String, TenantConfigPO> rows = loadRows(tenantId);
        List<TenantConfigItemVo> items = new ArrayList<>(TenantConfigKey.values().length);
        for (TenantConfigKey key : TenantConfigKey.values()) {
            TenantConfigPO row = rows.get(key.name());
            TenantConfigItemVo vo = new TenantConfigItemVo();
            vo.setKey(key.name());
            vo.setDefaultValue(key.getDefaultValue());
            vo.setOverridden(row != null);
            vo.setEffectiveValue(row == null ? key.getDefaultValue() : row.getConfigValue());
            vo.setRemark(row == null ? null : row.getRemark());
            items.add(vo);
        }
        return items;
    }

    /** 取生效值，供申请与流水线使用 */
    public String value(long tenantId, TenantConfigKey key) {
        TenantConfigPO row = findRow(tenantId, key.name());
        return row == null ? key.getDefaultValue() : row.getConfigValue();
    }

    /** 取生效的整数值。库里的值已经过 {@link #validate} 校验，解析失败说明数据被绕过写入 */
    public int intValue(long tenantId, TenantConfigKey key) {
        String effective = value(tenantId, key);
        try {
            return Integer.parseInt(effective.trim());
        } catch (NumberFormatException e) {
            log.error("租户配置不是合法整数，回落全局默认: tenantId={}, key={}, value={}", tenantId, key, effective);
            return Integer.parseInt(key.getDefaultValue().trim());
        }
    }

    /**
     * 增量更新。键必须是 {@link TenantConfigKey} 枚举名，值按各键的范围校验；未提交的键保持原样。
     * 值留空表示清除该租户的覆盖，回到全局默认。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<TenantConfigItemVo> update(long tenantId, Map<String, String> values) {
        Map<String, TenantConfigPO> rows = loadRows(tenantId);
        for (Map.Entry<String, String> entry : values.entrySet()) {
            TenantConfigKey key = parseKey(entry.getKey());
            TenantConfigPO row = rows.get(key.name());

            if (entry.getValue() == null || entry.getValue().isBlank()) {
                if (row != null) {
                    configMapper.deleteByKey(tenantId, key.name());
                    rows.remove(key.name());
                }
                continue;
            }

            String value = entry.getValue().trim();
            validate(key, value);
            if (row == null) {
                TenantConfigPO created = new TenantConfigPO();
                created.setTenantId(tenantId);
                created.setConfigKey(key.name());
                created.setConfigValue(value);
                configMapper.insert(created);
                rows.put(key.name(), created);
            } else {
                row.setConfigValue(value);
                configMapper.updateById(row);
            }
        }
        log.info("租户配置已更新: tenantId={}, keys={}", tenantId, values.keySet());
        return list(tenantId);
    }

    /**
     * 拦截器本就会按上下文改写 tenant_id，这里再显式带上条件，是为了让"返回的行确实属于入参租户"
     * 成为方法自身的保证——异步流水线里 tenantId 与上下文不一定同源。
     */
    private Map<String, TenantConfigPO> loadRows(long tenantId) {
        Map<String, TenantConfigPO> map = new LinkedHashMap<>();
        for (TenantConfigPO row : configMapper.selectList(Wrappers.<TenantConfigPO>lambdaQuery()
                .eq(TenantConfigPO::getTenantId, tenantId))) {
            map.put(row.getConfigKey(), row);
        }
        return map;
    }

    private TenantConfigPO findRow(long tenantId, String key) {
        return configMapper.selectOne(Wrappers.<TenantConfigPO>lambdaQuery()
                .eq(TenantConfigPO::getTenantId, tenantId)
                .eq(TenantConfigPO::getConfigKey, key));
    }

    private static TenantConfigKey parseKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            throw new BizException(ErrorCode.INVALID_PARAM, "配置键不能为空");
        }
        try {
            return TenantConfigKey.valueOf(rawKey.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.INVALID_PARAM, "不支持的配置键: " + rawKey);
        }
    }

    private static void validate(TenantConfigKey key, String value) {
        switch (key) {
            case APPLY_MAX_QUANTITY -> requireRange(key, value, 0, 100_000, "0 表示不额外限制");
            case APPLE_TOKENS_PER_FILE -> requireRange(key, value, 1, 2000, "苹果单文件固定 2000，一般无需修改");
            case APPLE_REGISTER_BATCH_SIZE -> requireRange(key, value, 1, 1000, "苹果批量注册硬上限 1000");
            case PIPELINE_MAX_RETRY -> requireRange(key, value, 0, 10, "重试上限过大会长时间占用队列");
            case PIPELINE_CONCURRENT_PER_TENANT -> requireRange(key, value, 1, 16, "过高会触发苹果限流");
            case SERIAL_NUMBER_PREFIX -> {
                if (!SN_PREFIX_PATTERN.matcher(value).matches()) {
                    throw new BizException(ErrorCode.INVALID_PARAM,
                            key.name() + " 只允许字母、数字、下划线与短横线，且不超过 16 个字符");
                }
            }
        }
    }

    private static void requireRange(TenantConfigKey key, String value, int min, int max, String remark) {
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.INVALID_PARAM, key.name() + " 必须是整数（" + remark + "）");
        }
        if (parsed < min || parsed > max) {
            throw new BizException(ErrorCode.INVALID_PARAM,
                    key.name() + " 取值范围 " + min + "~" + max + "（" + remark + "）");
        }
    }

}
