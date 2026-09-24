# CLAUDE.md — chainpay

这是一个**学习项目**。规约的目的不是产出效率，是**保证学习真的发生**。
进度与下一步见 `LEARNING-PATH.md`；进行中的进程拆分见 `docs/knowledge/m6-process-split.md`。

---
## 0. 分工（用户定）

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

### 讲解的结构（用户定，硬要求）

每一次讲解都按下面的顺序，**不跳层、不混层**：

1. **先讲整体**：这一步在整个系统里的位置、要解决的问题、不做会怎样。这一层不出现任何字段名、类名、SQL。
2. **再讲基础知识**：用到的概念从零讲（数据库的、以太坊的、记账的、并发的），每个概念配一个能落地的例子。
   重点不许一句话概括——讲清「是什么、为什么、错了会怎样」，能给数字就给数字，能给失败场景就给失败场景。
3. **再讲逻辑与流程**：用图（ASCII 流程图、状态图、表格）讲数据怎么流、状态怎么变、哪几道门各守什么。这一层仍然不贴代码。
4. **最后单独讲代码**：只挑承重的几段，每段先说它在上面哪个位置、干什么，再贴代码，再逐段用大白话解释。
   字段名、类名、SQL 关键字第一次出现时要先说明它是什么，不能突然冒出来。
5. 一段讲解只讲一件事；「为什么这么做」和「不这么做会怎样」都要展开成具体场景。
6. **图的载体**：复杂的东西（系统结构、多组件交互、状态机、并发时序、一笔钱或一笔交易的一生）做成浏览器页面（Artifact）配详细解释；
   简单的关系用 ASCII 图或表格在终端里讲。**不把所有讲解都搬进浏览器**：一步最多一页，只装那一步里靠文字讲不清的部分。

> 判据：读者不看代码也能复述这一步的逻辑；读到代码时，每一个名字都已经在前面被介绍过。

## 1. 技术栈与结构（不要擅自更换）

| 层 | 选型 | 版本 |
|---|---|---|
| 语言 | Java | **25 (LTS)**，`JAVA_HOME=~/.local/jdk-25/Contents/Home` |
| 框架 | Spring Boot | **4.1.0**（Spring Framework 7.0.8） |
| 数据库 | PostgreSQL | **18** |
| 数据访问 | **Spring `JdbcClient`** | **账本层禁止引入 ORM** |
| 迁移 | Flyway | 12.4.0（需 `spring-boot-flyway` + `flyway-database-postgresql`） |
| 测试 | JUnit Jupiter + Testcontainers | 6.0.3 / **2.0.5（坐标带 `testcontainers-` 前缀）** |

**版本相关的既有教训见 `README.md` 末尾**，改 pom 前先读。

### 包结构：顶层按功能分，包内按类型分

```
com.chainpay
├── common/web/      对外契约：信封、错误码、异常处理
├── ledger/          账本（不直接对外）
│   ├── service/     ★ 账本核心
│   └── system/      系统身份：SystemLedger（建池、启动自检）；池、事务管理器、JdbcClient、账本是限定名 system 的非默认候选 bean
├── merchant/        控制面：开户、发凭证、吊销、停用
├── security/        filter（进业务代码之前）、service（验签、租户降权、限流、重放）、crypto（AES-GCM）
├── admin/           管理员、短期会话、敏感操作再认证、审计（AdminAuthFilter 认人，拦截器认操作）
├── audit/           对账：五条检查，只追加的 audit_run / audit_finding
├── ops/             health（指示器只做判定）、alert（看 work 组、变化时打 webhook）、role（进程角色守卫）、Migrate / AdminBootstrap（一次性命令）
└── chain/
    ├── rpc/         JSON-RPC 客户端、ChainReader、十六进制、区块头与日志原文
    ├── erc20/       Transfer 解码、ABI、eth_call、金额换算
    ├── wallet/      收款地址派生（Keccak / EIP-55、Base58Check、BIP-32 公钥派生）；私钥数学只给离线工具与测试
    ├── deposit/     收款：分配地址、入账、商户接口
    ├── payout/      付款：状态机、三笔账本流、发送与追踪任务、白名单与申请、商户与管理接口
    └── indexer/     索引器：service（含四个写入类）、repository、domain、config、controller（只读状态）
```

纯按类型分，一个功能的文件散在三四个包里；纯按功能分，一个包里混着入口、服务、工具，看目录看不出谁调谁。

### 收口按概念，不按层（用户定）

同一个事实在两处以上用到，就给它一个以**概念**命名的家，放在定义这个事实的包里；不按层各放一份，也不建全局 `Constants` 大杂烩。
**收口要配守卫**：一个文件挡不住下一个人再抄一份，要有测试把收口点和它的每个使用处绑住。现有的家：

- **金额** `ledger/service/LedgerAmounts`：容量（18 / 20 位）、对外写法的正则 `FITTING_DECIMAL`（写法与容量一起查，超了在边界就回 2001）、`requireFits`（装不装得下只在这里判）、`text`（金额写成字符串只经这里）。链上原始单位按 decimals 换算是另一个概念，家在 `chain/erc20/TokenAmounts`。
- **地址** `chain/wallet/EthAddress`：`SHAPE`（给注解）、`isWellFormed`（给代码）、`lowercase`（存库）、`checksummed`（对外）。**库里的地址形状**只在函数 `is_eth_address`，地址类的列要么自带调它的 CHECK、要么外键指向这样的列。
- **密钥形态的变量名**：只在 `tools/image-check.sh` 那一行正则（它在生产里按这个名单按值扫描镜像）；Java 测试经 `support/SecretNames` 读。

守卫：`SingleHomeGuardTest`（一条规则守一个家）、`SchemaGuardTest`（金额容量 ↔ 每个带小数位的列）、`ControllerBoundaryTest`（控制器解析的金额 ↔ 格式正则）、`EnvInventoryTest`（env 样例 ↔ 密钥名单 ↔ 禁用名单）。

### 账本层禁止 ORM

**`com.chainpay.ledger`（`service` 与 `system`）下只允许 `JdbcClient` + 手写 SQL。**
账本是唯一不能错的地方，抽象越薄越好；ORM 的隐式行为（几条 SQL、加不加锁、插件拦截）是 flow-pay 付过学费的坑。其它业务层要引入 ORM，先显式讨论并记录取舍。

---

## 2. 正确性纪律

### 不变量优先于功能

任何改动之后，这条必须成立：

```sql
SELECT * FROM ledger_judge();   -- 以 chainpay_system 身份跑；必须 0 行
```

判官只在能看到全部行的身份（BYPASSRLS 或超级用户）下给结论，其余身份直接拒绝。每个测试结束时三个判官自动核一次账（`AbstractPostgresTest`）。

### 能让数据库守的，不要交给应用

约束（`UNIQUE` / `CHECK` / `FK` / `NOT NULL`）写进 schema，不写进 Java。应用代码会被绕过（新接口、手工 SQL、并发路径），约束不会。
**幂等尤其如此**：靠 `UNIQUE` 约束，不靠「先 SELECT 查一遍」——两个线程可以同时查到"不存在"。
**账本只追加**：两个角色对 `transfer` / `entry` 都没有 UPDATE 与 DELETE，`account` 只能改 `balance` 一列（`SchemaGuardTest` 守）。

### 数据库身份与作用域

应用以**普通角色** `chainpay_app` 连库（不是超级用户、不是属主），RLS 对它无条件生效；角色由 `db/init/01-roles.sql` 建，Flyway 以属主跑迁移。
**不要为迁就任何特权角色写代码。** 两种作用域，默认哪个都不设 = 一行都看不到（fail-closed）：

| | 谁用 | 看到什么 | 权限来自 |
|---|---|---|---|
| `TenantScope.asMerchant(id, …)` | HTTP 控制器 | 只有该商户的行 | 会话变量（同一条应用连接） |
| `@Transactional("system")` + 注入限定名 system 的 `JdbcClient` / `LedgerService` | 入账、结算、出账、对账、控制面、账本测试的脚手架 | 全部行 | **连接身份**：独立角色 `chainpay_system`（BYPASSRLS，非超级用户，非属主）+ 独立连接池 |

- 没有任何会话变量能打开整库（`PayoutSchemaTest.theSessionVariableDoorIsGone`）。但商户号这个会话变量是连接自己设的：RLS 挡的是「代码忘了限定商户」，挡不住一个已被控制的连接——所以强凭证不能放在对外的进程里（见「进程角色」）。
- 开了 RLS 的表一律 FORCE；策略里的会话函数包成 `(SELECT …)`，每条语句只算一次；每个视图都按调用者执行（`security_invoker`——否则视图用主人的身份读表，换一个受行级安全约束的主人，判官就静默报 0 行）。`SchemaGuardTest` 守这三条。
- `SystemLedger` 建池即自检（不是 BYPASSRLS、或是超级用户 = 拒绝启动，并跑一次判官）；系统身份对账本同样只追加。
- **系统侧的写法（方案甲，2026-09-23 用户定，同日换完）**：注入 `@Qualifier("system")` 的 `JdbcClient` 与 `LedgerService`。读与单条写直接用系统 `JdbcClient`（一条语句本身就是一个事务）；
  几条写要一起成败的，放进 `@Transactional("system")` 的方法。网络在事务外的类照索引器的做法拆出写入类（`AuditWriter`、`DepositWriter`、`PayoutSendWriter`、`PayoutTrackWriter`），计数靠写入方法的返回值，提交了才算。
  不要在商户的主池事务里直接用系统 `JdbcClient`：Spring 会把借来的系统连接绑到外层事务上、等它结束才还，而系统池默认只有 2 条。web 要跨商户问「是不是」，用库里的是 / 否函数（见下下条）。
  系统账本 `SystemLedgerService` 把 `transfer` 覆写成 system + `MANDATORY`：父类那个不带限定名的注解指向主池，原样继承的话，在系统事务外调用时系统连接上的几条 SQL 各自提交——现在不在 system 事务里调就当场抛。
  `SystemLedger` 的回调（`inTransaction` / `Session`）已删，它只剩建池、建 `JdbcClient` 与启动自检。
- 系统池、系统事务管理器、系统 `JdbcClient`、系统账本四个 bean 都是 `@Bean(defaultCandidate = false)` + `@Qualifier("system")`（`SystemLedgerConfig`）。**这四个 `defaultCandidate = false` 都是承重墙**：
  去掉池那个，Boot 对主数据源的自动配置整体退让，应用侧的 `JdbcClient` 悄悄连成系统身份（读会绕过租户隔离）；去掉事务管理器那个，`asMerchant` 的事务开在系统池上，租户变量设不上；
  去掉 `JdbcClient` 那个，Boot 自动配置的主 `JdbcClient` 退让，所有按类型注入它的地方悄悄拿到系统身份（实测，应用照常启动）；去掉账本那个，两个候选撞车、应用起不来。`SystemPoolBeansTest` 抓得住这四种。
- 用到系统身份（`SystemLedger` 或字面量限定名 `"system"`）的源文件里，`@Transactional` 必须写明限定名（`SystemTransactionalQualifierTest`）；
  系统侧每个带注解的方法都钉住「是代理、不是 final、限定名 system、传播方式对」（`SystemTransactionalBeansTest`）。controller 包不得引用系统身份的入口与四个 bean（`ControllerBoundaryTest` 扫源码）。
- **web 问「这是不是平台自己的地址」**：库里的是 / 否函数 `is_platform_address`（V30，进程拆分第 ②）按属主 `chainpay_system` 执行（`SECURITY DEFINER`），应用角色只拿到布尔值、拿不到任何一行。
  每个 SECURITY DEFINER 函数都锁死：属主是 `chainpay_system`、`search_path` 钉死且 `pg_temp` 在最后、PUBLIC 没有执行权（`SchemaGuardTest`）；函数体先自检属主看得全，看不全就抛、不答「不是」（坑 3）。
  `PlatformAddressFunctionTest` 实测两种绕法都不成：调用方建同名空临时表并授权给属主（search_path 没钉死时函数就读它）、换一个看不全的属主。

### 进程角色（进程拆分进行中）

- 同一个镜像起成 `web`（对外接商户请求）/ `worker`（定时任务、控制面、重钥匙）两种常驻进程，`SPRING_PROFILES_ACTIVE` **恰好一个**，没给或两个都给就拒绝启动。
- 每个角色一张禁用名单、一张必填名单（`ops/role/ProcessRole`：环境变量名 + 配置键 + 说明），禁用项出现、必填项缺了都拒绝启动，一次全部点名，报错只写变量名。worker 必填主节点地址与热钱包私钥。
- 配置项三种状态：有值；字面值 false（明确不要：测试基类用它关掉节点与热钱包模块，`deploy.sh` 不放行）；没配（没设、空白、解析不出的占位符）。`@ConditionalOnProperty` 把空串当「有」，所以空白由守卫判。节点与热钱包的五个装配类挂 `worker` profile、`matchIfMissing = true`：没配就装配、当场报错，不会悄悄不装配（`WorkerOnlyAssemblyTest`）。
- 守卫是静态、最高优先级的 BeanFactoryPostProcessor，在造任何 bean 之前核对。写成普通 bean 就晚了：应用已经在建连接。一次性命令（`--migrate-only`、`--create-admin`）不属于任何角色，守卫不管。
- 名单上的名字必须真实存在：`ProcessRoleBootTest` 起真应用、逐项设运维真会设的环境变量（从名单派生的单元测试查不出名单里的拼写错误）；`EnvInventoryTest` 让 env 样例里每个变量都有归属。
- 拆服务（第 ⑥ 步）之前，compose 唯一的 `app` 以 `worker` 身份跑，测试基类激活 `test` + `worker`。九条取舍、步骤与进度见 `docs/knowledge/m6-process-split.md`。

### 事务的两种写法

- **容器创建的服务用 `@Transactional`**（`TenantScope`、`LedgerServiceImpl`、`AdminService`、`DepositAddressService`、`HotWalletFundingService`、`PayoutApprovalService`，以及下面的写入类……）。注解靠代理生效，三条纪律：
  类与带注解的方法不能 final（final 类启动失败；final 方法启动只打一行 WARN，调用时字段全是 null）；不能 this 自调用；容器外 `new` 的实例没有事务（后两条完全静默）。
  删掉注解本身也静默（外层还有事务时测试照绿），所以守卫测试钉住「容器给的是代理、方法带 REQUIRED、类与方法都不是 final」（`DepositAddressServiceTest`、`IndexerWritersTransactionalTest`、`SystemTransactionalBeansTest`）。
- **网络在事务外、写库在事务里**的类：事务那一段搬进写入类，网络那一侧留在原类。索引器（`BatchWriter` / `ChainHeadWriter` / `ReorgWriter` / `ReconcileWriter`）、
  对账（`AuditWriter`）、入账（`DepositWriter`）、发送（`PayoutSendWriter`）、追踪（`PayoutTrackWriter`）都是这样。
  测试用 `IndexerWriters` 与 `AbstractDepositPostingTest.posterWith`（`TransactionalProxy` 给 new 出来的对象套上和容器同样的事务代理）。索引器后三个写入类的注解被删，只有守卫测试发现得了。
- 生产代码不用 `TransactionTemplate`（`SystemLedger.inTransaction` 2026-09-23 删掉）。测试要临时开系统事务用基类的 `inSystemTransaction`；直接调账本的测试走 `SystemScopedLedger`，每次调用一个系统事务。
- 嵌套靠传播方式：内层 REQUIRED 加入外层开的事务；系统账本的 `transfer` 是 MANDATORY，只加入、不自己开。

### 控制面

`/admin/**` 三道门：回环地址 + 不经代理 + 管理员会话（口令只存 Argon2id 散列；会话只存令牌的 SHA-256，闲置 30 分钟、12 小时到点失效；
敏感操作要 5 分钟内用口令再认证过；每次调用写 `admin_action`）。应用在容器里时，宿主打发布端口的请求不是回环，一律 401——用 `tools/admin.sh`（在容器里发请求）。
第一个管理员用 `--create-admin`，口令只从环境变量来。还没做：TOTP、提现冷却期、管理员的增删与停用接口。操作见 `docs/runbook/ops.md`「控制面」。

### 金额

- 一律 `BigDecimal` ↔ `NUMERIC(38,18)`，**绝不用 `double` / `float`**，**绝不拆成整数部分 + 小数部分两个字段**；对外 JSON **一律用字符串**；跨币种数值不可直接比较，先判 currency。
- **字符串只收普通小数写法**：请求里的金额字段都带 `LedgerAmounts` 的正则（`ControllerBoundaryTest` 守「控制器里每个 `new BigDecimal(…)` 解析的字段都带正则」）。
  不收科学计数法：`1E-999999999` 只有 12 个字符，写全是 10 亿位。
- 账本入口按 `NUMERIC(38,18)` 查小数位**和**整数位（整数位按 long 算：scale 可以是负二十亿，int 相减会溢出），报错只说几位、不写金额。
- **任何把外来数据格式化进输出的地方都要问：它最长能有多长。**

### check-then-act

「先查，再改」的每一处都要问：**两个线程同时走到中间会怎样？** 这是本项目最主要的 bug 来源，已经见过三种形态：

- 先查再插（`TokenRegistry.register`）→ `INSERT … ON CONFLICT DO NOTHING`，让主键裁决；
- 两个写入之间有缝（写事件与推书签）→ 放进同一个事务；
- 多次读取之间有缝（取一批要问节点三次，父哈希只核对过一次）→ 读完之后重读核对（`BlockIndexer` 的 ⑥）。

**自己写的代码最容易犯自己最熟的错。**

### 链数据与索引器

- `chain_transfer_log` / `indexer_cursor` 是账本的**上游证据，不是账本**：没有 RLS（链上事实不属于任何商户）；应用角色没有 DELETE（重组标 `ORPHANED`，不删行）。
- **事件与书签在同一个事务里提交**；书签只进不退，身份是「号 + 哈希」（锁后重读 + `UPDATE … WHERE` 期望的号与哈希）。
- **网络 IO 在事务外面**：事务要短，握着行锁等 RPC 会拖垮连接池。
- **解码失败 = 停下，不跳过**：跳过一条日志就是静默丢一笔入账。
- `value` 存 `NUMERIC(78,0)` 原始单位；进账本只经 `TokenAmounts.toLedger`（精确除法、永不四舍五入，装不下抛 `AmountOverflowException`，那一笔 HELD，不卡住循环）。
- 日志的唯一坐标是 `(block_hash, log_index)`，不是 `tx_hash`。**坐标相同不等于内容相同**：重放与对账都比载荷（代币、付款人、收款人、金额），同坐标不同内容 = 重放停下 / 对账 disputed，代码永远不改金额。
- **一批的归属**：节点给的块号与块哈希不可信。落库前三道核对——块号在 [from, to] 内；每条日志的块哈希等于该块的头；取完之后重读 block(from)，哈希与父哈希未变（否则整批作废、下次再来）。
- **重组 = 回滚**：`BlockIndexer` 只检测，`ReorgRecovery` 恢复。祖先 = 能证明和链上一致的最高一块（候选只有书签、有日志的块、finalized 头），**多退不伤，少退要命**；
  祖先之上标 ORPHANED、书签退回祖先、记 `chain_reorg`，三者同一事务；地板是 finalized。重放的写入是 upsert：载荷相同时把 ORPHANED 复活成 CANONICAL（同一行）。
- **确认等级不存，算出来**：视图 `chain_transfer_confirmation` 按单行表 `chain_head` 算 SEEN < SAFE < FINAL；给用户加钱绑在 FINAL。
  `chain_head` 只进不退：finalized 倒退或同号换哈希 = `FinalityViolationException`，停下叫人；safe / latest 倒退 = 节点落后，保留旧值。
- 失败分三类：瞬时的（`JsonRpcException`；`TransientDbFailure.isTransient` 认定的库抖动）下一轮再来；重组这一轮回滚、下一轮重放；
  结构性的（finalized 倒退、解码失败、约束违反、没书签也没配 `start-block`）停下。`RpcAuthException`（401 / 403，凭证失效）直接停下，不重试。
- **停下要能被问到**：状态表 `indexer_state`（RUNNING / DEGRADED / HALTED），HALTED **重启不算恢复**，人改回 RUNNING 才算；`GET /admin/v1/indexer` 一次给全状态。
- **RPC 不信任**，三种错三种对策：大声的错（带 code）→ getLogs 窗口对半分，记住失败过的尺寸、向它二分逼近，不翻倍撞回去；减到一块还失败就停下，
  除非那一块在链头两块以内（提供商后端之间头不一致，按瞬时处理）。安静的错（getLogs 漏日志）→ 每轮抽样用 `eth_getBlockReceipts` 重数，差异两个节点都点头（内容一致）才动，否则 disputed。
  自相矛盾的错 → 只核对 finalized 那一块，两个节点意见不同 = `FinalityViolationException`。
- 客户端对「发出到正文读完」整段计时，正文 16 MB 封顶。审计节点要独立于主节点才有价值（同一家两台机器会被同一个 bug 骗过）；没配时启动日志写明「单节点」。
- **节点地址按密码对待**：只经 `RpcEndpoint` 解析，报错只写变量名与主机名；审计节点与主节点同一主机 = 拒绝启动；带 key 的地址只对回环与内网允许 `http://`。
- **翻译层 `EthRpc` 是外部 JSON 的唯一入口**：缺字段就指名拒绝，不留空指针；契约测试用录下来的真实响应。
- **代币白名单**：只索引、只入账 `chain_token` 里 ACTIVE 的代币，由数据库外键守（事件表与书签表）；登记时用 `eth_call` 问 decimals / symbol，问不到由运营手填并注明来源；
  轮询第一次推批前核对链上 decimals，每轮 `requireUsable`；书签记住自己服务的代币，配置换了币而书签没换 = 停下。
  ABI 解码里对方给的偏移与长度，先在 BigInteger 上比过实际字节数再收窄。Transfer 事件是合约「说」的，余额是合约「做」的（见「收款」）。
- 追赶按时间封顶（`chainpay.chain.catch-up-budget`），不按批数。**动书签**（前跳或回退）必须用两个节点都同意的块哈希，先记旧值，只在停机或两轮之间做（`docs/runbook/chain-indexer.md`）。

### 收款

- **收款地址是租户边界**：`deposit_address` 有 RLS；一户一币一址由 `UNIQUE (merchant_id, token)` 裁决，并发申请输的一方读回赢家的地址；
  序号来自序列、`UNIQUE (derivation_index)` 保证不重用；`address` 主键冲突不是并发，是配置错（序号重用或 xpub 配错），报出来不猜。
  xpub 必填（`CHAINPAY_DEPOSIT_XPUB`，没配或留空就拒绝启动，报错点名变量）；派生器与两个收款服务靠组件扫描注册，没有「不配就不装配」。启动日志打出 xpub 指纹与 0/0 地址供对照，xpub 本身不进日志。
- **入账**（`DepositPoster`）只取 FINAL、收款方是 ACTIVE 收款地址、代币 ACTIVE、还没有 `deposit` 行的日志；记给谁由收款地址那一行推导，不接受调用方递进来的商户 id。三步：
  1. **核对在事务外**：两个节点各取该块头，哈希等于库里的、块号 ≤ 各自的 finalized。finalized 还没到、差距在 `finality-tolerance-blocks`（64）以内 = 这一轮延后、不占坑；超出 = HELD_NODE_DISAGREE。
  2. **判决是纯计算**：零值 IGNORED_ZERO；装不下 HELD_OVERFLOW；低于 `min_deposit` 的 REJECTED_DUST。
  3. **落库是系统池上的一个事务，先占坑再动钱**：`INSERT deposit … ON CONFLICT DO NOTHING` 占到了才记账（幂等键 `deposit:<block_hash>:<log_index>`，借镜像账户 `chain:custody:<SYMBOL>`、贷商户账户）。反过来先记账再占坑不安全：别的实例可能已把同一条判成 HELD。
- **信合约做的，不信合约说的**：记账前向两个节点问该地址在那一块的 `balanceOf`，必须等于事件累计，否则 HELD_BALANCE_MISMATCH。
- 失败分三种：拿不到回答 → 这一轮提前结束；`RpcAuthException` → 这一轮 HALTED，调度器关闸门、之后不再碰节点，换 key 后重启才再试；
  余额问不到时按 `RpcFailure` 分类——合约 revert 当场 HELD，不认识的错误码只把这一笔延后、连续 5 轮才 HELD。
- HELD 永不自动变 CREDITED、不卡队列；人复核后把行改成 APPROVED 并写明谁、为什么，任务下一轮重新占坑记账。**人永远不手工碰账本表。** 每种状态见 `docs/runbook/deposit.md`。
- 商户接口：分配一户一币一址且幂等，地址给 EIP-55 写法；查询整段在 `asMerchant` 里；没有「按 id 查一条」，「不存在」与「不是你的」无从区分；HELD 只露状态不露原因。

### 付款

- **账本先扣、链上后发生**：申请时 可用 → 冻结（`WITHDRAWAL_FREEZE`，商户连接）；FINAL 后 冻结 → 托管镜像（`WITHDRAWAL`，系统身份）；失败 冻结 → 可用（`WITHDRAWAL_REVERSE`）。
  结算过的不能再解冻，由「冻结账户不许为负」守，不靠代码记得。
- 表：`hot_wallet`（`next_nonce` 是意图，真相在链上）、`payout`（每笔以冻结开始；结算与解冻互斥；状态与结局一一对应）、
  `payout_tx`（签好的原文先落库再广播；同编号只有一笔 MINED 由部分唯一索引守）、`payout_address`（白名单）。系统角色对这几张表都没有 DELETE。
- 状态机是显式转换表（`PayoutStatus`、`PayoutTxStatus`）。**BROADCAST 没有到 FAILED 的边**：广播后只有回执能宣布结局。
- **发送**（`PayoutSender.sendOnce`，顺序是硬的）：对账（链上计数 C 在事务外问；事务里锁热钱包行，核 C ≤ N ≤ C + U：C > N 是有人在别处用了这把钥匙，N > C + U 是有编号没有尝试记录，都整把钱包 HALTED，**重启不算恢复**）
  → 原样重发所有 SIGNED 的尝试 → 排队的：事务外估 gas、取费率，事务里锁行、签名、落库、编号 +1，**提交之后才广播**。网络永远不在事务里。
- 广播的回答：成功 / already known → BROADCAST；没有 code（传输失败）→ 下一轮重发同一份原文；nonce too low → 看节点认不认识我们的哈希或兄弟尝试；
  其它带 code 的拒绝与 `underpriced` → 钱包 HALTED。四处问节点的地方都先接 `RpcAuthException` → 钱包 HALTED → 告警。
- **追踪**（`PayoutTracker`）只读链、只改状态、只在最后一步记账：status 0 也是上链（编号已用、gas 已扣）；主节点说那块哈希变了 = 重组，退回 BROADCAST；
  两个节点的 finalized 都过了那块且哈希一致才结算或解冻。卡单（广播超过 3 分钟、没回执、节点还认着）→ 同编号加价 25% 替换，两个费率都有上限。
- **申请**（`WithdrawalService.request`，顺序是硬的）：代币 → 金额 → 锁本商户的 merchant 行（同一商户的申请从这里起串行）→ 幂等键 → 平台自己的地址永远拒绝（问库里的是 / 否函数；热钱包要等发送任务第一轮对账、库里有了它那一行才认得出）→ 白名单 → 按限额定「放行 / 待核准」→ 冻结 → 插行（冻结与插行同一事务）。
  限额没定过 = 一律人工；当日上限只算自动放行过的。
- **已知缺口**：发送任务签名前不复核白名单、限额、核准与冻结；入账不重新派生收款地址——worker 不能信 web 写进库里的行（进程拆分第 ③ 步修）。
  商户用应用角色仍能插入借方是冻结账户的转账（没有接口会这么做；数据库层的写入检查用户定先不做）。
- 每种停发原因该做什么见 `docs/runbook/payout.md`，真环境演练步骤见 `docs/runbook/payout-drill.md`。给热钱包充币前先核对地址。

### 对账

- `AuditService.runOnce()` 只读、只报，不改任何业务表。脚下的块 F = min(`chain_head.finalized`, 索引书签)：证据和事实必须是同一个时刻的；两个节点取 F 的块头，哈希不符就 FAILED，不给结论。
- 五条检查：ADDRESS_BALANCE、DEPOSIT_LEDGER、PAYOUT_LEDGER（热钱包发出的每条日志都要对应一次尝试，否则就是有人在别处用了这把钥匙）、
  CUSTODY_TOTAL（**C = |镜像| + 已 FINAL 未入账 − 已上链未结算 + 已登记注资 + E**，E ≠ 0 就报）、LEDGER_JUDGE。
- 每一轮都落一行 `audit_run`（OK / DIFF / FAILED），差异逐条落 `audit_finding`，两张表只追加；「没跑」和「跑了没事」分得开（上次结论超过两个周期 = stale）。
- 运营往热钱包充的币要登记（`hot_wallet_funding`）：只指认是哪一笔，金额、块、代币从索引到的日志读；块 ≤ finalized 才收。每种差异该做什么见 `docs/runbook/audit.md`。

### 上线与运维

- **健康检查**只在回环的管理端口（8096）上答，主端口没有 `/actuator`（探针不带令牌）。三个组：liveness（进程在不在）、readiness（`db`：两个连接池；容器 HEALTHCHECK 打它）、
  work（索引器 / 入账 / 热钱包 / 判官 / Redis：给告警和人看，不影响进程去留）。多一档 DEGRADED（HTTP 200）。细节里没有密钥。
- **容器**：两阶段构建、JRE、非 root、按层 COPY；密钥只从 `env_file` 来，compose 的 `environment` 只放拓扑与角色；端口只绑宿主回环；
  去掉全部能力、禁止提权、根文件系统只读、限内存与进程数；`restart: unless-stopped`。镜像打完必须跑 `tools/image-check.sh`。
- **告警**：定时读 work 组，只在变化时叫，送到了才记下；webhook 地址按密码对待（日志只出主机名）。
- **部署**（`deploy/deploy.sh`，任一步失败即停）：构建（标签由内容决定）→ 配置检查 → 镜像扫描 → **迁移单独一步**（`--migrate-only`）→ 打标签（`current` / `previous` 就是发布记录）→ 切换 → 等就绪 → 不过就回滚。
  一次性模式只认精确的 `--migrate-only` / `--create-admin`，写错一个字就退出码 2、什么都不跑。
- **迁移只前进**：上一版代码必须能跑在新 schema 上（先加后删），Flyway 容忍库里的未来版本。**改列类型不满足这一条**（包括改成 DOMAIN）：运行中实例缓存的预编译语句在事务里报 SQLSTATE 0A000。
  **已执行过的迁移只能往前加，不能改**（Flyway 按文件内容算校验和，连注释也算）。
- 这台 Mac 的容器内 TLS 偶发断手，构建失败先重跑。操作细节见 `docs/runbook/ops.md`。

### 接口与安全

- **签名协议 CP2**：`canonical = "CP2" LF ts LF nonce LF method LF path(含查询串) LF sha256hex(body)`，签名是 Base64(HMAC-SHA256)。每段要么定长要么不含换行，拼接才无歧义；
  三处副本必须一字不差：`ApiCredentialService.prehash`、测试的 `SignedRequests`、`tools/api.py`。
- nonce 的格式在验签之前查（定长是拼接无歧义的前提），重放登记在验签之后；凭证未命中也做一次诱饵解密与 HMAC，两条失败路径耗时相等。
- 每个 `@RequestBody` 都带 `@Valid`；校验失败 / JSON 不可读 / 参数缺失或类型不匹配一律 400 + 2001；框架认得的异常由 `ResponseEntityExceptionHandler` 判状态码，不落进兜底的 500。
- **没有任何 HTTP 入口能自己选借贷双方**：controller 里不许出现账本的转账代码与业务类型（`ControllerBoundaryTest`）。
- 调度线程数 ≥ `@Scheduled` 任务数（Spring 默认只有一条，一个任务等锁时全体停摆）。系统连接带 `lock_timeout`，并把 55P03 翻成瞬时错误——**加超时的同时要问：超时会被翻成哪一类。**
- 新依赖进树那天，回头清点原先因为「没有库」而手写的每一段。

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

- **拆墙**：在临时拷贝里一次拆一处承重的东西，确认该红的测试红。恢复原状后要确认编译产物也恢复了——`rsync -a` 连旧的修改时间一起恢复，Maven 可能沿用旧 class；拿不准就清掉 `target` 再跑。
- **删接口会制造空转测试**：打这个接口的测试可能两边都拿到 404、照样绿，却什么都不证明。删接口时把相关测试逐条看过，不能只看红的那些。
- **单个向量证明不了「永远」**：要证明一条规律，就要让测试覆盖到那条规律会被违反的样本。

---

## 6. Git

### 提交前必须先让用户看变更集

AI 写完代码后不要直接 commit：写完 + 跑测试 → 把改动留在工作区，告诉用户「改了哪些文件、每个文件改了什么、重点看哪几行」→ 用户说「提交」才提交，说「push」才推送。
用户不写代码，判断力是他唯一的把关手段；代码一旦进了 commit，就从 IDE 的 Changes 面板里消失了。

- 暂存按显式路径，**禁止 `git add -A` / `git add .`**。
- 误提交且未推送：`git reset --soft HEAD~1`（首次提交用 `git update-ref -d HEAD`），文件不动、全部退回暂存区；提交信息先存到临时文件，确认后原样重新提交。
- `env/*.env`、私钥、助记词**绝不入库**（`.gitignore` 已配，但别依赖它）。

---

## 7. 私钥

- 绝不进代码、镜像层、日志。镜像的层不可变：`COPY` 进去再 `RUN rm`，密钥仍在前一层里。测试网私钥也按真密钥对待——习惯是练出来的。
- **服务端没有收款私钥**：收款地址从账户层 xpub（m/44'/60'/0'）做 BIP-32 普通派生，助记词与 xprv 从头到尾不进服务器；主代码只有 `chain/wallet` 能碰私钥数学（`WalletBoundaryTest` 守）。
  xpub 泄露 = 隐私全丢（能枚举全部收款地址），但转不走钱。**xpub 加任意一个普通派生的子私钥 = 父私钥**，所以绝不能把收款树里的某个子私钥单独交出去。
- **热钱包私钥**是服务端唯一的私钥：从 `CHAINPAY_PAYOUT_HOT_WALLET_KEY` 装入一次，只活在 `HotWalletSigner` 里（只暴露地址与签名，`toString` 只含地址，错误消息只说长度与范围）。
  它必须与收款树隔离：另一句助记词（`tools/mnemonic.sh`），或同一句的硬化账户 `m/44'/60'/1'/0/0`（`tools/hotwallet.sh`）；**绝不能**从日常钱包导出收款树里某个账户的私钥当热钱包。payout 包只能拿签名器，拿不到裸私钥。
- 离线工具（`tools/xpub.sh`、`hotwallet.sh`、`mnemonic.sh`）断网跑，不回显、不落盘、不记日志。`tools/check-secrets.sh` 扫仓库与任意目录里的私钥、xprv、助记词形态，命中只打印前 6 位；公开测试密钥在 `tools/check-secrets.allow` 里逐值放行（`SecretScanTest` 守）。
- **签名的两条会丢钱的规矩**：k 由 RFC 6979 确定地算出（两笔撞同一个 k 就能解出私钥）；s 取 ≤ n/2（EIP-2，否则同一笔交易有两个哈希）。
- **原语与协议编码用库，策略与业务自己写**：密码学原语用 BouncyCastle，RLP / EIP-1559 / 签名打包与恢复用 web3j 的 `crypto` 模块，我们只留不依赖 web3j 类型的薄包装，换库只动这一层；
  解码后编回必须与原文逐字节相同（节点会拒的我们不能比它宽松）。JSON-RPC 客户端、BIP-32 / 39 与 xpub 解析、`Abi`、`EthAddress`、`Hex` 自写（策略比库严），测试里与库对拍。**测试是我们的，实现可以是库的。**
- **已知答案必须来自原始文本**：规范向量用浅克隆取原文逐字核对，不经任何转述（一次经模型转述的 BIP-32 向量抄错了一个字母）。
