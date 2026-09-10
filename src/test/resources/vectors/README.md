# 测试向量的来源

按 CLAUDE.md §7「已知答案必须来自原始文本」：这些文件是从原始仓库**逐字复制**的，不经任何转述。

| 文件 | 来源 | 提交 |
|---|---|---|
| `rlptest.json` | github.com/ethereum/tests `RLPTests/rlptest.json` | `c67e485ff8b5be9abc8ad15345ec21aa22e290d9`（2026-09-09 浅克隆） |
| `keyaddrtest.json` | github.com/ethereum/tests `BasicTests/keyaddrtest.json` | 同上 |
| `LICENSE.ethereum-tests` | 同仓库 `LICENSE`（MIT） | 同上 |

EIP-155 的算例与 `TransactionTests/ttEIP1559/*` 的向量直接写在测试类里（数值逐字来自 github.com/ethereum/EIPs `EIPS/eip-155.md` 与上述提交），来源标在各自的注释里。
