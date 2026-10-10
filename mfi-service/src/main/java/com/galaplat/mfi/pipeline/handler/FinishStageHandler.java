package com.galaplat.mfi.pipeline.handler;

import com.galaplat.mfi.common.enums.Stage;
import com.galaplat.mfi.common.exception.BizException;
import com.galaplat.mfi.dao.mapper.TokenMapper;
import com.galaplat.mfi.pipeline.StageContext;
import com.galaplat.mfi.pipeline.StageHandler;
import com.galaplat.mfi.pipeline.StageOutcome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * FINISH：收口前的两道校验。申请单状态聚合与终态时间由 {@code StageRunner} 在这一跳落 SUCCESS 后统一做，
 * 因为"分片成功"和"整单聚合"必须是同一处决定，否则又会出现 MES 那种各监听器顺手改状态的覆盖。
 *
 * <p>制造序列号（SN）不在这里，属 P6：SN 规则还有 §13.4.1 未确认项，把未确认的规则塞进流水线只会让
 * 五个阶段的验收绑在一个待定决策上。
 */
@Slf4j
@Component
public class FinishStageHandler implements StageHandler {

    private final TokenMapper tokenMapper;

    public FinishStageHandler(TokenMapper tokenMapper) {
        this.tokenMapper = tokenMapper;
    }

    @Override
    public Stage stage() {
        return Stage.FINISH;
    }

    @Override
    public StageOutcome handle(StageContext context) {
        long tenantId = context.tenantId();
        long shardId = context.shardId();
        int tokens = tokenMapper.countByShard(tenantId, shardId);
        if (tokens == 0) {
            // 走到 FINISH 却一个 token 都没有，说明前面的阶段被绕过或数据被改坏；再重试也不会有货
            throw new BizException("分片未落到任何 token: shardId=" + shardId);
        }
        int unregistered = tokenMapper.countUnregistered(tenantId, shardId);
        if (unregistered > 0) {
            throw new BizException("分片仍有未注册的 token: shardId=" + shardId + ", count=" + unregistered);
        }

        int allocated = context.shard().getAllocateCount() == null ? 0 : context.shard().getAllocateCount();
        if (tokens != allocated) {
            // 苹果给的数量少于申请量是真实存在的情况（PPID 侧余额、生成失败），不足以判失败，
            // 但必须留下差异：后面的对账与导出都以这一行为准
            log.warn("到货数量与分配数量不一致: shardId={}, allocated={}, tokens={}, traceId={}",
                    shardId, allocated, tokens, context.traceId());
        }
        log.info("FINISH 校验通过: shardId={}, tokens={}, traceId={}", shardId, tokens, context.traceId());
        return StageOutcome.advance(Stage.FINISH);
    }

}
