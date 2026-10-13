# apple-mfi-token

面向 MFI 被授权厂商的**多租户 SaaS**，覆盖苹果 Software Token 的申请、下载、注册与导出全链路。

## 定位

- 租户 = 一家 MFI 厂商，持有**自己的**苹果客户端证书和自己的 PPID 池
- 同租户内用户数据共享，**只做用户管理，不做权限管理**（仅保留 `owner_flag` 一个布尔位圈住高危操作）
- 与 `galaplat-development-mes` 并行独立：不共库、不互调、不依赖 Nacos 与内部 jar

## 当前状态

**P0–P8a 已交付并在本机活体验收**（真实 MySQL + Redis + RabbitMQ + 自签 CA 的 two-way TLS 桩）。
唯一未完成的是 **P8b 真证书联调**，阻塞在外部输入：需要厂商真实的苹果客户端证书。

**R1–R4 后端结构重构已交付**：三个 Maven 模块合成一个可部署模块，业务边界改由限界上下文的包表达，
上下文之间只通过端口往来并由 ArchUnit 守门。HTTP 契约、Flyway 迁移历史与 MQ 载荷全程冻结，
P2/P3/P4/P5/P6/P8 六轮活体验收在重构后复跑通过（详见计划 §15）。

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

## 结构

Maven 只有一个可部署模块 `mfi-service`（根 `pom.xml` 只做父 POM 与依赖版本管理）。业务边界用
**包**表达，不用模块表达：每个限界上下文内部固定四层 `domain / application / infrastructure /
interfaces`，上下文之间**只通过消费方定义的端口**通信，由
`mfi-service/src/test/java/com/galaplat/mfi/architecture/ArchitectureTest.java` 在 `mvn test`
里守门（无环、不得引用他上下文的 `infrastructure`/`interfaces`、`apple` 保持叶子、`shared` 不认识
业务上下文、`domain` 不反向依赖 `interfaces`）。

| 包 | 职责 |
|---|---|
| `shared` | 响应包装与全局异常、健康检查、MyBatis-Plus 租户 SQL 改写与白名单、租户上下文与 sa-token 装配 |
| `apple` | 苹果 MFI 客户端与模型、SSL 上下文构造、下载可用性解析（防腐层，无库无 Web 职责） |
| `credential` | 凭证材料落库加密、按租户构造 mTLS 客户端工厂（`MfiClientFactory`）、租户苹果域名 |
| `identity` | 用户、租户、成员、租户配置、登录会话、审计留痕与只读端口 |
| `ppid` | PPID 池、Excel 导入、分配与 CAS 扣减 |
| `issuance` | 申请聚合、五阶段流水线、token 结果、进度、重放、导出、销毁 |
| `web` | Vue 3 前端（pnpm） |
