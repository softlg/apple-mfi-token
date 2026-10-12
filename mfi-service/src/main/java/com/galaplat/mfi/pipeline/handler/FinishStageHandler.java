package com.galaplat.mfi.pipeline.handler;

import com.galaplat.mfi.common.enums.Stage;
import com.galaplat.mfi.common.exception.BizException;
import com.galaplat.mfi.dao.entity.TokenPO;
import com.galaplat.mfi.dao.mapper.TokenMapper;
import com.galaplat.mfi.pipeline.StageContext;
import com.galaplat.mfi.pipeline.StageHandler;
import com.galaplat.mfi.pipeline.StageOutcome;
import com.galaplat.mfi.sn.SnAllocator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * FINISH：补齐制造序列号，再做收口的两道校验。申请单状态聚合与终态时间由 {@code StageRunner} 在这一跳
 * 落 SUCCESS 后统一做，因为"分片成功"和"整单聚合"必须是同一处决定，否则又会出现 MES 那种各监听器顺手改状态的覆盖。
 *
 * <p>SN 只给"还没有号"的行发（{@code selectSnMissing}），所以这一跳天然可重入：FINISH 重试时已落号的行
 * 不参与，最多给新出现的空行补号，绝不会把某一行已经交给厂商的序列号换成另一个。取号与落号之间崩溃会作废
 * 一段号——沿用 MES 的取舍，宁可断号也不重号。
 */
@Slf4j
@Component
public class FinishStageHandler implements StageHandler {

    /** 单条 case-when 更新的最大行数，限制一条语句的体积；分片行数是 2000 量级，一次一段 */
    private static final int ASSIGN_CHUNK_SIZE = 500;

    private final TokenMapper tokenMapper;
    private final SnAllocator snAllocator;

    public FinishStageHandler(TokenMapper tokenMapper, SnAllocator snAllocator) {
        this.tokenMapper = tokenMapper;
        this.snAllocator = snAllocator;
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

        assignMissingSerialNumbers(context);

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

    /**
     * 给本分片缺号的 token 连续取号并落库。
     *
     * <p>取号数量与落库数量可以不相等（并发下有行刚被别人落过号，条件更新落空），差值只记日志不判失败：
     * 那一段流水作废，符合"宁可断号"的取舍。
     */
    private void assignMissingSerialNumbers(StageContext context) {
        long tenantId = context.tenantId();
        long shardId = context.shardId();
        List<Long> missingIds = tokenMapper.selectSnMissing(tenantId, shardId);
        if (missingIds.isEmpty()) {
            return;
        }
        List<String> serials = snAllocator.allocate(tenantId, context.ppid(), missingIds.size());
        List<TokenPO> rows = new ArrayList<>(missingIds.size());
        for (int i = 0; i < missingIds.size(); i++) {
            TokenPO row = new TokenPO();
            row.setId(missingIds.get(i));
            row.setManufactureSn(serials.get(i));
            rows.add(row);
        }

        int assigned = 0;
        for (int from = 0; from < rows.size(); from += ASSIGN_CHUNK_SIZE) {
            int to = Math.min(from + ASSIGN_CHUNK_SIZE, rows.size());
            assigned += tokenMapper.assignSn(tenantId, rows.subList(from, to));
        }
        if (assigned != rows.size()) {
            log.warn("SN 落库行数少于取号行数，差额是并发下已被其它执行落号的行: shardId={}, allocated={}, assigned={}",
                    shardId, rows.size(), assigned);
        }
        log.info("SN 分配完成: shardId={}, missing={}, assigned={}, traceId={}",
                shardId, missingIds.size(), assigned, context.traceId());
    }

}

