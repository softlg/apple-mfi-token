package com.galaplat.mfi.pipeline;

import com.galaplat.mfi.common.enums.Stage;

import java.io.IOException;

/**
 * 一个阶段的业务处理。实现类只做三件事：读库、调苹果、把结果写成 {@link StageOutcome}。
 *
 * <p>不写状态机、不投消息、不绑租户上下文——那些都在 {@link StageRunner} 里，五个阶段共用一套
 * 跃迁与重试语义。这样"重试上限""延迟档位""死信"只有一处实现，改一处就五个阶段同时生效。
 *
 * <p>异常约定：{@link IOException}（网络、握手、解析）视为暂时故障，按退避重试；
 * {@code BizException}（凭证缺失/无效、数据不合法）视为永久故障，直接终态失败——
 * 重复提交一份注定被苹果拒绝的请求没有意义，只会白占出口 IP 的配额。
 */
public interface StageHandler {

    Stage stage();

    StageOutcome handle(StageContext context) throws IOException;

}
