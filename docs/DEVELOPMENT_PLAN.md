# apple-mfi-token 开发计划

> 状态：**P0、P1 已完成并本机验收全绿**（P1 为 33 项断言 0 失败，见 §11）。`mvn test` 9 项全绿；
> `local` profile 在真实 MySQL 8.0 上跑通注册/登录/成员管理与跨租户渗透自测，Redis 承载 sa-token 会话；
> RabbitMQ 亦已本机就绪（§10），P2–P7 无外部依赖阻塞。

> 决策记录见 §13。

---

## 1. 产品定位与边界

`apple-mfi-token` 是一个**独立的多租户 SaaS**，帮 MFI 被授权厂商完成苹果 Software Token 的申请、下载、注册全链路。

- `[已定]` 独立新仓库，仓库名 `apple-mfi-token`，路径 `D:\code\apple-mfi-token`
- `[已定]` 与 `galaplat-development-mes` **并行独立**：不共库、不互调、不共享配置中心
- `[已定]` 每租户自带 MFI 证书，PPID 池按租户隔离
- `[已定]` 只做用户管理，**不做权限管理**（无角色、无菜单权限、无数据权限）
- `[已定]` 交付后端服务 + Vue3 前端界面

### 核心概念

| 概念 | 含义 | 隔离性 |
|---|---|---|
| 租户 Tenant | 一家 MFI 被授权厂商，持有自己的苹果客户端证书 | 顶级隔离单位 |
| 用户 User | 一个自然人账号，全局唯一 | **全局**，不属于租户 |
| 成员 Member | 用户与租户的关系（加入/停用） | 租户内 |
| PPID | 产品计划 ID，带 SPU + 产品型号 + 可申请余量 | 租户内共享 |
| 申请单 Application | 一次 token 申请，可能拆分到多个 PPID | 租户内共享 |
| Token | 苹果下发的认证实体（auth entity） | 租户内共享 |

**"不做权限管理"的确切边界**：任何已登录成员都能查看/操作本租户的 PPID、申请单、token，没有角色与数据范围控制。唯一的例外是 `[已定]` 保留 `owner_flag` 单一布尔位，用于圈住三个"能让全租户申请立即挂掉"的操作：

1. 上传/替换/删除 MFI 凭证
2. 修改租户级规则配置
3. 移除成员、转让 owner、销毁 token

这不是权限体系，是一个布尔位。不引入 `t_role`、`t_permission`、不做菜单渲染控制。

### 明确不做

- 不做角色/RBAC/ABAC、不做菜单权限、不做数据权限范围
- 不做平台超管后台（租户由自助注册产生，避免引入第二类角色）
- 不做苹果 MFI 门户的替代（PPID 仍由厂商在苹果侧获取后录入本系统）
- 不做产线烧录/工厂 MES 对接（只负责 token 的申请、下载、注册状态回写）

---

## 2. 为什么需要租户隔离拦截器（不是权限管理）

`galaplat-development-mes` 把数据范围拦截器的 `skipDataSourcesPermission` 默认设为 `"true"`，等于关闭。SaaS 场景下这是致命配置——一旦某个查询漏了 `tenant_id`，就是跨厂商数据泄露。

新仓库的策略：**租户隔离做在 SQL 层且不可关闭**。

- 采用 MyBatis-Plus `TenantLineInnerInterceptor`，自动为每张业务表的 SELECT/UPDATE/DELETE 追加 `tenant_id = ?`，INSERT 自动填充
- 白名单只允许 `t_user`、`t_tenant`、`flyway_schema_history` 三类全局表跳过
- `TenantContext` 缺失时 `requireTenantId()` **直接抛异常**，绝不降级为"不加条件"
- MQ 消费者在线程内显式 `TenantContext.bind(...)`，`finally` 中 `clear()`
- 每个 Mapper 集成测试必须包含一条"跨租户查不到"的断言

选 MyBatis-Plus 而非沿用 MES 的 plain MyBatis + PageHelper，正是为了拿到这个自动改写能力——手写 JSqlParser 改写既不现实也容易出错。代价是偏离 MES 的 DAO 代码风格，且 PO 上会有 `@TableName`/`@TableId` 注解。这一点需要你认可。

---

## 3. 技术栈与版本基线

基于 2026-10-09 对本机的实测：

| 项 | 取值 | 依据 |
|---|---|---|
| JDK | **17**（非 21） | 实测 `openjdk 17.0.15`，`JAVA_HOME=D:\tools\jdk17` |
| Maven | 3.9.12 | `~/.m2/settings.xml` 实际文件名是 `settings.xml22`（**未生效**），故走公网 Central |
| Central 连通 | HTTP 200 | 实测 `repo1.maven.org` 可达，不受内网 Nexus 限制 |
| 前端包管理 | **pnpm 10.14.0** | `npm` 已损坏（`node_modules/npm/bin/npm-prefix.js` 缺失，nvm 多版本错配），不可用 |
| Node | v25.0.0（PATH）/ v24.18.0（Qoder） | 存在多份 node，构建脚本不依赖版本特定的 corepack |

`[已定]` 纯净自建，不依赖 Nacos 与任何 `galaplat-*` 内部 jar，配置走环境变量 + `application.yml`。

| 依赖 | 版本 | 本地已缓存 |
|---|---|---|
| Spring Boot | 3.4.1 | 是（含 starter-amqp / starter-web / starter-data-redis / starter-validation） |
| MyBatis-Plus | 3.5.9（`mybatis-plus-spring-boot3-starter` **+ 必须另加 `mybatis-plus-jsqlparser`**） | starter 本地未缓存，P0 已从 Central 拉取成功 |
| Flyway | 10.20.1 + flyway-mysql | 部分（`flyway-mysql` 本地仅 9.22.3，需下载 10.x） |
| sa-token | 1.44.0（boot3-starter + **redis-jackson**，会话存 Redis；**已去掉 jwt 模块**） | `sa-token-redis-jackson` 由 P0 收尾时从 Central 拉取成功，传递引入 `sa-token-redis-template` 与 `spring-boot-starter-data-redis` |
| OkHttp | 4.12.0 | 是 |
| BouncyCastle | bcprov-jdk18on 1.78.1 | 是 |
| EasyExcel | 4.0.3 | 是 |
| Caffeine | 3.2.3 | 是 |
| mysql-connector-j | 9.1.0 | 是 |
| springdoc-openapi | 2.8.9 | 是 |
| Lombok | 1.18.36 | 是 |
| MySQL / Redis / RabbitMQ | 本机 MySQL 8.0.34（Windows 服务，3306）/ Redis 5.0.14.1（tporadowski 便携版）/ RabbitMQ 3.13.7 + OTP 26.2.5.5（免安装 zip） | 见 §10；`docker-compose.yml` 仍保留给服务器部署 |

`[P0 踩坑]` **MyBatis-Plus 3.5.9 把依赖 JSqlParser 的 inner 拦截器拆成了独立可选模块**，`spring-boot3-starter` 不再自带 `TenantLineInnerInterceptor` 与 `PaginationInnerInterceptor`，只引 starter 会编译期"找不到符号"。必须额外声明 `com.baomidou:mybatis-plus-jsqlparser`（连带 `jsqlparser` 5.0）。本项目的租户隔离完全依赖这两个类，缺一个都没有。

选 sa-token 而非手写 JWT：它已在本地仓库缓存、自带 Redis 会话存储与多端并发控制，且我们只用它的**认证**API（`login/logout/checkLogin/getLoginId`），不调用任何 `checkRole/checkPermission`——正好贴合"有认证无授权"。

---

## 4. 工程结构

收敛为 3 个 Java 模块 + 1 个前端目录（对齐 MES 的 `apple/dao/service` 分层，去掉空的 `-api` 模块和多余的 `-core/-pipeline` 拆分）：

```
apple-mfi-token/
├─ pom.xml                      父 POM，dependencyManagement 统一版本
├─ AGENTS.md                    仓库执行守则
├─ docker-compose.yml           MySQL + Redis + RabbitMQ 本地底座
├─ .env.example                 必需环境变量清单（不含真实值）
├─ docs/
│  ├─ DEVELOPMENT_PLAN.md       本文件
│  └─ mfi-token-flow.md         苹果 API 语义与状态机说明
├─ mfi-apple/                   苹果 MFI 客户端（多租户 mTLS）
│  └─ src/main/java/com/galaplat/mfi/apple/
│     ├─ MfiTokenClient.java        9 个苹果接口的封装
│     ├─ MfiSslContextBuilder.java  cert/key → SSLContext（PKCS8/PKCS1 兼容）
│     ├─ model/                     请求体
│     ├─ response/                  响应体 + 错误码
│     └─ util/                      DownloadAvailability 解析、BaseURL 路由
├─ mfi-dao/                     持久层
│  └─ src/main/
│     ├─ java/com/galaplat/mfi/dao/
│     │  ├─ entity/             PO（@TableName）
│     │  ├─ mapper/             Mapper 接口
│     │  ├─ tenant/             TenantContext、TenantPrincipal、TenantNotBoundException
│     │  └─ mybatis/            TenantLineHandler、插件装配
│     └─ resources/db/migration/V*.sql
└─ mfi-service/                 Spring Boot 应用（可执行）
   └─ src/main/
      ├─ java/com/galaplat/mfi/
      │  ├─ MfiTokenApplication.java
      │  ├─ controller/         auth / member / credential / ppid / application / token
      │  ├─ service/            业务服务 + impl
      │  ├─ client/             MfiClientFactory：按租户构造 + Caffeine 缓存
      │  ├─ pipeline/           RabbitMQ 配置、5 阶段监听器、DLQ
      │  ├─ security/           租户绑定拦截器、sa-token 装配、owner 校验
      │  ├─ crypto/             凭证 AES-256-GCM 加解密
      │  └─ excel/              PPID 导入模板、token 导出
```

`[P0 调整]` 两处与初版结构的偏离，都由依赖方向决定：

- **`MfiClientFactory` 从 `mfi-apple` 移到 `mfi-service/client`**。工厂要读 `t_tenant_credential` 并解密，若放在 `mfi-apple` 就得让 `mfi-apple → mfi-dao/crypto`，客户端库被拖进数据库依赖。现在 `mfi-apple` 保持纯 Java + OkHttp，工厂在上层组装。
- **`TenantContext` 放在 `mfi-dao/tenant` 而非 `mfi-service/security`**。租户拦截器在 DAO 层，它必须能读到上下文；若上下文在上层，`mfi-dao` 就反向依赖 `mfi-service`。放 DAO 层也让"上下文缺失即抛错"的守卫离执行点最近。
      │  └─ common/             统一响应、异常、枚举、分页
      └─ resources/
         ├─ application.yml
         ├─ application-local.yml   （gitignore）
         └─ mapper/*.xml            仅复杂查询
```

---

## 5. 数据库设计

Flyway 全量托管 DDL，禁止手工改库。业务表统一：`tenant_id BIGINT NOT NULL`、`create_time`、`update_time`、`deleted TINYINT DEFAULT 0`。主键 `BIGINT UNSIGNED AUTO_INCREMENT`，对外暴露业务单号而非自增 ID。

### 5.1 全局表

```sql
t_user              id, account(UK), password_hash, name, email, phone,
                    status, last_login_time, create_time, update_time, deleted
t_tenant            id, tenant_code(UK), tenant_name, apple_technology,
                    apple_base_url, status, create_time, update_time, deleted
```

### 5.2 租户关系与配置

```sql
t_tenant_member     id, tenant_id, user_id, owner_flag, status, join_time
                    UK(tenant_id, user_id)  IDX(user_id)

t_tenant_credential id, tenant_id(UK), cert_cipher, key_cipher,
                    encrypt_key_id, fingerprint, cert_subject,
                    cert_not_after, version, update_by, update_time
                    -- 证书与私钥密文。cert_cipher/key_cipher = Base64(iv || ciphertext || tag)
                    -- 明文永不入库、永不回显、永不进日志

t_tenant_config     id, tenant_id, config_key, config_value, remark
                    UK(tenant_id, config_key)
```

`t_tenant_config` 承载原 MES 里硬编码的业务参数，改为一处可配（缺失时取全局默认）：

| config_key（`TenantConfigKey`） | 全局默认 | MES 原状 |
|---|---|---|
| `APPLY_MAX_QUANTITY` | 0（不限） | 试产订单硬编码 100（`TokenApplyServiceImpl:36`） |
| `APPLE_TOKENS_PER_FILE` | 2000 | `constant/TokenConstant:90` |
| `APPLE_REGISTER_BATCH_SIZE` | 1000 | `TokenRegisterListener:33` |
| `PIPELINE_MAX_RETRY` | 3 | `constant/TokenConstant:84`（写死 2） |
| `PIPELINE_CONCURRENT_PER_TENANT` | 2 | 无对应，MES 单租户不需要 |
| `SERIAL_NUMBER_PREFIX` | 空（用 PPID 派生） | `SnUtil.generateSnPrefix`（§13.4 待定规则） |

`[P0 调整]` MES 的 `apply.quantity_ratio`（订单量 × 1.05，`TokenApplyConfig:22`）**已删除**：新模型没有产品订单概念，申请上限由 PPID 余量与 `APPLY_MAX_QUANTITY` 共同决定，系数无对应语义。

### 5.3 PPID 池

```sql
t_ppid              id, tenant_id, ppid, spu, product_model,
                    total_quantity, applied_quantity, remaining_quantity,
                    status, remark, create_by, create_time, update_time
                    UK(tenant_id, ppid, spu)   IDX(tenant_id, product_model, spu, status)
```

对应 MES `t_ppid_management`，保留"按 产品型号 + SPU 选池、按余量倒序、贪心多 PPID 拆分"的既有分配逻辑。扣减用 `UPDATE ... WHERE remaining_quantity >= ?` 条件更新（CAS），不依赖应用层读后写。

### 5.4 申请与阶段状态机

```sql
t_application       id, tenant_id, application_no(UK), applicant_user_id,
                    applicant_name, product_model, spu, apple_technology,
                    apply_quantity, ppid_count, status, fail_reason,
                    create_time, finish_time
                    IDX(tenant_id, status, create_time)

t_application_ppid  id, tenant_id, application_id, ppid, allocate_count,
                    stage, stage_status, apple_request_uuid,
                    retry_count, next_execute_time, last_error,
                    create_time, update_time
                    UK(application_id, ppid)   IDX(tenant_id, stage, stage_status)
```

**这是对 MES `t_token_process_log` 的关键改造**：MES 按 `(operation_id, ppid)` upsert 且只保留最新一步，它是游标不是流水，导致前端无法展示进度、也无法判断卡点。新表**一行 = 一个 PPID 分片的阶段推进位 + 全阶段留痕**（配合 `t_operation_log` 记录每次阶段跳转）。

```
stage        : APPLY → FILE_NAME → FILE_CONTENT → REGISTER → FINISH
stage_status : PENDING → PROCESSING → SUCCESS
                           ↘ FAILED（retry_count < max 时回到 PENDING 并设 next_execute_time）
                                     → DEAD（进 DLQ，需人工介入）
```

### 5.5 token 结果

```sql
t_token_file        id, tenant_id, application_id, ppid, apple_request_uuid,
                    file_name, token_count, download_status, retry_count,
                    last_error, download_time
                    UK(tenant_id, apple_request_uuid, file_name)

t_token             id, tenant_id, application_id, ppid,
                    auth_entity_id, accessory_uuid, secret_key, manufacture_sn,
                    status, register_status, download_time, register_time, create_time
                    UK(tenant_id, auth_entity_id)   IDX(tenant_id, application_id, status)

t_operation_log     id, tenant_id, application_id, action, operator_user_id,
                    operator_name, quantity, detail, create_time
                    IDX(tenant_id, application_id)
```

`t_token` 合并了 MES 的 `t_token_detail` + `t_token_download_content` 两张表——`secret_key` 与下载状态在 MES 里分表存，同步要跨表判断（`existNotDownloadSuccess` 闸门），合并后一次 UPDATE 即可完成状态跃迁。

### 5.6 序列号

`manufacture_sn` 由本服务生成，沿用 MES 的 Redis 自增方案（`CodeComponent` 无外部依赖，实测可自建）：key = `mfi:sn:{tenantId}:{ppid}:{yyyyMMdd}`，`INCRBY` 批量取号，`SnUtil` 的前缀规则移植到新仓库。

---

## 6. MFI 客户端与凭证安全

### 6.1 多租户客户端

`[风险]` MES 现状：`TokenAuthenticationConfig.createHttpClient()` 是**无参静态方法**，直接读 `TokenConfig.PATH/KEY` 常量，因此 `TokenClient` 无法参数化；且实测有 **9 个调用点**各自 `new TokenClient()`（`ApiAppleTokenController` 5 处 + 4 个流水线监听器），每次调用重建 SSLContext 与 OkHttpClient。

新设计：

```java
MfiTokenClient client = mfiClientFactory.getClient(tenantId);
```

- `MfiClientFactory` 从 `t_tenant_credential` 解密 → `MfiSslContextBuilder` 构造 SSLContext → Caffeine 缓存（`expireAfterAccess=30min`，凭证更新时按 tenantId `invalidate`）
- 所有租户**共享**一个 OkHttp `ConnectionPool` 与 `Dispatcher`，仅 `SSLContext` 不同，避免租户数增长导致连接池/线程数爆炸
- 保留 PKCS8 → PKCS1 的 `PrivateKeyInfo` 转换兜底（苹果两种都发过）
- `apple_base_url` 由 `apple_technology` 决定（MES 只区分 HomeKit 与非 HomeKit，其余走默认）
- 移除 `downloadTokenFile(..., HttpServletResponse)` —— Web 层职责不进客户端，只保留返回数据的 `downloadTokenFileData`

### 6.2 凭证加密

- AES-256-GCM，主密钥来自环境变量 `MFI_CREDENTIAL_MASTER_KEY`（Base64 的 32 字节），**不进 git、不进数据库、不进日志**
- 每条记录独立随机 12 字节 IV，密文格式 `Base64(iv || ciphertext || tag)`
- GCM `AAD` 绑定 `tenant_id`，防止跨租户密文替换
- `encrypt_key_id` 支持主密钥轮换（新写入用新 key，读旧记录用旧 key）
- 上传接口只回显 X.509 指纹、Subject、`notAfter`，永不回显明文；解析失败即时拒绝并给出可读错误
- 凭证读取与替换写 `t_operation_log`（只记动作与指纹，不记内容）

### 6.3 日志红线

`auth_entity_id`、`secret_key`、`accessory_uuid`、PPID 全量列表一律不得出现在日志。MES 里有 `log.info("...: {}", JSON.toJSONString(...))` 直接打整个 token 列表的写法（如 `registerUsedAuthEntitiesBulk` 打 `authEntities`），移植时必须改为打数量与 ID 摘要。

---

## 7. 申请链路设计

### 7.1 同步段（一次事务）

```
POST /api/applications {ppidAllocationMode, productModel, spu, applyQuantity}
 1. 参数校验
 2. 校验租户凭证存在且未过期（否则快速失败，不做无谓扣减）
 3. 按 (product_model, spu) + status=可用 查 PPID 池，余量倒序
 4. 校验 applyQuantity ≤ Σ 余量，且 ≤ 租户规则上限
 5. 贪心分配 → 逐条 CAS 扣减 remaining_quantity
 6. 写 t_application + N 条 t_application_ppid(stage=APPLY, status=PENDING)
 7. 写 t_operation_log
 8. 事务提交后发 MQ  ← 关键
```

`[修正]` MES 在 `@Transactional` 方法内部直接发消息（`TokenApplyServiceImpl:75`），事务回滚时消息已投递，形成"PPID 已回滚、苹果已申请"的幽灵单。新实现用 `TransactionSynchronizationManager.registerSynchronization(afterCommit)` 或 `@TransactionalEventListener(AFTER_COMMIT)` 保证**只在提交成功后发消息**。

`[修正]` MES 文档里的 Redis 分布式锁 `TOKEN_APPLY_LOCK:` 实际已不存在（代码中无 `RedisUtil` 引用），只剩单库事务。新实现不依赖锁，靠 `UPDATE ... WHERE remaining_quantity >= ?` 的条件更新保证不超卖；只有"同一 PPID 并发申请"需要更强保证时才补 Redis 锁。

`[修正]` MES 的 `is_can_apply` 已成孤儿字段（`apply()` 从不置 NO，无人恢复，唯一写入口是人工补偿端点）。新仓库**不设该字段**，申请单的可用性由 `t_application_ppid.stage_status` 聚合判断。

### 7.2 异步段（5 阶段流水线）

沿用 MES 的 RabbitMQ TTL + 死信回流做延迟（**不引入 delayed-message 插件**，减少部署面）：

```
exchange: mfi.token.normal        exchange: mfi.token.delay (TTL → DLX 回流 normal)

队列与阶段
  mfi.apply.queue                 → ApplyStageListener      调 requestAuthEntities，落 apple_request_uuid
  mfi.name.delay.queue → TTL →    → FileNameStageListener    调 authEntities/{uuid}，未就绪则按 retryDownloadAfter 投延迟
  mfi.content.queue               → FileContentStageListener 逐文件下载 xlsx → 落 t_token
  mfi.register.queue              → RegisterStageListener    按 PPID 分组、batch 调 usedAuthEntities/bulk
  mfi.finish.queue                → FinishStageListener      分配 SN、聚合状态、通知
  mfi.token.dlq                   ← 重试耗尽
```

阶段间不靠"猜延迟时间"，而是用苹果返回的 `downloadAvailability` / `retryDownloadAfter` 计算真实等待（移植 `UtcUtil.parse`，保留默认 1h 与 +1min 安全余量）。

### 7.3 幂等与并发

| 手段 | 说明 |
|---|---|
| CAS 阶段跃迁 | `UPDATE t_application_ppid SET stage_status=? WHERE id=? AND stage_status=?`，影响行数 0 即视为重复消费，直接 ACK |
| 唯一约束兜底 | `UK(tenant_id, auth_entity_id)` + `INSERT IGNORE`/`ON DUPLICATE KEY`，替代 MES 的 `saveBatchIfNotExists` 应用层去重 |
| 消息重投上限 | `retry_count < max_retry` 投延迟队列，耗尽进 DLQ 并告警 |
| 租户并发闸门 | 按 `tenant_id` 的信号量限制同时在飞的苹果请求数，防止单租户大批量占满消费者、也避免共用出口 IP 被苹果限流 |
| 扫描兜底 | 每分钟扫 `stage_status=PENDING AND next_execute_time < NOW()` 补发（MQ 丢消息时自愈）|

### 7.4 与 MES 的实现差异汇总

| # | MES 现状 | 新仓库处理 | 影响 |
|---|---|---|---|
| 1 | 事务内发 MQ | AFTER_COMMIT 发送 | 消除幽灵申请 |
| 2 | process_log upsert 只留最新 | 阶段分片表 + 全阶段留痕 | 前端可展示进度 |
| 3 | 重试 2 次后静默失败 | DLQ + 告警 + 扫描兜底 | 不再丢单 |
| 4 | 无并发锁，仅单库事务 | 条件更新 CAS | 不超卖 PPID |
| 5 | `is_can_apply` 孤儿字段 | 删除 | 语义清晰 |
| 6 | 业务参数硬编码常量 | `t_tenant_config` | 不发版可调 |
| 7 | `new TokenClient()` × 6 处 | `MfiClientFactory` + Caffeine | 多租户可行 |
| 8 | 证书 Base64 硬编码入库 | AES-256-GCM + 环境变量主密钥 | 见 §6.4 |
| 9 | 11 个人工补偿端点 | 收敛为 `POST /api/applications/{no}/retry` | 运维面收窄 |
| 10 | 无定时任务，靠手工 `GET /api/init/*` | MQ 驱动 + 兜底扫描 | 无需人工触发 |

### 6.4 需上报的安全事项

`galaplat-development-mes` 的 `galaplat-development-mes-apple/src/main/java/com/galaplat/development/config/TokenConfig.java:12,14` 将**苹果 MFI 客户端证书与 RSA 私钥以 Base64 明文硬编码并已提交进 git 历史**。应视为私钥已泄露：

1. 向苹果申请重新签发该 MFI 客户端证书
2. 旧仓库改为密钥中心/环境变量注入，并加 pre-commit 密钥扫描
3. 评估 git 历史是否需要清理

新仓库不会复制该凭证，实现时用占位测试证书。

---

## 8. API 清单

统一前缀 `/api`，统一响应包装 `{code, message, data, traceId}`，OpenAPI 由 springdoc 生成。

### 8.1 认证（只做认证，无授权）

```
POST   /api/auth/register           注册：创建用户 + 创建租户 + 置为 owner
POST   /api/auth/login              账号密码登录 → sa-token 令牌
POST   /api/auth/logout
GET    /api/auth/profile            当前用户 + 我所属的租户列表 + 当前生效租户
POST   /api/auth/switch-tenant      切换当前租户
```

一个用户可属多个租户（`t_tenant_member` 天然支持），切换只影响 `TenantContext`，不涉及权限计算。

### 8.2 成员管理（用户管理主体）

```
GET    /api/members                 本租户成员列表
POST   /api/members/invite          按 account 邀请已有用户加入
PATCH  /api/members/{id}/status     启用/停用
DELETE /api/members/{id}            移除                    [owner]
POST   /api/members/transfer-owner  转让 owner              [owner]
```

无角色字段、无权限点、无菜单树。

### 8.3 租户凭证与配置

```
GET    /api/tenant/credential           指纹/Subject/notAfter/version（无明文）
PUT    /api/tenant/credential           上传 cert + key             [owner]
DELETE /api/tenant/credential           删除                        [owner]
POST   /api/tenant/credential/verify    苹果连通性自检
GET    /api/tenant/config               规则配置
PUT    /api/tenant/config               修改规则                    [owner]
PUT    /api/tenant/profile              租户基本信息（名称/technology） [owner]
```

### 8.4 PPID 池

```
GET    /api/ppid/page               分页 + 型号/SPU/状态筛选
POST   /api/ppid                    新增
PUT    /api/ppid/{id}               修改
DELETE /api/ppid/{id}               停用
POST   /api/ppid/import             Excel 批量导入（返回逐行错误）
GET    /api/ppid/template           下载导入模板
GET    /api/ppid/export
GET    /api/ppid/available          按 型号+SPU 返回可分配预览
```

### 8.5 申请与 token

```
POST   /api/applications                    发起申请
GET    /api/applications/page               申请单列表
GET    /api/applications/{no}               详情 + 阶段时间线
GET    /api/applications/{no}/files         文件明细
POST   /api/applications/{no}/retry         人工重放（替代 MES 11 个补偿端点）
GET    /api/quotas/remain?productModel=&spu=  余量预估

GET    /api/tokens/page                     token 查询（状态/PPID/申请单筛选）
POST   /api/tokens/export                   异步导出任务（CSV/XLSX）
GET    /api/export-tasks/{id}               任务状态与下载链接
POST   /api/tokens/destroy                  调苹果销毁未使用实体   [owner]
```

---

## 9. 前端（Vue3 + Element Plus）

Vite + TypeScript + Pinia + vue-router + Element Plus，`pnpm` 管理。构建产物由 `maven-resources-plugin` 复制进 `mfi-service` 的 `static/`，单进程部署；开发期 Vite proxy 到 `localhost:8090`。

| 页面 | 关键交互 |
|---|---|
| 登录 / 注册 | 注册即建租户，一次完成 |
| 申请发起 | 选型号+SPU → 实时余量预览 → 数量校验（含租户规则上限提示） |
| 申请进度 | **5 阶段时间线**，按 PPID 分片展示 stage/status/retry/error，失败分片可一键重放 |
| PPID 管理 | 列表、新增、Excel 导入（逐行报错定位）、模板下载 |
| Token 列表 | 筛选、异步导出、销毁 |
| 租户设置 | 证书上传 + 指纹展示 + 连通性自检 + 规则配置 |
| 成员管理 | 邀请、停用、移除、转让 owner |
| 全局 | 多租户切换器（顶栏）；owner-only 按钮对非 owner **隐藏而非禁用**（唯一区分，不做权限系统） |

---

## 10. 本地环境

`docker-compose.yml` 提供 `mysql:8.0`、`redis:7.2`、`rabbitmq:3.13-management`，供有 Docker 的开发机与服务器部署使用。

**本机（2026-10-09）无 Docker，且 `winget` 无任何输出（源不可用）**。WSL2 里虽有 Ubuntu，但其虚拟盘在 `C:\Users\esr\AppData\Local\wsl`，走 WSL 等于装进 C 盘，因此按"不占 C 盘"的要求改走 **D 盘免安装归档**：

| 组件 | 位置 | 启动 |
|---|---|---|
| Redis 5.0.14.1 | `D:\tools\redis`，数据 `D:\tools\redis\data`，日志 `D:\tools\redis\logs\redis.log` | `Start-Process D:\tools\redis\redis-server.exe -ArgumentList 'D:\tools\redis\mfi.redis.conf'`（只监听 `127.0.0.1:6379`，本机无口令） |
| Erlang/OTP 26.2.5.5 | `D:\tools\erlang` | 不单独启动，供 RabbitMQ 使用 |
| RabbitMQ 3.13.7 | `D:\tools\rabbitmq_server-3.13.7`，节点数据与日志在 `RABBITMQ_BASE=D:\tools\rabbitmq_var`，节点名 `rabbit@<主机名>` | 先 `epmd -daemon`，再按下表设环境变量跑 `sbin\rabbitmq-server.bat`（前台进程，不注册服务） |
| MySQL 8.0.34 | 复用已运行的 `MySQL80` 服务（3306，非本次新增） | 已在运行 |

`[选型说明]` RabbitMQ 锁 3.13.x 而非 4.x：4.x 要 OTP 27，而 3.13 与 OTP 26.2 是官方配对，且 3.13 的 release 资产里仍有 `rabbitmq-server-windows-*.zip`。Erlang 必须用 `.zip` 而非 `.exe`——安装器写注册表会触发 UAC，免安装归档不需要管理员权限。

`[免安装 Windows 的三个坑，实测]`

1. **`otp_win64_*.zip` 是根目录展开的**：直接 `unzip -d D:\tools` 会把 `bin/ lib/ erts-*/ releases/ doc/ usr/` 撒进工具根，需事后收进独立目录。
2. **`erts-*/bin/erl.ini` 里写死的是官方 CI 构建机路径**（`D:\a\otp\otp\otp_win64_26.2.5.5\...`）。解压到别的目录后必须把 `Bindir`/`Rootdir` 改成实际路径，否则 `erl` 定位不到 runtime。
3. **`epmd.exe` 只存在于 `erts-*/bin`，不在 `erlang\bin`**。zip 版没进 PATH，RabbitMQ 预启动节点连不上 epmd 会报 `Protocol 'inet_tcp': register/listen error: econnrefused` 然后 `BOOT FAILED`。先手工 `erts-*/bin/epmd.exe -daemon`（监听 **4369**，不是 4370），并且启动 RabbitMQ 时 PATH 要同时包含 `D:\tools\erlang\bin` 和 `D:\tools\erlang\erts-14.2.5.4\bin`。

RabbitMQ 启动环境变量（进程级，不改系统 PATH、不写注册表）：

```
ERLANG_HOME=D:\tools\erlang
PATH=<...>;D:\tools\erlang\bin;D:\tools\erlang\erts-14.2.5.4\bin
RABBITMQ_BASE=D:\tools\rabbitmq_var
RABBITMQ_MNESIA_BASE=D:\tools\rabbitmq_var\mnesia
RABBITMQ_LOG_BASE=D:\tools\rabbitmq_var\log
```

`guest/guest` 可用（RabbitMQ 默认只允许 guest 从 localhost 登录），`rabbitmq-diagnostics status` 实测通过。

本地开发不必设应用侧环境变量：`mfi-service/src/main/resources/application-local.yml`（已被 `.gitignore` 的 `**/application-local.yml` 覆盖，实测 `git check-ignore` 命中）提供数据源、Redis、RabbitMQ，启动加 `-Dspring-boot.run.profiles=local`。首次空库靠 JDBC 的 `createDatabaseIfNotExist=true` 建出 `apple_mfi_token`。

单模块启动要先 `mvn -DskipTests install`：只给 `-pl mfi-service` 时 `mfi-apple`/`mfi-dao` 的 SNAPSHOT 不在本地仓库，依赖解析直接失败。

`[已定]` 会话模式：**不启用 sa-token 的 JWT 模式**，不装配 `StpLogicJwtForSimple`，也不再有 `MFI_JWT_SECRET_KEY`。令牌为 uuid，会话数据存 Redis。代价是多实例必须共享同一 Redis（本产品已有 Redis），收益是移除成员/退出登录即时失效且少管理一把密钥。

必需环境变量（写入 `.env`，`.env.example` 只给键名）：

```
MFI_DB_URL / MFI_DB_USERNAME / MFI_DB_PASSWORD
MFI_REDIS_HOST / MFI_REDIS_PORT / MFI_REDIS_PASSWORD / MFI_REDIS_DATABASE
MFI_RABBITMQ_HOST / MFI_RABBITMQ_PORT / MFI_RABBITMQ_USERNAME / MFI_RABBITMQ_PASSWORD
MFI_CREDENTIAL_MASTER_KEY          # Base64 32B，AES-256-GCM 主密钥
MFI_SERVER_PORT                    # 默认 8090
```

---

## 11. 分期计划

每阶段结束必须 `mvn -q -DskipTests compile` 通过 + 阶段验收项通过，再进入下一阶段。

| 阶段 | 交付内容 | 验收标准 | 预估 |
|---|---|---|---|
| **P0** 底座 **（已完成，本机验收全绿）** | 父 POM + 3 模块 POM、`.gitignore`、`.gitattributes`、`docker-compose.yml`、`.env.example`、启动类、`application.yml`、Flyway `V1__baseline_schema.sql`（12 张表）、统一响应/异常/6 个枚举、`TenantContext`、MyBatis-Plus 租户拦截器与全局表白名单、sa-token 路由与租户绑定拦截器、`/api/health`；收尾追加：会话从 JWT 模式切到 sa-token Redis 模式（删 `StpLogicJwtForSimple` 与 `MFI_JWT_SECRET_KEY`）、补 `spring.data.redis` 配置、修正 `.gitignore` 里根锚定的 `application-local.yml` 路径 | **已验证**：`mvn test` 9/9（上下文缺失必须抛错、`runAs` 嵌套还原、白名单仅 2 张表且断言其数量）；DDL 12/12 JSqlParser 解析；三模块编译与 install SUCCESS；`local` profile 空库启动成功，Flyway 落 `apple_mfi_token` 共 12 张业务表 + `flyway_schema_history`、163 列，`uk_ppid_tenant_ppid_spu`/`uk_token_tenant_entity`/`uk_shard_application_ppid`/`idx_shard_scan`/`uk_credential_tenant` 全部实测存在；`/api/health` 200、`/v3/api-docs` 200（OpenAPI 3.1）、`/swagger-ui` 200、未登录访问 `/api/**` 返回 `{"code":"A0401"}` + HTTP 401 |


| **P1** 用户与租户 **（已完成，本机验收全绿）** | register/login/logout/profile/switch-tenant、成员列表/邀请/启停/移除/转让 owner、`TenantAccess` 两道入口守卫、Argon2id 密码哈希（`spring-security-crypto` + 显式带 BouncyCastle）；收尾追加：**owner 位不再缓存进会话**，`TenantBindingInterceptor` 每请求回库取成员与租户状态，会话只留"当前租户 ID"这一选择偏好；`BizException` 按错误码分流 HTTP（NOT_OWNER→403、UNAUTHORIZED/BAD_CREDENTIALS→401、其余→400）；`TenantMemberMapper` 只保留 `selectByUserId`/`selectByTenantAndUser` 两条按 user_id 自缚的放行查询 | **已验证**（对真实 MySQL + Redis 跑 33 项断言，0 失败）：4 租户 8 账号下各租户成员列表互不可见；A 拿乙租户成员 ID 做删除被当作"不存在"拒掉且乙租户数据无变化；C 在甲/乙之间切换后 `/api/members` 结果随上下文变化；非 owner 移除成员与转让 owner 返回 HTTP 403 + `A0403`；成员被停用后原会话立刻拿不到租户上下文（`A0410`），重新启用即恢复；转让 owner 后操作人下一次请求即失去权限、目标成员立刻获得，该租户仍恰好 1 个 owner（库内 `having owners<>1` 零行）；账号不存在与密码错误返回同码同文案；`t_user.password_hash` 全为 `$argon2id$v` 前缀；Redis 实测 `mfi-token:login:session:<userId>` 的 `dataMap` 仅含 `currentTenantId`，令牌为 uuid 且不含 JWT 结构；SQL 日志实测业务表带 `deleted = 0 AND tenant_id = N`、放行查询不带 tenant_id、`insert` 由拦截器补 `tenant_id` | 1d |
| **P2** 凭证与客户端 **（已完成，本机验收 48/48）** | AES-256-GCM 加解密（AAD 绑 `tenant_id` + 字段名）、密钥环与 `encrypt_key_id` 轮换位、`MfiSslContextBuilder`（PKCS#8/PKCS#1 双格式 + 私钥/证书配对预检）、`MfiClientFactory`（Caffeine，512 租户上限 + 30 分钟 `expireAfterAccess` 兼作明文私钥驻留上限）、`MfiTokenClient` 9 接口、`MfiProbeService` 连通性自检、`TenantProfileService`/`TenantConfigService` 与 owner 守卫的 `TenantController`/`TenantCredentialController`；**自签联调材料**：`scripts/gen-dev-certs.sh`（CA + 服务端 + PKCS#8/PKCS#1 两套租户证书 + 一张过期证书，写到 gitignored 的 `.dev-certs/`）、`scripts/MfiStubServer.java`（JDK 单文件源码启动的 two-way TLS 桩，`needClientAuth`）、`scripts/verify-p2.py`（活体验收脚本） | **已验证**：`mvn test` 36/36（apple 12 含 4 个**真实 mTLS 握手**：PKCS#8 与 PKCS#1 各自握手成功且桩侧读到客户端证书 DN、他签 CA 的客户端被拒且请求数为 0、不受信服务端被拒；8 个 `MfiSslContextBuilder` 断言含"A 证书 + B 私钥"配对检测为 false；15 个 service 断言含跨租户 AAD 替换解密失败、轮换后 v0 历史密文仍可解、密钥环空/未知 activeKeyId/非 Base64/16 字节全部启动期拒绝）；对真实 MySQL + Redis + 本地 mTLS 桩跑 **48 项活体断言，0 失败**：垃圾 PEM/不配对/过期证书三道校验（过期给 `B0410`）、PKCS#1 链式材料入库、`verify` 返回 `reachable=true, httpStatus=200` 且桩侧日志出现 `clientCert=CN=MFI Tenant B Client`、库内 `cert_cipher`/`key_cipher` 无 `BEGIN` 标记且互不相同、`encrypt_key_id=v1` 与指纹一致、应用日志私钥文本增量 0、**两次自检只装配 1 次客户端**（命中缓存）、替换凭证后重新装配 +1 且桩侧看到新证书主体、跨租户读不到凭证（`B0404`）、非 owner 改凭证与改业务参数均 HTTP 403 + `A0403`、删除凭证后自检给业务错误而非 500；`.gitignore` 补 `*.crt`/`.dev-certs/`/`__pycache__/` 并实测 `git check-ignore` 命中 | 1d |
| **P3** PPID 池 | CRUD、Excel 导入/模板/导出、余量计算、CAS 条件扣减、`t_tenant_config` 规则读写 | 并发 50 次扣减不超卖；导入逐行错误定位；余量预估与池实际一致 | 1d |
| **P4** 申请同步段 | 分配算法（型号+SPU 贪心）、事务、AFTER_COMMIT 发消息、`t_application`/`t_application_ppid` 落库、余量接口 | 事务回滚时消息未发出；多 PPID 拆分正确；回滚后余量恢复；重复提交不重复扣减 | 1d |
| **P5** 异步流水线 | 两 exchange + 6 queue + DLX、5 监听器、延迟计算、CAS 幂等、重试与 DLQ、租户并发闸门、兜底扫描；**自签 CA 要求的 two-way TLS WireMock 桩** | 阶段跃迁/CAS/幂等/延迟计算必须有测试覆盖。**broker 接线（TTL 回流、DLQ、并发闸门）本机已具备验证条件**：RabbitMQ 3.13.7 + OTP 26.2.5.5 已在 D 盘跑起（§10、§13.3） | 2d |
| **P6** 结果与导出 | token 落库、SN 生成、状态聚合、异步导出任务、进度时间线查询、人工重放、销毁 | 时间线显示 5 阶段真实状态；导出 1 万条不 OOM；重放只作用于失败分片 | 1d |
| **P7** 前端 | 8 个页面 + 租户切换器 + owner 可见性 + 进度时间线组件 | 浏览器实测完整链路：注册→上传证书→导入 PPID→申请→进度→导出 | 2d |
| **P8a** 本地安全验收 | `docs/mfi-token-flow.md`、日志脱敏复查、跨租户渗透自测、部署说明、§11 最终验收清单逐项 | 除"真证书联调"外的全部验收项通过 | 1d |
| **P8b** 真证书联调（**阻塞**） | 拿厂商真实证书后验 §13.2 列出的 5 项未验证风险：真实证书链与 CN 校验、`technology` 取值接受度、真实限流阈值、响应字段与文档一致性、苹果侧 PPID 配额 | 不在本次可交付范围，需外部输入才能启动 | 待定 |

合计约 **10.5 人日**（不含 P8b）。P0–P6 与苹果真实网络无关，本机 MySQL + Redis + RabbitMQ 三件套已就绪，可全程连续真实验证；P7 前端可浏览器实测完整链路；只剩 P8b 阻塞在外部输入（真实厂商证书）。

### P2 收尾修正的缺陷（活体验收第二轮才暴露，记录以免复发）

1. **逻辑删除与唯一键冲突**：`application.yml` 里全局 `logic-delete-field: deleted`，而 `uk_credential_tenant(tenant_id)`、`uk_config_tenant_key(tenant_id, config_key)`、`uk_member_tenant_user(tenant_id, user_id)` 都不含 `deleted`。于是"删除凭证 → 再上传"和"移出成员 → 再邀请"都会撞唯一键抛 500（业务参数留空同理）。三处改为按条件的物理删除（`TenantCredentialMapper.deleteByTenant`、`TenantConfigMapper.deleteByKey`、`TenantMemberMapper.deleteByTenantAndUser`）；凭证尤其必须物理删，被删行仍是可解密的密文，留着只增加误用面。
2. **`updateById` 跳过 null**：`apple_base_url` 想"留空即回到官方地址"清不掉旧覆盖值（MyBatis-Plus 默认 NOT_NULL 更新策略）。改为 `lambdaUpdate().set(...)` 显式写 NULL；`apple_technology` 是 `NOT NULL DEFAULT 'DEFAULT'` 列，留空只能落 `MfiEndpoints.TECHNOLOGY_DEFAULT` 哨兵，注册路径改用同一个常量。
3. **自检不该 evict**：`MfiProbeService.verify` 原本每次先 `evict` 再取客户端，等于每次自检都重建 SSLContext 与连接池，缓存形同虚设。改为复用缓存，`evict` 只留在凭证替换/删除与 BaseURL/technology 变更这几个真正改变材料的入口。
4. 新增错误码 `CREDENTIAL_INVALID("B0500")`，把"库里的材料不可用"（解密失败、证书与私钥不配对）与"未配置"（`B0404`）、"已过期"（`B0410`）分成三态。
5. 业务参数的"留空"语义落到实现：`TenantConfigService.update` 对空值删除覆盖行而不是写入空串，读取时自然回落到 `TenantConfigKey` 默认值，`overridden` 随之回到 false。

### 最终验收清单

- [ ] 两个租户各用独立证书并发申请，数据、PPID、token 完全隔离
- [ ] 任一租户证书错误/过期不影响其他租户消费
- [ ] 全程无明文凭证与 `secret_key` 出现在日志
- [ ] 无角色表、无权限点、前端无权限树（确认"不做权限管理"落地）
- [ ] `mvn -q -DskipTests compile` 与 `mvn test` 全绿
- [ ] 断掉 MQ 重启后，兜底扫描能自愈未完成分片

---

## 12. 风险清单

| 风险 | 说明 | 缓解 |
|---|---|---|
| 苹果侧限流 | SaaS 多租户共用出口 IP，高频请求可能触发限制 | 租户级信号量 + 全局速率上限 + 退避重试 |
| 延迟队列放大 | 苹果生成 token 可达小时级，大量在途消息占用 broker | 消息只带 ID 不带 token 内容；DLQ 分租户前缀告警 |
| 凭证泄露面扩大 | 一处主密钥泄露覆盖所有租户 | 主密钥不进仓库；AAD 绑 tenant_id；支持密钥轮换 |
| 证书到期无人知 | `cert_not_after` 到期后申请静默失败 | 落库 + 每日检查 + 列表预警（不引入定时任务框架，用兜底扫描） |
| 大文件下载 OOM | 2000 token/文件 × 多文件并发 | 流式 EasyExcel 读取 + 分批入库 + 租户并发闸门 |
| `technology` 取值不确定 | MES 只对 HomeKit 分流、其余走默认，真实取值域未验证 | P2 需确认；见 §13 |
| npm 环境损坏 | 本机 `npm` 不可用（nvm 多版本错配） | 全程用 pnpm；`web/package.json` 不写 npm-only 脚本 |

---

## 13. 决策记录与待确认

### 13.1 已确认（2026-10-09）

| # | 问题 | 结论 | 影响 |
|---|---|---|---|
| 1 | DAO 风格 | **切到 MyBatis-Plus** | §2 的租户自动 SQL 改写成立，隔离性由框架保证而非人工拼条件 |
| 2 | PPID 来源 | **租户手工录入 + Excel 导入**，不做苹果门户自动同步 | 表结构不变；`/api/ppid/import` + 模板为唯一批量入口；不引入第二套门户凭证 |
| 3 | 苹果测试证书 / sandbox | **没有** | 见下方 §13.2 的应对方案，验证策略随之调整 |

### 13.2 没有真证书，如何验证苹果链路（关键调整）

结论：**mTLS 握手不需要苹果的证书也能完整验证**。做法是在本地建一套自签 CA 测试夹具：

1. 脚本生成 `ca.crt` + 一个由该 CA 签发的**客户端证书/私钥**（PKCS8 与 PKCS1 各一份，覆盖两条加载分支）
2. 起一个**要求客户端证书**的本地 HTTPS 桩服务，路径与响应体按苹果规范伪造：
   `requestAuthEntities` 返回 request uuid；`authEntities/{uuid}` 先返回 `downloadAvailability=false` + `retryDownloadAfter`，第二次返回文件名；文件下载返回一份真实构造的 xlsx（含 Provisioning ID / UUID / Secret Key）；`usedAuthEntities/bulk` 返回受理
   > P2 实测调整：桩改用 `scripts/MfiStubServer.java`（JDK 单文件源码启动，`HttpsServer` + `setNeedClientAuth(true)`，客户端证书从 `HttpsExchange.getSSLSession()` 读）。不引 WireMock：它要靠 KeyStore 材料配 two-way TLS，而这里要验的正是"PEM 能不能装成 SSLContext"，夹具本身越薄结论越干净。P5 若要伪造 `retryDownloadAfter` 之类的时序，再在这个桩上加路由即可。
3. 租户的 `apple_base_url` 指向该桩，跑通**五阶段全链路 + 延迟 + 重试 + DLQ**

这样能验到的：SSLContext 构造、PKCS1→PKCS8 兜底、mTLS 握手与失败路径、`downloadAvailability` 延迟计算、xlsx 解析入库、阶段 CAS、幂等、重试耗尽。

**验不到的（真证书到位前必须标注为未验证风险）**：苹果真实证书链的可信度与 CN 校验规则、苹果侧对 `technology` 取值的实际接受度、真实限流阈值、生产 `authEntities` 响应字段是否与文档一致、PPID 在苹果侧的配额与有效期。这些留到 §11 的 P8b。

### 13.3 本机环境实测结论对验证上限的约束

2026-10-09 实测并已按方案 A 落地：

| 中间件 | 状态 | 影响 |
|---|---|---|
| MySQL | **8.0.34 已装并运行**（服务 `MySQL80`，3306），客户端在 `C:\Program Files\MySQL\MySQL Server 8.0\bin`；账号由用户提供，P0 已用它真实建库建表 | P0 Flyway 建表、P1 用户租户、P3 PPID、P4 申请同步段**已本地真实验证** |
| Redis | **已装**：`D:\tools\redis`（tporadowski 5.0.14.1 便携版），仅绑 `127.0.0.1:6379`，`PING` 与读写实测通过 | sa-token 会话直接落 Redis（已取消内存 dao 兜底与 JWT 模式）；SN 取号用真实 `INCRBY` |
| RabbitMQ | **已装**：`D:\tools\rabbitmq_server-3.13.7` + `D:\tools\erlang`（OTP 26.2.5.5 免安装 zip）。5672/25672 在听，`rabbitmq-diagnostics status` 通过，`guest/guest` 可用，节点数据与日志在 `D:\tools\rabbitmq_var` | P5 的 TTL 延迟、死信回流、消费幂等**可本地真实验证**；方案 C 的兜底不再需要。免安装三个坑见 §10 |

| Docker | **未安装** | `docker-compose.yml` 仅作为有 Docker 的环境与服务器部署用，本机起不来 |
| winget | **命令存在但无任何输出**（源不可用） | 安装一律走 GitHub release 直下 + 解压 |

P5 的应对（已由用户选定）：

- **A. 本机装 Redis + RabbitMQ —— 已选并执行完毕（2026-10-10）**。补充实测：原以为"Windows 下需管理员安装"，实际 `otp_win64_*.zip` 与 `rabbitmq-server-windows-*.zip` 都是免安装归档，但并不是解压就能跑——要先收纳根展开、改写 `erl.ini` 的 CI 构建机路径、并手工 `epmd -daemon`（详见 §10）。全程不需要管理员权限、不写注册表、不占 C 盘。P0–P7 均可本地验证。
- B. 指一台有中间件的开发服务器 —— 未采用（会外泄开发地址与账号）。
- C. 分层验证 —— 保留为兜底：若 RabbitMQ 起不来，P5 的阶段跃迁/CAS/幂等仍用单测直接调 handler 验证。

### 13.4 仍需你确认

1. **SN 规则**：沿用 MES 的 `SnUtil.generateSnPrefix(ppid)` + 自增，还是租户可配前缀？各厂商命名规则可能不同。
2. **销毁 token**：是否开放 `destroyUnusedAuthEntities` 给租户自助？不可逆，我按"仅 owner + 二次确认"设计。
3. **通知渠道**：建议改为邮件 + 可配 webhook，不再用钉钉（MES 那条按 `company_code` 路由到钉钉群，租户是外部厂商时不适用）。
4. **部署形态**：单进程（后端 + 打包进 `static/` 的前端）还是前后端两个镜像？我默认单进程。
5. **仓库可见性**（未处理）：`origin` 实测为 **public**，而 §6.4 写明了 MES 里硬编码凭证的文件与行号；
   2026-10-09 18:25 该仓库已被外部推上 `origin/main`（3 个 P0 提交），即 §6.4 的位置信息**已经公开**，
   现在改可见性只能阻止后续读者，不能撤销已发布内容。真正的处置仍是 §6.4 的三条：重新签发该厂商证书、
   从 MES 移除硬编码、评估 git 历史清理。P1 及之后的提交不含任何凭证，因此自 P1 起按 AGENTS.md 的
   push policy 正常推送，不再因可见性问题扣住。
6. **P1 的一处设计偏离**（已实施，需你确认是否保留）：owner 位**不写会话**，每次请求回库读成员与租户状态。
   代价是每个 HTTP 请求多两次主键查询；收益是移除成员/停用/转让 owner 立即生效，不存在"已登录会话带着旧
   权限继续操作"的窗口。若更在意吞吐，可改成 Caffeine 短 TTL（10–30 秒）缓存，但要接受这段窗口期。


---

## 14. 下一步

P0、P1、P2 已交付并本机验收（§11）。P2 交付的不只是凭证链路本身：`MfiClientFactory` 已经把"每租户一套证书 +
可复用连接池 + 变更即失效"这一层做实，后续任何苹果调用都只依赖 `forTenant(tenantId)`。接下来进入 **P3 PPID 池**：
CRUD、Excel 导入（逐行错误定位）、按产品型号 + SPU 的余量预估、以及带条件的原子扣减——P4 的分配算法要直接建在
P3 的扣减语义上，所以这里的 CAS 与索引必须先定死。P5 再把 §13.2 的本地 mTLS 桩接进五阶段流水线。
编码过程中的偏离会在本文档同步更新。
