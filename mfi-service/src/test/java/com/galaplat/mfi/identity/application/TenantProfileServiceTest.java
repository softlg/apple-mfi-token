package com.galaplat.mfi.identity.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.galaplat.mfi.apple.MfiEndpoints;
import com.galaplat.mfi.credential.domain.AppleClientProvider;
import com.galaplat.mfi.identity.infrastructure.persistence.TenantPO;
import com.galaplat.mfi.identity.infrastructure.persistence.TenantMapper;
import com.galaplat.mfi.identity.interfaces.TenantProfileRequest;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.galaplat.mfi.shared.api.BizException;

/**
 * 租户信息更新必须能把 technology 与 BaseURL 真正写回 NULL，否则"留空即回到官方地址"落不了地。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TenantProfileServiceTest {

    private static final long TENANT_ID = 1L;

    @Mock
    private TenantMapper tenantMapper;

    @Mock
    private AppleClientProvider clientFactory;

    private TenantProfileService service;

    @BeforeEach
    void setUp() {
        initTableInfo();
        service = new TenantProfileService(tenantMapper, clientFactory);
        when(tenantMapper.selectById(TENANT_ID)).thenReturn(tenantWithOverride());
        when(tenantMapper.update(ArgumentMatchers.isNull(), ArgumentMatchers.any())).thenReturn(1);
    }

    /**
     * lambdaUpdate 要把方法引用解析成列名，靠的是 MyBatis-Plus 的 TableInfo 缓存。
     * 这里没有 Spring 容器，得手工把实体灌进去，否则报 "can not find lambda cache"。
     */
    private static void initTableInfo() {
        if (com.baomidou.mybatisplus.core.metadata.TableInfoHelper.getTableInfo(TenantPO.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""), TenantPO.class);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void blankBaseUrlClearsTheStoredOverride() {
        service.updateProfile(TENANT_ID, request("租户甲", "", ""));

        ArgumentCaptor<LambdaUpdateWrapper<TenantPO>> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(tenantMapper).update(ArgumentMatchers.isNull(), captor.capture());
        String sqlSet = captor.getValue().getSqlSet();
        assertTrue(sqlSet.contains("apple_base_url="), "BaseURL 没进 SET 子句，旧覆盖值清不掉: " + sqlSet);
        assertTrue(sqlSet.contains("apple_technology="), sqlSet);
        assertTrue(sqlSet.contains("tenant_name="), sqlSet);
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(MfiEndpoints.TECHNOLOGY_DEFAULT),
                "apple_technology 是 NOT NULL 列，留空必须写哨兵值而不是 null");
        verify(clientFactory).evict(TENANT_ID);
    }

    @Test
    void rejectedTechnologyLeavesTheTenantUntouched() {
        TenantProfileRequest request = request("租户甲", "Bluetooth", "");
        org.junit.jupiter.api.Assertions.assertThrows(
                com.galaplat.mfi.shared.api.BizException.class, () -> service.updateProfile(TENANT_ID, request));
        verify(tenantMapper, org.mockito.Mockito.never())
                .update(ArgumentMatchers.isNull(), ArgumentMatchers.any());
    }

    private static TenantProfileRequest request(String name, String technology, String baseUrl) {
        TenantProfileRequest request = new TenantProfileRequest();
        request.setTenantName(name);
        request.setAppleTechnology(technology);
        request.setAppleBaseUrl(baseUrl);
        return request;
    }

    private static TenantPO tenantWithOverride() {
        TenantPO tenant = new TenantPO();
        tenant.setId(TENANT_ID);
        tenant.setTenantCode("T1");
        tenant.setTenantName("租户甲");
        tenant.setAppleTechnology("AirPlay Audio");
        tenant.setAppleBaseUrl("https://localhost:8443");
        tenant.setStatus(1);
        return tenant;
    }

}
