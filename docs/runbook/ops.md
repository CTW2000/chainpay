# 运维手册 · 上线与日常运维

应用在 docker compose 里跑（容器 `chainpay-app`）。下面的命令都按这个跑法写；宿主上直接跑 jar 时，把 `docker exec chainpay-app` 去掉即可。

## 健康检查

三个探针只在**管理端口**上（默认 8096，只绑容器内回环），不要令牌；主端口 8095 上没有 `/actuator`。

```bash
docker exec chainpay-app curl -s http://127.0.0.1:8096/actuator/health/readiness
docker exec chainpay-app curl -s http://127.0.0.1:8096/actuator/health/work
docker exec chainpay-app curl -s http://127.0.0.1:8096/actuator/health            # 全部部件
docker exec chainpay-app curl -s 'http://127.0.0.1:8096/actuator/metrics/hikaricp.connections?tag=pool:chainpay-system'
```

| 组 | 问题 | 里面有什么 | 谁看 | 不是 UP 时 |
|---|---|---|---|---|
| `liveness` | 进程在不在 | `livenessState` | 进程管理器 | 重启进程 |
| `readiness` | 能不能接请求 | `db`（子项 `dataSource` 主池、`systemDataSource` 系统池） | 容器 HEALTHCHECK | 不给流量；连着 DOWN 就重启 |
| `work` | 能不能干活 | `indexer`、`deposit`、`hotWallet`、`audit`、`redis` | 告警、人 | **叫人**，不重启（重启不会让 HALTED 变好） |

HTTP：UP / DEGRADED / UNKNOWN = 200；DOWN = 503。`DEGRADED` 是本项目多出来的一档：还在跑，但有人该来看看。细节里只有「状态 + 原因 + 几个数」，没有密码、连接串、节点地址（`HealthProbesTest` 守）。

### `work` 里每个部件不是 UP 时怎么办

| 部件 | 状态 | 意思 | 做什么 |
|---|---|---|---|
| `indexer` | UNKNOWN | 没配主节点 | 没事，除非它本该索引 |
| `indexer` | DEGRADED | 连续瞬时失败（节点在抖） | `tools/admin.sh GET /admin/v1/indexer`；恢复后自己回 RUNNING |
| `indexer` | DOWN | HALTED，`reason` 里是原因 | 按 `chain-indexer.md` 处理，处理完才能复位 |
| `deposit` | UNKNOWN | 没配 xpub 或主节点 | 没事，除非它本该入账 |
| `deposit` | DEGRADED | 连续 5 轮没跑完（节点答不上来、库在抖） | 看 `reason`；恢复后自己回 UP |
| `deposit` | DOWN | 上一轮 HALTED：节点拒绝了凭证 | 换 RPC key 后重启，见 `deposit.md` |
| `hotWallet` | DOWN | 钱包 HALTED（编号被别处用掉之类） | 按 `payout.md`「钱包 HALTED」 |
| `audit` | DOWN + `stale: true` | 判官沉默超过两个周期（含从没跑过） | 看日志里对账为什么没跑；`tools/admin.sh POST /admin/v1/audit/run` 手工跑一轮 |
| `audit` | DOWN + `lastRun: run N DIFF` | 上一轮有差异 | `tools/admin.sh GET /admin/v1/audit` 看逐条差异，按 `audit.md` |
| `audit` | DEGRADED | 上一轮 FAILED（节点或库瞬时失败），还没 stale | 等下一轮；连着 FAILED 会变 stale |
| `redis` | DOWN | Redis 不通 | 应用不死：限流降级为进程内计数，防重放放行（幂等键兜底）。修 Redis |

## 告警

告警每 30 秒看一眼 `work` 组，**只在变化时叫**：不是 UP 叫一次 🔴，回到 UP 叫一次 🟢；没送到的下一轮再叫；一直坏着不重复。收到 🔴 按上表处理。
出口是一个 HTTP webhook：`CHAINPAY_ALERT_WEBHOOK_URL`（按密码对待，日志只出主机名），形状由 `CHAINPAY_ALERT_FORMAT` 定：

| 值 | 给谁 | 载荷 |
|---|---|---|
| `generic` | 自己的接收端 | `{service, component, from, to, recovered, details, at, text}` |
| `slack` | Slack incoming webhook | `{text}` |
| `dingtalk` | 钉钉群机器人 | `{msgtype:"text", text:{content}}` |
| `feishu` | 飞书群机器人 | `{msg_type:"text", content:{text}}` |

不设地址 = 变化只打 ERROR 日志。本机演练可以在宿主起一个收 POST 的小程序，容器里地址写 `http://host.docker.internal:<端口>/…`；演练完把这行从 `env/local.env` 删掉，否则下一次变化会报「没送到」。

## 发布与回滚

```bash
deploy/deploy.sh            # 构建 → 配置检查 → 镜像扫描 → 迁移 → 打标签 → 切换 → 验证，不过就回滚
deploy/deploy.sh <提交>      # 发布一个旧提交：构建的是那个提交里的文件，和工作区无关
deploy/rollback.sh          # 人手回滚：previous 换回 current、up、再验；数据库不动
docker images chainpay      # 标签就是发布记录
```

**不要先 `source env/local.env`**：脚本在子 shell 里读 env 文件做检查，读完即丢；compose 自己读 `env_file`。先 source 等于把全部密钥导进你的终端，之后每条命令都继承一份。
换 env 文件用 `CHAINPAY_ENV_FILE=env/xxx.env deploy/deploy.sh`（配置检查与按值扫描用同一个文件）。

| 标签 | 意思 |
|---|---|
| `chainpay:<sha>` | 某个提交的 git tree 打出来的镜像（`git archive`），工作区的改动和杂物进不来 |
| `chainpay:<sha>-dirty.<tree>` | HEAD 加上工作区未提交改动；`<tree>` 是工作区内容的 tree 号前 7 位——同一份改动同一个标签，改一个字就是另一个标签。没有 `.<tree>` 后缀的旧格式标签别再拿来部署 |
| `chainpay:current` | compose 在跑的那个；脚本换它，人不手动换 |
| `chainpay:previous` | 上一版；回滚退到它 |
| `chainpay:failed` | 上一次被换下的，留着查 |

- **镜像扫描**（`tools/image-check.sh`）：非 root、有 HEALTHCHECK、`/app` 与镜像元数据里没有私钥形态、`env/local.env` 里每个密钥形态变量的值都 grep 不到。前提不成立（解包失败、没有 `/app`、取不到元数据、env 文件不在）就报「这次扫描不可信」并退出 1——「没找到密钥」和「根本没扫」不能长得一样。确实不需要按值扫描时用 `CHAINPAY_SKIP_VALUE_SCAN=1` 显式说明。
- **迁移失败**：脚本在迁移那一步停，什么都没切换，旧版本继续跑；完整日志路径会打出来。改迁移，再跑 `deploy/deploy.sh`。
- **验证不过**（120 秒没 healthy）：有上一版时自动回滚并退出 1，看 `docker logs chainpay-app`。**第一次部署就验证不过**：停下 app、坏镜像改叫 `failed`、摘掉 `current`，回到部署之前的样子。
- **回滚后数据库**：不动。迁移只前进，上一版代码要能跑在新 schema 上（先加后删）；旧代码对库里的更高版本视而不见（`ignore-migration-patterns: *:future`）。
- **只想跑迁移**：`docker compose run --rm --no-deps app --migrate-only`。
- **改了部署脚本**：`DeployScriptTest`（假 docker 逐环验证）与 `DeployGuardTest`（顺序）都要绿。

**日常操作**：停 / 起 `docker compose stop app` / `start app`（人手 stop 的不会被自动拉起）；崩了什么都不用做（`restart: unless-stopped`，`docker inspect --format '{{.RestartCount}}' chainpay-app` 看拉起过几次）；日志 `docker logs -f chainpay-app`。
**这台 Mac 上的怪事**：容器内出网走本机代理的隧道，TLS 偶发「Remote host terminated the handshake」，构建失败先重跑一次。

## 控制面

控制面（`/admin/**`）认两样：**本机回环且无代理头**（`tools/admin.sh` 在容器里发请求）和**管理员会话**。

```bash
# 第一个管理员（只做一次）。口令只经环境变量交给容器，别写在命令行上（会进 shell 历史）
printf '口令（至少 12 位）：'; read -rs CHAINPAY_ADMIN_PASSWORD; echo; export CHAINPAY_ADMIN_PASSWORD
docker compose run --rm --no-deps -e CHAINPAY_ADMIN_PASSWORD app --create-admin ops
unset CHAINPAY_ADMIN_PASSWORD

eval "$(tools/admin.sh login ops)"          # 提示输入口令（不回显）；会话令牌只在当前 shell 的环境变量里
tools/admin.sh GET  /admin/v1/indexer        # 只读接口直接调
tools/admin.sh reauth                        # 敏感操作前再认证（5 分钟有效）：建商户、发凭证、核准 / 拒绝提现、改限额、登记注资
tools/admin.sh POST /admin/v1/payouts/7/approve
tools/admin.sh logout
```

| 回应 | 意思 | 做什么 |
|---|---|---|
| 401「无权访问管理接口」 | 不是回环、带了代理头、没有会话或会话过期、口令错、账户锁定 | 故意不区分。重新 `login`；连续错 5 次锁 15 分钟 |
| 403 + 3002 | 敏感操作，最近 5 分钟没再认证过 | `tools/admin.sh reauth` 再来 |
| 409 + 4002 | `--create-admin` 的用户名已存在 | 换名字，或用现有的登录 |

会话闲置 30 分钟、12 小时到点失效。改口令：`POST /admin/v1/auth/password {current, next}`，改完本人其它会话全部失效。
**审计**：`admin_action` 每次调用一行（谁、方法、路径、状态、来源），登录成败也在；只追加。
`docker exec chainpay-postgres psql -U chainpay -d chainpay -c "SELECT at, username, method, path, status, remote_addr FROM admin_action ORDER BY id DESC LIMIT 50"`
**没做的**：TOTP、提现冷却期、管理员的增删与停用接口（停用改库 `status = 'DISABLED'`）。

## 进程角色

同一个镜像起成两种常驻进程：`web` 对外接商户请求，`worker` 跑定时任务与控制面、握着重钥匙。角色由 `SPRING_PROFILES_ACTIVE` 给，**恰好一个**。
拆成两个服务（进程拆分第 ⑥ 步）之前，compose 里唯一的 `app` 以 `worker` 身份跑。启动日志里有一行 `进程角色：worker（禁用名单 N 项，环境里一项都没有）`。

| 起不来时的报错 | 意思 | 做什么 |
|---|---|---|
| 进程角色必须恰好是 web、worker 之一……现在激活的 profile 是 […] | 没给角色，或给了两个 | 在这个进程的环境里设 `SPRING_PROFILES_ACTIVE=web` 或 `worker`。别写进 application.yml：给个默认角色等于没有角色 |
| web（或 worker）进程的环境里有它不该拿的凭证，拒绝启动：CHAINPAY_…（它能干什么） | 这把钥匙不属于这个进程 | 从这个进程的 env 文件里按名字删掉点名的变量（只删行，不要 cat 文件）。报错里只有变量名，没有值 |

| 禁用名单（`ops/role/ProcessRole`） | web | worker |
|---|---|---|
| `CHAINPAY_SYSTEM_DB_PASSWORD` | 禁 | 要 |
| `CHAINPAY_FLYWAY_PASSWORD` | 禁 | 第 ⑤ 步起禁（在那之前唯一的容器启动时还要自己迁移） |
| `CHAINPAY_PAYOUT_HOT_WALLET_KEY` | 禁 | 要 |
| `CHAINPAY_CHAIN_RPC_URL` / `_AUDIT_RPC_URL` | 禁 | 要 |
| `CHAINPAY_ALERT_WEBHOOK_URL` | 禁 | 要 |
| `CHAINPAY_ADMIN_PASSWORD` | 禁 | 禁：只属于 `--create-admin` 那一条一次性命令（另一个 JVM，守卫不管） |

**测试探针的节点地址**（`CHAINPAY_SEPOLIA_RPC` / `_AUDIT_RPC`）不放 `env/local.env`：那份文件整份进容器的环境，应用却从不读它们。放 `env/probe.env`（照 `env/probe.env.example`）。已经在 `env/local.env` 里的，按名字挪过去（值不经过终端；Linux 上把 `sed -i ''` 换成 `sed -i`）：

```bash
grep -E '^CHAINPAY_SEPOLIA_' env/local.env >> env/probe.env && chmod 600 env/probe.env && sed -i '' '/^CHAINPAY_SEPOLIA_/d' env/local.env
```

**已退役的 `CHAINPAY_ADMIN_TOKEN`**（没有代码读它，回滚链上的镜像也都不需要它）：按名字删掉，`sed -i '' '/^CHAINPAY_ADMIN_TOKEN=/d' env/local.env`。
