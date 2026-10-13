package com.galaplat.mfi.issuance.interfaces;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 销毁未使用的认证实体。
 *
 * <p>{@code confirmApplicationNo} 是二次确认，不是复选框：它要求把申请单号原样再输一遍，
 * 只勾一个"我确认"的前端组件挡不住误操作，而这里的动作在苹果侧不可逆。
 *
 * <p>销毁只可能发生在"已下载到本系统、还没注册回苹果"的 token 上，所以条件比导出更窄：
 * 申请单号必填（不给全租户批量销毁的入口），PPID 可选（用来收掉某个 PPID 的余量）。
 */
@Data
public class TokenDestroyRequest {

    @NotBlank(message = "申请单号不能为空")
    @Size(max = 64, message = "申请单号过长")
    private String applicationNo;

    /** 必须与 {@code applicationNo} 完全一致，否则拒绝 */
    @NotBlank(message = "请再次输入申请单号以确认销毁")
    @Size(max = 64, message = "确认申请单号过长")
    private String confirmApplicationNo;

    @Size(max = 64, message = "PPID 过长")
    private String ppid;

}
