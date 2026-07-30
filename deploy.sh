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
  docker exec ijtspring curl -sf http://127.0.0.1:3000/api/health >/dev/null 2>&1 && break
  sleep 2
done
docker exec ijtspring curl -sf http://127.0.0.1:3000/api/health >/dev/null

# No token to mint. Caddy used to inject an n8n-auth cookie on every non-/dashboard
# request because n8n owned those routes; the orchestrator serves them itself behind
# Caddy's basic_auth, so the ten-year JWT and the file it lived in are both gone.
python3 scripts/update-caddy.py
docker exec inference-caddy caddy reload --config /etc/caddy/Caddyfile >/dev/null 2>&1 \
  || docker restart inference-caddy >/dev/null

# copy eval harness into the data volume (used by docker-exec eval runs)
docker cp eval ijtspring:/data/ 2>/dev/null || true

echo "smoke checks:"
printf "  caddy 401 without auth: "
curl -s -o /dev/null -w "%{http_code}\n" https://improve-java-tests-spring.mikhailov.tech/ || true
printf "  api/health:             "
docker exec ijtspring curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:3000/api/health
printf "  dashboard served:       "
docker exec ijtspring curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:3000/dashboard/
printf "  run webhook reachable:  "
docker exec ijtspring curl -s -o /dev/null -w "%{http_code}\n" -X POST -H 'Content-Type: application/json' -d '{"dryProbe":true}' http://127.0.0.1:3000/webhook/improve-run
echo "deploy done."
