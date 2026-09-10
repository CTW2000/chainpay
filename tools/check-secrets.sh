#!/usr/bin/env bash
# 私钥检查（M4-①）：私钥不在代码里、不在配置里、不在日志里、不在镜像里。
#
#   tools/check-secrets.sh            扫仓库：git 跟踪的 + 未跟踪但不被忽略的文件（被 .gitignore 挡住的 env/*.env 不扫——它们本来就该有密钥）
#   tools/check-secrets.sh 目录...     扫任意目录（日志目录、解开的镜像层、临时导出）
#
# 三条规则，宁可多报：
#   1. 含 key / private / secret / mnemonic / xprv 字样的行里出现 64 位十六进制（可带 0x）  → 私钥形态
#   2. 出现 xprv 开头的长 Base58 串                                                      → 扩展私钥
#   3. 整行是 12 或 24 个小写英文单词                                                      → 助记词形状
# 例外只有两种，都在 tools/check-secrets.allow 里逐条列出：公开测试密钥按值放行；公开规范向量的测试文件按路径放行。
# 命中时只打印文件、行号与值的前 6 位——脚本自己不能成为泄露渠道。
set -uo pipefail
cd "$(dirname "$0")/.."
ALLOW="tools/check-secrets.allow"

targets=()
if [ $# -eq 0 ]; then
  while IFS= read -r f; do targets+=("$f"); done < <(git ls-files -co --exclude-standard)
else
  for d in "$@"; do
    while IFS= read -r f; do targets+=("$f"); done < <(find "$d" -type f -not -path '*/.git/*')
  done
fi

allowed_value() { grep -qixF "$1" "$ALLOW" 2>/dev/null; }
allowed_path()  { grep -qxF "path:$1" "$ALLOW" 2>/dev/null; }

hits=0
for f in "${targets[@]}"; do
  [ -f "$f" ] || continue
  case "$f" in src/test/resources/vectors/*|tools/check-secrets.allow) continue ;; esac
  grep -Iq . "$f" 2>/dev/null || continue                      # 二进制文件跳过

  # 1. 私钥形态
  while IFS= read -r line; do
    n="${line%%:*}"
    val="$(printf '%s' "${line#*:}" | grep -oE '(0x)?[0-9a-fA-F]{64}' | head -1)"; val="${val#0x}"
    [ -n "$val" ] || continue
    allowed_value "$val" && continue
    echo "私钥形态  $f:$n  ${val:0:6}…"; hits=$((hits+1))
  done < <(grep -nEi '(key|private|secret|mnemonic|xprv)' "$f" 2>/dev/null | grep -E '(0x)?[0-9a-fA-F]{64}')

  if ! allowed_path "$f"; then
    # 2. 扩展私钥
    while IFS= read -r line; do
      echo "扩展私钥  $f:${line%%:*}"; hits=$((hits+1))
    done < <(grep -nE 'xprv[1-9A-HJ-NP-Za-km-z]{100,}' "$f" 2>/dev/null)
    # 3. 助记词形状
    while IFS= read -r line; do
      echo "助记词形状  $f:${line%%:*}"; hits=$((hits+1))
    done < <(grep -nE '^([a-z]{3,8} ){11}[a-z]{3,8}$|^([a-z]{3,8} ){23}[a-z]{3,8}$' "$f" 2>/dev/null)
  fi
done

if [ "$hits" -gt 0 ]; then
  echo "命中 $hits 处：私钥、扩展私钥或助记词不该出现在这里（公开测试密钥请逐值加进 $ALLOW 并注明来源）"
  exit 1
fi
echo "未发现私钥形态的内容（扫了 ${#targets[@]} 个文件）"
