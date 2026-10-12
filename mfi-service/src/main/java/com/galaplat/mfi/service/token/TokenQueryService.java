package com.galaplat.mfi.service.token;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.galaplat.mfi.common.api.ErrorCode;
import com.galaplat.mfi.common.api.PageResult;
import com.galaplat.mfi.common.enums.RegisterStatus;
import com.galaplat.mfi.common.enums.TokenStatus;
import com.galaplat.mfi.common.exception.BizException;
import com.galaplat.mfi.dao.entity.ApplicationPO;
import com.galaplat.mfi.dao.entity.TokenPO;
import com.galaplat.mfi.dao.mapper.ApplicationMapper;
import com.galaplat.mfi.dao.mapper.TokenMapper;
import com.galaplat.mfi.model.request.TokenPageRequest;
import com.galaplat.mfi.model.vo.TokenVo;
import com.galaplat.mfi.security.TenantAccess;
import org.springframework.stereotype.Service;

/**
 * token 结果的读侧分页。
 *
 * <p>这里只服务"看"：凭证材料（token 原文、密钥、认证实体 ID）一列都不查，
 * 厂商要拿走材料得走导出任务。列表接口一旦带上 encoded_token，它就会跟着浏览器历史、
 * 网关日志和前端埋点一路漏出去。
 */
@Service
public class TokenQueryService {

    private final TokenMapper tokenMapper;
    private final ApplicationMapper applicationMapper;

    public TokenQueryService(TokenMapper tokenMapper, ApplicationMapper applicationMapper) {
        this.tokenMapper = tokenMapper;
        this.applicationMapper = applicationMapper;
    }

    public PageResult<TokenVo> page(TokenPageRequest request) {
        long tenantId = TenantAccess.requireTenantId();
        Long applicationId = null;
        if (hasText(request.getApplicationNo())) {
            applicationId = requireApplicationId(tenantId, request.getApplicationNo().trim());
        }

        // select 里显式列出要给的列：表上有 encoded_token 与 secret_key，少写一次条件就是漏一次
        LambdaQueryWrapper<TokenPO> filter = Wrappers.<TokenPO>lambdaQuery()
                .select(TokenPO::getId, TokenPO::getPpid, TokenPO::getManufactureSn, TokenPO::getStatus,
                        TokenPO::getRegisterStatus, TokenPO::getDownloadTime, TokenPO::getRegisterTime)
                .eq(TokenPO::getTenantId, tenantId)
                .eq(applicationId != null, TokenPO::getApplicationId, applicationId)
                .eq(hasText(request.getPpid()), TokenPO::getPpid, trimToNull(request.getPpid()))
                .eq(hasText(request.getManufactureSn()), TokenPO::getManufactureSn,
                        trimToNull(request.getManufactureSn()))
                .eq(hasText(request.getStatus()), TokenPO::getStatus, parseTokenStatus(request.getStatus()))
                .eq(hasText(request.getRegisterStatus()), TokenPO::getRegisterStatus,
                        parseRegisterStatus(request.getRegisterStatus()))
                .orderByDesc(TokenPO::getId);

        IPage<TokenPO> page = tokenMapper.selectPage(Page.of(request.getPageNum(), request.getPageSize()), filter);
        return PageResult.of(page, TokenQueryService::toVo);
    }

    private Long requireApplicationId(long tenantId, String applicationNo) {
        ApplicationPO application = applicationMapper.selectOne(Wrappers.<ApplicationPO>lambdaQuery()
                .select(ApplicationPO::getId)
                .eq(ApplicationPO::getTenantId, tenantId)
                .eq(ApplicationPO::getApplicationNo, applicationNo));
        if (application == null) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "申请单不存在，或不属于当前租户");
        }
        return application.getId();
    }

    private static TokenVo toVo(TokenPO row) {
        TokenVo vo = new TokenVo();
        vo.setId(row.getId());
        vo.setPpid(row.getPpid());
        vo.setManufactureSn(row.getManufactureSn());
        vo.setStatus(row.getStatus());
        vo.setRegisterStatus(row.getRegisterStatus());
        vo.setDownloadTime(row.getDownloadTime());
        vo.setRegisterTime(row.getRegisterTime());
        return vo;
    }

    /** 状态先按枚举校验再进 SQL：直接把请求值拼进条件会让任意字符串成为查询条件，错了也没有反馈 */
    private static String parseTokenStatus(String raw) {
        return parseEnum(TokenStatus.class, raw, "状态");
    }

    private static String parseRegisterStatus(String raw) {
        return parseEnum(RegisterStatus.class, raw, "注册状态");
    }

    private static <E extends Enum<E>> String parseEnum(Class<E> type, String raw, String label) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.toUpperCase()).name();
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.INVALID_PARAM, "不支持的" + label + ": " + raw);
        }
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
