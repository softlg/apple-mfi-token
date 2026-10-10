package com.galaplat.mfi.pipeline;

import com.galaplat.mfi.common.enums.Stage;
import com.galaplat.mfi.dao.entity.ApplicationPO;
import com.galaplat.mfi.dao.entity.ApplicationPpidPO;

/**
 * 一次阶段执行的输入。
 *
 * <p>分片与申请单都由 {@link StageRunner} 在领取成功后回库读取，处理器拿到的一定是库里的当前值——
 * 消息只带 ID 的意义就在这里：以库为准，broker 里的消息新旧无关紧要。
 *
 * @param tenantId     已绑定的租户，与 {@code shard.getTenantId()} 同源
 * @param stage        本次要处理（且已被 CAS 独占）的阶段
 * @param application  申请单
 * @param shard        分片行
 * @param traceId      申请单号，贯穿同步段与五个阶段的日志
 */
public record StageContext(long tenantId, Stage stage, ApplicationPO application,
                           ApplicationPpidPO shard, String traceId) {

    public long shardId() {
        return shard.getId();
    }

    public String ppid() {
        return shard.getPpid();
    }

}
