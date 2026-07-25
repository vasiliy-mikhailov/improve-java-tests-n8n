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
