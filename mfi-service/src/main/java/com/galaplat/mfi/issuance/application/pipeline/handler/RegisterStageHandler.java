package com.galaplat.mfi.issuance.application.pipeline.handler;

import com.galaplat.mfi.apple.MfiTokenClient;
import com.galaplat.mfi.apple.model.BulkUsedAuthEntityRequest;
import com.galaplat.mfi.apple.model.MfiResponse;
import com.galaplat.mfi.credential.domain.AppleClientProvider;
import com.galaplat.mfi.issuance.domain.Stage;
import com.galaplat.mfi.identity.domain.TenantConfigKey;
import com.galaplat.mfi.shared.api.BizException;
import com.galaplat.mfi.issuance.infrastructure.persistence.TokenPO;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPpidMapper;
import com.galaplat.mfi.issuance.infrastructure.persistence.TokenMapper;
import com.galaplat.mfi.issuance.domain.StageContext;
import com.galaplat.mfi.issuance.domain.StageHandler;
import com.galaplat.mfi.issuance.domain.StageOutcome;
import com.galaplat.mfi.identity.application.TenantConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * REGISTER：把下载到的 token 作为"已使用认证实体"批量注册回苹果（工厂配置场景）。
 *
 * <p>注册是不可逆动作：注册之后这个 token 在苹果侧就绑定了配件 UUID，不能再退回未使用状态。
 * 所以这里只读已经落库的 accessory_uuid，不重新生成，也不在失败时改动它。
 *
 * <p>逐批推进而不是一批失败就整片重来：每批成功即单独落库，重试时只补没成功的那几批。
 */
@Slf4j
@Component
public class RegisterStageHandler implements StageHandler {

    private final AppleClientProvider clientFactory;
    private final TokenMapper tokenMapper;
    private final TenantConfigService configService;
    private final ApplicationPpidMapper shardMapper;

    public RegisterStageHandler(AppleClientProvider clientFactory, TokenMapper tokenMapper,
                               TenantConfigService configService, ApplicationPpidMapper shardMapper) {
        this.clientFactory = clientFactory;
        this.tokenMapper = tokenMapper;
        this.configService = configService;
        this.shardMapper = shardMapper;
    }

    @Override
    public Stage stage() {
        return Stage.REGISTER;
    }

    @Override
    public StageOutcome handle(StageContext context) throws IOException {
        long tenantId = context.tenantId();
        long shardId = context.shardId();
        List<TokenPO> pending = tokenMapper.selectUnregistered(tenantId, shardId);
        if (pending.isEmpty()) {
            log.info("本分片没有待注册的 token，直接前进: shardId={}", shardId);
            return StageOutcome.advance(Stage.REGISTER);
        }

        int batchSize = Math.min(
                configService.intValue(tenantId, TenantConfigKey.APPLE_REGISTER_BATCH_SIZE),
                BulkUsedAuthEntityRequest.MAX_BATCH_SIZE);
        MfiTokenClient client = clientFactory.forTenant(tenantId);

        int registered = 0;
        for (int from = 0; from < pending.size(); from += batchSize) {
            // 每批之前续租：注册是不可逆动作，两个消费者同抢一跳只会让后半批撞上"实体已是 used"的错误。
            // 这一跳的耗时上界是"批数 × 单批苹果调用"，只靠 claim 时的那次写入撑不住。
            if (shardMapper.touchProcessing(tenantId, shardId, Stage.REGISTER.name()) == 0) {
                log.warn("分片租约已不在本消费者手里，停止后续批次: shardId={}, registered={}, traceId={}",
                        shardId, registered, context.traceId());
                return StageOutcome.waitUntil(Stage.REGISTER, LocalDateTime.now(), "分片租约已被接管");
            }
            List<TokenPO> chunk = pending.subList(from, Math.min(from + batchSize, pending.size()));
            registered += registerOneBatch(client, context, chunk);
        }
        log.info("注册完成: shardId={}, registered={}, traceId={}",
                shardId, registered, context.traceId());
        return StageOutcome.advance(Stage.REGISTER);
    }

    private int registerOneBatch(MfiTokenClient client, StageContext context, List<TokenPO> chunk)
            throws IOException {
        List<Long> ids = new ArrayList<>(chunk.size());
        // 苹果的 auth_entities 是"每项只有一对"的数组：key 为 token 编号，value 为配件 UUID。
        // MES 把整批塞进一个 map（TokenRegisterListener:136 的 List.of(单个 map)），与它自己客户端
        // 方法的注释不一致；这里按注释的契约来，并在 P8b 真证书联调时用真实响应确认。
        List<Map<String, String>> entities = new ArrayList<>(chunk.size());
        for (TokenPO token : chunk) {
            if (token.getAccessoryUUID() == null || token.getAccessoryUUID().isBlank()) {
                throw new BizException("token 缺少 accessory_uuid，数据异常: tokenId=" + token.getId());
            }
            ids.add(token.getId());
            entities.add(Map.of(token.getAuthEntityId(), token.getAccessoryUUID()));
        }

        MfiResponse response = client.registerUsedAuthEntitiesBulk(context.ppid(), entities);
        if (!response.isSuccess()) {
            tokenMapper.markRegisterFailed(context.tenantId(), ids);
            throw new IOException("批量注册失败: count=" + entities.size() + ", " + response.briefError());
        }
        tokenMapper.markRegistered(context.tenantId(), ids);
        return ids.size();
    }

}
