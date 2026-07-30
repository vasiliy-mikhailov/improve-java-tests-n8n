#!/usr/bin/env bash
set -euo pipefail

DATA_DIR="${DATA_DIR:-/data}"
mkdir -p "$DATA_DIR" "$DATA_DIR/repos" "$DATA_DIR/prs" 

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


# ── backend ─────────────────────────────────────────────────────────────────
# Java 25, explicitly — NOT `java` from PATH, which is JAVA_HOME and points at 17 because
# that is the default for building target projects. The backend needs its own JDK and the
# two must not be confused.
#
# The orchestrator, and nothing else.
#
# The three-way BACKEND switch is gone with n8n. `java` ran tech.mikhailov.ijt.Server
# standalone and was only useful because n8n drove it over HTTP; `node` was the original
# sidecar behind the same workflow. With no workflow there is nothing to drive either, so
# rollback now means redeploying an earlier image rather than flipping a variable.
#
# $BACKEND_JAVA_HOME explicitly, NOT `java` from PATH: PATH resolves to JAVA_HOME, which is
# 17 because that is the default for building TARGET projects. Two different jobs.
#
# `exec`, so the JVM is PID 1 and receives SIGTERM directly — `docker stop` then reaches the
# orchestrator instead of a shell that ignores it and waits out the 10-second kill timer.
# A JVM does not reap children it did not spawn, and PIT/Surefire orphans get reparented to
# PID 1 on a process-group kill, so compose sets `init: true` to supply a reaper.
echo "backend: spring orchestrator ($("$BACKEND_JAVA_HOME/bin/java" -version 2>&1 | head -1))"
exec "$BACKEND_JAVA_HOME/bin/java" -jar /app/ijt-orchestrator.jar


