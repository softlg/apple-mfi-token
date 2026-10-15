package com.galaplat.mfi.issuance.application;

import com.galaplat.mfi.identity.domain.TenantConfigKey;
import com.galaplat.mfi.identity.application.TenantConfigService;
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
 * <p>这是尽力而为的限流，不是正确性不变量：额度调整与在飞的调用之间没有严格的排他窗口，短时间的
 * 实际并发可能高于或低于配置值。真正的正确性由分片 CAS 与 PPID 条件扣减保证，两者都不依赖这里。
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
        Slot slot = slots.compute(tenantId, (key, existing) -> {
            if (existing == null) {
                return new Slot(new ResizableSemaphore(permits), permits);
            }
            existing.resizeTo(permits);
            return existing;
        });
        return slot.semaphore.tryAcquire();
    }

    /** 归还额度。必须与成功的 tryAcquire 成对，放在 finally 里 */
    public void release(long tenantId) {
        Slot slot = slots.get(tenantId);
        if (slot != null) {
            slot.semaphore.release();
        }
    }

    /**
     * 一个租户当前生效的额度槽。
     *
     * <p>额度变更时只能就地增减许可数，不能换一个 {@code Semaphore} 对象：换对象会让在飞的调用
     * release 到新对象上，凭空多出几个许可，同一租户的实际并发就超过配置值——正是闸门要防的那件事。
     *
     * <p>{@code permits} 记录"上次生效的额度"，用来把配置值的变化换算成增减量。它只在
     * {@link ConcurrentHashMap#compute} 里读写（同一 key 的映射函数互斥），volatile 让槽位被别的
     * 线程读到的是最新值。
     */
    private static final class Slot {

        private final ResizableSemaphore semaphore;
        private volatile int permits;

        private Slot(ResizableSemaphore semaphore, int permits) {
            this.semaphore = semaphore;
            this.permits = permits;
        }

        private void resizeTo(int target) {
            if (target == permits) {
                return;
            }
            semaphore.resizeBy(target - permits);
            permits = target;
        }
    }

    /**
     * 可增减许可的信号量：{@code reducePermits} 是 protected，只能靠子类暴露，
     * 这是 JDK 里调低一个已在使用的信号量上限的唯一办法。
     */
    private static final class ResizableSemaphore extends Semaphore {

        private ResizableSemaphore(int permits) {
            super(permits, true);
        }

        /** 按差额增减。调低时许可数可以为负（表示"欠着的额度"），已占用的额度回收后自然回到目标值 */
        private void resizeBy(int delta) {
            if (delta > 0) {
                release(delta);
            } else if (delta < 0) {
                reducePermits(-delta);
            }
        }
    }

}
