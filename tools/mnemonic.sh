#!/usr/bin/env bash
# 离线助记词工具：生成一句 12 词的 BIP-39 助记词（只显示一次）和它派生的热钱包地址。
#
#   1. 先断网、确认身后没人。
#   2. 先编译一次（需要联网下载依赖的话在断网前做）：JAVA_HOME=~/.local/jdk-25/Contents/Home mvn -q compile
#   3. tools/mnemonic.sh   → 抄下 12 个词，核对顺序，清屏
#   4. tools/hotwallet.sh  → 输入这 12 个词得到私钥，粘进 env/local.env，清屏
#
# 助记词不进参数、不进环境变量、不进任何文件；只在这个 JVM 的内存里活几百毫秒。词表随代码（src/main/resources/bip39/english.txt）。
set -euo pipefail
cd "$(dirname "$0")/.."
JDK25="${CHAINPAY_JDK25:-$HOME/.local/jdk-25/Contents/Home}"
if [ -x "$JDK25/bin/java" ]; then export JAVA_HOME="$JDK25"; fi
: "${JAVA_HOME:?需要 JDK 25：设 JAVA_HOME，或安装到 ~/.local/jdk-25}"
[ -x "$JAVA_HOME/bin/java" ] || { echo "JAVA_HOME=$JAVA_HOME 下没有 bin/java；需要 JDK 25" >&2; exit 1; }
MAJOR="$("$JAVA_HOME/bin/java" -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p')"
[ "${MAJOR:-0}" -ge 25 ] || { echo "需要 JDK 25，当前 $JAVA_HOME 是 ${MAJOR:-未知}" >&2; exit 1; }
CP_FILE="$(mktemp)"
trap 'rm -f "$CP_FILE"' EXIT
mvn -q -o dependency:build-classpath -Dmdep.outputFile="$CP_FILE" -Dmdep.includeScope=runtime >/dev/null
exec "$JAVA_HOME/bin/java" -cp "target/classes:$(cat "$CP_FILE")" com.chainpay.chain.wallet.MnemonicTool
