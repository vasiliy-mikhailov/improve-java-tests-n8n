# improve-java-tests-n8n

One Docker container that points an **n8n pipeline** at any Java repo (Maven or Gradle) and
produces **pull requests that improve tests**: higher JaCoCo line coverage, higher **PIT**
mutation score, and higher **MAC = coverage % × mutation %** — one PR per improved class —
with a live dashboard showing exactly what the pipeline is doing right now.

See [RESEARCH.md](RESEARCH.md) for the problem statement, Definition of Done and reward formula,
and [PROBLEM.md](PROBLEM.md) for the verbatim brief.

## Quick start (a team adapting this to their repo)

```bash
cp .env.example .env      # set REPO_URL, REPO_BRANCH, GH_TOKEN, SCOPE_GLOB, rules
docker compose up -d --build
```

- n8n editor: `https://improve-java-tests-n8n.mikhailov.tech` (Caddy basic-auth; no n8n login —
  a 10-year auth token is injected by Caddy)
- Dashboard: `https://improve-java-tests-n8n.mikhailov.tech/dashboard`
- Start a run: open workflow **Improve Java Tests** in n8n and hit *Execute*, or POST the webhook:

```bash
curl -X POST https://improve-java-tests-n8n.mikhailov.tech/webhook/improve-run -u admin:PASSWORD -H 'Content-Type: application/json' -d '{"scopeLimit":1}'
```

Any `.env` key can be overridden per run (camelCase in the JSON body).

## How it works

```
n8n workflow (native nodes ONLY: HTTP Request / Code / IF / NoOp / triggers)
   │  every OS-touching operation = HTTP call to the sidecar
   ▼
sidecar :3000  (zero-dependency Node 22)
   │    ├─ git / gh
   │    ├─ Maven or Gradle, under the JDK the project actually needs (8/11/17/21 in the image)
   │    ├─ JaCoCo  → line coverage per class
   │    ├─ PIT     → mutation score per class (scoped to one class per run)
   │    └─ qwen 3.6 27b fp8 → writes the JUnit tests
   │  state.json + events  →  dashboard (/dashboard) polls every 2 s
   ▼
per class: pick (rules) → branch → measure → write coverage tests (LLM) →
           write mutant-killing tests (LLM) → verify MAC improved → PR (or discard)
```

Stages you'll see live on the dashboard: cloning, building, measuring baseline,
**picking a class**, **improving coverage**, **improving mutation score**,
**improving MAC (verifying)**, **preparing PR**.

### The improvement loop (per class)

A picked class is improved in **rounds**. A round is **kept** only if at least one of
coverage / mutation / MAC improved and none degraded; it is **repeated** only while the last
round closed a real share of the remaining MAC gap (`MIN_ROUND_GAP_FRAC`, `MIN_ROUND_GAIN`) and
the round budget (`MAX_ROUNDS_PER_FILE`) lasts. Kept rounds are individual commits, so a later
bad round is dropped alone; the class then gets one cumulative PR if it netted improvement.

### Java specifics the pipeline handles for you

- **Build tool**: Maven or Gradle, wrapper preferred (`./mvnw`, `./gradlew`).
- **JDK**: detected from the project (the declared target is a floor, not the answer) and used
  for every command — PIT's forked minion crashes under the wrong JDK.
- **PIT wiring**: JUnit 5 needs `pitest-junit5-plugin` as a plugin *dependency* (a `-D` cannot
  add one), JUnit 6 additionally needs a matching `junit-platform-launcher`, TestNG needs
  `pitest-testng-plugin`. The sidecar injects exactly what is missing, into the main `<build>`
  (never a profile, where it would be silently ignored) — and never commits that change.
- **Scoping**: PIT runs against one class at a time; multi-module repos run in the owning module.
- **Artifacts**: Maven/Gradle resolve through the host's Nexus group repo when reachable.

### Team rules (applied at every stage)

Set free-text rules in `.env`; the LLM interprets them; mechanical guardrails enforce the
non-negotiables (suite must stay green, MAC must strictly improve, only `src/test/java` files
are ever committed):

| env var | stage | example |
|---|---|---|
| `RULES_POST_CLONE` | after cloning | `read AGENTS.md to find out how to behave` |
| `RULES_PRE_PICK` | before picking | `create a separate branch per class named tests/improve-{file}` |
| `RULES_PICK_FILE` | picking a class | `don't touch ui` |
| `RULES_WRITE_TEST` | writing tests | `don't use reflection or introspection` |
| `RULES_CHECK_CHANGES` | validating | `good only if suite green and MAC improved` |
| `RULES_MAKE_PR` | making the PR | `title starts with "test:"; body has a metrics table` |

### Adapting the workflow

The workflow is pure native n8n — open it in the editor and edit prompts (Code nodes) or rewire
stages. It never shells out; the sidecar API surface is documented in `sidecar/server.js`
(routes table).

`PR_MODE=github` opens real PRs via `gh`; `PR_MODE=local` (for repos you don't own) records the
branch, patch and PR payload under `/data/prs/`.

## Operating (this deployment)

```bash
./deploy.sh            # build + up + rotate the 10-year token into Caddy
./deploy.sh --fresh    # same, but wipe /data first
```

## Evaluation

`eval/` holds the harness from RESEARCH.md: 1 synthetic Java repo + 10 real-world OSS repos
(Maven and Gradle, JUnit 4 and 5, none with PIT preconfigured).

```bash
docker exec ijtn8n node /data/eval/run-eval.mjs synth
docker exec ijtn8n node /data/eval/score.mjs
```

Iteration history: `eval/RESULTS.md`.
