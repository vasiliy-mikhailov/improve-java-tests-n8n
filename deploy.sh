#!/usr/bin/env bash
# Server-side deploy: build image, (re)start container, sync Caddy auth token.
# Usage: ./deploy.sh [--fresh]   (--fresh wipes the /data volume)
set -euo pipefail
cd "$(dirname "$0")"

if [[ "${1:-}" == "--fresh" ]]; then
  docker compose down -v --remove-orphans || true
fi

docker compose build
docker compose up -d --force-recreate

echo "waiting for sidecar health..."
for i in $(seq 1 60); do
  docker exec ijtn8n curl -sf http://127.0.0.1:3000/api/health >/dev/null 2>&1 && break
  sleep 2
done
docker exec ijtn8n curl -sf http://127.0.0.1:3000/api/health >/dev/null

echo "waiting for n8n + auth token mint..."
TOK=""
for i in $(seq 1 120); do
  TOK=$(docker exec ijtn8n cat /data/n8n-auth-token.txt 2>/dev/null || true)
  [[ -n "$TOK" ]] && break
  sleep 2
done
[[ -n "$TOK" ]] || { echo "ERROR: no auth token minted"; exit 1; }

python3 scripts/update-caddy.py "$TOK"
docker exec inference-caddy caddy reload --config /etc/caddy/Caddyfile >/dev/null 2>&1 \
  || docker restart inference-caddy >/dev/null

# copy eval harness into the data volume (used by docker-exec eval runs)
docker cp eval ijtn8n:/data/ 2>/dev/null || true

echo "smoke checks:"
printf "  caddy 401 without auth: "
curl -s -o /dev/null -w "%{http_code}\n" https://improve-java-tests-n8n.mikhailov.tech/ || true
printf "  token accepted by n8n:  "
docker exec ijtn8n curl -s -o /dev/null -w "%{http_code}\n" -H "Cookie: n8n-auth=$TOK" http://127.0.0.1:5678/rest/active-workflows
printf "  dashboard served:       "
docker exec ijtn8n curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:3000/dashboard/
echo "deploy done."
