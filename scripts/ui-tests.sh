#!/usr/bin/env bash
# Run the Playwright dashboard suite inside the container.
#
#     ./scripts/ui-tests.sh              build the test image if needed, run DashboardUiTest
#     ./scripts/ui-tests.sh --rebuild    rebuild the test image first
#     ./scripts/ui-tests.sh -Dtest=DashboardUiTest#theApplyCheckboxSitsBesideItsLabelNotAboveIt
#
# The suite is gated behind -Dui.tests=true and OFF in the normal `mvn test`, because Playwright
# needs a browser that `mvn test` on a laptop should not have to download. This script is the
# other half of that gate: the place where the browser exists.
#
# No volume is mounted. The container gets its own empty /data, so a run of this can never write
# into the state a real pipeline run owns.
set -euo pipefail
cd "$(dirname "$0")/.."

BASE=improve-java-tests-spring:latest
IMAGE=improve-java-tests-spring:ui

rebuild=0
if [[ "${1:-}" == "--rebuild" ]]; then rebuild=1; shift; fi

if ! docker image inspect "$BASE" >/dev/null 2>&1; then
  echo "base image $BASE not built — running docker compose build first"
  docker compose build
fi

if [[ $rebuild == 1 ]] || ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "building $IMAGE (Chromium + system libs on top of $BASE)"
  docker build -f docker/Dockerfile.ui --build-arg "BASE=$BASE" -t "$IMAGE" .
fi

# THE WORKING TREE, not the copy baked into the image.
#
# The base image COPYs dashboard/ and the source trees in at build time. Run the suite without
# mounting and it exercises whatever was committed when that image was built — which is green,
# and green about the wrong code. That happened on the first run here: nine tests passed against
# a dashboard an hour older than the change under test.
#
# DASHBOARD_DIR is set explicitly so the application resolves it through the same first branch
# of `${ijt.dashboard-dir:${DASHBOARD_DIR:/app/dashboard}}` that a deployment would if it set
# one. The production DEFAULT — the literal /app/dashboard — is pinned by DashboardConfigTest,
# which is where a constant belongs; a browser is not needed to check a path.
#
# -am because the mounted backend is newer than the jar in the image's ~/.m2, and the
# orchestrator must compile against the source under test rather than against this morning.
# -Dsurefire.failIfNoSpecifiedTests=false because -am puts the backend in the reactor, where
# -Dtest=DashboardUiTest matches nothing and Surefire 3.x fails the module for it.
ARGS=${*:-}
exec docker run --rm --init \
  -v "$PWD:/src" -w /src \
  -e DASHBOARD_DIR=/src/dashboard \
  "$IMAGE" \
  "JAVA_HOME=\$BACKEND_JAVA_HOME mvn -B -ntp -pl orchestrator -am -Dui.tests=true \
     -Dsurefire.failIfNoSpecifiedTests=false ${ARGS:--Dtest=DashboardUiTest} test"
