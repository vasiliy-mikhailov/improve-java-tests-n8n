# Original problem statement (verbatim)

> The problem: We are building improve-java-tests-n8n n8n pipeline in docker for teams to adapt on
> their repos. Team need to: specify java repo, run docker with n8n pipeline against it and get
> improved tests for each file, meaning improved coverage, improved mutation score (with PIT),
> improved MAC (coverage * mutation score) and to get pr's with improvements for each file where
> improvements happened. Also teams need to see what's happening in n8n right now - is it picking a
> file to mutate or improving coverage or improving mutation score or improving mac or preparing pr.
> Also there can be rules that should be applied to every stage of process - you will implement -
> e.g. after downloading repo, how to act before picking a file (e.g. make separate branch), how to
> pick file (e.g. don't touch ui), on write test (e.g. don't use introspection), on how to check if
> changes are good, how to make pr (e.g. pr style is ...). Teams will use this docker file and n8n
> workflow adapting it to their particular project, so use only n8n native blocks - no shell, no
> python etc.
>
> Please turn this problem into research, meaning: problem + DoD + reward formula for implementation
> - DoD * implementation_performance. Where DoD is structured list you extracted from problem and
> implementation performance is result of running implementation against 1 synth repo and 10
> real-world repos.
>
> Implement and improve in ralph loop.
>
> Development environment:
>
> * Use mikhailov.tech ssh and folder improve-java-tests-n8n on it
> * Use qwen 3.6 27b fp8 endpoint from inference.mikhailov.tech
> * Use improve-java-tests-n8n.mikhailov.tech as url for n8n and improve-java-tests-n8n.mikhailov.tech/dashboard for dashboard
> * Login to n8n must be protected by caddy, no n8n login needed (make 10-years token and pass it with caddy)

## Additions (verbatim)

> addition: no LLM reasoning leakage into committed tests and no dead-weight tests; verified cleanup
> pass).

> per-file improvement criterion — a file is picked for repeated improvement rounds; a round is kept
> only if at least one of coverage / mutation score / MAC improves AND none of them degrades; rounds
> stop when all three stale or one or more degrades (the degrading round's changes are dropped;
> previously accepted rounds are kept as commits). Rounds are bounded by MAX_ROUNDS_PER_FILE. The
> file then gets one cumulative PR if it netted improvement.

> collect human-equivalent timesheets — for every improved file, estimate the developer time the
> delivered test work would have taken a human (itemized: module analysis, test-case writing,
> mutation analysis, verification/review), and show it on the dashboard per file and cumulatively —
> plus machine time spent, ETA to repo completion, and the human-FTE equivalent (human-equivalent
> hours ÷ machine hours: how many engineers working in parallel the pipeline replaces).

See RESEARCH.md for the derived DoD and reward formula, eval/RESULTS.md for iteration history.

> add in/out token measurement

(2026-07-26) LLM token usage — input and output tokens — must be measured and shown:
per improved unit and cumulatively for the run, alongside the machine-time and
human-equivalent accounting.
