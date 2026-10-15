package com.galaplat.mfi.identity.application;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.galaplat.mfi.apple.MfiEndpoints;
import com.galaplat.mfi.shared.api.ErrorCode;
import com.galaplat.mfi.shared.api.BizException;
import com.galaplat.mfi.identity.infrastructure.persistence.TenantMemberPO;
import com.galaplat.mfi.identity.infrastructure.persistence.TenantPO;
import com.galaplat.mfi.identity.infrastructure.persistence.UserPO;
import com.galaplat.mfi.identity.infrastructure.persistence.TenantMapper;
import com.galaplat.mfi.identity.infrastructure.persistence.TenantMemberMapper;
import com.galaplat.mfi.identity.infrastructure.persistence.UserMapper;
import com.galaplat.mfi.shared.tenant.TenantContext;
import com.galaplat.mfi.shared.tenant.TenantPrincipal;
import com.galaplat.mfi.identity.interfaces.LoginRequest;
import com.galaplat.mfi.identity.interfaces.RegisterRequest;
import com.galaplat.mfi.identity.interfaces.LoginVo;
import com.galaplat.mfi.identity.interfaces.ProfileVo;
import com.galaplat.mfi.identity.interfaces.TenantBriefVo;
import com.galaplat.mfi.identity.infrastructure.PasswordHasher;
import com.galaplat.mfi.shared.tenant.TenantBindingInterceptor;
import com.galaplat.mfi.identity.application.AuthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 认证与租户选择实现。
 *
 * <p>会话里只写当前租户 ID，owner 位不进会话（见 {@link TenantBindingInterceptor}），
 * 因此这里所有 owner 判断都以库内数据为准。
 */
@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final TenantMapper tenantMapper;
    private final TenantMemberMapper tenantMemberMapper;
    private final PasswordHasher passwordHasher;

    public AuthServiceImpl(UserMapper userMapper, TenantMapper tenantMapper,
                           TenantMemberMapper tenantMemberMapper, PasswordHasher passwordHasher) {
        this.userMapper = userMapper;
        this.tenantMapper = tenantMapper;
        this.tenantMemberMapper = tenantMemberMapper;
        this.passwordHasher = passwordHasher;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginVo register(RegisterRequest request) {
        boolean accountTaken = userMapper.exists(
                Wrappers.<UserPO>lambdaQuery().eq(UserPO::getAccount, request.getAccount()));
        if (accountTaken) {
            throw new BizException(ErrorCode.ACCOUNT_TAKEN, ErrorCode.ACCOUNT_TAKEN.getMessage());
        }

        UserPO user = new UserPO();
        user.setAccount(request.getAccount());
        user.setPasswordHash(passwordHasher.hash(request.getPassword()));
        user.setName(request.getName());
        user.setEmail(emptyToNull(request.getEmail()));
        user.setPhone(emptyToNull(request.getPhone()));
        user.setStatus(1);
        userMapper.insert(user);

        TenantPO tenant = new TenantPO();
        // 编码由创建者账号 ID 派生：注册只建首租户，user 主键全局唯一即保证编码唯一。
        // 若将来支持一人多租户，此规则必须换成独立取号，不能复用 user ID。
        tenant.setTenantCode("T" + user.getId());
        tenant.setTenantName(request.getTenantName());
        tenant.setAppleTechnology(MfiEndpoints.TECHNOLOGY_DEFAULT);
        tenant.setStatus(1);
        tenantMapper.insert(tenant);

        TenantMemberPO owner = new TenantMemberPO();
        owner.setUserId(user.getId());
        owner.setOwnerFlag(1);
        owner.setStatus(1);
        owner.setJoinTime(LocalDateTime.now());
        // t_tenant_member 受租户改写，此刻上下文还不存在，只能用 runAs 显式声明写入归属。
        // 写成代码块形式是为了让编译器把它认作 Runnable，避免与 Supplier 重载歧义。
        TenantContext.runAs(new TenantPrincipal(tenant.getId(), user.getId(), true), () -> {
            tenantMemberMapper.insert(owner);
        });

        log.info("注册完成: userId={}, tenantId={}", user.getId(), tenant.getId());
        return doLogin(user, tenant.getId());
    }

    @Override
    public LoginVo login(LoginRequest request) {
        UserPO user = userMapper.selectOne(
                Wrappers.<UserPO>lambdaQuery().eq(UserPO::getAccount, request.getAccount()));
        if (user == null) {
            passwordHasher.burn(request.getPassword());
            throw new BizException(ErrorCode.BAD_CREDENTIALS, ErrorCode.BAD_CREDENTIALS.getMessage());
        }
        if (!passwordHasher.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BizException(ErrorCode.BAD_CREDENTIALS, ErrorCode.BAD_CREDENTIALS.getMessage());
        }
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "账号已停用，请联系所属租户的 owner");
        }
        Long defaultTenantId = firstAvailableTenantId(user.getId());
        return doLogin(user, defaultTenantId);
    }

    @Override
    public void logout() {
        StpUtil.logout();
    }

    @Override
    public ProfileVo profile() {
        long userId = StpUtil.getLoginIdAsLong();
        UserPO user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "登录账号不存在或已被删除，请重新登录");
        }
        Long currentTenantId = TenantContext.isBound() ? TenantContext.requireTenantId() : null;

        ProfileVo vo = new ProfileVo();
        vo.setUserId(user.getId());
        vo.setAccount(user.getAccount());
        vo.setName(user.getName());
        vo.setEmail(user.getEmail());
        vo.setPhone(user.getPhone());
        vo.setStatus(user.getStatus());
        vo.setLastLoginTime(user.getLastLoginTime());
        vo.setCurrentTenantId(currentTenantId);
        vo.setTenants(listTenants(userId, currentTenantId));
        return vo;
    }

    @Override
    public TenantBriefVo switchTenant(Long tenantId) {
        long userId = StpUtil.getLoginIdAsLong();
        TenantMemberPO member = tenantMemberMapper.selectByTenantAndUser(tenantId, userId);
        if (member == null || !Integer.valueOf(1).equals(member.getStatus())) {
            throw new BizException(ErrorCode.TENANT_UNAVAILABLE, "你不是该租户的启用成员，无法切换到该租户");
        }
        TenantPO tenant = tenantMapper.selectById(tenantId);
        if (tenant == null || !Integer.valueOf(1).equals(tenant.getStatus())) {
            throw new BizException(ErrorCode.TENANT_UNAVAILABLE, "该租户不存在或已暂停");
        }

        StpUtil.getSession().set(TenantBindingInterceptor.SESSION_TENANT_ID, tenantId);
        // 本请求后续逻辑应立即以新租户为准，否则同一次调用里读写会分到两个租户
        TenantContext.bind(new TenantPrincipal(tenantId, userId, Integer.valueOf(1).equals(member.getOwnerFlag())));

        TenantBriefVo vo = toBrief(tenant, member);
        vo.setCurrent(true);
        log.info("切换租户: userId={}, tenantId={}", userId, tenantId);
        return vo;
    }

    /**
     * 建立登录态并选定生效租户。注册与登录共用，避免两处分别维护会话键。
     */
    private LoginVo doLogin(UserPO user, Long tenantId) {
        StpUtil.login(user.getId());
        if (tenantId != null) {
            StpUtil.getSession().set(TenantBindingInterceptor.SESSION_TENANT_ID, tenantId);
        }

        UserPO stamp = new UserPO();
        stamp.setId(user.getId());
        stamp.setLastLoginTime(LocalDateTime.now());
        userMapper.updateById(stamp);

        LoginVo vo = new LoginVo();
        vo.setTokenName(StpUtil.getTokenName());
        vo.setTokenValue(StpUtil.getTokenValue());
        vo.setUserId(user.getId());
        vo.setAccount(user.getAccount());
        vo.setName(user.getName());
        vo.setTenants(listTenants(user.getId(), tenantId));
        vo.setCurrentTenant(vo.getTenants().stream()
                .filter(t -> Boolean.TRUE.equals(t.getCurrent()))
                .findFirst()
                .orElse(null));
        return vo;
    }

    /** 登录时默认生效的租户：owner 身份优先，其次最早加入。 */
    private Long firstAvailableTenantId(Long userId) {
        return listTenants(userId, null).stream().map(TenantBriefVo::getTenantId).findFirst().orElse(null);
    }

    /**
     * 我所属的、且当前可用的租户列表。
     *
     * <p>跨租户读成员表只能走 {@code selectByUserId} 这一条按 user_id 自缚的放行路径；
     * 暂停或不存在的租户直接剔除，因为它们本来就切不进去。
     */
    private List<TenantBriefVo> listTenants(Long userId, Long currentTenantId) {
        List<TenantMemberPO> members = tenantMemberMapper.selectByUserId(userId);
        if (members.isEmpty()) {
            return List.of();
        }
        List<Long> tenantIds = members.stream().map(TenantMemberPO::getTenantId).distinct().toList();
        Map<Long, TenantPO> tenants = tenantMapper.selectBatchIds(tenantIds).stream()
                .filter(t -> Integer.valueOf(1).equals(t.getStatus()))
                .collect(Collectors.toMap(TenantPO::getId, Function.identity()));

        List<TenantBriefVo> vos = new ArrayList<>();
        for (TenantMemberPO member : members) {
            TenantPO tenant = tenants.get(member.getTenantId());
            if (tenant == null || !Integer.valueOf(1).equals(member.getStatus())) {
                continue;
            }
            TenantBriefVo vo = toBrief(tenant, member);
            vo.setCurrent(member.getTenantId().equals(currentTenantId));
            vos.add(vo);
        }
        vos.sort(Comparator.comparing((TenantBriefVo v) -> !Boolean.TRUE.equals(v.getOwner()))
                .thenComparing(TenantBriefVo::getTenantId));
        return vos;
    }

    private static TenantBriefVo toBrief(TenantPO tenant, TenantMemberPO member) {
        TenantBriefVo vo = new TenantBriefVo();
        vo.setTenantId(tenant.getId());
        vo.setTenantCode(tenant.getTenantCode());
        vo.setTenantName(tenant.getTenantName());
        vo.setOwner(Integer.valueOf(1).equals(member.getOwnerFlag()));
        return vo;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

}
