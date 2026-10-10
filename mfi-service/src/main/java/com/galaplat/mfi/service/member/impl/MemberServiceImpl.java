package com.galaplat.mfi.service.member.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.galaplat.mfi.common.api.ErrorCode;
import com.galaplat.mfi.common.exception.BizException;
import com.galaplat.mfi.dao.entity.TenantMemberPO;
import com.galaplat.mfi.dao.entity.UserPO;
import com.galaplat.mfi.dao.mapper.TenantMemberMapper;
import com.galaplat.mfi.dao.mapper.UserMapper;
import com.galaplat.mfi.model.request.InviteMemberRequest;
import com.galaplat.mfi.model.vo.MemberVo;
import com.galaplat.mfi.security.TenantAccess;
import com.galaplat.mfi.service.member.MemberService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 成员管理实现。
 *
 * <p>成员表带 tenant_id 且不在全局白名单，因此本类里的常规 CRUD 会被租户拦截器自动改写，
 * 跨租户读写在本层就不可能发生。唯一例外是按 user_id 定位成员，见
 * {@link TenantMemberMapper#selectByTenantAndUser}。
 */
@Slf4j
@Service
public class MemberServiceImpl implements MemberService {

    private static final int ENABLED = 1;
    private static final int OWNER = 1;
    private static final int NORMAL_MEMBER = 0;

    private final TenantMemberMapper tenantMemberMapper;
    private final UserMapper userMapper;

    public MemberServiceImpl(TenantMemberMapper tenantMemberMapper, UserMapper userMapper) {
        this.tenantMemberMapper = tenantMemberMapper;
        this.userMapper = userMapper;
    }

    @Override
    public List<MemberVo> list() {
        // 入口守卫：无生效租户时给出业务错误，而不是让 SQL 改写阶段抛编码缺陷
        TenantAccess.requireTenantId();
        List<TenantMemberPO> members = tenantMemberMapper.selectList(Wrappers.<TenantMemberPO>lambdaQuery()
                .orderByDesc(TenantMemberPO::getOwnerFlag)
                .orderByAsc(TenantMemberPO::getJoinTime));
        if (members.isEmpty()) {
            return List.of();
        }
        List<Long> userIds = members.stream().map(TenantMemberPO::getUserId).distinct().toList();
        Map<Long, UserPO> users = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(UserPO::getId, Function.identity()));
        return members.stream().map(member -> toVo(member, users.get(member.getUserId()))).toList();
    }

    @Override
    public MemberVo invite(InviteMemberRequest request) {
        long tenantId = TenantAccess.requireTenantId();
        UserPO user = userMapper.selectOne(
                Wrappers.<UserPO>lambdaQuery().eq(UserPO::getAccount, request.getAccount()));
        if (user == null) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "账号不存在；邀请只能针对已注册账号，请先让对方注册");
        }
        if (!Integer.valueOf(ENABLED).equals(user.getStatus())) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "该账号已停用，无法加入租户");
        }
        if (tenantMemberMapper.selectByTenantAndUser(tenantId, user.getId()) != null) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "该账号已是本租户成员");
        }

        TenantMemberPO member = new TenantMemberPO();
        member.setUserId(user.getId());
        member.setOwnerFlag(NORMAL_MEMBER);
        member.setStatus(ENABLED);
        member.setJoinTime(LocalDateTime.now());
        tenantMemberMapper.insert(member);
        log.info("邀请成员: tenantId={}, userId={}", tenantId, user.getId());
        return toVo(member, user);
    }

    @Override
    public void updateStatus(Long memberId, Integer status) {
        TenantAccess.requireTenantId();
        TenantMemberPO member = requireMember(memberId);
        if (Integer.valueOf(OWNER).equals(member.getOwnerFlag())) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "owner 不能被停用；如需变更请先进入转让流程");
        }
        TenantMemberPO update = new TenantMemberPO();
        update.setId(member.getId());
        update.setStatus(status);
        tenantMemberMapper.updateById(update);
        log.info("成员状态变更: tenantId={}, memberId={}, status={}",
                TenantAccess.requireTenantId(), memberId, status);
    }

    @Override
    public void remove(Long memberId) {
        TenantAccess.requireOwner();
        TenantMemberPO member = requireMember(memberId);
        if (Integer.valueOf(OWNER).equals(member.getOwnerFlag())) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "owner 不能被移除；如需变更请先进入转让流程");
        }
        tenantMemberMapper.deleteByTenantAndUser(member.getTenantId(), member.getUserId());
        log.info("成员移除: tenantId={}, memberId={}", TenantAccess.requireTenantId(), memberId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void transferOwner(Long targetUserId) {
        long tenantId = TenantAccess.requireTenantId();
        long actorUserId = TenantAccess.requireUserId();
        if (targetUserId == actorUserId) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "目标成员已经是本租户 owner");
        }
        TenantMemberPO target = tenantMemberMapper.selectByTenantAndUser(tenantId, targetUserId);
        if (target == null || !Integer.valueOf(ENABLED).equals(target.getStatus())) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "目标成员不在本租户或已停用");
        }

        TenantMemberPO currentOwner = tenantMemberMapper.selectByTenantAndUser(tenantId, actorUserId);
        if (currentOwner == null || !Integer.valueOf(OWNER).equals(currentOwner.getOwnerFlag())) {
            throw new BizException(ErrorCode.NOT_OWNER, "该操作仅限租户 owner");
        }

        // 两次更新同处一个事务，失败整体回滚，绝不会出现 0 个或 2 个 owner 的中间态
        TenantMemberPO demote = new TenantMemberPO();
        demote.setId(currentOwner.getId());
        demote.setOwnerFlag(NORMAL_MEMBER);
        tenantMemberMapper.updateById(demote);

        TenantMemberPO promote = new TenantMemberPO();
        promote.setId(target.getId());
        promote.setOwnerFlag(OWNER);
        tenantMemberMapper.updateById(promote);

        log.info("owner 转让: tenantId={}, fromUserId={}, toUserId={}", tenantId, actorUserId, targetUserId);
    }

    /** 按 ID 取本租户成员；跨租户 ID 会被租户改写过滤成 null。 */
    private TenantMemberPO requireMember(Long memberId) {
        TenantMemberPO member = tenantMemberMapper.selectById(memberId);
        if (member == null) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "成员不存在或不属于当前租户");
        }
        return member;
    }

    private static MemberVo toVo(TenantMemberPO member, UserPO user) {
        MemberVo vo = new MemberVo();
        vo.setMemberId(member.getId());
        vo.setUserId(member.getUserId());
        vo.setOwnerFlag(member.getOwnerFlag());
        vo.setStatus(member.getStatus());
        vo.setJoinTime(member.getJoinTime());
        if (user != null) {
            vo.setAccount(user.getAccount());
            vo.setName(user.getName());
            vo.setEmail(user.getEmail());
            vo.setPhone(user.getPhone());
            vo.setUserStatus(user.getStatus());
        }
        return vo;
    }

}
