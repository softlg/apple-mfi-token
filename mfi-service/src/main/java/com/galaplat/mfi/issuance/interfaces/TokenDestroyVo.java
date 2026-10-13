package com.galaplat.mfi.issuance.interfaces;

import lombok.Data;

/**
 * 一次销毁的结果。
 *
 * <p>刻意不用"成功就 200、失败就报错"来表达：销毁在苹果侧不可逆，一次分批的销毁完全可能是
 * 前三批成功、第四批被拒。把这种结果压成一个异常返回，用户看到的就是一个错误页，
 * 而真实情况是"已经有 N 个 token 在苹果侧没了"。所以这里永远如实回数量，失败原因只是附带说明。
 */
@Data
public class TokenDestroyVo {

    private String applicationNo;

    /** 本次挑出并提交给苹果的候选数（都是已下载未注册的 ISSUED 行） */
    private int selected;

    /** 苹果已受理且本地已落 DESTROYED 的数量 */
    private int destroyed;

    /** 该单里还有没销毁完的（取满单次上限，或中途有批次被拒） */
    private boolean hasMore;

    /** 某一批被苹果拒绝时的人类可读原因；为空表示没有批次失败 */
    private String failReason;

}
