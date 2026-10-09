-- =====================================================================================
-- apple-mfi-token  V1 baseline schema
-- MySQL 8.0 / InnoDB / utf8mb4
--
-- 约定：
--   1. 所有业务表必须含 tenant_id，由 MyBatis-Plus 租户拦截器自动改写 SQL 做隔离。
--      全局表（不参与租户过滤）仅 t_user、t_tenant 两张，见 MfiTenantLineHandler.GLOBAL_TABLES。
--   2. 主键统一 BIGINT UNSIGNED 自增；对外只暴露业务单号，不暴露自增 ID。
--   3. 本文件一经发布不得修改，任何变更走新的 Flyway 版本文件。
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- 全局：用户账号。一个用户可加入多个租户，故不属于任何单一租户。
-- -------------------------------------------------------------------------------------
CREATE TABLE t_user
(
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    account         VARCHAR(64)     NOT NULL COMMENT '登录账号，全局唯一',
    password_hash   VARCHAR(255)    NOT NULL COMMENT '密码哈希，禁止明文',
    name            VARCHAR(64)     NOT NULL COMMENT '姓名',
    email           VARCHAR(128)    DEFAULT NULL COMMENT '邮箱',
    phone           VARCHAR(32)     DEFAULT NULL COMMENT '手机号',
    status          TINYINT         NOT NULL DEFAULT 1 COMMENT '状态 1=启用 0=停用',
    last_login_time DATETIME        DEFAULT NULL COMMENT '最近登录时间',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=否 1=是',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_account (account),
    KEY idx_user_email (email)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='用户账号（全局表）';

-- -------------------------------------------------------------------------------------
-- 全局：租户 = 一家 MFI 被授权厂商
-- -------------------------------------------------------------------------------------
CREATE TABLE t_tenant
(
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_code      VARCHAR(64)     NOT NULL COMMENT '租户编码，全局唯一',
    tenant_name      VARCHAR(128)    NOT NULL COMMENT '租户名称',
    apple_technology VARCHAR(32)     NOT NULL DEFAULT 'DEFAULT' COMMENT '苹果 technology，决定 BaseURL 分流',
    apple_base_url   VARCHAR(255)    DEFAULT NULL COMMENT '覆盖默认 BaseURL，联调桩或自建代理时使用',
    status           TINYINT         NOT NULL DEFAULT 1 COMMENT '状态 1=启用 0=暂停',
    create_time      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted          TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=否 1=是',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_code (tenant_code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='租户（全局表）';

-- -------------------------------------------------------------------------------------
-- 成员关系：owner_flag 是唯一的角色位，只圈住高危操作，不构成权限体系
-- -------------------------------------------------------------------------------------
CREATE TABLE t_tenant_member
(
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id  BIGINT UNSIGNED NOT NULL COMMENT '租户 ID',
    user_id    BIGINT UNSIGNED NOT NULL COMMENT '用户 ID',
    owner_flag TINYINT         NOT NULL DEFAULT 0 COMMENT '是否租户 owner，1=是。仅用于圈住凭证/配置/成员管理类高危操作',
    status     TINYINT         NOT NULL DEFAULT 1 COMMENT '状态 1=启用 0=停用',
    join_time  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
    create_time DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted    TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=否 1=是',
    PRIMARY KEY (id),
    UNIQUE KEY uk_member_tenant_user (tenant_id, user_id),
    KEY idx_member_user (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='租户成员关系';

-- -------------------------------------------------------------------------------------
-- 租户凭证：证书与私钥密文。明文永不入库、永不回显、永不进日志。
-- 密文格式 Base64(iv(12B) || ciphertext || gcm_tag)，AAD 绑定 tenant_id 防跨租户密文替换。
-- -------------------------------------------------------------------------------------
CREATE TABLE t_tenant_credential
(
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id       BIGINT UNSIGNED NOT NULL COMMENT '租户 ID',
    cert_cipher     TEXT            NOT NULL COMMENT 'X.509 证书密文',
    key_cipher      TEXT            NOT NULL COMMENT '客户端私钥密文',
    encrypt_key_id  VARCHAR(64)     NOT NULL COMMENT '所用主密钥标识，支持主密钥轮换',
    fingerprint     VARCHAR(128)    NOT NULL COMMENT '证书 SHA-256 指纹，可用于展示与对账',
    cert_subject    VARCHAR(255)    DEFAULT NULL COMMENT '证书 Subject DN',
    cert_not_after  DATETIME        DEFAULT NULL COMMENT '证书到期时间，到期前预警',
    version         INT             NOT NULL DEFAULT 1 COMMENT '凭证版本号，每次替换递增',
    update_by       BIGINT UNSIGNED DEFAULT NULL COMMENT '最后替换人 user_id',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=否 1=是',
    PRIMARY KEY (id),
    UNIQUE KEY uk_credential_tenant (tenant_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='租户 MFI 客户端凭证（加密存储）';

-- -------------------------------------------------------------------------------------
-- 租户级业务参数：替代 MES 中散落的 Java 硬编码常量，改为一处可配 + 全局默认
-- -------------------------------------------------------------------------------------
CREATE TABLE t_tenant_config
(
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id    BIGINT UNSIGNED NOT NULL COMMENT '租户 ID',
    config_key   VARCHAR(64)     NOT NULL COMMENT '配置键，见 TenantConfigKey 枚举',
    config_value VARCHAR(255)    NOT NULL COMMENT '配置值',
    remark       VARCHAR(255)    DEFAULT NULL COMMENT '备注',
    create_time  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted      TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=否 1=是',
    PRIMARY KEY (id),
    UNIQUE KEY uk_config_tenant_key (tenant_id, config_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='租户级业务参数';

-- -------------------------------------------------------------------------------------
-- PPID 池：租户内共享。扣减一律用条件更新做 CAS，禁止读后写。
-- -------------------------------------------------------------------------------------
CREATE TABLE t_ppid
(
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id        BIGINT UNSIGNED NOT NULL COMMENT '租户 ID',
    ppid             VARCHAR(64)     NOT NULL COMMENT '苹果产品计划 ID',
    spu              VARCHAR(64)     NOT NULL COMMENT 'SPU',
    product_model    VARCHAR(64)     NOT NULL COMMENT '产品型号，PPID 选池维度',
    total_quantity   INT             NOT NULL DEFAULT 0 COMMENT '苹果授予的可申请总量',
    applied_quantity INT             NOT NULL DEFAULT 0 COMMENT '已申请量',
    remaining_quantity INT           NOT NULL DEFAULT 0 COMMENT '剩余可申请量，扣减需 CAS 条件更新',
    status           TINYINT         NOT NULL DEFAULT 1 COMMENT '状态 1=可用 0=停用（余量归零自动置 0）',
    remark           VARCHAR(255)    DEFAULT NULL COMMENT '备注',
    create_by        BIGINT UNSIGNED DEFAULT NULL COMMENT '创建人 user_id',
    create_time      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted          TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=否 1=是',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ppid_tenant_ppid_spu (tenant_id, ppid, spu),
    KEY idx_ppid_pool (tenant_id, product_model, spu, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='PPID 池';

-- -------------------------------------------------------------------------------------
-- 申请单：一次 token 申请。可能拆分到多个 PPID，故与 t_application_ppid 一对多。
-- -------------------------------------------------------------------------------------
CREATE TABLE t_application
(
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id         BIGINT UNSIGNED NOT NULL COMMENT '租户 ID',
    application_no    VARCHAR(64)     NOT NULL COMMENT '申请单号，对外暴露的业务标识',
    applicant_user_id BIGINT UNSIGNED NOT NULL COMMENT '发起人 user_id',
    applicant_name    VARCHAR(64)     NOT NULL COMMENT '发起人姓名快照',
    product_model     VARCHAR(64)     NOT NULL COMMENT '产品型号',
    spu               VARCHAR(64)     NOT NULL COMMENT 'SPU',
    apple_technology  VARCHAR(32)     NOT NULL COMMENT '发起时使用的 technology 快照',
    apply_quantity    INT             NOT NULL COMMENT '申请总量',
    ppid_count        INT             NOT NULL DEFAULT 0 COMMENT '拆分的 PPID 分片数',
    token_count       INT             NOT NULL DEFAULT 0 COMMENT '已成功落库的 token 数',
    status            VARCHAR(24)     NOT NULL DEFAULT 'RUNNING' COMMENT 'PENDING/RUNNING/SUCCESS/PARTIAL_FAILED/FAILED',
    fail_reason       VARCHAR(512)    DEFAULT NULL COMMENT '失败摘要',
    create_time       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    finish_time       DATETIME        DEFAULT NULL COMMENT '终态时间',
    deleted           TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=否 1=是',
    PRIMARY KEY (id),
    UNIQUE KEY uk_application_no (application_no),
    KEY idx_application_tenant (tenant_id, status, create_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='Token 申请单';

-- -------------------------------------------------------------------------------------
-- 申请分片与阶段状态机。
-- 关键设计：一行 = 一个 PPID 分片的阶段推进位。这是对 MES t_token_process_log
-- 「按 (operation_id,ppid) upsert 只保留最新一步」的修正 —— 那份数据既无法还原进度，
-- 也无法判断卡点。阶段跳转的历史另记 t_operation_log。
-- -------------------------------------------------------------------------------------
CREATE TABLE t_application_ppid
(
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id         BIGINT UNSIGNED NOT NULL COMMENT '租户 ID',
    application_id    BIGINT UNSIGNED NOT NULL COMMENT '申请单 ID',
    ppid              VARCHAR(64)     NOT NULL COMMENT '本分片使用的 PPID',
    ppid_id           BIGINT UNSIGNED NOT NULL COMMENT 't_ppid 主键，用于回写申请量',
    allocate_count    INT             NOT NULL COMMENT '本分片分配到的数量',
    stage             VARCHAR(20)     NOT NULL DEFAULT 'APPLY' COMMENT '阶段 APPLY/FILE_NAME/FILE_CONTENT/REGISTER/FINISH',
    stage_status      VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/SUCCESS/FAILED/DEAD',
    apple_request_uuid VARCHAR(64)    DEFAULT NULL COMMENT '苹果 requestAuthEntities 返回的 request id',
    retry_count       INT             NOT NULL DEFAULT 0 COMMENT '当前阶段已重试次数',
    next_execute_time DATETIME        DEFAULT NULL COMMENT '延迟/重试的下一次执行时间，兜底扫描依据',
    last_error        VARCHAR(512)    DEFAULT NULL COMMENT '最近一次失败摘要',
    create_time       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted           TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=否 1=是',
    PRIMARY KEY (id),
    UNIQUE KEY uk_shard_application_ppid (application_id, ppid),
    KEY idx_shard_scan (stage_status, next_execute_time),
    KEY idx_shard_tenant (tenant_id, stage, stage_status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='申请单 PPID 分片与阶段状态机';

-- -------------------------------------------------------------------------------------
-- 苹果下发的文件（每个 request 若干文件，逐文件独立下载推进）
-- -------------------------------------------------------------------------------------
CREATE TABLE t_token_file
(
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id          BIGINT UNSIGNED NOT NULL COMMENT '租户 ID',
    application_id     BIGINT UNSIGNED NOT NULL COMMENT '申请单 ID',
    shard_id           BIGINT UNSIGNED NOT NULL COMMENT 't_application_ppid 主键',
    ppid               VARCHAR(64)     NOT NULL COMMENT 'PPID',
    apple_request_uuid VARCHAR(64)     NOT NULL COMMENT '苹果 request id',
    file_name          VARCHAR(255)    NOT NULL COMMENT '苹果返回的文件名',
    token_count        INT             NOT NULL DEFAULT 0 COMMENT '文件内 token 数',
    download_status    VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/SUCCESS/FAILED',
    retry_count        INT             NOT NULL DEFAULT 0 COMMENT '重试次数',
    last_error         VARCHAR(512)    DEFAULT NULL COMMENT '最近一次失败摘要',
    download_time      DATETIME        DEFAULT NULL COMMENT '下载成功时间',
    create_time        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted            TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=否 1=是',
    PRIMARY KEY (id),
    UNIQUE KEY uk_file_tenant_request_name (tenant_id, apple_request_uuid, file_name),
    KEY idx_file_shard (shard_id, download_status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='Token 下载文件';

-- -------------------------------------------------------------------------------------
-- Token 结果表。
-- 合并了 MES 的 t_token_detail 与 t_token_download_content：secret_key 与下载状态分表存
-- 时，推进要跨表判断，合并后一次条件 UPDATE 即可完成状态跃迁。
-- auth_entity_id 与 secret_key 属敏感数据，禁止进日志。
-- -------------------------------------------------------------------------------------
CREATE TABLE t_token
(
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id       BIGINT UNSIGNED NOT NULL COMMENT '租户 ID',
    application_id  BIGINT UNSIGNED NOT NULL COMMENT '申请单 ID',
    shard_id        BIGINT UNSIGNED NOT NULL COMMENT 't_application_ppid 主键',
    ppid            VARCHAR(64)     NOT NULL COMMENT 'PPID',
    file_id         BIGINT UNSIGNED DEFAULT NULL COMMENT '来源文件 t_token_file 主键',
    auth_entity_id  VARCHAR(128)    NOT NULL COMMENT '苹果认证实体 ID（token），敏感，禁止日志',
    accessory_uuid  VARCHAR(64)     DEFAULT NULL COMMENT '配件 UUID，注册时生成',
    secret_key      VARCHAR(255)    DEFAULT NULL COMMENT '加密密钥，敏感，禁止日志',
    manufacture_sn  VARCHAR(64)     DEFAULT NULL COMMENT '本服务生成的制造序列号',
    sku             VARCHAR(64)     DEFAULT NULL COMMENT '苹果返回的 SKU 列',
    status          VARCHAR(20)     NOT NULL DEFAULT 'ISSUED' COMMENT 'ISSUED/REGISTERED/SCRAPPED/DESTROYED',
    register_status VARCHAR(16)     NOT NULL DEFAULT 'NOT_REQUIRED' COMMENT 'NOT_REQUIRED/PENDING/PROCESSING/SUCCESS/FAILED',
    download_time   DATETIME        DEFAULT NULL COMMENT '下载落库时间',
    register_time   DATETIME        DEFAULT NULL COMMENT '注册时间',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=否 1=是',
    PRIMARY KEY (id),
    UNIQUE KEY uk_token_tenant_entity (tenant_id, auth_entity_id),
    KEY idx_token_application (tenant_id, application_id, status),
    KEY idx_token_ppid (tenant_id, ppid, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='Token 认证实体';

-- -------------------------------------------------------------------------------------
-- 操作与阶段跳转留痕
-- -------------------------------------------------------------------------------------
CREATE TABLE t_operation_log
(
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id        BIGINT UNSIGNED NOT NULL COMMENT '租户 ID',
    application_id   BIGINT UNSIGNED DEFAULT NULL COMMENT '申请单 ID，非申请类操作可空',
    shard_id         BIGINT UNSIGNED DEFAULT NULL COMMENT '分片 ID',
    action           VARCHAR(32)     NOT NULL COMMENT 'APPLY/STAGE_ADVANCE/RETRY/CREDENTIAL_UPDATE/MEMBER_REMOVE/DESTROY/EXPORT 等',
    operator_user_id BIGINT UNSIGNED DEFAULT NULL COMMENT '操作人 user_id，系统动作可空',
    operator_name    VARCHAR(64)     DEFAULT NULL COMMENT '操作人姓名快照',
    quantity         INT             DEFAULT NULL COMMENT '涉及数量',
    detail           VARCHAR(1024)   DEFAULT NULL COMMENT '明细，禁止写入 auth_entity_id/secret_key/凭证内容',
    create_time      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_oplog_tenant (tenant_id, create_time),
    KEY idx_oplog_application (application_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='操作日志';

-- -------------------------------------------------------------------------------------
-- 异步导出任务（token 量大时不能同步出流）
-- -------------------------------------------------------------------------------------
CREATE TABLE t_export_task
(
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id      BIGINT UNSIGNED NOT NULL COMMENT '租户 ID',
    task_no        VARCHAR(64)     NOT NULL COMMENT '任务号',
    request_user_id BIGINT UNSIGNED NOT NULL COMMENT '发起人 user_id',
    export_format  VARCHAR(16)     NOT NULL DEFAULT 'CSV' COMMENT 'CSV/XLSX',
    param_snapshot VARCHAR(1024)   DEFAULT NULL COMMENT '筛选条件快照，禁止含敏感值',
    status         VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/SUCCESS/FAILED',
    file_path      VARCHAR(255)    DEFAULT NULL COMMENT '产物路径',
    total_rows     INT             DEFAULT NULL COMMENT '导出行数',
    fail_reason    VARCHAR(512)    DEFAULT NULL COMMENT '失败原因',
    create_time    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    finish_time    DATETIME        DEFAULT NULL COMMENT '完成时间',
    deleted        TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=否 1=是',
    PRIMARY KEY (id),
    UNIQUE KEY uk_export_task_no (task_no),
    KEY idx_export_tenant (tenant_id, status, create_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='异步导出任务';
