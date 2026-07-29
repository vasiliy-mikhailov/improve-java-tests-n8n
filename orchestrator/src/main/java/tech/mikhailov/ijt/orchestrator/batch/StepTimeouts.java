package tech.mikhailov.ijt.orchestrator.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;

import tech.mikhailov.ijt.Timeouts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// The outer clock, derived from the inner ones.
///
/// Deleting the HTTP hop deleted the client that gave up on a request while the backend was still
/// killing a process group. It did NOT delete the hazard, it moved it: a Batch step runs inside a
/// transaction, and that transaction's timeout now sits exactly where the HTTP timeout sat. A
/// step timeout at or below the subprocess ceiling reproduces the 6h47m hang precisely — the step
/// is declared failed, the Maven and PIT minion JVMs carry on burning host CPU, and the run
/// reports a status that has nothing to do with what the machine is doing.
///
/// So no number below is typed twice. `config/timeouts.json` is the single source — the same file
/// the n8n generator read and the same one `TimeoutsContractTest` pins the backend's constants to
/// — and every step budget here is a SUM over the ceilings of the routes that step can call, plus
/// the margin. A sum, because a step is a whole subgraph where an n8n node was a single call:
/// `setupStep` alone can spend a clone, a fifty-minute build and two sequential JaCoCo runs.
///
/// **Two routes the shared config does not cover, and must not be copied from n8n.**
/// `/api/verify` runs a coverage build (ceiling 2 x coverageRun) and then PIT (pitCompile +
/// pitRunMax): 12.6M ms. Its n8n node was given 5 400 000. `/api/test/cleanup` runs a full suite
/// and then PIT: 9M ms, and its node was given 5 400 000 as well. Both are the SAME defect this
/// file exists to prevent, still latent in the workflow because they never hit their worst case.
/// Their budgets below are composed from `perCallMs`, not inherited.
public final class StepTimeouts {

    /// Spring's "no timeout" (-1). A step with no clock cannot fire before the work finishes,
    /// which is the safe direction: the cost of being too generous is noticing a dead run late,
    /// and the cost of being too tight is the defect above.
    public static final int NO_TIMEOUT = TransactionDefinition.TIMEOUT_DEFAULT;

    // ── the allowances config/timeouts.json does not state ────────────────────
    //
    // These are the n8n node timeouts, kept because they are the only recorded judgement about
    // how long those routes may take, and none of them waits on an unbounded subprocess: the
    // clone, the build and the PR creation all pass their own timeout to Exec, and the model call
    // has the client's. They are allowances, not ceilings — if one of them ever grows a
    // subprocess whose limit belongs in the shared contract, it belongs in timeouts.json and not
    // here.

    /// `POST /api/repo/clone` — 20 min.
    static final long CLONE_MS = 1_200_000L;

    /// `POST /api/repo/prepare` — 50 min. A cold Maven or Gradle build of a real repository.
    static final long PREPARE_MS = 3_000_000L;

    /// `POST /api/llm/chat` — 15 min per call, and a phase makes two.
    static final long LLM_MS = 900_000L;

    /// `POST /api/pr/create` — 5 min.
    static final long CREATE_PR_MS = 300_000L;

    /// The generator's default node timeout — 10 min — for everything that neither shells out nor
    /// calls a model: candidates, gaps, parse, write, delete, iteration start, round bookkeeping,
    /// and the rule applications (which do call a model, through a client that bounds itself).
    static final long PLAIN_MS = 600_000L;

    /// Where the shared contract lives, tried in order. The container puts the repo at `/app`;
    /// Maven runs a module's tests with the module directory as the working directory.
    private static final List<String> CANDIDATE_PATHS =
            List.of("config/timeouts.json", "../config/timeouts.json", "/app/config/timeouts.json");

    private final Map<String, Long> ceilings;
    private final long marginMs;
    private final int improveStepSeconds;
    private final String source;

    StepTimeouts(Map<String, Long> ceilings, long marginMs, int improveStepSeconds, String source) {
        this.ceilings = Map.copyOf(ceilings);
        this.marginMs = marginMs;
        this.improveStepSeconds = improveStepSeconds;
        this.source = source;
        validate();
    }

    /// Read the contract, falling back to the backend's compile-time constants.
    ///
    /// The fallback is not laxity and it is not a second source: `TimeoutsContractTest` fails the
    /// build if {@link Timeouts} and the JSON ever disagree, so the two are the same numbers. It
    /// is there because a service that will not start because a config file moved is a worse
    /// failure than one that starts on numbers a test guarantees are current — the same judgement
    /// the backend made, for the same reason.
    ///
    /// @param configuredPath an explicit location, or null/blank to search {@link #CANDIDATE_PATHS}
    /// @param improveStepSeconds the one budget that cannot be derived — see
    ///                           {@link #improveStepSeconds()}
    public static StepTimeouts load(String configuredPath, int improveStepSeconds) {
        Path file = locate(configuredPath);
        Map<String, Long> ceilings = new LinkedHashMap<>();
        Long margin = null;
        if (file != null) {
            try {
                JsonNode root = new ObjectMapper().readTree(Files.readString(file));
                JsonNode routes = root.get("routes");
                if (routes != null) {
                    routes.fieldNames().forEachRemaining(route ->
                            ceilings.put(route, routes.get(route).get("ceilingMs").asLong()));
                }
                JsonNode configured = root.get("httpMarginMs");
                margin = configured == null ? null : configured.asLong();
            } catch (Exception e) {
                // Fall through to the constants — see above. A malformed shared file is the same
                // situation as a missing one. Nothing below this line is in the try: a rejected
                // step timeout must reach the caller as itself, not as "the config was unreadable".
                ceilings.clear();
                margin = null;
            }
        }
        if (!ceilings.isEmpty() && margin != null) {
            return new StepTimeouts(ceilings, margin, improveStepSeconds, file.toString());
        }
        return new StepTimeouts(Timeouts.SUBPROCESS_CEILING_MS, Timeouts.HTTP_MARGIN_MS,
                improveStepSeconds, "tech.mikhailov.ijt.Timeouts");
    }

    private static Path locate(String configuredPath) {
        if (configuredPath != null && !configuredPath.isBlank()) {
            Path p = Path.of(configuredPath);
            return Files.isReadable(p) ? p : null;
        }
        for (String candidate : CANDIDATE_PATHS) {
            Path p = Path.of(candidate);
            if (Files.isReadable(p)) {
                return p;
            }
        }
        return null;
    }

    /// Where the numbers came from — logged at startup, because "which timeouts is it running on"
    /// is the first question asked of a run that ended at a suspiciously round number of minutes.
    public String source() {
        return source;
    }

    /// The longest a route can legitimately spend in child processes, end to end. A SUM over the
    /// route's children, never a per-call limit: reading it as per-call is how /api/coverage/run
    /// came to allow half of what it needs.
    public long ceilingMs(String route) {
        Long ceiling = ceilings.get(route);
        if (ceiling == null) {
            // Never fall back to a default. An unknown route silently given a generic timeout is
            // how this class of bug returns.
            throw new IllegalArgumentException("unknown route for a subprocess ceiling: " + route
                    + " — add it to config/timeouts.json");
        }
        return ceiling;
    }

    public long marginMs() {
        return marginMs;
    }

    /// `/api/verify`: a coverage build, then PIT. Composed, not copied — see the class comment.
    public long verifyBudgetMs() {
        return ceilingMs("/api/coverage/run") + ceilingMs("/api/pit/run");
    }

    /// `/api/test/cleanup`: a full suite, then PIT, and only when the model changed something.
    public long cleanupBudgetMs() {
        return ceilingMs("/api/test/run") + ceilingMs("/api/pit/run");
    }

    /// One {@link Phase} at its longest: prompt, model, parse, write, suite — then the repair
    /// arm, which is another model call, another write and another suite.
    public long phaseBudgetMs() {
        return PLAIN_MS + LLM_MS + PLAIN_MS + PLAIN_MS + ceilingMs("/api/test/run")
                + PLAIN_MS + LLM_MS + PLAIN_MS + PLAIN_MS + ceilingMs("/api/test/run")
                + PLAIN_MS;
    }

    /// One round: gaps, both phases, verify, and the accept-or-miss that ends it.
    public long roundBudgetMs() {
        return PLAIN_MS + 2 * phaseBudgetMs() + verifyBudgetMs() + PLAIN_MS;
    }

    /// Everything a unit spends outside its rounds: the pick, the iteration start and the
    /// baseline PIT run at the front; the drop, the two rule applications, the cleanup and the PR
    /// at the back.
    public long unitOverheadBudgetMs() {
        return PLAIN_MS + PLAIN_MS + PLAIN_MS + ceilingMs("/api/pit/run")
                + PLAIN_MS + PLAIN_MS + cleanupBudgetMs() + PLAIN_MS + CREATE_PR_MS;
    }

    /// The floor under any configured `improveStep` timeout: one unit that runs exactly ONE round
    /// still costs this much, so a clock below it can fire while the work is legitimate.
    public long minimumUnitBudgetMs() {
        return unitOverheadBudgetMs() + roundBudgetMs();
    }

    /// clone + rules + build + baseline coverage + two more rules.
    public long setupBudgetMs() {
        return CLONE_MS + PLAIN_MS + PREPARE_MS + ceilingMs("/api/coverage/run") + PLAIN_MS + PLAIN_MS;
    }

    public int setupStepSeconds() {
        return seconds(setupBudgetMs() + marginMs);
    }

    /// The one budget that is not derived, and the one place a number may be configured.
    ///
    /// A unit's real bound is the run's own `UNIT_BUDGET_SEC`, checked between rounds by
    /// `Rounds.decide`, and the deployment ships `MAX_ROUNDS_PER_FILE=0` — uncapped. Both are
    /// per-RUN and can be overridden in the request that starts one, so a step clock computed
    /// here from an environment variable would be a second copy of a contract whose other half
    /// changes at run time. That is the shape of the original defect exactly: two halves, written
    /// independently, agreeing right up until they do not.
    ///
    /// So the default is {@link #NO_TIMEOUT}: the clocks that may fire are the subprocess
    /// ceilings inside `Exec`, which kill the whole process group — Maven and Gradle fork
    /// Surefire and PIT minion JVMs, and an orphan burns host CPU while the timeout that was
    /// supposed to stop it reports success. An operator who wants an outer bound sets one, and
    /// {@link #validate()} refuses any value that does not exceed {@link #minimumUnitBudgetMs()}.
    public int improveStepSeconds() {
        return improveStepSeconds;
    }

    public int finishStepSeconds() {
        return seconds(PLAIN_MS + marginMs);
    }

    /// A step's transaction attribute. Batch runs a tasklet inside this transaction, so its
    /// timeout is the step's clock.
    public DefaultTransactionAttribute attribute(int timeoutSeconds) {
        DefaultTransactionAttribute attribute = new DefaultTransactionAttribute();
        attribute.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        attribute.setTimeout(timeoutSeconds);
        return attribute;
    }

    /// Fail at startup, not at hour six.
    ///
    /// Everything derived is above its ceilings by construction — the margin is added last and is
    /// positive — so the assertions that can actually fire are about the configured value and
    /// about a contract file that lost a route.
    void validate() {
        if (marginMs <= 0) {
            throw new IllegalStateException("httpMarginMs must be positive: killing a process group, "
                    + "draining its output and parsing a report all happen AFTER the ceiling is hit");
        }
        for (String route : List.of("/api/test/run", "/api/coverage/run", "/api/pit/run")) {
            ceilingMs(route);   // throws, naming the route, if the contract lost one
        }
        assertOutlives("setupStep", setupStepSeconds(), setupBudgetMs());
        assertOutlives("finishStep", finishStepSeconds(), PLAIN_MS);
        if (improveStepSeconds != NO_TIMEOUT) {
            assertOutlives("improveStep", improveStepSeconds, minimumUnitBudgetMs());
        }
    }

    private static void assertOutlives(String step, int timeoutSeconds, long budgetMs) {
        if (timeoutSeconds * 1000L <= budgetMs) {
            throw new IllegalStateException(step + " has a timeout of " + timeoutSeconds
                    + "s, which does not exceed the " + (budgetMs / 1000)
                    + "s its subprocesses may legitimately take. A step timeout at or below the "
                    + "subprocess ceiling reproduces the 6h47m hang: the step fails, the child "
                    + "processes do not, and the run reports a state it is not in.");
        }
    }

    /// Rounded UP: a budget rounded down is a budget below the ceiling it was derived from.
    private static int seconds(long ms) {
        return (int) ((ms + 999) / 1000);
    }
}
