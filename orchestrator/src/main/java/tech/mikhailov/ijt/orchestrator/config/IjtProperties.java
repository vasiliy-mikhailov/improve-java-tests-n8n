package tech.mikhailov.ijt.orchestrator.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/// The container's ~40 environment variables, bound and named.
///
/// **This is not where a run's configuration comes from.** `State.envConfig()` is, and it reads
/// `System.getenv` directly — as do `Llm.envConfig`, `Pit`, `Coverage` and `Util.redact`. That
/// stays true after the migration: the domain layer is a library the orchestrator calls, not a
/// process it configures, and re-deriving a run's `State.Config` from these values would be a
/// second implementation of coercions that are load-bearing and unobvious (`MAX_ATTEMPTS_PER_FILE=0`
/// configures THREE attempts; `mutantsPerRound` is clamped up to 1; `prBase` falls back to the
/// cloned branch). To start a run, hand the request body to the domain layer and let it do that.
///
/// What this class is for:
///
///  - the ORCHESTRATOR's own reads — the dashboard's directory, the takeover wait — which have
///    no home in `State.Config` because n8n used to own them;
///  - making the configuration surface a typed, discoverable object instead of forty
///    `System.getenv` calls scattered over the tasklets;
///  - catching a garbled value at BOOT. `SCOPE_LIMIT=lots` is `NaN` to the JS and `null` to
///    `State.jsParseInt`, and it reaches the dashboard as a blank rather than as an error; here
///    the binder refuses to start the application and names the property. Finding that at second
///    zero instead of at hour three is worth the duplication.
///
/// And the duplication is pinned: `EnvironmentContractTest` binds these properties and calls
/// `State.envConfig` over the SAME environment and asserts field by field that they agree, for
/// an empty environment (defaults) and a fully populated one. If a default moves in the backend
/// and not here, that test fails.
///
/// Three known divergences, none of them silent:
///
///  1. An env var set to the EMPTY STRING is "unset" to the backend (`e.REPO_BRANCH || 'main'`)
///     and an empty VALUE to the binder — which is null for a boxed number. `.env.example` ships
///     `PR_BASE=` and `SETUP_SCRIPT=`, both strings, where the two agree; a numeric var set
///     empty would read null here and take its default there.
///  2. Booleans. The backend requires the exact string `true` (`"true".equals(...)`, in
///     `State.envConfig`, `Llm.envConfig` and `Pit`), so `DRY_RUN=1` is OFF. Spring's relaxed
///     binding would read `1` as true — the opposite — so the three of them are bound as TEXT
///     here and compared with the backend's own rule. See {@link Loop#dryRunEnabled()}.
///  3. `LLM_CONTEXT_LENGTH` is bound because .env.example documents it, and NOTHING reads it —
///     not this module, not the backend, not the retired Node sidecar. The model's context
///     window is the endpoint's business; the budget that is actually enforced is
///     `LLM_MAX_TOKENS_HARD` and what `Budget` learns from refusals.
///
/// Not bound, deliberately: `N8N_PORT`, `N8N_OWNER_EMAIL`, `WEBHOOK_URL`, `N8N_EDITOR_BASE_URL`
/// configure the process this module replaces, and `HOST_N8N_PORT` / `HOST_DASHBOARD_PORT` are
/// docker-compose port mappings that no process inside the container can see. `SIDECAR_PORT` is
/// bound by Spring itself as `server.port`, and `BACKEND` selects between this and the rollback
/// entry point before any of this is running.
@ConfigurationProperties("ijt")
public record IjtProperties(
        @DefaultValue Github github,
        @DefaultValue Repo repo,
        @DefaultValue Loop loop,
        @DefaultValue Toolchain toolchain,
        @DefaultValue Rules rules,
        @DefaultValue Llm llm,
        @DefaultValue Dashboard dashboard,
        @DefaultValue Ws ws,
        @DefaultValue Paths paths,
        @DefaultValue Run run) {

    /// A PAT with repo scope: clone private repos, push branches, open PRs.
    ///
    /// The token is bound so a missing one is visible in one place, and it is NOT passed to any
    /// child process from here — `gh` and git's credential helper read it from the process
    /// environment, and `Exec` keeps it out of the argv and out of the event log on purpose.
    public record Github(@DefaultValue("") String token,
                         @DefaultValue("improve-tests-bot") String userName,
                         @DefaultValue("bot@improve-tests.local") String userEmail) {}

    /// The two lines a team changes to point the pipeline at their code.
    public record Repo(@DefaultValue("") String url,
                       @DefaultValue("main") String branch) {}

    /// Scope and loop policy — the twenty knobs that decide how much work a run does and when it
    /// gives up. Every one of them also exists on `State.Config`, which is what the pipeline
    /// actually reads; these are the same values, bound.
    public record Loop(
            @DefaultValue("**/src/main/java/**/*.java") String scopeGlob,
            // 0 = whole repo; N = stop after N units were processed (improved or given up).
            @DefaultValue("0") Integer scopeLimit,
            // 0 = no limit for the repo.
            @DefaultValue("0") Integer maxIterations,
            // 0 = uncapped: a unit ends when a round kills nothing, when every surviving mutant
            // has been attempted, or when `unitBudgetSec` runs out. Those are real signals; a
            // fixed round count is a guess that stops a unit still paying off.
            @DefaultValue("0") Integer maxRoundsPerFile,
            // Mutants handed to the model per round. Code default 8; the shipped .env sets 1 —
            // one test for the single most promising survivor, re-measured before the next is
            // attempted, because small prompts compile far more reliably than a sprawling test
            // aimed at a dozen mutants.
            @DefaultValue("8") Integer mutantsPerRound,
            // survivors offered to the model when it chooses which mutant to kill this round
            @DefaultValue("20") Integer mutantChoices,
            // ceiling on the wall-clock one unit may consume, however cheap each round is
            @DefaultValue("3600") Integer unitBudgetSec,
            // consecutive rounds that fail to kill their mutant before a unit is given up
            @DefaultValue("3") Integer maxConsecutiveMisses,
            // Pick attempts per file before it is settled as no_improvement. NOTE the backend
            // reads this with `|| 3`, so a configured 0 means three, not none.
            @DefaultValue("3") Integer maxAttemptsPerFile,
            // Diminishing returns, both 0 by default: any real gain buys another round, because
            // a round already ends on its own when it kills nothing. Both are clamped to what
            // ONE mutant is worth, so a single kill always counts as progress.
            @DefaultValue("0") Double minRoundGapFrac,
            @DefaultValue("0") Double minRoundGain,
            // classes with fewer mutants than this are skipped without consuming the file quota
            @DefaultValue("1") Integer minMutantsPerClass,
            // a method with fewer executable lines than this is not worth a run (accessors,
            // one-line private constructors)
            @DefaultValue("1") Integer minUnitLines,
            // units we could not MEASURE do not consume the scope quota; this bounds how many
            // such failures a run tolerates before concluding the repo/toolchain is at fault
            @DefaultValue("10") Integer maxFailures,
            // The coverage phase runs only for a method covered at or below this (0 = only
            // methods nothing executes at all). Above it the mutation phase raises coverage on
            // its own: killing a NO_COVERAGE mutant means executing the line it sits on.
            @DefaultValue("0") Double covPhaseMaxPct,
            // `method` (default) mutates only the method a round targets and projects the class
            // score between rounds — PIT costs mutants x suite time, so a class-wide run per
            // round dominates wall-clock. `class` mutates everything every round.
            @DefaultValue("method") String pitScope,
            // github = push branch + open real PR | local = record branch + patch + PR payload
            @DefaultValue("github") String prMode,
            // Base branch for PRs. Empty means the branch that was cloned — the backend
            // substitutes it, which is why this is not defaulted to anything here.
            @DefaultValue("") String prBase,
            // Extra build goal/task to run after the initial compile (e.g. a codegen step).
            @DefaultValue("") String setupScript,
            // Text, not Boolean: see the class comment. The backend is `'true' === x`.
            @DefaultValue("false") String dryRun) {

        /// `DRY_RUN`, by the backend's rule: exactly the string `true`, so `yes` and `1` are off.
        public boolean dryRunEnabled() { return "true".equals(dryRun); }
    }

    /// PIT, JaCoCo and the artifact mirror. None of these reach a run's `State.Config`: they are
    /// read where the build is assembled (`Pit`, `Coverage`) and are bound here so the versions
    /// a deployment pins are visible in one place.
    public record Toolchain(
            @DefaultValue("1.25.8") String pitVersion,
            @DefaultValue("1.2.3") String pitJunit5Version,
            @DefaultValue("1.0.0") String pitTestngVersion,
            // `ALL` surfaces more mutation kinds — and more test ideas — at the cost of slower
            // runs and more equivalent mutants.
            @DefaultValue("DEFAULTS") String pitMutators,
            // PIT incremental history needs the commercial arcmutate plugin; without it, passing
            // the history flags fails the run outright. Text, by the backend's rule.
            @DefaultValue("false") String pitHistory,
            @DefaultValue("0.8.12") String jacocoVersion,
            // Artifact mirror for Maven and for Gradle PIT plugin resolution. OFF THE DEPLOYMENT
            // HOST THIS MUST BE EMPTY: the settings.xml that redirects every request at it is
            // installed only when the URL answers, and an unreachable mirror fails the build
            // rather than falling back to Central.
            @DefaultValue("") String mavenMirrorUrl) {

        /// `PIT_HISTORY`, by the backend's rule (`envOr(...).equals("true")`).
        public boolean pitHistoryEnabled() { return "true".equals(pitHistory); }
    }

    /// The six rule slots, free text, interpreted by the model at the stage they name. Empty is
    /// the default in code and in this file: an unset rule must be no rule, never a default
    /// opinion about how somebody else's repository should be tested.
    public record Rules(@DefaultValue("") String postClone,
                        @DefaultValue("") String prePick,
                        @DefaultValue("") String pickFile,
                        @DefaultValue("") String writeTest,
                        @DefaultValue("") String checkChanges,
                        @DefaultValue("") String makePr) {}

    /// The OpenAI-compatible endpoint.
    public record Llm(@DefaultValue("") String baseUrl,
                      @DefaultValue("") String apiKey,
                      @DefaultValue("qwen-3.6-27b-fp8") String model,
                      // Documented in .env.example and read by nothing — see the class comment.
                      @DefaultValue("32768") Integer contextLength,
                      // Text, by the backend's rule.
                      @DefaultValue("false") String enableThinking,
                      // Completion tokens reserved for thinking before any visible output. Only
                      // applied when thinking is on; the backend zeroes it otherwise.
                      @DefaultValue("3000") Integer thinkingBudget,
                      // The ceiling no stage may exceed, whatever `Budget` has learned.
                      @DefaultValue("16000") Integer maxTokensHard) {

        /// `LLM_ENABLE_THINKING`, by the backend's rule.
        public boolean thinkingEnabled() { return "true".equals(enableThinking); }
    }

    /// Where the dashboard's static files live. Not ported and not in the jar: plain HTML/CSS/JS
    /// the image copies from dashboard/. `application.yml` serves this directory, so the
    /// two cannot drift.
    public record Dashboard(@DefaultValue("/app/dashboard") String dir) {}

    /// The STOMP transport that replaces the 2-second poll.
    public record Ws(@DefaultValue("250") Long pollMillis) {}

    public record Paths(@DefaultValue("/data") String dataDir) {}

    /// Trigger policy — the part of a run's lifecycle n8n used to own.
    public record Run(@DefaultValue("2h") Duration takeoverWait,
                      @DefaultValue("2s") Duration takeoverPoll) {}
}
