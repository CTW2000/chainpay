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
| `readiness` | 能不能接请求 | `db`（Boot 的组合项：子项 `dataSource` 主池、`systemDataSource` 系统池） | 容器 HEALTHCHECK、负载均衡 | 不给它流量；连着 DOWN 就重启 |
| `work` | 能不能干活 | `indexer`、`deposit`、`hotWallet`、`audit`、`redis` | 告警、人 | **叫人**，不重启（重启不会让 HALTED 变好） |

HTTP：UP / DEGRADED / UNKNOWN = 200；DOWN = 503。`DEGRADED` 是本项目多出来的一档：还在跑，但有人该来看看。

## `work` 里每个部件不是 UP 时怎么办

| 部件 | 状态 | 意思 | 做什么 |
|---|---|---|---|
| `indexer` | UNKNOWN | 这个进程没配主节点 | 没事，除非它本该索引 |
| `indexer` | DEGRADED | 连续瞬时失败（节点在抖） | 看 `GET /admin/v1/indexer`；恢复后自己回 RUNNING |
| `indexer` | DOWN | HALTED，`reason` 里是原因 | 按 `docs/runbook/chain-indexer.md` 处理，处理完才能复位 |
| `deposit` | UNKNOWN | 这个进程没配 xpub 或主节点 | 没事，除非它本该入账 |
| `deposit` | DEGRADED | 连续 5 轮没跑完（节点答不上来、库在抖） | 看 `reason`；节点恢复后自己回 UP |
| `deposit` | DOWN | 上一轮 HALTED：节点拒绝了凭证，重试没用 | 换 RPC key 后重启，见 `docs/runbook/deposit.md` |
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
tools/image-check.sh                          # 打完必跑：非 root、HEALTHCHECK、文件系统与镜像元数据里无私钥形态、env 里每个密钥值 grep 不到
docker compose up -d app                      # 等中间件 healthy 才起；~10 秒后自己变 healthy
docker inspect --format '{{.State.Health.Status}}' chainpay-app
docker logs -f chainpay-app
```

**扫描的前提不成立时，它报「这次扫描不可信」并退出 1，而不是打一排 ✓**（2026-09-15）：解包失败、
镜像里没有 `/app`、取不到元数据、按值扫描用的 env 文件不在，都算前提不成立——「没找到密钥」和「根本没扫」
不能长得一样。换 env 文件用 `CHAINPAY_ENV_FILE=…`；确实不需要按值扫描（比如在没有密钥的机器上）用
`CHAINPAY_SKIP_VALUE_SCAN=1` 显式说明。形态检测覆盖 `/app` 与镜像的 Config（Env / Labels / Cmd / Entrypoint），
构建历史只参与按值扫描——基础镜像的命令里遍地是 sha256 校验和，形态规则在那里必然误报。

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
deploy/deploy.sh            # 八环节：构建 → 配置 → 密钥 → 迁移 → 打标签 → 切换 → 验证 → 不过就回滚
deploy/deploy.sh <提交>      # 发布一个旧提交：构建的就是那个提交里的文件，和工作区无关
deploy/rollback.sh          # 人手回滚：previous 换回 current、up、再验；数据库不动
docker images chainpay      # 标签就是发布记录
```

**不要先 `set -a; source env/local.env`**（2026-09-18 改）：脚本自己在子 shell 里读 env 文件做检查，读完即丢；compose 自己读 `env_file`，
compose 文件里唯一的插值 `${CHAINPAY_IMAGE:-chainpay:current}` 带默认值。先 source 等于把全部密钥导进你这个终端，之后跑的每一条命令都继承一份。
换 env 文件用 `CHAINPAY_ENV_FILE=env/xxx.env deploy/deploy.sh`——第 ② 步配置检查和第 ③ 步按值扫描用的是同一个文件。

| 标签 | 意思 |
|---|---|
| `chainpay:<sha>` | 某个提交打出来的镜像：构建上下文是那个提交的 git tree（`git archive`），工作区的改动和杂物进不来 |
| `chainpay:<sha>-dirty.<tree>` | HEAD 加上工作区未提交改动（含未跟踪文件）打出来的；`<tree>` 是工作区内容的 git tree 号前 7 位——同一份改动同一个标签，改一个字就是另一个标签 |
| `chainpay:current` | compose 在跑的那个；部署脚本换它，人不手动换 |
| `chainpay:previous` | 上一版；回滚退到它 |
| `chainpay:failed` | 上一次被换下的（回滚或第一次部署失败时打的），留着查 |

标签由内容决定，所以「这个标签已经有镜像了就跳过构建」是安全的。2026-09-18 之前脏工作区一律叫 `<sha>-dirty`，两份不同的改动撞同一个标签，
脚本会把旧镜像当成这一次部署出去——那种旧格式的标签别再拿来部署。顺带：`.gitignore` 挡住的文件（`env/*.env`、`*.key`）从此进不了构建上下文，不再只靠 `.dockerignore` 一道。

**迁移失败**：脚本在 ④ 停，什么都没切换，旧版本继续跑。完整日志在 `$TMPDIR/chainpay-migrate.XXXXXX`（路径会打出来；迁移成功时自动删掉）。改迁移，再跑 `deploy/deploy.sh`——改了就是新内容、新标签，一定会重新构建。
**验证不过**（120 秒没 healthy）：有上一版时，脚本自己回滚到 previous 并退出 1；看 `docker logs chainpay-app` 里新版本为什么起不来。
**第一次部署就验证不过**：没有上一版可退。脚本停下 app，坏镜像改叫 `chainpay:failed`，摘掉 `chainpay:current`，退出 1——回到部署之前「什么都没在跑」的样子，而不是留一个被 `restart: unless-stopped` 反复拉起的坏容器。
**部署成功之后**：最后一行打出 work 组的总状态（UP / DEGRADED / DOWN），只供参考、不影响部署结果；部件细节用它打出的那条 `docker exec … /actuator/health/work` 命令看。
**回滚后数据库怎么办**：不动。规矩是「迁移只前进，上一版代码要能跑在新 schema 上」——每条迁移只加不删（先加后删，删要等到没有代码再用它的下一版）。回滚后的旧代码对库里它不认识的更高版本视而不见（`ignore-migration-patterns: *:future`）。
**只想跑迁移不发布**：`docker compose run --rm --no-deps app --migrate-only`。
**改了部署脚本之后**：`DeployScriptTest` 用假 docker 逐环验证行为（脚本被 source 时只定义函数、不跑 main），`DeployGuardTest` 守八个环节的顺序；两个都要绿。

# 运维手册 · 控制面（M6-⑤）

控制面（`/admin/**`）认两样东西：**本机回环且无代理头**（容器里跑就在容器里发，`tools/admin.sh` 替你做）和**管理员会话**。静态令牌已经没有了。

```bash
# 第一个管理员（只做一次）。口令只经环境变量交给容器，而且别写在命令行上——写在命令行上会连口令一起进 shell 历史（2026-09-18 改）。
# 记在密码管理器里，或写进 env/admin.env（gitignore 挡着）方便日后登录
printf '口令（至少 12 位）：'; read -rs CHAINPAY_ADMIN_PASSWORD; echo; export CHAINPAY_ADMIN_PASSWORD
docker compose run --rm --no-deps -e CHAINPAY_ADMIN_PASSWORD app --create-admin ops
unset CHAINPAY_ADMIN_PASSWORD

eval "$(tools/admin.sh login ops)"          # 提示输入口令（不回显）；令牌只在当前 shell 的环境变量里
tools/admin.sh GET  /admin/v1/indexer        # 只读接口直接调
tools/admin.sh reauth                        # 敏感操作前再认证（5 分钟有效）：建商户、发凭证、核准 / 拒绝提现、改限额、登记注资
tools/admin.sh POST /admin/v1/payouts/7/approve
tools/admin.sh logout
```

| 回应 | 意思 | 做什么 |
|---|---|---|
| 401「无权访问管理接口」 | 不是回环、带了代理头、没令牌、令牌过期或退出过、口令错、账户锁定 | 故意不区分。重新 `login`；连续错 5 次锁 15 分钟 |
| 403 + 3002 | 敏感操作，最近 5 分钟没用口令再认证过 | `tools/admin.sh reauth` 再来 |
| 409 + 4002 | `--create-admin` 的用户名已存在 | 换名字，或用现有的登录 |

会话：闲置 30 分钟失效，12 小时到点失效（`chainpay.admin.*`）。改口令：`POST /admin/v1/auth/password {current, next}`，改完本人其它会话全部失效。
**审计**：`admin_action` 每次调用一行（谁、方法、路径、状态、来源），登录成败也在；只追加。查最近的：
`docker exec chainpay-postgres psql -U chainpay -d chainpay -c "SELECT at, username, method, path, status, remote_addr FROM admin_action ORDER BY id DESC LIMIT 50"`。
**没做的**：TOTP 二次验证、提现冷却期、管理员的增删与停用接口（现在只有 `--create-admin`；停用改库 `status = 'DISABLED'`）。
