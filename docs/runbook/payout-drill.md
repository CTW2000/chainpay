# M4-⑤ 真环境演练手册（Sepolia）

> 目标：一笔真实的 LINK 从商户账本出去、到链上、被追踪到 CONFIRMED；再用四种故障验证停发、退款、并发、核准。
> 全程只用两样工具：`tools/api.py`（商户签名客户端，凭证从 `env/drill.env` 来）和 `curl` 加管理令牌。**人不手工碰账本表。**

## 一、你要准备的（做完再叫我）

| # | 事 | 怎么做 | 怎么核对 |
|---|---|---|---|
| 1 | 热钱包私钥 | 断网跑 `tools/mnemonic.sh` 生成 12 词（抄在纸上，只显示一次），再跑 `tools/hotwallet.sh` 输入这 12 词得到私钥，粘进 `env/local.env` 的 `CHAINPAY_PAYOUT_HOT_WALLET_KEY=`，清屏 | 重启后日志一行「热钱包已装配：地址 0x…」，把地址记下来（下面叫 HOT） |
| 2 | HOT 的 gas | Sepolia 水龙头给 HOT 领 ETH（每笔 LINK 转账约 0.0001–0.001 ETH，领 0.05 够演练） | 区块浏览器看 HOT 的 ETH 余额 |
| 3 | HOT 的 LINK | MetaMask Account 1 转 5 LINK 给 HOT（这就是 v1 的「人工归集」） | 浏览器看 HOT 的 LINK 余额 ≥ 5 |
| 4 | 白名单目标 | MetaMask 新建 Account 2，记下地址（下面叫 DEST）。**不要用 Account 1**：它是 acme 的收款地址，会被 2010 拒绝 | — |
| 5 | 限额 | 见第二节第 1 步（我来做） | — |

私钥进 env 之后：`tools/check-secrets.sh` 扫一遍仓库确认没落进任何被跟踪的文件。

## 二、正常路径：1 LINK 走到底

```bash
set -a; source env/local.env; source env/drill.env; set +a
LINK=0x779877A7B0D9E8603169DdbD7836e478b4624789

# 1. 限额（管理令牌，回环）：单笔 2、当日 10。没定限额一律转人工
curl -s -X PUT -H "X-CP-ADMIN-TOKEN: $CHAINPAY_ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"perTxMax":"2","dailyMax":"10"}' http://localhost:8095/admin/v1/payout-limits/$LINK

# 2. 登记白名单
python3 tools/api.py POST /api/v1/withdrawal-addresses '{"address":"<DEST>","label":"Account 2"}'

# 3. 申请 1 LINK
python3 tools/api.py POST /api/v1/withdrawals '{"token":"'$LINK'","toAddress":"<DEST>","amount":"1","idempotencyKey":"drill-1"}'

# 4. 看它走：每 15 秒看一次，直到 CONFIRMED（FINAL 约 13–18 分钟）
python3 tools/api.py GET /api/v1/withdrawals
python3 tools/api.py GET "/api/v1/deposits/balance?token=$LINK"     # available 24、frozen 1 → 结算后 frozen 0
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

**A. 估 gas 就 revert（热钱包 LINK 不够）**：申请 `amount` 大于 HOT 的 LINK 余额但不超过商户余额与限额（先把限额调大）。期望：几秒内 FAILED，`failure_reason` 含「估 gas 失败」，frozen 回到 0，`payout_tx` 没有新行，`next_nonce` 不动。

**B. 并发三笔编号连续**：连发三个申请（三个不同 idempotencyKey）。期望：一轮内签三笔，`payout_tx` 的 nonce 连续（N、N+1、N+2），三笔各自 CONFIRMED。

**C. 有人在别处用了这把钥匙**：把热钱包私钥导入 MetaMask（演练完删掉），从 HOT 直接转 0.001 ETH 给 DEST。下一轮发送任务对账：链上计数 C 比库里的 N 大 → 整把钱包 HALTED，日志 ERROR「有人在别处用了这把私钥」，之后的申请留在 QUEUED。
恢复（见 `payout.md` 第三节）：`UPDATE hot_wallet SET next_nonce = <链上计数>, status = 'ACTIVE', halt_reason = NULL WHERE address = '<HOT>'`，下一轮继续。

**D. 转人工核准**：申请 3 LINK（超单笔上限 2）。期望：状态 PENDING_APPROVAL，frozen +3；`GET /admin/v1/payouts/pending` 看到它；`POST /admin/v1/payouts/{id}/approve` 后进队列走到底；或 `POST …/reject {"reason":"…"}` 后 REJECTED、frozen 回退。

**E. 进程死在提交与广播之间**：这个窗口只有几毫秒，真机很难命中；能做的是「签了没广播」的等价形态——申请一笔后立刻 `kill -9` 应用（10 秒一轮，先看到 `签 1` 的日志再杀最好）。重启后期望：`payout_tx` 里若有 SIGNED 行，第一轮日志 `重发 1`，编号不变，链上最终只有一笔。

不做的：卡单加价。发送任务的费率取 max(市价, 地板)，配置压不到市价以下，真环境造不出「一直排不上」；加价逻辑由 `PayoutTrackerTest` 的四条测试守着。

## 四、演练结束

- 从 MetaMask 删掉导入的热钱包账户；`tools/check-secrets.sh` 再扫一遍。
- `docs/retro/M4.md`（本地）：M4-before 的 34 问哪几条是自己想到的；演练里出的意外记进 `docs/knowledge/m4-payout.md`。
