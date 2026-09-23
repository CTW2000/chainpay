# M6 · 上线：背景与取舍

> M6，2026-09-14 至 09-15 完成：部署八环节（构建 → 配置 → 密钥 → 迁移 → 分发 → 切换 → 验证 → 回滚）都有对应的脚本或代码，系统在 docker compose 里无人值守地跑、会叫人、能回滚。
> 现在的形状与规则见 CLAUDE.md「上线与运维」「控制面」，操作见 `docs/runbook/ops.md`；之后的进程拆分见 `m6-process-split.md`。

## 一、背景

上线不是「换台机器」，是把「人盯」换成「机器盯」：谁判断它活着、谁判断它能干活、谁在它停下时叫人、坏了怎么退回上一版、退回时数据库怎么办——每个问题都要有一段代码或脚本对应。
部署目标是这台 Mac 上的 docker compose；脚本不依赖 Mac 特有的东西，换到一台 Linux 主机不用改。

- **三个健康问题不是一回事**：liveness 问进程在不在（进程管理器据此重启）；readiness 问能不能接请求；work 问能不能干活（索引器、入账、热钱包、判官、Redis）。
  把「索引器停了」放进 readiness，下线的是整个进程，商户连余额都查不了——它该叫人，不该下线。
- **探针不带令牌**：进程管理器、容器 HEALTHCHECK 都不会带，所以健康端点本身没有认证。
- **回滚时数据库不跟着回滚**：undo 脚本在生产几乎没人敢跑；可行的是迁移只前进（expand / contract）——每次发布的迁移都让上一版代码能在新 schema 上跑（先加后删）。

参考：`owasp-cheatsheets/Docker_Security.md`、`CI_CD_Security.md`、`Database_Security.md`；`correctness/strong-migrations-unsafe-operations.md`（相对 `~/Documents/CodeProject/flow-pay-backend/docs/ai/knowledge/`）。

## 二、取舍（九条都按建议定下）

1. **健康检查用 Actuator**：分组、探针、HTTP 状态映射、连接池与 Redis 的指示器是协议编码，自己写只会写出一个差的 Actuator；每个指示器「判什么算能干活」是业务，自己写（CLAUDE.md §7 同一条原则）。
2. **独立管理端口，只绑回环**（8096）：放在主端口上，就是对外开一个无认证接口；独立端口从外面根本到不了。主端口上没有 `/actuator`。
3. **readiness 只放数据库（两个连接池）**：商户 API 只要两个池通就能答；其余放 work 组，给告警和人看，不影响进程去留。
4. **自定义一档 DEGRADED**：排在 DOWN 之后、UP 之前，HTTP 200。告警按「不是 UP」触发，容器不重启。
5. **进程管理器用 Docker**（`restart: unless-stopped`）：Mac 与 Linux 一致；systemd 只会多一个「管 docker compose」的壳。
6. **迁移单独一步**（`--migrate-only`，在切换之前），应用启动时照旧迁移、`validate-on-migrate` 兜底：单独一步时迁移失败，旧版本还在跑、没有切换；随启动失败，新容器起不来、compose 反复重启它。
   兜底那一道保证「schema 和代码对不上就不起来」。
7. **注资登记只记一张表，托管等式加上它，不走账本**：运营充进热钱包的币不是任何商户的钱；走账本要先造一个「平台自有资金」账户体系，那是 M7 之后的题。
   运营只指认是哪一条日志，金额、块、代币都从索引到的日志读（数字不由人填）；块 ≤ finalized 才收——会被重组翻掉的钱不进等式。
8. **告警走通用 webhook**（载荷形状 generic / slack / dingtalk / feishu）：一个 POST JSON 能接各家的入站 webhook；邮件要 SMTP 凭证和一整套投递问题。
   告警读的就是健康检查的 work 组，没有第二套判定；只在变化时叫，送到了才记下。
9. **迁移只前进，代码兼容前一版 schema，不写 undo**：Flyway 容忍库里的「未来」版本，回滚到上一版镜像才起得来。代价是每条迁移都得先加后删（改列类型做不到，见 CLAUDE.md）。

另外两处：
- **追赶按时间封顶，不按批数**（`catch-up-budget` 5 分钟）：落后时连续推批直到追平或预算用完；一轮跑太久，压住的是索引器自己的降级检测与状态更新。
- **控制面换成管理员体系**（第 ⑤ 步）：静态令牌是一把能配钥匙的钥匙，泄露了吊销不掉。口令散列 Argon2id 用 `spring-security-crypto`（不引整个 Spring Security，实现来自已在树里的 BouncyCastle）；三道门与会话参数见 CLAUDE.md「控制面」。

## 三、不做的

- **Kubernetes**：一台机器一个 compose 足够；探针按 k8s 的约定命名（liveness / readiness），将来搬过去不用改。
- **多实例**：限流已经在 Redis 里，但定时任务（索引、入账、发送、追踪、对账、告警）都没有分布式锁，两个实例会各跑一份。先单实例，多实例是 M7 之后的题。
- **TOTP 二次验证、提现冷却期、管理员的增删与停用接口**：进程拆分之后再做。
