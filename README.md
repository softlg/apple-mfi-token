# apple-mfi-token

面向 MFI 被授权厂商的**多租户 SaaS**，覆盖苹果 Software Token 的申请、下载、注册与导出全链路。

## 定位

- 租户 = 一家 MFI 厂商，持有**自己的**苹果客户端证书和自己的 PPID 池
- 同租户内用户数据共享，**只做用户管理，不做权限管理**（仅保留 `owner_flag` 一个布尔位圈住高危操作）
- 与 `galaplat-development-mes` 并行独立：不共库、不互调、不依赖 Nacos 与内部 jar

## 当前状态

**P0–P8a 已交付并在本机活体验收**（真实 MySQL + Redis + RabbitMQ + 自签 CA 的 two-way TLS 桩）。
唯一未完成的是 **P8b 真证书联调**，阻塞在外部输入：需要厂商真实的苹果客户端证书。

- 产品约束、表结构、五阶段流水线设计、分期计划与验收标准见
  [docs/DEVELOPMENT_PLAN.md](docs/DEVELOPMENT_PLAN.md)
- 苹果 API 语义、状态机、隔离边界与日志红线见 [docs/mfi-token-flow.md](docs/mfi-token-flow.md)
- 依赖版本、环境变量、构建/运行/运维参数见 [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)
- 工程执行守则见 [AGENTS.md](AGENTS.md)
- 计划 §13 的开放问题已在实现中逐条收敛，结论与偏离记在 §11 各阶段的"编码期偏离与取舍"

## 快速开始

```bash
mvn -q -DskipTests install            # 后端（JDK 17）
cd web && pnpm install && pnpm build   # 前端（用 pnpm，不用 npm）
cd .. && java -jar mfi-service/target/apple-mfi-token.jar
curl -s localhost:8090/api/health
```

必需环境变量与可覆盖项见 `.env.example`，本地覆盖写进 gitignored 的 `application-local.yml`。

## 技术栈

JDK 17 · Spring Boot 3.4.1 · MyBatis-Plus + Flyway · MySQL 8 · Redis · RabbitMQ · sa-token
· Vue 3 + Element Plus + Vite（pnpm）

## 模块

| 模块 | 职责 |
|---|---|
| `mfi-apple` | 苹果 MFI 客户端，按租户构造 mTLS |
| `mfi-dao` | MyBatis-Plus 实体与 Mapper、Flyway 迁移、租户 SQL 改写 |
| `mfi-service` | Spring Boot 应用：接口、业务、MQ 流水线、凭证加密、租户上下文 |
| `web` | Vue 3 前端 |
