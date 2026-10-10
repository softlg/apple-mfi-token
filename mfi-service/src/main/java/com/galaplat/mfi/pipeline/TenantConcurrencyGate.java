package com.galaplat.mfi.pipeline;

import com.galaplat.mfi.common.enums.TenantConfigKey;
import com.galaplat.mfi.service.tenant.TenantConfigService;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * 租户级并发闸门：限制同一租户同时在飞的苹果调用数。
 *
 * <p>为什么按租户限而不是只限全局：SaaS 是多租户共用出口 IP，一家厂商一次性提交几万 token 会把
 * 消费者线程与出口带宽占满，其他租户的申请只能干等；而苹果对同源 IP 有隐式限流，超频之后拿到的
 * 是难以定位的 4xx。闸门把"一家占满全局"变成"一家只占自己的额度"。
 *
 * <p>这是尽力而为的限流，不是正确性不变量： permits 变更时替换信号量，此刻仍在旧信号量里执行的调用
 * 会 release 到旧对象上，短时间内的实际并发可能高于或低于配置值。真正的正确性由分片 CAS 与
 * PPID 条件扣减保证，两者都不依赖这里。
 */
@Component
public class TenantConcurrencyGate {

    private final Map<Long, Slot> slots = new ConcurrentHashMap<>();
    private final TenantConfigService configService;

    public TenantConcurrencyGate(TenantConfigService configService) {
        this.configService = configService;
    }

    /**
     * 尝试占用一个额度。不阻塞：拿不到就返回 false，由调用方把分片排到稍后再来。
     *
     * <p>阻塞等待会把消费者线程压在锁上——延迟队列本来就是为此存在的，没必要再占着线程。
     */
    public boolean tryAcquire(long tenantId) {
        int permits = configService.intValue(tenantId, TenantConfigKey.PIPELINE_CONCURRENT_PER_TENANT);
        Slot slot = slots.compute(tenantId, (key, existing) ->
                existing != null && existing.permits == permits ? existing : new Slot(permits, new Semaphore(permits, true)));
        return slot.semaphore.tryAcquire();
    }

    /** 归还额度。必须与成功的 tryAcquire 成对，放在 finally 里 */
    public void release(long tenantId) {
        Slot slot = slots.get(tenantId);
        if (slot != null) {
            slot.semaphore.release();
        }
    }

    /** 一个租户当前生效的额度槽：记录 permits 才能在配置变更时认出旧槽 */
    private record Slot(int permits, Semaphore semaphore) {
    }

}
