# M5 · 对账：链上和库内的任何不一致都要浮出来 —— 2026-09-14 完成

## 〇、M5 要做成什么

一个定时跑的任务，站在一个 finalized 的块上，把「链上的事实」和「库里的记录」逐项比对，任何不一致都记进一张表、打一条 ERROR；它自己没跑、跑失败，也要能被看出来。

| 验收 | 哪条检查抓 |
|---|---|
| 手工注入一笔链上不存在的入账 → 报出来 | DEPOSIT_LEDGER（证据不在主分支）、ADDRESS_BALANCE（事件累计 ≠ 链上余额） |
| 手工删掉一笔链上存在的入账 → 报出来 | DEPOSIT_LEDGER（FINAL 的收款日志没有入账行） |
| 手工改小一笔金额 → 报出来 | DEPOSIT_LEDGER（入账行、账本转账、日志三者金额不等） |
| 对账任务失败本身要告警 | `audit_run` 每次都落一行（OK / DIFF / FAILED）；管理接口按「上次成功距今」判 stale |

## 一、五条检查（都站在同一个 finalized 块 F 上）

F 取 `chain_head.finalized` 与索引书签中**较小**的那个（首轮真实对账教的：索引器追赶时书签落后 finalized，站在 finalized 上会把「还没索到」报成差异），对账开始时再向两个节点各取一次 F 的块头，哈希不等于库里的就**不给结论**（FAILED，原因写清）——在分叉上对账，对出来的差异是假的。

| 检查 | 比什么 | 差异的名字 |
|---|---|---|
| ADDRESS_BALANCE 地址余额 | 每个收款地址与每个热钱包：链上 `balanceOf(F)`（两节点都问，不一致 = DISPUTED）vs 库里 CANONICAL 日志的转入减转出（块 ≤ F） | 链上多 = MISSING_IN_LEDGER（漏了转入、或没有事件的铸币）；链上少 = MISSING_ON_CHAIN（留着的日志链上不认、或漏了转出） |
| DEPOSIT_LEDGER 入账台账 | 每笔 CREDITED 入账：它的日志必须 CANONICAL 且块 ≤ F；账本转账的金额必须等于入账行的金额；反过来，每条块 ≤ F − lag、收款方是 ACTIVE 收款地址、代币 ACTIVE 的 CANONICAL 日志必须有入账行（任何状态） | MISSING_ON_CHAIN / AMOUNT_MISMATCH / MISSING_IN_LEDGER |
| PAYOUT_LEDGER 出账台账 | 每笔 CONFIRMED 提现：恰好一次 MINED 尝试、块 ≤ F、回执未 revert，链上有一条从热钱包到收款地址、金额等于 raw_value 的 CANONICAL 日志，哈希相同；反过来，每条从热钱包发出的 CANONICAL 日志必须对应一次 MINED 尝试 | MISSING_ON_CHAIN / AMOUNT_MISMATCH / MISSING_IN_LEDGER（后者就是「有人在别处用了这把钥匙」） |
| CUSTODY_TOTAL 托管总量 | 每种币：链上托管 C = Σ 收款地址余额 + Σ 热钱包余额（F）；账本镜像 M = `chain:custody:<SYM>`；在路上的钱 U = 已 FINAL 未入账的转入、S = 已上链未结算的提现；等式 C = \|M\| + U − S + E，E 是「未解释的差额」 | E ≠ 0 → 按符号 MISSING_IN_LEDGER（链上多：外部注资没登记、漏账）/ MISSING_ON_CHAIN（链上少：假账、钥匙被别处用） |
| LEDGER_JUDGE 判官 | `ledger_judge()`（系统身份） | JUDGE |

## 二、取舍（我的建议，你定）

| # | 题 | 选项 | 建议 |
|---|---|---|---|
| 1 | 站在哪一块 | A finalized（库里的）；B latest | **A**。latest 会被重组翻掉，对账结论跟着翻；finalized 落后 13 分钟是可以接受的代价，「在路上的钱」单独算 |
| 2 | 两节点不一致 | A 记 DISPUTED 继续；B 整轮 FAILED | **余额不一致记 DISPUTED 继续；F 的块头不一致整轮 FAILED**。前者是一处数据，后者是脚下的地基 |
| 3 | 外部注资 | A 报差异；B 加「注资登记」表 | **A**，v1 只报。运营往热钱包充币是少数几次的手工动作，看到差额能解释就行；等它成为常规动作再做登记 |
| 4 | 跑多勤 | A 每天；B 每小时 | **每小时**，可配。数据量小时勤一点，发现得早；量大了再放宽 |
| 5 | 结论存哪 | A 表 + 管理接口；B 只打日志 | **A**。「上次成功是什么时候」必须存下来，否则「没跑」和「跑了没事」分不开 |
| 6 | 发现差异后 | A 只报不改；B 提供「解释」入口 | **A**。对账是判官不是修理工；解释与修正走人工路径（runbook） |
| 7 | 扫描范围 | A 每次全量；B 增量 | **A**，v1 全量。地址与记录都还小；全量没有「上次扫到哪」这种状态可以出错 |

## 三、不做的

- 归集（收款地址 → 热钱包）：M6 之前不做，等式里它是内部挪动，不改变 C 与 M。
- 铸币不发事件的代币：ADDRESS_BALANCE 能抓到「链上多」，但分不清是漏日志还是无事件铸币，只报不判。
- 告警的载体：v1 是 ERROR 日志 + 管理接口的 stale 标志；接通知渠道是 M6。
