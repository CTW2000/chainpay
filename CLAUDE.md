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
│   └── service/         ★ 账本核心，禁止 ORM（见下）
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
SELECT * FROM ledger_invariant WHERE total <> 0;   -- 必须 0 行
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

| | 谁用 | 看到什么 |
|---|---|---|
| `TenantScope.asMerchant(id, …)` | HTTP 控制器 | 只有该商户的行 |
| `TenantScope.asSystem(…)` | 注资、结算、M3 入账、M4 出账 | 全部行 |

**已知权宜之计**：`asSystem()` 是用会话变量模拟的 `BYPASSRLS`，
靠「控制器不得调它」这条纪律守着——一个 public 方法，任何代码都能调。
**M3 落地第一个真实的系统操作（入账）时，升级为独立的 system 角色 + 独立连接池**，
让系统权限变成连接身份而不是一个开关。在那之前接口多起来了，先用 ArchUnit
断言 `controller` 包不得引用 `asSystem`。

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
- **RPC 不信任**（M2-⑤）：三种错三种对策。大声的错（带 code 的 error）：getLogs 窗口对半分、成功后翻倍回 `batch-blocks`，减到一块还失败就停下；安静的错（getLogs 静默漏日志，回执才是事实源）：每次轮询抽 `reconcile-samples` 个已 finalized、已索引的块用 `eth_getBlockReceipts` 重数，差异**两个节点都点头才动，点的是内容不只是坐标**（补录要求两个节点给的内容一致 / 标废 / 只有一方点头或内容不同 = disputed 等人看），只把有差异的检查记进 `chain_reconcile`；自相矛盾的错：只核对 finalized 那一块，两个节点意见不同 = `FinalityViolationException`，头部的分歧不管
- 客户端对「发出到正文读完」整段计时，正文 16 MB 封顶。审计节点 `CHAINPAY_CHAIN_AUDIT_RPC_URL` 要独立于主节点才有价值（同一家两台机器会被同一个 bug 骗过）；不设时退化为主节点自己的回执路径，能抓索引漏日志，抓不住节点整体撒谎
- **代币白名单**（M2-⑥）：Transfer 事件是合约「说」的，余额是合约「做」的；事件金额只对行为规范的代币等于到账金额。只索引、只入账 `chain_token` 里 ACTIVE 的代币；登记时用 `eth_call` 问链上的 `decimals()` 与 `symbol()`，问不到要运营手工填并注明来源；轮询第一次推批前核对链上 decimals 与表一致，不一致 = 停下。symbol 是从别人的合约里解出来的：Java 侧空白或超过 64 字符当问不到，V14 的 CHECK 兜底（note ≤ 500）。ABI 解码里偏移字与长度字是对方给的 32 字节的数，**先在 BigInteger 上比过实际字节数再收窄**，否则 2^31 抛 ArithmeticException、2^30 乘 2 溢出成负数绕过边界检查；形状不对只允许抛 `IllegalArgumentException`，调用方只接这一种
- **金额换算只经 `TokenAmounts.toLedger`**：精确除法、永不四舍五入；整数位超过 20 或 decimals 超过 18 抛 `AmountOverflowException`，M3 把那笔标成「无法入账、等人看」而不是让它卡住循环。铸币（from 为 0x0）按普通入账；不发事件的铸币我们看不见、不入账，留给 M5 用 `balanceOf` 对账发现
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
