package com.galaplat.mfi.issuance.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.galaplat.mfi.shared.api.ErrorCode;
import com.galaplat.mfi.shared.api.PageResult;
import com.galaplat.mfi.issuance.domain.ApplicationStatus;
import com.galaplat.mfi.identity.domain.AuditFact;
import com.galaplat.mfi.identity.domain.OperationAction;
import com.galaplat.mfi.issuance.domain.Stage;
import com.galaplat.mfi.issuance.domain.StageStatus;
import com.galaplat.mfi.identity.domain.TenantConfigKey;
import com.galaplat.mfi.identity.domain.TenantProfile;
import com.galaplat.mfi.shared.api.BizException;
import com.galaplat.mfi.apple.MfiEndpoints;
import com.galaplat.mfi.credential.application.CredentialService;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPO;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPpidPO;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationMapper;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPpidMapper;
import com.galaplat.mfi.ppid.domain.PpIdAllocation;
import com.galaplat.mfi.issuance.interfaces.ApplyCreateRequest;
import com.galaplat.mfi.issuance.interfaces.ApplicationPageRequest;
import com.galaplat.mfi.issuance.interfaces.ApplicationRetryVo;
import com.galaplat.mfi.issuance.interfaces.ApplicationShardVo;
import com.galaplat.mfi.issuance.interfaces.ApplicationVo;
import com.galaplat.mfi.issuance.interfaces.ApplySubmitVo;
import com.galaplat.mfi.issuance.infrastructure.mq.ShardMessageProducer;
import com.galaplat.mfi.issuance.infrastructure.mq.StageMessage;
import com.galaplat.mfi.shared.tenant.TenantAccess;
import com.galaplat.mfi.issuance.application.ApplicationService;
import com.galaplat.mfi.issuance.application.ApplicationVoConverter;
import com.galaplat.mfi.identity.application.AccountReader;
import com.galaplat.mfi.identity.application.AuditTrail;
import com.galaplat.mfi.identity.application.TenantProfileReader;
import com.galaplat.mfi.ppid.application.PpIdService;
import com.galaplat.mfi.identity.application.TenantConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 申请同步段。
 *
 * <p>顺序上有三条硬约束：
 * <ol>
 *   <li>凭证检查放在扣减之前——证书缺失或已过期的租户不该先吃掉额度再失败；</li>
 *   <li>申请单插入放在扣减之前——幂等键撞库时立刻失败，本次事务一笔扣减都还没做；</li>
 *   <li>消息登记在事务内、投递在提交后，见 {@link ShardMessageProducer}。</li>
 * </ol>
 */
@Slf4j
@Service
public class ApplicationServiceImpl implements ApplicationService {

    /** 单号里的日期段，只做可读性用途，唯一性由随机段保证 */
    private static final DateTimeFormatter NO_DATE = DateTimeFormatter.ofPattern("yyMMdd");

    /** 单号随机段字符集：去掉易混字符，人工报障时不容易念错 */
    private static final char[] NO_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApplicationMapper applicationMapper;
    private final ApplicationPpidMapper shardMapper;
    private final AuditTrail auditTrail;
    private final AccountReader accountReader;
    private final TenantProfileReader tenantProfiles;
    private final PpIdService ppidService;
    private final TenantConfigService configService;
    private final CredentialService credentialService;
    private final ShardMessageProducer messageProducer;
    private final TransactionTemplate transactionTemplate;

    public ApplicationServiceImpl(ApplicationMapper applicationMapper, ApplicationPpidMapper shardMapper,
                                  AuditTrail auditTrail, AccountReader accountReader,
                                  TenantProfileReader tenantProfiles, PpIdService ppidService,
                                  TenantConfigService configService, CredentialService credentialService,
                                  ShardMessageProducer messageProducer, TransactionTemplate transactionTemplate) {
        this.applicationMapper = applicationMapper;
        this.shardMapper = shardMapper;
        this.auditTrail = auditTrail;
        this.accountReader = accountReader;
        this.tenantProfiles = tenantProfiles;
        this.ppidService = ppidService;
        this.configService = configService;
        this.credentialService = credentialService;
        this.messageProducer = messageProducer;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public ApplySubmitVo submit(ApplyCreateRequest request) {
        long tenantId = TenantAccess.requireTenantId();
        long userId = TenantAccess.requireUserId();
        String productModel = requireText(request.getProductModel(), "产品型号");
        String spu = requireText(request.getSpu(), "SPU");
        Integer requested = request.getApplyQuantity();
        if (requested == null || requested < 1) {
            throw new BizException(ErrorCode.INVALID_PARAM, "申请数量至少为 1");
        }
        String idempotencyKey = trimToNull(request.getRequestId());

        // 幂等键先读一次：命中就连额度都不碰。允许与并发窗口同时落到这里，
        // 最终判定在库上的唯一索引，见下面的 DuplicateKeyException 分支。
        if (idempotencyKey != null) {
            ApplicationPO hit = findByIdempotencyKey(tenantId, idempotencyKey);
            if (hit != null) {
                log.info("申请重复提交，返回已有单: tenantId={}, applicationNo={}", tenantId, hit.getApplicationNo());
                return toSubmitVo(hit, true);
            }
        }

        // 这两道检查必须在扣减之前：证书坏掉的租户、以及超出租户上限的量，都不该先吃掉额度再失败
        requireUsableCredential(tenantId);
        requireWithinTenantLimit(tenantId, requested);

        ApplicationPO created;
        try {
            created = transactionTemplate.execute(status ->
                    doApply(tenantId, userId, productModel, spu, requested, idempotencyKey));
        } catch (DuplicateKeyException e) {
            ApplicationPO existing = idempotencyKey == null ? null : findByIdempotencyKey(tenantId, idempotencyKey);
            if (existing == null) {
                // 不是幂等键撞的（例如单号随机段碰撞），没什么可返回的，照原样抛出
                throw e;
            }
            log.info("并发重复提交已整体回滚: tenantId={}, applicationNo={}", tenantId, existing.getApplicationNo());
            return toSubmitVo(existing, true);
        }
        return toSubmitVo(created, false);
    }

    /**
     * 一次事务里做完：拆分 → 落单 → 逐份额度 CAS 扣减 → 落分片 → 留痕 → 登记消息。
     * 任何一步失败，前面已扣的额度随事务一起回滚，不会留下"扣了量但没有单"的中间态。
     */
    private ApplicationPO doApply(long tenantId, long userId, String productModel, String spu,
                                  int requested, String idempotencyKey) {
        // 与 GET /api/ppid/available 同一条池查询、同一份贪心，预览与真实分配不会有两套语义
        List<PpIdAllocation> plan = ppidService.planAllocation(productModel, spu, requested);
        int allocatable = PpIdAllocation.total(plan);
        if (allocatable < requested) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "额度不足：产品型号 " + productModel + " / SPU " + spu
                    + " 当前可分配 " + allocatable + "，本次需要 " + requested + "，还差 " + (requested - allocatable));
        }

        String applicant = accountReader.displayName(userId);
        ApplicationPO application = newApplication(tenantId, productModel, spu, requested, plan.size(),
                applicant, userId, idempotencyKey);
        applicationMapper.insert(application);

        List<ApplicationPpidPO> shards = new ArrayList<>(plan.size());
        for (PpIdAllocation item : plan) {
            if (!ppidService.deduct(tenantId, item.ppidId(), item.quantity())) {
                // 预览说够、这里扣不动，只可能是这中间被别人抢走了。整单失败让用户重看余量，
                // 而不是把申请量悄悄砍小——砍小之后用户看到的单量与实际申请的 token 数不一致。
                throw new BizException(ErrorCode.BIZ_REJECTED, "额度刚被其他申请占用，请刷新余量后重新提交");
            }
            ApplicationPpidPO shard = new ApplicationPpidPO();
            shard.setTenantId(tenantId);
            shard.setApplicationId(application.getId());
            shard.setPpid(item.ppid());
            shard.setPpidId(item.ppidId());
            shard.setAllocateCount(item.quantity());
            shard.setStage(Stage.APPLY.name());
            shard.setStageStatus(StageStatus.PENDING.name());
            shards.add(shard);
        }
        shardMapper.insertBatch(shards);

        // insertBatch 拿不回自增 ID，消息体要带 shardId，所以按申请单读回一次；一个申请单个位数分片，
        // 这条查询比把主键回填逻辑手写进 SQL 便宜得多。
        List<ApplicationPpidPO> persisted = shardMapper.selectByApplication(tenantId, application.getId());
        writeOperationLog(tenantId, application, applicant, shards.size());
        messageProducer.publishAfterCommit(persisted.stream()
                .map(row -> new StageMessage(tenantId, application.getId(), row.getId(), row.getStage(),
                        application.getApplicationNo()))
                .toList());
        log.info("申请已受理: tenantId={}, applicationNo={}, quantity={}, shards={}", tenantId,
                application.getApplicationNo(), requested, shards.size());
        return application;
    }

    // ---- HELPERS ----

    /**
     * 凭证是否可用于受理申请，判定规则在凭证上下文里（那里才有证书有效期与"缺凭证"的业务语义）。
     * 这里不构造客户端、不联网，只看库里的元数据：申请量一旦扣掉，等到异步阶段才发现证书坏了，
     * 用户看到的是一张注定失败的单。
     */
    private void requireUsableCredential(long tenantId) {
        credentialService.requireUsable(tenantId);
    }

    /**
     * 租户级单次上限。0 表示不额外限制，只受池子余量约束——这条规则在 MES 是硬编码常量，改一次要发一次版。
     */
    private void requireWithinTenantLimit(long tenantId, int requested) {
        int max = configService.intValue(tenantId, TenantConfigKey.APPLY_MAX_QUANTITY);
        if (max > 0 && requested > max) {
            throw new BizException(ErrorCode.BIZ_REJECTED,
                    "超出单次申请上限 " + max + "，请分批提交（该上限由租户配置 APPLY_MAX_QUANTITY 决定）");
        }
    }

    private ApplicationPO newApplication(long tenantId, String productModel, String spu, int requested,
                                         int ppidCount, String applicantName, long userId, String idempotencyKey) {
        ApplicationPO application = new ApplicationPO();
        application.setTenantId(tenantId);
        application.setApplicationNo(generateApplicationNo());
        application.setApplicantUserId(userId);
        application.setApplicantName(applicantName);
        application.setProductModel(productModel);
        application.setSpu(spu);
        application.setAppleTechnology(appleTechnology(tenantId));
        application.setApplyQuantity(requested);
        application.setPpidCount(ppidCount);
        application.setTokenCount(0);
        application.setStatus(ApplicationStatus.PENDING.name());
        application.setIdempotencyKey(idempotencyKey);
        return application;
    }

    /**
     * 单号用"日期 + 随机段"而不是自增号：它出现在 URL、导出文件与厂商沟通里，
     * 自增号等于把租户数量与其他人的单号一起暴露出去。
     */
    private static String generateApplicationNo() {
        StringBuilder suffix = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            suffix.append(NO_ALPHABET[RANDOM.nextInt(NO_ALPHABET.length)]);
        }
        return "A" + LocalDate.now().format(NO_DATE) + "-" + suffix;
    }

    /** technology 取发起时的快照；租户不存在或未设置时落 DEFAULT 哨兵，与客户端工厂注册路径同一常量 */
    private String appleTechnology(long tenantId) {
        TenantProfile tenant = tenantProfiles.find(tenantId);
        return tenant == null ? MfiEndpoints.TECHNOLOGY_DEFAULT : tenant.technology();
    }

    private ApplicationPO findByIdempotencyKey(long tenantId, String idempotencyKey) {
        return applicationMapper.selectOne(Wrappers.<ApplicationPO>lambdaQuery()
                .eq(ApplicationPO::getTenantId, tenantId)
                .eq(ApplicationPO::getIdempotencyKey, idempotencyKey));
    }

    /**
     * 留痕只记动作与数量。分片明细以 t_application_ppid 为准，这里不重复写 PPID 清单，
     * 否则这张表会被排障脚本整行导出，等于把额度明细抄了一份到日志型表里。
     */
    private void writeOperationLog(long tenantId, ApplicationPO application, String applicantName, int shardCount) {
        auditTrail.append(AuditFact.of(tenantId, application.getId(), null, OperationAction.APPLY,
                application.getApplicantUserId(), applicantName, application.getApplyQuantity(),
                "产品型号 " + application.getProductModel() + " / SPU " + application.getSpu()
                        + "，拆为 " + shardCount + " 份额度"));
    }

    @Override
    public PageResult<ApplicationVo> page(ApplicationPageRequest request) {
        long tenantId = TenantAccess.requireTenantId();
        LambdaQueryWrapper<ApplicationPO> filter = Wrappers.<ApplicationPO>lambdaQuery()
                .eq(ApplicationPO::getTenantId, tenantId)
                .eq(hasText(request.getApplicationNo()), ApplicationPO::getApplicationNo,
                        trimToNull(request.getApplicationNo()))
                .eq(hasText(request.getProductModel()), ApplicationPO::getProductModel,
                        trimToNull(request.getProductModel()))
                .eq(hasText(request.getSpu()), ApplicationPO::getSpu, trimToNull(request.getSpu()))
                .eq(hasText(request.getStatus()), ApplicationPO::getStatus, parseStatus(request.getStatus()))
                .orderByDesc(ApplicationPO::getId);
        IPage<ApplicationPO> page = applicationMapper.selectPage(
                Page.of(request.getPageNum(), request.getPageSize()), filter);
        return PageResult.of(page, ApplicationVoConverter::toVo);
    }

    /**
     * 人工重放。定位是"死信需要人工介入"的那一条出口，所以只碰 DEAD：成功、进行中和退避等待中的分片
     * 都留在原地——把它们一起改只会让同一跳出现两个消费者。
     *
     * <p>阶段保持原样、{@code apple_request_uuid} 保留，停在 APPLY 的分片靠已有的 request id 短路，
     * 不会向苹果重复要一批 token；不重放申请本身，也就不会再扣一次额度。
     *
     * <p>任何已登录成员都可重放：它消耗的是本租户已经扣过额度的分片，不属于不可逆或高影响操作，
     * 与发起申请同级。
     */
    @Override
    public ApplicationRetryVo retry(String applicationNo) {
        long tenantId = TenantAccess.requireTenantId();
        long userId = TenantAccess.requireUserId();
        String no = requireText(applicationNo, "申请单号");
        ApplicationPO application = applicationMapper.selectOne(Wrappers.<ApplicationPO>lambdaQuery()
                .eq(ApplicationPO::getTenantId, tenantId)
                .eq(ApplicationPO::getApplicationNo, no));
        if (application == null) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "申请单不存在，或不属于当前租户");
        }

        List<ApplicationPpidPO> dead = shardMapper.selectDeadByApplication(tenantId, application.getId());
        if (dead.isEmpty()) {
            // 没有失败分片就不是错误：按钮点第二次、或分片已被兜底扫描捞回，都该安静地返回空
            log.info("人工重放无事可做，没有终态失败分片: tenantId={}, applicationNo={}", tenantId, no);
            return retryVo(no, List.of());
        }

        String operator = accountReader.displayName(userId);
        List<ApplicationPpidPO> replayed = transactionTemplate.execute(status -> {
            List<ApplicationPpidPO> done = new ArrayList<>(dead.size());
            for (ApplicationPpidPO shard : dead) {
                if (shardMapper.resetForRetry(tenantId, shard.getId()) == 0) {
                    log.warn("分片已不在终态失败，跳过重放: shardId={}, stage={}", shard.getId(), shard.getStage());
                    continue;
                }
                writeReplayLog(tenantId, application.getId(), userId, operator, shard);
                done.add(shard);
            }
            if (!done.isEmpty()) {
                applicationMapper.reopen(tenantId, application.getId());
            }
            return done;
        });
        if (replayed == null || replayed.isEmpty()) {
            return retryVo(no, List.of());
        }

        // 复位落库并提交之后才投递。这里不挂 AFTER_COMMIT 回调：事务已经返回，投递失败仍有兜底扫描兜着，
        // 因为 resetForRetry 把 next_execute_time 清成了 null，而扫描正是按这一列捞 PENDING 的。
        String traceId = no + "#retry";
        replayed.forEach(shard -> messageProducer.publishStage(new StageMessage(tenantId, application.getId(),
                shard.getId(), shard.getStage(), traceId)));
        log.info("人工重放已入队: tenantId={}, applicationNo={}, operator={}, shards={}",
                tenantId, no, operator, replayed.size());
        return retryVo(no, replayed);
    }

    /**
     * 重放留痕记的是"谁在什么时候把哪一片从死信里捞回来，原来的死因是什么"。
     * 复位会把 {@code last_error} 清空，所以这一份必须在复位之后由内存快照写出来。
     */
    private void writeReplayLog(long tenantId, long applicationId, long userId, String operator,
                                ApplicationPpidPO shard) {
        auditTrail.append(AuditFact.of(tenantId, applicationId, shard.getId(), OperationAction.RETRY,
                userId, operator, shard.getAllocateCount(),
                "人工重放阶段 " + shard.getStage() + "，原失败原因："
                        + (hasText(shard.getLastError()) ? shard.getLastError().trim() : "无留档")));
    }

    private static ApplicationRetryVo retryVo(String applicationNo, List<ApplicationPpidPO> replayed) {
        ApplicationRetryVo vo = new ApplicationRetryVo();
        vo.setApplicationNo(applicationNo);
        vo.setReplayedCount(replayed.size());
        vo.setShards(replayed.stream().map(ApplicationServiceImpl::replayedShardVo).toList());
        return vo;
    }

    /** 返回复位后的状态，而不是复位前读到的快照，免得前端还要自己推算 */
    private static ApplicationShardVo replayedShardVo(ApplicationPpidPO shard) {
        ApplicationShardVo vo = ApplicationVoConverter.toShardVo(shard);
        vo.setStageStatus(StageStatus.PENDING.name());
        vo.setRetryCount(0);
        vo.setLastError(null);
        vo.setNextExecuteTime(null);
        return vo;
    }

    private static String parseStatus(String raw) {
        if (!hasText(raw)) {
            return null;
        }
        String value = raw.trim();
        try {
            return ApplicationStatus.valueOf(value).name();
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.INVALID_PARAM, "不支持的申请单状态: " + raw);
        }
    }

    private ApplySubmitVo toSubmitVo(ApplicationPO application, boolean duplicated) {
        ApplySubmitVo vo = new ApplySubmitVo();
        vo.setApplicationNo(application.getApplicationNo());
        vo.setStatus(application.getStatus());
        vo.setApplyQuantity(application.getApplyQuantity());
        vo.setPpidCount(application.getPpidCount());
        vo.setDuplicated(duplicated);
        vo.setShards(shardMapper.selectByApplication(application.getTenantId(), application.getId()).stream()
                .map(ApplicationVoConverter::toShardVo).toList());
        return vo;
    }

    private static String requireText(String value, String label) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, label + "不能为空");
        }
        return trimmed;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }



}
