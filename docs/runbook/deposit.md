# 入账运行手册

> 给运维看的：一笔链上转账为什么没进商户余额、看到哪种状态该做什么。
> 代码里的对应物：`deposit` 表（V19/V20）、`DepositPoster`、`DepositPostingScheduler` 的日志。

## 一、一笔入账的几种结局

`SELECT id, block_number, log_index, status, amount, hold_reason FROM deposit WHERE status <> 'CREDITED' ORDER BY id;`

| `status` | 意思 | 钱在哪 | 做什么 |
|---|---|---|---|
| `CREDITED` | 已记账，`transfer_id` 指向账本那笔 | 商户余额里 | 无 |
| `IGNORED_ZERO` | 零值转账（EIP-20 允许） | 没有钱 | 无 |
| `REJECTED_DUST` | 低于该代币的 `chain_token.min_deposit` | 在收款地址上，不入账也不退 | 无；要改阈值改表，此前被拒的不会自动补记（M3-before 第 16 问） |
| `HELD_NODE_DISAGREE` | 记账前两个节点对该块哈希或 finalized 高度意见不同 | 在收款地址上 | 用区块浏览器核对该块哈希；节点坏了就换；确认无误后按第三节核准 |
| `HELD_BALANCE_MISMATCH` | 地址在该块的 `balanceOf` ≠ 事件累计（转入减转出），或问不到 | 在收款地址上，但可能比事件说的少 | 看 `hold_reason` 里的两个数：少了多半是转账扣费的代币，多了多半是静默铸币；节点没有那一块的状态（非归档）也会到这里。**按实际到账核准，不按事件** |
| `HELD_OVERFLOW` | 金额装不进 `NUMERIC(38,18)` | 在收款地址上 | 这笔在本账本里记不了，联系商户处理 |
| `HELD_ERROR` | 入账时撞上结构性异常（账本约束、币种不符……），`hold_reason` 有异常原文 | 在收款地址上 | 先修根因，再按第三节核准 |
| `APPROVED` | 人已核准，等任务下一轮记 | 在收款地址上 | 等 30 秒；一直不变说明任务没跑或又出错（看日志） |
| `POSTING` | **不该出现在提交后的行里** | — | 出现即是 bug：事务边界被绕过了 |

任务的日志：HELD 每笔一条 WARN；HELD_ERROR 一条 ERROR；节点或数据库瞬时失败一条 WARN「这一轮提前结束」，已记的不受影响，下一轮自动重试。

## 二、为什么 HELD 不会自动变成 CREDITED

这些状态的共同点是「代码不知道该信谁」。自动重试只会得到同样的答案；改动钱的决定必须由人做，并留下是谁、为什么。
所以 HELD 的行永远不进入账队列，直到人把它改成 APPROVED。

## 三、核准一笔 HELD

1. 用区块浏览器核对：那一笔在链上确实存在、金额是多少、那一块的哈希是不是库里记的那个。
2. 只改 `deposit` 表的状态，**把谁、为什么写进 `hold_reason`**：

```sql
UPDATE deposit
SET status = 'APPROVED',
    hold_reason = hold_reason || ' | 人工核准：<姓名> <日期> <理由>'
WHERE id = <id> AND status LIKE 'HELD_%';
```

3. 等入账任务下一轮（30 秒）：它会用同一套占坑与幂等键把这笔记进账本，成功后状态变 CREDITED、挂上 `transfer_id`；记不上会改回 HELD_ERROR 并写明原因。

**不要做的事**：不要手工 UPDATE 成 CREDITED，不要手工往 `transfer` / `entry` / `account` 表写任何行。账本对系统身份也只追加，
人绕过入账任务写账本，三个判官和对账都会失明。

## 四、改最小入账额

```sql
UPDATE chain_token SET min_deposit = 1 WHERE address = '0x…';   -- 账本单位；0 = 不限
```

下一轮起生效，此前记成 REJECTED_DUST 的不补记。
