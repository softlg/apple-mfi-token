package com.galaplat.mfi.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一个 token 的结果行。
 *
 * <p>这里刻意没有 {@code authEntityId}、{@code secretKey}、{@code accessoryUUID} 与
 * {@code encodedToken}：列表页只用来核对"到货与号段"，凭证材料只在导出文件里给，
 * 出现在列表响应就等于把整份 token 摊在浏览器历史、访问日志与前端埋点里。
 */
@Data
public class TokenVo {

    private Long id;

    private String ppid;

    /** 制造序列号，FINISH 阶段分配；为空表示这批还没走完 */
    private String manufactureSn;

    /** TokenStatus 枚举名 */
    private String status;

    /** RegisterStatus 枚举名 */
    private String registerStatus;

    private LocalDateTime downloadTime;

    private LocalDateTime registerTime;

}
