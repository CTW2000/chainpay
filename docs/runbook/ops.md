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

# 运维手册 · 容器里跑（M6-①）

```bash
set -a; source env/local.env; set +a          # 密钥只从这里来；compose 的 environment 只覆盖主机名与端口
docker compose build app                      # 两阶段构建；首次约 6 分钟（拉 Maven 镜像 + 依赖），之后 ~2 分钟
tools/image-check.sh                          # 打完必跑：非 root、HEALTHCHECK、无私钥形态、env 里每个密钥值 grep 不到
docker compose up -d app                      # 等中间件 healthy 才起；~10 秒后自己变 healthy
docker inspect --format '{{.State.Health.Status}}' chainpay-app
docker logs -f chainpay-app
```

| 想做 | 命令 | 说明 |
|---|---|---|
| 看探针 | `docker exec chainpay-app curl -s http://127.0.0.1:8096/actuator/health/work` | 8096 只在容器内回环，宿主到不了 |
| 调管理接口 | `tools/admin.sh GET /admin/v1/indexer` | 从宿主打 `127.0.0.1:8095/admin/...` 会 401：源地址是 Docker 网桥网关不是回环。脚本在容器里发 curl |
| 调商户接口 | `tools/api.py ...` | 数据面走发布端口，和以前一样 |
| 停 / 起 | `docker compose stop app` / `docker compose start app` | 人手 stop 的不会被自动拉起（`unless-stopped`） |
| 崩了 | 什么都不用做 | 进程退出（含 OOM）Docker 自动拉起；`docker inspect --format '{{.RestartCount}}' chainpay-app` 看拉起过几次 |
| 换版本 | `docker compose build app && tools/image-check.sh && docker compose up -d app` | M6-④ 会把它变成脚本并加回滚 |

**这台 Mac 上的怪事**：出网走本机代理的隧道，容器内 TLS 偶发「Remote host terminated the handshake」（apt 或 Maven 都可能撞上），构建失败先重跑一次再查别的。

# 运维手册 · 告警（M6-③）

告警看的就是上面的 `work` 组，每 30 秒一眼，**只在变化时叫**：不是 UP 叫一次 🔴，回到 UP 叫一次 🟢；没送到的下一轮再叫；一直坏着不重复。
出口是一个 HTTP webhook（`CHAINPAY_ALERT_WEBHOOK_URL`，按密码对待，日志里只出主机名），形状 `CHAINPAY_ALERT_FORMAT`：

| 值 | 给谁 | 载荷 |
|---|---|---|
| `generic` | 自己的接收端 | `{service, component, from, to, recovered, details, at, text}` |
| `slack` | Slack incoming webhook | `{text}` |
| `dingtalk` | 钉钉群机器人 | `{msgtype:"text", text:{content}}` |
| `feishu` | 飞书群机器人 | `{msg_type:"text", content:{text}}` |

不设地址 = 变化只打 ERROR 日志（`grep 告警 日志`）。收到一条 🔴 之后按上面「work 里每个部件不是 UP 时怎么办」处理；🟢 是它自己好了或人修好了。

**本机演练的接收端**：`python3 <scratchpad>/hook.py <日志文件>` 在 127.0.0.1:9911 收 POST 并逐行落文件；容器里地址写 `http://host.docker.internal:9911/hook`。接收端没起、地址又配着，每轮会 ERROR「没送到，下一轮再叫」——要么起接收端，要么把 `env/local.env` 里那两行删掉。

# 运维手册 · 发布与回滚（M6-④）

```bash
set -a; source env/local.env; set +a
deploy/deploy.sh            # 八环节：构建 → 配置 → 密钥 → 迁移 → 打标签 → 切换 → 验证 → 不过就回滚
deploy/rollback.sh          # 人手回滚：previous 换回 current、up、再验；数据库不动
docker images chainpay      # 标签就是发布记录
```

| 标签 | 意思 |
|---|---|
| `chainpay:<sha>` | 某个提交打出来的镜像；工作区脏时是 `<sha>-dirty`，日后按提交号找不回来源 |
| `chainpay:current` | compose 在跑的那个；部署脚本换它，人不手动换 |
| `chainpay:previous` | 上一版；回滚退到它 |
| `chainpay:failed` | 上一次被换下的（回滚时打的），留着查 |

**迁移失败**：脚本在 ④ 停，什么都没切换，旧版本继续跑。看脚本打印的 Flyway 错误，改迁移，再跑 `deploy/deploy.sh`（同一个提交的镜像已存在就不重打；改了迁移就是新提交或 `-dirty`）。
**验证不过**（120 秒没 healthy）：脚本自己回滚到 previous 并退出 1；看 `docker logs chainpay-app` 里新版本为什么起不来。
**回滚后数据库怎么办**：不动。规矩是「迁移只前进，上一版代码要能跑在新 schema 上」——每条迁移只加不删（先加后删，删要等到没有代码再用它的下一版）。回滚后的旧代码对库里它不认识的更高版本视而不见（`ignore-migration-patterns: *:future`）。
**只想跑迁移不发布**：`docker compose run --rm --no-deps app --migrate-only`。
