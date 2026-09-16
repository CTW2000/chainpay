#!/usr/bin/env bash
# 打完镜像后扫一遍（M6-①）：
#   ① 以非 root 用户跑   ② 有 HEALTHCHECK   ③ /app 与镜像元数据里没有私钥形态（tools/check-secrets.sh）
#   ④ env/local.env 里每个密钥形态变量的值在文件系统、元数据与构建历史里都 grep 不到（只报变量名，值不出现在输出里）   ⑤ 没有 env 目录
#
# 元数据 = Config.Env / Labels / Cmd / Entrypoint + 构建历史（2026-09-15 补）。docker export 只导文件系统，
# 密钥若从 ENV 或 docker build --build-arg 进来，光扫文件系统一无所获——而 ContainerGuardTest 只读 Dockerfile 文本，
# 也管不到「源码没写、构建时注入」这条路。
# 用法：tools/image-check.sh [镜像:标签]   默认 chainpay:local。命中任何一条退出 1。
# 环境变量：CHAINPAY_ENV_FILE=<按值扫描用的 env 文件，默认 env/local.env>；CHAINPAY_SKIP_VALUE_SCAN=1 显式跳过按值扫描。
set -euo pipefail
IMAGE=${1:-chainpay:local}
cd "$(dirname "$0")/.."
bad=0

user=$(docker inspect --format '{{.Config.User}}' "$IMAGE")
if [[ -z $user || $user == root || $user == 0 ]]; then echo "✗ 镜像以 root 跑（USER=${user:-空}）"; bad=1; else echo "✓ USER $user"; fi

if [[ $(docker inspect --format '{{if .Config.Healthcheck}}yes{{end}}' "$IMAGE") == yes ]]; then echo "✓ 有 HEALTHCHECK"; else echo "✗ 没有 HEALTHCHECK"; bad=1; fi

tmp=$(mktemp -d)
meta=$(mktemp)          # 我们自己的元数据：Config.Env / Labels / Cmd / Entrypoint
hist=$(mktemp)          # 构建历史：绝大多数行来自基础镜像
tarlog=$(mktemp)        # 解包时 tar 的抱怨：留着，别丢进 /dev/null
trap 'rm -rf "$tmp" "$meta" "$hist" "$tarlog"' EXIT
cid=$(docker create "$IMAGE")

# 解包失败就停（2026-09-15 补）。扫一个残缺或空的目录时，下面每一项都会「✓」——
# 「没找到密钥」和「根本没扫」在输出上一模一样，这是安全检查最糟的失败方式。
docker export "$cid" | tar -x -C "$tmp" 2>"$tarlog" || {
  docker rm "$cid" >/dev/null 2>&1
  echo "✗ 展开镜像失败，tar 的最后几行："; tail -5 "$tarlog"; exit 1
}
docker rm "$cid" >/dev/null

files=$(find "$tmp" -type f | wc -l | tr -d ' ')
echo "镜像文件系统已展开到临时目录（$files 个文件）"
# 断言，不是打印：文件太少 = 没解开；/app 不在 = 产物路径变了。两种情况下「没找到密钥」都没有意义。
# 500 这个下限：实测本镜像 4335 个文件，光 JRE 基础层就好几千，精简镜像也不会掉到 500 以下
[[ $files -ge 500 ]] || { echo "✗ 只展开了 $files 个文件，这次扫描不可信"; exit 1; }
[[ -d $tmp/app ]] || { echo "✗ 镜像里没有 /app 目录：产物路径变了？这次扫描不可信"; exit 1; }

# 元数据单独导两份。取不到就停：扫了个空，和「没问题」不是一回事
{
  docker inspect --format '{{json .Config.Env}}' "$IMAGE"
  docker inspect --format '{{json .Config.Labels}}' "$IMAGE"
  docker inspect --format '{{json .Config.Cmd}}' "$IMAGE"
  docker inspect --format '{{json .Config.Entrypoint}}' "$IMAGE"
} > "$meta"
docker history --no-trunc --format '{{.CreatedBy}}' "$IMAGE" > "$hist"
[[ -s $meta && -s $hist ]] || { echo "✗ 取不到镜像元数据（Config / history），这次扫描不可信"; exit 1; }
echo "镜像元数据已导出（Config 4 行 + 构建历史 $(wc -l < "$hist" | tr -d ' ') 行）"

# 形状检测只扫我们自己的 Config，不扫构建历史：历史里绝大多数是基础镜像的命令，
# temurin 下载 JRE 那一行同时带着 sha256 校验和与三处 key 字样（实测会命中「私钥形态」），
# 形状规则在那里必然常年误报。历史仍参与下面的按值扫描——按值不会误报。
if hits=$(tools/check-secrets.sh "$tmp/app" "$meta"); then
  echo "✓ /app 与镜像元数据里没有私钥形态"
else
  echo "✗ /app 或镜像元数据里有私钥形态"; bad=1
  printf '%s\n' "$hits" | grep -v '^未发现' | head -5      # check-secrets 只打前 6 位，可以直接给人看
fi

# 按值扫描的 env 文件可以换（进程拆分后会有 web.env / worker.env）。文件不在 = 这一项做不了 = 不通过；
# 确实不需要时必须显式说出来，而不是靠「文件恰好不在」蒙混过去（2026-09-15 补）
ENV_FILE=${CHAINPAY_ENV_FILE:-env/local.env}
if [[ ! -f $ENV_FILE ]]; then
  if [[ ${CHAINPAY_SKIP_VALUE_SCAN:-} == 1 ]]; then
    echo "· 按值扫描已显式跳过（CHAINPAY_SKIP_VALUE_SCAN=1）：这一轮只做了形态检测"
  else
    echo "✗ 找不到 $ENV_FILE：按值扫描做不了，这次扫描不可信"
    echo "  换文件用 CHAINPAY_ENV_FILE=…；确实不需要按值扫描用 CHAINPAY_SKIP_VALUE_SCAN=1"
    exit 1
  fi
else
  scanned=0
  while IFS='=' read -r k v; do
    [[ $k =~ ^CHAINPAY_[A-Z_]*(PASSWORD|KEY|TOKEN|RPC_URL|XPUB)$ ]] || continue
    v=${v%\"}; v=${v#\"}
    if [[ ${#v} -lt 12 ]]; then echo "· $k 的值只有 ${#v} 个字符，太短，按值扫会误报，跳过（这一项没查）"; continue; fi
    scanned=$((scanned+1))
    if grep -rqF -- "$v" "$tmp" "$meta" "$hist" 2>/dev/null; then echo "✗ 镜像里能 grep 到 $k 的值（文件系统或元数据）"; bad=1; else echo "✓ $k 的值不在镜像里"; fi
  done < <(grep -E '^CHAINPAY_' "$ENV_FILE")
  [[ $scanned -gt 0 ]] || { echo "✗ $ENV_FILE 里没有一个密钥形态的变量可扫：按值扫描等于没做"; exit 1; }
fi

if [[ -e $tmp/env || -e $tmp/app/env ]]; then echo "✗ 镜像里有 env 目录"; bad=1; else echo "✓ 镜像里没有 env 目录"; fi
exit $bad
