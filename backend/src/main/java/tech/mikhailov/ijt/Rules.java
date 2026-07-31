package tech.mikhailov.ijt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Per-stage team rules. Rule text comes from env/run config; application = LLM interpretation
/// with mechanical guardrails; results recorded in state.decisions.
///
/// ## The seams
///
/// rules.js reaches into four modules: `state` (the run's rule text, the decision ledger, the
/// event log and the pick-failure counter), `llm.chat`, `repo.readFileSafe` and `pick.choosePick`.
/// The first three are {@link Io} here, for the reason {@link Pr}, {@link Tests} and
/// {@link Coverage} give: what this module DECIDES — whether a template is usable, whether the
/// mechanical gate passes, what the model is allowed to assert in a PR body — is then a
/// function over its arguments and is tested without a model, a checkout or a network.
/// {@link Pick#choosePick} is imported directly, exactly as the JS imports it: it is a decision
/// this module asks another module to make, not a seam.
public final class Rules implements Server.RulesEngine {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /// The six stages `apply` knows. Anything else is a caller error, not a no-op.
    static final List<String> STAGES = State.Rules.STAGES;

    /// What this module needs from the rest of the backend, in one place.
    public interface Io {

        /// `state.run?.config?.rules?.[stage] || ''` — the team's text for one stage.
        ///
        /// Deliberately NOT falling back to the environment's rules the way `GET /api/rules`
        /// does: rules.js reads the RUN's config and nothing else, so a stage applied before a
        /// run exists has no rule rather than the container's default.
        String rule(String stage);

        /// `repo.readFileSafe(rel, maxLen)`. May throw — see {@link #repoContextHead}, which is
        /// where that matters.
        String readFileSafe(String rel, int maxLen);

        /// `llm.chat(opts)`. Whatever the transport throws comes back unchecked: a rule stage
        /// can do nothing with an IOException that the route above it cannot do better.
        Llm.Answer chat(Llm.Options opts);

        /// Append to the event log the dashboard polls.
        void event(String stage, String msg);

        /// `state.decisions` itself, so {@link #testWritingConstraints} reads what earlier
        /// stages wrote.
        Map<String, Object> decisions();

        /// `state.decisions[stage] = entry`.
        void recordDecision(String stage, Map<String, Object> entry);

        /// `state.pickFailures = (state.pickFailures || 0) + 1`, answering the new count.
        int bumpPickFailures();

        /// `state.pickFailures = 0`.
        void resetPickFailures();

        /// `state.runner?.[name]` — read only to tell the model what the run actually verified.
        Object runnerField(String name);

        /// `state.runner?.jdk?.[name]`.
        Object runnerJdk(String name);

        /// `Date.now()`, for the decision's timestamp.
        long nowMillis();
    }

    private final Io io;

    public Rules(Io io) { this.io = io; }

    /// The seam this engine reads through. Package-private, and here so a test can assert on what
    /// `forServer()` actually resolves a stage's rule to — asserting on the helper instead would
    /// pass with the rule wired to nothing, which is how eight components in this codebase came
    /// to be correct and unreachable.
    Io io() { return io; }

    // ── the repo's own words ───────────────────────────────────────────────────────────────

    /// The head of whatever conventions the repo documents, for the stages that ask the model
    /// to honour them.
    String repoContextHead() {
        List<String> parts = new ArrayList<>();
        for (String f : List.of("AGENTS.md", "CONTRIBUTING.md", "README.md")) {
            // Optional context, best-effort by definition. readFileSafe swallows a missing FILE
            // but readFileSafe -> repoDir() throws outright when there is no checkout, and that
            // exception took down the whole PR composition — a function whose job is "some docs
            // if we have them" must not be the thing that fails.
            String c;
            try {
                c = io.readFileSafe(f, 4000);
            } catch (RuntimeException e) {
                c = null;
            }
            // truthy, not merely non-null: an empty file contributes nothing but a header
            if (c != null && !c.isEmpty()) parts.add("--- " + f + " (head) ---\n" + c);
        }
        return parts.isEmpty()
                ? "(no AGENTS.md / CONTRIBUTING.md / README.md found)"
                : String.join("\n\n", parts);
    }

    // ── the constraints every test-writing prompt carries ──────────────────────────────────

    /// Free-text guidance assembled for test-writing prompts.
    ///
    /// The first read is `state.decisions.post_clone.constraints`, and it is spelled that way
    /// here because that is what the JS reads — NOT `.result.constraints`. `apply` stores
    /// `{rule, result, ts}`, so the key this looks for is not the key that was written and the
    /// branch has never once fired in production; every prompt ships with the `write_test` rule
    /// alone. Reading `.result.constraints` instead would be a behaviour change — it would start
    /// putting the post-clone constraints into every prompt — so the port keeps the JS reading
    /// and records the discrepancy here rather than quietly repairing it.
    public List<String> testWritingConstraints() {
        List<String> out = new ArrayList<>();
        Map<String, Object> d = io.decisions();
        Map<String, Object> postClone = Server.asMap(d == null ? null : d.get("post_clone"));
        List<String> stated = postClone == null ? List.of() : Server.strings(postClone.get("constraints"));
        if (!stated.isEmpty()) out.addAll(stated);
        String writeTest = io.rule("write_test");
        if (writeTest != null && !writeTest.isEmpty()) out.add(writeTest);
        return out;
    }

    // ── applying one stage ─────────────────────────────────────────────────────────────────

    public Object apply(String stage, Map<String, Object> context) {
        Map<String, Object> ctx = context == null ? Map.of() : context;
        // `rules()[stage] || ''`: every branch below tests this for emptiness, and a null would
        // read as "there is a rule" at the first `!ruleText` that is written as a null check
        String ruleText = nullToEmpty(io.rule(stage));
        Object result = switch (stage == null ? "" : stage) {
            case "post_clone" -> applyPostClone(ruleText);
            case "pre_pick" -> applyPrePick(ruleText);
            case "pick_file" -> applyPickFile(ruleText, ctx);
            case "write_test" -> mapOf("constraints", testWritingConstraints());
            case "check_changes" -> applyCheckChanges(ruleText, ctx);
            case "make_pr" -> applyMakePr(ruleText, ctx);
            default -> throw new IllegalStateException("unknown rule stage: " + stage);
        };
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("rule", ruleText);
        entry.put("result", result);
        // milliseconds, and an integer — `Date.now()`. A double would reach the state file as
        // 1.7e12 and the dashboard would render it as a date in 1970.
        entry.put("ts", io.nowMillis());
        io.recordDecision(stage, entry);
        io.event("rules", "applied " + stage + " rules: " + State.clip(json(result), 240));
        return result;
    }

    // ── post_clone: what the team's rule and the repo's docs actually require ───────────────

    static final String POST_CLONE_SYSTEM =
            "You are configuring an automated test-improvement pipeline. Extract actionable"
            + " constraints from the team rule and repo docs. Reply ONLY with JSON:"
            + " {\"constraints\": [\"short imperative constraint\", ...], \"notes\": \"one line\"}."
            + " Constraints are things the pipeline must honor when writing tests, naming branches,"
            + " or making PRs.";

    Object applyPostClone(String ruleText) {
        if (ruleText.isEmpty()) {
            return mapOf("constraints", List.of(), "notes", "no post-clone rules configured");
        }
        Llm.Answer r = io.chat(Llm.Options.of()
                .withSystem(POST_CLONE_SYSTEM)
                .withPrompt("TEAM RULE (post-clone): " + ruleText + "\n\nREPO DOCS:\n" + repoContextHead())
                .withJson(true)
                .withMaxTokens(1200));
        Object j = plain(r.json());
        return j != null ? j
                : mapOf("constraints", List.of(ruleText), "notes", "LLM parse failed, using raw rule text");
    }

    // ── pre_pick: what the branch is called ────────────────────────────────────────────────

    static final String PRE_PICK_SYSTEM =
            "You configure branching for an automated test-improvement pipeline. From the team"
            + " rule, produce JSON only: {\"branchTemplate\": \"template containing {file}"
            + " placeholder\", \"notes\": \"one line\"}. The template must be a valid git branch"
            + " name pattern; {file} will be replaced by a slug of the source file.";

    static final String DEFAULT_BRANCH_TEMPLATE = "tests/improve-{file}";

    Object applyPrePick(String ruleText) {
        if (ruleText.isEmpty()) {
            return mapOf("branchTemplate", DEFAULT_BRANCH_TEMPLATE, "notes", "default branch naming");
        }
        Llm.Answer r = io.chat(Llm.Options.of()
                .withSystem(PRE_PICK_SYSTEM)
                .withPrompt("TEAM RULE (before picking a file): " + ruleText
                        + "\n\nREPO DOCS:\n" + repoContextHead())
                .withJson(true)
                .withMaxTokens(800));
        Map<String, Object> j = Server.asMap(plain(r.json()));
        String template = j == null ? null : Server.str(j.get("branchTemplate"));
        // `!j?.branchTemplate` is falsy, so an empty template is no template; and a template
        // with no placeholder would name every branch the same and collide on the second unit
        if (template == null || template.isEmpty() || !template.contains("{file}")) {
            return mapOf("branchTemplate", DEFAULT_BRANCH_TEMPLATE,
                    "notes", "rule did not yield a usable template");
        }
        // replaceAll, not replaceFirst: the JS regex carries /g, so EVERY run of characters a
        // branch name cannot hold collapses to a dash, not just the first
        j.put("branchTemplate", template.replaceAll("[^a-zA-Z0-9/_{}.-]+", "-"));
        return j;
    }

    // ── pick_file: the rule vetoes, the measurements choose ────────────────────────────────

    static final String PICK_FILE_SYSTEM =
            "You apply ONE team rule to a list of candidate units for automated Java test improvement."
            + " A unit is \"<source file>::<method>\". Do NOT choose which to work on — that is"
            + " decided by measurements."
            + " Your ONLY job: list the candidates the team rule EXCLUDES."
            + " Reply ONLY with JSON: {\"exclude\": [\"<exact unit string, or a source file path to"
            + " exclude all its methods>\", ...], \"reason\": \"one line\"}."
            + " Exclude nothing unless the rule actually says so — an empty list is the normal answer"
            + " for a rule that only expresses a preference."
            + " Never exclude a unit merely for being small, well covered, or unmeasured.";

    /// How many failures to apply the rule are tolerated before the batch is given up on.
    static final int MAX_PICK_FAILURES = 3;

    Object applyPickFile(String ruleText, Map<String, Object> context) {
        // The candidates arrive already ranked weakest-first, with unreachable units removed
        // (select.rankUnits). The model's job here is the TEAM RULE and nothing else: which
        // candidates does it exclude? It used to choose outright, and chose the fifth-ranked
        // unit — a private method three hops from any public entry — over three directly
        // callable ones. Measurements choose; the rule vetoes.
        List<Map<String, Object>> rows = Server.maps(context.get("candidates"));
        List<Pick.Candidate> candidates = new ArrayList<>();
        for (Map<String, Object> c : rows) {
            candidates.add(new Pick.Candidate(Server.str(c.get("path")), Server.str(c.get("file")),
                    Server.str(c.get("method"))));
        }
        if (candidates.isEmpty()) return decision(Pick.choosePick(List.of(), List.of()));
        if (ruleText.isEmpty()) return decision(Pick.choosePick(candidates, List.of()));

        Llm.Answer r = io.chat(Llm.Options.of()
                .withSystem(PICK_FILE_SYSTEM)
                .withPrompt("TEAM RULE (how to pick a file): " + ruleText + "\n\nCANDIDATES:\n"
                        + candidateTable(rows))
                .withJson(true)
                .withMaxTokens(900)
                .withStage("picking_file"));
        Map<String, Object> j = Server.asMap(plain(r.json()));
        // `Array.isArray(j.exclude)` — an object or a string here is not an exclusion list, and
        // treating it as one would silently exclude nothing while claiming the rule was applied
        List<String> exclude = (j == null || !(j.get("exclude") instanceof List))
                ? null : Server.strings(j.get("exclude"));
        if (exclude == null) {
            // a transient model hiccup: retry rather than pick blind and risk violating the rule
            int failures = io.bumpPickFailures();
            boolean retry = failures < MAX_PICK_FAILURES;
            io.event("picking_file", "pick rule could not be applied (invalid LLM output, "
                    + failures + "/" + MAX_PICK_FAILURES + ") — "
                    + (retry ? "retrying" : "giving up on this batch"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("file", null);
            out.put("retry", retry);
            out.put("reason", "pick rule could not be applied reliably; refusing a pick that might violate it");
            return out;
        }
        io.resetPickFailures();
        Pick.Decision chosen = Pick.choosePick(candidates, exclude);
        if (!exclude.isEmpty()) {
            String reason = Server.str(j.get("reason"));
            io.event("picking_file", "team rule excluded " + exclude.size() + " candidate(s)"
                    + (reason == null || reason.isEmpty() ? "" : ": " + State.clip(reason, 160)));
        }
        return decision(chosen);
    }

    /// The first 80 candidates, one per line, as the model sees them.
    ///
    /// Every number goes through {@link Util#showMetric}: these are boxed Doubles off a store
    /// that round-tripped through JSON, and plain concatenation would offer the model
    /// `coverage=80.0%` where the JS offered `coverage=80%`. `?` is what the JS `?? '?'` prints
    /// for a unit nothing has measured yet.
    static String candidateTable(List<Map<String, Object>> rows) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> c : rows.subList(0, Math.min(80, rows.size()))) {
            String method = Server.str(c.get("method"));
            // truthy: a unit with no method (a whole-file candidate) says nothing here
            String methodPart = (method == null || method.isEmpty()) ? "" : "method=" + method + "() ";
            // `c.executableLines ?? c.lines ?? '?'` — nullish, so a measured 0 stays 0
            Object lines0 = c.get("executableLines") != null ? c.get("executableLines") : c.get("lines");
            lines.add(Server.str(c.get("path")) + " | " + methodPart
                    + "coverage=" + Util.showMetric(Server.num(c.get("coverage"))) + "% "
                    + "mutation=" + Util.showMetric(Server.num(c.get("mutation"))) + "% "
                    + "mac=" + Util.showMetric(Server.num(c.get("mac"))) + " "
                    + "lines=" + Util.showMetric(Server.num(lines0)));
        }
        return String.join("\n", lines);
    }

    /// {@link Pick.Decision} on the wire.
    ///
    /// `retry` is written ONLY when the decision states one — the JS answer for a successful
    /// pick carries no `retry` key at all, and a `"retry": null` in its place would be a key the
    /// workflow has to know is not a `false`.
    static Map<String, Object> decision(Pick.Decision d) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("file", d.file());
        if (d.retry() != null) out.put("retry", d.retry());
        out.put("reason", d.reason());
        return out;
    }

    // ── check_changes: the mechanical gate first ───────────────────────────────────────────

    static final String CHECK_CHANGES_SYSTEM =
            "You review automated test changes against a team rule. Mechanical checks already"
            + " passed (suite green, MAC improved). Judge ONLY the team rule. Reply ONLY with JSON:"
            + " {\"approved\": true/false, \"reason\": \"one line\"}.";

    Object applyCheckChanges(String ruleText, Map<String, Object> ctx) {
        // Mechanical gate first: tests must be green and MAC must strictly improve.
        Double macAfter = Server.num(ctx.get("macAfter"));
        Double macBefore = Server.num(ctx.get("macBefore"));
        boolean testsGreen = State.jsTruthy(ctx.get("testsGreen"));
        // `(ctx.macAfter ?? 0) > (ctx.macBefore ?? 0)` — nullish, so an unmeasured "after" is 0
        // and does not read as an improvement over a null "before"
        boolean macImproved = Server.or0(macAfter) > Server.or0(macBefore);
        Map<String, Object> mech = new LinkedHashMap<>();
        mech.put("testsGreen", testsGreen);
        mech.put("macImproved", macImproved);
        if (!testsGreen || !macImproved) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("approved", false);
            out.put("reason", !testsGreen ? "test suite is red"
                    : "MAC did not improve (" + Util.showMetric(macBefore) + " → "
                      + Util.showMetric(macAfter) + ")");
            out.put("mechanical", mech);
            return out;
        }
        if (ruleText.isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("approved", true);
            out.put("reason", "MAC " + Util.showMetric(macBefore) + " → " + Util.showMetric(macAfter)
                    + ", suite green");
            out.put("mechanical", mech);
            return out;
        }
        Llm.Answer r = io.chat(Llm.Options.of()
                .withSystem(CHECK_CHANGES_SYSTEM)
                .withPrompt("TEAM RULE (how to check changes are good): " + ruleText
                        + "\n\nFILE: " + Server.str(ctx.get("file"))
                        + "\nMAC: " + Util.showMetric(macBefore) + " → " + Util.showMetric(macAfter)
                        + " (coverage " + Util.showMetric(Server.num(ctx.get("coverageBefore")))
                        + " → " + Util.showMetric(Server.num(ctx.get("coverageAfter")))
                        + ", mutation " + Util.showMetric(Server.num(ctx.get("mutationBefore")))
                        + " → " + Util.showMetric(Server.num(ctx.get("mutationAfter")))
                        + ")\n\nDIFF OF CHANGES:\n"
                        // `String(ctx.diff || '').slice(0, 12000)` — a whole branch diff would
                        // otherwise be most of the context window
                        + State.clip(nullToEmpty(Server.str(ctx.get("diff"))), 12000))
                .withJson(true)
                .withMaxTokens(800));
        Map<String, Object> j = Server.asMap(plain(r.json()));
        Map<String, Object> out = new LinkedHashMap<>();
        if (j == null) {
            out.put("approved", true);
            out.put("reason", "LLM verdict unparseable — mechanical checks passed");
        } else {
            out.putAll(j);
        }
        out.put("mechanical", mech);
        return out;
    }

    // ── make_pr: the body may state only what this run measured ────────────────────────────

    static final String MAKE_PR_SYSTEM =
            // The body may state ONLY what this run measured. A generated PR claimed "Tests pass
            // on Java 1.6 - 25" for work verified on one JDK — the reviewer's trust in every other
            // number on the page rests on none of them being invented.
            "You prepare a GitHub pull request for automated test improvements, following the team"
            + " rule for PR style."
            + " Reply ONLY with JSON: {\"title\": \"...\", \"body\": \"markdown\", \"labels\": [\"...\"]}."
            + " Include the before/after metrics table in the body, with the numbers given to you"
            + " and no others."
            + " State ONLY what the facts below establish. Do NOT claim compatibility with JDKs,"
            + " platforms or versions that are not listed, do not claim the change is backward"
            + " compatible, does not affect performance, or was reviewed — none of that was measured."
            + " A checklist item you cannot support from the facts below must be left out entirely"
            + " rather than ticked.";

    Object applyMakePr(String ruleText, Map<String, Object> ctx) {
        String file = Server.str(ctx.get("file"));
        String covBefore = Util.showMetric(Server.num(ctx.get("coverageBefore")));
        String covAfter = Util.showMetric(Server.num(ctx.get("coverageAfter")));
        String mutBefore = Util.showMetric(Server.num(ctx.get("mutationBefore")));
        String mutAfter = Util.showMetric(Server.num(ctx.get("mutationAfter")));
        String macBefore = Util.showMetric(Server.num(ctx.get("macBefore")));
        String macAfter = Util.showMetric(Server.num(ctx.get("macAfter")));
        String dfltTitle = "test: improve mutation-adjusted coverage of " + file;
        String dfltBody = String.join("\n",
                "## Test improvements for `" + file + "`", "",
                "| metric | before | after |", "|---|---|---|",
                "| line coverage | " + covBefore + "% | " + covAfter + "% |",
                "| mutation score | " + mutBefore + "% | " + mutAfter + "% |",
                "| **MAC** | **" + macBefore + "** | **" + macAfter + "** |", "",
                "Generated by the improve-javascript-tests pipeline.");
        if (ruleText.isEmpty()) return prAnswer(dfltTitle, dfltBody, List.of());

        Llm.Answer r = io.chat(Llm.Options.of()
                .withSystem(MAKE_PR_SYSTEM)
                .withPrompt("TEAM RULE (how to make a PR): " + ruleText
                        + "\n\nREPO DOCS:\n" + repoContextHead()
                        + "\n\nTHE FACTS — everything this run established, and the only things the body may assert:"
                        + "\nFILE: " + file + "\nBRANCH: " + Server.str(ctx.get("branch"))
                        + "\nMETRICS: coverage " + covBefore + "%→" + covAfter + "%, mutation "
                        + mutBefore + "%→" + mutAfter + "%, MAC " + macBefore + "→" + macAfter
                        + "\nTEST SUITE: green after the change"
                        + "\nVERIFIED ON: JDK " + orQuestion(io.runnerJdk("chosen"))
                        + ", " + orQuestion(io.runnerField("tool"))
                        + ", " + orQuestion(io.runnerField("testFramework"))
                        + " — and no other configuration"
                        + "\nCHANGED TEST FILES: " + String.join(", ", Server.strings(ctx.get("changedFiles"))))
                .withJson(true)
                .withMaxTokens(2000));
        Map<String, Object> j = Server.asMap(plain(r.json()));
        String title = j == null ? null : Server.str(j.get("title"));
        String body = j == null ? null : Server.str(j.get("body"));
        // falsy on both: an empty title is a PR with no title, which `gh` rejects outright
        if (title == null || title.isEmpty() || body == null || body.isEmpty()) {
            return prAnswer(dfltTitle, dfltBody, List.of());
        }
        // `if (!Array.isArray(j.labels)) j.labels = []` — the answer is handed to `gh` as argv
        if (!(j.get("labels") instanceof List)) j.put("labels", List.of());
        return j;
    }

    private static Map<String, Object> prAnswer(String title, String body, List<String> labels) {
        return mapOf("title", title, "body", body, "labels", labels);
    }

    /// `state.runner?.x ?? '?'` — what the run verified on, or an honest question mark.
    private static String orQuestion(Object v) {
        return v == null ? "?" : String.valueOf(v);
    }

    // ── the small conversions ──────────────────────────────────────────────────────────────

    /// A parsed answer as plain Java values — maps, lists, strings, numbers, booleans — because
    /// the result is stored in `state.decisions`, round-trips through state.json, and is read
    /// back as maps. A JsonNode would serialise identically and come back as something else.
    static Object plain(JsonNode node) {
        if (node == null || node.isNull()) return null;
        return MAPPER.convertValue(node, Object.class);
    }

    /// `JSON.stringify(result)` for the event line. A value that will not serialise costs the
    /// line its detail and nothing else — this is a log, and the decision itself is already
    /// recorded.
    static String json(Object v) {
        try {
            return MAPPER.writeValueAsString(v);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return String.valueOf(v);
        }
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) out.put(String.valueOf(kv[i]), kv[i + 1]);
        return out;
    }

    // ── the seam, wired to this backend ────────────────────────────────────────────────────

    /// The repo's own standing guidance, appended to the deployment's write_test rule.
    ///
    /// THE SHORT LOOP. Someone types "i don't like too many mocks in these tests" and the NEXT
    /// round's prompt carries it — no GEPA run, no redeploy, no waiting for a corpus to be big
    /// enough to optimise over. GEPA is the long loop over the same sentences; this is what makes
    /// them worth writing down before that exists.
    ///
    /// Appended rather than replacing, because the deployment's rule and the repo's guidance are
    /// different claims: the first is policy for every repo this instance improves, the second is
    /// what one team has learned about theirs. Guidance goes last so it wins a contradiction.
    ///
    /// Only `write_test` gets this. The other five stages decide WHICH file, WHETHER to open a
    /// PR and HOW to word it; "no mocks" is not an answer to any of them, and injecting it there
    /// would be noise in prompts that already fail for their own reasons.
    static String withRepoGuidance(String base) {
        List<String> guidance;
        try {
            // repoDir() throws when no run is configured — a prompt built outside a run is a
            // test's, and has no repo to read guidance from
            guidance = Feedback.guidance(Repo.repoDir());
        } catch (RuntimeException e) {
            return base;
        }
        if (guidance.isEmpty()) return base;
        StringBuilder sb = new StringBuilder(base == null ? "" : base);
        if (!sb.isEmpty()) sb.append("\n");
        sb.append("The team reviewing these tests has said:");
        for (String g : guidance) sb.append("\n- ").append(g);
        return sb.toString();
    }

    /// The real engine: the run's rules, the live store, the real repository and model.
    public static Rules forServer() {
        return new Rules(new Io() {
            public String rule(String stage) {
                State.Run run = State.STATE.run;
                State.Rules r = run == null ? null : run.config.rules();
                String base = r == null ? "" : nullToEmpty(r.get(stage));
                return "write_test".equals(stage) ? withRepoGuidance(base) : base;
            }

            public String readFileSafe(String rel, int maxLen) { return Repo.readFileSafe(rel, maxLen); }

            public Llm.Answer chat(Llm.Options opts) {
                try {
                    return Llm.shared().chat(opts);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                } catch (InterruptedException e) {
                    // restore the flag: a virtual thread that swallows it will not stop when the
                    // server is asked to
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while waiting for the model", e);
                }
            }

            public void event(String stage, String msg) { State.event(stage, msg); }

            public Map<String, Object> decisions() {
                synchronized (State.STATE) { return State.STATE.decisions; }
            }

            public void recordDecision(String stage, Map<String, Object> entry) {
                synchronized (State.STATE) { State.STATE.decisions.put(stage, entry); }
                State.save();
            }

            public int bumpPickFailures() {
                synchronized (State.STATE) {
                    // `(state.pickFailures || 0) + 1` — the field does not exist until it is
                    // assigned, and null is 0 here rather than an error
                    int next = (State.STATE.pickFailures == null ? 0 : State.STATE.pickFailures) + 1;
                    State.STATE.pickFailures = next;
                    return next;
                }
            }

            public void resetPickFailures() {
                synchronized (State.STATE) { State.STATE.pickFailures = 0; }
            }

            public Object runnerField(String name) {
                synchronized (State.STATE) {
                    return State.STATE.runner == null ? null : State.STATE.runner.get(name);
                }
            }

            public Object runnerJdk(String name) {
                Map<String, Object> jdk = Server.asMap(runnerField("jdk"));
                return jdk == null ? null : jdk.get(name);
            }

            public long nowMillis() { return System.currentTimeMillis(); }
        });
    }
}
