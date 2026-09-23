# 付款真环境演练（Sepolia）

> 目标：一笔真实的 LINK 从商户账本出去、到链上、被追踪到 CONFIRMED；再用几种故障验证停发、退款、并发、核准。
> 工具只有两样：`tools/api.py`（商户签名客户端，凭证从 `env/drill.env` 来）和 `tools/admin.sh`（管理接口）。**人不手工碰账本表。**

## 一、准备

| # | 事 | 怎么做 | 怎么核对 |
|---|---|---|---|
| 1 | 热钱包私钥 | 断网跑 `tools/mnemonic.sh` 生成 12 词（抄在纸上，只显示一次），再跑 `tools/hotwallet.sh` 输入这 12 词得到私钥，粘进 `env/local.env` 的 `CHAINPAY_PAYOUT_HOT_WALLET_KEY=`，清屏，`deploy/deploy.sh` | 日志一行「热钱包已装配：地址 0x…」，把地址记下来（下面叫 HOT） |
| 2 | HOT 的 gas | Sepolia 水龙头给 HOT 领 ETH（每笔 LINK 转账约 0.0001–0.001 ETH，领 0.05 够演练） | 区块浏览器看 HOT 的 ETH 余额 |
| 3 | HOT 的 LINK | MetaMask Account 1 转 5 LINK 给 HOT。Account 1 是收款地址，这是归集（内部挪动），不用登记注资；从外部地址转进来的才要登记（`audit.md` 第五节） | 浏览器看 HOT 的 LINK 余额 ≥ 5 |
| 4 | 白名单目标 | MetaMask 新建 Account 2，记下地址（下面叫 DEST）。**不要用 Account 1**：它是 acme 的收款地址，会被 2010 拒绝 | — |

私钥进 env 之后，`tools/check-secrets.sh` 扫一遍仓库，确认没落进任何被跟踪的文件。

## 二、正常路径：1 LINK 走到底

```bash
set -a; . env/drill.env; set +a
eval "$(tools/admin.sh login ops)"
LINK=0x779877A7B0D9E8603169DdbD7836e478b4624789

# 1. 限额：单笔 2、当日 10（没定限额一律转人工；改限额是敏感操作，先再认证）
tools/admin.sh reauth && tools/admin.sh PUT /admin/v1/payout-limits/$LINK '{"perTxMax":"2","dailyMax":"10"}'

# 2. 登记白名单
tools/api.py POST /api/v1/withdrawal-addresses '{"address":"<DEST>","label":"Account 2"}'

# 3. 申请 1 LINK
tools/api.py POST /api/v1/withdrawals '{"token":"'$LINK'","toAddress":"<DEST>","amount":"1","idempotencyKey":"drill-1"}'

# 4. 看它走：隔十几秒看一次，直到 CONFIRMED（FINAL 约 13–18 分钟）
tools/api.py GET /api/v1/withdrawals
tools/api.py GET "/api/v1/deposits/balance?token=$LINK"     # frozen 先 +1，结算后回到 0
```

期望的日志顺序：`发送：看了 1 笔，签 1、广播 1` → 追踪 `上链：块 N` → 每轮 `等 FINAL` → `FINAL：结算 1 LINK，CONFIRMED`。
浏览器里 DEST 收到 1 LINK；`GET /api/v1/withdrawals` 里的 `txHash` 能在浏览器查到。

核对表：

```sql
SELECT id, status, amount, settle_transfer_id, failure_reason FROM payout ORDER BY id;
SELECT payout_id, nonce, status, block_number, reverted FROM payout_tx ORDER BY id;
SELECT address, next_nonce, status FROM hot_wallet;
SELECT * FROM ledger_judge();     -- 以 chainpay_system 身份；必须 0 行
```

## 三、故障演练（每个做完先恢复再做下一个）

**A. 估 gas 就 revert（热钱包 LINK 不够）**：申请 `amount` 大于 HOT 的 LINK 余额、但不超过商户余额与限额（先把限额调大）。前提是商户余额大于 HOT 的 LINK 余额，否则账本先拒。
期望：几秒内 FAILED，`failure_reason` 含「估 gas 失败」，frozen 回到 0，`payout_tx` 没有新行，`next_nonce` 不动。

**B. 并发三笔编号连续**：连发三个申请（三个不同 idempotencyKey）。期望：一轮内签三笔，`payout_tx` 的 nonce 连续（N、N+1、N+2），三笔各自 CONFIRMED。

**C. 有人在别处用了这把钥匙**（真环境没做过，由 `PayoutSenderTest.chainAheadOfTheDatabaseHaltsTheWallet` 守）：把热钱包私钥导入 MetaMask（演练完删掉），从 HOT 直接转 0.001 ETH 给 DEST。
期望：下一轮对账时链上计数 C 比库里的 N 大 → 整把钱包 HALTED，日志 ERROR「有人在别处用了这把私钥」，之后的申请留在 QUEUED。恢复见 `payout.md` 第三节（`next_nonce` 改成链上计数、状态改回 ACTIVE）。

**D. 转人工核准**：申请 3 LINK（超单笔上限 2）。期望：PENDING_APPROVAL，frozen +3；`tools/admin.sh GET /admin/v1/payouts/pending` 看得到它；
再认证后 `tools/admin.sh POST /admin/v1/payouts/<id>/approve` 进队列走到底，或 `tools/admin.sh POST /admin/v1/payouts/<id>/reject '{"reason":"…"}'` 后 REJECTED、frozen 回退。

**E. 进程死在提交与广播之间**：这个窗口只有几毫秒，真机很难命中。能做的是等价形态：申请一笔后几秒内 `docker kill chainpay-app`，再 `docker start chainpay-app`。
期望：若 `payout_tx` 里留下 SIGNED 行，重启后第一轮日志 `重发 1`、编号不变；无论哪种，链上最终只有一笔。

不做的：卡单加价。发送任务的费率取 max(市价, 地板)，配置压不到市价以下，真环境造不出「一直排不上」；加价逻辑由 `PayoutTrackerTest` 守着。

## 四、演练结束

做过 C 的话，从 MetaMask 删掉导入的热钱包账户；`tools/check-secrets.sh` 再扫一遍。
