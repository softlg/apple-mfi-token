# MFI Token 链路说明：苹果 API 语义与状态机

本文档描述 `apple-mfi-token` 从"厂商提交一次申请"到"token 带序列号可导出"的完整语义，以及这条链路上
每一个状态迁移的判定依据。它是 `docs/DEVELOPMENT_PLAN.md` 的实现侧对照物：计划回答"为什么这样设计"，
本文回答"跑起来到底是什么行为"。改动流水线、PPID 分配或销毁逻辑前先读这里。

约定：**苹果**指 `https://swa.apple.com` / `https://sauth-external.apple.com` 上的 MFI Software Token API；
**分片（shard）**指一条 `t_application_ppid`，即"某次申请里、某个 PPID 上的一笔分配"；
**阶段（stage）**指 APPLY / FILE_NAME / FILE_CONTENT / REGISTER / FINISH 五个环节。

---

## 1. 苹果侧接口语义

认证方式是 **mutual TLS**：每个租户一套自己的客户端证书与私钥，握手时由本服务出示。没有 API key、
没有 OAuth，凭证一旦正确就长期有效，所以"哪个租户的连接"完全由 SSLContext 决定。地址常量与分流在
`mfi-apple/src/main/java/com/galaplat/mfi/apple/MfiEndpoints.java`。

`technology` 决定走哪个域名：`HomeKit` 走 `sauth-external`，其余（`AirPlay Audio`、`DockKit`、
`Find My network`、不指定）走 `swa`。租户可以用 `t_tenant.apple_base_url` 覆盖整个前缀——本地 mTLS 桩
和自建代理就是这么落地的，真实部署时留空。

| 接口 | 方法 | 语义 | 本链路用在哪个阶段 |
|---|---|---|---|
| `/authEntityRequests` | POST | 按 PPID 申请 N 个认证实体，**异步受理**：立刻返回 request id，token 还没生成 | APPLY |
| `/authEntities/{requestId}` | GET | 取该 request 生成的文件名清单；未就绪时返回 `retry_download_after`（UTC 时刻） | FILE_NAME |
| `/authEntities/{requestId}/{fileName}` | GET | 下载 token 文件，每个文件固定 2000 条 | FILE_CONTENT |
| `/bulk/usedAuthEntities` | POST | 批量把 token 标记为"已使用"并绑定配件 UUID，**不可逆** | REGISTER |
| `/single/usedAuthEntity` | POST | 单条注册（现场配置场景） | 预留 |
| `/unusedAuthEntities` | POST | 销毁未使用的认证实体，额度回退给 PPID | 销毁入口 |
| `/status/{requestId}` | GET | 查询 request 处理状态 | 排障 |
| `/authEntities/{authEntityId}` | GET | 查询单个 token 信息 | 排障 |

三条对代码有直接影响的苹果行为：

- **受理不等于到货**。`/authEntityRequests` 返回成功后，文件可能要等数十分钟到数小时才出现，且苹果会在
  响应里给出建议的下次尝试时刻。因此 FILE_NAME 的"还没好"是**正常分支**，不能算失败、更不能消耗重试次数
  （`FileNameStageHandler` 把它写成 `WAIT` + `next_execute_time`）。
- **4xx 与 5xx 的处置完全不同**。4xx 是证书权限、PPID 无效这类配置问题，重试一百次也不会变好，直接判
  永久失败进 DEAD；5xx 与 IO 异常才走退避重试（`ApplyStageHandler.isClientError`）。
- **注册一旦成功就回不去**。`accessory_uuid` 在 FILE_CONTENT 阶段随 token 一起落库，REGISTER 只读不写；
  否则一次网络失败会让同一个 token 在苹果侧对应两个配件（`FileContentStageHandler` 类注释记录了这一点）。

`TenantConfigKey.APPLE_TOKENS_PER_FILE` 只是"苹果单文件固定 2000"的镜像值，用于校验和展示；实际的下载
与落库按文件名清单逐个推进，不依赖这个数。

---

## 2. 同步段：一次申请受理时发生了什么

入口 `POST /api/applications`，实现 `mfi-service/.../service/application/impl/ApplicationServiceImpl`。
三条顺序上的硬约束，缺一条就会产生"额度被吃掉但单没落下"的中间态：

1. **凭证检查在扣减之前**：只看库里的元数据（存在且未过期），不构造客户端、不联网。证书坏的租户不该先
   吃掉额度再收到一张注定失败的单。
2. **申请单插入在扣减之前**：幂等键（`requestId`）撞库时立刻失败，此时本次事务一笔扣减都还没做。
3. **消息登记在事务内、投递在提交后**：`ShardMessageProducer.publishAfterCommit` 走 `AFTER_COMMIT`，
   绝不在开着的事务里发 MQ。

一个事务内的完整动作（`doApply`）：

```
planAllocation(产品型号, SPU, 申请量)      # 与 GET /api/ppid/available 同一条查询、同一份贪心
  → 落 t_application（单号 A{yyMMdd}-{8位随机}）
  → 逐条 PpIdMapper.deduct(tenantId, ppidId, quantity)  # 条件 UPDATE，返回值必须等于 1
  → 落 t_application_ppid（每条额度行一个分片，stage=APPLY，stage_status=PENDING）
  → 写操作日志
  → 登记 AFTER_COMMIT 投递
```

分配粒度是 **产品型号 + SPU** 下按 PPID 额度行贪心吃满，因此一个申请可能有多个分片。预览接口和真实分配
共用同一份贪心代码，不会出现"预览说够、提交说不够"的两套语义。

余量不足、幂等键命中、并发抢占（预览够但 CAS 扣动不了）分别给出 `A0422 BIZ_REJECTED`，其中并发抢占的文案
明确让用户刷新余量重提，**不会**把申请量悄悄砍小。

投递失败不会让请求失败：broker 不可用时提交仍然返回单号，分片留在 `APPLY|PENDING`，日志记一条
`分片消息投递失败，等待兜底扫描补发`。这是 P8a 活体验收的第 [D] 节验过的行为。

---

## 3. 五阶段流水线

拓扑（`RabbitTopology`）：正常交换机 `mfi.token.normal` 直连五条阶段队列
（`mfi.apply.queue` / `mfi.name.queue` / `mfi.content.queue` / `mfi.register.queue` / `mfi.finish.queue`），
延迟交换机 `mfi.token.delay` 连"阶段 × 档位"的延迟队列（`mfi.delay.<stage>.<档位名>`，队列级 TTL +
`x-dead-letter-exchange=mfi.token.normal` + 对应阶段路由键），到期即由 broker 死信投回阶段队列。
默认 4 个档位即 20 条延迟队列；活体验收为把小时级等待压到分钟内，临时用 `-D` 覆盖成 3 档
（10s/30s/1m），此时总队列数 21 = 5 阶段 + `mfi.token.dlq` + 15 延迟。
另有 `mfi.token.dlq` 承接永久失败。默认档位 `mfi.pipeline.delay-tiers=[60000,300000,1800000,3600000]`。
**档位数组改动等于队列名改动**，滚动升级必须先停生产、清干净旧队列。

五个监听方法都只做一件事：把消息交给 `StageRunner`（`PipelineListener` 刻意薄到没有逻辑）。所有跃迁集中在
`StageRunner`，处理器只回答"这次怎么样"——`StageOutcome` 四选一：

| 结论 | 含义 | 是否消耗重试次数 |
|---|---|---|
| `ADVANCE` | 本阶段完成，前进到下一阶段（FINISH 即终态成功） | — |
| `WAIT` | 条件未就绪（苹果还在生成文件 / 租户并发额度占不到），按 `executeAt` 稍后再来 | 否 |
| `RETRY` | 本次失败，按 `executeAt` 退避重试 | 是 |
| `DEAD` | 永久失败或重试耗尽，落 DEAD 并进死信队列 | — |

`StageRunner` 的三条不可让的约束（五个阶段共享）：

1. **先 CAS 领取再干活**：`stage_status` 从 `PENDING` 条件更新到 `PROCESSING`，返回 0 就直接不调苹果。
   重复投递、消费者重启、延迟消息与兜底扫描撞车都收敛到这一个去重点。
2. **先写库再投递**：跃迁落库后再发下一跳；投递失败只记日志，由兜底扫描按 `next_execute_time` 补发。
   反过来会让下一跳消费者读到还没落定的状态。
3. **不向 AMQP 容器抛业务异常**：容器 `prefetch=1`、auto ACK 且关闭 Spring 层重试，抛出去只会原队列重投。
   兜不住的异常就地记错误，把分片留给扫描复位。

退避：第 n 次重试用升序档位的第 n 项（`backoffNanos(retryCount)`，越界取最后一项）；重试次数达到租户配置的
`PIPELINE_MAX_RETRY`（默认 3）即 `DEAD`。

各阶段做什么、幂等点在哪：

- **APPLY**：分片上已有 `apple_request_uuid` 就直接 `ADVANCE`（重投绝不重复申请）。否则
  `requestAuthEntities(ppid, allocateCount)`；4xx → `DEAD`，5xx/IO → `RETRY`。若调用成功但落库前进程崩溃，
  苹果侧会多出一批已申请未登记的实体，只能靠 PPID 的 `applied_quantity` 与苹果门户对账发现，所以日志必须
  留下 request id。
- **FILE_NAME**：`getFileNames(requestId)`。有清单则逐条登记 `t_token_file` 并前进；没有清单且非 4xx 则按
  `retry_download_after` 计算 `WAIT` 时刻。等待中的分片**在库里看得见、在队列里不占消费者**——这是对 MES
  固定 1 小时轮询风暴的正面修正。
- **FILE_CONTENT**：只处理 `download_status <> 'SUCCESS'` 的文件，逐个下载解析、`INSERT IGNORE` 落
  `t_token`，成功即单独标记该文件。中途失败时已成功的文件不回退，重投只补下缺的那一个。`accessory_uuid`
  在这里生成。
- **REGISTER**：取本分片未注册的行，按 `min(PIPELINE_REGISTER_BATCH_SIZE, 苹果批量上限)` 分批提交
  `usedAuthEntities`（数组每项 `{token 编号: 配件 UUID}`）。**逐批推进**：每批成功即单独落库，重试只补没
  成功的那几批。
- **FINISH**：两道收口校验（本分片有 token、且没有未注册的行），再补制造序列号。到货量与分配量不一致只
  `WARN` 留痕，不判失败——苹果侧 PPID 余额导致的短量是真实存在的情况。

**SN 口径**（`SnAllocator`）：一次 `INCRBY` 占住连续号段，按 id 顺序发号。Redis key 含 `tenantId` 与 SN 前缀
（企业段 + PPID 后 6 位 + 年字母 + 月日），即**计数器粒度严格等于 SN 前缀粒度**，不按 SPU 拆 key。取号与落号
之间崩溃，那段号作废——宁可断号也不重号，因为重号会让厂商拿同一串 SN 刷两件产品而无法追溯。
由此推论：**SN 只在租户内唯一**，两个租户若使用同一 PPID 字符串，号段各自从 1 开始、可能编出相同 SN，
这是已知且可接受的行为（真实场景中 PPID 由苹果签发给唯一厂商）。

**申请单状态聚合**由 `ApplicationAggregator` 在 `StageRunner` 把 FINISH 分片落 SUCCESS 后统一做，写成一条
带条件的 UPDATE：状态没变化不写库，终态不会被迟到的重复聚合改回 `RUNNING`。MES 那种"各监听器顺手改状态"
造成过读侧假象，这里收敛成一处。

**租户并发闸门**（`TenantConcurrencyGate`）：按 `PIPELINE_CONCURRENT_PER_TENANT`（默认 2）限制同一租户同时
在飞的苹果调用；拿不到额度就 `WAIT`（`gate-retry-millis`，不消耗重试），不阻塞线程。这是尽力而为的限流，
正确性由分片 CAS 与 PPID 条件扣减保证。多租户共用出口 IP，苹果对同源 IP 有隐式限流，闸门把"一家占满全局"
变成"一家只占自己的额度"。

**死信**（`DeadLetterListener`）：只把"哪个分片为什么死了"打成一条可被告警抓到的 ERROR，**不自动重放**——
进 DEAD 意味着需要人来判断。人工重放是唯一出口外按钮：`POST /api/applications/{no}/retry`，只把终态失败
（`DEAD`）的分片按当前阶段放回流水线，替代 MES 的 11 个人工补偿端点。

**兜底扫描**（`PipelineScanner`，默认 30 秒一轮，traceId 前缀 `recovery`）两条互补复位路径：

- `PENDING` 且 `next_execute_time` 早于 `现在 - scan-grace-seconds(90)` → 补发。覆盖投递失败、broker 丢消息。
- `PROCESSING` 且超过 `processing-timeout-minutes(30)` → 放回 `PENDING`，下一轮被上一条捞走。覆盖消费者崩溃。

分两步是为了让"复位"与"补发"的判定条件各自保持简单。前提是"消息可以丢，状态不能死"。

---

## 4. 状态机

**分片**：`stage` ∈ APPLY → FILE_NAME → FILE_CONTENT → REGISTER → FINISH（`Stage.next()`，FINISH 无后继）。
`stage_status` ∈ PENDING → PROCESSING →（SUCCESS | 回到 PENDING 等待/重试 | DEAD）。
DEAD 之后只能由人工重放改回 PENDING。

```
PENDING --CAS 领取--> PROCESSING --ADVANCE--> 下一阶段 PENDING
                              --WAIT/RETRY--> 本阶段 PENDING(+next_execute_time[,+retry_count])
                              --DEAD / 重试耗尽--> DEAD --人工重放--> 本阶段 PENDING
```

**申请单**：`PENDING → RUNNING → SUCCESS | PARTIAL_FAILED | FAILED`，三者均为终态（`isTerminal()`）。
聚合按分片当前阶段汇总：全部到 FINISH 成功为 SUCCESS，存在 DEAD 且其余已终态为 PARTIAL_FAILED 或 FAILED。

**token**：`ISSUED`（已下载未注册）→ `REGISTERED`（已注册回苹果）。`DESTROYED` 由人工销毁产生且只作用于
`ISSUED` 行（`destroyable()`）；`SCRAPPED` 是本系统自己打的报废标记，苹果侧到底绑没绑配件无从确认，因此
**不可销毁**、也不进注册与发号候选。所有查询与批量 SQL 都显式排掉 `DESTROYED`：人工销毁只改 `status`，
`register_status` 仍停在 `PENDING`，不排掉就会把销毁过的实体再送去注册或发号。

**导出任务**：`PENDING → PROCESSING → SUCCESS | FAILED`。停在 PROCESSING 超过
`stale-timeout-minutes` 判定执行进程已不在，置失败。

---

## 5. 结果读侧、导出与销毁

- 详情 `GET /api/applications/{no}` 带五阶段进度与时间线（每阶段 `reached/succeeded`），文件明细
  `GET /api/applications/{no}/files`，token 分页 `GET /api/tokens/page`（可按单号、PPID、SN、状态筛选）。
  读侧全部走 `ApplicationQueryService`，不碰额度、不发消息。
- 导出是**异步任务**：`POST /api/tokens/export` 只在请求线程里校验与落库，读数据写盘在有界线程池
  （`worker-size` / `queue-capacity`）里跑；轮询 `GET /api/export-tasks/{taskNo}` 到 SUCCESS 后
  `GET /api/export-tasks/{taskNo}/download` 取件。产物含 token 原文，因此**只认发起人**：别人连任务状态都读不到。
  文件名按 RFC 5987 的 `filename*=UTF-8''` 给出。
- 销毁 `POST /api/tokens/destroy` 是本服务唯一"不可逆"的入口，四条形制：仅 owner，且必须
  把申请单号**原样再输一遍**（二次确认不是复选框）；只对 `ISSUED` 行发起；逐批推进、每批成功即单独落库，
  一旦某批被拒就停手，剩下的留机会；单次有数量上限，超限留给下一次点击。
  销毁只作废苹果侧的实体，**不回补 PPID 池的 `applied_quantity`**：苹果收到销毁请求后是否把这份 quantity
  还给该 PPID 尚无实测依据，提前在本地加回余量会让本地比苹果宽松，反而更容易超发。`PpIdMapper.restore`
  已按条件更新写好，等 P8b 真证书联调确认口径后再接上。

---

## 6. 多租户隔离边界

隔离不是应用层约定，而是 SQL 层强制：

- `TenantLineInnerInterceptor` 给每条业务 SQL 追加 `tenant_id = ?`，无法在代码里关闭；白名单只有
  `t_user`、`t_tenant` 两张真全局表。
- 唯一键一律含租户：`t_ppid` 是 `(tenant_id, ppid, spu)`，所以同一 PPID 字符串在两个租户各是一行、各自计量。
- 苹果凭证每租户一套，`MfiClientFactory.forTenant(tenantId)` 按需构造并缓存 SSLContext；不存在全局证书。
- 业务端点没有"指定租户"的入参：凭证、配置、成员列表一律解析到调用方当前租户，隔壁的资源在 SQL 层就
  不可寻址，因此"越权读"表现为"资源不存在"，不区分无权与不存在。
- 跨租户写（改 PPID、停用、删除、重放、销毁、转让 owner、改配置）在租户上下文里定位不到行，条件更新影响 0 行。
- 会话只带 `currentTenantId`（一个选择偏好），`owner_flag` 每次请求由 `TenantBindingInterceptor` 回库读，
  不进会话缓存——否则被移除成员/被转让 owner 的会话会带着旧权限继续操作。

P8a 的渗透自测（`scripts/verify-p8.py` 第 [E] 节）把 §8 的每个端点都按"拿隔壁租户的资源 ID 去打"跑了一遍。

---

## 7. 日志与产物红线

任何阶段都不得把下列内容写进日志（含控制台、异常栈、SQL 实参）：`secret_key`、`accessory_uuid`、
`auth_entity_id` 的值、PPID 明文、token 原文、PEM 私钥/证书文本。允许的是计数与指纹。

- `com.galaplat.mfi.dao.mapper` 必须停在 INFO：DEBUG 会把 SQL 与占位符实参整条打进去，里面就是 PPID 与
  凭证密文。本地要查 SQL 只在 gitignored 的 `application-local.yml` 里临时开。
- 分片/阶段日志只带 `tenantId`、`applicationNo`、`shardId`、`stage`、request id、计数与号段区间。
  SN 取号成功只打 `count` 与 `range`，不打前缀（前段含 PPID 后 6 位）。
- 导出目录（`mfi.export.dir`）必须本服务独占、不进版本库，目录里只应有 `<任务号>.<后缀>`。
- 前端不得把 PEM 文本绑进可见控件——可访问性树会暴露输入值（`CredentialView.vue` 因此把上传与粘贴分成两条
  路径，粘贴内容只在提交时进内存、提交后立即清空）。

`scripts/verify-p8.py` 第 [F] 节对本轮日志增量做了这些 grep。

---

## 8. 故障模式与应对（已活体验证）

| 故障 | 系统行为 | 恢复方式 |
|---|---|---|
| broker 宕机时提交申请 | 单照样落下，分片停 `APPLY\|PENDING`，ERROR 记"等待兜底扫描补发" | 恢复后 ≤30 秒由扫描补发，`Declarables` 自动重声明拓扑，无需人工 |
| 消费者执行中崩溃 | 分片卡在 `PROCESSING` | 超过 `processing-timeout-minutes` 复位 PENDING，再被补发 |
| 苹果 5xx / 网络异常 | `RETRY`，按档位退避，耗尽进 DEAD | 人工 `POST /api/applications/{no}/retry` |
| 苹果 4xx（证书权限、PPID 无效） | 直接 `DEAD`，不白占出口 IP 配额 | 修凭证/PPID 后人工重放 |
| 苹果文件未就绪 | `WAIT` + `next_execute_time`，不消耗重试 | 到点自动继续 |
| 某租户证书过期/缺失 | 提交入口即拒（`B0404`/`B0500`），**不影响其他租户消费** | 该租户换证；其他租户链路不动 |
| APPLY 成功但落库前崩溃 | 苹果多出一批已申请未登记实体 | 靠 PPID `applied_quantity` 与苹果门户对账 |

---

## 9. 尚未验证的部分

以下必须在拿到厂商真实证书后才能确认（P8b），本地一律用自签 CA 的 mTLS 桩替代：

1. 苹果对真实证书链与 CN 的校验规则；
2. `technology` 各取值的实际接受度（含 `DEFAULT` 哨兵）；
3. 真实限流阈值，以及 `TenantConcurrencyGate` 的默认额度是否合适；
4. 生产 `authEntities` 响应字段与文档是否一致（尤其 `retry_download_after` 的时区与精度）；
5. PPID 在苹果侧的配额与有效期。

桩能证明的是：协议形状、状态机、租户隔离、重试与自愈。它证明不了苹果的任何业务规则。
