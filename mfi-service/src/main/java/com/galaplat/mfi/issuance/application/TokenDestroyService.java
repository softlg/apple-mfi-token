package com.galaplat.mfi.issuance.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.galaplat.mfi.apple.MfiTokenClient;
import com.galaplat.mfi.apple.model.MfiResponse;
import com.galaplat.mfi.credential.domain.AppleClientProvider;
import com.galaplat.mfi.shared.api.ErrorCode;
import com.galaplat.mfi.identity.domain.AuditFact;
import com.galaplat.mfi.identity.domain.OperationAction;
import com.galaplat.mfi.shared.api.BizException;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPO;
import com.galaplat.mfi.issuance.infrastructure.persistence.TokenPO;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationMapper;
import com.galaplat.mfi.identity.application.AuditTrail;
import com.galaplat.mfi.issuance.infrastructure.persistence.TokenMapper;
import com.galaplat.mfi.issuance.interfaces.TokenDestroyRequest;
import com.galaplat.mfi.issuance.interfaces.TokenDestroyVo;
import com.galaplat.mfi.shared.tenant.TenantAccess;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.galaplat.mfi.issuance.application.pipeline.handler.RegisterStageHandler;

/**
 * 销毁未使用的认证实体：把"已下载到本系统、从没注册回苹果"的 token 在苹果侧作废，额度还给 PPID。
 *
 * <p>四条形制来自"不可逆"这三个字：
 * <ol>
 *   <li>仅 owner，且必须把申请单号原样再输一遍——二次确认不是一个复选框，复选框挡不住误操作；</li>
 *   <li>只对 {@code ISSUED} 行发起。注册过的实体在苹果侧已绑到配件 UUID，销毁请求会被拒，
 *       与其等苹果报错不如在查询条件里就排除掉；</li>
 *   <li>逐批推进、每批成功即单独落库（与 {@code RegisterStageHandler} 同一套纪律），
 *       一旦某批被拒就停手，不把剩下的继续送去——剩下的还有机会救回来；</li>
 *   <li>单次请求有数量上限，超限的部分留给下一次点击。</li>
 * </ol>
 *
 * <p>结果如实回数量而不抛异常：半途失败意味着"确实已经有一部分没了"，把它压成错误响应反而瞒住了事实。
 */
@Slf4j
@Service
public class TokenDestroyService {

    /** 一次请求最多销毁多少个。不可逆的动作宁可让人点第二次，也不能一次误删整单 */
    private static final int MAX_PER_REQUEST = 500;

    /** 单次提交给苹果的实体数。苹果没公布销毁接口的批量上限，按注册上限的十分之一保守切批 */
    private static final int BATCH_SIZE = 100;

    private final TokenMapper tokenMapper;
    private final ApplicationMapper applicationMapper;
    private final AuditTrail auditTrail;
    private final AppleClientProvider clientFactory;

    public TokenDestroyService(TokenMapper tokenMapper, ApplicationMapper applicationMapper,
                               AuditTrail auditTrail, AppleClientProvider clientFactory) {
        this.tokenMapper = tokenMapper;
        this.applicationMapper = applicationMapper;
        this.auditTrail = auditTrail;
        this.clientFactory = clientFactory;
    }

    public TokenDestroyVo destroy(TokenDestroyRequest request) {
        TenantAccess.requireOwner();
        long tenantId = TenantAccess.requireTenantId();
        long userId = TenantAccess.requireUserId();
        String applicationNo = requireText(request.getApplicationNo(), "申请单号");
        String confirm = requireText(request.getConfirmApplicationNo(), "确认申请单号");
        if (!applicationNo.equals(confirm)) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "二次确认的申请单号不一致，已拒绝销毁");
        }
        String ppid = trimToNull(request.getPpid());
        ApplicationPO application = requireApplication(tenantId, applicationNo);

        TokenDestroyVo vo = new TokenDestroyVo();
        vo.setApplicationNo(applicationNo);
        List<TokenPO> candidates = tokenMapper.selectDestroyCandidates(
                tenantId, application.getId(), ppid, MAX_PER_REQUEST);
        vo.setSelected(candidates.size());
        if (candidates.isEmpty()) {
            log.info("销毁没有候选：该单已无已下载未注册的 token: tenantId={}, applicationNo={}",
                    tenantId, applicationNo);
            return vo;
        }

        // 先做纯本地的校验与切批，再取客户端：数据坏了不该依赖一份有效凭证才报出来
        List<List<TokenPO>> batches = splitByPpid(candidates);
        MfiTokenClient client = clientFactory.forTenant(tenantId);
        int destroyed = 0;
        String failReason = null;
        for (List<TokenPO> batch : batches) {
            List<Long> ids = new ArrayList<>(batch.size());
            List<String> authEntityIds = new ArrayList<>(batch.size());
            for (TokenPO token : batch) {
                ids.add(token.getId());
                authEntityIds.add(token.getAuthEntityId());
            }
            MfiResponse response;
            try {
                response = client.destroyUnusedAuthEntities(batch.get(0).getPpid(), authEntityIds);
            } catch (IOException e) {
                // 没送达就等于这批还在，停手让用户重发；这里不重试，因为"请求已生效但响应丢了"
                // 与"请求没出去"从客户端分不出来，盲目重试可能把已销毁的当没销毁再报一次错
                failReason = "销毁请求未能送达苹果：" + flatten(e.getMessage());
                break;
            }
            if (!response.isSuccess()) {
                failReason = "苹果拒绝了这一批：" + flatten(response.briefError());
                break;
            }
            int landed = tokenMapper.markDestroyed(tenantId, ids);
            if (landed < ids.size()) {
                log.warn("苹果已受理但本地落状态行数少于提交数: applicationNo={}, submitted={}, landed={}",
                        applicationNo, ids.size(), landed);
            }
            destroyed += landed;
        }

        vo.setDestroyed(destroyed);
        vo.setFailReason(failReason);
        vo.setHasMore(failReason != null || candidates.size() == MAX_PER_REQUEST);
        // 苹果回来的文案可能把 PPID 原样抄回来，所以它只进响应、不进日志与留痕
        log.info("销毁未使用实体: tenantId={}, applicationNo={}, selected={}, destroyed={}, interrupted={}",
                tenantId, applicationNo, candidates.size(), destroyed, failReason != null);
        writeDestroyLog(tenantId, userId, application, destroyed, failReason != null);
        return vo;
    }

    /**
     * 苹果的销毁请求用一个 PPID 定位额度，所以先按 PPID 归堆，再按批量上限切。
     * 候选行是按 id 升序取回的，同一 PPID 的行可能因为并发落库而交错，归堆后再切批。
     */
    private static List<List<TokenPO>> splitByPpid(List<TokenPO> candidates) {
        Map<String, List<TokenPO>> byPpid = new LinkedHashMap<>();
        for (TokenPO token : candidates) {
            if (token.getAuthEntityId() == null || token.getAuthEntityId().isBlank()) {
                throw new BizException(ErrorCode.SYSTEM_ERROR,
                        "token 缺少认证实体编号，无法销毁: tokenId=" + token.getId());
            }
            byPpid.computeIfAbsent(token.getPpid(), key -> new ArrayList<>()).add(token);
        }
        List<List<TokenPO>> batches = new ArrayList<>();
        for (List<TokenPO> group : byPpid.values()) {
            for (int from = 0; from < group.size(); from += BATCH_SIZE) {
                batches.add(group.subList(from, Math.min(from + BATCH_SIZE, group.size())));
            }
        }
        return batches;
    }

    /** 留痕只记单号与数量，不带 PPID，也不带认证实体编号 */
    private void writeDestroyLog(long tenantId, long userId, ApplicationPO application,
                                 int destroyed, boolean interrupted) {
        auditTrail.append(AuditFact.of(tenantId, application.getId(), null, OperationAction.DESTROY,
                userId, null, destroyed,
                "销毁未使用实体 " + destroyed + " 个" + (interrupted ? "，有一批被拒绝后中止" : "")));
    }

    private ApplicationPO requireApplication(long tenantId, String applicationNo) {
        ApplicationPO application = applicationMapper.selectOne(Wrappers.<ApplicationPO>lambdaQuery()
                .select(ApplicationPO::getId)
                .eq(ApplicationPO::getTenantId, tenantId)
                .eq(ApplicationPO::getApplicationNo, applicationNo));
        if (application == null) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "申请单不存在，或不属于当前租户");
        }
        return application;
    }

    /** 对外文案压平并截断：苹果的错误体可能是整页 HTML */
    private static String flatten(String text) {
        if (text == null || text.isBlank()) {
            return "无返回内容";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200);
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

}
