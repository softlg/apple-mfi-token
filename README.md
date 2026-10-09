# apple-mfi-token

面向 MFI 被授权厂商的**多租户 SaaS**，覆盖苹果 Software Token 的申请、下载、注册与导出全链路。

## 定位

- 租户 = 一家 MFI 厂商，持有**自己的**苹果客户端证书和自己的 PPID 池
- 同租户内用户数据共享，**只做用户管理，不做权限管理**（仅保留 `owner_flag` 一个布尔位圈住高危操作）
- 与 `galaplat-development-mes` 并行独立：不共库、不互调、不依赖 Nacos 与内部 jar

## 当前状态

**开发计划待确认，尚未进入编码。**

- 产品约束、表结构、五阶段流水线设计、分期计划与验收标准见
  [docs/DEVELOPMENT_PLAN.md](docs/DEVELOPMENT_PLAN.md)
- 工程执行守则见 [AGENTS.md](AGENTS.md)
- 计划 §13 列有 8 个待确认的开放问题（PPID 来源、`apple_technology` 取值域、SN 规则、
  销毁权限、通知渠道、部署形态、DAO 风格、苹果测试环境）

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
