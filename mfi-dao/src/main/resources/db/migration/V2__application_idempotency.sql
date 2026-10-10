-- 申请幂等键。
--
-- 提交按钮重复点击、前端超时重试，在 MES 里都会变成"同一笔需求扣走两份额度"。库里加一列客户端
-- 生成的键，配合唯一索引把去重交给数据库：重复提交的第二次插入会撞键，申请事务连同已做的扣减一起
-- 回滚，服务再按同一个键读回首次那张单返回，用户看到的是同一张申请单而不是报错。
--
-- MySQL 的唯一索引不约束 NULL，所以不传幂等键的请求之间永不互相冲突。
ALTER TABLE t_application
    ADD COLUMN idempotency_key VARCHAR(64) DEFAULT NULL COMMENT '客户端幂等键，空则不去重' AFTER finish_time,
    ADD UNIQUE KEY uk_application_idempotency (tenant_id, idempotency_key);
