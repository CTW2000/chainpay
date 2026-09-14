#!/usr/bin/env bash
# 管理接口（/admin/**）的调用器。控制面只认「本机回环 + 令牌」：应用在容器里跑时，宿主打到发布端口的请求源地址是
# Docker 网桥网关，不是回环，会被 401——这是设计如此（能登上这台机器、能 docker exec 的人才算本机）。
# 所以在容器里发请求：源地址是容器内回环。令牌只从环境变量 CHAINPAY_ADMIN_TOKEN 来，不进参数、不进 shell 历史。
#
# 用法：tools/admin.sh GET  /admin/v1/indexer
#       tools/admin.sh POST /admin/v1/audit/run
#       tools/admin.sh POST /admin/v1/payouts/7/reject '{"reason":"演练"}'
#       CHAINPAY_APP_CONTAINER=chainpay-app（默认）；没有容器在跑时退而打宿主的 127.0.0.1:8095（nohup 起的 jar）。
set -euo pipefail
method=${1:?GET|POST}; path=${2:?/admin/...}; body=${3:-}
: "${CHAINPAY_ADMIN_TOKEN:?先 set -a; source env/local.env; set +a}"
container=${CHAINPAY_APP_CONTAINER:-chainpay-app}
args=(-s -S -X "$method" -H "X-CP-ADMIN-TOKEN: $CHAINPAY_ADMIN_TOKEN" -H 'Content-Type: application/json')
[[ -n $body ]] && args+=(-d "$body")
if docker inspect --format '{{.State.Running}}' "$container" 2>/dev/null | grep -q true; then
  docker exec -e CHAINPAY_ADMIN_TOKEN "$container" curl "${args[@]}" "http://127.0.0.1:8095$path"
else
  curl "${args[@]}" "http://127.0.0.1:${CHAINPAY_PORT:-8095}$path"
fi
echo
