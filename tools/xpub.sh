#!/usr/bin/env bash
# 离线 xpub 工具：从助记词算出 m/44'/60'/0' 的账户层 xpub 与前三个地址。
#
#   1. 先断网。
#   2. 先编译一次（需要联网下载依赖的话在断网前做）：JAVA_HOME=~/.local/jdk-25/Contents/Home mvn -q compile
#   3. tools/xpub.sh   然后按提示输入助记词（不回显）
#
# 助记词不进参数、不进环境变量、不进任何文件；只在这个 JVM 的内存里活几百毫秒。
set -euo pipefail
cd "$(dirname "$0")/.."
# 这台机器的 shell 默认 JAVA_HOME 指向 jdk-21，所以不能信 ${JAVA_HOME:-…}：有 jdk-25 就用它，然后核对主版本号
JDK25="${CHAINPAY_JDK25:-$HOME/.local/jdk-25/Contents/Home}"   # 可用环境变量指到别处，测试拒绝路径时也用它
if [ -x "$JDK25/bin/java" ]; then export JAVA_HOME="$JDK25"; fi
: "${JAVA_HOME:?需要 JDK 25：设 JAVA_HOME，或安装到 ~/.local/jdk-25}"
[ -x "$JAVA_HOME/bin/java" ] || { echo "JAVA_HOME=$JAVA_HOME 下没有 bin/java；需要 JDK 25" >&2; exit 1; }
MAJOR="$("$JAVA_HOME/bin/java" -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p')"
[ "${MAJOR:-0}" -ge 25 ] || { echo "需要 JDK 25，当前 $JAVA_HOME 是 ${MAJOR:-未知}" >&2; exit 1; }
CP_FILE="$(mktemp)"
trap 'rm -f "$CP_FILE"' EXIT
# -o = 离线：断网状态下也能拼出 classpath（依赖已在本机 m2 里）
mvn -q -o dependency:build-classpath -Dmdep.outputFile="$CP_FILE" -Dmdep.includeScope=runtime >/dev/null
exec "$JAVA_HOME/bin/java" -cp "target/classes:$(cat "$CP_FILE")" com.chainpay.chain.wallet.XpubTool
