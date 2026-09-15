#!/usr/bin/env bash
# 回滚（M6-④）：把 previous 换回 current 再 up。数据库不动（取舍 9：迁移只前进，上一版代码兼容新 schema）。
# 被换下的镜像打成 chainpay:failed，留着查。用法：deploy/rollback.sh
set -euo pipefail
cd "$(dirname "$0")/.."
docker image inspect chainpay:previous >/dev/null 2>&1 || { echo "✗ 没有 chainpay:previous：从没部署过第二版，无处可退"; exit 1; }
docker tag chainpay:current chainpay:failed
docker tag chainpay:previous chainpay:current
echo "current ← previous（被换下的在 chainpay:failed）"
docker compose up -d app 2>&1 | tail -1
for i in $(seq 1 40); do
  if [[ $(docker inspect --format '{{.State.Health.Status}}' chainpay-app 2>/dev/null) == healthy ]] \
     && docker exec chainpay-app curl -fsS http://127.0.0.1:8096/actuator/health/readiness >/dev/null 2>&1; then
    echo "✓ 回滚完成，readiness 200（$((i*3))s）"; exit 0
  fi
  sleep 3
done
echo "✗ 回滚后 120 秒仍未就绪：上一版也起不来，人来"; exit 1
