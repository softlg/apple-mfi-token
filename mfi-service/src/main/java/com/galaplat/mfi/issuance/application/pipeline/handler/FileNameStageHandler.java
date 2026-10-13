package com.galaplat.mfi.issuance.application.pipeline.handler;

import com.galaplat.mfi.apple.MfiTokenClient;
import com.galaplat.mfi.apple.MfiUtcTime;
import com.galaplat.mfi.apple.model.FileNameListResponse;
import com.galaplat.mfi.credential.infrastructure.client.MfiClientFactory;
import com.galaplat.mfi.issuance.domain.Stage;
import com.galaplat.mfi.shared.api.BizException;
import com.galaplat.mfi.issuance.infrastructure.persistence.TokenFilePO;
import com.galaplat.mfi.issuance.infrastructure.persistence.TokenFileMapper;
import com.galaplat.mfi.issuance.domain.StageContext;
import com.galaplat.mfi.issuance.domain.StageHandler;
import com.galaplat.mfi.issuance.domain.StageOutcome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * FILE_NAME：取回苹果生成的文件名清单并登记。未就绪时按苹果给的 UTC 时刻等待，不消耗重试次数。
 *
 * <p>这一步是整个链路唯一"必须等几小时"的阶段：苹果的 token 生成是异步的，MES 靠猜延迟时间
 * （固定 1 小时轮询）把它做成了轮询风暴。这里改成按 {@code retry_download_after} 计算真实到货时间，
 * 并把等待写成 next_execute_time——所以等待中的分片在库里看得见、在队列里不占消费者。
 */
@Slf4j
@Component
public class FileNameStageHandler implements StageHandler {

    private final MfiClientFactory clientFactory;
    private final TokenFileMapper tokenFileMapper;

    public FileNameStageHandler(MfiClientFactory clientFactory, TokenFileMapper tokenFileMapper) {
        this.clientFactory = clientFactory;
        this.tokenFileMapper = tokenFileMapper;
    }

    @Override
    public Stage stage() {
        return Stage.FILE_NAME;
    }

    @Override
    public StageOutcome handle(StageContext context) throws IOException {
        String requestId = context.shard().getAppleRequestUuid();
        if (requestId == null || requestId.isBlank()) {
            // APPLY 的成功标志就是这个 id，缺它说明分片被手工改坏了；继续等只会有下一次空跑
            throw new BizException("分片缺少 apple_request_uuid，无法取文件列表: shardId=" + context.shardId());
        }

        MfiTokenClient client = clientFactory.forTenant(context.tenantId());
        FileNameListResponse response = client.getFileNames(requestId);
        List<String> fileNames = response.getFileNames();
        if (fileNames == null || fileNames.isEmpty()) {
            if (!response.isSuccess() && isClientError(response.getCode())) {
                return StageOutcome.dead(Stage.FILE_NAME, "苹果拒绝返回文件列表: " + response.briefError());
            }
            LocalDateTime ready = MfiUtcTime.nextAttempt(response.getRetryDownloadAfter());
            log.info("文件尚未就绪，按计划等待: shardId={}, requestId={}, readyAt={}",
                    context.shardId(), requestId, ready);
            return StageOutcome.waitUntil(Stage.FILE_NAME, ready, "苹果尚未生成文件");
        }

        List<TokenFilePO> rows = new ArrayList<>(fileNames.size());
        for (String fileName : fileNames) {
            if (fileName == null || fileName.isBlank()) {
                continue;
            }
            TokenFilePO row = new TokenFilePO();
            row.setTenantId(context.tenantId());
            row.setApplicationId(context.application().getId());
            row.setShardId(context.shardId());
            row.setPpid(context.ppid());
            row.setAppleRequestUuid(requestId);
            row.setFileName(fileName);
            rows.add(row);
        }
        if (rows.isEmpty()) {
            throw new IOException("文件列表里全是空文件名, requestId=" + requestId);
        }
        // insert ignore：同一个 request 的文件名被重复登记不是错误，唯一键负责去重
        tokenFileMapper.insertBatchIgnore(rows);
        log.info("文件清单已登记: shardId={}, requestId={}, files={}, traceId={}",
                context.shardId(), requestId, rows.size(), context.traceId());
        return StageOutcome.advance(Stage.FILE_NAME);
    }

    private static boolean isClientError(Integer code) {
        return code != null && code >= 400 && code < 500;
    }

}
