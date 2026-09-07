# M3 · 收款 Pay-In —— 规划与前置知识（2026-09-04）

> 按 CLAUDE.md §3：这份只讲**事实、原理与规划**。「这一步会怎么坏」在文末**只列问题不给答案**，答案由你写进 `docs/retro/M3-before.md`。
> 带 ★ 的是本仓库实测或从本仓库代码直接推出来的；标「以官网为准」的是会变的外部事实。

---

## 〇、M3 要做成什么

**目标（LEARNING-PATH）：真的从测试网转一笔进来，余额正确增加。**

验收五条原文：

```
✅ 从 Sepolia faucet 领币 → 转到你派生的地址 → 余额正确增加
✅ 同一笔 tx 被索引两次 → 不能双记
✅ 确认数不够时余额不可用，够了才可用
✅ 转入未支持的代币 → 不能崩，要有明确处理
✅ 转入 0 或极小金额 → 按策略处理，不能产生负手续费
```

### M2 已经替 M3 铺好的路 ★

M3 不是从零开始，M2 的六步把「链上发生了什么」这个问题解决了，M3 只回答「这笔钱归谁、什么时候记」：

| M2 留下的东西 | M3 怎么用 |
|---|---|
| `chain_transfer_log`（永不删行，重组标 ORPHANED，可复活） | 入账的**唯一证据来源**，M3 只读它，不改它 |
| 视图 `chain_transfer_confirmation`（SEEN < SAFE < FINAL 算出来不存） | **入账队列 = `level = 'FINAL'` 且尚未记账的行**（V10 注释原话） |
| `chain_token` 白名单 + V15 外键 | 「未支持的代币」在结构上进不了事件表，M3 不用再判 |
| `TokenAmounts.toLedger`（精确除法、溢出抛 `AmountOverflowException`） | 原始单位 → 账本单位的**唯一**换算入口 |
| `Erc20Calls.balanceOf(token, holder, blockTag)` | 入账前拿链上余额做核对 |
| 审计节点（`CHAINPAY_CHAIN_AUDIT_RPC_URL`） | 入账前向第二个节点核对块哈希与 finalized 高度 |
| `BlockHeader.timestamp` | 入账的 `occurred_at`（业务发生时刻 = 区块时间） |
| `TransferCode.DEPOSIT`、`TenantScope.asSystem()` | 账本层早就预留了「链上充值」这个业务类型和系统作用域 |

### 代码里已经替 M3 下过的判决 ★（这些是约束，不是选项）

从 CLAUDE.md §2 与迁移注释里摘出来的、写在 M3 之前的决定：

1. **给用户加钱绑在 FINAL 上**（V10）：等约 13 分钟，换掉整类「钱已加上又要撤」的问题；重组回滚永远只碰链表、不碰账本。
2. **入账那一步再向两个节点核对该行的块哈希与 finalized 高度**（CLAUDE.md §2「链数据」）：那是动钱的边界，索引器无论怎么错都过不了这道门。
3. **装不下 `NUMERIC(38,18)` 的金额 → 标成「无法入账、等人看」**，不能让它卡住循环。
4. **铸币（from = 0x0）按普通入账**；不发事件的铸币留给 M5 用 `balanceOf` 对账发现。
5. **M3 落地第一个真实的系统操作时，`asSystem()` 升级为独立的 system 角色 + 独立连接池**；在那之前先用 ArchUnit 断言 `controller` 包不得引用 `asSystem`。
6. 账本层继续 `JdbcClient` 手写 SQL；「M3 之后的业务层可以引入 ORM，但要显式讨论并记录取舍」。
7. 关键时刻用 `balanceOf` 核对（V13 注释：M3 入账、M5 对账）。

---

## 一、前置知识（从零讲）

### 1. 一个钱包地址是怎么来的

链上没有「账号密码」，只有三样东西，一层算一层，**每一层都是单向的**：

```
私钥 k            256 位随机数。谁知道它，谁就能花这个地址上的钱
   │  椭圆曲线乘法（secp256k1）：K = k · G      ← 单向：由 K 算不回 k
公钥 K            曲线上的一个点，64 字节（x ‖ y）
   │  Keccak-256(K)，取最后 20 字节              ← 单向：由地址算不回 K
地址              20 字节 = 40 个十六进制字符，前面加 0x
```

打个比方：私钥是印章，公钥是印章印出来的图案，地址是图案的指纹。看到指纹推不出图案，看到图案刻不出印章。

三个初学者必踩的坑：

- **地址不是账户。** 链上不需要「注册」，任何 20 字节都是合法地址，往一个没人有私钥的地址转账，钱就永远丢了。
- **Keccak-256 ≠ SHA3-256。** 以太坊用的是标准化之前的 Keccak，和 NIST 定稿的 SHA3-256 只差一个填充字节，但结果完全不同。Java 自带的 `MessageDigest.getInstance("SHA3-256")` 算出来的**不是**以太坊地址。★ 本仓库目前没有任何 Keccak 实现：`Abi` 里的四个函数选择器是写死的常量——M3 派生地址必须引入一个库（见二、M3-①）。
- **大小写是校验和（EIP-55）。** `0xAbC…` 里的大小写不是随意的，是把小写地址再做一次 Keccak，按哈希位决定每个字母的大小写。抄错一位，校验和对不上——所有钱包都会拒绝。存库统一小写（本仓库 CHECK 约束已经要求小写），**对外展示时给带校验和的写法**。

### 2. HD 钱包：一个种子派生无限地址

给每个商户（甚至每张收款单）一个地址，如果每个地址都是独立随机私钥，你就得保管成千上万把私钥。HD（Hierarchical Deterministic，分层确定性）钱包用**一个种子确定地生成一整棵密钥树**，三份标准各管一层：

| 标准 | 管什么 | 一句话 |
|---|---|---|
| **BIP-39** | 助记词 → 种子 | 12/24 个英文词，经 PBKDF2-HMAC-SHA512（2048 轮，可加口令）得到 512 位种子。词是给人抄的，种子才是根 |
| **BIP-32** | 种子 → 密钥树 | 种子经 HMAC-SHA512 得主密钥 + 链码；每个节点能派生 2^32 个子节点；节点 = 密钥 + 链码，合称**扩展密钥**（xprv / xpub） |
| **BIP-44** | 树的目录结构 | `m / 44' / 币种' / 账户' / 找零 / 序号`，以太坊币种号 60。MetaMask 的第 n 个账户就是 `m/44'/60'/0'/0/n` |

路径里带 `'` 的层叫**硬化派生**（index ≥ 2^31），需要父私钥才能算；不带 `'` 的层叫**普通派生**，**只用父公钥就能算出子公钥**。这一条是 M3 最重要的知识点：

> **★ M3 的服务器不需要任何私钥。**
> 把账户层的扩展公钥（`m/44'/60'/0'` 的 xpub）交给服务器，它就能派生出 `0/0, 0/1, 0/2 …` 无穷多个收款地址，而**助记词和私钥从头到尾不在服务器上**。收进来的币躺在这些地址上，动它们要私钥——那是 M4 的事（归集与出金）。
> 这就是交易所「充值地址由后端生成、私钥在冷端」的原理，叫 watch-only（只看不花）钱包。

xpub 的两个代价（也是 M3-before 里要问的）：

- **xpub 泄露 = 隐私全丢**：拿到它的人能枚举你所有收款地址，看到全部资金流向（但转不走）。
- **xpub + 任意一个普通派生的子私钥 = 父私钥**：这是 BIP-32 普通派生的数学缺陷，所以「用 xpub 派生地址」和「把某个子私钥单独交出去」这两件事**绝不能同时做**。

### 3. 谁的钱：地址归因的三种模式

以太坊的转账**没有附言**。链上只有「谁 → 谁 → 多少」，没有「这是订单 #123 的货款」。归属只能靠地址：

| 模式 | 做法 | 优点 | 难题 |
|---|---|---|---|
| **一户一址**（交易所主流） | 每个用户、每种币一个专属地址 | 归属明确、地址数可控 | 用户重复打款分不清哪笔对应哪单（收款单要靠金额 + 时间猜） |
| **一单一址** | 每张收款单新派一个地址 | 精确对应到单 | 地址数量随订单爆炸；每个地址上的钱都要归集 |
| **地址复用 + 金额识别** | 一个地址收所有人的钱，靠金额/时间匹配 | 最省 | 两个人同一时刻转同样金额就撞了；本质是猜 |

XRP / XLM / EOS / TON 这类链有 memo / destination tag，可以「一个地址 + memo 区分用户」——**flow-pay 的 `65372f8a` 就踩过丢弃 memo 的坑**（丢了 memo 的地址 = 收不到账的地址）。以太坊没有 memo，所以只能在地址上做文章。

ERC-20 特有的隐藏成本——**gas 喂养问题**：币收在派生地址上，要把它归集到热钱包，得由那个地址发一笔交易，而发交易要付 ETH 当 gas。于是每个收过款的地址都得先「喂」一点 ETH。行业解法有三种：往地址里打 gas 再归集（最笨、最贵）、用支持 `permit` 的代币、用合约转发器（CREATE2 预计算地址，收到即转）。**M3 不做归集**，但地址模型要给它留门：派生序号、地址状态、每个地址上「链上应有多少」都要能查。

### 4. 什么时候算到账：把 M2 的三态用起来

复习 M2：`latest` 会翻、`safe` 几乎不翻、`finalized` 翻了要罚没 ≥ 1/3 质押。Sepolia 实测 safe 落后 35 块、finalized 落后 66 块（≈ 13 分钟）★。

交易所的数字是用钱买来的风险定价：Binance 12 个确认、OKX 入账 32 个、**提现要求更高**。「入账」和「可用 / 可提」是**两个门槛**——钱先出现在余额里让用户安心，但要再等一会儿才允许它离开。

chainpay 的判决是绑 FINAL（上面第 1 条）。代价是十几分钟的等待；收益是**账本里永远只有事实**，M2-④ 的重组回滚永远不需要碰账本。M3 要把「门槛」做成两层：

- **SEEN / SAFE**：对商户可见（「有一笔 x LINK 在路上」），但**不在余额里**
- **FINAL**：记账，余额增加

「按金额分级确认数」（小额 3 个、大额 30 个）是产品取舍不是技术问题；本项目目前一刀切 FINAL，V10 的 `confirmations` 列留着，将来要分级不用改表。

### 5. 复式记账视角的入账：钱从哪来、到哪去

账本（V1/V2）的符号约定：一笔 `transfer` 写两条 `entry`，借方账户 −amount（钱从这里出），贷方账户 +amount（钱到这里去），每种币 `SUM(amount) = 0` 是判官。

入账时「钱到这里去」是商户账户，那「钱从哪里出」？**复式记账里钱不能凭空出现**，所以要有一个对手方——它代表「链上托管钱包里的币」在账本中的镜像。这和 M0 的 SEED 一模一样：注资时资金来源账户变负。

```
链上：买家 → 商户的派生地址（我们控制的托管地址）  收到 10 LINK
账本：transfer(code = DEPOSIT)
        借  chain:custody:LINK   −10      （镜像账户，allow_negative = true）
        贷  user:<mid>:LINK      +10      （商户账户，不可为负）
```

镜像账户的余额永远是负的，**它的绝对值 = 我们所有托管地址在链上应有的余额之和**。M5 对账时拿 `balanceOf` 逐地址加总和它比——这就是「链是真相、库是副本」的判官，M3 建表时就要为它留好。

三个和本仓库直接相关的细节 ★：

- **商户账户可能还不存在。** 现在没有任何「开账户」的接口，账户只在测试里用属主连接建。M3 分配收款地址时要顺手确保 `user:<mid>:<TOKEN>` 账户存在（`account_code_uk` 让它幂等）。
- **幂等键用链上坐标：`deposit:<block_hash>:<log_index>`**，不用 `tx_hash`——重组后同一笔交易会换块重现（V9 注释原话）。系统作用域下 `submitter_merchant_id` 为 NULL，V7 的 `NULLS NOT DISTINCT` 保证它照样幂等。
- **`occurred_at` = 区块时间**，不是写库时间。`BlockHeader.timestamp` 有，但 `chain_transfer_log` 没存——入账前反正要重新取区块头核对哈希，顺手拿。

### 6. 金额：原始单位 → 账本单位

链上 `value` 是无小数点的整数（`NUMERIC(78,0)`），账本是 `NUMERIC(38,18)`。`decimals` 决定小数点往左挪几位：LINK 的 18 位 → `1000000000000000000` 是 1 LINK。

- **只经 `TokenAmounts.toLedger`**：精确除法，永不四舍五入；整数位超过 20 位或 decimals 超过 18 位抛 `AmountOverflowException`。
- **零值转账**：EIP-20 说它「MUST 按普通转账处理」，所以索引器记它；但账本 `transfer_amount_ck` 要求 > 0——记账层的策略是「记录、不入账」。
- **灰尘（dust）与最小入账金额**：一笔 0.000001 LINK 的入账，归集它要花的 gas 比它本身还贵；交易所的做法是每种币设最小入账额，低于它**不入账也不退**（以各家页面为准）。这就是验收标准里「不能产生负手续费」的意思：手续费不能比金额大。策略要参数化在 `chain_token` 上，不写死在代码里。

### 7. 代币的谎言：为什么记账前还要问一次 `balanceOf`

M2-⑥ 讲过：Transfer 事件是合约「说」的，余额是合约「做」的。转账扣费（转 100 到 98）、弹性供应（余额自己变）、失败不回滚（先发事件再返回 false）——这些代币的事件金额 ≠ 到账金额。白名单挡住了大部分，但白名单也可能收错人。

所以入账前多做一步：对着 finalized 的块问 `balanceOf(收款地址)`，它必须 ≥ 「这个地址所有已 FINAL 的入账之和」（M3 没有归集，等式应精确成立）。对不上 = 不记账、标 HELD、叫人看。**信合约做的，不信合约说的。**

### 8. 归属与安全：地址表就是新的租户边界

M1 花了三步才把「这个账户是你的吗」做对（认证 ≠ 授权、RLS 兜底、不可区分响应）。收款地址会重演一遍：

- 地址只能由服务端派生，商户**不能传地址、不能传序号**（Mass Assignment）
- 「这个地址是谁的」是租户数据，要进 RLS；查一个不属于你的地址，回「不存在」还是「无权」？（M1 的答案是不可区分）
- 派生序号必须**全局唯一且只增**：`MAX(index) + 1` 是 check-then-act，本项目已经踩过 8 次同型——用数据库序列或 `UNIQUE` + 重试
- 商户重试「创建地址」不能得到两个地址：要么一户一币一址（`UNIQUE(merchant_id, token)`），要么带客户端幂等键

---

## 二、M3 分步规划（沿用 M2 的 ①…⑥ 形态）

每一步都按 §3 走：先问坏法 → 红灯测试 → 绿灯 → 对清单 → 复盘。**每步结束都能拆一面墙**（去掉某行让某条测试变红），否则那条测试没在守东西。

### M3-⓪ 地基（不碰链、不碰钱）

| 产出 | 说明 |
|---|---|
| ArchUnit 测试 | `controller` 包不得引用 `TenantScope.asSystem`（CLAUDE.md 欠的那条） |
| 系统角色 + 独立连接池 **或** 显式推迟并记录理由 | CLAUDE.md 说 M3 落地时升级。升级 = 新角色 `chainpay_system`（`db/init/01-roles.sql`）+ 只给它放行的 RLS 策略 + 第二个 `DataSource`/`JdbcClient`。要评估：现在只有一个索引器和一个入账任务，值不值得先做？不做的话「一个 public 方法谁都能调」的风险怎么守？ |
| 选型记录 | 派生地址用哪个库（见 ①） |

### M3-① 地址派生（零私钥）

- **选型**：仓库里没有 Keccak 和 secp256k1。两条路——`org.web3j:crypto`（成熟、但要联网核实它拖进哪些传递依赖，本机 `~/.m2` 没缓存过它）或直接用 BouncyCastle（`bcprov-jdk18on` 本机已有 ★，`Keccak.Digest256` + `ECPoint` 乘法 + `HMAC-SHA512`，BIP-32 公钥派生约 60 行）。判据同 M2 选裸 JSON-RPC：越是承重的地方抽象越薄，但密码学**不要手写曲线运算**，用库的曲线、自己写派生逻辑。
- **输入**：账户层 xpub 从环境变量注入（`CHAINPAY_DEPOSIT_XPUB`），无默认值，不设 = 不装配收款模块（同索引器的 `@ConditionalOnProperty`）
- **表**：`V16 deposit_address(address PK 小写, merchant_id, token, account_id, derivation_index UNIQUE, status ACTIVE/DISABLED, created_at)` + RLS + 派生序号用 `SEQUENCE`
- **服务**：`DepositAddressService.allocate(merchantId, token)` —— 确保账户存在 → 取序号 → 派生 → 落库；一户一币一址时重复调用返回同一地址
- **已知答案测试（KAT）**：BIP-32/BIP-44 的公开测试向量 + **一条自己的**：用一个测试专用助记词导入 MetaMask，它显示的前三个账户地址必须等于我们派生的 `0/0, 0/1, 0/2`（大小写按 EIP-55）。派生错一位，钱就打进没人有私钥的地址——这条 KAT 是 M3 最值钱的测试
- **墙**：改坏 EIP-55 → KAT 红；把 `MAX+1` 换回来 → 并发分配测试红

### M3-② 入账队列与记账

- **表**：`V17 deposit(id, transfer_log_id UNIQUE → chain_transfer_log, address, merchant_id, token, amount_ledger NUMERIC(38,18), status, transfer_id → transfer, hold_reason, created_at, credited_at)`；状态词表见 ③
- **任务**：`DepositPoster`（定时，独立于索引器）：
  1. 从 `chain_transfer_confirmation` 取 `level = 'FINAL'`、`to_address ∈ deposit_address(ACTIVE)`、尚无 `deposit` 行的记录
  2. **核对**：向主节点和审计节点各取一次该块头，哈希必须等于行里的 `block_hash`，且块号 ≤ 两个节点的 finalized；不一致 = 不记，HELD
  3. 换算金额（`TokenAmounts.toLedger`），溢出 = HELD_OVERFLOW
  4. 在 `asSystem` 里 `ledger.transfer(DEPOSIT, key = deposit:<block_hash>:<log_index>, occurredAt = 区块时间)`，`deposit` 行与账本转账**同一事务**
- **契约测试**（FakeChain 已有 `addTransfer / reportFinalized / defineBalance / beforeBlock`）：同一日志跑两遍只记一笔；FINAL 之前不记、之后记；两个实例并发不双记；崩在「写 deposit」与「写 transfer」之间不留半截；块哈希核对不过不记
- **墙**：把 deposit 与 transfer 拆成两个事务 → 崩溃测试红；去掉块哈希核对 → 「节点撒谎」测试红

### M3-③ 策略与例外

状态机（写进 CHECK 约束）：`CREDITED` / `IGNORED_ZERO` / `REJECTED_DUST` / `HELD_OVERFLOW` / `HELD_BALANCE_MISMATCH` / `HELD_NODE_DISAGREE`。HELD 类永远不自动重试成 CREDITED，只能人处理后改状态——**入账任务遇到 HELD 要跳过继续，不能卡住整个队列**（CLAUDE.md 那条判决）。

- `chain_token` 加 `min_deposit`（账本单位）；低于它 → `REJECTED_DUST`，记录但不入账
- 入账前 `balanceOf(address, finalized)` 与该地址已 CREDITED 之和比对，不等 → `HELD_BALANCE_MISMATCH`
- 地址 DISABLED 后来的钱、代币 DISABLED 后来的钱：记不记？（M3-before 里问）

### M3-④ 对外接口（照币安 / OKX）

- `POST /api/v1/deposit-addresses {token}` → `{address(EIP-55), token, createdAt}`，幂等
- `GET /api/v1/deposits?token=…&status=…` → 列表，金额字符串，含 `confirmations` 与 `level`（SEEN/SAFE/FINAL），让商户看到「在路上」的钱
- 余额接口区分 `available`（已记账）与 `pending`（SEEN/SAFE 合计）——两个门槛在 API 上的样子
- 参照：币安 `GET /sapi/v1/capital/deposit/address`、`/deposit/hisrec`；OKX `GET /api/v5/asset/deposit-address`、`/asset/deposit-history`（动手前查一次官方文档，字段会变）

### M3-⑤ 真环境演练

- 准备：一个**测试专用**助记词（按真密钥对待，不进 git、不进聊天记录）；MetaMask 导入它；Sepolia ETH（付 gas）与 Sepolia LINK（Chainlink 的测试网水龙头 faucets.chain.link，以页面为准）
- 流程：分配地址 → 从 MetaMask 转 1 LINK → 看 `deposits` 接口里 SEEN → SAFE → FINAL（约 13 分钟）→ `CREDITED` → 余额接口 available 增加
- 同 M2 的两条演练：书签回退 1000 块重放不双记；入账任务跑到一半 `kill -9` 重启不漏不重

### M3-⑥（可选，可推迟到 M5 之后）webhook 通知

LEARNING-PATH 的架构图里有它，但它是**外部副作用**——M2 知识文档里 Envio 那句「外部副作用回滚不了」正是为它写的。绑 FINAL 之后这个问题小了很多，但「至少一次投递 + 商户侧幂等」是另一整套东西，建议单独立项。

---

## 三、动手前你要准备的（用户侧）

1. 一个只用于测试网的助记词：用钱包新建，抄在纸上；**把它当真密钥**——习惯是练出来的（CLAUDE.md §7）
2. MetaMask 里切到 Sepolia，用上面的助记词导入，记下前三个账户地址（KAT 用）
3. Sepolia ETH：Alchemy / Google Cloud 的 Sepolia 水龙头（以页面为准，通常每天限量）
4. Sepolia LINK：Chainlink 水龙头（以页面为准）
5. 确认现有的 Alchemy 主节点与 tenderly 审计节点仍可用（M2 探针）

---

## 四、「这一步会怎么坏」——只提问（答案写进 `docs/retro/M3-before.md`）

### 关于地址与归属
1. 派生出来的地址如果算错了一位，钱去了哪里？谁会先发现——你、商户、还是永远没人？**哪条测试能在上线前抓住它**？
2. xpub 存在哪？谁能读到它？它泄露的后果是什么、和私钥泄露差在哪？
3. 派生序号是怎么取的？两个请求同时到达会不会拿到同一个序号？（这是 check-then-act 第 9 次的候选位置。）
4. 商户把「创建地址」重试了三次，会得到几个地址？三个地址都能收钱吗？
5. 一个已经 DISABLED 的地址又收到钱了，记还是不记？不记的话钱在哪？
6. 两个白名单代币的 `symbol` 都叫 USDT（一真一假），账本的 `currency` 列填什么？`chain_token.symbol` 现在有唯一约束吗？

### 关于什么时候记
7. 入账绑 FINAL，那么 SEEN / SAFE 的钱在 API 里长什么样？商户能不能拿「在路上」的钱去做任何事？
8. 一条日志 FINAL 了、也记了账，之后它被标成 ORPHANED（理论上不可能，但 `FinalityViolationException` 那条路存在）。账本上那笔怎么办——复式记账里「撤销一笔入账」长什么样？
9. 索引器停了（HALTED）三个小时，这三小时里到的钱什么时候记？入账任务需要知道索引器的状态吗？
10. 入账任务本身跑在两个实例上：同一条 FINAL 日志会不会被两个实例同时记？靠什么互斥——行锁、唯一约束、还是「事务里先 INSERT deposit 再转账」的顺序？

### 关于幂等与崩溃
11. 幂等键为什么是 `(block_hash, log_index)` 而不是 `tx_hash`？重组后同一笔交易换了块，两条日志会不会记两次？
12. 崩溃在「写 deposit 行」和「写 transfer」之间，重启后会怎样？两者的顺序颠倒过来又会怎样？
13. 账本的 `transfer()` 是幂等的（同键同体返回原 id）。那 `deposit` 行呢？它和 `transfer` 的一对一关系由谁保证？

### 关于金额
14. 一笔 `value = 0` 的 Transfer 到了 FINAL，记账层会怎样？`transfer_amount_ck` 会拦，拦下来之后这条日志算处理完了吗？
15. 一笔 10^33 原始单位的转账（装不下 20 位整数），入账任务会停下来还是跳过？跳过的话谁知道？
16. 最小入账额定在哪里、谁定、改了之后此前被拒的灰尘要不要补记？

### 关于代币的谎言
17. 一个转账扣费的代币进了白名单，事件说 100、链上到 98。入账记 100 会怎样？M5 对账能发现吗？在 M3 就能发现吗？
18. `balanceOf` 核对时用哪个块？`latest` 和 `finalized` 会给出不同的答案吗？地址上同时有一笔 SAFE 的转入，核对会不会误判？

### 关于安全
19. 商户能不能通过接口枚举出别人的收款地址？「不存在」和「不是你的」两条路径返回的是不是同一句话？
20. 入账任务用 `asSystem()` 跑，它读的是所有商户的地址表。哪一行代码保证它把钱记到**正确的**商户账户上，而不是记错人？记错了三个判官会响吗？

### 关于测试
21. 这些坏法里，哪些能用 FakeChain + Testcontainers 构造？哪些只能在 Sepolia 上演练？哪些两者都做不到——那它们靠什么被守？

---

## 五、对照清单（动手前翻这几份）

| 主题 | 文件 |
|---|---|
| 复式记账、镜像账户、余额不能为负 | `flow-pay-backend/docs/ai/knowledge/tigerbeetle/concepts/debit-credit.md`、`recipes/balance-invariant-transfers.md` |
| 撤销一笔入账（冲正） | `tigerbeetle/recipes/correcting-transfers.md` |
| 银行账户思路迁移到收款地址（归属、校验和） | `falsehoods/falsehoods-about-IBANs.md` |
| 密钥与 xpub 的保管 | `owasp-cheatsheets/Key_Management.md`、`Secrets_Management.md`、`Cryptographic_Storage.md` |
| 哪些字段允许客户端传 | `owasp-cheatsheets/Mass_Assignment.md` |
| 不可区分响应、枚举 | `owasp-cheatsheets/Insecure_Direct_Object_Reference_Prevention.md` |
| 流程绕过（先记账后确认之类） | `owasp-wstg-business-logic/06-Testing_for_the_Circumvention_of_Work_Flows.md` |
| 确认与重组的事实 | `chainpay/docs/knowledge/m2-block-indexer.md` §三 |

标准原文：BIP-32 <https://github.com/bitcoin/bips/blob/master/bip-0032.mediawiki>、BIP-39 <https://github.com/bitcoin/bips/blob/master/bip-0039.mediawiki>、BIP-44 <https://github.com/bitcoin/bips/blob/master/bip-0044.mediawiki>、EIP-55 <https://eips.ethereum.org/EIPS/eip-55>、SLIP-44（币种号）<https://github.com/satoshilabs/slips/blob/master/slip-0044.md>
