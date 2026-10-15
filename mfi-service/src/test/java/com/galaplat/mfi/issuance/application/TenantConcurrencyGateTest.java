package com.galaplat.mfi.issuance.application;

import com.galaplat.mfi.identity.application.TenantConfigService;
import com.galaplat.mfi.identity.domain.TenantConfigKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 租户并发闸门。闸门本身是尽力而为，但"额度改了之后实际并发不能超过新额度"必须成立，
 * 否则多租户共用出口 IP 的隔离就形同虚设。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TenantConcurrencyGateTest {

    private static final long TENANT_ID = 7L;

    @Mock
    private TenantConfigService configService;

    private TenantConcurrencyGate gate;

    @BeforeEach
    void setUp() {
        gate = new TenantConcurrencyGate(configService);
    }

    private void permits(int value) {
        when(configService.intValue(TENANT_ID, TenantConfigKey.PIPELINE_CONCURRENT_PER_TENANT)).thenReturn(value);
    }

    @Test
    void callsBeyondTheLimitAreRefused() {
        permits(2);

        assertTrue(gate.tryAcquire(TENANT_ID));
        assertTrue(gate.tryAcquire(TENANT_ID));
        assertFalse(gate.tryAcquire(TENANT_ID));
    }

    @Test
    void loweringTheLimitStillCountsInFlightCalls() {
        permits(2);
        assertTrue(gate.tryAcquire(TENANT_ID));

        // 换掉 Semaphore 对象的话，在飞的额度会跟着旧对象丢掉，实际并发就超过新额度
        permits(1);
        assertFalse(gate.tryAcquire(TENANT_ID), "已占用 1 个额度时，新额度 1 不该再放一个进去");

        gate.release(TENANT_ID);
        assertTrue(gate.tryAcquire(TENANT_ID));
        assertFalse(gate.tryAcquire(TENANT_ID));
    }

    @Test
    void raisingTheLimitTakesEffectOnTheNextAcquire() {
        permits(1);
        assertTrue(gate.tryAcquire(TENANT_ID));
        assertFalse(gate.tryAcquire(TENANT_ID));

        permits(3);
        assertTrue(gate.tryAcquire(TENANT_ID));
        assertTrue(gate.tryAcquire(TENANT_ID));
        assertFalse(gate.tryAcquire(TENANT_ID));
    }

    @Test
    void releaseForAnUnknownTenantIsIgnored() {
        permits(1);

        // 拿不到额度的消费者也会走 finally 归还；此时槽位还不存在，凭空 release 不能把闸门放开
        gate.release(TENANT_ID);

        assertTrue(gate.tryAcquire(TENANT_ID));
        assertFalse(gate.tryAcquire(TENANT_ID));
    }

}
