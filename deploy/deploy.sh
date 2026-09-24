#!/usr/bin/env bash
# 部署八环节：构建 → 配置检查 → 镜像扫描 → 迁移 → 打标签 → 切换 → 验证 → 回滚。任何一步失败即停；验证不过自动回滚。
# 发布记录是两个 docker 标签：chainpay:current（在跑的）与 chainpay:previous（上一版），compose 永远跑 current。
# 用法：deploy/deploy.sh [git 引用，默认 HEAD]。要求：env 文件齐全（默认 env/local.env，CHAINPAY_ENV_FILE 可换）、Docker 与中间件在跑。
# 每一环是一个函数，main 按顺序调；被 source 时只定义函数、不跑 main（末尾的守卫），DeployScriptTest 靠这一点配假 docker 逐环验证。
# DeployGuardTest 按各环节命令第一次出现的位置核对顺序：注释里别提前写出后面环节的命令。
set -euo pipefail

ENV_FILE=${CHAINPAY_ENV_FILE:-env/local.env}
START=$(date +%s)
step() { printf '\n== %s（%ss）==\n' "$1" "$(( $(date +%s) - START ))"; }

# ---------------------------------------------------------------- ① 构建：标签由内容决定，构建的也只是那份内容
# 构建上下文永远是一棵 git tree（git archive 打包），标签里写的就是这棵 tree；同一个标签 = 同一份内容，「已有就跳过构建」才安全：
#   干净的提交            → chainpay:<提交>，构建那个提交的 tree
#   HEAD 且工作区有改动    → chainpay:<提交>-dirty.<tree 前 7 位>，构建工作区那棵 tree（含未跟踪文件，不含 .gitignore 挡住的）
# 所以 .gitignore 挡住的文件（env/*.env、*.key）根本进不了构建上下文，不只靠 .dockerignore 一道。

# 把工作区写成一棵 tree，输出它的号：内容一样，号就一样。用全新的临时暂存区按内容从头算，真正的 index 不动。
# 不能复制真正的 index 来用：git 凭文件属性判断「没改」，这台机器上修改时间按秒比；复制出来的 index 带着新时间，
# git「条目不早于 index 本身就回头读内容」的防护随之失效，同一秒里改成同样长度的内容会漏判。
worktree_tree() {
  local dir status=0
  dir=$(mktemp -d)
  { GIT_INDEX_FILE=$dir/index git add -A && GIT_INDEX_FILE=$dir/index git write-tree; } || status=$?
  rm -rf "$dir"
  return $status
}

# 输出「<要构建的 tree> <镜像标签>」
resolve_source() {
  local ref=$1 sha tree work
  sha=$(git rev-parse --short --verify "$ref^{commit}")
  tree=$(git rev-parse --verify "$ref^{tree}")
  if [[ $ref == HEAD ]]; then
    work=$(worktree_tree)
    if [[ $work != "$tree" ]]; then
      echo "$work chainpay:$sha-dirty.${work:0:7}"
      return
    fi
  fi
  echo "$tree chainpay:$sha"
}

build_image() {
  local tree=$1 image=$2
  if docker image inspect "$image" >/dev/null 2>&1; then
    echo "已有 $image：标签由内容决定，同一个标签就是同一份内容，跳过构建"
    return
  fi
  git archive --format=tar "$tree" | docker build -q -t "$image" - >/dev/null
}

# ---------------------------------------------------------------- ② 配置：必填的都在、形状对；只报名字，不回显值
# 函数体是圆括号 = 子 shell：set -a 导出的密钥只活在这对括号里，检查完随子 shell 一起消失。
# 写成花括号的话，密钥留在部署脚本的环境里，后面每个 docker 子进程都继承一份
check_config() (
  [[ -f $ENV_FILE ]] || { echo "✗ 找不到 $ENV_FILE（CHAINPAY_ENV_FILE 可换）"; exit 1; }
  set -a; source "$ENV_FILE"; set +a
  local bad=0
  for k in CHAINPAY_DB_PASSWORD CHAINPAY_FLYWAY_PASSWORD CHAINPAY_SYSTEM_DB_PASSWORD CHAINPAY_SECRET_KEY CHAINPAY_DEPOSIT_XPUB; do
    if [[ -z ${!k:-} ]]; then echo "✗ $k 没设"; bad=1; else echo "✓ $k"; fi
  done
  if [[ -n ${CHAINPAY_SECRET_KEY:-} && $(printf '%s' "$CHAINPAY_SECRET_KEY" | base64 -d 2>/dev/null | wc -c | tr -d ' ') != 32 ]]; then
    echo "✗ CHAINPAY_SECRET_KEY 不是 Base64 的 32 字节"; bad=1
  fi
  for k in CHAINPAY_CHAIN_RPC_URL CHAINPAY_PAYOUT_HOT_WALLET_KEY CHAINPAY_ALERT_WEBHOOK_URL; do
    [[ -n ${!k:-} ]] && echo "✓ $k（已设）" || echo "· $k 未设：对应模块不装配"
  done
  return $bad
)

# ---------------------------------------------------------------- ③ 镜像扫描：镜像里 grep 不到任何一个密钥值（按值扫描用第 ② 步同一个 env 文件）
scan_image() {
  CHAINPAY_ENV_FILE=$ENV_FILE tools/image-check.sh "$1"
}

# ---------------------------------------------------------------- ④ 迁移：新镜像单独跑一次 --migrate-only；失败就到此为止，旧版本还在跑
# 两处 grep 只挑几行给人看，不决定成败：开着 pipefail，一行都没匹配上时 grep 返回 1，set -e 会在迁移成功后掐断部署、
# 或在说出「迁移失败」之前就退出，所以两处都带 || true。成功时删临时日志；失败时留着，路径打给人
migrate() {
  local image=$1 log
  log=$(mktemp "${TMPDIR:-/tmp}/chainpay-migrate.XXXXXX")     # 写明模板：macOS 的 mktemp 不带模板时不认 TMPDIR
  if CHAINPAY_IMAGE="$image" docker compose run --rm --no-deps app --migrate-only >"$log" 2>&1; then
    grep -E "迁移完成|Successfully validated|Migrating" "$log" | sed 's/^.*\] //' | cut -c1-140 || true
    rm -f "$log"
  else
    grep -E "迁移失败|ERROR|Message" "$log" | sed 's/^.*\] //' | cut -c1-140 | head -8 || true
    echo "✗ 迁移失败：没有切换，旧版本继续跑（完整日志 $log）"
    return 1
  fi
}

# ---------------------------------------------------------------- ⑤ 打标签：标签就是发布记录
# HAD_PREVIOUS 记下「这一次有没有上一版可退」，第 ⑧ 步据此决定是回滚还是停下
tag_release() {
  local image=$1
  HAD_PREVIOUS=0
  if docker image inspect chainpay:current >/dev/null 2>&1; then
    docker tag chainpay:current chainpay:previous
    HAD_PREVIOUS=1
    echo "previous ← $(docker image inspect --format '{{.Id}}' chainpay:previous | cut -c8-19)"
  fi
  docker tag "$image" chainpay:current
  echo "current  ← $image"
}

# ---------------------------------------------------------------- ⑥ 切换：compose 永远跑 current
switch_over() {
  docker compose up -d app 2>&1 | tail -1
}

# ---------------------------------------------------------------- ⑦ 验证：readiness 通才算部署成功
wait_ready() {
  for i in $(seq 1 40); do
    if [[ $(docker inspect --format '{{.State.Health.Status}}' chainpay-app 2>/dev/null) == healthy ]] \
       && docker exec chainpay-app curl -fsS http://127.0.0.1:8096/actuator/health/readiness >/dev/null 2>&1; then
      echo "✓ healthy，readiness 200（$((i*3))s）"; return 0
    fi
    sleep 3
  done
  return 1
}

# 就绪之后看一眼 work 组，只打给人看、不参与成败：readiness 不含 work（索引器停了要叫人，不是把进程下线），但刚发布完人该知道后台任务的状态。
# 只取顶层 status：Boot 4 按字母序排键，它不是第一个键就是最后一个；部件自己的 status 永远不在两头。部件细节不拆（可能嵌套），打出查看的命令
work_summary() {
  local body overall
  body=$(docker exec chainpay-app curl -s http://127.0.0.1:8096/actuator/health/work 2>/dev/null) || true
  overall=$(printf '%s' "$body" | grep -oE '^\{"status":"[A-Z_]+"|"status":"[A-Z_]+"\}$' | grep -oE '[A-Z_]{2,}' | head -1) || true
  echo "· work 组：${overall:-取不到}（只供参考，不影响部署结果；细节：docker exec chainpay-app curl -s http://127.0.0.1:8096/actuator/health/work）"
}

# ---------------------------------------------------------------- ⑧ 回滚：验证不过就退回 previous；第一次部署没有 previous，就停下
# 第一次部署没有上一版可退：退回部署之前的样子——容器停下、坏镜像改叫 failed 留着查、current 摘掉。
# 只报「无处可退」的话，current 仍指着坏镜像，restart: unless-stopped 会把它一遍遍拉起
roll_back_or_stop() {
  if [[ ${HAD_PREVIOUS:-0} == 1 ]]; then
    echo "✗ 新版本 120 秒内没有就绪，回滚"
    deploy/rollback.sh
    return
  fi
  echo "✗ 新版本 120 秒内没有就绪；这是第一次部署，没有上一版可退"
  docker compose stop app 2>&1 | tail -1
  docker tag chainpay:current chainpay:failed
  docker rmi chainpay:current >/dev/null              # 只摘标签：镜像还挂在 chainpay:failed 和它自己的标签上
  echo "已停下 app；坏镜像在 chainpay:failed，查完修好再部署"
}

main() {
  cd "$(dirname "${BASH_SOURCE[0]}")/.."
  local ref=${1:-HEAD} resolved tree image
  resolved=$(resolve_source "$ref")
  tree=${resolved%% *}; image=${resolved#* }
  if [[ $image == *-dirty.* ]]; then echo "⚠ 工作区有未提交改动：构建的是工作区此刻的内容，镜像标签 $image"; fi
  step "① 构建 $image";                   build_image "$tree" "$image"
  step "② 配置检查";                      check_config
  step "③ 镜像扫描";                      scan_image "$image"
  step "④ 迁移（新镜像，只迁移不起应用）"; migrate "$image"
  step "⑤ 打标签";                        tag_release "$image"
  step "⑥ 切换";                          switch_over
  step "⑦ 验证"
  if ! wait_ready; then
    roll_back_or_stop
    exit 1
  fi
  work_summary
  echo; echo "部署完成：$image 在跑（$(( $(date +%s) - START ))s）"
}

# 直接执行才跑 main；被 source（测试）时只定义上面的函数
if [[ ${BASH_SOURCE[0]} == "$0" ]]; then
  main "$@"
fi
