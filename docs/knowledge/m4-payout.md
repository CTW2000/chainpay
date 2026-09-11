# M4 · 付款 Pay-Out：从热钱包签名、广播、追踪、结算

> 规划稿（2026-09-09）。规矩同 M3：先从零讲前置知识，再分步，取舍列出来由用户定；动手前的「会怎么坏」问题在 `docs/retro/M4-before.md`（本地）。

## 〇、M4 要做成什么

商户调一个接口说「把 1 LINK 打到这个地址」，平台用自己的热钱包签一笔 ERC-20 转账、广播、盯着它上链、到 FINAL 后在账本里结算；中途任何一步失败，商户的钱回到可用余额，且**同一笔业务永远只广播一次**。

LEARNING-PATH 的验收标准，逐条对应到步骤：

| 验收 | 落在哪一步 |
|---|---|
| 并发 10 笔提现，nonce 不冲突、全部上链 | ② nonce 分配 |
| 进程被 kill 后重启，不重复广播已发出的交易 | ② 先落库再广播 |
| 手工制造 nonce 跳号，系统能检测并恢复 | ②③ 对账与补发 |
| gas 不足导致卡单，能加速或取消 | ③ 卡单策略 |
| 私钥不在代码、镜像、日志里，有检查脚本 | ① 私钥保管 |
| 提现到未白名单地址必须拒绝 | ④ 风控 |

### M2 / M3 已经替 M4 铺好的路 ★

- `chain_head` 与三态（SEEN / SAFE / FINAL）：出账的结算同样绑 FINAL，重组永远只碰链表不碰账本。
- 两节点核对（块哈希、finalized）：一笔出账「上链了」的判定复用 M3-② 那道门。
- `SystemLedger`：出账的所有账本动作走系统连接，`lock_timeout` 与 55P03 翻译已经在（补丁）。
- 瞬时 / 结构性失败的分类、HELD → APPROVED 的人工路径：出账的「等人看」直接照抄。
- `TokenAmounts`：账本单位 ↔ 原始单位，出账要反着换（账本 → 原始）。
- `Abi.encodeCall` / `encodeUint`：`transfer(address,uint256)` 的 calldata 就是这两个函数。
- `Keccak256`、`Secp256k1`、`ExtendedPrivateKey`：签名要的数学一半已经在 `chain/wallet` 里，`WalletBoundaryTest` 守着谁能碰私钥。

### 代码里已经替 M4 下过的判决 ★（约束，不是选项）

1. **服务端的私钥只能是热钱包这一把**，且它**不能是收款树（xpub 那一支）的普通派生子密钥**——xpub 加任意一个子私钥就能反推父私钥，一把热钱包私钥泄露会带走全部收款地址。
2. 系统级动作走 `SystemLedger.inTransaction`，控制器不得持有它（`ControllerBoundaryTest`）。
3. `TenantScope.asSystem` 在 M4 删掉（CLAUDE.md 作用域表里的承诺）。
4. 网络 IO 在事务外面；账本事务毫秒级；等锁 5 秒放弃。
5. 金额一律 `BigDecimal`/字符串，对外不用 number。

---

## 一、前置知识（从零讲）

### 1. 账户与 nonce：为什么以太坊要给每笔交易编号

以太坊的外部账户（EOA）只有两样状态：余额和 **nonce**。nonce 是「这个地址已经成功发出的交易数」，从 0 开始。每笔交易都要带一个 nonce，网络只接受**恰好等于当前值**的那一笔：发了 0..4 之后，下一笔必须是 5；带 5 的两笔只有一笔能进；带 7 的会被节点先放着等 6。

这条规则是为了防重放：没有 nonce，别人截到你的一笔签名交易就能反复广播。代价就是 LEARNING-PATH 里那句话——**nonce 是并发问题最纯粹的形态**，而且真相在链上、不在你的库里。你的库说 next = 12，链说 10，那就是你以为发出去的两笔其实没到；库说 12，链说 13，那就是有人用这把私钥在别处发了一笔。

节点回答「现在 nonce 是多少」有两种口径：`eth_getTransactionCount(addr, "latest")` 只算已上链的；`"pending"` 把节点内存池里排队的也算上。两者不同的窗口期就是并发事故的温床：pending 只是**这个节点**看到的队列，另一个节点不一定看到。

### 2. 一笔交易长什么样（EIP-1559，类型 2）

```
chainId               11155111（Sepolia）—— 防止把 Sepolia 的签名拿去主网重放（EIP-155）
nonce                 上面讲的编号
maxPriorityFeePerGas  给出块者的小费上限
maxFeePerGas          总费率上限 = 基础费 + 小费 的上限
gasLimit              最多烧多少 gas；ERC-20 transfer 约 5 到 7 万
to                    代币合约地址（不是收款人！收款人在 data 里）
value                 0 —— 转的是代币，不是 ETH
data                  a9059cbb ‖ 收款人 ‖ 金额        ← transfer(address,uint256) 的 calldata
accessList            []
yParity, r, s         签名
```

**新手最常错的一处**：ERC-20 转账的 `to` 是合约，收款人藏在 `data` 里。M2 解码 Transfer 事件时看的 `to` 是事件参数；这里是交易字段，两个「to」不是一回事。

### 3. gas：三个数各管什么

- **基础费（baseFee）**由协议按上一块的拥挤程度算出来，每块变一次，**烧掉**，谁都拿不到。
- **小费（priority fee）**给出块者，决定你排队的位置。
- 你签的是两个**上限**：`maxFeePerGas` 和 `maxPriorityFeePerGas`。实际付的每单位 gas = min(maxFee, baseFee + maxPriority)，多设的部分退回。所以 `maxFee` 设成「两倍基础费加小费」是常见做法：给基础费涨价留余量，又不会真的多付。
- `gasLimit` 是烧多少 gas 的上限，用不完退回；设低了交易执行到一半 out of gas，**gas 照扣、状态回滚**。所以先 `eth_estimateGas` 再加两成余量。

估算会失败：热钱包没有足够代币时，`eth_estimateGas` 直接回 revert。这是好事——**在花 gas 之前**就知道这笔发不出去。

### 4. 序列化与签名：RLP、Keccak、secp256k1

- **RLP** 是以太坊的序列化格式：一个字节串或一个列表，前缀几个字节说明长度。整条交易先按字段顺序排成列表，RLP 编码，前面拼上类型字节 `0x02`。
- **签名哈希** = Keccak-256(`0x02` ‖ RLP(前九个字段))。注意不含签名本身。
- **ECDSA over secp256k1**：私钥 k 和随机数（RFC 6979 让它由消息与私钥**确定**地算出，不用真随机，避免了 PlayStation 3 那种重复 k 泄露私钥的事故）算出 (r, s)；以太坊还要求 **low-s**（s ≤ n/2，否则同一个签名有两个合法写法，交易哈希就不唯一）；再附一个 **yParity**（0 或 1），让验证方能从 (r, s) **反推出公钥**、进而算出发送地址——以太坊交易里没有「from」字段，from 是从签名恢复出来的。
- **交易哈希** = Keccak-256(整条带签名的 RLP)。它在广播前就能算出来——这一点是 ②「先落库再广播」的基础。

`Keccak256` 与 `Secp256k1` 已经在 M3 写好；要新写的是 RLP、签名与恢复、类型 2 的字段编排。已知答案必须逐字来自原始文本（M3 的教训），候选来源：以太坊执行层规范仓库里的交易测试向量、go-ethereum 的 `transaction_signing_test`。

### 5. 交易的一生

```
签好 ─→ 广播（eth_sendRawTransaction）─→ 在节点内存池里 pending
                                         ├─→ 被打包进块：MINED，有回执（status 1 成功 / 0 回滚）
                                         │      └─→ 块被重组掉：回到 pending，或被同 nonce 的另一笔取代
                                         ├─→ 一直没人打包（费率太低）：卡单
                                         └─→ 被节点丢弃（内存池满、太久）：dropped，链上什么都没有
```

三个不直观的事实：
- **回执 status 0 也是上链了**：nonce 用掉了、gas 扣了、代币没动。对出账来说这是「失败但已终结」，钱要解冻。
- **同一个 nonce 只能有一笔上链**。加速（同 nonce 更高费率）和取消（同 nonce 转 0 给自己）都是「用一笔新的挤掉旧的」，节点要求新费率至少高 10%。
- **广播是按内容幂等的**：同一条签名后的原文再发一次，节点回「already known」，不会发出第二笔。这让「重启后重发」变得安全，前提是你**留着原文**。

### 6. 热钱包与冷钱包：私钥住在哪

收款地址的私钥不在服务器上（M3），钱到了那里服务器**只能看、不能动**。要往外付，就必须有一把服务器能用的私钥——**热钱包**。它天然是整个系统里最危险的东西：Ronin 桥的 6.25 亿美元就是私钥被拿走。

原则（OWASP Key_Management / Secrets_Management）：私钥只从环境注入，不进代码、镜像层、日志、聊天；热钱包里只放**近期要付的量**，大头在冷端，运营定期补（这叫「归集与调拨」，本项目 v1 由人工做）；一把私钥泄露的影响面要有边界——所以热钱包**不能**从收款树普通派生。

### 7. 复式记账视角的出账：冻结、结算、解冻

M3 的入账是「链上先发生，账本后承认」。出账反过来：**账本先扣，链上后发生**，中间有一段几分钟到几小时的不确定期，钱既不能算商户可用，又还没真的离开平台。复式记账用一个中间账户表达这段时间：

```
提现申请   借 user:acme:LINK（可用 −1）      贷 user:acme:LINK:frozen（冻结 +1）      WITHDRAWAL_FREEZE
链上 FINAL 借 user:acme:LINK:frozen（冻结 −1） 贷 chain:custody:LINK（托管镜像）        WITHDRAWAL
失败       借 user:acme:LINK:frozen（冻结 −1） 贷 user:acme:LINK（可用 +1）             WITHDRAWAL_REVERSE
```

三笔都走 `ledger.transfer`，各有幂等键（`withdrawal:<申请幂等键>:freeze`、`withdrawal:<提现 id>:settle` / `:reverse`），于是「同一笔业务只结算一次」由 M0 的唯一约束守；「结算过的不能再解冻」由冻结账户不许为负守。gas 是平台用 ETH 付的成本，v1 不进 LINK 账本，记在链上尝试表里给 M5 对账。

### 8. 出账的风控（学 OKX 的提币流程，取 v1 能做的）

白名单地址（先登记，再提现）、单笔与当日限额、超限转人工核准、拒绝提到平台自己的收款地址（那是内部转账，不是提现）。冷却期与二次验证依赖用户会话与通知渠道，本项目是 API key 体系，v1 不做，记为 M6 之后的题。

---

## 二、M4 分步规划

### M4-⓪ 地基（不碰链、不碰私钥）—— 2026-09-09 完成

> 落地：V21（拆门 + 四张表 + 权限）；`chain/payout/domain` 两张显式转换表；`chain/payout/service/PayoutLedger` 三笔账本流；
> `TenantScope.asSystem` 删除，M0 测试脚手架改走 `SystemLedger`。23 条测试先红后绿（状态机 5、账本流 6、表与权限 12）；
> 三面墙：把 `is_system_scope()` 装回策略 → 拆门那条红；去掉 `(hot_wallet, nonce) WHERE MINED` 部分唯一索引 → 同 nonce 两笔 MINED 那条红；
> 冻结账户允许为负 → 「结算过的不能再解冻」那条红。红灯里有一条一开始就绿：「非法边一律拒绝」在骨架全返回 false 时空洞地为真，绿灯后才有意义。

- V21：`hot_wallet`（地址、`next_nonce`、状态；一行一把热钱包）、`payout`（业务：商户、幂等键、代币、收款地址、金额、状态、冻结/结算/解冻三笔的 transfer id、失败原因；RLS 同账户表）、`payout_tx`（链上尝试：payout、nonce、原文、哈希、费率、状态、上链的块；一笔业务可有多次尝试，同 nonce 只有一次能上链）、`payout_address`（白名单）。系统角色的 GRANT 同样没有 DELETE。
- 状态机写成代码里的显式转换表：REQUESTED → (PENDING_APPROVAL →) QUEUED → SIGNED → BROADCAST → MINED → CONFIRMED；任一步 → FAILED / REJECTED；只允许表里有的边。
- 账本：`WITHDRAWAL_FREEZE / WITHDRAWAL / WITHDRAWAL_REVERSE` 三个 code（`WITHDRAWAL` 是 M0 就预留的那个）；冻结账户按需创建（同 M3 的 `ensureAccount`）。
- 删 `TenantScope.asSystem`：`SystemScopedLedger` 改走 `SystemLedger`，作用域表只剩两行。
- 测试：状态机拒绝非法边；三笔账本流各自幂等、`ledger_invariant` 恒 0；RLS 三个方向。

### M4-① 签名（零网络）—— 2026-09-09 完成

> 落地：`chain/wallet` 新增 `Rlp`、`Eip1559Transaction`、`Ecdsa`——先自写并过了全部向量，再按用户改判换成 web3j 6.0.0 `crypto` 的薄包装（同一套测试对库验收，一次全绿；库的解码器宽松，规范性由「拆回再编回必须逐字节相同」守）、
> `HotWalletSigner`（唯一持钥者）、`HotWalletDerivation` + `HotWalletTool` + `tools/hotwallet.sh`（硬化账户 1' 离线派生）；`chain/payout/config/PayoutConfig`（设了 `CHAINPAY_PAYOUT_HOT_WALLET_KEY` 才装配，日志只打地址）；
> `tools/check-secrets.sh` + `check-secrets.allow`。已知答案逐字取自 ethereum/tests（RLP 28 例、类型 2 向量、私钥→地址）与 EIP-155 正文算例，见 `src/test/resources/vectors/README.md`。
> 90 条新测试先红后绿；四面墙：随机 k → EIP-155 的 (r, s) 与确定性各红；去掉 low-s → 24 条消息那条红（EIP-155 向量仍绿：它的 s 碰巧是小的）；
> 接受前导零 → 官方反例那条红；扫描脚本关掉私钥规则 → 埋进去的私钥没被抓到，红。

- `chain/wallet`：RLP 编码；EIP-1559 类型 2 的签名哈希与原文；ECDSA（RFC 6979、low-s、yParity）与地址恢复；`HotWalletSigner`（进程启动时从环境变量装入私钥，只暴露 `address()` 与 `sign(tx)`，`toString` 不含密钥）。
- `WalletBoundaryTest` 扩到新类：私钥类型只许出现在 `chain/wallet`。
- 已知答案：规范或 geth 的测试向量，curl 取原文逐字核对；自洽测试：签名后恢复地址 = 热钱包地址。
- 离线工具 `tools/hotwallet.sh`：从**另一句**助记词（或同一句的 hardened 账户 `m/44'/60'/1'/0/0`）算出热钱包私钥与地址，只打印一次，不落盘；README 写清「为什么不能用收款树的账户」。
- 私钥检查脚本：扫源码、配置、镜像层、日志目录不出现私钥形态的 64 位十六进制（验收标准那条）。

### M4-② nonce 分配与发送 —— 2026-09-09 完成

- 新 RPC：`eth_getTransactionCount`、`eth_estimateGas`、`eth_maxPriorityFeePerGas`（基础费从 `eth_getBlockByNumber("latest")` 拿，没用 `eth_feeHistory`）、`eth_sendRawTransaction`、`eth_getTransactionByHash`（回执留给 ③）；`FakeChain` 学会内存池：按内容识别 already known、编号低于已上链笔数 = nonce too low、同编号已有一笔 = replacement underpriced（按费率决定打包与丢弃留给 ③）。
- 发送任务 `PayoutSender.sendOnce()` 每轮三段：**对账**（链上计数事务外问；事务里锁热钱包行、第一次见到从链上计数起步；核 N = C + U，C > N 或 N > C + U 都停整把钱包）→ **重发**（SIGNED 的原样重发，already known 算成功）→ **排队的**（事务外估 gas 与费率；revert = 判失败并解冻，编号没分；事务里锁行、改 SIGNED、签名、写原文、编号 +1、提交；提交之后才广播）。网络永远不在事务里。
- 广播的回答三类：成功 / already known → BROADCAST；传输失败 → 下一轮重发同一份原文；节点明确拒绝（insufficient funds、nonce too low 且不认识我们的哈希）→ 整把钱包 HALTED，人处理后恢复即重发。规划里「跳号补发或以取消交易填坑」改成停发叫人：② 还没有回执，填坑的判断留给 ③。
- 费率 `FeePolicy`：小费 = max(节点建议, 地板 1 gwei)；总费率 = 2 × 基础费 + 小费，超过上限（100 gwei）这一轮不发等回落；gasLimit = 估算 × 1.2，超过上限（20 万）判失败。
- 测试 17 条（发送 12 + EthRpc 合同 5）。**发现**：双实例测试证明不了热钱包行锁——两个线程按同样顺序抢同一笔提现，在提现行上就串行了；拆掉行锁与编号守卫它仍绿。拆墙挖出两处双实例竞态并修：同一笔 revert 由锁提现行让后到的看见已 FAILED；同一份原文两个实例同时重发，BROADCAST 谁先改谁算。
- 停发原因与处置：`docs/runbook/payout.md`。

### M4-③ 追踪、结算、卡单 —— 2026-09-10 完成

- 追踪任务：BROADCAST 的尝试查回执；有回执 → MINED（status 0 也是上链），等两个节点的 finalized 都过了它的块且**对该块哈希一致**（复用 M3-② 的门）→ status 1 结算 → CONFIRMED，status 0 解冻 → FAILED（改判：revert 也绑 FINAL，重组可能把它翻回去，冻着的钱多等十几分钟比退错了强）。
- 卡单：广播超过 `stuck-after`（3 分钟约 15 块）未上链 → 同 nonce 加价，两个费率都取 max(市价, 旧 × 125%)（不低于节点要求的 +110%）再发一笔；超过费率上限**这一轮不加、等回落**（规划里写「停下叫人」，改判：旧的那笔仍在池里排队，没有损失，费率是瞬时的）；旧尝试在新尝试上链后标 REPLACED。`eth_getTransactionByHash` 为空且未上链 → DROPPED，发送任务重发原文；有兄弟正被节点认着 → 是被顶掉，REPLACED。
- 重组：MINED 的块消失 → 退回 BROADCAST 继续等（结算只在 FINAL 之后，同 M3）。
- 调度线程：任务数从 2 变 4，`pool.size` 随之提高，冒烟测试的「线程数 ≥ 任务数」断言守着。

### M4-④ 风控与商户接口

- 白名单：`POST /api/v1/withdrawal-addresses`、`GET …`；提现目标必须在名单里且 ACTIVE；平台自己的收款地址一律拒绝。
- 限额：每代币单笔上限与当日上限（配置表），超限进 PENDING_APPROVAL，管理接口 `approve / reject`（复用 HELD → APPROVED 的形状）。
- `POST /api/v1/withdrawals {token, toAddress, amount, idempotencyKey}`：同商户同幂等键重发返回同一笔；余额不足 → 4xx；`GET /api/v1/withdrawals?…` 列表带链上状态与哈希；余额接口加 `frozen`。
- 错误码新增段；`ControllerBoundaryTest` 继续守 `SystemLedger` 不进控制器。

### M4-⑤ 真环境演练

- 准备：热钱包（独立助记词或 hardened 账户 1'）的私钥进 env；用 Sepolia 水龙头给它 ETH（付 gas）；把 M3 演练里 Account 1 收到的 25 LINK 转一部分到热钱包——这是「人工归集」，v1 就这么做。
- 流程：登记白名单（MetaMask 的另一个账户）→ 申请提现 1 LINK → 看 QUEUED → SIGNED → BROADCAST → MINED → CONFIRMED，余额 frozen → 0、链上收到。
- 演练：提交与广播之间 `kill -9`（重启后恰好一笔上链）；并发 10 笔（nonce 连续）；用 MetaMask 直接从热钱包发一笔制造「有人在别处用了这把钥匙」（系统停发叫人）；故意把费率上限压到基础费以下制造卡单，再放开看加速。

### M4-⑥（可选，推迟）webhook

同 M3-⑥：外部副作用，另立项。

---

## 三、取舍（由你定，附我的建议）

| # | 题 | 选项 | 我的建议 |
|---|---|---|---|
| 1 | 热钱包私钥来源 | A 环境变量里的十六进制私钥；B keystore 文件加口令；C 服务器持有助记词自己派生 | **A**。与 M3 的 xpub 同一套注入方式，够 v1；B 多一层加密但口令仍要注入，M6 谈 KMS 时一起看；C 违反「服务端没有助记词」。附加规矩：私钥由离线工具从**另一句助记词或 hardened 账户 1'** 算出，绝不用收款树的账户 |
| 2 | 交易类型 | A 只做 EIP-1559；B 兼容 legacy | **A**。Sepolia 与主网都支持；两套费率模型只会让卡单策略翻倍 |
| 3 | 签名实现 | A 自写 RLP + BouncyCastle 的 ECDSA；B 引 web3j | 先按 A 做完并过了全部向量，**用户改判为 B**（2026-09-09）：协议层编码用库、不重复造轮子。只拿 web3j 6.0.0 的 `crypto` 模块（不拿 `core`，排除 EIP-4844 原生库），薄包装隔离类型，测试原样保留当验收。A 版三个类 487 行换成 326 行薄包装（算法全部委托，剩下的是字段校验、拆回再编回的比对与注释），原则记在 CLAUDE.md §7 |
| 4 | nonce 分配 | A 库里分配 + 先落库签名后广播 + 单发送线程；B 发送时问节点 pending | **A**。真相虽在链上，但「我打算发第 12 笔」这个意图必须先落库，重启才有据可依；对账把链的真相拉回来 |
| 5 | 账本流 | A 每商户每币一个冻结账户；B 平台级清算账户 | **A**。商户能在余额接口看到 frozen；RLS 天然归属；M5 对账时冻结合计就是「在路上的出账」 |
| 6 | gas 记账 | A 记在 `payout_tx`，不进 LINK 账本；B 建 ETH 账户体系 | **A**。ETH 是平台成本，v1 不向商户收费；M5 对账时再决定要不要 ETH 账本 |
| 7 | 风控 v1 范围 | 白名单必做（验收标准）；限额超限转人工；拒绝平台收款地址；冷却期与二次验证不做 | 如左 |
| 8 | 卡单策略 | A 自动加价（+25%，有上限）+ 超上限停下叫人；取消只作运维手段；B 全人工 | **A**。取消交易会改变 nonce 用途，容易和加速打架，v1 不自动做 |
| 9 | 结算时点 | A 绑 FINAL（同 M3）；B MINED 即结算 | **A**。重组只碰链表不碰账本这条纪律不能只对入账成立 |
| 10 | 删 `asSystem` | A 放在 ⓪；B 推到 M5 | **A**。CLAUDE.md 承诺的是 M4；改动只在测试支撑类 |

---

## 四、动手前你要准备的（用户侧）

1. **另一句测试助记词**（或决定用同一句的 hardened 账户 1'）：热钱包的私钥从它算出。按真密钥对待。
2. 热钱包地址算出后，去 Sepolia 水龙头领 ETH 给它付 gas（每笔 ERC-20 转账约 0.0001 到 0.001 ETH）。
3. 从 MetaMask Account 1 把几 LINK 转到热钱包（人工归集）。
4. 准备一个白名单目标地址（MetaMask 新建的 Account 2 即可）。
5. 上面第三节的十条取舍。

---

## 五、「这一步会怎么坏」

题目在 `docs/retro/M4-before.md`。按规矩 AI 只提问不给答案。

## 六、对照清单（动手前翻这几份）

| 主题 | 文件 |
|---|---|
| 私钥与秘密的保管 | `flow-pay-backend/docs/ai/knowledge/owasp-cheatsheets/Key_Management.md`、`Secrets_Management.md`、`Cryptographic_Storage.md` |
| 高风险操作的授权（限额、人工核准） | `owasp-cheatsheets/Transaction_Authorization.md` |
| 日志里不该出现什么 | `owasp-cheatsheets/Logging.md`、`Logging_Vocabulary.md` |
| 冻结 / 结算 / 冲正的复式记账 | `tigerbeetle/concepts/debit-credit.md`、`recipes/balance-bounds.md`、`recipes/correcting-transfers.md` |
| 流程绕过（先广播后扣款之类） | `owasp-wstg-business-logic/06-Testing_for_the_Circumvention_of_Work_Flows.md` |
| 地址归属与校验和 | `falsehoods/falsehoods-about-IBANs.md`（类比） |
| 确认与重组 | `chainpay/docs/knowledge/m2-block-indexer.md` §三、`m3-payin.md` §一.4 |
