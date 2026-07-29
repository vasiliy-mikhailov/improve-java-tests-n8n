# Replacing n8n with Spring

The domain logic already moved to Java (30 modules, 902 tests). This replaces the other
half: n8n for orchestration, and the polling dashboard.

    n8n workflow (66 nodes, 44 HTTP calls)  ->  Spring Batch job
    dashboard polling /api/state every 2s   ->  Spring WebSocket (STOMP)
    /data/state.json                        ->  H2

The 44 HTTP calls become in-process method calls. That deletes an entire failure mode: the
timeout contract between an HTTP client and the subprocess it waits on, which cost a live
run 6h47m. It does NOT delete the underlying hazard — see "What must not be lost".

## The control flow being replaced

Read off `n8n/generate-workflows.mjs`, which is the source of the 66 nodes.

    setup ....... clone -> rules(post_clone) -> build+detect -> baseline coverage
                  -> rules(pre_pick) -> rules(write_test)

    UNIT LOOP ... candidates -> more work? --no--> finish
                    -> rules(pick_file) -> picked? --no--> retryable? -> loop | finish
                    -> start iteration -> baseline mutation -> has mutants? --no--> loop
                    -> coverage gaps
                    -> [PHASE cov] -> [PHASE mut]
                    -> verify -> keep round? -> accept | miss
                    -> another round? --yes--> back to coverage gaps   (ROUND LOOP)
                    -> drop last round -> rules(check_changes) -> approved?
                         yes -> cleanup -> rules(make_pr) -> create PR
                         no  -> discard
                    -> loop

    PHASE(p) .... build prompt -> has work? --no--> done
                    -> llm -> parse -> write -> run tests -> green? --yes--> done
                    -> wrote any? --no--> done
                    -> build repair -> llm -> parse -> write -> re-run -> green? --yes--> done
                    -> delete broken tests -> done

Two nested loops and fifteen decision points.

## Why Spring Batch is used the way it is

Batch is good at: job/step persistence, restart, and per-step metrics. It is awkward at
tight nested loops with rich state and many branches — expressing the ROUND loop as a flow
of `JobExecutionDecider`s would be more XML-shaped ceremony than the logic deserves, and
harder to read than the n8n graph it replaces.

So the split is deliberate:

    improveJob
      setupStep    Tasklet, once
      improveStep  Tasklet returning RepeatStatus.CONTINUABLE -- ONE UNIT per invocation
      finishStep   Tasklet, once

One unit per `improveStep` invocation, and Batch repeats it. The round loop inside a unit
stays imperative Java. That buys the thing actually worth having — a crashed or killed run
resumes at the next UNIT rather than re-cloning and re-measuring from zero — without
contorting the control flow.

Restart granularity is a unit because that is where the natural checkpoint already is: the
ledgers record final dispositions per unit, and a half-finished unit's work is discarded by
design anyway.

## Persistence

H2, **file-backed on disk** under `DATA_DIR` — `jdbc:h2:file:/data/ijt`.

This reverses an earlier in-memory decision, and the reason is worth keeping: the ledgers
are not a cache, they are the record of what has already been done. See below.

    Run        one row per run: id, repoUrl, branch, config, status, startedAt
    Unit       path::method, coverage, mutation, mac, status, attempts, lastSurvived
    EventRow   seq, ts, stage, msg          -- append-only, the dashboard's feed
    PrRow      unit, branch, title, url/patchPath, mode, createdAt
    LedgerRow  repoSlug + unit -> disposition  -- improvedLedger / measureLedger

WHY ON DISK. Three things today would have been lost on every container restart, and all
three are correctness rather than performance:

- `improvedLedger` (143 entries) is what stopped run 2 redoing run 1's work. Without it the
  pipeline re-improves files that already have open PRs.
- `measureLedger` is why a full run starts in seconds instead of re-measuring 319 units for
  40 minutes. That cost is paid once and reused — unless it evaporates.
- A hung run was recoverable ONLY because state.json survived a container restart: the
  backend read it back and marked it `interrupted` rather than pretending it was idle.

The volume is already there (`ijt_data:/data`), so the file lives beside the state.json it
replaces and survives `docker compose up -d --force-recreate`. `deploy.sh --fresh` wipes it,
which is the same semantics as today.

The seam stays explicit regardless: everything goes through `StateRepository`, so the
storage choice remains a URL, not a refactor. Do not scatter `EntityManager` use through the
tasklets.

MIGRATION NOTE: an existing /data/state.json holds a populated improvedLedger and
measureLedger. Read it once on first boot if the H2 file does not yet exist, or the first
Spring run redoes work the Node and Java backends already finished.

## WebSocket

STOMP over SockJS. `State.event()` publishes to `/topic/events`; stage changes to
`/topic/stage`. The dashboard subscribes instead of polling `/api/state` every 2 s.

`seq` still matters. It is not just an ordering key — a client that reconnects asks for
everything after the last seq it holds, so the counter must keep rising across runs. That
is already load-bearing today and a reset would silently drop events for any client that
was connected across the boundary.

Keep `GET /api/state` as a REST endpoint. A fresh page load needs a snapshot before the
stream is useful, and it is what every existing diagnostic in this repo reads.

## What must not be lost

Each of these cost a real run to learn. They survive the move or the move is a regression.

1. TIMEOUT ORDERING. No HTTP client sits between the caller and the subprocess any more,
   but Batch step timeouts and any `@Transactional` timeout take its place. A step timeout
   less than or equal to the subprocess ceiling reproduces the 6h47m hang exactly.
   `config/timeouts.json` stays the single source; step config derives from it.

2. PROCESS GROUP KILLS. `Exec` must keep killing descendants, not just the direct child.
   Maven and Gradle fork Surefire and PIT minion JVMs, and an orphan burns host CPU while
   the timeout that was supposed to stop it reports success.

3. THE EVENT LOG BELONGS TO A RUN. Clearing it at run start is why the dashboard no longer
   opens with the previous run's units. In SQL that means deleting this run's rows, not
   truncating the table blindly — and `seq` still does not reset.

4. UNMEASURED IS NOT ZERO. A unit whose mutation score could not be measured must never
   record 0. Zero is a measurement; absent is not, and reporting absent as zero flatters
   every later improvement.

5. THE LEDGERS SURVIVE `run/start`. Everything else about a run is reset; the per-repo
   ledgers are what make batched full-repo runs and crash-restart possible. They must also
   survive the PROCESS — hence file-backed H2 above. Surviving `run/start` but not a restart
   would fix half the problem and hide the other half.

## Not in scope

- The domain logic. It is ported, tested, and running.
- Team rules. They stay LLM-interpreted configuration and need no code change.
- The dashboard's look. Only its transport changes, polling -> subscribe.
