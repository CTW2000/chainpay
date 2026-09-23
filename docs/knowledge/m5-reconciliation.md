# M5 · 对账：链上和库内的任何不一致都要浮出来

> M5，2026-09-13 至 09-14 完成。一个定时任务站在一个 finalized 的块上，把链上事实和库里的记录逐项比对，任何不一致都落表、打 ERROR；它自己没跑、跑失败也要看得出来。
> 规则见 CLAUDE.md「对账」；每种差异怎么处置见 `docs/runbook/audit.md`。

差异分三种：MISSING_IN_LEDGER（链上有、库内无：漏账）、MISSING_ON_CHAIN（库内有、链上无：假账，最危险）、AMOUNT_MISMATCH（两边都有、金额不符：算错）；
另有 DISPUTED（两个节点意见不同，不下结论）与 JUDGE（判官 `ledger_judge()` 的一行）。

## 一、脚下的块 F

F = min(`chain_head.finalized`, 索引书签)：**证据和事实必须是同一个时刻的**。索引器追赶时书签落后 finalized，站在 finalized 上会把「还没索到」报成差异（首轮真实对账就撞上了）。
开始时再向两个节点各取一次 F 的块头，哈希不等于库里的就整轮 FAILED、不给结论——在分叉上对出来的差异是假的。

## 二、五条检查（都站在 F 上）

| 检查 | 比什么 | 差异 |
|---|---|---|
| ADDRESS_BALANCE | 每个 ACTIVE 收款地址与热钱包：链上 `balanceOf(F)`（两个节点都问，不一致 = DISPUTED）vs 库里主分支日志的转入减转出（块 ≤ F） | 链上多 = MISSING_IN_LEDGER；链上少 = MISSING_ON_CHAIN |
| DEPOSIT_LEDGER | 每笔 CREDITED 入账：日志在主分支且块 ≤ F，账本转账金额等于入账行金额；反过来，块 ≤ F − 10（给入账任务几轮时间）、打到 ACTIVE 收款地址、代币 ACTIVE 的主分支日志都要有入账行（任何状态） | MISSING_ON_CHAIN / AMOUNT_MISMATCH / MISSING_IN_LEDGER |
| PAYOUT_LEDGER | 每笔 CONFIRMED 提现：恰好一次 MINED 尝试、未 revert、结算金额相等，链上有同哈希、从热钱包到收款地址、金额相等的主分支日志；反过来，热钱包发出的每条主分支日志都要对应一次尝试 | 同上；最后一种 MISSING_IN_LEDGER 就是「有人在别处用了这把钥匙」 |
| CUSTODY_TOTAL | 每种币：链上托管 C（收款地址与热钱包在 F 的余额之和）= M + U − S + R + E | E ≠ 0：链上多 = MISSING_IN_LEDGER，链上少 = MISSING_ON_CHAIN |
| LEDGER_JUDGE | `ledger_judge()`（系统身份） | JUDGE |

托管等式（原始单位）：M = 截至 F 的镜像（CREDITED 入账减 CONFIRMED 结算，与链上余额同一时刻）；U = 已 FINAL 未入账的转入；S = 已上链未结算的提现；R = 已登记的注资（`hot_wallet_funding`，M6-② 加的）；E = 解释不了的差额。
有地址余额 DISPUTED 的币这一轮不算总量；登记过的注资，日志后来不在主分支了也报。

## 三、取舍

1. **站在 finalized（再与书签取小），不站 latest**：latest 会被重组翻掉，结论跟着翻；落后十几分钟可以接受，在路上的钱单独算（U、S）。
2. **两节点不一致**：余额不一致记 DISPUTED、继续别的；F 的块头不一致整轮 FAILED。前者是一处数据，后者是脚下的地基。
3. **外部注资**：M5 只报差异；但不登记的话每一轮都报「链上多」、判官的健康项一直 DOWN，所以 M6-② 加了注资登记，等式多一项 R（`m6-launch.md` 取舍 7）。
4. **每小时一轮，可配**：数据量小时勤一点，发现得早；量大了再放宽。
5. **结论存表**：每一轮都落一行 `audit_run`（OK / DIFF / FAILED），差异逐条落 `audit_finding`，两张表只追加。「上次成功是什么时候」必须存下来，否则「没跑」和「跑了没事」分不开：
   上次结论距今超过两个周期 = stale；stale、DIFF、FAILED 经健康检查 work 组的 `audit` 项进告警。
6. **只报不改**：对账是判官不是修理工；解释与修正走人工路径（runbook）。
7. **每次全量**：地址与记录都还小；全量没有「上次扫到哪」这种状态可以出错。

## 四、不做的

- **归集**（收款地址 → 热钱包）：没做，人工转。做的时候它在等式里是内部挪动，不改变 C 与 M。
- **不发事件的铸币**：ADDRESS_BALANCE 能抓到「链上多」，但分不清是漏日志还是无事件铸币，只报不判。
- 热钱包的 ETH（gas）、停用的收款地址不在对账范围里（`m4-payout.md`、`m3-payin.md` 的「不做的」）。
