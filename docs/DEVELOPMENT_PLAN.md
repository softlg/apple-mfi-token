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
                    idempotency_key(UK with tenant_id, 可空), 
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

`manufacture_sn` 由本服务生成，沿用 MES 的 Redis 自增方案（`CodeComponent` 无外部依赖，实测可自建）：key = `mfi:sn:{tenantId}:{ppid}:{spu}:{yyyyMMdd}`，`INCRBY` 批量取号，`SnUtil` 的前缀规则移植到新仓库。

`[P3 期间的修正]` 原计划写的是 `mfi:sn:{tenantId}:{ppid}:{yyyyMMdd}`（照抄 MES）。但本仓库 `t_ppid` 的唯一键是
`uk_ppid_tenant_ppid_spu(tenant_id, ppid, spu)`，也就是**同一个 PPID 允许挂多个 SPU**；取号 key 不带 `spu` 就会让两个
SPU 共用一条计数器，各自批量取号后 `SnUtil` 前缀相同、序号互相跳过，跨厂商对账时无法解释。因此 key 必须补上 `spu` 段，
与唯一键的粒度对齐。

`[P6 期间的再修正]` 上面那条推理只解决了"跳号难解释"，代价却是**重号**：SN 格式（企业段 + PPID 后 6 位 + 年字母 + MMDD
+ 4 位流水）里根本没有 SPU 段，按 `spu` 拆计数器等于让同一 PPID 下的两个 SPU 各自从 0001 开始，编出两串完全相同的 SN。
重号比断号严重得多——厂商拿同一串 SN 刷两件产品时谁都追溯不了，而断号只影响对账观感。最终实现因此把 key 收成
`mfi:sn:{tenantId}:{前缀本身}`（`SnAllocator.KEY_FORMAT`），**计数器粒度严格等于 SN 前缀粒度**，同前缀下的多个 PPID/SPU
共用一条连续号段；日期段本身就在前缀里，所以 key 不需要额外的 `yyyyMMdd` 段，TTL 只要长过一天（留 7 天便于排障）。
断号仍然存在：号段在 Redis 占住之后、落库之前进程死掉，这段号作废且不回收——这是"宁可断号也不重号"的取舍。
活体证据见 §11 的 P6 行：本轮三张测试单的 PPID 后 6 位同为运行时间戳、SN 前缀完全相同，实测号段 `1-6` 与 `7-1206`
连续推进，1206 个序列号互不重复。

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

`auth_entity_id`、`secret_key`、`accessory_uuid`、`encoded_token`（token 原文）、PPID 一律不得出现在日志。MES 里有 `log.info("...: {}", JSON.toJSONString(...))` 直接打整个 token 列表的写法（如 `registerUsedAuthEntitiesBulk` 打 `authEntities`），移植时必须改为打数量与 ID 摘要。

PPID 这一条按"单个也不落"执行，不做"只禁全量列表"的宽解释：一次苹果调用就带一个 PPID，把它写进 INFO 等于每条阶段日志都带上租户的产品标识；
读侧接口把 PPID 回给本租户看自己的数据不在此列。P6 移植 `MfiTokenClient` 时就是按这条把三个方法的 PPID 与实体清单从日志里摘掉的（§11 的 P6 偏差第 13 条）。

红线不只约束自己写的日志语句，也约束**日志级别配置**：`logging.level.<mapper 包>: DEBUG` 会让 MyBatis 把每条 SQL 连同占位符实参整行输出，
PPID、凭证密文、其他租户的数据都会随之落盘，而这类泄露不会出现在 code review 的 diff 里。P3 就踩过一次（§11 的 P3 偏差第 7 条），
现在入库的 `application.yml` 固定 `com.galaplat.mfi.dao.mapper: INFO`，SQL 调试只能在 gitignored 的 `application-local.yml` 里临时取消注释。

---

## 7. 申请链路设计

### 7.1 同步段（一次事务）

```
POST /api/applications {productModel, spu, applyQuantity, requestId?}
 1. 参数校验
 2. requestId 命中已有单则直接返回那张单（不开事务、不碰额度）
 3. 校验租户凭证存在且未过期（否则快速失败，不做无谓扣减）
 4. 校验 applyQuantity ≤ 租户规则上限 APPLY_MAX_QUANTITY
 5. 按 (product_model, spu) + status=可用 查 PPID 池，余量倒序，贪心拆分并校验 ≤ Σ 余量
 6. 插 t_application（幂等键在这里由唯一索引判定）→ 逐条 CAS 扣减 remaining_quantity
    → N 条 t_application_ppid(stage=APPLY, status=PENDING) → t_operation_log
 7. 事务提交后发 MQ  ← 关键；回滚则一条都不发
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
GET    /api/ppid/stats              池内份额/已申请/剩余汇总（页面顶部卡片）
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
```

余量预估不另开 `/api/quotas/remain`：P3 已交付的 `GET /api/ppid/available` 就是同一份语义（同一 service 方法、同一条 SQL），P4 直接复用，避免两个端点各算各的。

```
GET    /api/tokens/page                     token 查询（状态/PPID/申请单/制造序列号筛选）
POST   /api/tokens/export                   异步导出任务（CSV/XLSX）
GET    /api/export-tasks/{taskNo}           任务状态与下载路径（只认发起人，产物含 token 原文）
GET    /api/export-tasks/{taskNo}/download  取产物字节（只认发起人）
POST   /api/tokens/destroy                  调苹果销毁未使用实体   [owner + 单号二次确认]
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

`[实测的验证入口]`

```
# 单元测试（dao 新增类时必须带 -am，否则用到本地仓库里的旧 jar）
mvn -o -pl mfi-service -am test

# 打真实 MySQL 的并发 CAS 集成测试：默认跳过，需要显式开关与库账号
# 库账号在本机 application-local.yml 里（gitignored），命令行按需导出，不写进仓库
MFI_IT_DB=1 MFI_DB_USERNAME=<本地库账号> MFI_DB_PASSWORD=<本地库口令> mvn -o -pl mfi-dao -am test

# 起应用（凭证主密钥与自签 CA 只从环境变量进，来源是 gitignored 的 .dev-certs/）
MFI_CREDENTIAL_MASTER_KEY=$(cat .dev-certs/master-key.txt) \
MFI_APPLE_TRUSTED_CA_PEM="$(cat .dev-certs/ca.crt)" \
  mvn -o -pl mfi-service spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=local

# 活体验收（对 8090 与真实库跑断言，脚本自带随机 stamp 所以可重复执行）
PYTHONIOENCODING=utf-8 MFI_DB_USERNAME=<本地库账号> MFI_DB_PASSWORD=<本地库口令> python scripts/verify-p3.py
```

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
| **P3** PPID 池 **（已完成，本机验收 59/59）** | `PpIdMapper` 的 CAS 条件扣减/回补 SQL、`PpIdService`（分页 CRUD、停用作废、按型号+SPU 的余量预估）、Excel 导入/模板/导出（EasyExcel 4.0.3，列序即契约）、`/api/ppid` 9 个端点、`PageResult` 统一分页响应、`MaxUploadSizeExceededException` 全局处理；**测试资产**：`PpIdServiceTest`（12 项，含 `MockMultipartFile` + EasyExcel 真实读写往返）、`PpIdDeductConcurrencyTest`（对真实 MySQL 的并发 CAS，`MFI_IT_DB=1` 才启用）、`scripts/verify-p3.py`（活体验收） | **已验证**：`mvn -o -pl mfi-service -am test` 48/48（apple 12 + dao 9 + service 27，其中新增 12 项覆盖贪心拆分预估、`total<applied` 拒绝、"启用但余量为 0"拒绝、人工改总量必带 `applied_quantity` 快照条件、停用绝不 `deleteById`、导入行号/分块 500/`"100.0"` 放行/重导报"已存在"）；`MFI_IT_DB=1 mvn -o -pl mfi-dao -am test` 12/12，其中 3 项打真实 MySQL：**50 线程各扣 1 打到 40 份额度恰好 40 成功**、`applied=40 remaining=0` 且自动停用、余量 3 扣 5 不改一行、SQL 手工把停用行改回启用后再扣仍 0 影响；活体 59/59（真实 MySQL + Redis）：模板列序与 RFC 5987 文件名、重复 PPID+SPU 拒绝、分页四类筛选、预估与库内实际一致、非法文件逐行报错 `[3,4,5,6]` 且 `insertedRows=0`、1200 行整份入库后 stats 对齐、导出中文状态、**跨租户 8 项**（B 查不到/改不动/停不掉 A 的行、同 PPID+SPU 在 B 可独立导入、B 导出只含自己）、openapi 不泄漏库列名；`grep -ac 'P3-IMP' 运行日志` = 0 | 1d |
| **P4** 申请同步段 **（已完成，本机验收 42/42）** | `ApplicationPO`/`ApplicationPpidPO`/`OperationLogPO` 与三个 Mapper、Flyway `V2__application_idempotency.sql`、`ApplicationService.submit`（一次事务内：凭证与上限前置 → 贪心拆分 → 逐份额度 CAS 扣减 → 落单落分片 → 留痕 → 登记消息）、`ShardMessageProducer`（AFTER_COMMIT 投递）、`RabbitTopology`（`mfi.token.normal` + `mfi.apply.queue` + JSON 转换器）、`POST /api/applications` 与 `GET /api/applications/page`；`PpIdService.planAllocation` 让预览与实际分配共用同一条池查询 | **已验证**：`mvn -o -pl mfi-service -am test` 57/57（新增 `ApplicationServiceImplTest` 9 项：**提交前一条消息都不许离开进程**、触发提交后回调才发且只发分片数那么多条、抢不到额度时不登记任何提交后回调、额度不足先拒后扣、上限闸门在池查询之前、证书过期快速失败、同 requestId 命中已有单且不开事务、唯一键撞库回滚后返回对手的单、脱离事务登记消息直接失败）；活体 42/42（真实 MySQL + Redis + RabbitMQ）：120 份在 100+30 的池上拆成 `[100,20]`、扣满的那份额度**余量归零自动停用**、库里分片 2 行合计 120、发起人/technology 快照落库、留痕只记数量不含 PPID 清单、**队列 `mfi.apply.queue` 实测正好多出 2 条**（rabbitmqctl 对账）、重复提交返回同一单号且 `applied_quantity` 不变、不带 requestId 的两次提交是两个单（NULL 不受唯一约束）、上限 3 挡掉 4 且额度分文未动、**两线程抢最后 1 份恰好成一个**且输家库里无单无扣减、跨租户 4 项、非法状态给 `A0400` 而不是 500、openapi 不泄漏 `idempotency_key`；运行日志 `grep -ac 'Preparing\|P4-BIG\|PRIVATE KEY'` 全为 0 | 1d |
| **P5** 异步流水线 **（已完成，本机验收 54/54）** | `RabbitTopology`（`mfi.token.normal` + `mfi.token.delay` 两个 direct exchange、5 个阶段队列 + 1 个 DLQ、按 `delayTiers` 逐档位声明"队列级 TTL + 死信回流到本阶段队列"的延迟队列）、`PipelineNames`（档位毫秒数 ↔ 队列名互转，取整到不小于它的档位）、`StageRunner`（claim CAS → 回库读 → 早到重排 → 租户闸门 → 处理器 → 跃迁，先落库后投递）、`StageOutcome` 四态、5 个阶段处理器与 1 个 `PipelineListener`（5 个 `@RabbitListener`）、`MfiUtcTime`（苹果 UTC 时刻 + 1 分钟安全余量、解析失败回落 1h）、`TenantConcurrencyGate`、`ApplicationAggregator`（单条条件 UPDATE，终态不被迟到的聚合改回 RUNNING）、`PipelineScanner`（PROCESSING 超时复位 + PENDING 到点补发）、`DeadLetterListener`；**fixture 扩展**：`scripts/MfiStubServer.java` 增加五条剧情（正常/`WAIT`+`retry_download_after`/两小时后到货/403 永久拒/503 可恢复）与逐文件切片，`scripts/verify-p5.py`（54 项活体断言） | **已验证**：`mvn -o -pl mfi-service -am test` 全绿（apple 17 + dao 12（3 项真实库并发 IT 无 `MFI_IT_DB` 时跳过）+ service 94），其中 `StageRunnerTest` 16 项盯三条会直接造成重复调苹果/丢进度/耗死分片的规则（**领取返回 0 时回库读、闸门、处理器一条都不走到**、跃迁落库成功才投下一跳、等待原样回传 `retryCount` 而 RETRY 才 +1、早于计划时间重排、重试耗尽落 DEAD 并投死信），`PipelineNamesTest` 8 项（档位取整、非法档位、队列名稳定性），`PipelineScannerTest` 5 项（复位不加重试、补发只到点、跨租户），5 个处理器测试 29 项；**对真实 MySQL + Redis + RabbitMQ + 自签 CA 的 two-way TLS 桩跑 54 项活体断言，0 失败**（桩用 JDK `HttpsServer` 手写而不是计划里写的 WireMock，两-way TLS 与剧情都要自己控，200 行内可 review，且不必为 fixture 再引一套 Jetty）：7 个 token 走完 APPLY→FILE_NAME→FILE_CONTENT→REGISTER→FINISH 且桩给的 request id 落库、**逐文件推进**（两个文件各登记一次且 `token_count` 合计 7）、配件 UUID 逐行唯一（重试不换 uuid）、苹果 CRC32 原样落库、列序解析（Token ID 与 token 原文各归其列、base64 解出内容一致）、留痕 5 条前进记录且**不含 token 原文与 Token ID**、`retry_download_after` 到货前 PENDING + `next_execute_time` 在未来且 `retry_count=0`、`rabbitmqctl` 实测消息躺在 `mfi.delay.name.*` 档位队列里（TTL+死信回流闭环）、等待中的申请单读侧显示 RUNNING、桩侧对同一 PPID 只回一次"还在生成"、超过最长档位到货后重排且不消耗重试且时刻不倒退、**把分片伪造成 PROCESSING 后兜底扫描复位**且不加重试、在飞消息到货被 CAS 挡下（日志"判定为重复投递或已被推进"且分片没被改坏）、403 直接 DEAD 且只调苹果一次 + 死信被记成需要人工介入 + 全分片失败聚合 FAILED、503 按 10s/30s/1m 退避且总调用次数正好等于上限 3、**五个阶段队列与 DLQ 收口时全部 0 积压**、运行日志 `grep` 不到 token 原文/Token ID/`PRIVATE KEY`；本轮补掉一处真实缺陷（申请单在等待期间一直显示 PENDING，见下方第 7 条），并修掉 fixture 的 Token ID 复用问题（第 10 条） | 2d |
| **P6** 结果与导出 **（已完成，本机验收 93/93）** | SN：`SnUtil`（企业段 + PPID 后 6 位清洗 + 年字母 + MMDD，移植 MES 规则）+ `SnAllocator`（Redis `INCRBY` 一次占一段连续流水，**计数器 key 的粒度严格等于 SN 前缀粒度**，生产日按 `Asia/Shanghai` 取，4 位补零流水超上限只变长不截断）+ `FinishStageHandler` 只给缺号行发号并做收口两道校验；读侧：`ApplicationQueryService`（详情一次给全五阶段进度/分片明细/token 汇总/时间线）、`TokenQueryService`（分页 + 按 SN 反查，响应不含四个凭证列）、`GET /api/applications/{no}`、`/{no}/files`、`GET /api/tokens/page`；人工重放：`POST /api/applications/{no}/retry` + `ApplicationMapper.reopen`（只复位 FAILED/PARTIAL_FAILED 并抹掉上一轮死因）；异步导出：`ExportTaskPO`/`ExportTaskMapper`/`ExportProperties`/`ExportExecutorConfig`（有界线程池 + 队列满即拒）、`ExportServiceImpl`（keyset 分页、`max-rows` 触顶截断记 WARN、僵死 PROCESSING 扫描、产物目录约束、只认发起人）、`TokenExportRow`（十列列序即契约）、CSV 带 UTF-8 BOM / XLSX、RFC 5987 文件名、`GET /api/export-tasks/{taskNo}` 与 `/download`；销毁：`TokenDestroyRequest`/`TokenDestroyVo`/`TokenDestroyService` + `POST /api/tokens/destroy`（仅 owner、单号原样二次确认、只对 ISSUED 候选、按 PPID 归堆后每 100 个一批、被拒即停手、`hasMore` 如实回报）；**收尾修正**：`TokenMapper` 三处补 `status <> 'DESTROYED'`、`TokenStatus.destroyable()` 收窄到 ISSUED、`MfiTokenClient` 日志摘掉 PPID 与实体清单、`ResponseWrapAdvice` 不再包装 `ResponseEntity`；**fixture**：`scripts/MfiStubServer.java` 补 `unusedAuthEntities` 路由与 `REGISTERFAIL`/`DESTROYFAIL` 两条剧情 + `auth_entities`/`auth_entity_ids` 计数日志（一律不带 PPID），`scripts/verify-p6.py`（93 项活体断言） | **已验证**：`mvn -o -am -pl mfi-service test` 全绿（apple 17 + dao 12，其中 3 项真实库并发 IT 无 `MFI_IT_DB` 时跳过 + service 153），P6 新增/改写 58 项：`SnUtilTest` 9（逐条对着 MES 的 `generateSnPrefix` 断言，厂商产线已按这 16 位排产）、`SnAllocatorTest` 6（指定生产日期入参，跨年/跨月边界不依赖跑测试的当天）、`FinishStageHandlerTest` 9（可重入：只补空号行、绝不换掉已发出的 SN）、`ApplicationQueryServiceTest` 7、`TokenQueryServiceTest` 6、`ExportServiceImplTest` 9（翻页/截断/产物路径约束/只认发起人）、`TokenDestroyServiceTest` 10（**非 owner 在读任何数据之前就被拒**、二次确认必须逐字相同、按 PPID 归堆后切成 `[100,20,1]` 三批、一批被拒即停手并如实回已销毁数、留痕只记数量、`auth_entity_id` 为空判为数据损坏而非静默跳过）、`ResponseWrapAdviceTest` 2；**对真实 MySQL + Redis + RabbitMQ + 自签 CA 的 two-way TLS 桩跑 93 项活体断言，0 失败**：六个 token 走完五阶段且 SN 前段实测等于「E + PPID 后 6 位 + 年字母 B + MMDD」、4 位流水连续、互不重复，**三张单共用同一条计数器**（PPID 后 6 位同为时间戳 → 前段相同）并按 `1-6`、`7-1206` 连续推进不重号；详情一次给全五阶段（`reached=succeeded=1`、`dead=0`）、时间线五条 `STAGE_ADVANCE`、token 汇总按状态分组、两份文件行数之和=申请量且都 SUCCESS；token 分页六行含 PPID/SN/状态而**响应体内 `encodedToken`/`secretKey`/`authEntityId`/`accessoryUuid` 四个键一个都没有**、按 SN 反查命中 1 行；CSV 下载带 UTF-8 BOM、表头与十列列序逐字对齐、行数=1+6、产物里能看到 base64 的 token 原文与刚发出的 SN、库里只存 `E261012-xxxx.csv` 这一相对文件名；导出越权六项（隔壁租户读不到任务号也拿不到字节、同租户成员读不到 owner 的任务、成员可以自己发起、反过来 owner 也读不到成员的）；**1200 行的真实大单在 `page-size=100 / max-rows=1000` 下翻满 10 页并触顶截断**（`totalRows=1000` + WARN 日志 + XLSX 是合法 zip 容器 + 留痕 `EXPORT\|1000`）；`REGISTERFAIL` 剧情让分片 DEAD 在 REGISTER（退避一次后终态、桩侧正好被打了两次注册、六个 token 停在 ISSUED 且无 SN、聚合 FAILED 且写了 `fail_reason`、进度页 REGISTER `dead=1` 而 FINISH `reached=0`）；销毁的四道拒绝（非 owner HTTP 403、二次确认不一致、隔壁租户、确认位为空）之后**桩侧销毁调用增量为 0 且库里状态未变**；正常销毁 `selected=destroyed=6`、一个批次、六行 DESTROYED、留痕 `DESTROY\|6` 且 detail 里既无 PPID 也无 `AUTH`；对已注册的单候选为 0（不报错也不打苹果）、重复点击不再有调用；**销毁之后人工重放**这一节一次证完三处 mapper 修正：`replayedCount=1` 且分片回到 REGISTER/PENDING（`retry_count` 归零、`last_error` 清空）、单子被拉回进行中且上一轮 `fail_reason` 抹掉、走到 FINISH/SUCCESS、**桩侧注册调用没有新增**（已作废实体不再送苹果）、六行仍是 DESTROYED 且收口不把它们算成漏注册、**已销毁的实体一个序列号也没白占**（SN 仍为 0）、整单 SUCCESS + `finish_time` 重新落上 + `token_count=6`、无 DEAD 时重放是空操作而不是报错；日志红线八项（三份 PPID 明文、SN 前段、token 原文的 base64 前缀、`==> Parameters`、`PRIVATE KEY` 在运行日志里全部 grep 为 0，产物目录只含 `<任务号>.<后缀>`）；五个阶段队列与 DLQ 收口 0 积压；本轮把"导出 1 万条不 OOM"这条验收改成**低档位让翻页与截断都真的发生**（机制由 keyset 分页 + 单页驻留 + 单测支撑，见下方第 7 条），并修掉一处只在活体才暴露的下载 500（第 12 条） | 1d |
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

### P3 编码期偏离与踩坑（活体验收与日志审计暴露，记录以免复发）

1. **导入改为"整份文件或留或走"**：计划里写的是"合法行入库 + 非法行报行号"。实测那样更坑用户——部分成功后再导同一份文件，
   已入库的行会报"已存在"，用户无法分辨哪些量真的进了池，而 PPID 是账本，含糊不得。现在先全量校验（含文件内自重复与库内冲突），
   有任何一行不合法就一行都不写，逐行错误照样给到 Excel 界面行号（表头算第 1 行）。
2. **模板与导出文件不入库**：`.xlsx` 二进制进 git 无法 review，且列序一改就会静默不一致。改为 EasyExcel 运行时生成，
   列序由 `@ExcelProperty(index=)` 固定并在类的 javadoc 里写明"列序即对外契约"；验收脚本直接断言下载文件的表头顺序。
3. **监听器不抛业务异常**：EasyExcel 会把 `invoke` 里的异常再包一层 `ExcelAnalysisException`，`GlobalExceptionHandler`
   拿不到原始业务消息，用户只会看到 500。行数超限改成置 `overflow` 标志、读完再判，报错文案回到 `BizException`。
4. **导入单元格一律按 String 读**：一旦声明 `Integer`，某格写成 `"100.0"` 或文本"一百"就会让整份文件在转换阶段中断，
   错误信息是 EasyExcel 的转换器堆栈而不是"第 N 行"。改 String 后自己解析，`100.0` 用
   `BigDecimal.stripTrailingZeros().scale()>0` 判过再 `intValueExact()`，非整数才报行号。
5. **人工改总量必须带"已申请量未变"条件**：分配线程随时在 CAS 扣减，按旧快照写回 `total/remaining` 会把中间刚扣掉的量
   覆盖掉，等于凭空多出额度。`update` 用 `WHERE id=? AND applied_quantity=<读到的快照>`，影响 0 行就返回"这份额度刚被申请占用，请刷新后重试"。
   单测用 `ArgumentCaptor<LambdaUpdateWrapper>` 断言 SQL 段含 `applied_quantity` 且参数含该快照。
6. **MySQL 单表 UPDATE 的 SET 是从左往右求值**：`deduct` 要"这次扣完就没余量了就顺手停用"，`status` 就必须排在
   `remaining_quantity` **之前**——此时它读到的还是扣减前的余量，`case when remaining_quantity <= #{quantity} then 0` 才成立；
   写反了就会拿扣减后的值判断，边界那一笔（正好扣到 0）漏停用。已写进 mapper 注释，并由"余量归零后该行 status=0"的并发测试钉住。
7. **`com.galaplat.mfi.dao.mapper: DEBUG` 曾写在入库的 `application.yml` 里**：MyBatis 的 DEBUG 会把每条 SQL 的占位符实参整行打进日志，
   实测一次验收后运行日志里能 `grep` 到 30 处 PPID 明文——这与本节上一段（§6.3）的红线直接冲突，而注释就写在它上面一行。
   现基础配置固定 `INFO`，要查 SQL 只能在 gitignored 的 `application-local.yml` 里取消注释（示例文件同样给出注释块和警告），
   部署配置里不得出现。改完重启重跑活体验收 59/59，`grep -ac 'Preparing\|Parameters' 运行日志` = 0。
8. **构建陷阱**：`mvn -o -pl mfi-service test` 不带 `-am` 会从本地仓库拿旧的 `mfi-dao` jar，报"找不到符号 PpIdPO/PpIdMapper"，
   看起来像代码坏了。dao 有新增类时一律带 `-am`。
9. **`-Dtest` 之外还需要环境变量**：真实 MySQL 的并发 CAS 测试用 `@EnabledIfEnvironmentVariable(named="MFI_IT_DB")` 门控，
   默认跳过，避免 CI/他人机器没有库时报错；跑法见 §10。
10. **"租户级配额规则"没有落在 P3**：原 P3 交付项写了 `t_tenant_config` 规则读写，实测 P2 的 `TenantConfigService` 已经把
    6 个键（含 `APPLY_MAX_QUANTITY`）的读写、留空回落默认值与 owner 守卫做完，P3 再实现一遍只会造成两套语义。
    真正的缺口是**执行点**：`APPLY_MAX_QUANTITY` 目前只是存着，没人读它拦申请。这属于申请事务的一部分，落到 P4 与
    `deduct` 同一事务内生效，避免"预估放行、提交后才发现超限"。

### P4 编码期偏离与取舍

1. **加了一列幂等键（唯一的 schema 改动）**：`V2__application_idempotency.sql` 给 `t_application` 加
   `idempotency_key` 与 `uk_application_idempotency(tenant_id, idempotency_key)`。MES 的重复提交靠"前端别连点"，
   超时重试就会重复扣额度并生成两张单。做法是客户端每次表单提交带一个 `requestId`：撞唯一键时整个申请事务
   （连同已做的扣减）回滚，服务按同一把键读回先赢的那张单返回。MySQL 唯一索引不约束 NULL，所以不传键的调用
   行为不变。单号 `application_no` 改成 `AyyMMdd-8位随机`（字符集去掉易混字符）而不是自增号：它会出现在 URL、
   导出文件与厂商沟通里，自增号等于把租户量级和别人的单号一起发出去。
2. **`ppidAllocationMode` 没有做**：§7.1 的入参里列了这个字段。只支持"按 型号+SPU 余量倒序贪心"一种分配，
   人工指定 PPID 会把超卖判定从数据库的条件更新挪回应用层的读后写，MES 那 11 个补偿端点就是这么攒出来的。
   真有厂商需要指定额度时，再加一个"候选白名单"过滤条件，而不是另一套分配实现。
3. **消息在事务提交后投递失败只记 ERROR，不回滚也不报错**：此刻申请单已经生效（额度已扣、单已落库），
   把 500 甩给用户只会诱导他再点一次。分片仍是 `PENDING`，由 P5 的兜底扫描按 `next_execute_time` 补发。
   这条取舍要求 P5 的扫描必须真的实现，否则就是静默丢单。
4. **AFTER_COMMIT 用 `TransactionSynchronizationManager` 而不是 `@TransactionalEventListener`**：申请服务用的是
   `TransactionTemplate`（编程式事务）——为了能在事务**外**捕获 `DuplicateKeyException` 再回读对手的单，
   `@Transactional` 注解的自调用代理在这里不好摆。同步回调与事件监听器语义等价，且单测可以直接触发 `afterCommit()`
   来证明"回滚不发消息"，不需要起一个真事务管理器。`publishAfterCommit` 在没有活动事务时**直接抛
   `IllegalStateException`**：降级为"立即发送"就复刻了 MES 的幽灵申请形状。
5. **分片 ID 靠事务内回读**：`insertBatch` 是多值插入，拿不回自增主键，而消息体必须带 `shardId`。
   单张申请的分片是个位数，多一条按 `application_id` 的查询比手写主键回填便宜。P5 若把分片数放大要重新评估。
6. **检查顺序是硬约束**，改动前要留意：幂等预查 → 凭证存在且未过期 → 租户单次上限 →（进事务）拆分预估 →
   **先插申请单**再逐条 `deduct` → 落分片 → 留痕 → 登记消息。插单先于扣减，重复提交那条路径连额度都不碰；
   凭证检查先于扣减，证书坏掉的租户不会先吃额度再失败。
7. **消息体只带 ID 是类型层面的约束**：`StageMessage` 是 `record(tenantId, applicationId, shardId, stage, traceId)`，
   结构里就没有 PPID、数量的位置，将来想泄露得先改这个类型。`traceId` 直接用申请单号，同步段与五个阶段的日志能串起来。
   队列侧对账用 `rabbitmqctl list_queues`（免安装 RabbitMQ 的 ctl 脚本要 `ERLANG_HOME` + `erts-*/bin` 在 PATH 里，
   即 §10 第 3 个坑的同一把锁）。
8. **拓扑只声明用得上的部分**：P4 只建 `mfi.token.normal` 与 `mfi.apply.queue`，后四个阶段的队列、延迟队列与 DLX 留给 P5，
   避免声明一堆没人消费也没人测试的绑定。消息转换器用 `Jackson2JsonMessageConverter`（不用 JDK 序列化，否则消费端
   类结构一变，broker 里的旧消息就读不出来）。

### P5 编码期偏离与取舍

1. **状态机只留在 `StageRunner`，处理器只回答"这次怎么样"**：`StageOutcome` 只有 ADVANCE/WAIT/RETRY/DEAD 四种，
   五个处理器不写跃迁、不碰重试计数、不投消息。MES 那五个监听器各自 `updateStatus` 再各自 `sendToDelay`，
   正是"重试 2 次后静默失败"与"有分片失败但单据显示成功"的共同来源；跃迁的正确性（CAS 前态、重试计数、投递时机）
   集中一处才能被测一处，所以 `StageRunnerTest` 是本轮最重的一组测试。
2. **消费顺序是硬约束**：`claim`(CAS) → 回库读分片与申请单 → 早到检查 → 租户闸门 → 处理器 → 跃迁。
   `claim` 返回 0 就是唯一的去重点，之后的回库读、闸门、处理器一条都不许走到——重复投递若在闸门处才被发现，
   APPLY 阶段已经多调了一次苹果。
3. **等待不算失败重试**：`hold()` 把 `retryCount` 原样传回，只有 RETRY 才 +1。苹果的 `retry_download_after`
   是小时级，若等待消耗重试，一次大文件申请会在几个小时内被自己的等待耗进死信队列。
4. **超过最长档位时不猜时间**：延迟靠"队列级 TTL + 死信回流"，档位是固定的几条队列（1m/5m/30m/1h），
   不能为"两小时四十分"临时造队列。做法是让消息按最长档位到货，`StageRunner` 再比对 `next_execute_time`：
   仍早于计划（容差 60s，抵消时钟与排队抖动）就按原计划重排一次。代价是最多每档位一次空转往返，
   收益是绝不提前调苹果，也保住"同一 TTL 队列里队头即最早到期"这条前提。
5. **先落库再投递，跃迁影响 0 行就不投**：`advance`/`schedule`/`markDead` 都是带前态条件的单条 UPDATE，
   返回 0 说明被兜底扫描或另一条消息抢先，本跳放弃。反序会在落库失败时留下没人认领的消息。
   `retry_count` 与 `next_execute_time` 由同一条 UPDATE 写，避免"计数加了、时间没加"的半次跃迁。
6. **兜底扫描干两件事且都不判失败**：PROCESSING 超过 `processing-timeout-minutes` 复位成 PENDING（消费者被杀、
   进程崩溃），PENDING 且 `next_execute_time` 已过 `scan-grace-seconds` 的补发（MQ 丢消息、P4 那条
   "AFTER_COMMIT 投递失败只记 ERROR"的取舍就在这里落地）。复位不加 `retry_count`——被捞回不是分片的错。
   扫描必须跨租户，对应 Mapper 用 `@InterceptorIgnore(tenantLine = "true")` 并自带 `tenant_id` 输出列。
   两件事都用活体验证过（第 [6] 节把分片伪造成 PROCESSING 让扫描捞回），但**"整只 RabbitMQ 停一次再重启"这条
   最终清单项没有在 P5 跑**：它要重启本机共享的 broker，而投递失败与消息丢失走的是同一个补发分支，
   留给 P8a 与安全验收一起做，届时按最终清单逐项对账。
7. **申请单 PENDING→RUNNING 由第一个被领取的阶段消息写**（本轮活体暴露的真实缺陷）：聚合原本只在终态事件
   （FINISH 完成、DEAD）触发，于是"等苹果生成文件"的那段时间里读侧一直显示"已受理"，用户以为单子卡住了。
   补一条 `where status = 'PENDING'` 的条件更新（`ApplicationMapper.markRunning`），最多真正写一次，
   既不会把已落定的终态改回进行中，也不在五个阶段上造成写放大。
8. **死信不自动重放**：`mfi.token.dlq` 的消费者只记一条"需要人工介入"并保留 `x-mfi-dead-reason` 头。
   自动重放会把苹果侧的持续故障变成静默的无限循环；重放入口是 P6 的 `POST /api/applications/{no}/retry`，
   且只作用于失败分片。
9. **租户并发闸门只有单测覆盖**：`TenantConcurrencyGate` 是每租户一个信号量 + 非阻塞 `tryAcquire`，
   拿不到就 `hold`（不计重试）。本机每个队列只有 1 个消费者线程（`prefetch=1`），活体里没法把额度占满，
   硬凑要并发改多租户多分片，超出本轮验收面；跨租户互不影响留给 §11 最终清单的渗透自测那一条统一验。
10. **桩的 Token ID 必须逐请求唯一**：第一轮活体 6 项失败的根因不在流水线，而在 `scripts/MfiStubServer.java`
    把两份文件里的 Token ID 都从 `TST0000001` 重排，撞上 `uk_token_tenant_entity(tenant_id, auth_entity_id)`
    之后第二份文件的行被静默忽略，FINISH 数到 0 个 token 判失败。真实苹果的 Token ID 全局唯一，桩照做即可
    （前缀改成 `"TST"+序号+"-"`）。这次意外反证了三件事：逐文件推进真的在跑、FINISH 的数量校验有效、
    DEAD→DLQ→申请单 FAILED 的闭环有效。
11. **验收专用的短延迟档位**：本轮用命令行把档位临时改成 10s/30s/1m、`processing-timeout-minutes=1`
    （不改入库的 `application.yml`）。延迟机制一条没换，仍是队列级 TTL + 死信回流；不换档位就无法在一轮脚本里
    跑完"等两小时的文件"。改动档位的真实代价写在 `PipelineProperties` 注释里：档位即队列名，滚动升级必须
    先停生产、清干净旧队列再改。
12. 工具侧的坑（不是产品问题，但每轮都会踩）：Windows 的 `mysql.exe` 往管道写本地代码页（GBK），中文
    `last_error` 断言全挂，必须显式 `--default-character-set=utf8mb4`；桩与 app 一律 `-encoding UTF-8` /
    `-Dfile.encoding=UTF-8`；`-pl mfi-service spring-boot:run` 之前不 `install` 会从本地仓库拿旧 `mfi-dao` jar，
    新增的 Mapper 方法在运行时表现为 `NoSuchMethodError`（P3 第 8 条同一个坑，这次栽在运行期而不是编译期）。

### P6 编码期偏离与取舍

1. **SN 计数器不按 `spu` 拆**（推翻 §5.6 里 P3 期间的那条修正，理由与活体证据都写在 §5.6）：SN 格式里没有 SPU 段，
   按 SPU 拆 key 会把"跳号难解释"换成"重号"，而重号不可追溯、断号只是难看。最终粒度是 `租户 + SN 前段本身`。
2. **宁可断号也不重号**：号段在 Redis 占住之后、落库之前进程死掉，这段号作废且不回收。FINISH 只给"还没有号"的行发
   （`selectSnMissing`），所以这一跳天然可重入——重试时已落号的行不参与，最多给新出现的空行补号，绝不会把某一行已经
   交给厂商的序列号换成另一个。`assignSn` 的 WHERE 再带一次"SN 为空"，并发下后写的一方直接落空而不是覆盖已发的号。
3. **生产日按 `Asia/Shanghai` 而不是 JVM 默认时区**：容器里常是 UTC，月末与年末边界会差出一天，前缀跟着错一天。
   日期做成可传入的包级方法只为测试能按指定日期断言跨年/跨月，不依赖跑测试的当天。
4. **流水撞 4 位上限时变长而不是截断**（与 MES 一致）：只记 WARN。单个 PPID 单日产量超过 1 万说明位数方案本身要厂商
   重新确认，服务端擅自截断成重复的 4 位是不可逆的数据事故。
5. **导出状态词与 DDL 对齐**：`ExportTaskStatus` 用 `PENDING/PROCESSING/SUCCESS/FAILED`，与 `t_export_task.status`
   的注释同一套词，不再引入 MES 的 0/1/2 数字态。任务号 `taskNo` 而不是自增 id 出现在 URL 与文件名里：id 会让"猜邻居的任务"
   变成一次算术。
6. **运行参数与业务配额分家**：`page-size`/`max-rows`/`worker-size`/`queue-capacity` 走 `mfi.export.*`（`ExportProperties`），
   销毁的 500/请求与 100/批次是 `TokenDestroyService` 的 Java 常量，都**不进 `TenantConfigKey`**。一个租户把导出上限调到
   千万行会耗尽整个进程的堆与磁盘，不可逆动作的单次批量也不是它能自行放开的东西。
7. **"导出 1 万条不 OOM"没有按字面跑一万行**：改成把档位压到 `page-size=100 / max-rows=1000` 并用 1200 行的真实单，
   让翻页与截断都在活体里真的发生（10 页取满、第 11 页不取、留下 WARN 与 `EXPORT|1000` 留痕）。内存结论由机制承担：
   keyset 按主键前进，每轮只驻留一页 POJO 与刚转出的行，代价与总量无关；再加 `TokenMapper#selectExportPage` 的 SQL 形态
   与 `ExportServiceImplTest` 的翻页/截断断言。真跑一万行只增加耗时，不增加证据。
8. **导出产物只认发起人，而不是"同租户共享"**：产物里就是 `encoded_token` 与 `secret_key`，同租户的其他成员没有下载
   别人导出结果的理由；`ExportTaskVo` 因此不回显 `filePath`，筛选条件快照也不回显（里面含 PPID）。下载按"配置目录 +
   库里存的相对名"拼路径，先校验名字匹配 `^[A-Za-z0-9._-]{1,128}$` 再校验 `startsWith(base)`，所以产物目录必须是本服务
   独占的（写进 `ExportProperties` 注释）。僵死的 PROCESSING 任务由定时扫描置败——执行进程重启或 OOM 后不能留下永远"进行中"
   的任务与半个文件。
9. **CSV 与 XLSX 共用同一份行模型**：`TokenExportRow` 的时间列是字符串而不是 `LocalDateTime`。两条写值路径格式化之后
   输出完全一致，产物不再依赖某个转换器版本的默认格式；状态列输出枚举名而不是中文，因为这份文件是给机器与厂商对账用的，
   中文改词会让对方的导入脚本静默错分类。
10. **销毁的不可逆按四条形制兜住**：仅 owner；二次确认要**把申请单号原样再输一遍**（复选框挡不住误操作）；只对 ISSUED 的行
    发起（注册过的实体在苹果侧已绑配件 UUID，销毁必被拒，与其等苹果报错不如在查询条件里就排除）；单次 500 个上限，剩下的
    留给下一次点击（`hasMore` 为真）。逐批推进、每批成功即单独落库，一批被拒就停手不把剩下的继续送去——与
    `RegisterStageHandler` 同一套纪律，剩下的还有机会救回来。半途失败如实回 `destroyed`/`hasMore`/`failReason` 而**不抛异常**：
    那意味着"确实已经有一部分没了"，压成错误响应等于瞒住事实。苹果的返回文案可能把 PPID 原样抄回来，所以它只进响应、
    不进日志与留痕；`IOException` 分支不重试，因为"请求已生效但响应丢了"与"请求没出去"在客户端分不出来。
11. **`TokenStatus.destroyable()` 收窄到只剩 ISSUED**：SCRAPPED 只是本系统自己打的报废标记，苹果侧到底还绑没绑配件无从
    确认。方法原本把 SCRAPPED 也算可销毁，与 `selectDestroyCandidates` 的 ISSUED-only 条件相互矛盾——读侧会显示"可销毁"
    而提交后候选为空。两处必须同一口径，方法注释直接指向那条 SQL。
12. **`ResponseWrapAdvice` 不能包装 `ResponseEntity`（活体第一轮 6 项失败的根因）**：导出下载声明
    `ResponseEntity<Resource>`，被统一响应包装塞成 `R` 之后，按 Resource 选中的 `ResourceHttpMessageConverter` 在
    `getContentLength` 处抛 `ClassCastException`——文件已写好、库里已是 SUCCESS，附件却下不来。这类"全局 advice ×
    局部返回类型"的冲突单测看不见，只有真下载才发现；除修 `supports()` 之外补 `ResponseWrapAdviceTest` 两条把边界钉住。
    另两处同期修正：`TokenMapper` 的 `selectUnregistered`/`countUnregistered`/`selectSnMissing` 各补一条
    `status <> 'DESTROYED'`（少了它们，销毁过后的分片被重放时会把苹果侧已不存在的实体再注册一遍、收口校验把已作废的行
    算成漏注册、以及给已作废的实体白吃一个序列号），`MfiTokenClient` 三个方法的日志摘掉 PPID 与实体清单（§6.3）。
13. **桩侧调用次数只能按基线 + 增量对账**：桩进程跨多轮运行、日志是累加的，而新增的注册/销毁剧情日志按 §6.3 不带 PPID，
    所以脚本无法把一次拒绝归到某张单上，只能在启动时取基线、之后算增量。这带来一个前提：兜底扫描会在应用启动后几秒内把
    上一轮遗留的延迟消息消费掉，`run-p6.sh` 因此把计数基线放在"健康检查通过 + 30 秒消费窗口"之后，否则上一轮的两次注册
    拒绝会被算进本轮，"苹果侧正好两次"这条断言就会飘。
14. **`t_token.encoded_token` 的列注释与实现相反**（`V3__token_encoded_column.sql` 写"禁止进日志与导出列"）：前半句是
    §6.3 红线、成立；后半句不成立——导出正是 token 原文的唯一出口（`TokenExportRow` 第 6 列）。为一句注释去改已应用的
    迁移会让 Flyway 校验和与本地库不一致，不值得动迁移链；以 §6.3、本节与验收第 [5] 节为准，P8a 若重排基线 DDL 一并改掉。
15. 工具侧新增两条（都不是产品问题）：`-pl mfi-service spring-boot:run` 之前必须先
    `mvn -o -am -pl mfi-service install -DskipTests`，否则运行时用的是本地仓库里的旧 `mfi-dao`/`mfi-apple` jar，
    新增的 Mapper 方法表现为 `NoSuchMethodError`（P5 第 12 条同一个坑）；`-Dmfi.export.dir` 要给**绝对路径**，
    因为应用的工作目录是 `mfi-service/` 而验收脚本从仓库根跑，相对路径会在两处各长出一个目录、"产物只落在配置目录"
    那条断言直接失真。

### 最终验收清单

- [ ] 两个租户各用独立证书并发申请，数据、PPID、token 完全隔离
- [ ] 任一租户证书错误/过期不影响其他租户消费
- [ ] 全程无明文凭证与 `secret_key` 出现在日志
- [ ] 运行日志 `grep` 不到 PPID 明文，也 `grep` 不到 SQL 的 `==> Parameters`（入库配置里 mapper 包必须是 INFO，见 §6.3）
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

P0–P6 已交付并本机验收（§11）。P5 把五个阶段的队列、TTL→DLX 延迟回流、CAS 跃迁、重试与 DLQ、兜底扫描接成闭环；
P6 把结果侧收口：SN 按"计数器粒度等于 SN 前缀粒度"的 Redis 号段生成、进度与结果的三个读接口、失败分片的人工重放、
有界线程池的异步导出（产物含 token 原文，因此只认发起人），以及销毁未使用实体——本服务唯一一个"不可逆且把额度还给
PPID"的入口。

接下来进入 **P7 前端**（§9）：Vue3 + Vite + TS + Pinia + Element Plus，八个页面 + 租户切换器 + owner 可见性 +
进度时间线组件。构建产物由 `maven-resources-plugin` 复制进 `mfi-service` 的 `static/` 单进程部署，开发期 Vite proxy 到
`localhost:8090`；本机 `npm` 已损坏，一律用 `pnpm`（§12 最后一条）。P7 的验收口径是浏览器实测完整链路
（注册→上传证书→导入 PPID→申请→进度→导出→销毁），它会第一次把 §8 的全部端点按真实顺序串起来——P6 那处
"全局响应包装 × 局部返回类型"的打架（§11 的 P6 偏差第 12 条）只在真点下载时才暴露，前端联调就是这类接线问题的
下一个暴露面。P8a 再按 §11 最终验收清单逐项复跑，包括 P5 欠下的那条：把本机共享的 RabbitMQ 重启一次，
证明拓扑重声明与兜底扫描自愈真的成立。
编码过程中的偏离会在本文档同步更新。
