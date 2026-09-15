#!/usr/bin/env bash
# 部署八环节（M6-④）：构建 → 配置 → 密钥 → 迁移 → 分发 → 切换 → 验证 → 回滚。任何一步失败即停；验证不过自动回滚。
# 镜像的「账本」是两个 docker 标签：chainpay:current（在跑的）与 chainpay:previous（上一版）。compose 永远跑 current。
# 用法：deploy/deploy.sh [git 引用，默认 HEAD]      要求：env/local.env 齐全、Docker 在跑、中间件在跑。
set -euo pipefail
cd "$(dirname "$0")/.."
REF=${1:-HEAD}
SHA=$(git rev-parse --short "$REF")
# 工作区有未提交改动时打出来的镜像不是那个提交：标签加 -dirty，免得日后按提交号找不回它的来源
if [[ $REF == HEAD && -n $(git status --porcelain --untracked-files=no) ]]; then SHA="$SHA-dirty"; echo "⚠ 工作区有未提交改动，镜像标签 $SHA"; fi
IMAGE="chainpay:$SHA"
START=$(date +%s)
step() { printf '\n== %s（%ss）==\n' "$1" "$(( $(date +%s) - START ))"; }

# ---------------------------------------------------------------- ① 构建：镜像按提交打标签，同一个提交打出来的就是同一个东西
step "① 构建 $IMAGE"
if docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "已有 $IMAGE，跳过构建（想重打先 docker rmi $IMAGE）"
else
  docker build -q -t "$IMAGE" . >/dev/null
fi

# ---------------------------------------------------------------- ② 配置：必填的都在、形状对；只报名字，不回显值
step "② 配置检查"
check_config() {
  set -a; source env/local.env; set +a
  local bad=0
  for k in CHAINPAY_DB_PASSWORD CHAINPAY_FLYWAY_PASSWORD CHAINPAY_SYSTEM_DB_PASSWORD CHAINPAY_SECRET_KEY CHAINPAY_ADMIN_TOKEN; do
    if [[ -z ${!k:-} ]]; then echo "✗ $k 没设"; bad=1; else echo "✓ $k"; fi
  done
  if [[ ${#CHAINPAY_ADMIN_TOKEN} -lt 32 ]]; then echo "✗ CHAINPAY_ADMIN_TOKEN 不足 32 字符"; bad=1; fi
  if [[ $(printf '%s' "$CHAINPAY_SECRET_KEY" | base64 -d 2>/dev/null | wc -c | tr -d ' ') != 32 ]]; then echo "✗ CHAINPAY_SECRET_KEY 不是 Base64 的 32 字节"; bad=1; fi
  for k in CHAINPAY_CHAIN_RPC_URL CHAINPAY_DEPOSIT_XPUB CHAINPAY_PAYOUT_HOT_WALLET_KEY CHAINPAY_ALERT_WEBHOOK_URL; do
    [[ -n ${!k:-} ]] && echo "✓ $k（已设）" || echo "· $k 未设：对应模块不装配"
  done
  return $bad
}
check_config

# ---------------------------------------------------------------- ③ 密钥：镜像里 grep 不到任何一个密钥值
step "③ 镜像扫描"
tools/image-check.sh "$IMAGE"

# ---------------------------------------------------------------- ④ 迁移：新镜像单独跑一次 --migrate-only；失败就到此为止，旧版本还在跑
step "④ 迁移（新镜像，只迁移不起应用）"
MIGRATE_LOG=$(mktemp)
if CHAINPAY_IMAGE="$IMAGE" docker compose run --rm --no-deps app --migrate-only >"$MIGRATE_LOG" 2>&1; then
  grep -E "迁移完成|Successfully validated|Migrating" "$MIGRATE_LOG" | sed 's/^.*\] //' | cut -c1-140
else
  grep -E "迁移失败|ERROR|Message" "$MIGRATE_LOG" | sed 's/^.*\] //' | cut -c1-140 | head -8
  echo "✗ 迁移失败：没有切换，旧版本继续跑（完整日志 $MIGRATE_LOG）"; exit 1
fi

# ---------------------------------------------------------------- ⑤ 分发：标签就是发布记录
step "⑤ 打标签"
if docker image inspect chainpay:current >/dev/null 2>&1; then
  docker tag chainpay:current chainpay:previous
  echo "previous ← $(docker image inspect --format '{{.Id}}' chainpay:previous | cut -c8-19)"
fi
docker tag "$IMAGE" chainpay:current
echo "current  ← $IMAGE"

# ---------------------------------------------------------------- ⑥ 切换：compose 永远跑 current
step "⑥ 切换"
docker compose up -d app 2>&1 | tail -1

# ---------------------------------------------------------------- ⑦ 验证：readiness 通才算部署成功
step "⑦ 验证"
verify() {
  for i in $(seq 1 40); do
    if [[ $(docker inspect --format '{{.State.Health.Status}}' chainpay-app 2>/dev/null) == healthy ]] \
       && docker exec chainpay-app curl -fsS http://127.0.0.1:8096/actuator/health/readiness >/dev/null 2>&1; then
      echo "✓ healthy，readiness 200（$((i*3))s）"; return 0
    fi
    sleep 3
  done
  return 1
}
# ---------------------------------------------------------------- ⑧ 回滚：验证不过就退回 previous
if ! verify; then
  echo "✗ 新版本 120 秒内没有就绪，回滚"
  deploy/rollback.sh
  exit 1
fi
echo; echo "部署完成：$IMAGE 在跑（$(( $(date +%s) - START ))s）"
