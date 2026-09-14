#!/usr/bin/env bash
# 打完镜像后扫一遍（M6-①）：
#   ① 以非 root 用户跑   ② 有 HEALTHCHECK   ③ /app 里没有私钥形态（tools/check-secrets.sh）
#   ④ env/local.env 里每个密钥形态变量的值都 grep 不到（只报变量名，值不出现在输出里）   ⑤ 没有 env 目录
# 用法：tools/image-check.sh [镜像:标签]   默认 chainpay:local。命中任何一条退出 1。
set -euo pipefail
IMAGE=${1:-chainpay:local}
cd "$(dirname "$0")/.."
bad=0

user=$(docker inspect --format '{{.Config.User}}' "$IMAGE")
if [[ -z $user || $user == root || $user == 0 ]]; then echo "✗ 镜像以 root 跑（USER=${user:-空}）"; bad=1; else echo "✓ USER $user"; fi

if [[ $(docker inspect --format '{{if .Config.Healthcheck}}yes{{end}}' "$IMAGE") == yes ]]; then echo "✓ 有 HEALTHCHECK"; else echo "✗ 没有 HEALTHCHECK"; bad=1; fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
cid=$(docker create "$IMAGE")
docker export "$cid" | tar -x -C "$tmp" 2>/dev/null
docker rm "$cid" >/dev/null
echo "镜像文件系统已展开到临时目录（$(find "$tmp" -type f | wc -l | tr -d ' ') 个文件）"

if tools/check-secrets.sh "$tmp/app" >/dev/null; then echo "✓ /app 里没有私钥形态"; else echo "✗ /app 里有私钥形态"; bad=1; fi

if [[ -f env/local.env ]]; then
  while IFS='=' read -r k v; do
    [[ $k =~ ^CHAINPAY_[A-Z_]*(PASSWORD|KEY|TOKEN|RPC_URL|XPUB)$ ]] || continue
    v=${v%\"}; v=${v#\"}
    [[ ${#v} -ge 12 ]] || continue
    if grep -rqF -- "$v" "$tmp" 2>/dev/null; then echo "✗ 镜像里能 grep 到 $k 的值"; bad=1; else echo "✓ $k 的值不在镜像里"; fi
  done < <(grep -E '^CHAINPAY_' env/local.env)
else
  echo "（没有 env/local.env，跳过按值扫描）"
fi

if [[ -e $tmp/env || -e $tmp/app/env ]]; then echo "✗ 镜像里有 env 目录"; bad=1; else echo "✓ 镜像里没有 env 目录"; fi
exit $bad
