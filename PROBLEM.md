# Problem

## What we want

Teams own Java repositories whose test suites are green but weakly verifying. Line coverage
overstates quality: a line the tests *execute* but never *assert on* is unverified, and PIT
proves it by mutating the code and watching the suite pass anyway. One number captures both
dimensions:

> **MAC (Mutation-Adjusted Coverage) = line coverage % (JaCoCo) × mutation score % (PIT)**

A team must be able to point one Docker deliverable at their repo and get back **pull
requests that measurably raise MAC**, with **live visibility** into what the pipeline is
doing and **their own rules** applied at every stage.

Concretely:

1. **Specify a Java repo** (URL + branch) by configuration only — no code changes.
2. **Run one Docker deliverable**: orchestrator + execution + dashboard in one container.
3. **Get improved tests**, with improved coverage, improved PIT mutation score, and
   improved MAC — measured, not asserted.
4. **Get a PR per improved file**, and none where nothing improved.
5. **See what is happening right now**: picking, improving coverage, improving mutation,
   verifying, preparing a PR — including what the model was asked and what it answered.
6. **Apply team rules at every stage**: `post_clone`, `pre_pick`, `pick_file`,
   `write_test`, `check_changes`, `make_pr`.
7. **Adapt the workflow** in the n8n editor, so it uses **only native n8n blocks** — no
   shell, no Python. Everything touching the OS lives behind the sidecar's HTTP API.

### Requirement 7 was superseded, deliberately

Requirements 1–6 and 8+ are met as written. Requirement 7 is **not**, and this section is
here so that is a recorded decision rather than a quiet omission.

The brief above is quoted verbatim further down, and it asked for n8n. What shipped is a
Spring Boot orchestrator: Spring Batch for the run loop, Spring WebSocket for the dashboard,
H2 on disk for the store, and the backend as a Java library in the same JVM. n8n and Node are
gone from the image entirely.

The instruction to migrate came from the same people who wrote the brief, on the grounds that
the deliverable had to look like an enterprise Java service rather than a workflow tool. The
engineering case, from having run both:

- **A string in a JSON file has no compiler and no test.** That was already the reason the
  prompt and parsing logic had been pulled out of Code nodes into tested files. A deployed
  prompt once spent days asking the model to choose a mutant after that decision had moved
  into the pipeline, because an edit silently matched nothing.
- The workflow had **zero Code nodes** by design, so "adapt it in the editor" had come to
  mean re-wiring HTTP calls — which a typed `Phase` expresses better and a compiler checks.

**What was genuinely lost**: a non-programmer can no longer re-arrange the pipeline in a
visual editor. Adapting it now means editing Java and rebuilding. Teams that wanted point 7
for that reason should weigh this before adopting.

Additional requirements gathered while building:

8. **No LLM reasoning leakage** into committed tests, and **no dead-weight tests** — every
   committed test kills a mutant or covers new code, enforced by a verified cleanup pass.
9. **Human-equivalent timesheets**: for each improved unit, an itemized estimate of the
   developer time the work would have taken, shown per unit and cumulatively, with machine
   time, ETA and the human-FTE equivalent.
10. **LLM token accounting**: input and output tokens per unit and per run.

## The improvement loop (what the pipeline does)

The unit of work is a **method**; the targets within it are its **surviving mutants**.

```
pick the weakest method  →  PIT it  →  take every surviving line still un-attempted
      ↑                                            ↓
      └──── repeat while it pays ←── re-measure ←── one test per line, asked for together
```

One round per method rather than one per mutant. The per-mutant loop paid two PIT runs for
every mutant — 609 runs across 22 classes on JSON-java, 43 % of wall-clock — to kill them one
at a time, when one kill routinely takes neighbours with it anyway. Each generated test
carries a deterministic name derived from its target, so the next round greps the file, sees
what is already written and asks only for the rest; that marker is what makes a unit stop on
its own instead of re-requesting lines it has already covered.

A mutant is never attacked twice. A unit ends when a round kills nothing, when every survivor
has been attempted, or when its time budget expires — then it gets a PR if it netted a gain.
A round whose test never reached the measurement is not counted against it: it broke the
build and was deleted, which says nothing about whether the method can be improved.

## The ralph loop (how the pipeline gets better)

```
loop:
  deploy the current implementation
  run the eval (1 synthetic repo + 10 real-world repos)
  compute the reward; append the iteration to eval/RESULTS.md
  pick the lowest-scoring DoD item or the worst-performing repo class
  fix it
until the reward plateaus (two consecutive iterations with no gain)
```

Every iteration's findings — including wrong turns and retracted claims — are recorded in
`eval/RESULTS.md`, because the failures are what the next iteration is steered by.

## Reward

```
reward = DoD_score × implementation_performance            ∈ [0, 1]

DoD_score = (Σ Di) / 14                                    Di ∈ {0, 0.5, 1}
            scored on OBSERVED evidence only — an item stays at 0.5 until it has been
            seen working on real repos, not just on the synthetic one

implementation_performance = mean over eval repos of per_repo_score
  eval set = 1 synthetic Java repo + 10 real-world OSS Java repos

per_repo_score = 0.4 × completion + 0.6 × improvement
  completion  ∈ {0, 0.5, 1}
      1   — ran unattended to a terminal state and produced a PR per improved unit
      0.5 — measured and attempted, but failed before the PR stage
      0   — failed before producing a baseline measurement
  improvement = clamp( ΔMAC_gap_closed, 0, 1 )
      ΔMAC_gap_closed = (MAC_after − MAC_before) / (100 − MAC_before)
      over the union of units the run targeted; a repo already at MAC 100 is excluded
```

The 0.4/0.6 split rewards finishing the loop but weights actual test-quality gain higher.
Gap-closed normalisation makes +5 points on a 90 %-MAC method worth as much as +50 on a
0 %-MAC one — otherwise the optimal strategy is to only ever pick untested code.

See **SPEC.md** for how the implementation works, **RESEARCH.md** for the DoD table, and
**eval/RESULTS.md** for the iteration history.

---

## Appendix: the brief, verbatim

> The problem: We are building improve-java-tests-spring n8n pipeline in docker for teams to
> adapt on their repos. Team need to: specify java repo, run docker with n8n pipeline
> against it and get improved tests for each file, meaning improved coverage, improved
> mutation score (with PIT), improved MAC (coverage * mutation score) and to get pr's with
> improvements for each file where improvements happened. Also teams need to see what's
> happening in n8n right now - is it picking a file to mutate or improving coverage or
> improving mutation score or improving mac or preparing pr. Also there can be rules that
> should be applied to every stage of process - you will implement - e.g. after downloading
> repo, how to act before picking a file (e.g. make separate branch), how to pick file (e.g.
> don't touch ui), on write test (e.g. don't use introspection), on how to check if changes
> are good, how to make pr (e.g. pr style is ...). Teams will use this docker file and n8n
> workflow adapting it to their particular project, so use only n8n native blocks - no
> shell, no python etc.
>
> Please turn this problem into research, meaning: problem + DoD + reward formula for
> implementation - DoD * implementation_performance. Where DoD is structured list you
> extracted from problem and implementation performance is result of running implementation
> against 1 synth repo and 10 real-world repos.
>
> Implement and improve in ralph loop.
>
> Development environment:
>
> * Use mikhailov.tech ssh and folder improve-java-tests-spring on it
> * Use qwen 3.6 27b fp8 endpoint from inference.mikhailov.tech
> * Use improve-java-tests-spring.mikhailov.tech as url for n8n and
>   improve-java-tests-spring.mikhailov.tech/dashboard for dashboard
> * Login to n8n must be protected by caddy, no n8n login needed (make 10-years token and
>   pass it with caddy)

> addition: no LLM reasoning leakage into committed tests and no dead-weight tests; verified
> cleanup pass).

> per-file improvement criterion — a file is picked for repeated improvement rounds; a round
> is kept only if at least one of coverage / mutation score / MAC improves AND none of them
> degrades; rounds stop when all three stale or one or more degrades (the degrading round's
> changes are dropped; previously accepted rounds are kept as commits). Rounds are bounded by
> MAX_ROUNDS_PER_FILE. The file then gets one cumulative PR if it netted improvement.

> collect human-equivalent timesheets — for every improved file, estimate the developer time
> the delivered test work would have taken a human (itemized: module analysis, test-case
> writing, mutation analysis, verification/review), and show it on the dashboard per file and
> cumulatively — plus machine time spent, ETA to repo completion, and the human-FTE
> equivalent (human-equivalent hours ÷ machine hours: how many engineers working in parallel
> the pipeline replaces).

> add in/out token measurement

Later steering, which changed the design materially:

> pit is very slow, so it's better to mutate method, not a class
> so list of files must become list of methods
> let's ask llm to write single test that kills most promising mutant and then repeat
> mutation. this will make our test model small
> i think that coverage phase is needed only when there is no coverage on this method
> there is only 8 iterations, it's definitely not enough. better use infinity
> do not try to kill same mutant twice, but be ready that killing one mutant will kill many
> i think you are trying to spot mutants from llm judgement not from pit - that's wrong
