package com.galaplat.mfi.identity.domain;

import java.time.LocalDateTime;

/**
 * 一条操作留痕的事实。
 *
 * <p>留痕表只追加、不修改，所以这里是不变记录而不是可写实体。写入侧不带 {@code createTime}
 * （时间戳由库的默认值维护），读取侧才带回它。
 *
 * <p>{@code detail} 的约束沿用表上的约定：禁止写 auth_entity_id、secret_key、凭证内容与 PPID 全量清单，
 * 这张表会被导出与排障脚本整行读出来。需要明细就按 applicationId / shardId 回业务表查。
 *
 * @param tenantId       归属租户
 * @param applicationId  关联申请单，非单据级动作可为空
 * @param shardId        关联额度分片，单据级动作可为空
 * @param action         动作名
 * @param operatorUserId 操作人，系统动作可空
 * @param operatorName   操作人姓名快照，系统动作可空
 * @param quantity       本次动作涉及的数量（分片额度、重试次数、导出行数等），可空
 * @param detail         可读摘要
 * @param createTime     库侧时间戳，写入时为 null
 */
public record AuditFact(long tenantId, Long applicationId, Long shardId, OperationAction action,
                        Long operatorUserId, String operatorName, Integer quantity, String detail,
                        LocalDateTime createTime) {

    /** 写入侧工厂：留痕时间由库维护，调用方不传 */
    public static AuditFact of(long tenantId, Long applicationId, Long shardId, OperationAction action,
                               Long operatorUserId, String operatorName, Integer quantity, String detail) {
        return new AuditFact(tenantId, applicationId, shardId, action, operatorUserId, operatorName,
                quantity, detail, null);
    }

    /** 系统动作（阶段推进、自动重试、终态失败）：没有操作人，读侧展示成"系统" */
    public static AuditFact system(long tenantId, long applicationId, long shardId, OperationAction action,
                                   Integer quantity, String detail) {
        return of(tenantId, applicationId, shardId, action, null, null, quantity, detail);
    }

}
