package com.galaplat.mfi.pipeline;

import com.galaplat.mfi.common.enums.Stage;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 一个阶段处理的结论。只有四种，刻意不让处理器自己写状态机：状态跃迁的正确性
 * （CAS 前态、重试计数、消息投递）全部集中在 {@link StageRunner}，处理器只回答"这次怎么样"。
 *
 * @param kind       结论类型
 * @param stage      本次处理的阶段，用于跃迁时校验前态
 * @param requestUuid APPLY 阶段拿到的苹果 request id，前进时一并落库；其余阶段为 null
 * @param executeAt    WAIT/RETRY 的下一次执行时间
 * @param reason       失败或等待的摘要，只写错误码与文案，禁止带 token 内容
 */
public record StageOutcome(Kind kind, Stage stage, String requestUuid, LocalDateTime executeAt, String reason) {

    public enum Kind {
        /** 本阶段完成，前进到下一阶段（FINISH 则为终态成功） */
        ADVANCE,
        /** 条件未就绪（苹果还在生成文件、租户并发额度被占满），按 executeAt 稍后再来，不消耗重试次数 */
        WAIT,
        /** 本次失败，按 executeAt 退避重试，消耗一次重试 */
        RETRY,
        /** 永久失败或重试耗尽，进 DEAD 与死信队列 */
        DEAD
    }

    public static StageOutcome advance(Stage stage) {
        return new StageOutcome(Kind.ADVANCE, stage, null, null, null);
    }

    public static StageOutcome advance(Stage stage, String requestUuid) {
        return new StageOutcome(Kind.ADVANCE, stage, requestUuid, null, null);
    }

    public static StageOutcome waitUntil(Stage stage, LocalDateTime executeAt, String reason) {
        return new StageOutcome(Kind.WAIT, stage, null, executeAt, reason);
    }

    public static StageOutcome retryAt(Stage stage, LocalDateTime executeAt, String reason) {
        return new StageOutcome(Kind.RETRY, stage, null, executeAt, reason);
    }

    public static StageOutcome dead(Stage stage, String reason) {
        return new StageOutcome(Kind.DEAD, stage, null, null, reason);
    }

    /** 延迟投递用的等待时长：到 executeAt 还要等多久，已经过期则返回 0（立即再来一次） */
    public long delayMillis() {
        if (executeAt == null) {
            return 0L;
        }
        long millis = Duration.between(LocalDateTime.now(), executeAt).toMillis();
        return Math.max(0L, millis);
    }

}
