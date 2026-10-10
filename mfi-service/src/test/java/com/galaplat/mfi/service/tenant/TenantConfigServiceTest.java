package com.galaplat.mfi.service.tenant;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.galaplat.mfi.common.enums.TenantConfigKey;
import com.galaplat.mfi.common.exception.BizException;
import com.galaplat.mfi.dao.entity.TenantConfigPO;
import com.galaplat.mfi.dao.mapper.TenantConfigMapper;
import com.galaplat.mfi.model.vo.TenantConfigItemVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 租户业务参数的生效值语义：缺键用全局默认，留空即撤掉覆盖。
 */
class TenantConfigServiceTest {

    private static final long TENANT_ID = 7L;

    /** 用 configKey 作主键模拟表内容，删除按 id 回落 */
    private final Map<String, TenantConfigPO> table = new LinkedHashMap<>();
    private TenantConfigMapper mapper;
    private TenantConfigService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mapper = Mockito.mock(TenantConfigMapper.class);
        Mockito.when(mapper.selectList(ArgumentMatchers.<Wrapper<TenantConfigPO>>any()))
                .thenAnswer(invocation -> new ArrayList<>(table.values()));
        Mockito.when(mapper.selectOne(ArgumentMatchers.<Wrapper<TenantConfigPO>>any()))
                .thenAnswer(invocation -> table.values().stream().findFirst().orElse(null));
        Mockito.doAnswer(invocation -> {
            TenantConfigPO po = invocation.getArgument(0);
            po.setId((long) table.size() + 1);
            table.put(po.getConfigKey(), po);
            return 1;
        }).when(mapper).insert(ArgumentMatchers.any(TenantConfigPO.class));
        Mockito.doAnswer(invocation -> {
            String configKey = invocation.getArgument(1);
            table.remove(configKey);
            return 1;
        }).when(mapper).deleteByKey(ArgumentMatchers.anyLong(), ArgumentMatchers.anyString());
        service = new TenantConfigService(mapper);
    }

    @Test
    void listShowsEveryKeyWithGlobalDefaults() {
        List<TenantConfigItemVo> items = service.list(TENANT_ID);
        assertEquals(TenantConfigKey.values().length, items.size());
        items.forEach(item -> {
            assertFalse(item.getOverridden(), item.getKey() + " 不该被标记为已覆盖");
            assertEquals(TenantConfigKey.valueOf(item.getKey()).getDefaultValue(), item.getEffectiveValue());
        });
    }

    @Test
    void blankValueClearsTheOverride() {
        service.update(TENANT_ID, Map.of(TenantConfigKey.APPLY_MAX_QUANTITY.name(), "100"));
        assertTrue(find(service.list(TENANT_ID), TenantConfigKey.APPLY_MAX_QUANTITY).getOverridden());

        TenantConfigItemVo reset = find(
                service.update(TENANT_ID, Map.of(TenantConfigKey.APPLY_MAX_QUANTITY.name(), "  ")),
                TenantConfigKey.APPLY_MAX_QUANTITY);
        assertFalse(reset.getOverridden());
        assertEquals("0", reset.getEffectiveValue());
        Mockito.verify(mapper).deleteByKey(ArgumentMatchers.eq(TENANT_ID),
                ArgumentMatchers.eq(TenantConfigKey.APPLY_MAX_QUANTITY.name()));
    }

    @Test
    void untouchedKeysKeepTheirValue() {
        service.update(TENANT_ID, Map.of(TenantConfigKey.APPLY_MAX_QUANTITY.name(), "50"));
        List<TenantConfigItemVo> items = service.update(TENANT_ID,
                Map.of(TenantConfigKey.PIPELINE_MAX_RETRY.name(), "5"));
        assertEquals("50", find(items, TenantConfigKey.APPLY_MAX_QUANTITY).getEffectiveValue());
        assertEquals("5", find(items, TenantConfigKey.PIPELINE_MAX_RETRY).getEffectiveValue());
        assertFalse(find(items, TenantConfigKey.SERIAL_NUMBER_PREFIX).getOverridden());
    }

    @Test
    void rejectsOutOfRangeAndUnknownKeys() {
        assertThrows(BizException.class, () -> service.update(TENANT_ID,
                Map.of(TenantConfigKey.APPLE_REGISTER_BATCH_SIZE.name(), "2000")));
        assertThrows(BizException.class, () -> service.update(TENANT_ID,
                Map.of(TenantConfigKey.PIPELINE_CONCURRENT_PER_TENANT.name(), "0")));
        assertThrows(BizException.class, () -> service.update(TENANT_ID, Map.of("NOT_A_KEY", "1")));
        assertThrows(BizException.class, () -> service.update(TENANT_ID,
                Map.of(TenantConfigKey.SERIAL_NUMBER_PREFIX.name(), "中文前缀")));
        assertTrue(table.isEmpty());
        Mockito.verify(mapper, Mockito.never()).insert(ArgumentMatchers.any(TenantConfigPO.class));
    }

    @Test
    void intValueFallsBackWhenStoredValueIsCorrupted() {
        table.put(TenantConfigKey.APPLY_MAX_QUANTITY.name(), row(TenantConfigKey.APPLY_MAX_QUANTITY, "not-a-number"));
        assertEquals(0, service.intValue(TENANT_ID, TenantConfigKey.APPLY_MAX_QUANTITY));
    }

    private static TenantConfigItemVo find(List<TenantConfigItemVo> items, TenantConfigKey key) {
        return items.stream().filter(item -> item.getKey().equals(key.name())).findFirst().orElseThrow();
    }

    private static TenantConfigPO row(TenantConfigKey key, String value) {
        TenantConfigPO po = new TenantConfigPO();
        po.setId(99L);
        po.setTenantId(TENANT_ID);
        po.setConfigKey(key.name());
        po.setConfigValue(value);
        return po;
    }

}
