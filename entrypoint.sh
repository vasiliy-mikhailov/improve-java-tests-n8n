#!/usr/bin/env bash
set -euo pipefail

DATA_DIR="${DATA_DIR:-/data}"
mkdir -p "$DATA_DIR" "$DATA_DIR/repos" "$DATA_DIR/prs" "$DATA_DIR/.n8n"

# keep the eval harness / batch driver in the volume in sync with the image,
# preserving run artifacts (results/, synth-origin) that only live in /data
if [[ -d /app/eval ]]; then
  mkdir -p "$DATA_DIR/eval"
  cp -f /app/eval/*.mjs /app/eval/*.json "$DATA_DIR/eval/" 2>/dev/null || true
  cp -rf /app/eval/synth-repo "$DATA_DIR/eval/" 2>/dev/null || true
fi

# ── Maven artifact mirror: install ONLY if it answers ───────────────────────
# docker/settings.xml mirrors `*` at the host's Nexus. That is a redirect, not a
# preference: with it in place Maven sends every artifact request to Nexus and FAILS the
# build when Nexus is absent — it does not fall back to Central. Shipping it live is fine
# on the deployment host and fatal anywhere else, so the decision belongs at boot.
#
# Gradle needs no equivalent here: docker/init.gradle probes reachability itself.
MIRROR_URL="${MAVEN_MIRROR_URL:-}"
mkdir -p /root/.m2
rm -f /root/.m2/settings.xml
if [[ -n "$MIRROR_URL" ]] && curl -sf -m 3 -o /dev/null "$MIRROR_URL"; then
  sed "s|__MIRROR_URL__|${MIRROR_URL}|g" /app/docker/settings.xml.template > /root/.m2/settings.xml
  echo "artifact mirror reachable, resolving through it → $MIRROR_URL"
elif [[ -n "$MIRROR_URL" ]]; then
  echo "artifact mirror unreachable ($MIRROR_URL) — resolving from public repositories"
fi

# ── report the JDKs that actually exist on this architecture ────────────────
# Dangling symlinks are expected (not every JDK is published for every arch) and are
# dropped by selection; printing them once at boot turns a later "no JDK" surprise into
# something visible on line one of the log.
for v in 8 11 17 21; do
  home="/opt/java/${v}"
  [[ -x "${home}/bin/java" ]] && echo "JDK ${v}: ${home}" || echo "JDK ${v}: not installed for $(dpkg --print-architecture)"
done

gen_secret() { head -c 32 /dev/urandom | base64 | tr -d '/+=' | cut -c1-40; }

# ── persistent secrets ──────────────────────────────────────────────────────
if [[ -z "${N8N_ENCRYPTION_KEY:-}" ]]; then
  KEY_FILE="$DATA_DIR/.encryption_key"
  [[ -f "$KEY_FILE" ]] || { gen_secret > "$KEY_FILE"; chmod 600 "$KEY_FILE"; }
  export N8N_ENCRYPTION_KEY="$(cat "$KEY_FILE")"
fi
PW_FILE="$DATA_DIR/.owner_password"
[[ -f "$PW_FILE" ]] || { echo "Ijt-$(gen_secret | cut -c1-16)-1" > "$PW_FILE"; chmod 600 "$PW_FILE"; }
OWNER_EMAIL="${N8N_OWNER_EMAIL:-admin@ijt.local}"
OWNER_PASSWORD="$(cat "$PW_FILE")"

# ── git identity ────────────────────────────────────────────────────────────
git config --global user.name  "${GIT_USER_NAME:-improve-tests-bot}"
git config --global user.email "${GIT_USER_EMAIL:-bot@improve-tests.local}"
git config --global init.defaultBranch main
git config --global --add safe.directory '*'
git config --global core.pager cat

# ── GitHub auth via credential helper (never inline tokens in remote URLs) ──
if [[ -n "${GH_TOKEN:-}" ]]; then
  gh auth setup-git 2>/dev/null \
    || git config --global credential.'https://github.com'.helper '!gh auth git-credential'
fi

# ── import workflows (by fixed id → idempotent re-import on each boot) ──────
n8n import:workflow --separate --input=/app/n8n/workflows 2>&1 | tail -2 || echo "workflow import failed"
# import deactivates workflows; re-activate so the webhook trigger registers
n8n update:workflow --id=ijtImproveJavaTests1 --active=true 2>&1 | tail -1 || true

# ── sidecar ─────────────────────────────────────────────────────────────────
node /app/sidecar/server.js &

# ── post-boot: owner setup + 10-year auth token mint ────────────────────────
(
  set +e
  for i in $(seq 1 120); do
    sleep 2
    curl -sf http://127.0.0.1:5678/healthz >/dev/null && break
  done
  # login → n8n-auth cookie (valid N8N_USER_MANAGEMENT_JWT_DURATION_HOURS = 10 years).
  # Owner setup is retried INSIDE the loop: /healthz turns green before /rest is ready
  # to serve it, and a setup request that lands too early leaves every later login
  # 401-ing with no owner ever created.
  for i in $(seq 1 20); do
    # create owner if the instance is fresh (4xx once it already exists — ignored)
    curl -sS -X POST http://127.0.0.1:5678/rest/owner/setup \
      -H 'Content-Type: application/json' \
      -d "{\"email\":\"$OWNER_EMAIL\",\"firstName\":\"IJT\",\"lastName\":\"Bot\",\"password\":\"$OWNER_PASSWORD\"}" >/dev/null 2>&1
    COOKIE=$(curl -sS -D - -o /dev/null -X POST http://127.0.0.1:5678/rest/login \
      -H 'Content-Type: application/json' \
      -d "{\"emailOrLdapLoginId\":\"$OWNER_EMAIL\",\"password\":\"$OWNER_PASSWORD\"}" \
      | grep -i '^set-cookie: n8n-auth=' | sed -E 's/^[Ss]et-[Cc]ookie: n8n-auth=([^;]+).*/\1/')
    if [[ -n "$COOKIE" ]]; then
      printf '%s' "$COOKIE" > "$DATA_DIR/n8n-auth-token.txt"
      chmod 600 "$DATA_DIR/n8n-auth-token.txt"
      echo "n8n auth token minted → $DATA_DIR/n8n-auth-token.txt"
      break
    fi
    sleep 3
  done
) &

exec n8n start
