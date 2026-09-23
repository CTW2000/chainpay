#!/usr/bin/env bash
# 管理接口（/admin/**）的调用器。
# 控制面只认「本机回环 + 会话」：应用在容器里跑时宿主打发布端口的源地址是网桥网关，会 401，所以在容器里发 curl。
#
#   tools/admin.sh login <用户名>          提示输入口令（不回显），打印一行 export CHAINPAY_ADMIN_SESSION=…，eval 它
#   tools/admin.sh reauth                  敏感操作（发凭证、建商户、核准 / 拒绝、改限额、登记注资）前用口令再认证，5 分钟有效
#   tools/admin.sh logout
#   tools/admin.sh GET  /admin/v1/indexer
#   tools/admin.sh POST /admin/v1/audit/run
#   tools/admin.sh POST /admin/v1/payouts/7/reject '{"reason":"演练"}'
# 令牌只在环境变量 CHAINPAY_ADMIN_SESSION 里（eval 那一行之后），不进参数、不进文件、不进 shell 历史。
# 第一个管理员：口令先用 read -rs 读进环境变量（别写在命令行上，会进 shell 历史），再
#   docker compose run --rm --no-deps -e CHAINPAY_ADMIN_PASSWORD app --create-admin <用户名>，做完 unset。完整写法见 docs/runbook/ops.md「控制面」
set -euo pipefail
container=${CHAINPAY_APP_CONTAINER:-chainpay-app}
call() {   # method path [body] [extra curl args…]
  local method=$1 path=$2 body=${3:-}; shift 2; [[ $# -gt 0 ]] && shift
  local args=(-s -S -X "$method" -H 'Content-Type: application/json')
  [[ -n ${CHAINPAY_ADMIN_SESSION:-} ]] && args+=(-H "X-CP-ADMIN-SESSION: $CHAINPAY_ADMIN_SESSION")
  [[ -n $body ]] && args+=(-d "$body")
  if docker inspect --format '{{.State.Running}}' "$container" 2>/dev/null | grep -q true; then
    docker exec -e CHAINPAY_ADMIN_SESSION="${CHAINPAY_ADMIN_SESSION:-}" "$container" curl "${args[@]}" "http://127.0.0.1:8095$path"
  else
    curl "${args[@]}" "http://127.0.0.1:${CHAINPAY_PORT:-8095}$path"
  fi
}
json_escape() { python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$1"; }
case ${1:?login|reauth|logout|GET|POST|PUT} in
  login)
    user=${2:?用户名}; read -r -s -p "口令（不回显）: " pw; echo >&2
    resp=$(call POST /admin/v1/auth/login "{\"username\":$(json_escape "$user"),\"password\":$(json_escape "$pw")}"); unset pw
    token=$(printf '%s' "$resp" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["data"]["token"] if d.get("data") else "")')
    [[ -n $token ]] || { echo "登录失败：$resp" >&2; exit 1; }
    echo "export CHAINPAY_ADMIN_SESSION=$token" ;;
  reauth)
    : "${CHAINPAY_ADMIN_SESSION:?先 eval \"\$(tools/admin.sh login <用户名>)\"}"
    read -r -s -p "口令（不回显）: " pw; echo >&2
    call POST /admin/v1/auth/reauth "{\"password\":$(json_escape "$pw")}"; unset pw; echo ;;
  logout) call POST /admin/v1/auth/logout; echo; echo "unset CHAINPAY_ADMIN_SESSION" ;;
  GET|POST|PUT)
    : "${CHAINPAY_ADMIN_SESSION:?先 eval \"\$(tools/admin.sh login <用户名>)\"}"
    call "$1" "${2:?/admin/...}" "${3:-}"; echo ;;
  *) echo "不认识：$1" >&2; exit 2 ;;
esac
