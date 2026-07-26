#!/usr/bin/env bash
# Push sources to the server. --delete keeps the remote a mirror, so every path that
# exists ONLY there must be excluded: .env holds the secrets (GH_TOKEN, LLM_API_KEY)
# and is not in git — a bare --delete wipes it and the next deploy fails with
# "env file not found". That happened once; hence this script.
set -euo pipefail
rsync -az --delete \
  --exclude .git --exclude node_modules --exclude .env --exclude repo \
  "$(dirname "$0")/" mh:~/improve-java-tests-n8n/
