# Research: improve-java-tests-n8n — an adaptable n8n pipeline that raises test quality of any Java repo

> Scope note: this document holds the **Definition of Done** and the **evaluation method**.
> The problem statement, improvement loop and reward formula live in
> [PROBLEM.md](PROBLEM.md); the implementation as built is described in [SPEC.md](SPEC.md).

## 1. Problem

Teams own Java repositories (Maven or Gradle) whose test suites are green but weakly verifying.
Line coverage overstates quality: a line the tests *execute* but never *assert on* is unverified —
PIT proves it by mutating the code and watching the suite pass anyway. The composite metric

> **MAC (Mutation-Adjusted Coverage) = line-coverage % (JaCoCo) × mutation-score % (PIT)**

captures both dimensions in one number. Teams need a **turn-key, adaptable pipeline** they can point
at their repo and get back **pull requests that measurably improve tests**, with **live visibility**
into what the pipeline is doing and **team-specific rules** applied at every stage.

Concretely, a team must be able to:

1. **Specify a Java repo** (URL + branch) via configuration only — no code changes.
2. **Run one Docker deliverable** (image + compose) containing the whole pipeline: n8n orchestrator,
   execution sidecar (JDKs + Maven/Gradle + PIT + JaCoCo + git + gh), dashboard.
3. **Get improved tests per file**: improved line coverage, improved PIT mutation score, and
   improved MAC. A picked file goes through **repeated improvement rounds**: a round is kept only if
   at least one of the three improves and none degrades; rounds stop when **all three stale or one
   or more degrades** (that round's changes are dropped; kept rounds accumulate as commits), bounded
   by `MAX_ROUNDS_PER_FILE`, and also on diminishing returns.
4. **Get a PR per improved file** — only where an improvement actually happened.
5. **See what is happening right now**: picking a file, improving coverage, improving mutation
   score, improving MAC (verification), or preparing a PR.
6. **Apply team rules at every stage**:
   - `post_clone` — after downloading the repo (e.g. read AGENTS.md / CONTRIBUTING.md);
   - `pre_pick` — before picking a file (e.g. make a separate branch, naming scheme);
   - `pick_file` — how to pick (e.g. "don't touch UI", "skip generated sources");
   - `write_test` — constraints on generated tests (e.g. "don't use reflection/introspection");
   - `check_changes` — how to decide whether changes are good;
   - `make_pr` — how the PR must look (style, labels, title conventions).
7. **Adapt the workflow** in the n8n editor. The workflow therefore uses **only n8n-native blocks**
   — no Execute Command, no shell, no Python in nodes. Everything touching the OS (git, mvn/gradle,
   PIT, JaCoCo, GitHub, LLM) sits behind a plain HTTP API (the sidecar) that native **HTTP Request**
   nodes call.

### What makes Java different from the JS/TS equivalent

These are the constraints the implementation must absorb (sources: `improve-java-tests-skill`,
`java-test-evolution-lab` on the same host):

- **PIT is scoped per class.** Whole-repo mutation is far too slow; every run targets one class
  (`-DtargetClasses`) with its test scope (`-DtargetTests`). This aligns with per-file improvement.
- **PIT refuses to run when in-scope tests are red.** A green baseline must be established first,
  and already-red tests (needing DB/network/Docker) must be scoped out rather than fixed.
- **The JDK matters.** A project's real build floor can exceed its declared bytecode target, and
  PIT's forked coverage minion crashes under the wrong JDK. The pipeline must detect the needed JDK
  and run every command under it.
- **The test framework decides PIT's wiring.** JUnit 4 works out of the box; JUnit 5/6 need
  `pitest-junit5-plugin` (and, for JUnit 6, a `junit-platform-launcher` pinned to the project's
  platform version) injected into the **main** `<build>`, never a profile; TestNG needs
  `pitest-testng-plugin`. Wrong wiring ⇒ PIT finds 0 tests or crashes.
- **Artifact resolution must be cached.** Builds resolve hundreds of artifacts; the host runs a
  Nexus group repo on the `mvn-cache` network, so Maven/Gradle are mirrored through it.
- **Mutants come in kinds.** `NO_COVERAGE` survivors (line never executed) are the cheapest wins;
  `SURVIVED` ones need a sharper assertion. Mutator → assertion mapping (boundary, negate
  conditionals, math, return values, void-call removal) drives test generation.

### Deployment constraints (this instance)

- Host: `mikhailov.tech` (ssh alias `mh`), folder `~/improve-java-tests-n8n`.
- LLM: `https://inference.mikhailov.tech/qwen-3.6-27b-fp8/v1` (OpenAI-compatible vLLM).
- n8n UI: `https://improve-java-tests-n8n.mikhailov.tech`,
  dashboard: `https://improve-java-tests-n8n.mikhailov.tech/dashboard`.
- Access protected by **Caddy** (basic auth). **No n8n login screen**: a 10-year n8n auth JWT
  (`N8N_USER_MANAGEMENT_JWT_DURATION_HOURS=87600`) is minted once and injected by Caddy via
  `header_up Cookie`.

## 2. Definition of Done (DoD)

Each item scores 0 (absent), 0.5 (partial), or 1 (fully met). `DoD_score` = mean of D1–D14.

| # | Item | Verification |
|---|------|--------------|
| **D1** | **One-command Docker deliverable**: `docker compose up -d` brings up n8n + sidecar + dashboard in one container; state survives restarts via a volume. | Fresh `compose up` on the server; `/api/health` OK; n8n UI reachable; restart keeps state. |
| **D2** | **Repo is configuration**: team sets `REPO_URL`/`REPO_BRANCH` (+ scope globs) in `.env`; no source edits needed to target a different repo. | Point at ≥2 different Java repos with an env change only. |
| **D3** | **Metrics measured per file and overall**: JaCoCo line coverage %, PIT mutation score %, MAC = coverage × mutation. Baseline and after values stored and visible. | Dashboard/API values match raw `jacoco.xml` / `mutations.xml` for the same class. |
| **D4** | **Tests actually improve**: generated JUnit tests raise coverage and/or mutation score so per-file MAC strictly increases; suite stays green. Multi-round criterion applied (keep iff ≥1 up and none down; stop on stale/degrade/diminishing returns). | Eval runs show ΔMAC > 0 on improved files; suite green after changes; round verdicts in the event log. |
| **D5** | **PR per improved file, only on improvement**: separate branch + commit + PR per file whose MAC improved; no PR otherwise. Real `gh` PR on owned repos; prepared-PR artifact (branch, title, body, diff) in local mode for third-party repos. | PR exists per improved file; none for non-improved files. |
| **D6** | **Live stage visibility**: dashboard and API expose the current stage — `picking_file`, `improving_coverage`, `improving_mutation`, `improving_mac`, `preparing_pr` (+ bootstrap stages) — updating in ≤5 s, including progress lines of long steps (PIT runs take minutes). | Watch the dashboard during a run; stages track the n8n execution. |
| **D7** | **Per-stage rules**: free-text rules for all six stages are configurable via env and demonstrably applied at their stage (LLM-interpreted, with mechanical guardrails). | Set a distinctive rule per stage and observe it obeyed in run artifacts. |
| **D8** | **n8n-native workflow only**: workflow contains only native nodes (Trigger, HTTP Request, Code with pure JS data transforms, IF, SplitInBatches, Set/NoOp, Wait). All OS work behind the sidecar HTTP API. | Static scan of workflow JSON: node-type whitelist; Code nodes free of `child_process`/`fs`. |
| **D9** | **Adaptable across Java builds**: auto-detects Maven vs Gradle (wrapper preferred), JDK required to build, JUnit 4/5/6 vs TestNG, and injects the correct PIT + JaCoCo wiring when the project has none. | Eval covers both build tools, ≥2 JDK levels, JUnit 4 and 5, and repos without PIT preconfigured. |
| **D10** | **Protected access, no n8n login**: Caddy basic auth in front of `improve-java-tests-n8n.mikhailov.tech`; 10-year n8n JWT injected by Caddy; dashboard at `/dashboard`; n8n never shows its login screen. | Open both URLs: only Caddy asks for credentials, then the n8n editor / dashboard loads. |
| **D11** | **No reasoning leakage in committed artifacts**: LLM chain-of-thought never appears in PR'd test files. Model thinking stays in the thinking channel; a cleanup pass strips residual scratch commentary; at most one short intent comment per test. | Grep PR'd tests for scratch-comment patterns; inspect samples across repos. |
| **D12** | **No dead-weight tests**: every committed test earns its place — it kills ≥1 mutant or covers previously uncovered code; vacuous tests are pruned by a **verified** cleanup pass (suite stays green and mutation score does not drop, else the cleanup is reverted). | Review PR diffs; cleanup events show prune/revert decisions backed by re-measurement. |
| **D14** | **LLM token accounting**: input and output tokens are measured for every model call (including retries and repair passes), attributed to the unit being improved, and shown per unit and cumulatively — with tokens per improved unit, so the cost of an improvement is visible next to its value. | Dashboard shows in/out tokens per unit and for the run; totals match the `usage` reported by the LLM endpoint. |
| **D13** | **Human-equivalent timesheets**: for every improved file, an itemized estimate of the developer time the delivered work would have taken (analysis, test writing, mutation analysis, verification), shown per file and cumulatively on the dashboard, together with machine time, ETA to repo completion and the human-FTE equivalent. | Dashboard shows per-file and cumulative hours, machine time, ETA, FTE; numbers reproducible from the recorded inputs. |

## 3. Reward formula

```
reward = DoD_score × implementation_performance            ∈ [0, 1]

DoD_score = (Σ Di) / 14                                    Di ∈ {0, 0.5, 1}

implementation_performance = mean over eval repos of per_repo_score
  eval set = 1 synthetic Java repo + 10 real-world OSS Java repos (11 total)

per_repo_score = 0.4 × completion + 0.6 × improvement
  completion  ∈ {0, 0.5, 1}:
      1   — pipeline ran unattended to a terminal state; on improvement a PR
            (real or prepared, per repo mode) was produced for each improved file
      0.5 — pipeline produced measurements and attempts but failed before
            finishing the PR stage, or needed manual intervention
      0   — pipeline failed before producing a baseline measurement
  improvement = clamp( ΔMAC_gap_closed, 0, 1 )
      ΔMAC_gap_closed = (MAC_after − MAC_before) / (100 − MAC_before)
      computed over the union of files the run targeted; if MAC_before = 100
      (nothing to improve) the repo is excluded from the mean.
```

The 0.4/0.6 split rewards *finishing* the loop but weights *actual test-quality gain* higher.
Gap-closed normalisation makes +5 points on a 90 %-MAC class worth as much as +50 on a 0 %-MAC one —
otherwise the optimal strategy is to only ever pick untested classes.

## 4. Evaluation methodology

- **Synthetic repo** (`eval/synth-repo`): a small Maven + JUnit 5 project with engineered defects —
  a branch-heavy class with no tests, a class whose tests execute everything but assert almost
  nothing (mutants survive), a UI-ish class that `pick_file` rules must skip, and an AGENTS.md
  carrying the rules. Known ground truth → checks D4/D5/D7 sharply.
- **10 real-world Java repos**: small/medium OSS libraries, a mix of Maven and Gradle, JUnit 4 and
  5, with and without PIT preconfigured, selected empirically (cloneable, buildable offline through
  the Nexus mirror, suite green in ≤ ~10 min). Run in **local PR mode** (we don't own them): branch
  + commit + prepared-PR artifact instead of pushing.
- Each eval run uses a fixed `SCOPE_LIMIT` and `MAX_ROUNDS_PER_FILE` so runs are comparable and
  token-bounded. `eval/score.mjs` computes per-repo scores and the reward.
- Results per ralph-loop iteration are appended to `eval/RESULTS.md`.

## 5. Improvement protocol (ralph loop)

```
loop:
  deploy current implementation
  run eval (synth first, then real repos)
  compute reward; append to eval/RESULTS.md
  pick the lowest-scoring DoD item or worst-performing repo class
  fix it; repeat
until reward plateaus (2 consecutive iterations with no gain)
```

## 6. Architecture (one container, three processes' worth of duties)

```
        Caddy (basic auth, injects 10-year n8n JWT)
                 │
   ┌─────────────┴─────────────────────────────┐
   │  improve-java-tests-n8n container         │
   │                                           │
   │   n8n (native nodes only) ──HTTP──▶ sidecar (:3000, zero-dep Node)
   │                                        ├─ git / gh
   │                                        ├─ JDK 8/11/17/21 + Maven/Gradle (Nexus-mirrored)
   │                                        ├─ JaCoCo (line coverage per class)
   │                                        ├─ PIT (mutation score per class)
   │                                        ├─ qwen 3.6 27b fp8 (test generation)
   │                                        └─ dashboard (/dashboard)
   └───────────────────────────────────────────┘
```

The workflow owns *orchestration and rules*; the sidecar owns *everything that touches the OS*. That
split is what keeps the workflow adaptable in the n8n editor while the Java toolchain stays real.
