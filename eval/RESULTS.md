# Ralph-loop iteration results

reward = DoD_score × implementation_performance (see RESEARCH.md §3)

| iter | date | DoD_score | impl_performance | reward | notes |
|------|------|-----------|------------------|--------|-------|

(appended by each iteration)

## Iteration 1 — 2026-07-25 — bring-up on the synthetic repo

First implementation of `improve-java-tests-n8n`: research artifacts (PROBLEM.md verbatim brief,
RESEARCH.md with DoD D1–D13 and the reward formula), the Docker deliverable (n8n 2.32 + zero-dep
Node sidecar + dashboard; Temurin 8/11/17/21, Maven 3.9.9, Gradle 8.10.2, gh; Nexus-mirrored
artifact resolution), the 64-node native-only workflow, and the Java execution layer
(Maven/Gradle + JDK + JUnit4/5/6/TestNG detection, JaCoCo coverage, PIT mutation testing scoped
per class, module-aware).

No score yet — the eval sweep over the 10 real repos has not run. What this iteration bought was
a working synthetic-repo loop and three defects that only running could expose:

**B1 — apostrophe in a prompt fragment (fatal, loud).** `MUTATOR_PLAYBOOK` contains "the returned
object's contents"; the generator interpolated it into a single-quoted JS string inside a Code
node, so `Mut: Build Prompt` threw `SyntaxError` and killed run 1 ten minutes in. Fragments are
now injected with `JSON.stringify`, and the generator refuses to emit a workflow whose Code nodes
do not parse (`new Function(jsCode)` on all 8).

**B2 — generated test classes were invisible to the build (silent).** `PricingMacTestCov.java`
does not match Surefire's default includes (`Test*`, `*Test`, `*Tests`, `*TestCase`), so `mvn
test` compiled it and never ran it: the suite stayed "green", JaCoCo reported nothing, and
coverage read 0 % — while PIT, which selects tests by its own glob, ran the very same file and
reported 100 % killed. MAC was computed as 0 × 100 = 0 on a well-tested class. Generated names
are now `<Class>MacCov[Rn]Test` / `<Class>MacMut[Rn]Test`, computed in the sidecar.

**B3 — no compile before PIT, so every baseline was a false 0 (silent, metric-corrupting).**
`mvn <plugin>:<goal>` runs no lifecycle phase, and `createBranch` wipes `target/` via
`git clean -fd`; baseline PIT therefore found no classes and reported "no mutations exercised" =
score 0 for every file. Every class looked like it started at `macBefore = 0`, inflating
gap-closed and erasing the synth repo's entire point. PIT runs are now preceded by an explicit
`test-compile` (module-scoped with `-am`), and PIT no longer passes `-DskipTests`.

**Guard added.** B2 and B3 both announced themselves the same way: two independent measurements
of one class contradicting each other. When PIT reports no tests for a class JaCoCo says is
covered, the mutation score is now recorded as **UNMEASURED**, never as 0.

### Synth results after the fixes (run 4)

| class | coverage | mutation | MAC | rounds | outcome |
|---|---|---|---|---|---|
| `core/Pricing` (no tests at all) | 0 → 100 | 0 → 100 | **0 → 100** | 1 kept, round 2 STALE | PR prepared |
| `core/Validator` (covered, unasserted) | 100 → … | **8.51** → … | 8.51 → … | in progress | — |
| `ui/ConsoleRenderer` | — | — | — | — | never picked (pick_file rule) |

`Validator`'s 8.51 % baseline (47 mutants, 4 killed incidentally by the deliberately weak
`ValidatorTest`) is the number that proves the measurement is honest — it read 0 before B3.

Rules were obeyed end to end: UI excluded from picking, branch per class
(`tests/improve-{file}`), PR titled `test: strengthen tests for Pricing` with the before/after
metrics table AGENTS.md demands.

### Next

1. Finish the synth run (Validator's mutation rounds — the real exercise of the playbook prompt).
2. Run the 10-repo sweep (Maven ×8, Gradle ×2; JUnit 4 and 5) and compute the first reward.
3. Expected weak spots: Gradle path (PIT via init script) is the least exercised; multi-module
   `-pl` scoping is only covered by json-path and the two Gradle repos.

## Iteration 2 — 2026-07-25 — the first real-repo sweep: 0 PRs across 10 repos

The sweep found no improvements anywhere. Every cause was a defect, and none of them was
visible from the synthetic repo — which is exactly what a 10-repo eval set is for.

**B5 — the JDK was chosen from the declared bytecode target.** Apache Commons declares
`maven.compiler.source=1.8` while its plugin stack needs 11/17, so half the sweep built
under JDK 8 and died. The declared target is a FLOOR. `install()` now walks a preference
list (modern first, never below the declared target) and keeps the first JDK that
actually compiles — discovery by building, the only reliable method.

**B6 — JaCoCo detection was blinded by `-q`.** `help:effective-pom` prints at INFO level,
so `-q` suppressed the POM entirely and the grep found nothing: every Apache project was
treated as "no JaCoCo" and got a SECOND agent on the test JVM. Two agents abort the JVM
before a single test runs (`LinkageError: duplicate class definition for java.lang.$JaCoCo`).

**B7 — class coverage read a method's counter.** `<class>` nests a `<method>` per method,
each with its own LINE counter, and the class total comes last. Taking the first match
meant a class whose first method was uncovered reported 0 %.

**B8 — a self-closing `<class/>` swallowed the classes after it.** JaCoCo emits
`<class …/>` for synthetic classes with no methods (`Foo$1`); a paired `<class>…</class>`
regex reads it as an opening tag and runs on into the next class, stealing its counters.
Parsing now splits on class boundaries. On the real commons-cli report this moved
`MissingOptionException` from 0 % to 100 % and took the classes carrying coverage from a
subset to all 32.

**B9 — the picker spent every run on classes that cannot be improved.** json-java got an
annotation, commons-cli a constants holder (`Char.java`: PIT reports **0 mutants**),
json-path an interface — because a class with no executable code reports 0 % coverage and
therefore sorts FIRST as "weakest". With `scopeLimit: 1` that is the whole run. Now:
classes absent from the JaCoCo report are `executableLines: 0` and excluded; among equally
weak candidates the one with the MOST executable code wins; and a baseline PIT under
`MIN_MUTANTS_PER_CLASS` settles the class as `no_mutants` without consuming the file quota,
with a `Has Mutants?` gate picking another.

Eval-set errors, not code: `concurrency-limits` has no `master` branch (it is `main`), and
`moshi` is a Kotlin project whose `src/main/java` holds nothing to improve — replaced with
`graphql-java/java-dataloader` (Gradle, JUnit 5, pure Java).

### Method-scoped mutation (user direction: "PIT is very slow, better to mutate a method")

PIT costs (mutants × suite time) and re-mutating a whole class every round dominates
wall-clock. PIT has no target-method option, but it has `excludedMethods`, so mutating one
method means excluding the others — the method list coming from the class's own baseline
report (bytecode truth, constructors as `<init>`). The baseline stays class-wide and builds
a queue of methods ordered by surviving mutants; each round takes the richest remaining one;
the class score is projected between rounds and measured for real once at the end.

Validated on synth (`Pricing`, 32 mutants, 5 methods):

| round | method | mutants run | projected class |
|---|---|---|---|
| 1 | `discountPercent()` | 12 | 37.50 % |
| 2 | `shipping()` | 8 | 62.50 % |
| 3 | `net()` | 7 | 81.25 % |
| 4 | `net()` | 7 | 84.38 % |
| 5 | `round2()` | 3 | 93.75 % |
| — | **final class-wide measurement** | 32 | **100 %** |

The projection under-counted by 6.25 points and the final measurement corrected it
**upward** — the intended safety property: a new test can kill mutants outside the targeted
method but never revive them, so the projection is conservative and the committed number is
measured, never inferred. Mutation work for the class: 37 method-scoped mutants + 2
class-wide runs, against 192 mutant-runs before. `PIT_SCOPE=class` restores the old behaviour.

## Iteration 3 — 2026-07-26 — the unit becomes the mutant, and four measurement bugs

First scored reward: **0.4292** (DoD 0.9231 × implementation_performance 0.465). Seven of
eleven repos scored exactly 0.4 — completion 1, improvement 0 — so the whole headroom was
in repos that finish without improving anything.

### Structural change: file → method → mutant

Driven by the user: PIT is slow, so mutate a method, not a class; then, so the candidate
list should be methods; then, pick the most promising mutant with the LLM and repeat PIT on
the method. The pipeline now works like that end to end:

```
pick a method (units are <path>::<method>, enumerated free from JaCoCo's per-method counters)
  └─ PIT on the method  → survivors
     └─ the MODEL picks the most promising survivor from 20 and says why
        └─ writes ONE short test for that mutant (2500 tokens)
           └─ suite → PIT on the method → new score → repeat
```

MAC is now method coverage × method mutation score, both measured on the same method — the
projection and its end-of-rounds reconciliation are gone. Prompts carry the target method,
its class header and sibling signatures instead of the whole class (12k characters of
XML.java to write a 400-byte test).

This required fixing the round-stop rule, which would otherwise have defeated it: killing
one mutant of N moves MAC by coverage/N — 0.29 points on XML#parse — under the 0.5 floor.
**A floor above the measurement's own granularity is a bug**, so both floors are clamped to
what one mutant is worth.

### Four bugs where the pipeline reported numbers it had not measured

- **B10 — skips consumed the iteration budget.** A mutant-less class was exempt from the
  file quota but not from `maxIterations`, so two duds ended a run before any improvable
  unit was attempted. Iterations are unlimited now; scope and time bound a run, and a unit
  we could not measure no longer consumes the quota either (bounded by `MAX_FAILURES`).
- **B11 — PIT_VERSION was pinned to 1.15.2 in the server .env**, overriding the 1.25.8
  default I had "deployed". Changing a default is not deploying it.
- **B12 — JaCoCo method names arrived HTML-escaped** (`&lt;init&gt;`), so constructors got
  that literal string as their name, in unit keys and in PIT's excludedMethods where it can
  never match.
- **B13 — a stale mutations.xml was read as a real result.** pit.js never deleted the
  previous report and `findReport()` falls back to "newest anywhere", so a failed run
  parsed the PREVIOUS unit's report, filtered it by the current class, found nothing and
  recorded "0 mutants". Five substantial methods (JSONObject#quote has 25 mutants,
  #convertValue 38) were written off as having no mutation surface.

B13 also produced a false result I reported as a win: PIT "history" appearing to cut a
measurement from 43s to 3s. That run had failed — PIT 1.25 moved history behind the
commercial arcmutate plugin — and the byte-identical numbers came from the stale report.
A failed run reading a stale report is indistinguishable from a fast successful one.

### The recurring lesson

Every serious bug this iteration was the pipeline stating a number it had not measured:
coverage 0 % beside mutation 100 %, a baseline of 0 for a tested class, "no mutants" for a
25-mutant method, an instant re-measurement that never ran. Guards added accordingly: a
missing report is a failure not a zero, PIT "no tests" against non-zero coverage is recorded
as UNMEASURED, and the workflow generator now EXECUTES every Code node at build time (a
half-applied edit left an identifier undefined — it parsed fine and threw at runtime).

---

## Iteration 4 — extract the node code, then let e2e find the bugs

Two instructions shaped this iteration: *"you don't need graph simulator — you should put
pieces of code from n8n nodes to files and test them with tdd"*, and the loop that follows
from it: **run e2e, observe what fails, root-cause it, write the unit test, watch it red,
fix, watch it green, run e2e again.** Straight-through runs are the e2e; the code a node
calls is what unit tests cover.

The workflow now has **zero Code nodes** — 66 nodes, all HTTP into the sidecar, each path
checked at build time against a route `server.js` actually defines. Prompt building
(`prompts.js`) and answer handling (`parse.js`) moved into files. The extraction alone
surfaced two live defects: `Parse Tests` read `$('Mut: Build Prompt')` in **both** phases,
so the coverage phase reached for a node that never ran; and the deployed mutation prompt
still said *"CHOOSE the single MOST PROMISING mutant"* — contradicting the comment directly
above it — because an earlier `str.replace` had silently matched nothing.

Unit tests: **41 → 190**.

### What e2e found, in order

| symptom | cause |
|---|---|
| run died at its first step | `REPO_BRANCH=main` handed straight to git; JSON-java's default is `master` |
| a round spent on a mutant at line 3005 of a unit whose method ends at 2090 | `excludedMethods` is best-effort and nothing filtered the report. `isRecordStyleAccessor`'s "15.79 %, 3 kills" were `toString()`'s; scoped properly the method measures **0 %** |
| 4 of 12 completions truncated and re-run at ~100 s each | fixed thinking reserve, and nothing remembered the truncation |
| the same `ConditionalsBoundary` offered after misses at 164, 191, 234 | demotion required "never killed", so one early kill immunised the kind |
| a private method: passing test, `cov 0→0`, 14 mutants alive, four rounds | the model was told "public API only, never reflection" with no way to satisfy both |
| that fix shipped and changed nothing | analysis read the 24 000-char copy clipped **for the prompt**; and brace counting drifted to −4 on `'{'`/`"}"` inside literals |
| `JSONArray#getNumber` 0 % covered in one run, 100 % in the next | `jacoco.exec` is APPENDED to and `target/` is gitignored, so `git clean -fd` left 10 MB of accumulated executions — the 100 % came from a generated test that had already been deleted |

### The audit

A 45-agent adversarial audit (five lenses: measurement, rounds, graph, model-io, state;
every finding attacked by a skeptic told to default to refuted) raised 40 candidates, of
which **29 survived**. Sixteen are fixed. The sharpest was created by this iteration's own
scoping fix: PIT XML-escapes method names, so a constructor arrives as `&lt;init&gt;` while
JaCoCo gives `<init>` — **every constructor unit measured "0 mutants, no mutation surface"**
and was settled for good. Before scoping, another method's mutants had stood in for them.

Also confirmed and fixed: `mutatedClass.startsWith(fqcn)` credited `a.BFoo`'s kills to
`a.B`; `/api/round/miss` echoed the previous round's `continueRounds`, so a verify that
stopped measuring looped the run for ever; the per-**file** branch was reset by the second
**method** of that file, dropping already-PR'd commits and then force-pushing over the PR;
`prBase` stayed frozen at the unresolved branch so `gh pr create` failed after the work was
committed; `MAX_ROUNDS_PER_FILE=0`, documented uncapped, was `|| 5`; and only one existing
test file was guarded, so any other real test could be overwritten and then deleted.

### Measured

`JSONArray#getNumber`: coverage 0 → 100, mutation 0 → 66.67, **MAC 0 → 66.67**, one
targeted mutant killed taking a second with it. Reproduced across two runs.

Whole-repo JSON-java: 26 classes → **307 method units**. PR-stage evidence is still
outstanding, so D5/D12 keep their earlier scores; D9 has the Gradle wiring under test but
no Gradle repo run yet.

### The recurring lesson, again

Every defect above is the same one: **a number stated that nothing measured** — a score
computed over another method's mutants, a baseline replayed from semantics that had
changed, coverage produced by a deleted test, a kill announced by a round that wrote
nothing, machine hours charged three times. The guard list in SPEC §4 is now long enough
that it is the specification's centre of gravity, which is the honest description of this
project.
