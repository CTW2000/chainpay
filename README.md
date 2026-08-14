# chainpay

加密支付网关 —— **学习项目**。完整的学习路线在 [LEARNING-PATH.md](LEARNING-PATH.md)。

当前进度：**M0 · 账本地基**（三个判官测试是红的，等你实现）。

---

## 环境

| 组件 | 版本 | 状态 |
|---|---|---|
| Java | **25 (LTS)** | 已装在 `~/.local/jdk-25` |
| Spring Boot | **4.1.0**（Spring Framework 7.0.8） | pom 已配 |
| PostgreSQL | **18** | 通过 Docker |
| Flyway | 12.4.0 | Boot 托管 |
| Testcontainers | 2.0.5 | Boot 托管 |
| Maven | 3.9.16 | 已装 |
| Docker | 29.7.2 | Docker Desktop |

### 每次开工前

```bash
export JAVA_HOME=~/.local/jdk-25/Contents/Home
```

想省事就写进 `~/.zshrc`。注意：**flow-pay 用的是 JDK 21**，两个项目的 `JAVA_HOME` 不一样。

---

## 跑起来

### 1. 启动 Docker Desktop

```bash
open -a Docker
```

### 2. 跑测试（不需要手工起数据库）

```bash
JAVA_HOME=~/.local/jdk-25/Contents/Home mvn test
```

Testcontainers 会自己拉起一个 PostgreSQL 18 容器，跑完自动销毁。
**第一次会拉镜像，慢一些（约 2 分钟）；之后几秒。**

预期结果——**四个测试全部失败**，且失败原因都是：

```
java.lang.UnsupportedOperationException: M0：transfer 由你来实现
```

**这是正确状态。** 如果你看到别的错误，说明环境有问题，先解决环境。

### 3. 想手工连数据库看看（可选）

```bash
docker compose up -d
```

```bash
psql "postgresql://chainpay:chainpay_local_dev@127.0.0.1:5433/chainpay"
```

这个是本地开发库，和测试用的容器是两回事——测试不依赖它。

---

## 目录结构

```
chainpay/
├── LEARNING-PATH.md            ← 学习路线，先读这个
├── README.md                   ← 你在这
├── CLAUDE.md                   ← 项目规约（AI 和你都要遵守）
├── docker-compose.yml          ← 本地 Postgres 18
├── pom.xml
├── docs/
│   └── retro/                  ← 每个里程碑的复盘（gitignore，本地保留）
└── src/
    ├── main/
    │   ├── java/com/chainpay/
    │   │   ├── ChainpayApplication.java
    │   │   └── ledger/service/
    │   │       ├── LedgerService.java       ← 契约都写在 javadoc 里
    │   │       └── LedgerServiceImpl.java   ← ★ M0 你要写的地方
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/V1__ledger.sql  ← 账本 schema，读一遍
    └── test/java/com/chainpay/
        ├── support/AbstractPostgresTest.java
        └── ledger/LedgerInvariantTest.java  ← ★ 三个判官
```

---

## 你的第一步（不要跳）

**不要打开 `LedgerServiceImpl.java` 就开始写。**

1. 读 [LEARNING-PATH.md](LEARNING-PATH.md) 第四节「学习方法」
2. 读 `V1__ledger.sql`——注释里解释了每个约束为什么存在
3. 读 `LedgerService.java` 的 javadoc——三条契约
4. **在 `docs/retro/M0-before.md` 里写下你认为这个账本会怎么坏**
5. 写完之后，再展开 LEARNING-PATH.md 里 M0 那份折叠的清单对答案

> 第 4 步是这个项目里唯一能测出你在进步的东西。

---

## 已经踩过的三个版本坑（留作记录）

搭这个骨架时真实遇到的，都是「选最新版」的代价：

**① Testcontainers 2.x 改了 Maven 坐标**

```xml
<!-- 1.x（网上示例几乎都是这个） -->
<artifactId>postgresql</artifactId>
<artifactId>junit-jupiter</artifactId>

<!-- 2.x（正确） -->
<artifactId>testcontainers-postgresql</artifactId>
<artifactId>testcontainers-junit-jupiter</artifactId>
```

报错是 `'dependencies.dependency.version' ... is missing`，看起来像版本号忘了写，
实际是坐标不存在所以 BOM 匹配不上。**Java 包名没变，只有 Maven 坐标变了。**

**② Spring Boot 4 把自动配置拆成了独立模块**

只加 `flyway-core` 只得到 Flyway 库本身，**拿不到 Spring 的 `FlywayAutoConfiguration`**。
必须显式加 `org.springframework.boot:spring-boot-flyway`。

**这个坑的现象特别隐蔽**：应用正常启动、数据源连得上、日志里一个错误都没有，
只是**一张表都没建**，直到第一条 SQL 报 `relation "entry" does not exist`。

**③ PostgreSQL 18 的官方镜像改了推荐挂载点**

```yaml
# 17 及以前（网上示例几乎都是这个）
- chainpay-pgdata:/var/lib/postgresql/data

# 18+（正确）
- chainpay-pgdata:/var/lib/postgresql
```

沿用旧路径会让容器**反复重启**，日志里说
`there appears to be PostgreSQL data in ... (unused mount/volume)`。
改动的原因是让将来的 `pg_upgrade --link` 不跨挂载点边界。

注意 Testcontainers 的测试**不受影响**——它不挂持久卷，所以 `mvn test` 一直是好的，
只有 `docker compose up` 才炸。**「测试通过」和「本地能跑」是两件事**，
这正好是前面讲部署时那条「测试环境验证不了生产产物」的微缩版。

> 三个坑的共同教训：**报错信息指向的位置，往往不是根因所在的位置。**
> ① 看起来是"版本号忘了写"，② 看起来是"SQL 写错了"，③ 看起来是"数据损坏了"。
