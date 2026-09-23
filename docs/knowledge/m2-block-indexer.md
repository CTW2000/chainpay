# M2 · 区块索引器：背景知识与取舍

> M2，2026-09-02 至 09-03 完成（09-04 至 06 补丁）。这份记索引器依赖的外部事实（带出处）和由此定下的做法；
> 规则见 CLAUDE.md「链数据与索引器」，运维见 `docs/runbook/chain-indexer.md`。带 ★ 的是本项目 2026-09 在 Sepolia 上实测（外部服务的行为会变）。

---

## 一、JSON-RPC

### 区块标签
| 标签 | 含义（ethereum.org） | Sepolia 实测 ★ |
|---|---|---|
| `latest` | the latest proposed block | — |
| `safe` | the latest safe head block | 落后 latest 约 35 块 |
| `finalized` | the latest finalized block | 落后约 66–88 块；一笔转账从进块到 FINAL 约 13–18 分钟 |

`safe` / `finalized` 是合并之后才有的，**只有以太坊主网、测试网与 Arbitrum One 支持**；它们能直接用作 `eth_getLogs` 的 `fromBlock` / `toBlock`。

### `eth_getLogs`
- 过滤器：`fromBlock` `toBlock` `address`（单个或数组）`topics`（**按位置匹配**的 32 字节数组）`blockHash`（限定单块，与 from/to 互斥）。
- 每条 log 带 `blockNumber` `blockHash` `transactionHash` `logIndex` 与 `removed`。`removed = true` 表示因重组被移除，但它只在订阅 / 过滤器语境下出现，轮询拿到的基本都是 `false`。

**提供商的限制各不相同，而且不告诉你**（SQD 2026 年中整理）：Alchemy / Infura 范围 ≤ 2 000 块不限条数，或任意范围 ≤ 10 000 条；1rpc.io 50 块；eth.merkle.io 1 000 块；
rpc.mevblocker.io 10 000 条（超了报 `query returned more than 10000 results. Try with this block range [...]`）；publicnode 只服务近期区块。
★ **Alchemy 免费档一次最多 10 块**，超了回 **HTTP 400** + JSON-RPC error（code -32600，message 给出可用范围）——先看状态码的客户端会把 code 丢掉。

**`eth_getLogs` 会静默漏日志**：SQD 记录的 Polygon 区块 74 614 768，getLogs 返回 848 条，回执里实际 856 条。getLogs 通常从 `logsBloom` 建的索引里取，**响应里没有任何字段提示「少了」**。
Polygon 的 state-sync 是特例，但**回执是事实源、getLogs 是索引**在所有链上成立。
★ Sepolia 上撞见过：publicnode 对块 11625117 的无过滤 getLogs 一次返回 0 条、一次在正文读到 73 KB 时连接被掐断，而它自己的回执与 Tenderly 的 getLogs 都是 260 条。

### Sepolia 节点 ★
Chain ID `11155111`。
- `ethereum-sepolia-rpc.publicnode.com`：免 key，支持 `eth_getBlockReceipts`；**连续几十次请求会被 HTTP 403 限流**。
  客户端把 401 / 403 一律当成凭证失效、停下叫人，拿它当节点时，一次限流也会被当成这一类。
- `sepolia.gateway.tenderly.co`：免 key，支持 `eth_getBlockReceipts`，同一块的哈希与 publicnode 一致。
- Alchemy / Infura：要 key，key 就在 URL 里（整条 URL 按密码对待）。
- `1rpc.io/sepolia` 不给 `eth_getBlockReceipts`（`Method not allowed`）；`sepolia.drpc.org` 回 HTTP 200 + error（code 35，`chain is not available on free plan`），只看状态码会把报错当成功；
  `rpc.sepolia.org`、blastapi、blockpi、`rpc2.sepolia.org` 不可用（404 / 403 / 521 / 超时）。

---

## 二、ERC-20 Transfer 事件

### EIP-20 原文（eips.ethereum.org/EIPS/eip-20）
- `event Transfer(address indexed _from, address indexed _to, uint256 _value)`，**MUST** trigger when tokens are transferred, **including zero value transfers**。
- 铸币 **SHOULD**（不是 MUST）发 `_from = 0x0` 的 Transfer——有的代币铸币不发事件。
- `decimals()` 是 **OPTIONAL**，调用方不能假定它存在。
- 调用方 **MUST** 处理 `false` 返回值：EURS、BAT 等失败返回 false 不 revert，OpenZeppelin 一律 revert，两种都合规。

### 解码 ★
`topic0 = keccak256("Transfer(address,address,uint256)") = 0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef`。
标准 Transfer 有 3 个 topics（topic0 + from + to，地址左补零到 32 字节），`data` 是一个 uint256（`0x` + 64 位十六进制）。索引对象是 Sepolia LINK（`0x779877A7B0D9E8603169DdbD7836e478b4624789`）。

### 事件金额 ≠ 实际到账的代币（d-xo/weird-erc20）
| 类别 | 对链下索引器的影响 |
|---|---|
| **Fee on Transfer**（转账扣费） | 事件 value ≠ 收款方实际增加的余额 |
| **Balance Modifications Outside Transfers**（rebasing / 空投） | 余额变了，没有任何 Transfer 事件 |
| **No Revert on Failure** | 有实现**先发事件再返回 false**，事件不代表成功 |
| **Transfer of Less Than Amount**（`amount == uint256.max` 时只转余额） | 事件 value ≠ 实际转移量 |

（Missing return values、Pausable、Blocklist、高低 decimals、多地址代理：事件本身仍准确。）
结论：**把事件 value 当余额变化量，只对行为规范的代币成立。** 合约里的惯例是转账前后各读一次余额取差值，链下索引器没有这个手段，只能靠白名单加 `balanceOf` 核对。

### 精度
uint256 最大 ≈ 1.16 × 10^77（78 位十进制），账本列 `NUMERIC(38,18)` 的**整数部分只有 20 位**：一个 18 位小数、总量 10^15 的代币（SHIB 量级），单笔原始单位可达 10^33，装不进。
这条不在任何清单里，是从 EIP-20 的 `uint256` 和账本列定义推出来的——所以链上原始值按 `NUMERIC(78,0)` 存，进账本才换算，装不下的那一笔单独 HELD。

---

## 三、确认与重组

### PoS 的最终性
- slot 12 秒，epoch 32 个 slot（6.4 分钟）。检查点先被 2/3 质押投票 **justified**，下一个 epoch 的检查点也 justified 时前一个变 **finalized**：约 2 个 epoch ≈ 12.8 分钟。
- 分叉选择（LMD-GHOST）与检查点最终性（Casper FFG）是两套机制：**finalized 之前的头部可以被重组**；回滚 finalized 要至少 1/3 质押被罚没，经济上不可行。`safe` 除非大规模协同攻击不太可能被重组。

### 重组真的发生过
| 事件 | 深度 | 后果 | 出处 |
|---|---|---|---|
| 以太坊信标链 2022-05-25 | 7 块（3 887 075–3 887 081） | 无损失；迟到的提案 + 客户端对 proposer-boost 修复采用不一 | Decrypt / Coinparative |
| Ethereum Classic 2019-01 51% 攻击 | 100+ 块 | 双花约 $1.1M；Coinbase 检测到深度重组，Gate.io 确认多笔双花 | dwellir |
| Ethereum Classic 2020-08 三次 51% 攻击 | 3 693 / 4 000+ / 7 000+ 块 | 同月三次 | dwellir |
| Sepolia 2026-09-08 ★ | 3 块 | 索引器自动退书签、重放，标废 0 行 | 本项目 |

### 交易所的确认数
Binance ETH / ERC-20 充值 **12** 个确认（从 30 降到 12，充提同一标准）；OKX **32**（2023-11-15 从 64 降到 32），**提现所需确认数不变**——入账与出金是两个门槛。

### 通行的重组处理（Envio / QuickNode / Chainbase）
每块存 hash 与 parentHash，下一块的 parentHash 对不上 = 重组；沿 parentHash 往回走到共同祖先，回滚其后的派生数据再重放。
**外部副作用回滚不了**（已发的通知、已调的外部接口）——Envio 文档明写。Envio 所有链默认最大回滚深度 **200 块**（`max_reorg_depth`），超过要人工，且用自定义 RPC 时「有些重组可能检测不到」。
另一种缓解是**延迟 N 块再处理**，概率性地避开短重组。

---

## 四、取舍（理由；规则见 CLAUDE.md「链数据与索引器」）

- **JSON-RPC 客户端自己写**（JDK `HttpClient` + 已在类路径上的 Jackson 3），不用 web3j：web3j 5.0.3 的 core 直接拖进 OkHttp、RxJava2、WebSocket、jnr-unixsocket、tuweni、AWS KMS SDK，
  而整段超时、正文封顶、错误分类、两节点核对都是策略，不是轮子；索引器是账本的上游，它出错就是账本出错。签名编码后来用了 web3j 的 `crypto` 模块（只拿它），见 `m4-payout.md` 取舍 3。
- **钱绑 FINAL，不用「延迟 N 块」或「最大回滚深度」这类数字**：PoS 已经把「多深不会翻」交给了协议。确认等级不存，由视图按 `chain_head` 算；重组回滚因此只碰链表、不碰账本。
- **重组靠父哈希发现，不靠 `removed`**（代码只解析不用）。我们知道哈希的只有书签那一块、有日志的块和存下的 finalized 头，中间的块不知道，
  所以不像通行做法那样沿 parentHash 逐块回走，而是取「能证明一致的最高一块」当祖先——可能比真正的分叉点低，**多退不伤，少退要命**。
- **getLogs 窗口记住天花板**：撞上限对半分，之后向失败过的尺寸二分逼近，不在成功后翻倍撞回去。★ 在 Alchemy 免费档的 10 块上限上，「对半分、成功翻倍」会震荡（12 败、6 成、翻倍到 12 再败），每轮只前进 60–100 块。
- **代币会说谎**：索引器只信事件，所以只索引白名单里的代币；事件与到账对不上的，由入账前的 `balanceOf` 核对与对账的 ADDRESS_BALANCE 发现（`m3-payin.md`、`m5-reconciliation.md`）。

---

## 出处
- Ethereum JSON-RPC（block tags / eth_getLogs / `removed`）：https://ethereum.org/en/developers/docs/apis/json-rpc/ ；规范：https://ethereum.github.io/execution-apis/
- EIP-20：https://eips.ethereum.org/EIPS/eip-20
- weird-erc20：https://github.com/d-xo/weird-erc20 ；fee-on-transfer / rebase 解读：https://medium.com/@0xnolo/fee-on-transfer-rebase-tokens-an-erc-20-security-bug-you-need-to-know-f4e5badea1ee
- eth_getLogs 限制与漏日志：https://sqd.dev/learn/eth-getlogs-limits/ ；Alchemy：https://www.alchemy.com/docs/chains/ethereum/ethereum-api-endpoints/eth-get-logs ；QuickNode 10k 区块限制：https://support.quicknode.com/hc/en-us/articles/10258449939473 ；Chainstack：https://docs.chainstack.com/docs/understanding-eth-getlogs-limitations
- 重组处理模式：https://docs.envio.dev/docs/HyperIndex/reorgs-support ；https://www.quicknode.com/docs/streams/reorg-handling ；https://platform.chainbase.com/blog/article/unraveling-reorgs-problems-the-chainbase-approach
- 重组事件：信标链 7 块 https://decrypt.co/101390/ethereum-beacon-chain-blockchain-reorg ；ETC 51% https://www.dwellir.com/blog/what-is-a-chain-reorg
- PoS 最终性：https://ethereum.org/developers/docs/consensus-mechanisms/pos/ ；承诺级别：https://www.alchemy.com/overviews/ethereum-commitment-levels ；攻防：https://ethereum.org/developers/docs/consensus-mechanisms/pos/attack-and-defense/
- 交易所确认数：Binance 12 https://www.binance.com/en/support/announcement/binance-reduces-the-number-of-confirmations-required-for-deposits-withdrawals-on-btc-and-eth-networks-360030775291 ；OKX 32 https://www.okx.com/en-us/help/okx-to-reduce-the-number-of-confirmations-required-for-deposits-on-eth
- web3j：https://github.com/LFDT-web3j/web3j ；https://blog.web3labs.com/web3j-5-0-2-a-community-release-that-moves-us-forward/ ；依赖列表为本机 `~/.m2` 中 `core-5.0.3.pom` 实读
