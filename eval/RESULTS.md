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
