# chainpay

加密支付网关——**学习项目**。学习路线与进度见 [LEARNING-PATH.md](LEARNING-PATH.md)，项目规约见 [CLAUDE.md](CLAUDE.md)，出了事怎么办见 `docs/runbook/`。

---

## 环境

| 组件 | 版本 | 在哪 |
|---|---|---|
| Java | **25 (LTS)** | `~/.local/jdk-25` |
| Spring Boot | **4.1.0**（Spring Framework 7.0.8） | pom |
| PostgreSQL / Redis | **18** / 8.10 | Docker |
| Flyway / Testcontainers | 12.4.0 / 2.0.5 | Boot 托管 |
| Maven / Docker | 3.9 / Docker Desktop | 本机 |

```bash
export JAVA_HOME=~/.local/jdk-25/Contents/Home      # 写进 ~/.zshrc 更省事；flow-pay 用的是 JDK 21，别混
```

## 跑测试

```bash
open -a Docker
mvn test
```

Testcontainers 自己拉起 PostgreSQL 18 与 Redis，跑完销毁，不依赖开发库。两个 Sepolia 探针默认跳过（要节点地址，见 `env/probe.env.example`）。

## 用容器跑（标准跑法）

应用和中间件都在 docker compose 里；密钥只在 `env/local.env`（被 `.gitignore` 挡住），镜像里 grep 不到。

```bash
cp env/local.env.example env/local.env      # 第一次：按说明填真实值
```

```bash
deploy/deploy.sh                            # 构建 → 配置检查 → 镜像扫描 → 迁移 → 打标签 → 切换 → 验证，不过就回滚
```

```bash
deploy/rollback.sh                          # 退回上一版
```

- **不要先 `source env/local.env`**：脚本自己在子 shell 里读，读完即丢。
- 容器以 `worker` 角色跑（compose 的 `SPRING_PROFILES_ACTIVE`）；角色与禁用名单见 `docs/runbook/ops.md`「进程角色」。
- 健康探针只在容器内回环的 8096 上：`docker exec chainpay-app curl -s http://127.0.0.1:8096/actuator/health/readiness`。
- 管理接口从宿主打会 401（源地址不是回环），一律用 `tools/admin.sh`。

## 在宿主上直接跑（调试用）

```bash
set -a; . env/local.env; set +a
SPRING_PROFILES_ACTIVE=worker mvn spring-boot:run
```

- **必须给角色**：没有 `SPRING_PROFILES_ACTIVE`，或给了 web 却带着 worker 的凭证，应用拒绝启动并点名变量。
- 五个变量故意没有默认值：`CHAINPAY_DB_PASSWORD`、`CHAINPAY_FLYWAY_PASSWORD`、`CHAINPAY_SYSTEM_DB_PASSWORD`、`CHAINPAY_SECRET_KEY`、`CHAINPAY_DEPOSIT_XPUB`（收款地址从它派生）。配错了就起不来，好过带着默认密钥上生产。
- 节点地址、热钱包私钥不设时，对应的模块整个不装配，应用照常启动。
- 两个数据库角色（`chainpay_app` 受行级安全约束、`chainpay_system` 给系统任务）由 `db/init/01-roles.sql` 在数据卷**首次建库**时创建。

## 密钥与工具

**收款地址的 xpub**（服务端没有收款私钥，只拿账户层 xpub 派生地址）：断网，用测试网助记词跑一次。

```bash
mvn -q compile                                           # 联网时先编译好
```

```bash
tools/xpub.sh                                            # 断网后跑；按提示输入助记词，不回显
```

它只打印 `CHAINPAY_DEPOSIT_XPUB=…` 和前三个地址，前三个地址必须和钱包里同一助记词的前三个账户一致。

**热钱包私钥**（服务端唯一的私钥）：断网，用**另一句**助记词，或同一句的硬化账户 `m/44'/60'/1'/0/0`。

```bash
tools/mnemonic.sh                                        # 断网生成一句新的 12 词助记词，只显示一次
```

```bash
tools/hotwallet.sh                                       # 按提示输入助记词；私钥只打印一次
```

把 `CHAINPAY_PAYOUT_HOT_WALLET_KEY=…` 粘进 `env/local.env` 后清屏；给打印的地址领 Sepolia ETH（付 gas）并转入 LINK。**绝不能用收款树的普通子私钥**：xpub 加任意一个子私钥 = 父私钥。

**私钥检查**：提交前、打镜像后、导出日志时跑，命中就退出 1、只打印前 6 位。

```bash
tools/check-secrets.sh                                   # 扫仓库；也可以传目录：tools/check-secrets.sh /path/to/logs
```

**手工调商户接口**：先用管理接口给商户发一把凭证（`secret` 只在这一次响应里出现），写进 `env/drill.env`（`CHAINPAY_API_KEY`、`CHAINPAY_API_SECRET`），再用签名客户端 `tools/api.py`。

```bash
eval "$(tools/admin.sh login ops)" && tools/admin.sh reauth
tools/admin.sh POST /admin/v1/merchants/1/credentials '{"label":"drill"}'
```

```bash
set -a; . env/drill.env; set +a
tools/api.py GET '/api/v1/deposits/balance?token=0x779877A7B0D9E8603169DdbD7836e478b4624789'
```

`tools/api.py` 的签名串与服务端、测试助手三处必须一字不差（CLAUDE.md「接口与安全」）。

## 连开发库

```bash
psql "postgresql://chainpay:chainpay_local_dev@127.0.0.1:5433/chainpay"
```

## 目录

```
chainpay/
├── CLAUDE.md / LEARNING-PATH.md   规约 / 学习路线与进度
├── docs/knowledge/                每个里程碑的前置知识与取舍
├── docs/runbook/                  出了事怎么办
├── docs/retro/                    复盘（gitignore，本地）
├── deploy/                        部署与回滚脚本
├── tools/                         离线密钥工具、管理与商户客户端、镜像与密钥扫描
├── db/init/                       首次建库时建角色
├── env/                           env 样例（真实的 *.env 不进 git）
└── src/main/resources/db/migration/   迁移（执行过的只能往前加，不能改）
```

---

## 已经踩过的三个版本坑

都是「选最新版」的代价。共同教训：**报错信息指向的位置，往往不是根因所在的位置。**

1. **Testcontainers 2.x 改了 Maven 坐标**：`postgresql` → `testcontainers-postgresql`、`junit-jupiter` → `testcontainers-junit-jupiter`。
   报错是 `'dependencies.dependency.version' ... is missing`，看起来像忘了写版本号，其实是坐标不存在、BOM 匹配不上。Java 包名没变。
2. **Spring Boot 4 把自动配置拆成了独立模块**：只加 `flyway-core` 拿不到 `FlywayAutoConfiguration`，必须显式加 `spring-boot-flyway`。
   现象很隐蔽：应用正常启动、一个错误都没有，只是一张表都没建，直到第一条 SQL 报 `relation "entry" does not exist`。
3. **PostgreSQL 18 的官方镜像改了推荐挂载点**：卷挂 `/var/lib/postgresql`，不再是 `/var/lib/postgresql/data`。
   沿用旧路径容器会反复重启（`there appears to be PostgreSQL data in ... (unused mount/volume)`）。Testcontainers 不挂卷所以测试一直是好的——**「测试通过」和「本地能跑」是两件事**。
