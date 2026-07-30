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
# `caddy reload` is not trustworthy on its own: it reads the file, logs "adapted config to
# JSON", exits 0 — and leaves the OLD config running. Observed during the rename, when the
# live upstream stayed `ijtn8n:3000` after the container had been replaced by `ijtspring`,
# so every request 502'd while reload kept reporting success and the `||` fallback below
# never fired. The site was down and the deploy said it was fine.
#
# So the reload is VERIFIED against the running config rather than believed, and only a
# genuine mismatch escalates to a restart — which briefly interrupts every other site this
# Caddy fronts, and is not something to do on every deploy.
docker exec inference-caddy caddy reload --config /etc/caddy/Caddyfile >/dev/null 2>&1 || true
if ! docker exec inference-caddy wget -q -O- http://127.0.0.1:2019/config/apps/http/servers 2>/dev/null \
     | grep -q "ijtspring:3000"; then
  echo "  caddy reload did not take — restarting it"
  docker restart inference-caddy >/dev/null
  sleep 10
fi

# copy eval harness into the data volume (used by docker-exec eval runs)
docker cp eval ijtspring:/data/ 2>/dev/null || true

echo "smoke checks:"
printf "  caddy 401 without auth: "
curl -s -o /dev/null -w "%{http_code}\n" https://improve-java-tests-spring.mikhailov.tech/ || true
printf "  api/health:             "
docker exec ijtspring curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:3000/api/health
printf "  dashboard served:       "
docker exec ijtspring curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:3000/dashboard/
# A SMOKE CHECK MUST NOT START A RUN. This used to POST {"dryProbe":true}, on the belief that
# an obviously fake key made the call a no-op. There is no such key: the pipeline reads the
# fields it knows and ignored the rest, so the body was a valid configuration and every deploy
# launched a real run against the real repository, with the model and the token budget attached.
# It went unseen because it normally 409'd on the run already in flight — until one deploy where
# the previous run had just been marked interrupted, and it reset a seven-hour run's units and
# PR list out from under it.
#
# The route now answers 400 to a key nothing reads, so that body is refused rather than obeyed.
# This asks for the same proof — the route exists, is mapped, and is parsing bodies — and 400 is
# the PASSING answer. 202 here would mean a run just started.
printf "  run webhook refuses junk (400): "
docker exec ijtspring curl -s -o /dev/null -w "%{http_code}\n" -X POST -H 'Content-Type: application/json' -d '{"deployProbeNotAConfigKey":true}' http://127.0.0.1:3000/webhook/improve-run
echo "deploy done."
