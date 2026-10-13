# 部署与运行说明

面向把 `apple-mfi-token` 跑到一台服务器（或本机开发环境）的人。链路与状态语义见 `docs/mfi-token-flow.md`，
设计取舍见 `docs/DEVELOPMENT_PLAN.md`。

## 1. 依赖版本（实测口径）

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | **17** | `maven.compiler.release=17`。不要按 21 设定或推荐依赖 21 的框架版本 |
| Maven | 3.9.x | 只需能解析公网 Maven Central |
| MySQL | 8.0 | Flyway 全量托管 DDL，账号需对 `apple_mfi_token` 库有 DDL 权限；无建库权限时由 DBA 预建库 |
| Redis | 5+ | sa-token 会话存储（uuid token，状态在服务端），**启动即必需**，无内存兜底 |
| RabbitMQ | 3.13（需匹配 Erlang/OTP） | 五阶段流水线 + TTL 延迟 + DLX 死信 |
| Node/pnpm | Node 20+，**pnpm 10** | 前端构建。不要用 npm |

## 2. 环境变量

只有键名进版本库（`.env.example`），真实值一律放部署环境或 gitignored 的 `.env` / `application-local.yml`。

必需（缺失即启动失败）：

```
MFI_DB_USERNAME / MFI_DB_PASSWORD      数据源账号
MFI_RABBITMQ_USERNAME / _PASSWORD      MQ 账号
MFI_CREDENTIAL_MASTER_KEY              AES-256-GCM 主密钥，Base64 的 32 字节；用于加密租户 MFI 证书与私钥
```

有默认值、按需覆盖：

```
MFI_SERVER_PORT=8090
MFI_DB_URL=jdbc:mysql://localhost:3306/apple_mfi_token?...
MFI_REDIS_HOST / _PORT / _PASSWORD / _DATABASE
MFI_RABBITMQ_HOST / _PORT / _VHOST
MFI_CREDENTIAL_KEY_ID=v1                       # 当前加密密钥 id
MFI_CREDENTIAL_MASTER_KEY_PREV=                # 轮换后仍要解历史密文时填上一把；未轮换留空
MFI_APPLE_TRUSTED_CA_PEM=                      # 额外信任锚；访问真实苹果端点必须留空
MFI_EXPORT_DIR=./export                        # 导出产物目录，本服务独占、绝不进版本库
```

三条硬性要求：

- **主密钥不得进仓库、不得复用示例值**。轮换时旧密钥留在环里（`_PREV`）继续解历史密文，新写入用
  `MFI_CREDENTIAL_KEY_ID` 指向的那把。
- **访问真实苹果不要设 `MFI_APPLE_TRUSTED_CA_PEM`**。它只为本地 mTLS 桩/自建代理存在；设了就等于往信任库
  里塞自签 CA。
- **租户的苹果客户端证书由控制台上传**（`PUT /api/tenant/credential`），不进文件系统、不进配置、不进仓库。
  本服务永远不持有"共享证书"。

## 3. 底座

有 Docker 时：

```bash
cp .env.example .env      # 填真实值
docker compose up -d mysql redis rabbitmq
```

没有 Docker（例如 Windows 开发机）就装本机服务：MySQL 8.0 服务 + 便携版 Redis + 免安装 RabbitMQ（RabbitMQ
需要 `ERLANG_HOME` 指向 Erlang/OTP，并把 `<erlang>\bin` 与 `erts-<ver>\bin` 加进 PATH，否则 `rabbitmqctl.bat`
静默失败）。

队列与交换机**不需要手工创建**：应用通过 `Declarables` 声明，启动时与每次 AMQP 重连后自动重声明。
P8a 验收里专门把 broker 杀掉再起来验证过这一点。

## 4. 构建

```bash
# 后端（仓库根）
mvn -q -DskipTests install          # 多模块：先 install，-pl 单模块构建才会拿到兄弟模块的新 jar
mvn test

# 前端（web/）
pnpm install
pnpm typecheck
pnpm build                          # 产出 web/dist
```

前端构建之后，`mfi-service` 的文件激活 profile `web-ui`（判据是 `web/dist/index.html` 存在）会在
`process-resources` 把 `web/dist` 拷进 `classes/static`，于是同一个 Boot 进程同时托管控制台静态资源与
`/api`。**没跑过 `pnpm build` 时该 profile 不激活**，后端构建与测试不受前端影响。前端是 hash 路由，
不需要为 SPA 加转发规则。

只想刷新前端而不重启后端：

```bash
mvn -o -q -pl mfi-service process-resources   # 若运行的是 spring-boot:run（classes 目录）
```

## 5. 运行

```bash
java -jar mfi-service/target/apple-mfi-token.jar
```

自检：

```bash
curl -s localhost:8090/api/health          # {"code":"0",...,"status":"UP"}
curl -s localhost:8090/                     # 单进程部署时返回控制台 index.html
```

首次启动 Flyway 会建齐 V1–V3 的全部表；库里没有任何角色/权限/菜单表（本产品只做用户管理，不做权限系统）。

前后端分离部署也可以，但会话令牌只走请求头 `mfi-token`（`sa-token.is-read-cookie: false`，不发 cookie，
下载走 `fetch` + Blob）。反向代理因此必须放行这个自定义请求头；同源部署无需配 CORS。

## 6. 运维参数

`mfi.*`（`application.yml`，均可用 `-D` 覆盖）：

```
mfi.pipeline.delay-tiers=[60000,300000,1800000,3600000]   # 退避档位（毫秒，升序）
mfi.pipeline.scan-interval-millis=30000                   # 兜底扫描周期
mfi.pipeline.scan-grace-seconds=90                        # PENDING 补发的宽限窗口
mfi.pipeline.scan-batch-size=200
mfi.pipeline.processing-timeout-minutes=30                # PROCESSING 超时判定为消费者已不在
mfi.pipeline.gate-retry-millis=30000                      # 租户并发额度占不到时的重排间隔
mfi.export.dir / page-size / max-rows / worker-size / queue-capacity / stale-timeout-minutes / scan-interval-millis
```

**改 `delay-tiers` 等于改队列名**：延迟队列按档位命名，滚动升级时旧队列里可能还有消息在等 TTL。安全顺序是
先停止生产（不再受理申请）、等旧队列排空、再上新配置。

租户级业务参数在 `t_tenant_config` 里按租户覆盖（`APPLY_MAX_QUANTITY`、`APPLE_REGISTER_BATCH_SIZE`、
`PIPELINE_MAX_RETRY`、`PIPELINE_CONCURRENT_PER_TENANT`、`SERIAL_NUMBER_PREFIX`），不要在代码里写死。

日志：`com.galaplat.mfi.dao.mapper` 必须停在 **INFO**。DEBUG 会把 SQL 与占位符实参整条打进去，里面是 PPID
与凭证密文。本地临时排查只在 `application-local.yml` 里开。

告警抓手（都是 ERROR，可直接被抓取）：

- `需要人工介入-分片已进入死信` —— 重试耗尽或永久失败，需人工判断后用 `POST /api/applications/{no}/retry`；
- `分片消息投递失败，等待兜底扫描补发` —— broker 抖动，通常自愈；
- `兜底扫描: 复位 PROCESSING N 个，补发 PENDING N 个` —— 自愈在跑；持续非零要查消费者。

## 7. 备份与迁移纪律

- DDL 只能走 Flyway：**不手工改库，不编辑已应用的迁移**。要加列就加 `V4__*.sql`。
- `mfi.export.dir` 里的产物含 **token 原文**，目录必须本服务独占、不进版本库、按租户数据保密级别备份与清理。
- 凭证密文在 `t_tenant_credential`，丢了主密钥等于丢了全部租户证书；主密钥的备份与库备份分开管理。

## 8. 本地开发与验收

`application-local.yml`（gitignored）覆盖数据源、MQ、Redis 与 `MFI_APPLE_TRUSTED_CA_PEM`，用
`--spring.profiles.active=local` 启动。苹果侧用自签 CA 的 mTLS 桩替代：`scripts/gen-dev-certs.sh` 生成
CA + 服务端 + 两套租户证书（PKCS#8 / PKCS#1）+ 一张过期证书到 gitignored 的 `.dev-certs/`，
`scripts/MfiStubServer.java` 用 JDK 单文件源码方式启动 need-client-auth 的 HTTPS 桩；租户的
`apple_base_url` 指到 `https://localhost:8443` 即可跑通完整链路。

活体验收脚本（只依赖标准库 + 本机 mysql/rabbitmqctl）：

```bash
python -m py_compile scripts/verify-p8.py
PYTHONIOENCODING=utf-8 python -u scripts/verify-p8.py
```

脚本自己注册租户、上传各自证书、并发下单、杀 broker 再拉起、跑跨租户渗透与日志红线 grep。
它只在 stdout 打计数与断言名，不打印任何凭证或 token 材料。

## 9. 已知未验证项

真实厂商证书到位前的 5 项风险（证书链与 CN 校验、`technology` 接受度、真实限流阈值、响应字段一致性、
苹果侧 PPID 配额）见 `docs/mfi-token-flow.md` §9。本地桩不能替苹果业务规则背书。
