# M3 · 收款 Pay-In：背景知识与取舍

> M3，2026-09-06 至 09-08 完成。这份记收款依赖的背景知识（带出处）、定下的做法与理由、还没做的事。
> 规则见 CLAUDE.md「收款」与 §7「私钥」；每种入账状态怎么处置见 `docs/runbook/deposit.md`。带 ★ 的是本项目实测。

---

## 一、背景知识

### 1. 地址从哪来
```
私钥 k   256 位随机数，谁知道它谁就能花这个地址上的钱
  │  secp256k1 椭圆曲线乘法 K = k·G        ← 单向：由 K 算不回 k
公钥 K   曲线上的一个点，64 字节（x ‖ y）
  │  Keccak-256(K)，取最后 20 字节          ← 单向：由地址算不回 K
地址     20 字节，0x + 40 位十六进制
```
- **地址不是账户**：链上不用注册，任何 20 字节都是合法地址；打给一个没人有私钥的地址，钱就永远丢了。
- **Keccak-256 ≠ SHA3-256**：以太坊用的是标准化之前的 Keccak，和 NIST 定稿的 SHA3-256 只差填充，结果完全不同；JDK 的 `SHA3-256` 算出来的不是以太坊地址（本项目用 BouncyCastle 的 Keccak）。
- **大小写是校验和（EIP-55）**：按小写地址的 Keccak 哈希位决定每个字母的大小写，抄错一位钱包就拒收。库里存小写，对外给校验和写法。

### 2. HD 钱包：一个种子，一整棵密钥树
| 标准 | 管什么 | 一句话 |
|---|---|---|
| BIP-39 | 助记词 → 种子 | 12 / 24 个词经 PBKDF2-HMAC-SHA512（2048 轮，可加口令）得 512 位种子 |
| BIP-32 | 种子 → 密钥树 | HMAC-SHA512 得主密钥 + 链码；节点 = 密钥 + 链码 = 扩展密钥（xprv / xpub） |
| BIP-44 | 树的目录 | `m / 44' / 币种' / 账户' / 找零 / 序号`，以太坊币种号 60（SLIP-44）；MetaMask 的第 n 个账户是 `m/44'/60'/0'/0/n` |

带 `'` 的层是**硬化派生**（序号 ≥ 2^31），要父私钥才能算；不带的是**普通派生**，**只用父公钥就能算出子公钥**。
所以把账户层 `m/44'/60'/0'` 的 xpub 交给服务器，它就能派生 `0/0, 0/1 …` 无穷个收款地址，助记词与私钥从头到尾不在服务器上——交易所「充值地址由后端生成、私钥在冷端」的原理（watch-only）。
xpub 的两个代价：泄露 = 能枚举全部收款地址、看到全部资金流向（但转不走）；**xpub + 任意一个普通派生的子私钥 = 父私钥**，这是 BIP-32 普通派生的数学性质，所以绝不能把收款树里的某个子私钥单独交出去。

### 3. 谁的钱：以太坊转账没有附言
链上只有「谁 → 谁 → 多少」，归属只能靠地址：

| 模式 | 优点 | 难题 |
|---|---|---|
| **一户一址**（交易所主流） | 归属明确、地址数可控 | 同一用户重复打款，分不清对应哪一单 |
| 一单一址 | 精确到单 | 地址数随订单爆炸，每个地址上的钱都要归集 |
| 地址复用 + 金额识别 | 最省 | 两人同时转同样金额就撞了，本质是猜 |

XRP / XLM / EOS / TON 有 memo / destination tag，可以「一个地址 + memo 区分用户」——丢了 memo 就是收不到账（flow-pay `65372f8a` 踩过）。以太坊没有 memo，只能在地址上做文章。
**gas 喂养**：ERC-20 收在派生地址上，要挪走得由那个地址发交易、付 ETH 当 gas，于是每个收过款的地址都得先「喂」一点 ETH。
行业解法：先打 gas 再归集（最笨、最贵）、用支持 `permit` 的代币、用合约转发器（CREATE2 预先算出地址，收到即转）。

### 4. 什么时候算到账
确认与重组的事实见 `m2-block-indexer.md` 第三节。交易所的确认数是风险定价，而且「入账」与「可提」是两个门槛：钱先出现在余额里让用户安心，再等一会儿才允许它离开。
「按金额分级确认数」（小额少等、大额多等）是产品取舍，不是技术问题。

### 5. 复式记账里的入账
账本每笔 `transfer` 写两条 `entry`：借方 −amount、贷方 +amount，每种币 `SUM(amount) = 0`。入账时钱到商户账户，可复式记账里钱不能凭空出现，对手方是**托管镜像账户**——链上托管地址里的币在账本里的影子（和 M0 注资时资金来源账户变负是同一回事）：
```
链上：付款人 → 商户的收款地址（我们派生、我们托管）   10 LINK
账本：transfer(DEPOSIT)   借 chain:custody:LINK −10（允许为负）   贷 user:<商户>:LINK +10
```
镜像余额永远为负，**绝对值 = 所有托管地址链上应有的余额之和**——对账拿链上余额和它比（`m5-reconciliation.md`）。

### 6. 金额：原始单位 → 账本单位
链上 `value` 是无小数点的整数，`decimals` 决定小数点左移几位（LINK 18 位）。
- **零值转账**：EIP-20 说它 MUST 照发事件，所以索引器记它；账本要求金额 > 0，于是「记录、不入账」。
- **灰尘**：一笔 0.000001 LINK，归集它要花的 gas 比它本身还贵。交易所的做法是每种币设最小入账额，低于它不入账也不退（以各家页面为准）——「不能产生负手续费」就是这个意思。

### 7. 代币会说谎
Transfer 事件是合约「说」的，余额是合约「做」的：转账扣费、弹性供应、先发事件再返回 false 的代币，事件金额 ≠ 到账金额（`m2-block-indexer.md` 第二节）。白名单挡住大部分，但白名单也可能收错。

### ★ 真环境演练里实测到的
- 一笔 25 LINK：索引到即 SEEN（1 个确认），约 12 分钟 SAFE（57 个），约 18 分钟 FINAL（88 个），FINAL 之后 30 秒内记账。余额接口 `pending` 25 变成 `available` 25。
- **外键会把锁传导到引用它的表**：对 `transfer` 加 EXCLUSIVE 锁（只挡写不挡读）之后，`INSERT INTO deposit` 也卡住——`deposit` 有指向 `transfer` 的外键，插入要在被引用表上取 RowShareLock，EXCLUSIVE 把它也挡了。
- **Spring 7 的 SQLSTATE 翻译器不认识 55 这一类**：等锁超时（55P03）被翻成 `UncategorizedSQLException`，入账任务会把它当结构性错误记 HELD_ERROR——加了 `lock_timeout` 反而让每次等锁变成一张工单。所以系统池把 55P03 翻成瞬时错误。
- 审计节点连续 503 二十多分钟：索引器第 30 次失败时降级、恢复后自动回到正常；入账把核对失败当瞬时、留到下一轮——审计节点不在，钱就在路上等。
- 书签回退 820 块重放：范围内日志行数与 md5 不变，入账与账本一行没多。

---

## 二、取舍

1. **服务器零私钥**：只配账户层 xpub（`CHAINPAY_DEPOSIT_XPUB`），普通派生收款地址；动这些地址上的钱要私钥，那不在服务器上（出金走热钱包，见 `m4-payout.md`）。
   已知答案测试用 Hardhat 公开默认助记词的前三个地址，它证明派生算法对，证明不了配进服务器的 xpub 是你的——所以启动日志打出 xpub 指纹与 `0/0` 地址，让人和自己的钱包对照。
2. **原语用库，派生自己写**：曲线运算与 Keccak 摘要用 BouncyCastle，不手写；BIP-32 / 39、xpub 解析、EIP-55 自己写，拿规范原文里的向量验收（CLAUDE.md §7）。
3. **一户一币一址**：归属明确；同一商户同一代币重复申请拿回同一个地址。地址只由服务端派生（请求体只有 token，不收地址也不收序号），序号来自数据库序列（`MAX + 1` 是 check-then-act）。
   收款地址是租户数据，进 RLS；商户接口没有「按 id 查一条」，「不存在」与「不是你的」无从区分。商户的账本账户 `user:<商户 code>:<SYMBOL>` 在分配地址时按需创建。
4. **钱绑 FINAL**：多等十几分钟，换掉整类「钱已加上又要撤」的问题，重组回滚永远只碰链表。SEEN / SAFE 对商户可见（列表里的 `level`、余额里的 `pending`），不进 `available`。
5. **动钱之前再问两个节点**：入账是动钱的边界，索引器无论怎么错都要过这道门。块哈希对不上 = HELD_NODE_DISAGREE；块号还没在两个节点上 finalized、差距在 `finality-tolerance-blocks`（64）以内 = 这一轮延后、不占坑——
   两家节点更新 finalized 的时刻差几秒是常态，不该变成工单；超出才 HELD。代价：审计节点不在，钱就在路上等。
6. **信合约做的，不信合约说的**：记账前向两个节点问该地址在那一块的 `balanceOf`，必须**等于**库里主分支日志到那一块为止的转入减转出（原始单位），对不上 HELD_BALANCE_MISMATCH。问不到时：
   合约 revert 是节点的最终回答，当场 HELD；错误码不认识（后端落后、非归档节点、配额在错误码上分不开）只把这一笔延后，连续 5 轮才 HELD——
   「不知道」不能当成「对上了」，也不该第一时间占住人工队列，一笔答不上来更不该拖住排在后面的所有入账。
7. **先占坑再动钱，同一个事务**：`INSERT deposit … ON CONFLICT DO NOTHING` 占到了才记账。反过来先记账再占坑，别的实例已判 HELD 的那一笔也会被记上。
   幂等键用链上坐标 `deposit:<block_hash>:<log_index>`，不用 `tx_hash`：重组后同一笔交易会换块重现。书签回退重放不双记，靠三层都由数据库守的唯一约束：日志（块哈希，日志序号）、`deposit.transfer_log_id`、账本幂等键。
   系统身份记的账 `submitter_merchant_id` 为 NULL，靠 `NULLS NOT DISTINCT` 照样幂等；`occurred_at` 取区块时间，不是写库时间。
8. **例外记下来，不卡队列**：零值 IGNORED_ZERO；低于代币 `min_deposit` 的 REJECTED_DUST（按代币配在 `chain_token` 上，不写死）；装不下账本的 HELD_OVERFLOW；落库撞上结构性异常的 HELD_ERROR（瞬时失败不进这里，下一轮再来）。
   HELD 永不自动变 CREDITED，人复核后改 APPROVED，任务下一轮用同一套占坑与幂等键记上——人不手工碰账本表。
9. **铸币（from = 0x0）按普通入账**；不发事件的铸币只能由对账的 ADDRESS_BALANCE 发现。没登记的代币在结构上进不了事件表（白名单外键），入账不用再判。
10. **入账任务独立于索引器**：索引器停了，已 FINAL 的钱照记；入账停了，索引照走。
11. **商户接口照币安 / OKX 的形状**（币安 `GET /sapi/v1/capital/deposit/address`、`/deposit/hisrec`；OKX `GET /api/v5/asset/deposit-address`、`/asset/deposit-history`）：
    `POST /api/v1/deposit-addresses`、`GET /api/v1/deposits`、`GET /api/v1/deposits/balance`（`available` / `pending` / `frozen`）；金额一律字符串，HELD 只露状态不露原因。

---

## 三、不做的

- **归集**（收款地址 → 热钱包）：没做，人工转。自动做之前先解决 gas 喂养；地址模型已留门（派生序号、地址状态、每个地址链上应有多少都查得到）。对账等式里它是内部挪动，不改变托管总量。
- **商户回调（webhook）**：没做，商户轮询查询接口。它是外部副作用、回滚不了；绑 FINAL 之后风险小了很多，但「至少一次投递 + 商户侧幂等」是另一整套，另立项。
- **按金额分级确认数**：一刀切 FINAL；视图里算得出 `confirmations`，要分级不用改表。
- **停用收款地址**：表里有 DISABLED，但没有接口。入账候选与对账都只看 ACTIVE 的地址：停用之后打进来的钱不入账、也不进托管等式，停用一个还有余额的地址还会让托管总量报「链上少」。做停用之前先定这些钱怎么办。
- 收款地址分配的其它已知风险（DISABLED 的地址照样被返回、账户编码用 symbol 等）列在 `m6-process-split.md`「不做的」。

---

## 参考
- 复式记账、镜像账户、余额不能为负：`tigerbeetle/concepts/debit-credit.md`、`tigerbeetle/recipes/balance-invariant-transfers.md`；冲正：`tigerbeetle/recipes/correcting-transfers.md`
- 地址归属与校验和（类比 IBAN）：`falsehoods/falsehoods-about-IBANs.md`
- 密钥与 xpub 的保管：`owasp-cheatsheets/Key_Management.md`、`Secrets_Management.md`、`Cryptographic_Storage.md`
- 客户端能传哪些字段：`owasp-cheatsheets/Mass_Assignment.md`；不可区分响应：`Insecure_Direct_Object_Reference_Prevention.md`
- 流程绕过（先记账后确认之类）：`owasp-wstg-business-logic/06-Testing_for_the_Circumvention_of_Work_Flows.md`
- （以上相对 `~/Documents/CodeProject/flow-pay-backend/docs/ai/knowledge/`）
- 标准原文：BIP-32 <https://github.com/bitcoin/bips/blob/master/bip-0032.mediawiki>、BIP-39 <https://github.com/bitcoin/bips/blob/master/bip-0039.mediawiki>、BIP-44 <https://github.com/bitcoin/bips/blob/master/bip-0044.mediawiki>、EIP-55 <https://eips.ethereum.org/EIPS/eip-55>、SLIP-44 <https://github.com/satoshilabs/slips/blob/master/slip-0044.md>
