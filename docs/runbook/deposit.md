# 入账运行手册

> 给运维看的：一笔链上转账为什么没进商户余额、看到哪种状态该做什么。
> 代码里的对应物：`deposit` 表、`DepositPoster`、`DepositPostingScheduler` 的日志。

## 一、一笔入账的几种结局

`SELECT id, block_number, log_index, status, amount, hold_reason FROM deposit WHERE status <> 'CREDITED' ORDER BY id;`

| `status` | 意思 | 钱在哪 | 做什么 |
|---|---|---|---|
| `CREDITED` | 已记账，`transfer_id` 指向账本那笔 | 商户余额里 | 无 |
| `IGNORED_ZERO` | 零值转账（EIP-20 允许） | 没有钱 | 无 |
| `REJECTED_DUST` | 低于该代币的 `chain_token.min_deposit` | 在收款地址上，不入账也不退 | 无；改阈值见第五节，此前被拒的不会自动补记 |
| `HELD_NODE_DISAGREE` | 两个节点对该块**哈希**意见不同，或两边 finalized 的差距**超出** `chainpay.deposit.finality-tolerance-blocks`（默认 64 块） | 在收款地址上 | 先看 `hold_reason` 是哪一种：哈希不同 → 用区块浏览器核对该块，节点坏了就换；写着「去看节点」→ 某个节点卡住，或库里的视图跑到两个节点前面了，**先修节点，不要核准**（核准会跳过全部核对，见第三节） |
| `HELD_BALANCE_MISMATCH` | 地址在该块的 `balanceOf` ≠ 事件累计（转入减转出）；或者问不到——合约 revert 当场判，带别的错误码只把这一笔延后、别的入账照记，连续 5 轮仍是它才判（`hold_reason` 里写「重试 N 轮仍是它」） | 在收款地址上，但可能比事件说的少 | 看 `hold_reason` 里的两个数：少了多半是转账扣费的代币，多了多半是静默铸币；节点没有那一块的状态（非归档）也会到这里。**按实际到账核准，不按事件** |
| `HELD_OVERFLOW` | 金额装不进 `NUMERIC(38,18)` | 在收款地址上 | 这笔在本账本里记不了，联系商户处理 |
| `HELD_ERROR` | 入账时撞上结构性异常（账本约束、币种不符……），`hold_reason` 有异常原文。库连不上、池满、等锁超时是瞬时的，这一轮退避、下一轮再来，不记成它 | 在收款地址上 | 先修根因，再按第四节核准 |
| `APPROVED` | 人已核准，等任务下一轮记 | 在收款地址上 | 等 30 秒；一直不变说明任务没跑或又出错（看日志） |
| `POSTING` | **不该出现在提交后的行里** | — | 出现即是 bug：事务边界被绕过了 |

任务的日志：HELD 每笔一条 WARN；HELD_ERROR 一条 ERROR；节点或数据库瞬时失败一条 WARN「这一轮提前结束」，已记的不受影响，下一轮自动重试。

> **两个节点的 finalized 只差一点点时，这笔不进这张表。** 差距在容忍值以内（含库里的视图比两个节点超前），任务这一轮**延后**：不占坑、不留行，日志一条 INFO「入账延后：块 … 还没在两个节点上 finalized」，节点追上来下一轮自己记上。
> 所以「钱到了链上、`deposit` 表里却没有行」不一定是丢了。先看入账任务日志里「延后 N」：偶尔冒一下正常（两家节点更新 finalized 差几秒），一直涨就是有节点卡住（差距超出容忍会升级成 `HELD_NODE_DISAGREE`）。

## 二、入账任务整个停下了

看 `work` 健康组里的 `deposit` 一项（三个组的读法见 `ops.md`「健康检查」）：

| 状态 | 意思 | 做什么 |
|---|---|---|
| UNKNOWN | 这个进程没配 xpub 或主节点，不入账 | 没事，除非它本该入账 |
| DEGRADED | 连续 5 轮没跑完（节点一直答不上来、库一直抖） | 看日志里的 `reason`：节点的问题等它恢复，会自己回 UP；一直不回就去看节点 |
| DOWN | 上一轮 HALTED：节点拒绝了我们的凭证（HTTP 401 / 403） | **重试永远没用**。换 `CHAINPAY_CHAIN_RPC_URL` 里的 key，重启进程；在这之前入账一笔都不会走，任务也不再敲节点（闸门在内存里，重启才开） |

凭证被拒单独一档，是因为它和网络抖动在传输层长得一样（都拿不到回答），但抖动下一轮就好，被撤销的 key 永远不会；混在一起，入账就会悄悄停着、没人叫。

## 三、为什么 HELD 不会自动变成 CREDITED

这些状态的共同点是「代码不知道该信谁」。自动重试只会得到同样的答案；改动钱的决定必须由人做，并留下是谁、为什么。所以 HELD 的行永远不进入账队列，直到人把它改成 APPROVED。

**核准 = 跳过全部核对。** 核准后的行下一轮走「不再核对」的路径：不比块哈希、不看两个节点的 finalized、不核对 `balanceOf`，只把金额算出来就记账。
所以核准只用于「已经用别的办法确认这笔钱确实到账、且不会被重组翻掉」。节点自己有问题时应该修节点，不要用核准绕过去。

## 四、核准一笔 HELD

1. 用区块浏览器核对：那一笔在链上确实存在、金额是多少、那一块的哈希是不是库里记的那个。
2. 只改 `deposit` 表的状态，**把谁、为什么写进 `hold_reason`**：

```sql
UPDATE deposit
SET status = 'APPROVED',
    hold_reason = hold_reason || ' | 人工核准：<姓名> <日期> <理由>'
WHERE id = <id> AND status LIKE 'HELD_%';
```

3. 等入账任务下一轮（30 秒）：它用同一套占坑与幂等键把这笔记进账本，成功后状态变 CREDITED、挂上 `transfer_id`；记不上会改回 HELD_ERROR 并写明原因。

**不要**手工 UPDATE 成 CREDITED，**不要**手工往 `transfer` / `entry` / `account` 写任何行：账本对系统身份也只追加，人绕过入账任务写账本，三个判官和对账都会失明。

## 五、改最小入账额

```sql
UPDATE chain_token SET min_deposit = 1 WHERE address = '0x…';   -- 账本单位；0 = 不限
```

下一轮起生效，此前记成 REJECTED_DUST 的不补记。

## 六、账本判官

```sql
SELECT * FROM ledger_judge();   -- 必须 0 行
```

**只能以 `chainpay_system` 身份跑**（BYPASSRLS）；用别的身份它直接拒绝并说明原因，不会给一个假的「0 行」。
三类违规各一行：`ledger_invariant`（某币种分录不平）、`balance_consistency`（物化余额与分录求和不符）、`negative_balance`（不该为负的账户为负）。
应用启动时以系统身份跑一次：`账本判官（系统身份）：0 处违规，可见分录 N 条`；有违规每条一行 ERROR，不拒绝启动——失衡要人进来查。

## 七、核对一笔入账走完了没有

商户视角用签名客户端（凭证放 `env/drill.env`，用法见 README）：

```bash
set -a; . env/drill.env; set +a
tools/api.py GET '/api/v1/deposits?token=0x779877A7B0D9E8603169DdbD7836e478b4624789&limit=5'
```

`status` 与 `level` 一起看：PENDING/SEEN → PENDING/SAFE（约 12 分钟）→ PENDING/FINAL（约 18 分钟）→ CREDITED/FINAL（FINAL 后 30 秒内）。
FINAL 了却一直 PENDING，先看审计节点是不是答不出（`chain-indexer.md` 第二节）：审计节点不在，入账任务把核对失败当瞬时、每轮重来，一笔都不记。

账本视角：

```sql
SELECT d.id, d.status, d.transfer_id, a.code, e.amount
FROM deposit d JOIN entry e ON e.transfer_id = d.transfer_id JOIN account a ON a.id = e.account_id
ORDER BY d.id;
```

一笔 CREDITED 对应两条分录：`chain:custody:<币>` 负、`user:<商户>:<币>` 正，金额相等。

## 八、等锁与崩溃演练

**等锁**：系统连接带 `lock_timeout`（5 秒）。别的事务握着账本表时，入账这一轮以 WARN「记块 N 时数据库瞬时失败：…等锁超时（lock_timeout）」提前结束、下一轮再来，不进 HELD。
反复出现就找是谁握着锁：`SELECT pid, mode, granted FROM pg_locks WHERE relation IN ('entry'::regclass, 'transfer'::regclass, 'deposit'::regclass) AND mode LIKE '%Exclusive%';`。每个定时任务各有调度线程，一个等锁别的照跑。

**崩溃演练**（只在开发库）：另开一个 psql 会话 `BEGIN; LOCK TABLE entry IN EXCLUSIVE MODE;` 握住锁。入账事务会在占坑、建镜像账户、写转账之后卡在写分录，5 秒后超时回滚、下一轮再卡；在卡住的那几秒里 `docker kill chainpay-app`，然后 `ROLLBACK;` 放锁。
期望：`deposit` 仍是 0 行、`transfer` / `entry` 计数不变；`docker start chainpay-app` 后下一轮恰好记一次。
锁别放在 `transfer` 上：`deposit` 的外键会让占坑那条 INSERT 先卡住，演练就只剩「一轮没跑完」。
