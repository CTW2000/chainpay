# 对账（M5）操作手册：看到差异该做什么

> 对账是判官不是修理工：它只报，不改。**人也不手工改账本表**；修正走各模块自己的人工路径。

## 一、先看哪里

```bash
curl -s -H "X-CP-ADMIN-TOKEN: $CHAINPAY_ADMIN_TOKEN" http://localhost:8095/admin/v1/audit          # 上次结论、stale、差异清单
curl -s -X POST -H "X-CP-ADMIN-TOKEN: $CHAINPAY_ADMIN_TOKEN" http://localhost:8095/admin/v1/audit/run   # 立刻跑一轮
```

```sql
SELECT id, status, finalized_number, findings, started_at, finished_at, detail FROM audit_run ORDER BY id DESC LIMIT 5;
SELECT check_name, kind, subject, expected, actual, detail FROM audit_finding WHERE run_id = (SELECT max(id) FROM audit_run);
```

日志：每轮一行；有差异每条一行 ERROR；没跑完一行 ERROR。

## 二、三种结论

| `status` | 含义 | 该做什么 |
|---|---|---|
| OK | 站在块 F（finalized 与索引书签中较小的那个）上，五条检查零差异 | 不用做。`detail` 写着站在哪；站在书签上说明索引器还在追赶，等它追平再跑一轮 |
| DIFF | 有差异，逐条在 `audit_finding` | 按第三节逐条处理 |
| FAILED | 没跑完：节点不可达、两节点对 F 的哈希意见不同、库瞬时失败、异常 | 看 `detail`。分叉那种等下一轮（finalized 不会真的分叉，多半是审计节点落后）；反复 FAILED 看节点 |
| `stale: true` | 上次 OK / DIFF 距今超过两个周期，或从没跑过 | 调度器没跑或每轮都 FAILED：看进程、看日志。**沉默不等于正常** |

## 三、每种差异

| 检查 · 差异 | 意思 | 常见原因 | 该做什么 |
|---|---|---|---|
| ADDRESS_BALANCE · MISSING_IN_LEDGER | 链上余额比库里的日志累计多 | 索引器漏了转入日志；没有事件的铸币；代币转账扣费方向反了 | 对着区块浏览器看该地址在 F 之前的转账，找库里没有的那条；漏块走索引器 runbook 的书签回退 |
| ADDRESS_BALANCE · MISSING_ON_CHAIN | 链上余额比库里的日志累计少 | 留着的日志链上不认（重组没回滚）；漏了转出 | 同上，反向找库里多出来的那条；确认是重组后走书签回退重放 |
| ADDRESS_BALANCE · DISPUTED | 两个节点对余额意见不同 | 审计节点落后或撒谎 | 换一轮再看；持续不同就换审计节点 |
| DEPOSIT_LEDGER · MISSING_ON_CHAIN | 记了账的入账，链上证据不在主分支 | 重组没回滚就入账了（不该发生：入账绑 FINAL）；有人手工改了库 | **最危险的一种**。先查 `chain_reorg` 与该日志的 status 变更时间；确认是假账后，走人工冲正（`WITHDRAWAL_REVERSE` 形状的反向转账由人发起，不改原分录） |
| DEPOSIT_LEDGER · AMOUNT_MISMATCH | 入账行与账本转账金额不同 | 手工改库 | 账本转账是原始证据（有分录、不可改），入账行以它为准；查谁改的 |
| DEPOSIT_LEDGER · MISSING_IN_LEDGER | FINAL 够久的收款日志没有入账行 | 入账任务停了；有人删了入账行 | 看入账任务日志；任务在跑就等下一轮；入账行被删要查 |
| PAYOUT_LEDGER · MISSING_ON_CHAIN | CONFIRMED 的提现，链上找不到它的转账 | 假账；索引器漏了那一块 | 用交易哈希查浏览器：链上有就是索引器漏了，链上没有就是假账 |
| PAYOUT_LEDGER · AMOUNT_MISMATCH | 链上转账的金额或收款人与提现不同 | 签名前后被改过 | 立刻停发（把热钱包 HALTED），查 `payout_tx.raw_tx` 与链上交易 |
| PAYOUT_LEDGER · MISSING_IN_LEDGER | 热钱包发出了库里没有的转账 | 有人在别处用了这把钥匙；运营手工转账 | 当泄露处理（见 `payout.md` 第二节第一行），除非能对上一次运营的手工操作 |
| CUSTODY_TOTAL · MISSING_IN_LEDGER | 链上托管比账本能解释的多 | 运营往热钱包充币还没登记；漏账 | 能对上一次充币就**登记它**（下面「注资登记」），下一轮对账就平；对不上按漏账查 |
| CUSTODY_TOTAL · MISSING_ON_CHAIN | 链上托管比账本能解释的少 | 假账；钥匙在别处被用 | 先看 PAYOUT_LEDGER 有没有同时报 MISSING_IN_LEDGER |
| LEDGER_JUDGE | 账本自己的不变量破了 | 手工改库 | M0 起就不该发生；查改动来源 |

## 四、对账自己错了怎么办

对账在库里没有链头、两节点对 F 意见不同、节点不可达时一律 FAILED，不猜。它错报的方式只有一种：把「在路上的钱」算错方向（已 FINAL 未入账、已上链未结算）。看到 CUSTODY_TOTAL 单独报而别的检查都不报时，先核那两个数。

## 注资登记（M6-②）

运营往热钱包充币后，对账会报 CUSTODY_TOTAL / MISSING_IN_LEDGER，直到有人登记它。登记只指认「是哪一笔」，金额从索引器记下的日志读：

```bash
set -a; source env/local.env; set +a
tools/admin.sh POST /admin/v1/hot-wallet/fundings '{"txHash":"0x…","note":"谁、为什么充"}'
tools/admin.sh GET  /admin/v1/hot-wallet/fundings
```

| 回应 | 意思 | 做什么 |
|---|---|---|
| 200，`rawValue` / `blockNumber` | 登记成功（同一笔再登记还是这一行） | 跑一轮对账核实 |
| 404 + 2011 | 库里没有这笔转给热钱包的日志 | 哈希对不对；索引器索到那个块了没（`GET /admin/v1/indexer`）；收款方是不是热钱包 |
| 400「指定 logIndex」 | 一笔交易里有多条转给热钱包的日志 | 按回应里列的 logIndex 各登记各的 |
| 400 + 2010 | 发起方是平台自己的地址（收款地址或热钱包） | 那是归集，不是注资，不登记；等式里它是内部挪动 |
| 409 + 4005 | 还没 finalized | 等十几分钟再来 |

登记表只追加、没有改和删：登记错了不能撤，只能在对账里看到它被报成「登记的注资链上不认」（日志被翻掉）或把差额留在等式里；谁登记的要靠 M6-⑤ 的审计表。
