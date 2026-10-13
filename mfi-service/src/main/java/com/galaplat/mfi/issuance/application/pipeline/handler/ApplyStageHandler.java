package com.galaplat.mfi.issuance.application.pipeline.handler;

import com.galaplat.mfi.apple.MfiTokenClient;
import com.galaplat.mfi.apple.model.MfiResponse;
import com.galaplat.mfi.credential.domain.AppleClientProvider;
import com.galaplat.mfi.issuance.domain.Stage;
import com.galaplat.mfi.shared.api.BizException;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPpidPO;
import com.galaplat.mfi.issuance.domain.StageContext;
import com.galaplat.mfi.issuance.domain.StageHandler;
import com.galaplat.mfi.issuance.domain.StageOutcome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * APPLY：向苹果请求认证实体，拿到 request id 落库。
 *
 * <p>幂等靠已经落库的 {@code apple_request_uuid}：这一步一旦写成功，重投的消息就直接前进，
 * 绝不重复提交申请。反过来，如果调用成功但落库前进程崩了，苹果那边会多出一批已申请但未登记的实体
 * ——这批量只能靠 PPID 池的 applied_quantity 与苹果门户对账发现，所以这里的日志必须把 request id 留下。
 */
@Slf4j
@Component
public class ApplyStageHandler implements StageHandler {

    private final AppleClientProvider clientFactory;

    public ApplyStageHandler(AppleClientProvider clientFactory) {
        this.clientFactory = clientFactory;
    }

    @Override
    public Stage stage() {
        return Stage.APPLY;
    }

    @Override
    public StageOutcome handle(StageContext context) throws IOException {
        ApplicationPpidPO shard = context.shard();
        String existing = shard.getAppleRequestUuid();
        if (existing != null && !existing.isBlank()) {
            log.info("APPLY 已有 request id，直接前进: shardId={}, traceId={}",
                    context.shardId(), context.traceId());
            return StageOutcome.advance(Stage.APPLY, existing);
        }

        int count = shard.getAllocateCount() == null ? 0 : shard.getAllocateCount();
        if (count < 1) {
            // 分片数量不可能是 0，出现即为脏数据；重试一百次也不会变好
            throw new BizException("分片分配数量为 0，数据异常: shardId=" + context.shardId());
        }

        MfiTokenClient client = clientFactory.forTenant(context.tenantId());
        MfiResponse response = client.requestAuthEntities(shard.getPpid(), count);
        if (!response.isSuccess()) {
            // 4xx 是证书权限、PPID 无效这类配置问题，重试只会白占出口 IP 配额；5xx 交给上层退避
            if (isClientError(response.getCode())) {
                return StageOutcome.dead(Stage.APPLY, "苹果拒绝申请: " + response.briefError());
            }
            throw new IOException("苹果申请认证实体失败: " + response.briefError());
        }
        String requestId = response.getRequestId();
        if (requestId == null || requestId.isBlank()) {
            throw new IOException("苹果受理成功但没有返回 request id, code=" + response.getCode());
        }
        log.info("APPLY 完成: shardId={}, count={}, requestId={}, traceId={}",
                context.shardId(), count, requestId, context.traceId());
        return StageOutcome.advance(Stage.APPLY, requestId);
    }

    private static boolean isClientError(Integer code) {
        return code != null && code >= 400 && code < 500;
    }

}
