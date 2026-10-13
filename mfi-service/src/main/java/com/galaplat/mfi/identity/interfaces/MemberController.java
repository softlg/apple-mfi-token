package com.galaplat.mfi.identity.interfaces;

import com.galaplat.mfi.identity.interfaces.InviteMemberRequest;
import com.galaplat.mfi.identity.interfaces.MemberStatusRequest;
import com.galaplat.mfi.identity.interfaces.TransferOwnerRequest;
import com.galaplat.mfi.identity.interfaces.MemberVo;
import com.galaplat.mfi.identity.application.MemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 成员管理。本产品没有角色与权限点，只有 owner 这一位，标 [owner] 的接口在服务端强制校验。
 */
@Tag(name = "成员管理")
@RestController
@RequestMapping("/api/members")
public class MemberController {

    private final MemberService memberService;

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    @Operation(summary = "本租户成员列表", description = "不含任何凭证字段")
    @GetMapping
    public List<MemberVo> list() {
        return memberService.list();
    }

    @Operation(summary = "邀请成员", description = "按登录账号把已注册用户加入当前租户，普通成员身份")
    @PostMapping("/invite")
    public MemberVo invite(@Valid @RequestBody InviteMemberRequest request) {
        return memberService.invite(request);
    }

    @Operation(summary = "启停成员", description = "owner 成员不允许停用")
    @PatchMapping("/{memberId}/status")
    public void updateStatus(@PathVariable Long memberId, @Valid @RequestBody MemberStatusRequest request) {
        memberService.updateStatus(memberId, request.getStatus());
    }

    @Operation(summary = "移除成员", description = "[owner] 高危操作，仅 owner 可调")
    @DeleteMapping("/{memberId}")
    public void remove(@PathVariable Long memberId) {
        memberService.remove(memberId);
    }

    @Operation(summary = "转让 owner", description = "[owner] 高危操作，转让后操作人立即降为普通成员")
    @PostMapping("/transfer-owner")
    public void transferOwner(@Valid @RequestBody TransferOwnerRequest request) {
        memberService.transferOwner(request.getUserId());
    }

}
