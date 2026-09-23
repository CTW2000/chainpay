# syntax=docker/dockerfile:1
# 两个阶段：构建（JDK + Maven，只活在构建期）→ 运行（JRE，非 root，只装应用）。
# 密钥永远不进这里：没有 ARG / ENV 放密码，运行时全部从 env_file 注入（docker-compose.yml 的 app 服务）。
# 打完镜像跑 tools/image-check.sh：非 root、有 HEALTHCHECK、文件系统里 grep 不到 env/local.env 的任何一个值。

# ---------------------------------------------------------------- 构建阶段
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
# 先只拷 pom 再拷源码：源码改了不用重新解析依赖。~/.m2 挂成 BuildKit 缓存，第二次构建不再下载依赖
COPY pom.xml ./
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -Dmaven.test.skip=true package
# 把胖 jar 按层拆开（依赖 / 加载器 / 快照依赖 / 应用代码）：依赖层几乎不变，能被镜像缓存复用；每次发布真正变化的只有应用层
RUN java -Djarmode=tools -jar target/chainpay-*.jar extract --layers --destination /layers

# ---------------------------------------------------------------- 运行阶段
FROM eclipse-temurin:25-jre-noble AS runtime
# curl 只为 HEALTHCHECK；专用系统用户，无家目录、不能登录（OWASP Docker RULE #2）
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system chainpay \
 && useradd --system --gid chainpay --no-create-home --shell /usr/sbin/nologin chainpay
WORKDIR /app
# 四层按「最不常变 → 最常变」的顺序拷：前面的层命中缓存，改一行代码只重打最后一层
COPY --from=build --chown=chainpay:chainpay /layers/dependencies/ ./
COPY --from=build --chown=chainpay:chainpay /layers/spring-boot-loader/ ./
COPY --from=build --chown=chainpay:chainpay /layers/snapshot-dependencies/ ./
COPY --from=build --chown=chainpay:chainpay /layers/application/ ./
USER chainpay
# 主端口对外（compose 只绑到宿主的 127.0.0.1）；管理端口 8096 只绑容器内回环，HEALTHCHECK 在容器里打它，宿主从外面到不了
EXPOSE 8095
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8096/actuator/health/readiness || exit 1
# 堆按容器内存上限的 75% 算（RULE #7 的内存限制在 compose 里给）；OOM 直接退出让容器运行时重启，不带着半死的堆继续跑
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "chainpay-0.1.0-SNAPSHOT.jar"]
