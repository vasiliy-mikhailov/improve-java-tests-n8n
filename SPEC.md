# Implementation spec

How `improve-java-tests-n8n` actually works, as built. See PROBLEM.md for what it is for
and how it is scored; eval/RESULTS.md for why several of these decisions look the way they
do (most were forced by a defect found while running).

## 1. Shape

```
                     Caddy (basic auth, injects a 10-year n8n JWT)
                                     │
   ┌─────────────────────────────────┴──────────────────────────────────┐
   │  one container (ijtn8n)                                            │
   │                                                                    │
   │   n8n — 65 nodes, native only ──HTTP──▶ sidecar :3000 (zero-dep)   │
   │     Trigger / HTTP Request / Code / IF / NoOp     ├─ git, gh        │
   │     no shell, no Python, no fs in Code nodes      ├─ Maven | Gradle │
   │                                                   ├─ JDK 8/11/17/21 │
   │   dashboard /dashboard ◀── /api/metrics (2 s)     ├─ JaCoCo         │
   │                        ◀── /api/llm/log  (5 s)    ├─ PIT            │
   │                                                   └─ qwen 3.6 27b   │
   └────────────────────────────────────────────────────────────────────┘
```

The workflow owns orchestration and rules; the sidecar owns everything that touches the OS.
That split is what keeps the workflow editable in the n8n UI while the Java toolchain stays
real. The generator (`n8n/generate-workflows.mjs`) refuses to emit a workflow that contains
a non-native node, a Code node referencing `child_process`/`fs`, or a Code node that fails
to **parse or execute** against stubbed n8n globals.

## 2. The unit of work

A unit is **one method**, keyed `<source path>::<method name>`.

JaCoCo already reports per-method counters, so enumerating every method with its own line
coverage costs nothing beyond the baseline run. Units are dropped when they have no
executable lines (annotations, marker interfaces), when the method is `<clinit>`, or when it
has fewer than `MIN_UNIT_LINES` executable lines.

`f.path` remains the source file, because git, the diff and the PR care about files: the
**branch and PR are per file**, while the work underneath is per method.

## 3. Run flow

### Bootstrap

| step | what happens |
|---|---|
| `/api/run/start` | config = env, overridden per-run by the webhook body |
| clone | `git clone/fetch`; credentials never inlined into the remote URL |
| `post_clone` rules | LLM reads AGENTS.md / CONTRIBUTING.md, extracts constraints |
| build & detect | Maven vs Gradle (wrapper preferred), **JDK discovered by building**, test framework, whether the project brings its own JaCoCo |
| baseline coverage | JaCoCo → repo total, per-class and per-method counters → **units** |
| `pre_pick` / `write_test` rules | branch template; test-writing constraints |

**JDK discovery** walks a preference list (modern first, never below the declared target)
and keeps the first JDK that actually compiles. The declared bytecode target is a floor, not
an answer — Apache Commons declares `source=1.8` and needs 11/17 to build.

**JaCoCo** is supplied only if the project has none (checked via the *effective* POM): two
agents on one JVM abort it before a single test runs.

### Per unit

1. **Pick** — candidates are units, weakest MAC first, most executable lines as tie-break.
   `pick_file` rules apply (LLM, with a mechanical lowest-MAC fallback); an answer naming a
   bare file is resolved to that file's weakest method rather than discarded.
2. **Branch** — per source file, from the `pre_pick` template.
3. **Baseline PIT**, scoped to the method via `excludedMethods` (every sibling method).
   Preceded by `test-compile`, because `mvn <plugin>:<goal>` runs no lifecycle phase and
   branch creation wipes `target/`. Stale `mutations.xml` files are deleted first: a missing
   report must read as a failure, never as a zero. Under `MIN_MUTANTS_PER_CLASS` mutants the
   unit is settled as `no_mutants` — free, consuming neither the file quota nor an iteration.
4. **Coverage phase** — only when the method is entirely unexecuted
   (`COV_PHASE_MAX_PCT`, default 0). Above it the mutation phase raises coverage by itself,
   since killing a `NO_COVERAGE` mutant means executing its line. When it runs it asks for
   the *simplest* test that reaches the uncovered lines, capped at 2 500 tokens.
5. **Mutation phase** — the pipeline picks the target, the model writes the test:
   - survivors are ranked by `killDifficulty()` (mutator kind; `SURVIVED` before
     `NO_COVERAGE`) and re-ranked by **this run's kill record** — a mutator kind attacked
     twice with no kill is demoted behind everything else;
   - mutants already attempted on this unit are never offered again;
   - the model receives the target method (not the whole class), the class header, sibling
     signatures, the mutator→assertion playbook, and **one** mutant; it returns one short
     test, or an empty list to say the mutation changes nothing observable.
6. **Green gate** — run the suite; on RED, one repair attempt; still RED, the generated
   tests are deleted.
7. **Verify** — suite, JaCoCo, PIT on the method. Reports whether the *targeted* mutant died
   and how many others went with it. `MAC = method coverage × method mutation score`, both
   measured on the same method — no projection.
8. **Round verdict** (`sidecar/rounds.js`) — keep the round iff ≥1 of coverage / mutation /
   MAC improved and none degraded; continue iff it kept, mutants remain, the unit's time
   budget holds, and MAC < 100.
9. **Finish** — drop uncommitted work, `check_changes` rules (mechanically: suite green
   **and** MAC strictly improved), the verified cleanup pass, `make_pr` rules, PR.

### Ending a run

`scopeLimit` units settled, no candidates left, or `MAX_FAILURES` unmeasurable units — that
last one ends the run saying the toolchain is at fault, not the units. Iterations are
uncapped: skips and failures cost neither quota nor iteration, so a run cannot be ended by
duds before reaching real work.

## 4. Measurement rules

The recurring failure in this project has been the pipeline **stating a number it never
measured**. The guards that exist because of it:

| guard | the failure it prevents |
|---|---|
| stale `mutations.xml` deleted before every PIT run | a failed run parsed the previous unit's report and recorded "0 mutants" for a 25-mutant method |
| a missing report is a failure, never a zero | same |
| PIT "no tests" + JaCoCo coverage > 0 → **UNMEASURED**, not 0 | a tested class reported as 0 % mutation |
| class LINE counter read from the **last** counter in the element | `<class>` nests per-method counters; the first is a method's |
| JaCoCo parsed by splitting on class boundaries | a self-closing `<class/>` swallowed the classes after it |
| method names XML-unescaped | constructors arrived as the literal `&lt;init&gt;` |
| test glob `*Class*Test*` | tests in a sibling package (`org.json.junit`) matched nothing, so PIT ran none and everything "survived" |
| Code nodes executed at build time | a half-applied edit left an identifier undefined: it parsed, then threw mid-run |
| round floors clamped to one mutant's worth | a floor above the measurement's granularity stops the loop on its first success |

## 5. Configuration

Thresholds are deliberately permissive: what ends work is a measurement, not a guess.

| variable | default | meaning |
|---|---|---|
| `SCOPE_LIMIT` | 1 | units settled before the run stops (0 = whole repo) |
| `MAX_ITERATIONS` | 0 | uncapped; skips and failures are free |
| `MAX_ROUNDS_PER_FILE` | 0 | uncapped; rounds end on no kill / exhausted mutants / time |
| `MUTANTS_PER_ROUND` | 1 | one mutant, one test, re-measure |
| `MUTANT_CHOICES` | 20 | survivors retained per measurement for ranking |
| `MIN_MUTANTS_PER_CLASS` | 1 | below this a unit has no mutation surface |
| `MIN_UNIT_LINES` | 1 | executable lines a method needs to be a unit |
| `MIN_ROUND_GAP_FRAC` / `MIN_ROUND_GAIN` | 0 / 0 | any real gain buys another round |
| `UNIT_BUDGET_SEC` | 3600 | wall-clock ceiling per unit |
| `MAX_ATTEMPTS_PER_FILE` | 3 | pick attempts before a unit settles |
| `MAX_FAILURES` | 10 | unmeasurable units before the run gives up on the repo |
| `COV_PHASE_MAX_PCT` | 0 | coverage phase only for wholly unexecuted methods |
| `PIT_VERSION` / `PIT_JUNIT5_VERSION` | 1.25.8 / 1.2.3 | PIT must be current for modern JUnit |
| `PIT_SCOPE` | method | `class` restores whole-class mutation |
| `PIT_HISTORY` | false | incremental history needs the commercial arcmutate plugin |

What is **not** relaxed, because it protects correctness rather than gating improvement: the
suite must be green, MAC must strictly improve for a change to be kept, only
`src/test/java` files are ever committed (never the injected PIT wiring), and the cleanup
pass reverts itself if the mutation score drops.

## 6. Java-specific wiring

- **PIT + JUnit 5** needs `pitest-junit5-plugin` as a plugin *dependency*, which a `-D`
  cannot add — injected into the main `<build>`, never a `<profile>`, and never committed.
  `junit-platform-launcher` is pinned to the platform matching the project's engine.
- **Gradle** gets an init script; `gradle-pitest-plugin` follows the project's Gradle
  version (1.15.0 reads `reporting.baseDir`, which Gradle 9 removed).
- **Nexus** on the host mirrors Maven and Gradle resolution; the Gradle init script must set
  `allowInsecureProtocol` for the plain-http mirror.
- **Multi-module**: every command runs in the module owning the class (`-pl`).

## 7. Observability

- **Stages**: cloning, building, measuring baseline, picking, improving coverage, improving
  mutation, improving MAC (verifying), preparing PR.
- **Live status line** fed every 3 s — by `exec.js` for child processes and by `llm.js`
  while a completion is in flight, so it never goes blank during the phase the pipeline
  spends most of its time in.
- **Activity stream** interleaves events with the **model exchanges** (system / prompt /
  response, duration, finish reason), collapsed to one line and expandable in place.
- **Accounting**: per-unit and cumulative human-equivalent hours, machine time, ETA, FTE,
  and LLM tokens in/out with tokens per improved unit.

## 8. Deployment

`./deploy.sh` builds the image, recreates the container, waits for health, mints the
10-year n8n JWT and rotates it into the Caddy block (preserving its basic-auth line).
`--fresh` wipes the volume. The eval harness ships in the image and is refreshed into
`/data/eval` on boot.
