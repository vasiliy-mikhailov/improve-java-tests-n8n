# improve-java-tests-n8n

One Docker container that points an **n8n pipeline** at any Java repo (Maven or Gradle) and
produces **pull requests that improve tests**: higher JaCoCo line coverage, higher **PIT**
mutation score, and higher **MAC = coverage % × mutation %** — one PR per improved file —
with a live dashboard showing exactly what the pipeline is doing right now, down to what the
model was asked and what it answered.

[PROBLEM.md](PROBLEM.md) — what we want, the ralph loop and the reward formula.
[SPEC.md](SPEC.md) — how the implementation actually works.
[RESEARCH.md](RESEARCH.md) — the Definition of Done and evaluation method.
[eval/RESULTS.md](eval/RESULTS.md) — iteration history, including the wrong turns.

## Run it anywhere (laptop, CI box, your own server)

Clone, point it at a repo, start it. Nothing outside Docker is required — no Nexus, no
reverse proxy, no host JDK.

```bash
git clone https://github.com/vasiliy-mikhailov/improve-java-tests-n8n
cd improve-java-tests-n8n
cp .env.example .env
```

Edit `.env` — six keys matter, the rest have working defaults:

| key | set it to |
|---|---|
| `REPO_URL` | the Java repo to improve (Maven or Gradle) |
| `REPO_BRANCH` | its default branch |
| `GH_TOKEN` | PAT with `repo` scope — clones, pushes branches, opens PRs |
| `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL` | any OpenAI-compatible `/v1` endpoint |
| `MAVEN_MIRROR_URL` | **empty** — the Nexus default only exists on the deployment host |
| `WEBHOOK_URL`, `N8N_EDITOR_BASE_URL` | `http://localhost:5678/` |

Then:

```bash
docker compose -f docker-compose.local.yml up -d --build
```

- n8n editor: <http://localhost:5678> — log in as `N8N_OWNER_EMAIL` (default
  `admin@ijt.local`); the password is generated on first boot:

```bash
docker exec ijtn8n cat /data/.owner_password
```

- Dashboard: <http://localhost:3000/dashboard>
- Start a run — open workflow **Improve Java Tests** and hit *Execute*, or POST the webhook:

```bash
curl -X POST http://localhost:5678/webhook/improve-run -H 'Content-Type: application/json' -d '{"scopeLimit":1}'
```

Any `.env` key can be overridden per run (camelCase in the JSON body), so `scopeLimit: 1`
is a one-file smoke test before committing to a full-repo run.

Notes that save an hour:

- **Use `docker-compose.local.yml`, not `docker-compose.yml`.** The latter is the deployment
  topology: it joins two `external: true` networks (Caddy, Nexus) and publishes no host
  ports, so it fails to start anywhere else.
- **Memory.** A PIT run forks a JVM per mutant on top of Maven/Gradle and n8n. Docker
  Desktop's default allowance is where laptop runs die first — exit code 137 means
  OOM-killed, not a broken repo.
- **First run is slow.** No warm artifact cache off the deployment host, so the initial
  dependency resolution and baseline measurement pay full price.
- **JDK 8 on arm64.** The image carries Temurin 8/11/17/21, but Adoptium does not publish
  every JDK for every architecture; the boot log prints which ones exist. A repo that
  genuinely requires the missing one will not build on that machine.

## Operating the deployment on `mh`

`docker-compose.yml` is that deployment: Caddy fronts n8n and the dashboard at
`improve-java-tests-n8n.mikhailov.tech`, and Maven/Gradle resolve through the host Nexus.

```bash
./deploy.sh            # build + up + rotate the 10-year token into Caddy
./deploy.sh --fresh    # same, but wipe /data first
```

## How it works

```
n8n workflow (native nodes ONLY: HTTP Request / Code / IF / NoOp / triggers)
   │  every OS-touching operation = HTTP call to the sidecar
   ▼
sidecar :3000  (zero-dependency Node 22)
   │    ├─ git / gh
   │    ├─ Maven or Gradle, under the JDK the project actually needs (8/11/17/21 in the image)
   │    ├─ JaCoCo  → line coverage per class AND per method
   │    ├─ PIT     → mutation score, scoped to one method per run
   │    └─ qwen 3.6 27b fp8 → writes the JUnit tests
   │  state.json + events  →  dashboard (/dashboard) polls every 2 s
   ▼
per method: pick (rules) → branch → measure → target one mutant →
            write ONE test for it → re-measure → repeat → PR (or discard)
```

Stages you'll see live on the dashboard: cloning, building, measuring baseline,
**picking a method**, **improving coverage**, **improving mutation score**,
**improving MAC (verifying)**, **preparing PR**.

### The improvement loop

The unit of work is a **method**; the target inside it is a **single mutant**.

```
pick the weakest method → PIT it → target the most killable surviving mutant
      ↑                                          ↓
      └──── repeat while it pays ←─ re-measure ←─ write ONE test for that mutant
```

The target is chosen from PIT's own data — mutator kind, `SURVIVED` before `NO_COVERAGE`,
and this run's actual kill record — not from the model's opinion about what it can kill. A
mutant is never attacked twice; one kill often takes neighbours with it, which the
re-measurement catches. A round is **kept** iff at least one of coverage / mutation / MAC
improved and none degraded, and each kept round is its own commit so a later bad round is
dropped alone. A unit ends when a round kills nothing, when every survivor has been
attempted, or when its time budget expires — then one cumulative PR per file that gained.

Full detail in [SPEC.md](SPEC.md).

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

The workflow is pure native n8n — open it in the editor to rewire stages, change a branch
condition or point a step somewhere else. It never shells out; the sidecar API surface is the
routes table in `sidecar/server.js`.

It deliberately holds no logic: there are zero Code nodes. **Prompts live in
`sidecar/prompts.js`** (served to the workflow by `/api/prompt/*`) and answer handling in
`sidecar/parse.js`, both covered by `npm test` — edit them there, where a mistake fails a test
instead of an hour-long run. Per-stage team rules stay configuration (`RULES_*` above) and need
no code change at all.

`PR_MODE=github` opens real PRs via `gh`; `PR_MODE=local` (for repos you don't own) records the
branch, patch and PR payload under `/data/prs/`.

## Evaluation

`eval/` holds the harness from RESEARCH.md: 1 synthetic Java repo + 10 real-world OSS repos
(Maven and Gradle, JUnit 4 and 5, none with PIT preconfigured).

```bash
docker exec ijtn8n node /data/eval/run-eval.mjs synth
docker exec ijtn8n node /data/eval/score.mjs
```

Iteration history: `eval/RESULTS.md`.
