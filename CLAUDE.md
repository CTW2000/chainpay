# CLAUDE.md — chainpay

这是一个**学习项目**。规约的目的不是产出效率，是**保证学习真的发生**。

---
## 0. 分工（2026-08-13 由用户确定）

**用户不写代码。** 用户的职责是搞清楚来龙去脉、每个细节在干什么、把握整体方向不出错。

| 用户负责 | AI 负责 |
|---|---|
| 判断方向对不对 | 写代码、写测试、跑验证 |
| 判断取舍是否合理 | 把每个决策的**为什么**摊开讲 |
| 决定做什么、不做什么 | 主动指出自己不确定、可能出错的地方 |

### AI 的解释标准（硬要求）

写完任何实现，必须同时交付：
1. 每个关键决策的理由
2. **不这么写会怎样**——具体失败场景，最好有实测
3. 我不确定的地方，明确标出
4. 哪几行是"承重墙"，删掉会怎样

> 判据：解释是为了让用户能**独立判断**这段代码对不对，不是让用户相信它是对的。

### 讲解的结构（2026-09-09 由用户定，硬要求）

用户反馈：讲解时把代码里的字段和逻辑混在一起，看着费劲；重点常被一句话带过。这是学习项目，目的是让用户获得知识，尤其是基础知识。
从此每一次讲解都按下面的顺序，**不跳层、不混层**：

1. **先讲整体**：这一步在整个系统里的位置、要解决的问题、不做会怎样。这一层不出现任何字段名、类名、SQL。
2. **再讲基础知识**：这一步用到的概念从零讲（数据库的、以太坊的、记账的、并发的），每个概念配一个能落地的例子。
   重点不许一句话概括——要讲清「是什么、为什么、错了会怎样」，能给数字就给数字，能给失败场景就给失败场景。
3. **再讲逻辑与流程**：用图（ASCII 流程图、状态图、表格）讲数据怎么流、状态怎么变、哪几道门各守什么。这一层仍然不贴代码。
4. **最后单独讲代码**：只挑承重的几段，每段先说它在上面哪个位置、干什么，再贴代码，再逐段用大白话解释。
   字段名、类名、SQL 关键字第一次出现时要先说明它是什么，不能突然冒出来。
5. 一段讲解只讲一件事；「为什么这么做」和「不这么做会怎样」都要展开成具体场景，不用一句话带过。
6. **图的载体**（2026-09-09 由用户定）：复杂的东西——系统结构、多组件交互、状态机、并发时序、一笔钱或一笔交易的一生——
   做成浏览器页面（Artifact，真正的流程图 / 状态图 / 结构图）配详细解释让用户看；简单的关系用 ASCII 图或表格在终端里讲。
   **不把所有讲解都搬进浏览器**，那太费 token：一步最多一页，只装那一步里靠文字讲不清的部分。

> 判据：读者不看代码也能复述这一步的逻辑；读到代码时，每一个名字都已经在前面被介绍过。

## 1. 技术栈（不要擅自更换）

| 层 | 选型 | 版本 |
|---|---|---|
| 语言 | Java | **25 (LTS)**，`JAVA_HOME=~/.local/jdk-25/Contents/Home` |
| 框架 | Spring Boot | **4.1.0**（Spring Framework 7.0.8） |
| 数据库 | PostgreSQL | **18** |
| 数据访问 | **Spring `JdbcClient`** | **账本层禁止引入 ORM** |
| 迁移 | Flyway | 12.4.0（需 `spring-boot-flyway` + `flyway-database-postgresql`） |
| 测试 | JUnit Jupiter + Testcontainers | 6.0.3 / **2.0.5（坐标带 `testcontainers-` 前缀）** |

**版本相关的既有教训见 `README.md` 末尾**，改 pom 前先读。

### 包结构（2026-08-31 重整）

**顶层按功能分，包内按类型分**——和 flow-pay 同一套规矩，切换项目不用换脑子。

```
com.chainpay
├── common/web/          横切的对外契约：信封、错误码、异常处理、错误写出
├── ledger/              账本领域
│   ├── controller/      对外的转账 / 余额接口
│   ├── service/         ★ 账本核心，禁止 ORM（见下）
│   └── system/          系统身份的账本入口 SystemLedger（独立角色 + 独立连接池，M3-⓪）
├── merchant/            控制面：开户、发凭证、吊销、停用
│   ├── controller/
│   └── service/
├── security/            认证、授权、限流、重放、加密
│   ├── filter/          进业务代码之前跑的东西
│   ├── service/         验签、归属校验、租户降权、限流、重放登记
│   └── crypto/          AES-GCM
└── chain/               只读上链（M2）
    ├── rpc/             JSON-RPC 客户端、ChainReader、十六进制、区块头与日志原文
    ├── erc20/           Transfer 事件解码、ABI 编解码与 eth_call 问合约、金额换算
    ├── wallet/          收款地址派生（M3-①）：Keccak/EIP-55、Base58Check、BIP-32 公钥派生；私钥数学只给 XpubTool 与测试
    ├── deposit/         收款（M3）：config 装配（配了 xpub 才生效）、service 分配与入账、repository、domain、controller 商户接口
    ├── payout/          付款（M4）：domain 状态机（PayoutStatus / PayoutTxStatus 显式转换表）、service 三笔账本流（PayoutLedger）
    └── indexer/         索引器（2026-09-02 拆分：20 个文件按类型分四组）
        ├── service/     BlockIndexer、ChainHeadTracker、ReorgRecovery、ChainIndexerScheduler 及它们抛的异常
        ├── repository/  四张表的 SQL：书签、事件、链头、重组审计
        ├── domain/      record 与 enum：书签、链头、批结果、轮询结果
        ├── config/      装配（配了节点地址才生效）与配置
        └── controller/  只读的状态接口 GET /admin/v1/indexer
```

**为什么不是纯按类型分**（`controller/` `service/` 各一个大包）：
那样 `AdminService` 会和 `RateLimiter` 放在一起——两个毫不相干的东西挨着，
而一个功能的相关文件散在三四个包里。改一个功能要同时开好几个目录。

**为什么也不是纯按功能分**（一个包里塞 controller + service + filter）：
这正是重整前的状态，`api/auth/` 里 8 个文件混着过滤器、服务、加密工具三类东西，
看目录看不出哪个是入口、哪个是被调用的。

### 账本层禁止 ORM

**`com.chainpay.ledger.service` 下只允许 `JdbcClient` + 手写 SQL。**

注意范围是 `.service`，不是整个 `ledger` 包——`ledger.controller` 不做数据访问，
这条规则对它不适用（它只负责把 HTTP 请求翻译成 service 调用）。

理由：账本是唯一不能错的地方，抽象越薄越好。ORM 的隐式行为
（几条 SQL、加不加锁、插件拦截）是 flow-pay 已经付过学费的坑。

M3 之后的业务层可以引入 ORM，但要显式讨论并记录取舍。

---

## 2. 正确性纪律

### 不变量优先于功能

任何改动之后，这条必须成立：

```sql
SELECT * FROM ledger_judge();   -- 以 chainpay_system 身份跑；必须 0 行
```

### 能让数据库守的，不要交给应用

约束（`UNIQUE` / `CHECK` / `FK` / `NOT NULL`）写进 schema，不写进 Java。
应用代码会被绕过（新接口、手工 SQL、并发路径），约束不会。

**幂等尤其如此**：靠 `UNIQUE` 约束，不靠「先 SELECT 查一遍」——
后者在并发下必然失败，两个线程可以同时查到"不存在"。

### 数据库身份与作用域（2026-08-31 定）

应用以**普通角色** `chainpay_app` 连库（不是超级用户、不是表的所有者），RLS 对它无条件生效。
角色由 `db/init/01-roles.sql` 建；Flyway 以属主跑迁移。**不要为迁就任何特权角色写代码。**

两种作用域，默认哪个都不设 = 一行都看不到（fail-closed）：

| | 谁用 | 看到什么 | 权限来自 |
|---|---|---|---|
| `TenantScope.asMerchant(id, …)` | HTTP 控制器 | 只有该商户的行 | 会话变量（同一条应用连接） |
| `SystemLedger.inTransaction(…)` | M3 入账、结算、M4 出账、M0 账本测试的脚手架 | 全部行 | **连接身份**：独立角色 `chainpay_system`（BYPASSRLS，非超级用户，非属主）+ 独立连接池 |

**第三种（`TenantScope.asSystem`，会话变量 `chainpay.system = on` 放行策略）已于 M4-⓪（2026-09-09）删除**，V21 同时把五张表策略里的 `is_system_scope()` 分支拆掉、函数删掉：
从此没有任何一个会话变量能打开整库，`PayoutSchemaTest.theSessionVariableDoorIsGone` 守着（应用连接上 set_config 之后仍一行看不到）。

**M3-⓪（2026-09-06）兑现了那句承诺**：系统权限是连接身份，不是一个开关。`SystemLedger` 建池即自检
（不是 BYPASSRLS、或者是超级用户 = 拒绝启动），事务边界由它自己的模板给（手工 `new` 的 `LedgerServiceImpl`
上的 `@Transactional` 没有代理，形同虚设），账本只在回调里可见。V17 的 GRANT 里没有 DELETE：账本对系统身份同样只追加。
故意不做成第二个 `DataSource` / `JdbcClient` bean：Boot 的自动配置见到同类型的第二个 bean 就整体退让，主连接反而会坏。
`ControllerBoundaryTest` 扫源码断言 `controller` 包既不引用 `asSystem` 也不引用 `SystemLedger`（不引 ArchUnit 依赖，规则的形状就是「某个包里不出现某个字符串」）。

**薄实现 vs Boot 官方双数据源（2026-09-07 定：先不换）**：`SystemLedger` 靠**类型**守事务边界与越权出口（拿不到 `Session` 就拿不到账本），
官方做法靠**限定名字符串**（`@Transactional("systemTx")`、`@Qualifier("system")`，忘了写就静默错）。按「能靠结构保证的，不要靠纪律保证」，前者不是权宜之计。
**回来换的条件**（任一命中即评估；前两条同时命中即换）：
① 系统侧超过三个服务类，且 `Session` 开始被当参数一层层往下传；
② 系统侧需要传播语义：`REQUIRES_NEW`、提交后再发事件、嵌套回滚；
③ 上线（M6）需要两个池的健康检查与指标进监控——只命中这一条时手工给 Hikari 绑 Micrometer，不换。
换法：两个 `DataSource` / 事务管理器 / `JdbcClient` 按官方方式声明，应用侧全部 `@Primary`，现有代码不动；`SystemLedger` 保留外形只换内脏，仍是边界测试守的那个类型；
加一条源码扫描测试「系统包里的 `@Transactional` 必须带限定名」；今天「回调抛异常整体回滚」那条测试换完必须仍绿。

### 控制面的防护深度（2026-09-02 定：暂不加）

`/admin/**` 目前只有「回环地址 + 静态令牌」一层：无限流、无重放防护、无审计日志、
发凭证不幂等（质询扫描 3.8）。**这是开发阶段的临时实现**，系统里没有真钱，
后续会做一个生产级的管理员系统（用户体系 + 短期会话 + 敏感操作再认证 + 审计表）。
**回来做的条件**：任一成立即触发——接入第一个真实商户；部署到可从公网到达的机器；
开始做 M6。在那之前不要往这个临时接口上叠防护，那是给一个要被替换的东西建配套。

### 金额

- 一律 `BigDecimal` ↔ `NUMERIC(38,18)`，**绝不用 `double`/`float`**
- **绝不拆成整数部分 + 小数部分两个字段**
- 对外 JSON **一律用字符串**，不用 number（JS 的 number 是 double）
- 跨币种数值不可直接比较，先判 currency

### check-then-act

「先查，再改」的每一处都要问：**两个线程同时走到中间会怎样？**
这是本项目最主要的 bug 来源，M0/M2/M4 会以三种不同形态各出现一次。
M2 的形态已在 2026-09-02 出现：不是「先查再改」，是「两个写入之间有缝」——写事件与推书签，见 V9 注释与 `BlockIndexer`。
2026-09-03 又在 `TokenRegistry.register` 出现一次最经典的形态（先查再插），是评审代理抓到的，不是自己想到的——改成 `INSERT … ON CONFLICT DO NOTHING`，插不进去 = 已登记，让主键裁决。**自己写的代码最容易犯自己最熟的错。**
2026-09-03 第三种形态：不是「先查再改」也不是「两个写入之间有缝」，是「**多次读取之间有缝**」——取一批要问节点三次，父哈希只在第一次核对过，一次重组落在三次之间就把旧分支的行留成了 CANONICAL。见 `BlockIndexer` 的 ⑥。

### 链数据（M2 起，2026-09-02 定）

`chain_transfer_log` / `indexer_cursor` 是账本的**上游证据，不是账本**：没有 RLS（链上事实不属于任何商户），
应用角色没有 DELETE（重组时标 `ORPHANED`，不删行）。

- **事件与书签在同一个事务里提交**；书签只进不退：锁后重读 + `UPDATE … WHERE last_block_number = 期望值` 两道保险
- **网络 IO 在事务外面**：事务要短，握着行锁等 RPC 会拖垮另一个实例和连接池
- **解码失败 = 停下，不跳过**：一条被跳过的日志就是一笔静默丢失的入账。**重组 = 回滚**（M2-④）：`BlockIndexer` 只检测（parentHash 对不上就抛 `ReorgDetectedException`），`ReorgRecovery` 恢复
- `value` 存 `NUMERIC(78,0)` 原始单位；进账本前必须显式检查装不装得下 `NUMERIC(38,18)`，不能静默截断
- 日志的唯一坐标是 `(block_hash, log_index)`，不是 `tx_hash`：重组后同一笔交易会在另一个区块里再出现一次。**坐标相同不等于内容相同**：重放与对账都比载荷（代币、付款人、收款人、金额），同坐标不同内容 = 重放停下 / 对账 disputed，代码永远不改金额（2026-09-03 补丁）
- `BlockIndexer` 不是 Spring bean：设了 `CHAINPAY_CHAIN_RPC_URL` 才由 `ChainIndexerConfig` 装配；测试用内存里的 `FakeChain` 换整条链
- **确认等级不存，算出来**（M2-③）：视图 `chain_transfer_confirmation` 按单行表 `chain_head` 算 SEEN < SAFE < FINAL。给用户加钱绑在 FINAL（M3），于是重组回滚永远只碰链表、不碰账本。**M3 前置条件**：入账那一步再向两个节点核对该行的块哈希与 finalized 高度，那是动钱的边界，索引器无论怎么错都过不了这道门
- `chain_head` 只进不退：finalized 倒退或同号换哈希 = `FinalityViolationException`，停下叫人；safe / latest 倒退 = 节点落后，保留旧值
- 轮询（`ChainIndexerScheduler`）的失败分两种：瞬时的（`JsonRpcException` / `TransientDataAccessException`）下次再来；重组这一次回滚、下一次重放（REORGED）；结构性的（finalized 倒退、解码失败、约束违反、没书签也没配 `start-block`）停下
- **重组回滚**（M2-④）：祖先 = 能证明和链上一致的最高一块——候选只有书签、有日志的块、finalized 头，其余块的哈希我们没有；祖先可能比分叉点低，**多退不伤，少退要命**。祖先之上标 ORPHANED、书签退回祖先、记 `chain_reorg`，三者同一事务；锁内核对书签的号**和哈希**（别的实例可能已重放到同号的新分支）。地板是 finalized，连它都对不上 = `FinalityViolationException`
- 重放的写入是 upsert：CANONICAL 不动，ORPHANED **复活**成 CANONICAL（同一行同一 id）——链翻回原分支时，DO NOTHING 会让存款永远消失；复活只在载荷相同时发生
- **一批的归属**（2026-09-03 补丁）：日志自带的块号与块哈希是节点「说」的，不是承诺给我们的。`BlockIndexer` 落库前三道核对：块号在 [from, to] 内（否则是答非所问，停下）；每条日志的块哈希等于该块的头（为有日志的块再取一次头）；取完日志和 block(to) 之后重读 block(from)，哈希与父哈希未变（否则节点前后不一致，这批作废、下次再来）。不核对的后果实测过：一次重组落在三次读取之间，旧分支的行以 CANONICAL 留下、视图判 FINAL，之后每轮父哈希检查都通过
- **RPC 不信任**（M2-⑤）：三种错三种对策。大声的错（带 code 的 error）：getLogs 窗口对半分；**成功后不翻倍撞回去**，记住失败过的尺寸、向它二分逼近，收敛在上限上后每批只问一次，连续成功 100 批才忘掉天花板试探一次（M3-⑤ 补丁，2026-09-09：Alchemy 免费层限 10 块，翻倍策略在固定上限上永远震荡、一半调用注定失败）；减到一块还失败就停下——**除非那一块在链头两块以内**：那是提供商各后端头不一致（eth_blockNumber 看到了、getLogs 还没有），按瞬时处理并把减半得到的假信息整体恢复（2026-09-09 真实启动实测，此前会停机）；安静的错（getLogs 静默漏日志，回执才是事实源）：每次轮询抽 `reconcile-samples` 个已 finalized、已索引的块用 `eth_getBlockReceipts` 重数，差异**两个节点都点头才动，点的是内容不只是坐标**（补录要求两个节点给的内容一致 / 标废 / 只有一方点头或内容不同 = disputed 等人看），只把有差异的检查记进 `chain_reconcile`；自相矛盾的错：只核对 finalized 那一块，两个节点意见不同 = `FinalityViolationException`，头部的分歧不管
- 客户端对「发出到正文读完」整段计时，正文 16 MB 封顶。审计节点 `CHAINPAY_CHAIN_AUDIT_RPC_URL` 要独立于主节点才有价值（同一家两台机器会被同一个 bug 骗过）；不设时退化为主节点自己的回执路径，能抓索引漏日志，抓不住节点整体撒谎
- **代币白名单**（M2-⑥）：Transfer 事件是合约「说」的，余额是合约「做」的；事件金额只对行为规范的代币等于到账金额。只索引、只入账 `chain_token` 里 ACTIVE 的代币；登记时用 `eth_call` 问链上的 `decimals()` 与 `symbol()`，问不到要运营手工填并注明来源；轮询第一次推批前核对链上 decimals 与表一致，不一致 = 停下。symbol 是从别人的合约里解出来的：Java 侧空白或超过 64 字符当问不到，V14 的 CHECK 兜底（note ≤ 500）。ABI 解码里偏移字与长度字是对方给的 32 字节的数，**先在 BigInteger 上比过实际字节数再收窄**，否则 2^31 抛 ArithmeticException、2^30 乘 2 溢出成负数绕过边界检查；形状不对只允许抛 `IllegalArgumentException`，调用方只接这一种。**白名单由数据库守**（V15）：事件表与书签表的 token 都是指向 `chain_token` 的外键，任何写路径都绕不开；索引器落库前比对每条日志的合约地址，不信节点的过滤；轮询**每轮**都 `requireUsable`（一次主键查询，停用下一轮生效、不等重启），上链核对 decimals 每进程一次；书签记住自己服务的代币，配置换了币而书签没换 = 停下，不从旧进度开始猜
- **金额换算只经 `TokenAmounts.toLedger`**：精确除法、永不四舍五入；整数位超过 20 或 decimals 超过 18 抛 `AmountOverflowException`，M3 把那笔标成「无法入账、等人看」而不是让它卡住循环。铸币（from 为 0x0）按普通入账；不发事件的铸币我们看不见、不入账，留给 M5 用 `balanceOf` 对账发现
- **收款地址是租户边界**（M3-①b，2026-09-07）：`deposit_address` 挂 RLS（同账户表的策略），`token` 是指向白名单的外键，`account_id` 指向商户在该币上的账本账户（`user:<商户 code>:<SYMBOL>`，分配时 `ON CONFLICT (code) DO NOTHING` 顺手建）。一户一币一址由 `UNIQUE (merchant_id, token)` 裁决，并发申请 `INSERT … ON CONFLICT DO NOTHING`、输的一方读回赢家的地址；序号来自序列 `deposit_address_index_seq`，`UNIQUE (derivation_index)` 保证不重用，跳号无害；`address` 主键冲突不是并发是配置错（序号重用或 xpub 配错），报出来不猜。账本币种名用 symbol，`chain_token` 上 ACTIVE 代币的 symbol 部分唯一索引。模块设了 `CHAINPAY_DEPOSIT_XPUB` 才装配，启动日志打出 xpub 指纹与 0/0 地址供对照，xpub 本身不进日志
- **入账**（M3-②，2026-09-07）：`DepositPoster` 只从 `chain_transfer_confirmation` 取 `level = 'FINAL'`、收款方是 ACTIVE 收款地址、代币 ACTIVE、还没有 `deposit` 行的日志；「记给谁」由 `deposit_address` 那一行推导，不接受调用方递进来的商户 id。三步：**核对在事务外**（主节点与审计节点各取一次该块头，哈希等于库里的 block_hash 且块号 ≤ 两个节点各自的 finalized，否则 HELD_NODE_DISAGREE；同一轮内块头与 finalized 复用），**判决是纯计算**（零值 IGNORED_ZERO；金额只经 `TokenAmounts.toLedger`，装不下 HELD_OVERFLOW），**落库是系统池上的一个事务**：**先占坑再动钱**——`INSERT deposit … ON CONFLICT (transfer_log_id) DO NOTHING`（状态 POSTING），占不到 = 别的实例已处理、不碰账本；占到了才 `ledger.transfer`（幂等键 `deposit:<block_hash>:<log_index>`，借镜像账户 `chain:custody:<SYMBOL>`、贷商户账户，occurred_at = 区块时间），再改成 CREDITED。反过来先记账再占坑不安全：另一个实例可能已把同一条判成 HELD。HELD 永不自动变 CREDITED、不卡队列；节点瞬时失败让这一轮提前结束；`deposit` 有 RLS，商户只读自己的。镜像账户余额的绝对值 = 托管地址链上应有余额之和，M5 对账的判官
- **入账策略**（M3-③，2026-09-07）：**信合约做的，不信合约说的**——记账前向两个节点问该地址在那一块的 `balanceOf`，必须等于事件累计（转入减转出，原始单位），对不上或问不到 = HELD_BALANCE_MISMATCH，转账扣费、弹性供应、静默铸币、节点撒谎都在这里露馅。`chain_token.min_deposit` 是每种代币的最小入账额（账本单位，0 = 不限），低于它 REJECTED_DUST 记录不入账也不退。失败分两种：瞬时的（`JsonRpcException` code 为空、`TransientDataAccessException`）这一轮提前结束；结构性的这一笔 HELD_ERROR 带异常原文、队列继续。HELD 的人工路径：人复核后把行改成 APPROVED 并把谁、为什么写进 `hold_reason`，任务下一轮不再核对、用 `UPDATE … WHERE status = 'APPROVED'` 重新占坑、同一幂等键记账；**人永远不手工碰账本表**。每种状态该做什么见 `docs/runbook/deposit.md`
- **收款接口**（M3-④，2026-09-07）：`POST /api/v1/deposit-addresses {token}` 一户一币一址且幂等，地址给 EIP-55 写法；`GET /api/v1/deposits` 把已处理的行和「在路上」的钱（PENDING）并在一起，都带 `level` 与 `confirmations`；`GET /api/v1/deposits/balance` 分 `available`（账本余额）与 `pending`（在路上合计）。查询走应用连接、整段在 `asMerchant` 里：地址表与入账表有 RLS，链表没有但每条查询都经地址表连接，别人的转账结构上带不出来；没有「按 id 查一条」的接口，「不存在」与「不是你的」连区分的机会都没有。金额一律字符串；HELD 只露状态不露原因；请求体只有 token，地址与序号由服务端派生；白名单外或停用的代币回 400 + 2008（`TOKEN_NOT_SUPPORTED`）；没配 xpub 时这些路径是 404
- **真环境演练**（M3-⑤，2026-09-08）：一笔真实的 Sepolia LINK 走完 SEEN → SAFE → FINAL → CREDITED（入块到 FINAL 约 18 分钟）；`kill -9` 演练证明占坑、镜像账户、转账、分录在一个事务里同生同死；书签回退重放不双记。演练留下的规矩与补丁：① **动书签（前跳或回退）必须用两个节点都同意的块哈希**，先记旧值，只在停机或两轮之间做（做法见 `docs/runbook/chain-indexer.md` 第五节）；② Alchemy 免费层 `eth_getLogs` 限 10 块，「对半分、成功后翻倍」在固定上限上震荡——已修，见上面「RPC 不信任」；追赶速度仍受「每轮 10 批」限制，那是 M6 的题（停机恢复）；③ 入账任务与索引器曾共用单线程调度器——已修，见下一条。给人用的签名客户端是 `tools/api.py`，凭证只从环境变量来；`entry`/`deposit` 这类带外键的表，锁被引用表会连带挡住引用表的插入
- **出账地基**（M4-⓪，2026-09-09）：出账是**账本先扣、链上后发生**，中间的不确定期用冻结账户 `user:<商户>:<币>:frozen`（LIABILITY，属于商户）表达。三笔账本流都是普通 `ledger.transfer`：申请 `WITHDRAWAL_FREEZE`（可用 → 冻结，键 `withdrawal:<申请幂等键>:freeze`，走商户连接与 asMerchant）、FINAL `WITHDRAWAL`（冻结 → 托管镜像，键 `withdrawal:<id>:settle`，系统身份）、失败 `WITHDRAWAL_REVERSE`（冻结 → 可用，键 `withdrawal:<id>:reverse`，系统身份）；结算过的不能再解冻由**冻结账户不许为负**守，不靠代码记得。V21 四张表：`hot_wallet`（`next_nonce` 是意图，真相在链上由对账拉回；无 RLS，应用角色连读都没有）、`payout`（`freeze_transfer_id NOT NULL UNIQUE`——每笔提现以冻结开始，先冻结再插行同一事务；settle / reverse 互斥；CONFIRMED ⇔ 有结算、FAILED/REJECTED ⇔ 有解冻且写原因；`UNIQUE (merchant_id, idempotency_key)`；RLS 商户只看只插自己的，应用角色无 UPDATE）、`payout_tx`（签好的原文先落库再广播；`tx_hash` 唯一；MINED ⇔ 带块号块哈希；**部分唯一索引 (hot_wallet, nonce) WHERE status = 'MINED'**——加速后只有一笔上链由数据库守；无 RLS，应用角色只读）、`payout_address`（白名单，RLS 同账户表）。状态机是显式转换表（`PayoutStatus`：PENDING_APPROVAL → QUEUED → SIGNED → BROADCAST → MINED → CONFIRMED，核准可拒、排队与上链可失败、上链可因重组退回；**BROADCAST 没有到 FAILED 的边**——广播后只有回执能宣布结局；`PayoutTxStatus`：SIGNED → BROADCAST → MINED / DROPPED / REPLACED，丢弃可重发、上链可因重组退回、被替代是终态）。系统角色对四张表都没有 DELETE
- **演练补丁**（2026-09-09）：`spring.task.scheduling.pool.size` ≥ `@Scheduled` 任务数（现在 4，两个任务），`ChainIndexerBootSmokeTest` 用一个卡住的任务证明别的任务照跑——Spring 默认只有一条调度线程，入账事务等锁时索引器跟着停、降级检测不跑、一条 ERROR 都没有。系统连接带 `lock_timeout`（`chainpay.system-db.lock-timeout`，默认 5s，Hikari `connectionInitSql`）：等锁超过上限就放弃。**Spring 7 把 SQLSTATE 55P03 翻成 `UncategorizedSQLException`（非瞬时）**，入账任务会据此记 HELD_ERROR、每次等锁超时都变成一张工单——红灯测试实测；所以系统池的 `JdbcTemplate` 装了翻译器把 55P03 翻成 `CannotAcquireLockException`（瞬时），其余交回默认翻译。加超时的同时必须问「超时会被翻成哪一类」，否则修法比病更重
- **扫描补丁**（2026-09-09，M0–M3 全量安全扫描的 14 条，V22 + 十处 Java）：**判官必须以能看到全部行的身份跑**——`ledger_judge()` 只在超级用户或 BYPASSRLS 下给结论，其余身份直接拒绝；两个判官视图授权给 `chainpay_system`；`SystemLedger` 启动时跑一次判官并打出可见分录数。此前视图没授权也没 `security_invoker`，能查通只是开发库属主恰好是超级用户的副作用，非超级用户属主会静默得到 0 行。**开了 RLS 的表一律 FORCE**，`SchemaGuardTest` 扫 `pg_class` 守着；租户策略里的会话函数包成 `(SELECT …)` 每条语句只算一次；`chain_transfer_log` 按 `(to_address, token)` / `(from_address, token)` 各一个 `WHERE status = 'CANONICAL'` 的部分索引——谓词与查询耦合，EXPLAIN 测试守着。**书签的身份是「号 + 哈希」**：`indexer_cursor` 的守卫 WHERE 同时比两者，`persist` 与 `ReorgRecovery` 一样。**每个 `@RequestBody` 都带 `@Valid`**（`ControllerBoundaryTest` 守），校验失败 / JSON 不可读 / 参数缺失或类型不匹配一律 400 + 2001。**nonce 的格式在验签之前检查**（32 位十六进制）：定长是「拼接无歧义」的前提，必须在算签名之前成立；重放登记仍在验签之后。凭证未命中也做一次诱饵解密与 HMAC，两条失败路径等耗时。块哈希一律 `equalsIgnoreCase`；`logIndex` 收窄前比范围；`Hex` 只接受 `[0-9a-fA-F]`；对账路径的解码失败记 disputed 而不是停机；带 key 的节点 URL 只对回环与内网允许 `http://`；离线 xpub 工具没有终端就拒绝
- **停下要能被问到**（M2-⑥ 补丁 3）：状态表 `indexer_state`（RUNNING / DEGRADED / HALTED）。进程启动先读它，HALTED 就不碰节点，**重启不算恢复**，人改回 RUNNING 才算；连续 `degraded-after-failures` 次瞬时失败、或审计节点连续答不出 = DEGRADED，每轮 ERROR；HTTP 401 / 403 是凭证失效，`RpcAuthException` 直接停下不重试；只读接口 `GET /admin/v1/indexer` 一次给全状态、书签、链头、落后块数、争议块数。每种停机原因该做什么见 `docs/runbook/chain-indexer.md`
- **节点地址按密码对待**：只经 `RpcEndpoint` 解析，失败只报变量名与主机名，永不回显原文（`URI.create` 会把整条含 key 的输入放进异常）；审计节点与主节点同一主机 = 拒绝启动；没配审计节点要在启动日志里写明「单节点」
- **翻译层是外部 JSON 的唯一入口**：`EthRpc` 缺字段就指名拒绝，不留空指针；`EthRpcTest` 用 2026-09-04 录自 Sepolia 的真实响应做契约测试，`ChainIndexerBootSmokeTest` 让容器真的装配一次索引器（条件注解、属性绑定、`@Scheduled` 三者从此有默认覆盖）
- controller 包不得引用 `asSystem`：`ControllerBoundaryTest` 扫源码守着（§2 那条 ArchUnit 的承诺以更薄的方式兑现，匹配集合不能为空）

---

## 3. 每个里程碑的固定流程

```
① 先在 docs/retro/M<n>-before.md 写「这一步会怎么坏」   ← 写代码之前
② 写出能抓住这些坏的测试（红灯）
③ 实现到绿灯
④ 对照现成清单，补没想到的
⑤ 在 docs/retro/M<n>.md 复盘：哪几条是自己想到的
```

**AI 在第 ① 步只能提问，不能给答案。**
可以问「并发下这里会怎样」，不能直接说「这里有 check-then-act」。

---

## 4. 可复用的知识清单

不要复制，直接读路径：

- `~/Documents/CodeProject/flow-pay-backend/docs/ai/knowledge/` — TigerBeetle 账本、OWASP Cheat Sheets（120 篇）、WSTG 业务逻辑、falsehoods、strong_migrations
- `~/Documents/CodeProject/flow-pay-merchant/docs/knowledge/` — TanStack Query、OWASP 客户端测试

**引用清单时给出具体文件名**，不要泛泛地说"参考 OWASP"。

---

## 5. 验证纪律

不要说「应该可以了」。按这个顺序找证据，**停在第一个真正证明结论的层**：

1. 已有测试 → 2. `mvn -q test-compile` → 3. 最窄的直接验证（跑单个测试类）→ 4. 手工

报告四件事：**验证了什么 / 跑了哪些命令 / 什么通过了 / 什么仍未验证**。
没有可行的验证路径就直说，不要含糊过去。

**证据会过期**：三轮编辑之前的那次绿灯，现在不算数。

---

## 6. Git

### 提交前必须先让用户看 diff（2026-08-13 增补）

**AI 写完代码后不要直接 commit。** 正确流程：

```
① 写完 + 跑测试
② 把改动留在工作区/暂存区，告诉用户「改了哪些文件、每个文件改了什么、重点看哪几行」
③ 等用户确认
④ 用户说可以了，再 commit
```

> **为什么**：用户不写代码，判断力是他唯一的把关手段。
> 代码一旦进了 commit，在 IDE 的 Changes 面板里就消失了——等于把唯一的审阅窗口关掉。

**如果已经误提交了**（且未推送）：`git update-ref -d HEAD`（首次提交）或
`git reset --soft HEAD~1`，文件不动、全部退回暂存区。**提交信息先存到临时文件**，
确认后原样重新提交。

### 其余规则

- **AI 不得自行 `push`**，除非明确要求
- 暂存按显式路径，**禁止 `git add -A` / `git add .`**
- `env/*.env`、私钥、助记词**绝不入库**（`.gitignore` 已配，但别依赖它）

---

## 7. 私钥（M4 起）

- 绝不进代码、绝不进镜像层、绝不进日志
- 镜像的层不可变：`COPY` 进去再 `RUN rm` 删掉，密钥仍在前一层里
- 测试网私钥也按真密钥对待——习惯是练出来的
- **服务端没有私钥**（M3-①，2026-09-07）：收款地址从账户层 xpub（m/44'/60'/0'）做 BIP-32 普通派生，助记词与 xprv 从头到尾不进服务器。主代码里只有 `chain/wallet` 包能碰 `ExtendedPrivateKey` / `Bip39`，`WalletBoundaryTest` 扫源码守着；xpub 用 `tools/xpub.sh` 断网算，工具不回显、不落盘、不记日志。xpub 泄露 = 隐私全丢（能枚举全部收款地址），但转不走钱；**xpub 加任意一个普通派生的子私钥 = 父私钥**，所以 M4 取私钥签名时绝不能把某个子私钥单独交出去
- **已知答案必须来自原始文本**：BIP-32 向量经概括模型转述时被抄错一个字母，Base58Check 校验和立刻不成立；规范向量用 curl 取原文逐字核对，不经任何转述
