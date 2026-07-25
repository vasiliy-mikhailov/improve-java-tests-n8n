# improve-javascript-tests

One Docker container that points an **n8n pipeline** at any JavaScript/TypeScript repo and
produces **pull requests that improve tests**: higher line coverage, higher Stryker mutation
score, and higher **MAC = coverage% × mutation%** — one PR per improved file — with a live
dashboard showing exactly what the pipeline is doing right now.

See [RESEARCH.md](RESEARCH.md) for the problem statement, Definition of Done, and reward formula.

## Quick start (a team adapting this to their repo)

```bash
cp .env.example .env      # set REPO_URL, REPO_BRANCH, GH_TOKEN, SCOPE_GLOB, rules
docker compose up -d --build
```

- n8n editor: `https://improve-javascript-tests.mikhailov.tech` (Caddy basic-auth; no n8n login —
  a 10-year auth token is injected by Caddy)
- Dashboard: `https://improve-javascript-tests.mikhailov.tech/dashboard`
- Start a run: open workflow **Improve JS Tests** in n8n and hit *Execute*, or POST the webhook:

```bash
curl -X POST https://improve-javascript-tests.mikhailov.tech/webhook/improve-run \
  -u admin:<basic-auth-password> -H 'Content-Type: application/json' \
  -d '{"scopeLimit": 1}'   # any .env key can be overridden per run (camelCase)
```

## How it works

```
n8n workflow (native nodes ONLY: HTTP Request / Code / IF / NoOp / triggers)
   │  every OS-touching operation = HTTP call to the sidecar
   ▼
sidecar :3000  (zero-dependency Node 22)  ── git / npm / vitest / jest / stryker / gh / LLM
   │  state.json + events  →  dashboard (/dashboard) polls every 2 s
   ▼
per file: pick (rules) → branch → measure → write coverage tests (LLM) →
          write mutant-killing tests (LLM) → verify MAC improved → PR (or discard)
```

Stages you'll see live on the dashboard: cloning, installing, measuring baseline,
**picking a file**, **improving coverage**, **improving mutation score**, **improving MAC
(verifying)**, **preparing PR**.

### Team rules (applied at every stage)

Set free-text rules in `.env`; the LLM interprets them; mechanical guardrails enforce the
non-negotiables (suite must stay green, MAC must strictly improve, generated files can only be
tests):

| env var | stage | example |
|---|---|---|
| `RULES_POST_CLONE` | after cloning | `read AGENTS.md to find out how to behave` |
| `RULES_PRE_PICK` | before picking | `create a separate branch per file named tests/improve-{file}` |
| `RULES_PICK_FILE` | picking a file | `don't touch ui` |
| `RULES_WRITE_TEST` | writing tests | `don't use introspection` |
| `RULES_CHECK_CHANGES` | validating | `good only if suite green and MAC improved` |
| `RULES_MAKE_PR` | making the PR | `title starts with "test:"; body has a metrics table` |

### Adapting the workflow

The workflow is pure native n8n — open it in the editor and edit prompts (Code nodes) or
rewire stages. It never shells out; the sidecar API surface is documented in
`sidecar/server.js` (routes table).

`PR_MODE=github` opens real PRs via `gh`; `PR_MODE=local` (for repos you don't own) records the
branch, patch and PR payload under `/data/prs/`.

## Operating (this deployment)

```bash
./deploy.sh            # build + up + rotate the 10-year token into Caddy
./deploy.sh --fresh    # same, but wipe /data first
```

## Evaluation

`eval/` holds the harness from RESEARCH.md: 1 synthetic repo + 10 real-world OSS repos.

```bash
docker exec ijst-n8n node /data/eval/run-eval.mjs synth     # or a repo name from eval/repos.json
docker exec ijst-n8n node /data/eval/score.mjs              # implementation_performance + reward
```

Iteration history: `eval/RESULTS.md`.
