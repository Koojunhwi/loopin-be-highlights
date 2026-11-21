#!/usr/bin/env bash
set -euo pipefail

# -----------------------------
# Config
# -----------------------------
APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-docker/compose.prod.yml}"
DEPLOY_MODE="${DEPLOY_MODE:-app}"

cd "$APP_DIR"

# docker compose / docker-compose 자동 감지
if command -v docker-compose >/dev/null 2>&1; then
  DC="docker-compose"
else
  DC="docker compose"
fi

ROLLBACK_TAG_FILE="${APP_DIR}/.previous_image_tag"

echo "[deploy] dir=$APP_DIR"
echo "[deploy] compose=$COMPOSE_FILE"
echo "[deploy] dc='$DC'"
echo "[deploy] mode=$DEPLOY_MODE"

# -----------------------------
# Pre-checks
# -----------------------------
if ! command -v docker >/dev/null 2>&1; then
  echo "[deploy] ERROR: docker not found" >&2
  exit 1
fi

if [[ ! -f "$COMPOSE_FILE" ]]; then
  echo "[deploy] ERROR: $COMPOSE_FILE not found in $APP_DIR" >&2
  exit 1
fi

# FCM 서비스 계정 파일 체크 (app 모드에서만)
if [[ "$DEPLOY_MODE" == "app" ]]; then
  if [[ ! -s "firebase-adminsdk.json" ]]; then
    echo "[deploy] ERROR: firebase-adminsdk.json is missing or empty" >&2
    echo "[deploy] Hint: FIREBASE_ADMIN_SDK_B64 주입/출력 부분 확인 필요" >&2
    exit 1
  fi

  # jq가 있으면 JSON 유효성 검사까지
  if command -v jq >/dev/null 2>&1; then
    if ! jq -e . < firebase-adminsdk.json >/dev/null 2>&1; then
      echo "[deploy] ERROR: firebase-adminsdk.json is not valid JSON" >&2
      exit 1
    fi
  fi
fi

# -----------------------------
# 롤백용 현재 이미지 태그 저장
# -----------------------------
if [[ "$DEPLOY_MODE" == "app" ]]; then
  CURRENT_TAG=$($DC -f "$COMPOSE_FILE" images app --format '{{.Tag}}' 2>/dev/null || true)
  if [[ -n "$CURRENT_TAG" ]]; then
    echo "[deploy] saving current tag for rollback: $CURRENT_TAG"
    echo "$CURRENT_TAG" > "$ROLLBACK_TAG_FILE"
  fi
fi

# -----------------------------
# Deploy
# -----------------------------
echo "[deploy] pulling images..."
$DC -f "$COMPOSE_FILE" pull

echo "[deploy] starting/updating containers..."
# --remove-orphans: compose 파일에서 사라진 컨테이너 정리
$DC -f "$COMPOSE_FILE" up -d --remove-orphans
# app은 항상 재생성하여 파일/환경변수 변경사항 반영 보장 (app 모드에서만 — monitoring/data compose에는 app 서비스가 없다)
if [[ "$DEPLOY_MODE" == "app" ]]; then
  $DC -f "$COMPOSE_FILE" up -d --force-recreate app
fi

# monitoring 모드: prometheus.yml·rules·alertmanager.yml은 바인드 마운트라 파일만 바뀌면 컨테이너가 재생성되지
# 않는다 → SIGHUP으로 설정 리로드 (두 서비스 모두 SIGHUP 리로드를 지원하며, 설정 오류 시 기존 설정을 유지한다)
if [[ "$DEPLOY_MODE" == "monitoring" ]]; then
  echo "[deploy] reloading prometheus/alertmanager config (SIGHUP)..."
  for c in loopin-prometheus loopin-alertmanager; do
    if docker kill -s HUP "$c" >/dev/null 2>&1; then
      echo "[deploy] reloaded $c"
    else
      echo "[deploy] WARN: could not send SIGHUP to $c" >&2
    fi
  done
fi

echo "[deploy] current status:"
$DC -f "$COMPOSE_FILE" ps

# app 컨테이너 Health Check (app 모드에서만)
if [[ "$DEPLOY_MODE" == "app" ]]; then
  APP_CID="$($DC -f "$COMPOSE_FILE" ps -q app || true)"

  echo "[deploy] waiting for health check..."
  HEALTHY=false
  for i in $(seq 1 30); do
    if docker exec loopin-app curl -sf http://localhost:8080/actuator/health > /dev/null 2>&1; then
      echo "[deploy] app is healthy"
      HEALTHY=true
      break
    fi
    sleep 2
  done

  if [[ "$HEALTHY" != "true" ]]; then
    echo "[deploy] ERROR: app failed to start within 60s" >&2
    if [[ -n "${APP_CID:-}" ]]; then
      echo "[deploy] app logs:"
      docker logs --tail 50 "$APP_CID" || true
    fi

    # 자동 롤백 시도
    if [[ -f "$ROLLBACK_TAG_FILE" ]]; then
      PREV_TAG=$(cat "$ROLLBACK_TAG_FILE")
      echo "[deploy] ROLLING BACK to previous tag: $PREV_TAG"
      export APP_IMAGE_TAG="$PREV_TAG"
      $DC -f "$COMPOSE_FILE" pull
      $DC -f "$COMPOSE_FILE" up -d --remove-orphans

      ROLLBACK_HEALTHY=false
      for j in $(seq 1 30); do
        if docker exec loopin-app curl -sf http://localhost:8080/actuator/health > /dev/null 2>&1; then
          echo "[deploy] rollback successful, app is healthy on tag: $PREV_TAG"
          ROLLBACK_HEALTHY=true
          break
        fi
        sleep 2
      done

      if [[ "$ROLLBACK_HEALTHY" != "true" ]]; then
        echo "[deploy] CRITICAL: rollback to $PREV_TAG also failed!" >&2
      fi
    else
      echo "[deploy] no previous tag found, skipping rollback" >&2
    fi

    exit 1
  fi
fi

# 배포 성공: 현재 태그를 롤백 대상으로 확정 저장
if [[ "$DEPLOY_MODE" == "app" ]]; then
  echo "${APP_IMAGE_TAG:-latest}" > "$ROLLBACK_TAG_FILE"
  echo "[deploy] updated rollback tag to: ${APP_IMAGE_TAG:-latest}"
fi

# 찌꺼기 이미지 정리(실패해도 배포 성공에는 영향 없게)
echo "[deploy] pruning dangling images..."
docker image prune -f >/dev/null 2>&1 || true

# 사용 중이지 않은 오래된 loopin-app 이미지 정리
echo "[deploy] cleaning up old loopin-app images..."
RUNNING_IMAGES=$(docker ps --format '{{.Image}}' | sort -u)
docker images "ghcr.io/ita-loopin/loopin-app" --format '{{.Repository}}:{{.Tag}}' \
  | while read -r img; do
      if ! echo "$RUNNING_IMAGES" | grep -qF "$img"; then
        echo "[deploy] removing unused image: $img"
        docker rmi "$img" 2>/dev/null || true
      fi
    done

echo "[deploy] done"
