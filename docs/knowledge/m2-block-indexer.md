# M2 · 区块索引器 —— 知识补充（2026-09-02 从 web 采集 + 本机实测）

> 按「谁在什么时候读」组织，不按来源组织。每条都标了出处；带 ★ 的是本机实测。
> 这份只记**事实**。「这一步会怎么坏」在 `docs/retro/M2-before.md`，按 §3 只提问不给答案。

---

## 一、读 JSON-RPC 时要知道的（协议事实）

### 区块标签：不止 `latest`
| 标签 | 含义（ethereum.org 原文） | Sepolia 实测 ★ |
|---|---|---|
| `latest` | the latest proposed block | 11 618 283 |
| `safe` | the latest safe head block | 11 618 248（落后 35） |
| `finalized` | the latest finalized block | 11 618 217（落后 66 ≈ 2 个 epoch） |
| `pending` / `earliest` | 待打包 / 创世 | — |

`safe` 与 `finalized` 是合并（The Merge）后才有的，**只有以太坊主网/测试网与 Arbitrum One 支持**；
其它 EVM 链没有这两个标签。它们能直接进 `eth_getLogs` 的 `fromBlock`/`toBlock`。

### `eth_getLogs` 的过滤器与返回
过滤器字段：`fromBlock` `toBlock` `address`（单个或数组）`topics`（**顺序相关**的 32 字节数组）`blockHash`（限定单个区块，与 from/to 互斥）。
每条 log：`address` `topics` `data` `blockNumber` `blockHash` `transactionHash` `transactionIndex` `logIndex` **`removed`**。

> `removed`: `true` when the log was removed, due to a chain reorganization. `false` if its a valid log.
> —— 这是协议层给出的**重组信号**之一，但只在订阅/过滤器语境下出现，`eth_getLogs` 轮询拿到的基本都是 `false`。

### 提供商对 `eth_getLogs` 的限制（各家不一样，且不告诉你）
| 提供商 | 限制（SQD 2026 年中实测） |
|---|---|
| Alchemy / Infura | 区块范围 ≤ 2 000 不限条数；**或**任意范围但 ≤ 10 000 条 |
| 1rpc.io | 50 个区块/次 |
| eth.merkle.io | 1 000 个区块/次 |
| rpc.mevblocker.io | 10 000 条/次，超了报 `query returned more than 10000 results. Try with this block range [...]` |
| publicnode | 只服务近期区块（归档数据收费） |

标准做法：**自适应对半分**——请求一个窗口，撞上限就把范围减半重试，拼接结果。固定页大小要么浪费请求要么溢出。

### `eth_getLogs` 会**静默漏掉**日志
SQD 记录的 Polygon 区块 74 614 768：`eth_getLogs` 返回 848 条，`eth_getTransactionReceipt` 对同一笔 state-sync 交易多返回 8 条，实际 856 条。
根因：`eth_getLogs` 通常从 `logsBloom` 建的索引里取，bloom 没收录的日志只有节点刻意补进去才有。**响应里没有任何字段提示"少了"。**
（Polygon 的 state-sync 是特例，但"回执是事实源、getLogs 是索引"这个关系在所有链上成立。）

### Sepolia 公共 RPC ★
| 端点 | 结果 |
|---|---|
| `https://ethereum-sepolia-rpc.publicnode.com` | **可用，无需 key** |
| `https://rpc.sepolia.org` | 404，已下线 |
| `https://sepolia.drpc.org` | `chain is not available on free plan` |
| `https://sepolia.gateway.tenderly.co` | **可用，无需 key，支持 `eth_getBlockReceipts`**，对同一块的哈希与 publicnode 一致（2026-09-03 实测）|
| `https://1rpc.io/sepolia` | 可用，但 `eth_getBlockReceipts` 报 `Method not allowed`（2026-09-03）|
| `eth-sepolia.public.blastapi.io` / `blockpi` / `rpc2.sepolia.org` | 403 / 521 / 超时（2026-09-03）|
Chain ID `11155111`。
★ 2026-09-03 实测两条：publicnode 支持 `eth_getBlockReceipts`（一个块 146 张回执、296 条日志），但**连续几十次请求会被 403 限流**，对账要慢慢抽。同一个块（11625117）不带地址过滤的 `eth_getLogs`：publicnode 第一次返回 0 条、第二次连接在正文读到 73 KB 时被掐断（`IncompleteRead`），而它自己的回执有 260 条、tenderly 的 getLogs 也是 260 条——「回执是事实源、getLogs 是索引」和「正文要整段计时」都在真节点上撞见了
Alchemy/Infura 免费档需要 API key（放 env，不给默认值，同 M1 的密钥纪律）。

---

## 二、读 Transfer 事件时要知道的（ERC-20 事实）

### EIP-20 原文（eips.ethereum.org/EIPS/eip-20）
- `event Transfer(address indexed _from, address indexed _to, uint256 _value)`
- **MUST** trigger when tokens are transferred, **including zero value transfers**.
- 铸币 **SHOULD** trigger a Transfer event with `_from` set to `0x0`（是 SHOULD 不是 MUST——有的代币铸币不发事件）。
- `decimals()` 是 **OPTIONAL**：interfaces and other contracts MUST NOT expect these values to be present.
- 转账：SHOULD throw if balance insufficient；**Callers MUST handle `false` from `returns (bool success)`. Callers MUST NOT assume that `false` is never returned!**
  （EURS、BAT 等返回 false 不 revert；OpenZeppelin 一律 revert，两种都"合规"。）

### 事件解码 ★
`topic0 = keccak256("Transfer(address,address,uint256)") = 0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef`
标准 Transfer 有 **3 个 topics**（topic0 + from + to，地址左补零到 32 字节），`data` 是一个 uint256（`0x` + 64 位十六进制 = 66 字符）。
Sepolia 上的 LINK（`0x779877A7B0D9E8603169DdbD7836e478b4624789`）最近 500 个区块内有 20 条 Transfer，形态与上述一致——可作 M2 的索引对象。

### 哪些"奇怪的 ERC-20"会让「事件里的 value ≠ 实际到账」（d-xo/weird-erc20）
| 类别 | 对链下索引器的影响 |
|---|---|
| **Fee on Transfer**（转账扣费） | **事件 value ≠ 收款方实际增加的余额** |
| **Balance Modifications Outside Transfers**（rebasing / 空投） | **余额变了但没有任何 Transfer 事件** |
| **No Revert on Failure**（失败返回 false） | 有实现会**先发事件再返回 false**，事件不代表成功 |
| **Transfer of Less Than Amount**（`amount == uint256.max` 时只转余额） | 事件 value ≠ 实际转移量 |
| Missing return values / Pausable / Blocklist / 低或高 decimals / 多地址代理 | 事件本身仍准确，不影响索引（decimals 影响**展示与精度**，见下） |

结论（weird-erc20 + 审计惯例）：**信任事件 value 作为余额变化量，只对"行为规范"的代币成立**。链上合约的惯例是「转账前后各读一次余额，取差值」；链下索引器没有这个手段，只能靠白名单代币或用 `balanceOf` 对账。

### 精度：uint256 vs NUMERIC(38,18)
uint256 最大值 ≈ 1.16 × 10^77（78 位十进制）。我们账本列是 `NUMERIC(38,18)`：**整数部分只有 20 位**。
一个 18 位小数、总量 10^15 的代币（如 SHIB 量级），单笔转账的原始单位可达 10^33，**装不进整数部分**。
这条不在任何清单里，是从 EIP-20 的 `uint256` 和 V1 的列定义直接推出来的。

---

## 三、决定「什么时候算数」时要知道的（确认与重组）

### 以太坊 PoS 的最终性
- 一个 slot 12 秒，一个 epoch 32 个 slot（6.4 分钟）。检查点先被 2/3 质押投票**justified**，下一个 epoch 的检查点也 justified 时前一个变 **finalized**：**约 2 个 epoch ≈ 12.8 分钟**。
- 分叉选择（LMD-GHOST）与检查点最终性（Casper FFG）是两套机制：**finalized 之前的头部可以被重组**；回滚 finalized 区块需要至少 1/3 质押被罚没，经济上不可行。
- `safe`：不太可能被重组，除非大规模协同攻击；`finalized`：不可逆。Sepolia 实测 safe 落后 35、finalized 落后 66 个区块 ★。

### 重组真的发生过
| 事件 | 深度 | 后果 | 出处 |
|---|---|---|---|
| 以太坊信标链 2022-05-25 | **7 个区块**（3 887 075–3 887 081） | 无损失；原因是迟到的提案 + 客户端对 proposer-boost 修复采用不一 | Decrypt / Coinparative |
| Ethereum Classic 2019-01 51% 攻击 | **100+ 个区块** | 双花约 **$1.1M**；Coinbase 检测到深度重组，Gate.io 确认多笔双花 | dwellir |
| Ethereum Classic 2020-08 三次 51% 攻击 | **3 693 / 4 000+ / 7 000+ 个区块** | 同月三次 | dwellir |

### 交易所的确认数（别人用钱买来的数字）
| 交易所 | ETH / ERC-20 充值 | 说明 |
|---|---|---|
| Binance | **12** 个确认（从 30 降到 12） | 充提同一标准 |
| OKX | **32** 个确认（2023-11-15 从 64 降到 32） | **提现所需确认数不变**——入账与出金是两个门槛 |

### 索引器的重组处理（Envio / QuickNode / Chainbase 的共同模式）
1. 每处理一个区块，**存 hash 和 parentHash**；下一个区块的 parentHash 必须等于上一个存的 hash，不等 = 重组。
2. 沿 parentHash 往回走，直到找到和链上一致的**共同祖先**。
3. **回滚**共同祖先之后所有派生数据，从共同祖先重新处理。
4. **外部副作用回滚不了**（已发的通知、已调的外部接口、已写的日志）——Envio 文档明写。
5. 有一个"最大回滚深度"：Envio 所有链默认 **200 个区块**（`max_reorg_depth`）；超过它的重组要人工介入。
6. 用自定义 RPC 时"有些重组可能检测不到，取决于提供商实现"——多提供商之间数据可能不一致。
7. 缓解手段：**延迟 N 个区块再处理**（"Latest Block Delay"），概率性地避开短重组。

---

## 四、写代码前的技术选型

### web3j 5.0.3（2026-01-21 发布，需 Java ≥ 21）★ 直接依赖
```
org.web3j:abi / crypto / tuples
com.squareup.okhttp3:okhttp:4.12.0 + logging-interceptor
io.reactivex.rxjava2:rxjava:2.2.21
org.java-websocket:Java-WebSocket:1.5.6
com.github.jnr:jnr-unixsocket:0.38.22
tools.jackson.core:jackson-databind:3.1.0     ← Jackson 3，和 Boot 4 同代，不冲突
io.consensys.tuweni:tuweni-bytes / tuweni-units
software.amazon.awssdk:kms:2.27.24            ← 一个索引器要拖进整个 AWS KMS SDK
```
好消息：它已经迁到 Jackson 3，和 Spring Boot 4 不打架。坏消息：为解码一个 Transfer 事件要背 OkHttp、RxJava2、WebSocket、Unix socket、AWS SDK。

### 裸 JSON-RPC 的成本 ★
`java.net.http.HttpClient`（JDK 自带）+ Jackson 3（已在 classpath）。Transfer 解码：`topics[1]`、`topics[2]` 各取后 20 字节为地址，`data` 转 `BigInteger`。约 30 行，没有隐式行为。
M0 的取舍「账本层抽象越薄越好、SQL 看得见」在这里同样适用：**索引器是账本的上游，它出错就是账本出错**。

---

## 来源
- Ethereum JSON-RPC（block tags / eth_getLogs / `removed`）：https://ethereum.org/en/developers/docs/apis/json-rpc/ ；规范：https://ethereum.github.io/execution-apis/
- EIP-20：https://eips.ethereum.org/EIPS/eip-20
- weird-erc20：https://github.com/d-xo/weird-erc20 ；fee-on-transfer/rebase 解读：https://medium.com/@0xnolo/fee-on-transfer-rebase-tokens-an-erc-20-security-bug-you-need-to-know-f4e5badea1ee
- eth_getLogs 限制与漏日志：https://sqd.dev/learn/eth-getlogs-limits/ ；Alchemy：https://www.alchemy.com/docs/chains/ethereum/ethereum-api-endpoints/eth-get-logs ；QuickNode 10k 区块限制：https://support.quicknode.com/hc/en-us/articles/10258449939473 ；Chainstack：https://docs.chainstack.com/docs/understanding-eth-getlogs-limitations
- 重组处理模式：https://docs.envio.dev/docs/HyperIndex/reorgs-support ；https://www.quicknode.com/docs/streams/reorg-handling ；https://platform.chainbase.com/blog/article/unraveling-reorgs-problems-the-chainbase-approach
- 重组事件：信标链 7 块 https://decrypt.co/101390/ethereum-beacon-chain-blockchain-reorg ；ETC 51% https://www.dwellir.com/blog/what-is-a-chain-reorg
- PoS 最终性：https://ethereum.org/developers/docs/consensus-mechanisms/pos/ ；承诺级别：https://www.alchemy.com/overviews/ethereum-commitment-levels ；攻防：https://ethereum.org/developers/docs/consensus-mechanisms/pos/attack-and-defense/
- 交易所确认数：Binance 12 https://www.binance.com/en/support/announcement/binance-reduces-the-number-of-confirmations-required-for-deposits-withdrawals-on-btc-and-eth-networks-360030775291 ；OKX 32 https://www.okx.com/en-us/help/okx-to-reduce-the-number-of-confirmations-required-for-deposits-on-eth
- web3j：https://github.com/LFDT-web3j/web3j ；https://blog.web3labs.com/web3j-5-0-2-a-community-release-that-moves-us-forward/ ；依赖列表为本机 `~/.m2` 中 `core-5.0.3.pom` 实读
