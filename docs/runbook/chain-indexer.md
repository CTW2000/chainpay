# 索引器运行手册

> 给运维看的：索引器停了、降级了、对账有争议了，从哪里看、看到哪句该做什么。
> 代码里的对应物：`indexer_state` 表（V16）、`GET /admin/v1/indexer`、`ChainIndexerScheduler` 的日志。

## 一、状态从哪里看

**状态表** `indexer_state`，一枚书签一行：

| 列 | 含义 |
|---|---|
| `status` | `RUNNING` 正常；`DEGRADED` 还在跑但该有人来看；`HALTED` 停下等人，**重启也不恢复** |
| `reason` | 为什么（截到 2000 字） |
| `since` | 从什么时候起处于这个状态（状态不变时不动） |

**只读接口** `GET /admin/v1/indexer`，和其它管理接口同一道门（本机 + 管理员令牌）：

```bash
curl -s -H "X-CP-ADMIN-TOKEN: $CHAINPAY_ADMIN_TOKEN" http://127.0.0.1:8095/admin/v1/indexer
```

| 字段 | 含义 |
|---|---|
| `status` | **这个进程**的视角：`NOT_CONFIGURED`（没配 `CHAINPAY_CHAIN_RPC_URL`）或表里的状态 |
| `persistedStatus` / `reason` / `since` | **状态表**的视角：上一个进程停下的原因，重启后还在 |
| `cursorBlock`、`latestBlock`、`safeBlock`、`finalizedBlock`、`lagBlocks` | 书签、三个头、落后多少块 |
| `lastTickOutcome`、`lastTickAt`、`consecutiveFailures` | 最近一轮的结局与时间、连续瞬时失败次数 |
| `auditMode` | 「双节点：审计节点 主机名」或「单节点（未配置审计节点）」 |
| `disputedBlocks` | 对账里两个节点意见不同、等人看的块数 |

**日志**：停机时一条 ERROR，之后每轮直接返回不再出声（状态在表里）；降级后每轮一条 ERROR；瞬时失败是 WARN，带「连续第 N 次」。

## 二、看到这句该做什么

| `reason` / 日志里的话 | 它是什么 | 做什么 |
|---|---|---|
| finalized 倒退 / finalized 区块 N 换了哈希 | 主节点对不可逆的部分改口：节点坏了，或链上出了灾难 | 用区块浏览器核对 N 的哈希与 `chain_head`；换主节点；确认一致后改回 RUNNING、重启 |
| 两个节点对 finalized 块 N 意见不同 | 至少一个节点坏了，代码不知道信谁 | 用第三方（区块浏览器）查 N 的哈希，判断谁错，换掉错的那个；改回 RUNNING、重启 |
| 节点拒绝了我们的凭证（HTTP 401 / 403） | key 失效或被撤销，重试永远没用 | 到提供商控制台换 key，更新 `CHAINPAY_CHAIN_RPC_URL`（或审计的那个）；改回 RUNNING、重启 |
| 代币未登记 / 代币已停用 / decimals 不一致 | 白名单、配置、链三者不一致 | 核对 `chain_token` 与 `chainpay.chain.token-address`；决定是改表还是改配置；改回 RUNNING、重启 |
| 没有书签，也没配 chainpay.chain.start-block | 第一次启动没告诉它从哪开始 | 配 `CHAINPAY_CHAIN_START_BLOCK`（当前链头减几百，十进制，不能是未来的块）；改回 RUNNING、重启 |
| 单块 N 的日志也取不到 | 提供商在这个高度答不出（归档范围、套餐限制）。**链头两块以内**的单块失败不算——那是负载均衡的各后端头不一致（"block range extends beyond current head block"），2026-09-09 起按瞬时处理、自动下一轮再来，不会走到这一行 | 换提供商；改回 RUNNING、重启 |
| 节点返回了错误的区块 / …缺少字段… / 不是数组 | 节点返回的形状不对 | 换节点，或向提供商报障；改回 RUNNING、重启 |
| 日志块 N 的哈希与区块头不符 / 答非所问 | 节点前后不一致或塞入了不属于这批的日志 | 一次是瞬时（自动重试）；反复出现就换节点 |
| 数据库连不上、连接池耗尽、事务开不出来 | 库抖一下、主从切换、Hikari 池满。2026-09-10 起按瞬时处理（`TransientDbFailure`）：这一轮 RETRY_LATER，连续 N 次才 DEGRADED，**不会 HALTED** | 看库与连接池；恢复后自动回 RUNNING |
| 连续 N 次瞬时失败（DEGRADED） | 网络、限流、提供商故障 | 看 `lastTick` 的 detail 与 `consecutiveFailures`；不用改状态，恢复后自动回 RUNNING |
| 审计节点连续 N 次答不出（DEGRADED） | 审计节点挂了或落后 | 检查 `CHAINPAY_CHAIN_AUDIT_RPC_URL`；恢复后自动回 RUNNING |
| `disputedBlocks > 0` | 两个节点对某块的日志意见不同（有无或内容）；或某个节点的回执里有一条解不了的日志（2026-09-09 起记 disputed 而不是停机，原因在 WARN 日志里） | `SELECT * FROM chain_reconcile WHERE disputed > 0`，用区块浏览器裁决；解不了的日志看该轮 WARN「回执里有一条解不了的日志」并核对节点 |
| 追块很慢（每轮只前进约 `batch-blocks` × 10 以内）且日志里没有 ERROR | 提供商限制了 `eth_getLogs` 的块范围（Alchemy 免费层 10 块，HTTP 400 / code -32600「Under the Free tier plan…」）。窗口会自己收敛到上限、每批只问一次（2026-09-09 起），但每轮仍最多 10 批 | 不是故障。稳态不受影响（Sepolia 每分钟 5 块）；要快就换套餐、换提供商；落后很远又等不起时按第五节前跳书签；「追赶不受每轮 10 批限制」是 M6 的题 |
| `lastTickAt` 长时间不动，进程还活着 | 2026-09-09 起索引器与入账任务各有线程、系统连接等锁 5 秒就放弃，这一现象不该再出现；出现即是新 bug | 先看线程转储里 scheduling-* 线程在做什么，再 `SELECT pid, wait_event_type, query FROM pg_stat_activity WHERE usename = 'chainpay_system'` |

## 三、恢复

```sql
UPDATE indexer_state SET status = 'RUNNING', reason = NULL WHERE name = 'sepolia:link:transfer';
```

然后重启进程。**重启本身不算恢复**：进程启动先读状态表，读到 HALTED 就不碰节点。
这条纪律存在的理由：结构性原因（finalized 倒退、两个节点意见不同）重启只会立刻再撞一次，
在自动拉起的环境里就是静默的重启死循环；先让人看一眼，再让它跑。

## 四、启动时该看的一行

启动日志里有一行「索引器主节点 主机名；双节点：审计节点 主机名」或「…单节点（未配置 CHAINPAY_CHAIN_AUDIT_RPC_URL）」。
审计节点和主节点是同一台主机时应用会拒绝启动：同一家的两把 key 不算独立，等于自比对。

## 五、动书签（前跳或回退）

书签是「这个块及之前都处理过了」的承诺，下一批用 `block(from).parentHash` 核对书签里的哈希。所以动书签只有一种安全做法：

1. 先记下旧值：`SELECT last_block_number, last_block_hash FROM indexer_cursor WHERE name = 'sepolia:link:transfer';`
2. 目标块的哈希**向两个节点各要一次，必须一致**（`eth_getBlockByNumber`）。一个节点给的哈希只是一个节点的说法。
3. 停机，或确认在两轮之间（`lastTickAt` 刚更新）；然后

```sql
UPDATE indexer_cursor
SET last_block_number = <N>, last_block_hash = '<两个节点一致的哈希>', updated_at = now()
WHERE name = 'sepolia:link:transfer' AND last_block_number = <旧值>;   -- 带旧值做守卫，UPDATE 0 就说明书签又动了
```

回退是重放：日志行按（块哈希，日志序号）唯一，已有的行不会再插一次，`deposit` 与账本不受影响（M3-⑤ 实测）。
前跳是放弃一段历史：跳过的块里如果有到收款地址的转账，它们永远不会入账，而且之后该地址的 `balanceOf` 会和事件累计对不上、进 HELD——只在确认没有已分配地址有活动时做（开发库），生产不做。

