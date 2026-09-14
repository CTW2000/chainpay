# 运维手册 · 健康检查（M6-⓪）

三个探针都在**管理端口**上（默认 8096，只绑 127.0.0.1，`CHAINPAY_MANAGEMENT_PORT` 可改），不要令牌。主端口 8095 上没有 `/actuator`。

```bash
curl -s http://127.0.0.1:8096/actuator/health/liveness
curl -s http://127.0.0.1:8096/actuator/health/readiness
curl -s http://127.0.0.1:8096/actuator/health/work
curl -s http://127.0.0.1:8096/actuator/health            # 全部部件
curl -s 'http://127.0.0.1:8096/actuator/metrics/hikaricp.connections?tag=pool:chainpay-system'
```

## 三个组各回答什么

| 组 | 问题 | 里面有什么 | 谁看 | 不是 UP 时 |
|---|---|---|---|---|
| `liveness` | 进程在不在 | `livenessState` | 进程管理器 | 重启进程 |
| `readiness` | 能不能接请求 | `db`（主池）、`systemDb`（系统池） | 容器 HEALTHCHECK、负载均衡 | 不给它流量；连着 DOWN 就重启 |
| `work` | 能不能干活 | `indexer`、`hotWallet`、`audit`、`redis` | 告警、人 | **叫人**，不重启（重启不会让 HALTED 变好） |

HTTP：UP / DEGRADED / UNKNOWN = 200；DOWN = 503。`DEGRADED` 是本项目多出来的一档：还在跑，但有人该来看看。

## `work` 里每个部件不是 UP 时怎么办

| 部件 | 状态 | 意思 | 做什么 |
|---|---|---|---|
| `indexer` | UNKNOWN | 这个进程没配主节点 | 没事，除非它本该索引 |
| `indexer` | DEGRADED | 连续瞬时失败（节点在抖） | 看 `GET /admin/v1/indexer`；恢复后自己回 RUNNING |
| `indexer` | DOWN | HALTED，`reason` 里是原因 | 按 `docs/runbook/chain-indexer.md` 处理，处理完才能复位 |
| `hotWallet` | DOWN | HALTED，编号被别处用掉之类 | 按 `docs/runbook/payout.md`「钱包 HALTED」 |
| `audit` | DOWN + `stale: true` | 判官沉默超过两个周期（含从没跑过） | 看日志里对账为什么没跑；`POST /admin/v1/audit/run` 手工跑一轮 |
| `audit` | DOWN + `lastRun: run N DIFF` | 上一轮有差异 | `GET /admin/v1/audit` 看逐条差异，按 `docs/runbook/audit.md` |
| `audit` | DEGRADED | 上一轮 FAILED（节点或库瞬时失败），还没 stale | 等下一轮；连着 FAILED 会变 stale |
| `redis` | DOWN | 限流计数的 Redis 不通 | 限流已降级为进程内计数，应用不死；修 Redis |

## 细节里为什么没有更多

细节只到「状态 + 原因 + 几个数」。密码、连接串、节点地址、私钥永远不进来（`HealthProbesTest.detailsLeakNoSecrets` 守着）。管理端口虽然只绑回环，但本机上任何进程都到得了；细节越少，泄露面越小。
