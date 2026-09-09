# chainpay · 学习路线

> 一个加密支付网关。目的不是做出产品，是**用一个真实到会咬人的项目，把正确性、安全、部署这三件事练成肌肉记忆**。
>
> 创建于 2026-08-12。

---

## 一、这个项目是什么

**chainpay = 一个加密货币收付款网关。**

```
商户                      chainpay                     链 / 交易所
 │                           │                            │
 │  ① 创建收款单             │                            │
 │ ─────────────────────────>│                            │
 │  <── 返回充值地址 ────────│                            │
 │                           │                            │
 │                           │  ② 监听链上到账             │
 │                           │ <──────────────────────────│
 │  <── webhook 通知 ────────│                            │
 │                           │                            │
 │  ③ 发起提现               │                            │
 │ ─────────────────────────>│  ④ 签名 + 广播交易          │
 │                           │ ──────────────────────────>│
 │  <── webhook 通知 ────────│  <── 确认 ────────────────  │
```

你在这个系统里**同时扮演两个角色**：

| 角色 | 你是谁的什么 | 要学什么 |
|---|---|---|
| **上游** | 商户调用你的 API | **怎么当一个成熟的服务商**——照币安 / OKX 的规范设计 |
| **下游** | 你调用链 / 交易所 | **怎么正确对接一个你不控制的系统**——重组、超时、重复推送 |

> **这正是 flow-pay 教你的东西的镜像。** 在 flow-pay 里，你是 OSL 的下游，被一个不成熟的服务商反复教育。
> 在 chainpay 里，你要**自己成为那个服务商**——而"怎么当好服务商"的标准答案，币安和 OKX 已经完整写在他们的 API 文档里了。

---

## 二、为什么选这个方向（而不是别的）

我评估过几个候选，这个方向胜出的理由是**它能自动制造你需要的那种 bug**。

### 从零搭项目的最大陷阱

**一个人 + AI 搭出来的项目，几乎必然"能跑但学不到东西"。**

因为你在 flow-pay 学到的那些教训——随机 ID 撞号、check-then-act、并发双花、缓存回填旧值——**没有一个是"写代码"时发现的，全是被现实咬出来的**：

- 真实并发（不是你一个人点）
- 真实的钱（错了有人损失）
- 真实的外部系统（会超时、会重复推、会返回旧数据）
- 真实的部署（环境和你本地不一样）
- 真实的队友（会 review 你，会改你的代码）

**你一个人写的 CRUD，这五样一个都没有。** 所以从零搭项目的核心设计问题不是"做什么功能"，是：

> **怎么让这个项目自带对抗性，在没有真实用户和真实金钱的情况下，仍然会咬你。**

### 区块链恰好把对抗性白送给你

| 你需要的对抗性 | 链免费提供 |
|---|---|
| 真实并发 | 全网都在发交易，nonce 会撞、gas 会涨 |
| 真实外部系统 | RPC 节点会超时、会返回旧数据、多节点不一致 |
| 不可控的重放 | **区块重组（reorg）**——你以为确认的事实会被撤销 |
| 可验证的真相 | 链上数据是公开的，**你能拿它对账，证明自己算错了** |
| 真实的钱语义 | 转账不可逆、精度 18 位、手续费必须自己出 |
| 零成本 | **测试网完全免费**，faucet 领币，随便炸 |

**最后一条"可验证的真相"是关键。** 普通 CRUD 项目里，你的数据库就是唯一真相，没人能证明你错了。
链上项目里，**链是真相，你的库是副本**——只要写一个对账脚本，它就会持续告诉你哪里算错了。

> **这就是"能抓住你的机制"。** 学习项目最缺的不是功能，是**独立于你的判官**。

---

## 三、技术选型与理由

**全部取当前最新稳定版**（2026-08 核实于 Maven Central / Docker Hub / Adoptium）。

| 层 | 选型 | 版本 | 为什么是它 |
|---|---|---|---|
| 语言 | **Java** | **25 (LTS)** | 当前最新 LTS。比 flow-pay 的 21 新一代 |
| 框架 | **Spring Boot** | **4.1.0** | 最新；带 Spring Framework 7 |
| 数据库 | **PostgreSQL** | **18** | 最新 GA（19 还是 beta）。**刻意换掉 MySQL**（见下） |
| 数据访问 | **Spring `JdbcClient`（不用 ORM）** | 内置 | **账本层必须看得见每一条 SQL**（见下） |
| 迁移 | Flyway | 12.4.0 | Boot 托管。`strong_migrations` 清单能直接用 |
| 测试 | JUnit Jupiter + Testcontainers | 6.0.3 / 2.0.5 | Boot 托管。真 Postgres 跑测试，不用 H2 骗自己 |
| 构建 | Maven | 3.9.16 | 本机已装 |
| 容器 | Docker Compose | 29.7.2 | 本机已装 |
| 链（M2 起） | **Ethereum Sepolia 测试网** | — | 免费、真实、12 秒出块（够慢，看得清） |
| 链库（M2 起） | web3j | 6.0.0 | 到 M2 再加，现在不装 |
| 上游参照 | **Binance API / OKX API v5** | — | 学 API 设计规范 |

### 四个需要解释的选择

#### ① 为什么全上最新版：好处和代价都要说清楚

**好处**（你要的兼容性）：

- Java 25 是 LTS，未来 4–5 年都是主流基线
- Spring Boot 4 / Framework 7 是新一代主干，3.x 会逐步进入维护期
- 依赖树全新，不会一开始就背技术债

**代价**（必须提前知道）：

> **选最新版 = 你会成为第一批踩坑的人。**
>
> 网上的示例大多还是 Spring Boot 3.x 的写法，某些第三方库还没跟上 Framework 7。
> 遇到问题时，StackOverflow 上可能没有答案，**得自己读官方文档和源码**。

**但对这个项目来说，这个代价是划算的**——因为「读官方文档、读源码、自己判断」正是你要练的能力。
遇到 4.x 特有的问题我会明确标注出来，不会让你以为是自己写错了。

#### ② Spring Framework 7 顺手补上了你的一个知识空白

我之前判断过：你在 flow-pay 的教训里有几条"现成清单填不上"，其中一条是**三态建模**——
`undefined` ≠ `false` ≠ `null`，这三者混用是 bug 的温床，但它是类型系统实践，没有清单可抄。

**Framework 7 引入了 JSpecify 的空安全注解**（`@Nullable` / `@NonNull` 有了跨生态的统一标准），
配合 IDE 和编译期检查，能把"这个值可能不存在"变成**编译器帮你查的事**，而不是靠你记得。

> 这一条到 M1 会用上：API 里"字段没传"和"字段传了 null"是**两件事**，
> 币安/OKX 的接口设计对此有明确区分。

Framework 7 还带来两个对本项目直接有用的东西：

- **API 版本化支持** → M1 用
- **内建弹性能力**（重试、并发限流注解）→ M2/M4 用，正好对上 RPC 不可靠和 nonce 串行化

#### ③ 为什么换成 PostgreSQL

不是因为它"更好"，是**因为换一个能让你看见差异**。

| Postgres 的特性 | 学到什么 |
|---|---|
| `NUMERIC` 是任意精度的真十进制 | MySQL 的 `DECIMAL` 也是，但 Postgres 的溢出/舍入行为更严、报错更早 |
| `REPEATABLE READ` 是真正的快照隔离 | MySQL 的同名级别有间隙锁的怪异行为——**对比着学才知道"隔离级别"不是一个词** |
| `EXCLUDE` 约束、部分索引、更强的 `CHECK` | **能把不变量写进数据库**，而不是靠应用代码守 |
| `TIMESTAMPTZ` 真的带时区语义 | MySQL 的 `TIMESTAMP` 坑很多 |
| **DDL 可以在事务里回滚** | 迁移失败不会留下半个表——这一条 MySQL 做不到 |

> 更重要的是：**你以后一定会遇到 Postgres**。加密和金融领域它是事实标准。

#### ④ 为什么账本层不用 ORM

这是**故意的**，而且是最重要的一个选择。

你在 flow-pay 的教训清单里有一条我判定"清单填不上"的空白：**框架魔法边缘**——ORM 插件在特定版本下的行为，谁也没法提前给你清单。

**账本是整个系统里唯一不能错的地方。** 在这里你会写：

```java
// 每一条 SQL 都在眼前
long entryId = jdbcClient.sql("""
        INSERT INTO entry (transfer_id, account_id, currency, amount)
        VALUES (:transferId, :accountId, :currency, :amount)
        RETURNING id
        """)
    .param("transferId", transferId)
    // ...
```

而不是：

```java
entryRepository.save(entry);   // 这一行到底发了几条 SQL？加没加锁？触没触发多租户插件？
```

> **判据：越是不能错的地方，抽象越要薄。**
> 业务 CRUD 用 ORM 没问题，**账本不行**。

（M3 之后的业务层你可以引入 JPA 或 MyBatis——那时候你已经知道自己在放弃什么了。）

---

## 四、学习方法：每个里程碑怎么走

**这是全文最重要的一节。方法比路线更重要。**

### 固定的五步循环

```
① 先写"这一步可能怎么坏"清单     ← 写代码之前！
② 写出能抓住这些坏的测试（红灯）
③ 实现到绿灯
④ 对照现成清单，补你没想到的
⑤ 复盘：清单里哪几条是你自己想到的？哪几条是抄来的？
```

**第 ① 步是关键，也是最反直觉的一步。**

普通做法是：写代码 → 出 bug → 修。
这个做法是：**先假装自己是攻击者，列出这个功能会怎么坏，再动手。**

你已经有了做这件事的原料——`flow-pay-backend/docs/ai/knowledge/` 和
`flow-pay-merchant/docs/knowledge/` 里那 313 份清单。**每个里程碑开始前，先翻对应的那几份。**

### 第 ⑤ 步为什么重要

**记录"哪几条是你自己想到的"，是唯一能看出你在进步的指标。**

M0 的时候可能 10 条里你想到 2 条。M4 的时候如果还是 2 条，说明方法有问题；
如果变成 7 条，说明你真的把清单内化了。

**每个里程碑结束写一份 `docs/retro/M<n>.md`：**

```markdown
## M<n> 复盘

- 我事先想到的坏法：（列表）
- 清单补上的：（列表）
- 实际踩到但两边都没预料的：（列表）  ← 这些是你自己的事故簿，最值钱
- 下次要提前问的问题：
```

### 关于 AI（也就是我）在这里的角色

**一条硬规则：不要让我一次性生成一个里程碑的全部代码。**

**因为那正好跳过了学习发生的地方。** 你学到东西的时刻，是在
「我以为它对」和「它其实错了」之间的那个缝隙里。AI 一把生成的代码直接跳过了缝隙。

| 该让 AI 做 | 该你自己做 |
|---|---|
| 讲解概念、原理、别人的设计为什么那样 | **写第 ① 步的坏法清单** |
| 找资料、拉清单、配事故案例 | 写测试 |
| 写脚手架、配置、样板代码 | **写核心逻辑**（账本、nonce、reorg 处理） |
| Review 你写的代码，指出问题 | 决定架构取舍 |
| 解释报错、解释新版本的行为差异 | 调试（我陪着，你主导） |

> **我的定位：教练 + 资料员 + 陪练，不是代打。**

---

## 五、里程碑

| # | 里程碑 | 核心难点 | 预估 | 必做? |
|---|---|---|---|---|
| **M0** | 账本地基 | 并发下的正确性 | 1–2 周 | ✅ 核心 |
| **M1** | 对外 API（照币安/OKX） | 签名、防重放、限频 | 1 周 | ✅ 核心 |
| **M2** | 区块索引器 | **重组 reorg** | 2 周 | ✅ 核心 |
| **M3** | 收款 Pay-In | 地址派生、确认数策略 | 1 周 | ✅ 核心 |
| **M4** | 付款 Pay-Out | **nonce 管理**、私钥 | 2 周 | ✅ 核心 |
| **M5** | 对账 | 三种差异的发现 | 3–5 天 | ✅ 核心 |
| **M6** | 上线 | 部署八环节 | 3–5 天 | 建议 |
| **M7** | 攻防复现 | 复现真实事故 | 持续 | 加分 |

**总计约 2–3 个月业余时间。** 不要赶——M0 花两周是值得的，它是后面全部的地基。

---

### M0 · 账本地基（不碰链、不碰网络）

> **目标：一个在 100 线程并发下也不会算错账的复式记账账本。**

这一步**故意不碰任何外部系统**。因为你要先证明：在最可控的环境里，你能写出不会错的账。

#### 会踩到的坑（预告，**先别看答案**）

<details>
<summary>点开前，先自己列一份清单存到 docs/retro/M0-before.md</summary>

1. 金额用 `double` → `0.1 + 0.2 ≠ 0.3`
2. 金额拆成"整数部分 + 小数部分"两个字段 → **Google Ads 把 $250 算成 $25,000 就是这么来的**
3. 余额检查和扣款不在同一个事务/锁里 → check-then-act，并发下超额扣款
4. `SELECT balance` 再 `UPDATE balance = ?` → 丢失更新
5. 幂等键只在应用层查，不加 UNIQUE 约束 → 并发下重复入账
6. 借贷两条分录分两个事务写 → 中间崩了，账不平
7. 转账给自己 → 余额不变但产生两条分录，某些统计会翻倍
8. 金额为 0 或负数的转账 → 没校验就能"反向转账"
9. 跨币种转账 → 数值直接比较（flow-pay `e8fd34e` 踩过）
10. 余额存在 `account.balance` 列，和分录求和不一致 → 没人发现
11. 加锁顺序不固定 → 死锁
12. `NUMERIC(38,18)` 溢出 → 悄悄截断还是报错？

</details>

#### 学什么

| 主题 | 具体 |
|---|---|
| **复式记账** | 为什么每笔转账要写两条分录；借贷符号约定；`SUM == 0` 这个不变量 |
| **金额类型** | `NUMERIC(38,18)` vs `DECIMAL(20,8)` vs `BIGINT`（分）vs `double`。为什么是 18 位小数 |
| **事务隔离** | `READ COMMITTED` / `REPEATABLE READ` / `SERIALIZABLE` 的真实差异；丢失更新、脏读、幻读 |
| **加锁** | `SELECT ... FOR UPDATE`（悲观）vs 版本号（乐观）；死锁与加锁顺序 |
| **幂等** | 为什么幂等键必须是**数据库 UNIQUE 约束**而不是应用层查询 |
| **约束下沉** | `CHECK (amount > 0)`、`CHECK (debit <> credit)`——**能让数据库守的不变量，不要交给应用** |
| **余额** | 实时求和 vs 物化余额列；一致性怎么保证 |

#### 对照清单（你已经有的）

| 清单 | 位置 |
|---|---|
| 复式记账为什么这么设计 | `flow-pay-backend/docs/ai/knowledge/tigerbeetle/concepts/debit-credit.md` |
| 余额不能为负这类约束 | `tigerbeetle/recipes/balance-invariant-transfers.md`、`balance-bounds.md` |
| 记错账要冲正 | `tigerbeetle/recipes/correcting-transfers.md` |
| 金额别用浮点 | `falsehoods/falsehoods-about-prices.md` |
| 断言与防御式编程的纪律 | `tigerbeetle/TIGER_STYLE.md` |
| 并发 / 竞态 / TOCTOU | `owasp-wstg-business-logic/04-*Process_Timing.md` |

#### 事故配对

> **Google Ads 的 $25,000 假钱**
> 内部测试用的 $250 优惠券，被系统算成了 $25,000。根因：**金额被拆成"元"和"分"两个字段**，
> 某段代码把两个字段拼错了位。
> —— 索引在 `falsehoods/awesome-falsehood.md`，标题 *Twenty five thousand dollars of funny money*

> **Knight Capital（2012-08-01）**
> 部署时 8 台服务器有 1 台没更新到新代码，旧代码复用了一个被重新赋予新含义的开关位。
> **45 分钟亏掉约 4.4 亿美元**，公司当年就被收购。
> —— 这个案例在 M6（部署）会再出现一次。

#### 验收标准（必须"能抓住你"）

这一步的验收**不是"功能能跑"**，是三个机制：

```
✅ 1. 不变量检查器
   SELECT currency, SUM(amount) FROM entry GROUP BY currency
   → 每一行必须是 0。任何时刻跑都必须成立。

✅ 2. 并发压测
   100 个线程 × 每线程 100 次随机转账，跑完检查：
   - 不变量成立
   - 没有账户余额为负
   - 转账笔数 == 预期笔数（没有丢、没有重）

✅ 3. 幂等测试
   同一个 idempotency_key 并发提交 50 次
   → 数据库里必须恰好 1 笔
```

**这三个测试的骨架我已经写好了，是红灯状态。你的 M0 就是把它们变绿。**

#### 加分题

- 加一个物化的 `account.balance` 列，写测试证明它**永远**等于 `SUM(entry.amount)`
- 把不变量写成 Postgres 的 `CHECK` 约束或触发器，让数据库自己拒绝坏数据
- 制造一次死锁，然后修好它（提示：按账户 id 排序加锁）
- 用 Framework 7 的 JSpecify 注解标注账本 API，让"可能为空"变成编译期可查

---

### M1 · 对外 API：照着币安 / OKX 的规范设计

> **目标：一套商户能安全调用的 REST API。学的是"成熟服务商的 API 长什么样"。**

#### 为什么值得抄他们

币安和 OKX 的 API 每天扛着几百万笔请求和真金白银，**他们的设计里每一条都是被攻击过之后加上去的**。你不需要重新发明，照抄就是最快的学习路径。

#### 两家的签名机制对比（本节精华）

**Binance：**

```
signature = HMAC-SHA256(queryString + requestBody, secretKey)
请求头：X-MBX-APIKEY: <apiKey>
参数里带：timestamp=<毫秒>&recvWindow=5000&signature=<签名>
```

**OKX v5：**

```
prehash = timestamp + method + requestPath + body
OK-ACCESS-SIGN = Base64(HMAC-SHA256(prehash, secretKey))
请求头：OK-ACCESS-KEY / OK-ACCESS-SIGN / OK-ACCESS-TIMESTAMP / OK-ACCESS-PASSPHRASE
```

**差异在哪，为什么重要：**

| | Binance | OKX | 影响 |
|---|---|---|---|
| 签名覆盖 HTTP method | ❌ | ✅ | OKX 下把 `GET /order` 改成 `DELETE /order` 会签名失效 |
| 签名覆盖 path | ❌（经 query 间接覆盖） | ✅ 显式 | OKX 更难被路径替换攻击 |
| 凭证要素 | key + secret | key + secret + **passphrase** | 泄露 key/secret 还不够 |
| 防重放 | `timestamp` + `recvWindow`（默认 5s） | `OK-ACCESS-TIMESTAMP` + 服务端偏移校验 | 两家都靠时间窗 |

> **建议：实现 OKX 那一套（更严），然后写测试证明 Binance 那一套在什么情况下会被绕过。**
> 这个对比测试本身就是最好的学习。

#### 还要抄的六件事

| 设计 | 他们怎么做 | 为什么 |
|---|---|---|
| **金额全用字符串** | `"price": "0.00001234"` 不是 number | **JSON number 在 JS 里是 double**，18 位小数必然丢精度（flow-pay 的 snowflake ID 是同一个坑） |
| **客户端幂等键** | 币安 `newClientOrderId` / OKX `clOrdId` | **让调用方决定幂等边界**，而不是你猜 |
| **统一响应信封** | OKX：`{"code":"0","msg":"","data":[]}` | 客户端只需要一套解析逻辑 |
| **分段错误码** | 币安 `-1xxx` 通用 / `-2xxx` 交易 | 客户端能按段做重试策略 |
| **权重限频** | 币安每接口有 weight，响应头回 `X-MBX-USED-WEIGHT-1M` | 简单 QPS 限不住——查一次全量比查一条贵 100 倍 |
| **一个 header 切环境** | OKX：`x-simulated-trading: 1` 走模拟盘 | 比"改 base URL"更不容易搞错 |

> ⚠️ **API 细节会变。** 上面是我知识范围内的形态，动手前请查一次官方文档：
> Binance <https://developers.binance.com> · OKX <https://www.okx.com/docs-v5/>

#### 验收标准

```
✅ 重放：同一个签名请求发两次 → 第二次必须被拒
✅ 时钟：timestamp 偏移超过 recvWindow → 必须被拒
✅ 篡改：body 改一个字符 → 签名必须失效（这就是为什么要签 body）
✅ 换方法：GET 改成 DELETE，签名不变 → 必须失效
✅ 限频：超过配额 → 429 + Retry-After，并且计数是原子的
   （回想 flow-pay 的 9f22aac：get+set 非原子，5 次上限能被并发绕过）
✅ 幂等：同一 clientOrderId 并发提交 → 只产生一笔
```

#### 对照清单

- `owasp-cheatsheets/Transaction_Authorization.md` — 敏感操作的二次验证
- `owasp-cheatsheets/REST_Security.md`
- `owasp-cheatsheets/Mass_Assignment.md` — 哪些字段允许客户端传
- `owasp-cheatsheets/Insecure_Direct_Object_Reference_Prevention.md`
- `owasp-wstg-business-logic/05-*Function_Can_Be_Used_Limits.md` — 限次

---

### M2 · 只读上链：区块索引器

> **目标：把 Sepolia 测试网上的 ERC-20 转账，可靠地索引进你的库。**
>
> **这是整条路线上最难、也最值钱的一节。**

#### 为什么最值钱

因为**区块重组（reorg）是 check-then-act 问题的终极形态**：

```
你以为的：区块 100 确认了 → 这笔转账是事实 → 入账
现实：     链回滚了 → 区块 100 变成另一个区块 → 那笔转账从未发生
           但你已经给用户加过钱了
```

**你在 flow-pay 学到的所有"先检查后行动"的教训，在这里会以最纯粹的形态重现一次。**

#### 学什么

| 主题 | 具体 |
|---|---|
| JSON-RPC 基础 | `eth_blockNumber` / `eth_getBlockByNumber` / `eth_getLogs` / `eth_getTransactionReceipt` |
| 事件日志 | ERC-20 `Transfer` 事件的 `topic0`；`topics` vs `data`；indexed 参数 |
| **确认数** | 为什么不能看到就入账；不同金额用不同确认数 |
| **重组** | 用 `blockHash` 而不是 `blockNumber` 做主键；发现重组后怎么回滚 |
| 游标推进 | 断点续传；崩溃后不漏块不重复 |
| RPC 不可靠 | 节点返回旧数据；多节点不一致；限频；超时 |
| 批量与分页 | `eth_getLogs` 的区块范围上限；大范围查询超时 |

#### 事故配对

> **Ethereum Classic 51% 攻击（2019-01）**
> 攻击者重组了链，**双花了已被交易所确认入账的充值**。多家交易所损失。
> 根因：确认数设置得太低，以为"确认了"就是最终的。

> **Bitcoin v0.8 分叉（2013-03-11）**
> 客户端版本升级导致共识分裂，**两条链并存约 6 小时**，期间存在真实的双花窗口，
> 交易所被迫暂停充值。

> **教训是同一个：区块链没有"最终确认"这个绝对概念，只有"重组概率随确认数下降"。**
> 你的系统必须把"已确认"当成一个**可撤销**的状态，而不是终态。

#### 验收标准

```
✅ 幂等重放：把游标手工回退 1000 个区块重跑
   → 数据库最终状态必须和回退前完全一致

✅ 模拟重组：手工改掉某个区块的 blockHash
   → 系统必须发现，并回滚该区块之后的所有派生数据

✅ 崩溃恢复：索引到一半 kill -9
   → 重启后不漏块、不重复

✅ RPC 撒谎：mock 一个返回旧 blockNumber 的节点
   → 游标不能倒退

✅ 对账：随机抽 100 个区块，链上 log 数 == 库内记录数
```

#### 知识补充（2026-09-02 已完成）

**这一块原有的 313 份清单没有覆盖。** 已从 web 采集并本机实测，落在
`docs/knowledge/m2-block-indexer.md`（按"谁在什么时候读"组织，每条带出处）。要点：

- JSON-RPC 的 `safe` / `finalized` 标签（合并后才有）：Sepolia 实测分别落后 35 / 66 个区块
- EIP-20 原文：Transfer **MUST** 含零值；铸币从 `0x0` 是 **SHOULD**；`decimals` **OPTIONAL**；调用方 **MUST** 处理 `false`
- weird-erc20：fee-on-transfer / rebasing / no-revert-on-failure / uint256.max 转账 → **事件 value ≠ 实际到账**
- 提供商 `eth_getLogs` 上限各不相同（2 000 区块 / 10 000 条 / 50 区块…），且会**静默漏日志**
- 确认数：Binance 12，OKX 入账 32、**提现更高**；PoS 最终性 ≈ 2 epoch ≈ 12.8 分钟
- 重组事实：信标链 7 块（2022-05）、ETC 100+ 块（2019-01，$1.1M）、ETC 7 000+ 块（2020-08）
- 索引器重组模式：存 hash+parentHash → 找共同祖先 → 回滚派生数据 → 重放；外部副作用回滚不了；Envio 默认最大回滚 200 块
- **一条清单里没有、从 uint256 与 `NUMERIC(38,18)` 直接推出来的坑**：整数部分只有 20 位，装不下大额代币的原始单位
- 选型：web3j 5.0.3 已用 Jackson 3（不与 Boot 4 冲突）但拖 OkHttp/RxJava2/WebSocket/**AWS KMS SDK**；裸 JSON-RPC + 30 行解码是更薄的路

「这一步会怎么坏」的提问在 `docs/retro/M2-before.md`（20 问，只问不答）。

#### 进度

- ✅ **M2-①**（2026-09-02）裸奔版：`JsonRpcClient` + `EthRpc` + `TransferLogDecoder`，Sepolia 实测解码正确；离线测试 + 环境变量门控的探针
- ✅ **M2-②**（2026-09-02）落库 + 书签：V9（`chain_transfer_log` / `indexer_cursor`）、`BlockIndexer`（事件与书签同事务、锁后重读、网络在事务外、重组与解码失败即停）、11 条契约测试对着验收标准写；两面墙（去掉事务 → 崩溃测试红；去掉重读 + 带期望值的 UPDATE → 慢实例把书签推回去）；Sepolia 落库探针对账：链上 3 条 = 库里 3 条
- ✅ **M2-③**（2026-09-02）三态确认：V10（单行 `chain_head` + 视图 `chain_transfer_confirmation`，等级算出来不存，ORPHANED 不出现，confirmations 夹到 0）、`ChainHeadTracker`（finalized 倒退或换哈希即停；safe / latest 倒退保留旧值；乱序拒绝）、`ChainIndexerScheduler`（放书签 → 刷新头 → 推批到追平；瞬时失败 RETRY_LATER，结构性失败 HALTED 且不再碰节点；配了 `start-block` 才自动放书签）；15 条测试先红后绿；两面墙（去掉 finalized 倒退检查 → 红；视图不过滤 ORPHANED → 红）；Sepolia 落库探针打印三个头与等级分布
- ✅ **M2-④**（2026-09-02）重组回滚：V11 审计表 `chain_reorg`（depth 生成列，只增）、`ReorgRecovery`（候选 = 书签之下有日志的块 + finalized 头，降序问链找祖先；标废、退书签、记审计同一事务；锁内核对号和哈希；地板 finalized）、写入改成复活型 upsert、轮询 REORGED；FakeChain 学会真正的分支切换（日志跟着分支走）；9 条测试先红后绿；两面墙（标废挪到事务外 → 同生同死测试红；upsert 改回 DO NOTHING → 翻回来测试红）
- ✅ **M2-⑤**（2026-09-03）RPC 不信任：JsonRpcClient 整段超时 + 正文封顶（假节点滴流 / 无限正文两条测试）；BlockIndexer 窗口对半分、翻倍回、单块仍败即停；V12 审计表 `chain_reconcile` + 书签 `start_block`；`LogReconciler` 抽样对账（回执为事实源，两个节点都点头才补录 / 标废，否则 disputed）；`ChainHeadTracker` 用审计节点核对 finalized；21 条测试先红后绿；三面墙（补录不要主节点点头 → 红；抽样不以 finalized 为界 → 红；正文不封顶 → 红）；Sepolia 探针：主节点 publicnode、审计节点 tenderly 真实对账
- ✅ **M2-⑥**（2026-09-03）代币谎言：V13 白名单 `chain_token`（预置 LINK，decimals 0..18）；第一次 `eth_call`——`Abi`（选择器、地址编码、uint / string 解码，已知答案从 ABI 规范手写）、`Erc20Calls`（decimals / symbol / balanceOf，问不到返回空）；`TokenAmounts.toLedger` 精确换算、装不下就 `AmountOverflowException`；`TokenRegistry` 登记时问链、使用前核对；轮询第一次推批前核对代币；34 条测试先红后绿；两面墙（不查整数位 → 红；不核对 decimals → 红）；探针问真实 LINK：decimals 18、symbol LINK
- ✅ **M2-⑥ 补丁**（2026-09-03）两个评审代理（Java 质量、安全）独立审 ⑥ 的改动，各自抓到同一处：`Abi.decodeString` 把对方给的 32 字节长度字先收窄成 int 再检查——2^31 抛 ArithmeticException、2^30 乘 2 溢出成负数绕过边界检查，两者都不是调用方接的 IllegalArgumentException；改成先在 BigInteger 上和实际字节数比。同一轮还抓到：`TokenRegistry.register` 先查再插（check-then-act 第 8 次，在自己刚写的代码里）→ `INSERT … ON CONFLICT DO NOTHING` 让主键裁决；symbol / note 没有长度上限 → Java 侧 64 / 500 + V14 CHECK；「核对代币时节点瞬时失败应 RETRY_LATER」没有测试守着 → FakeChain 加 `beforeCall` 钩子补上，拆墙（先标记再核对）证明它能红。新增 `Erc20CallsTest` 钉住「问不到 vs 没问到」的分界。红 41 跑 5 败 1 错 → 绿。教训：评审要独立于作者，两个独立评审撞到同一处 = 高置信；不可信的数先在大整数上比完再收窄
- ✅ **真环境验收演练**（2026-09-03，开发库 + Alchemy 主节点 + tenderly 审计节点）：把书签从 11626939 手工退到 11625939（1000 块）重放，重放到 11626143 时对 JVM `kill -9`（上一秒书签还在变，正处一次轮询中间），重启后继续追到 N。回退前后比对：范围 (R, N] 32 行、范围 ≤ R 16 行，两段行数与整行 md5 一字不差；`chain_reorg` 与 `chain_reconcile` 均 0 → 0；非 CANONICAL 行 0；两次运行零告警。验收标准「幂等重放」「崩溃恢复」至此有了真环境证据，不只靠 FakeChain
- ✅ **M2-⑥ 补丁 2**（2026-09-03）用提问模板全面自查（5 个子代理按节分派 + 主会话实验），两条严重都先在只读克隆里受控复现再修：① **撕裂的快照**——取一批要问节点三次，父哈希只在第一次核对，重组落在三次之间就把旧分支的行留成 CANONICAL、视图判 FINAL；修法是落库前三道归属核对（块号在范围内、每条日志的块哈希等于该块的头、重读 block(from) 未变），撒谎的节点塞进来的范围外 / 哈希不符的日志也被同一道门挡住。② **对账只比坐标不比金额**——`LogCoordinate` 只装坐标，同坐标不同金额判「干净」，复活型 upsert 只改 status 让第一次写入的说法永远留下；修法是比整条载荷，内容不同一律 disputed 且代码不改金额，重放遇到同坐标不同内容就停下。8 条测试先红后绿（红灯即拆墙）；FakeChain 加 `beforeBlock` / `injectIntoGetLogs`。教训：只核对了容器（区块头、坐标）没核对内容；测试集合缺的永远是「有，但内容不一样」那个输入；动钱的边界（M3 入账）还要再核一次，不把正确性押在索引器一处
- ✅ **M2-⑥ 补丁 3 · 第一组「白名单与归属」**（2026-09-04）质询扫描七条「中」里的第一组。白名单原来只挂在轮询器的一个入口上、检查结果缓存到进程寿命、写入路径信节点按地址过滤、主代码没有任何地方写 DISABLED：有关卡，但一个入口、活到进程结束、绕过静默。修成三层：V15 给事件表与书签表加指向 `chain_token` 的外键（谁都绕不开）；`BlockIndexer` 落库前比对每条日志的合约地址（`injectIntoGetLogs` 模拟不按地址过滤的节点）；轮询每轮 `requireUsable`、上链核对每进程一次。书签加 `token` 列并回填（白名单恰好一枚时无歧义，多枚则迁移停下由人定），配置换币沿用书签名 = 停下。4 条测试先红后绿。教训：判断一道关卡的效力问三个数字——挂在几个入口、检查结果活多久、绕过时会不会报错
- ✅ **M2-⑥ 补丁 3**（2026-09-04）质询扫描的第二到第五组「中」：① 可观测出口——V16 `indexer_state`，停机落库、重启不算恢复、人改回 RUNNING 才算；连续瞬时失败与审计跳过到阈值 = DEGRADED 每轮报错；401/403 单列为 `RpcAuthException` 直接停下；只读接口 `GET /admin/v1/indexer`；运行手册 `docs/runbook/chain-indexer.md`。② 翻译层——`EthRpc` 缺字段指名拒绝，`EthRpcTest` 喂真实 Sepolia 响应，`ChainIndexerBootSmokeTest` 让容器真装配一次。③ 审计独立与密钥——`RpcEndpoint` 不回显 URL，同主机的审计节点拒绝启动，启动日志写明单/双节点。④ 守卫——对账阶段 finalized 分歧要从轮询器停下、幻影被主节点确认要留 disputed、重组恢复的两条未测分支、十批上限、`ControllerBoundaryTest`。红 12 → 绿；两面墙（catch 扩到 RuntimeException、去掉主节点点头）各自变红

**M2 六步全部完成（2026-09-02 至 09-03）。** 复盘 `docs/retro/M2.md`（§3 第 ⑤ 步：M2-before 的 20 问哪几条是自己想到的）由用户写。

---

### M3 · 收款 Pay-In

> **目标：真的从测试网转一笔进来，余额正确增加。**

#### 学什么

- **HD 钱包**：BIP-32 / BIP-44 派生路径；从一个种子派生无限地址
- **一址一单 vs 地址复用**：各自的优劣和归属难题
- **memo 链**：XRP / XLM / EOS 靠 memo 归因——**flow-pay 的 `65372f8a` 已经踩过**（丢弃 memo = 收不到账的地址）
- **最小入账金额**：灰尘攻击；手续费超过金额的入账
- **确认数策略**：小额 3 个确认，大额 30 个——**这是风险与体验的取舍，不是技术问题**
- **代币的坑**：
  - fee-on-transfer token（转 100 到账 98）
  - rebasing token（余额自己会变）
  - 老 ERC-20 转账失败返回 `false` 而不 revert

#### 验收标准

```
✅ 从 Sepolia faucet 领币 → 转到你派生的地址 → 余额正确增加
✅ 同一笔 tx 被索引两次 → 不能双记
✅ 确认数不够时余额不可用，够了才可用
✅ 转入未支持的代币 → 不能崩，要有明确处理
✅ 转入 0 或极小金额 → 按策略处理，不能产生负手续费
```

#### 规划与前置知识（2026-09-04 / 09-06）

`docs/knowledge/m3-payin.md`：M2 铺好的路、代码里已下的判决、地址派生（xpub 只看不花）、归属模式、
FINAL 门槛、镜像账户 `chain:custody:<TOKEN>`、金额换算、`balanceOf` 第二意见、分步 ⓪–⑥。
「这一步会怎么坏」30 问在 `docs/retro/M3-before.md`，只问不答。
六个取舍（2026-09-06 由用户拍板，按建议）：系统角色现在升；一户一币一址；BouncyCastle 自写派生；
离线小工具产 xpub；币种名用 symbol 并对 ACTIVE 代币唯一；`balanceOf` 核对要求相等。

#### 进度

- ✅ **M3-⓪**（2026-09-06）地基：系统权限从「会话变量开关」变成「连接身份」。`db/init/01-roles.sql` 加第二个登录角色 `chainpay_system`（BYPASSRLS、非超级用户、非属主）；V17 只授权（账本三表可读写不可删，merchant 与链表只读，序列可用）并在角色缺失时用一句中文说清怎么办；`ledger/system/SystemLedger`：独立 Hikari 池 + 自己的事务模板 + 绑在系统连接上的一份账本，对外只有 `inTransaction(...)`，建池即自检身份。故意不做成第二个 `DataSource` / `JdbcClient` bean（Boot 自动配置会整体退让）。测试 6 条先红后绿（身份、免会话变量看全表、跨租户记 DEPOSIT、回调抛异常整体回滚、系统身份也删不了账本、配错身份拒绝启动）+ `ControllerBoundaryTest` 加禁 `SystemLedger`。两面墙：去掉 BYPASSRLS → 六条在装配阶段全被拒；拆掉事务模板 → 回滚那条红。全套 237 跑 0 败 0 错 2 跳。**一处操作失误记下来**：恢复墙一时用了 `git checkout`，把同一轮还没提交的角色定义一起抹掉，墙二第一次全错是因为角色不存在——未提交的改动只能用反向编辑恢复，`git checkout` 只回到已提交版本
- ✅ **M3-①a**（2026-09-07）地址派生核心（零私钥）：`chain/wallet`——`Keccak256`（BouncyCastle 的 Keccak，不是 JDK 的 SHA3-256）、`EthAddress`（公钥 → 地址、EIP-55 校验和）、`Base58Check`、`Secp256k1`、`ExtendedPublicKey`（xpub 解析 / 序列化 / **CKDpub**）、`DepositAddressDeriver`（账户层 xpub → 0/i，深度不是 3 就拒绝）；私钥数学 `ExtendedPrivateKey` / `Bip39` 只给 `XpubTool` 与测试，`WalletBoundaryTest` 扫源码守着；`tools/xpub.sh` 断网算 xpub。已知答案全来自规范原文：BIP-32 向量 1 与 2、EIP-55 八个地址、BIP-39 两条向量（passphrase TREZOR）、Hardhat 公开助记词的前三个地址（m/44'/60'/0'/0/i）。14 条先红后绿；两面墙：EIP-55 阈值 8 改 7 → 校验和与 Hardhat 地址红；CKDpub 去掉 + K_par → 公钥派生向量与 Hardhat 红、纯私钥派生仍绿。全套 251 跑 0 败 0 错 2 跳。**教训**：第一次经 WebFetch 的概括模型取向量，m/0'/1/2' 的 xprv 被抄错一个字母（m → M），Base58Check 一验就露馅；改为 curl 取原文、脚本逐字核对全部 12 个常量。已知答案的来源本身也要可信，转述一次就不算原文。表、RLS、分配服务在 ①b
- ✅ **M3-①b**（2026-09-07）收款地址分配：V18 `deposit_address`（address 主键小写、`UNIQUE (merchant_id, token)` 一户一币一址、`UNIQUE (derivation_index)`、外键指向 merchant / chain_token / account、RLS 策略同账户表、序列取号、系统角色只读）+ `chain_token` 上 ACTIVE symbol 的部分唯一索引（取舍 ⑤）；`chain/deposit`：`DepositConfig`（设了 `CHAINPAY_DEPOSIT_XPUB` 才装配，启动日志打 xpub 指纹与 0/0 地址）、`DepositAddressService.allocate`（白名单核对 → 商户核对 → 已有即返回 → 确保账户 → 取序号 → 派生 → `insertIfAbsent`，输给并发的一方读回赢家；地址主键冲突 = xpub 配错，报出）。测试基类钉入 Hardhat 账户层 xpub，于是「商户申请地址 → 库里那一行」整条链路有已知答案：序号 0 就是 Hardhat 的第一个地址。9 条先红后绿（首次分配、幂等、八线程并发恰好一行、**确定性插队**、RLS 三个方向、白名单拒绝、停用商户拒绝、地址被占用报 xpub、ACTIVE symbol 唯一）；两面墙：去掉 ENABLE ROW LEVEL SECURITY → 租户边界那条红；去掉 ON CONFLICT → **第一次没红**——八线程那条实际是串行撞上去的，第一个提交完别的才到「先查」，全走了「已有即返回」，等于没守东西。补了一条确定性的插队测试（A 取走序号 0 后停住，B 先插入提交，A 的插入被吞掉、读回 B 的地址、序号 0 作废；预建账户避免 A 未提交的账户行把 B 卡成死锁）才红。**不强制交错顺序的并发测试证明不了任何事。**全套 261 跑 0 败 0 错 2 跳
- ✅ **M3-②**（2026-09-07）入账：V19 `deposit`（`transfer_log_id` 唯一 = 一条日志只处理一次，`transfer_id` 唯一 = 一笔转账只属于一条入账，CHECK 只允许 CREDITED 带 transfer_id、HELD 必须有原因，RLS 商户只读，系统身份可写）；`DepositPoster`：核对在事务外（两个节点的块头哈希与 finalized 都点头才算 FINAL，同一轮复用）→ 判决纯计算（零值忽略、溢出 HELD）→ 系统池一个事务里**先占坑再动钱**（POSTING 占坑 → `ledger.transfer` 幂等键为链上坐标、借 `chain:custody:<SYMBOL>` 贷商户账户、occurred_at 为区块时间 → CREDITED）；`DepositPostingScheduler` 独立于索引器，30 秒一轮。测试 14 条先红后绿，链是 FakeChain 但日志由真的 BlockIndexer 索引、链头由真的 ChainHeadTracker 刷新、地址由真的分配服务派生：记账与三张表、没到 FINAL 不记、重放无害、过期候选不双记、审计节点分歧 HELD 且下一笔照记、主节点改口 HELD、瞬时失败提前结束下一轮照记、溢出 HELD、零值忽略、陌生地址不是候选、DISABLED 地址不是候选、崩在记账后整个事务回滚（连镜像账户都不留）、RLS、**另一个实例已判 HELD 则一分钱不记**。两面墙：顺序换成先记账再占坑 → 最后那条红；去掉审计节点哈希核对 → 审计分歧那条红。全套 275 跑 0 败 0 错 2 跳。**记下一处脚手架失误**：FakeChain 要先有块才能挂日志，13 条一起错在 `addTransfer`，和业务无关
- ✅ **M3-③**（2026-09-07）策略与例外：V20 `chain_token.min_deposit`（账本单位，0 = 不限）与状态词表加 HELD_ERROR / APPROVED；`DepositPoster` 判决顺序：两节点块头与 finalized → 零值 → 溢出 → 灰尘 → **balanceOf 第二意见**（两个节点在那一块的余额都必须等于事件累计的转入减转出，对不上或问不到 = HELD_BALANCE_MISMATCH）；失败分瞬时（节点、数据库 → 这一轮提前结束）与结构性（这一笔 HELD_ERROR 带异常原文，队列继续）；人工路径：HELD → 人改 APPROVED 并留名留因 → 任务下一轮 `UPDATE … WHERE status = 'APPROVED'` 原子重占坑、同一幂等键记账，记不上改回 HELD_ERROR。FakeChain 学会按块高回答余额；测试脚手架抽成 `AbstractDepositPostingTest`，`pay()` 挂日志的同时在两条链上定义该块余额。10 条先红后绿（余额相等照记、扣费代币 HELD、审计余额不同 HELD、问不到 HELD、问余额瞬时失败重试、灰尘拒绝与边界、结构性异常 HELD_ERROR 不卡队列、数据库瞬时失败重试、核准后照记且不再核对、核准行原子重占坑）；② 的崩溃用例改成新契约（回滚后 HELD_ERROR，核准后照记）。两面墙：去掉余额比对 → 扣费代币那条红；去掉最小入账额 → 灰尘那条红。全套 285 跑 0 败 0 错 2 跳。运行手册 `docs/runbook/deposit.md`
- ✅ **M3-④**（2026-09-07）商户收款接口（照币安 / OKX 的 deposit-address 与 deposit-history）：`POST /api/v1/deposit-addresses`（幂等，EIP-55）、`GET /api/v1/deposit-addresses`、`GET /api/v1/deposits?token&status&limit`（已处理的行 UNION 在路上的钱，PENDING 带 level 与 confirmations，金额字符串，HELD 不露原因）、`GET /api/v1/deposits/balance?token`（available 与 pending）；错误码 2008 `TOKEN_NOT_SUPPORTED`；`DepositQueryRepository` 走应用连接、整段在 asMerchant 里，链表没有 RLS 但每条查询都经地址表连接。测试 5 条真 HTTP 真签名先红后绿（幂等申请与 EIP-55、白名单外 400/2008 与不成形 400/2001 与匿名 401、列表 PENDING/CREDITED 与 level/confirmations 与别的商户看不到与过滤、余额 available/pending 记账前后、HELD 不露原因）。两面墙：在路上那一段不经地址表 → 别的商户看到了 acme 的转账，红；列表接口不进 asMerchant → RLS fail-closed 自己也查不到，红。全套 290 跑 0 败 0 错 2 跳
- ✅ **M3-⑤**（2026-09-08）真环境演练：管理接口发凭证 → `tools/api.py`（会签名的 curl，凭证只从环境变量来）申请 LINK 地址（序号 0 = MetaMask 的 Account 1，所以付款方是水龙头合约）→ 水龙头打 25 LINK → 商户接口 SEEN（10:24:03 UTC）→ SAFE（10:35:38）→ FINAL（10:42:10）→ CREDITED（10:45:57），pending 25 变 available 25，账本 transfer 13 加两条分录。两条演练：① 对 `entry` 表持 EXCLUSIVE 锁，让入账事务在占坑、建镜像账户、写转账之后卡在写分录，`kill -9`，数据库整体回滚（三张表 0 改动），重启 31 秒恰好记一次；第一版锁在 `transfer` 上，占坑那条 INSERT 就被外键的 RowShareLock 挡住——外键让锁传导到引用表。② 书签退回 11659777 重放：820 块重放后范围内 155 行 md5 不变、无标废无新增，deposit / transfer / entry / 余额全不变，`ledger_invariant` 0 违反（三层唯一约束：日志坐标、transfer_log_id、幂等键）。真环境送上门的三件事：Tenderly 中断 23 分钟（第 30 次失败降级、恢复后自动回 RUNNING）、深度 3 的重组自动退 3 块重放、Alchemy 免费层 getLogs 限 10 块让「对半分再翻倍」震荡（追块每轮 60 到 100 块，为完成演练把开发库书签前跳 31k 块；改进方向记在 CLAUDE.md，未动代码）。另一个发现：入账任务与索引器共用单线程调度器，一个卡住另一个也停（M6）。README 加「手工调商户接口」

**M3 ⓪ 到 ⑤ 全部完成（2026-09-06 至 09-08）；⑥ webhook 推迟到 M5 之后。** 复盘 `docs/retro/M3.md`（§3 第 ⑤ 步：M3-before 的 30 问哪几条是自己想到的）由用户写。

---

### M4 · 付款 Pay-Out

> **目标：从你的热钱包签名并广播一笔转账。**
>
> **难点是 nonce——这是并发问题最纯粹的形态。**

#### 为什么 nonce 是终极并发题

以太坊每个地址的交易必须**按 nonce 严格顺序上链**。

```
你并发发 3 笔提现：
  线程 A 读到 nonce = 5，构造交易
  线程 B 读到 nonce = 5，构造交易   ← 撞了
  线程 C 读到 nonce = 5，构造交易   ← 又撞了

结果：只有一笔能上链，另外两笔永久失败
更糟：如果 nonce 跳号（发了 5 和 7，没发 6），
      7 会永远 pending，后面所有交易全部卡死
```

**这是 check-then-act 的最纯形态，而且没有数据库事务能救你**——nonce 的真相在链上，不在你的库里。

#### 学什么

| 主题 | 具体 |
|---|---|
| **nonce 管理** | `pending` vs `latest` 的差异；本地 nonce 池；跳号恢复 |
| gas | EIP-1559 的 `maxFeePerGas` / `maxPriorityFeePerGas`；估算失败 |
| **卡单** | pending 太久怎么办：加速（同 nonce 更高 gas）/ 取消（同 nonce 转 0 给自己） |
| **私钥管理** | 绝不进代码、绝不进镜像（**呼应 Docker 那讲：镜像的层是可以扒出来的**） |
| 提现风控 | 地址白名单、二次验证、限额、冷却期——**学 OKX 的提币流程** |
| 幂等广播 | 同一笔业务提现不能广播两次 |

#### 事故配对

> **Ronin Bridge（2022-03）约 6.25 亿美元**
> 9 个验证者私钥里的 5 个被攻破。其中 4 个来自一次社工，第 5 个来自
> **一个本应临时授权、但一直没撤销的权限**。
>
> **教训：私钥管理 + 权限的生命周期。临时授权必须有过期时间。**

#### 验收标准

```
✅ 并发 10 笔提现 → nonce 不能冲突，全部上链
✅ 进程被 kill 后重启 → 不能重复广播已发出的交易
✅ 手工制造 nonce 跳号 → 系统必须能检测并恢复
✅ gas 不足导致卡单 → 能加速或取消
✅ 私钥不在代码里、不在镜像里、不在日志里（写个检查脚本）
✅ 提现到未白名单地址 → 必须拒绝
```

---

### M5 · 对账

> **目标：一个每天跑的脚本，能发现链上和库内的任何不一致。**
>
> **这一节最短，但它是前面所有里程碑的判官。**

#### 三种差异

| 差异 | 含义 | 通常原因 |
|---|---|---|
| 链上有，库内无 | **漏账** | 索引器漏块、代币未支持、确认数逻辑错 |
| 库内有，链上无 | **假账**（最危险） | 重组没回滚、手工改库、重复入账 |
| 两边都有，金额不符 | **算错** | 精度、fee-on-transfer、手续费归属 |

#### 事故配对（这个案例太完美了）

> **Crypto.com 误转事件（2021）**
> 本该退给一位用户约 100 澳元，实际转出约 **1047 万澳元**——退款金额栏被填成了账号。
> **关键不是转错，是没人发现：直到 7 个月后的例行审计才查出来。**
>
> **教训：没有对账，错误不会自己浮出来。** 系统会安静地错下去，直到有人从外部发现。

#### 验收标准

```
✅ 手工往库里注入一笔链上不存在的入账 → 对账必须报出来
✅ 手工删掉一笔链上存在的入账 → 对账必须报出来
✅ 手工改小一笔金额 → 对账必须报出来
✅ 对账任务失败本身要告警（"没跑"和"跑了没问题"必须能区分）
```

> 最后一条是 flow-pay 教你的：**沉默不等于正常。**

---

### M6 · 上线

> **目标：把它真的部署到一台机器上，走完部署八环节。**

复用你刚学的那一讲：构建 → 配置 → 密钥 → 迁移 → 分发 → 切换 → 验证 → 回滚。

重点补上 flow-pay 缺的那几格：

```
✅ 健康检查端点，且能区分"活着"和"能干活"
✅ 迁移失败 → 部署必须中止，不能带着半个 schema 启动
✅ 密钥从环境注入，镜像里 grep 不到
✅ 一条能回滚的路径，并且真的演练一次
✅ Dockerfile 有 .dockerignore、有非 root USER、有 HEALTHCHECK
```

---

### M7 · 攻防复现（持续）

> **目标：拿真实事故，在自己的系统上复现一遍。**

到这一步你已经有一个完整的系统了。现在去读事故报告，然后问：**"这个如果发生在我的系统上，会怎样？"**

资料源：

- **rekt.news** — 加密领域事故编年史，按损失金额排序
- **Immunefi** 的漏洞披露报告
- 各交易所的事后分析（post-mortem）
- **慢雾（SlowMist）区块链黑客事件档案库** — 中文，覆盖全

方法：挑一起事故 → 读根因 → 在自己系统里找同构的地方 → **写一个测试证明你有/没有这个问题**。

---

## 六、你已有的清单怎么用

你在 flow-pay 两个仓库里已经下载了 **313 份**参考资料。**对这个项目 80% 可直接复用：**

| 里程碑 | 直接可用的清单 |
|---|---|
| M0 账本 | `tigerbeetle/`（25 份，全部）、`falsehoods-about-prices.md` |
| M1 API | `owasp-cheatsheets/` 的 Transaction_Authorization、REST_Security、Mass_Assignment、Authentication、Session_Management |
| M2 索引器 | ⚠️ **需要新下载** |
| M3 收款 | `falsehoods-about-IBANs.md`（银行账户思路可迁移）、`owasp-wstg-business-logic/` |
| M4 付款 | `owasp-cheatsheets/Secrets_Management.md`、`Key_Management.md`、`Cryptographic_Storage.md` |
| M5 对账 | `tigerbeetle/recipes/correcting-transfers.md` |
| M6 上线 | `owasp-cheatsheets/Docker_Security.md`、`CI_CD_Security.md`、`correctness/strong-migrations-*.md` |

**建议：不要复制过来，直接读那边的路径。** 一份资料只维护一处。

---

## 七、明确不要做的事

| ❌ 不要 | 为什么 |
|---|---|
| **用主网真钱** | 测试网免费且完全够学。真钱只会让你不敢试错 |
| **一开始就写智能合约** | 那是另一条完整的技能树。先把后端做对 |
| **先做用户系统 / 后台 / KYC** | 那些是 CRUD，你在 flow-pay 已经会了 |
| **让 AI 一次生成整个里程碑** | 直接跳过学习发生的地方（见第四节） |
| **先搭前端** | 用 curl 和测试就够了。前端等 M5 之后再说 |
| **同时学新语言** | Java 你已经在学了。别同时开 Go/Rust 战场 |
| **追求功能完整** | **一个只支持 USDT 但绝对不会算错的系统，价值远超十个币种但会错账的系统** |

---

## 八、现在的状态与下一步

**已完成（本次）：**

- ✅ 项目骨架，Java 25 + Spring Boot 4.1.0
- ✅ PostgreSQL 18 的 docker-compose（端口绑 `127.0.0.1`）
- ✅ M0 的账本 schema（`V1__ledger.sql`）
- ✅ **三个红灯测试**：不变量、并发、幂等

**你的第一步：**

1. 读 `README.md`，把环境跑起来
2. **确认三个测试都是红的**
3. 在写代码之前，**自己写一份 M0 的"会怎么坏"清单**，存到 `docs/retro/M0-before.md`
4. 然后我们对答案，再开始实现

> **第 3 步不要跳过。** 它是这整份路线里唯一能测出你在进步的东西。
