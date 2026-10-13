package com.galaplat.mfi.identity.application;

import com.galaplat.mfi.identity.interfaces.InviteMemberRequest;
import com.galaplat.mfi.identity.interfaces.MemberVo;

import java.util.List;

/**
 * 租户成员管理。所有方法都以当前租户上下文为边界，不接受外部传入的 tenantId。
 */
public interface MemberService {

    List<MemberVo> list();

    MemberVo invite(InviteMemberRequest request);

    void updateStatus(Long memberId, Integer status);

    /** 移除成员，高危操作，仅 owner */
    void remove(Long memberId);

    /** 转让 owner，高危操作，仅 owner */
    void transferOwner(Long targetUserId);

}
