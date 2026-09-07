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
| 单块 N 的日志也取不到 | 提供商在这个高度答不出（归档范围、套餐限制） | 换提供商；改回 RUNNING、重启 |
| 节点返回了错误的区块 / …缺少字段… / 不是数组 | 节点返回的形状不对 | 换节点，或向提供商报障；改回 RUNNING、重启 |
| 日志块 N 的哈希与区块头不符 / 答非所问 | 节点前后不一致或塞入了不属于这批的日志 | 一次是瞬时（自动重试）；反复出现就换节点 |
| 连续 N 次瞬时失败（DEGRADED） | 网络、限流、提供商故障 | 看 `lastTick` 的 detail 与 `consecutiveFailures`；不用改状态，恢复后自动回 RUNNING |
| 审计节点连续 N 次答不出（DEGRADED） | 审计节点挂了或落后 | 检查 `CHAINPAY_CHAIN_AUDIT_RPC_URL`；恢复后自动回 RUNNING |
| `disputedBlocks > 0` | 两个节点对某块的日志意见不同（有无或内容） | `SELECT * FROM chain_reconcile WHERE disputed > 0`，用区块浏览器裁决；M3 之前不会有人据此入账 |

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
