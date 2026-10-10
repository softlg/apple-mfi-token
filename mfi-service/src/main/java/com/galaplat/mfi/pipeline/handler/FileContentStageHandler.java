package com.galaplat.mfi.pipeline.handler;

import com.galaplat.mfi.apple.MfiTokenClient;
import com.galaplat.mfi.apple.model.TokenFileRow;
import com.galaplat.mfi.apple.response.TokenFileDownload;
import com.galaplat.mfi.client.MfiClientFactory;
import com.galaplat.mfi.common.enums.Stage;
import com.galaplat.mfi.common.exception.BizException;
import com.galaplat.mfi.dao.entity.TokenFilePO;
import com.galaplat.mfi.dao.entity.TokenPO;
import com.galaplat.mfi.dao.mapper.TokenFileMapper;
import com.galaplat.mfi.dao.mapper.TokenMapper;
import com.galaplat.mfi.pipeline.StageContext;
import com.galaplat.mfi.pipeline.StageHandler;
import com.galaplat.mfi.pipeline.StageOutcome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * FILE_CONTENT：逐个下载文件、解析并落 token。
 *
 * <p>按文件推进而不是按分片一次性做完：一个 request 可能有多个 2000 token 的文件，下载中途失败时
 * 已成功的文件已经落库，重投只会补下缺的那一个。{@code download_status <> 'SUCCESS'} 与
 * token 表的 {@code insert ignore} 共同保证重跑不产生第二份。
 *
 * <p>accessory_uuid 在这里生成并随 token 一起落库，注册阶段只读不写。这样重试注册时提交给苹果的
 * 还是同一个配件 UUID；如果 uuid 是注册时才生成，一次网络失败就会让同一个 token 在苹果侧对应两个配件。
 */
@Slf4j
@Component
public class FileContentStageHandler implements StageHandler {

    /** 单条多值 INSERT 的行数上限。2000 行一次插会撑爆 max_allowed_packet 并长时间锁表 */
    private static final int INSERT_CHUNK = 500;

    private final MfiClientFactory clientFactory;
    private final TokenFileMapper tokenFileMapper;
    private final TokenMapper tokenMapper;

    public FileContentStageHandler(MfiClientFactory clientFactory, TokenFileMapper tokenFileMapper,
                                   TokenMapper tokenMapper) {
        this.clientFactory = clientFactory;
        this.tokenFileMapper = tokenFileMapper;
        this.tokenMapper = tokenMapper;
    }

    @Override
    public Stage stage() {
        return Stage.FILE_CONTENT;
    }

    @Override
    public StageOutcome handle(StageContext context) throws IOException {
        long tenantId = context.tenantId();
        long shardId = context.shardId();
        String requestId = context.shard().getAppleRequestUuid();
        List<TokenFilePO> files = tokenFileMapper.selectByShard(tenantId, shardId);
        if (files.isEmpty()) {
            throw new BizException("分片没有登记任何文件，无法下载: shardId=" + shardId);
        }

        MfiTokenClient client = clientFactory.forTenant(tenantId);
        int landed = 0;
        for (TokenFilePO file : files) {
            if ("SUCCESS".equals(file.getDownloadStatus())) {
                continue;
            }
            landed += downloadOne(client, context, file, requestId);
        }
        log.info("文件下载完成: shardId={}, requestId={}, files={}, landed={}, traceId={}",
                shardId, requestId, files.size(), landed, context.traceId());
        return StageOutcome.advance(Stage.FILE_CONTENT);
    }

    /**
     * 下载一个文件。失败时把错误写回文件行再抛出，交给上层的退避重试——同一分片的其他文件已经落库，
     * 下一次重试不会重复下载。
     */
    private int downloadOne(MfiTokenClient client, StageContext context, TokenFilePO file, String requestId)
            throws IOException {
        long tenantId = context.tenantId();
        TokenFileDownload download = client.downloadTokenFile(requestId, file.getFileName());
        if (!download.isSuccess()) {
            String reason = "下载失败 http=" + download.getHttpStatus();
            tokenFileMapper.markDownloadFailed(tenantId, file.getId(),
                    nullToZero(file.getRetryCount()) + 1, false, reason);
            throw new IOException("文件下载失败: fileName=" + file.getFileName() + ", " + reason);
        }

        List<TokenPO> rows = toTokens(context, file, download.getRows());
        int inserted = 0;
        for (int from = 0; from < rows.size(); from += INSERT_CHUNK) {
            List<TokenPO> chunk = rows.subList(from, Math.min(from + INSERT_CHUNK, rows.size()));
            inserted += tokenMapper.insertBatchIgnore(chunk);
        }
        tokenFileMapper.markDownloaded(tenantId, file.getId(), rows.size());
        log.info("单文件入库: shardId={}, fileName={}, rows={}, newlyInserted={}",
                context.shardId(), file.getFileName(), rows.size(), inserted);
        return rows.size();
    }

    private List<TokenPO> toTokens(StageContext context, TokenFilePO file, List<TokenFileRow> parsed) {
        List<TokenPO> rows = new ArrayList<>(parsed.size());
        for (TokenFileRow row : parsed) {
            if (row.getTokenId() == null || row.getTokenId().isBlank()) {
                // 没有 Token ID 的行落库就会撞唯一键的空值语义，直接丢弃；苹果的文件不会出这种行，
                // 出现即说明文件不是我们期待的那一份，问题留给日志与 count 对比暴露
                log.warn("丢弃缺少 Token ID 的行: fileName={}, crc32={}", file.getFileName(), row.getCrc32());
                continue;
            }
            TokenPO token = new TokenPO();
            token.setTenantId(context.tenantId());
            token.setApplicationId(context.application().getId());
            token.setShardId(context.shardId());
            token.setFileId(file.getId());
            token.setPpid(row.getPpid() == null || row.getPpid().isBlank() ? context.ppid() : row.getPpid());
            token.setAuthEntityId(row.getTokenId());
            token.setAccessoryUUID(UUID.randomUUID().toString());
            token.setSecretKey(row.getCrc32());
            token.setEncodedToken(row.getBase64EncodedToken());
            rows.add(token);
        }
        return rows;
    }

    private static int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

}
