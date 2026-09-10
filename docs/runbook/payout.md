# 付款（M4）操作手册：热钱包停发了怎么办

> 适用范围：M4-②（编号、签名、广播）。追踪与结算（③）落地后再扩。
> 规矩不变：**人永远不手工碰账本表**（`account` / `transfer` / `entry`）。

## 一、先看哪里

```sql
-- 热钱包：ACTIVE 还是 HALTED，为什么
SELECT address, next_nonce, status, halt_reason, updated_at FROM hot_wallet;

-- 提现按状态计数
SELECT status, count(*) FROM payout GROUP BY status ORDER BY 1;

-- 签了没广播出去的尝试（正常情况下这张表里 SIGNED 只存在几毫秒）
SELECT id, payout_id, nonce, tx_hash, status, updated_at FROM payout_tx WHERE status = 'SIGNED' ORDER BY nonce;
```

日志里每轮一行：`发送：看了 N 笔，签 …、广播 …、重发 …、判失败 …`；停发时每轮一行 ERROR `热钱包停发，等人处理：…`。

## 二、停发（`hot_wallet.status = 'HALTED'`）的四种原因

| `halt_reason` 开头 | 发生了什么 | 该做什么 |
|---|---|---|
| `链上已上链 C 笔，超过本库分出去的编号 N` | **有人在别处用了这把私钥**（或者有人拿这把钱包在别的工具里发了交易） | 先当泄露处理：查链上多出来的那几笔是谁发的、发去哪；确认只是有人误用后，把 `next_nonce` 改成链上计数（见第三节），再恢复。确认是泄露：换钱包（新私钥进环境变量、旧钱包的余额转走），旧行留着作证据 |
| `编号 N 超过链上 C 笔与未终结尝试 U 之和` | 有分出去的编号没有对应的尝试记录——正常路径做不到，多半是人手工改过 `hot_wallet` 或 `payout_tx` | 查 `payout_tx` 里 `nonce` 的空洞；缺的那些编号要么在链上（那 C 应该更大，重新对账），要么真的没发过——这时 `next_nonce` 改回 C + U，再恢复 |
| `节点拒绝广播编号 n（insufficient funds …）` | 热钱包的 **ETH** 不够付 gas（LINK 够不够在估 gas 时就会暴露，不会走到这里） | 往热钱包地址充 Sepolia ETH，然后恢复。尝试留在 SIGNED，恢复后的第一轮会原样重发，编号不变 |
| `编号 n 已被链上另一笔用掉（nonce too low），而节点不认识我们这笔` | 同第一行：这个编号被别处的一笔用掉了 | 同第一行 |

其它带错误码的拒绝（节点不认这笔交易的形状、gas 太低等）也走停发，原文在 `halt_reason` 里。

## 三、恢复：唯一的办法是人把状态改回去

**重启不算恢复。** 进程启动后读到 HALTED 就一笔都不签，每轮报 ERROR。

```bash
docker exec -it chainpay-postgres psql -U chainpay -d chainpay -c \
  "UPDATE hot_wallet SET status = 'ACTIVE', halt_reason = NULL, updated_at = now() WHERE address = '0x…';"
```

需要改编号时（只在第二节前两行的情形下，改之前把旧值记下来）：

```sql
UPDATE hot_wallet SET next_nonce = <链上计数 或 C + U>, updated_at = now() WHERE address = '0x…';
```

恢复后的第一轮：先对账（改错了会立刻再停），再重发所有 SIGNED，然后才轮到排队的。

## 四、不是停发的两种「不动」

- 日志 `发送这一轮提前结束，下一轮再来：费率超上限…`：链上基础费涨过了 `chainpay.payout.max-fee-gwei`。申请留在 QUEUED，什么都不用做；真要现在发，临时调高上限重启。
- 日志 `…节点失败…`：主节点没回答。下一轮自动重试；连续出现看 `docs/runbook/chain-indexer.md` 的节点一节。

## 五、SIGNED 的尝试卡着不动

正常情况下不会：每轮开头都重发。如果卡着，看第二节——多半是钱包停发了。**不要手工把 SIGNED 改成别的状态**：原文已经签好，改状态不会让链忘记它。

## 六、判失败（FAILED）的提现

估 gas 就 revert（最常见：热钱包的 LINK 不够）的提现会直接 FAILED、写 `failure_reason`、解冻退回商户可用余额，编号没分出去。往热钱包转 LINK 后，商户重新申请即可；**已 FAILED 的不会自动重发**。
