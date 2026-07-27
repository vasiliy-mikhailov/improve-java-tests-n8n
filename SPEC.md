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
   │   n8n — 66 nodes, native only ──HTTP──▶ sidecar :3000 (zero-dep)   │
   │     Trigger / HTTP Request / IF / NoOp            ├─ git, gh        │
   │     zero Code nodes: no logic outside tested files├─ Maven | Gradle │
   │                                                   ├─ JDK 8/11/17/21 │
   │   dashboard /dashboard ◀── /api/metrics (2 s)     ├─ JaCoCo         │
   │                        ◀── /api/llm/log  (5 s)    ├─ PIT            │
   │                                                   └─ qwen 3.6 27b   │
   └────────────────────────────────────────────────────────────────────┘
```

The workflow owns orchestration and rules; the sidecar owns everything that touches the OS.
That split is what keeps the workflow editable in the n8n UI while the Java toolchain stays
real. The generator (`n8n/generate-workflows.mjs`) refuses to emit a workflow containing a
non-native node, or an HTTP node pointing at a route `sidecar/server.js` does not define —
a path typo used to surface as a 404 an hour into a run.

Logic that once lived as JavaScript strings inside Code nodes (prompt building, answer
parsing) now lives in `sidecar/prompts.js` and `sidecar/parse.js` behind unit tests. A
string in a JSON file has no compiler and no test: one shipped an identifier that was never
defined, and an edit that silently matched nothing left the deployed prompt asking the model
to choose a mutant days after that decision had moved into the pipeline.

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
| JaCoCo exec data and reports deleted before every coverage run | `jacoco.exec` is APPENDED to and `target/` is gitignored, so `git clean -fd` leaves it: on JSON-java it reached 10 MB and `JSONArray#getNumber` measured 0 % covered in one run and 100 % in the next — the 100 % produced by a generated test that had already been deleted |
| stale `mutations.xml` deleted before every PIT run | a failed run parsed the previous unit's report and recorded "0 mutants" for a 25-mutant method |
| a missing report is a failure, never a zero | same |
| PIT "no tests" + JaCoCo coverage > 0 → **UNMEASURED**, not 0 | a tested class reported as 0 % mutation |
| class LINE counter read from the **last** counter in the element | `<class>` nests per-method counters; the first is a method's |
| JaCoCo parsed by splitting on class boundaries | a self-closing `<class/>` swallowed the classes after it |
| method names XML-unescaped | constructors arrived as the literal `&lt;init&gt;` |
| test glob `*Class*Test*` | tests in a sibling package (`org.json.junit`) matched nothing, so PIT ran none and everything "survived" |
| round floors clamped to one mutant's worth | a floor above the measurement's granularity stops the loop on its first success |
| PIT reports scoped to the unit's own method before anything reads them | `excludedMethods` let 5 of `toString()`'s mutants into `isRecordStyleAccessor`'s report: its score was computed over them (15.79 % with 3 kills that were all `toString`'s — really 0 %), and a SURVIVED stray outranked every real candidate, so a whole round wrote a `toString` test and was then scored against a method it never touched |
| stray mutants reported, not dropped quietly | a silent filter is indistinguishable from a scope that worked |
| a method with no mutants of its own scores 0, not 100 | nothing was proven about it |
| measurements stamped with the semantics that produced them | the ledger seeds each run's baseline and still held per-**file** numbers from before the unit became `path::method`, and per-method scores from before scoping — replayed, they give a run a baseline it can only "improve" by luck |
| one canonical unit key (XML entities unescaped) | the ledger held both `Property.java::&lt;init&gt;` and `Property.java::<init>`, so a measurement written under one was invisible under the other |
| the branch resolved against `git ls-remote` | `REPO_BRANCH=main` against a repo whose default is `master` ended the run at its first step |
| a non-public target's route named, an uncallable one skipped | the model was handed a private method plus "call only the public API, never use reflection" — an unsatisfiable pair — and wrote a passing test that executed none of it |
| mutator kinds ranked on observed kill **rate** | "tried twice, never killed" let one lucky kill immunise a kind: `ConditionalsBoundary` stayed top of the queue through misses at lines 164, 191 and 234 |
| units no public path reaches are dropped from the queue | a private method with no route in costs a pick, a PIT run, a model call and three misses — ~25 min to learn what the call graph already said |
| among equally weak units, a directly callable one first | `isRecordStyleAccessor` (5 hops of private calls) outranked three callable methods on weakness alone |
| the team rule **excludes**; the ranking **chooses** | asked to choose, the model took the fifth-ranked unit over three callable ones on the strength of "high potential" — the same judgement that lost 0-4 to PIT's data on mutant selection |
| PIT method names XML-decoded (`&lt;init&gt;`, `&#60;init&#62;`) | JaCoCo gives `<init>`, PIT escapes it — so every constructor unit scoped to zero and was written off as having no mutation surface |
| the mutated class matched exactly, or as `Fqcn$Inner` | `startsWith` counted `a.BFoo` as part of `a.B`, and with the method matched by name alone, `a.BFoo#run`'s kills were credited to `a.B#run` |
| "could not measure" is its own outcome, recorded nowhere | `totalMutants ?? 0` turned a failed PIT run into a confident "0 mutants", settled the unit and wrote the invented zero into both ledgers |
| a missed round decides for itself whether another follows | `/api/round/miss` echoed the PREVIOUS round's `continueRounds`; when verify stopped measuring, that stale `true` looped the run for ever |
| the round-change guard counts TEST files only | `changedFiles()` includes the injected `pom.xml`, so "did this round change anything" was true in every round |
| a target mutant is stamped with its round | a round whose mutation phase skipped inherited the last round's target, found it already dead, and announced a KILL it had not made |
| branches this run owns are continued, not reset | the branch is per FILE and the unit is a METHOD, so `checkout -B` dropped the first method's already-PR'd commits and the force-push rewrote the PR |
| `prBase` follows the branch actually resolved | frozen at the unresolved `REPO_BRANCH`, `gh pr create --base main` failed after the work was measured and committed |
| a round writes exactly one file, at the planned path | only one existing test was guarded, so the model could name any other real test file and have it overwritten — then deleted when the suite went red |
| setup overhead charged once per run | keyed on `iteration === 0`, and a no-mutation-surface skip REFUNDS its iteration, so each skip re-charged the whole elapsed time |
| units with no mutation surface excluded from the repo mean | averaging their 0 % made the repo look weaker on the strength of methods that cannot be mutated |
| reachability RANKS a unit, it never removes one | as a filter it deleted real work: tests share the class's package, so package-private and protected methods are directly callable, and the regex behind it misses multi-line signatures, inner-class callers, `this::foo` and overloads |
| replayed improvement records restore status, not metrics | the metrics carry no version stamp, so numbers rejected as stale by the measurement ledger were admitted through the improvement ledger into the headline |
| a unit that failed to MEASURE is left open | settling it blacklists improvable work on the strength of a measurement that measured nothing |
| a survivor count of 0 means zero, not "unknown" | `|| null` made "no surviving mutants left — stop" unreachable, so rounds kept being ordered with nothing to target |
| the timesheet diffs from where THIS unit started | the branch is per file and the unit is per method, so a sibling's committed tests were counted as this unit's effort |
| a round is told whether its last test REACHED the method | "never executed it" and "executed it but did not distinguish the mutation" need opposite fixes and were reported identically |
| the suite verdict comes from the coverage run that executed it | verify ran 1163 tests, then ran them again under JaCoCo to learn the same thing; `passed: null` (log unclear) still forces a real run |
| generated test files named per METHOD, not per class | `JSONArray#optEnum` wrote rounds 1-4, then `#putAll` began at round 1 and overwrote its round-1 file: a verified kill vanished from the prepared PR while the PR still reported the MAC measured with it |
| one attempt key per MUTANT, including PIT's `<index>` | a line can carry several mutations of one kind. `JSONObject#parseJSONObject` had 3 survivors, all `RemoveConditionalMutator_EQUAL_ELSE` at line 248: attacking one struck off all three, and the unit settled at 78.57 % announcing "no un-attempted survivors left" with two survivors never tried. 37 survivors across 18 units were masked this way in one run. Keys written before the index was recorded keep their old broad meaning rather than silently matching nothing |
| `select.eligible` is the only implementation of that key | `survivorsLeft` hand-rolled a second copy without the index, so the round's stop decision used the colliding spelling even where the queue did not |
| a round's verdict requires the round's test to still be ON DISK when PIT runs | a test that breaks the suite is repaired once and then DELETED, so verification measured a repo with no new test, found the score unchanged and announced "STILL ALIVE — the new test does not distinguish it" about a file that was not there — 41 times across 19 units in one run. The mutant had been marked attempted when the test was *written*, so it was struck off for good; `mutatorStats` booked a try with no kill, and two of those demote a whole mutator kind run-wide; and three such rounds ended the unit. `JSONWriter#value` was abandoned at 20 % that way, then re-picked and taken to 40 % by the first round whose test compiled |

| per-stage token ceilings learned from `finish_reason` | 4 of 12 completions were truncated mid-JSON and re-run whole at ~100 s each, and nothing remembered it |

### How this is kept true

Two kinds of test, and they answer different questions:

- **unit tests** (`sidecar/test/unit/`, `npm test` — 267 of them) cover the code the workflow's nodes call.
  Every case is a defect that actually shipped; the table above is, row by row, an
  assertion in that suite.
- **e2e** is a straight-through run against a real repo — hundreds of methods, real Maven,
  real PIT, real model. It is what *finds* the defects: observe the failure, root-cause it,
  write the unit test, watch it go red, fix it, watch it go green, run e2e again.

The workflow itself holds **no logic to test**: it has zero Code nodes, and its 44 HTTP
nodes are checked at build time against the routes `sidecar/server.js` actually defines.

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
