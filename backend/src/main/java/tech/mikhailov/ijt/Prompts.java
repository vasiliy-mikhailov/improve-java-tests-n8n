package tech.mikhailov.ijt;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/// Prompt construction. This used to live as JavaScript strings inside the workflow JSON,
/// where it had no compiler, no linter and no tests — and duly shipped defects that only an
/// hours-long run revealed: an identifier that was never defined, and an edit that silently
/// matched nothing so the deployed prompt kept asking the model to choose a mutant days
/// after that decision had moved into the pipeline.
///
/// The workflow now calls these over HTTP and stays orchestration-only.
public final class Prompts {

    private Prompts() {}

    /// What a generated Java test must satisfy to compile, run and be worth committing.
    public static final String COMMON_TEST_RULES = """

            - ONE test file only. Its public class name MUST equal the file name, and the package declaration MUST match the directory under src/test/java.
            - Use the SAME test framework and assertion library as the style reference (JUnit 5: org.junit.jupiter.api.Test/Assertions; JUnit 4: org.junit.Test/org.junit.Assert). Do NOT introduce a dependency the project does not already have.
            - Call only the PUBLIC API of the class under test. Never use reflection, setAccessible, or read source/bytecode.
            - Do NOT modify production code, existing tests, or the build files.
            - Deterministic: no network, no clock/randomness without fixing them, no reliance on file-system state or test execution order.
            - ISOLATED from every other test, including earlier ones this pipeline generated for the same class. If the class under test holds static, singleton or ThreadLocal state, reset it in the test itself (@Before/@BeforeEach, or explicitly at the top of the method) instead of assuming a fresh value. A test that passes alone and fails beside its siblings stops mutation testing dead: PIT runs all of them in one JVM against unmutated code first and refuses to proceed unless every one passes.
            - Every test must ASSERT a value or an observable side effect; a test that only checks "does not throw" is worthless.
            - Need an instance of an interface or abstract type to build the test? Prefer a CONCRETE implementation the project already ships (look for Simple*, NoOp*, Default*, *Impl, or a builder) over writing one. If you must implement one inline, override EVERY abstract method it declares — a partial anonymous class does not compile, and "is not abstract and does not override abstract method ..." costs the whole round.
            - Be TERSE. No javadoc, no explanatory prose, at most ONE short comment naming what the test pins down. Long answers get truncated before they finish, and a truncated answer is thrown away.
            - The tests MUST compile and PASS against the CURRENT implementation.""";

    /// PIT mutator → the assertion that detects it.
    public static final String MUTATOR_PLAYBOOK = """

            Mutator → the assertion that detects it:
            - ConditionalsBoundary (< ↔ <=, > ↔ >=): exercise the value EXACTLY at the boundary and assert the branch taken there.
            - NegateConditionals (== ↔ !=, < ↔ >=): assert behaviour on BOTH sides of the condition.
            - Math (+ ↔ -, * ↔ /, %): choose inputs where the two operations differ; assert the exact numeric result.
            - Increments (i++ ↔ i--): assert the final accumulated/counted value.
            - ReturnVals / NullReturnVals / PrimitiveReturnVals / BooleanTrueReturnVals / EmptyObjectReturnVals: assert the ACTUAL returned value or the returned object's contents — never merely that it is non-null.
            - VoidMethodCall / ConstructorCall: assert the observable SIDE EFFECT of the removed call.
            - RemoveConditional: assert that the guarded behaviour does NOT happen when the condition is false.
            - InlineConstant: assert the exact constant-derived value.
            - Switch: assert the outcome for each case label, including the default.""";

    /// The stage names the pipeline books this work under.
    public static final String STAGE_COVERAGE = "improving_coverage";
    public static final String STAGE_MUTATION = "improving_mutation";

    // ── the payloads ──────────────────────────────────────────────────────────

    /// A surviving mutant, as a PROMPT needs to describe it.
    ///
    /// [Targets.Mutant] carries only what TARGETING needs — method, line, mutator. A prompt
    /// also shows PIT's status, because SURVIVED and NO_COVERAGE need opposite instructions
    /// (the first line runs and nothing asserts on it; the second never runs at all), and
    /// PIT's own description of the mutation.
    ///
    /// @param status      `SURVIVED` or `NO_COVERAGE`; nullable and printed as-is
    /// @param method      the method the mutation sits in; nullable, printed as `?` when absent
    /// @param description PIT's sentence about the mutation; nullable
    public record Mutant(String status, String mutator, int line, String method, String description) {

        /// The same mutant as far as {@link Targets} is concerned.
        public Targets.Mutant forTargeting() { return new Targets.Mutant(method, line, mutator); }
    }

    /// One line of work and every mutation found on it — {@link Targets.Target}, keeping the
    /// fields only a prompt reads.
    public record Target(String marker, String method, int line, List<Mutant> mutants) {}

    /// Group survivors into one target per line, exactly as {@link Targets} does it.
    ///
    /// The grouping and the marker naming stay there and are not repeated here; this only
    /// re-attaches the status and description that {@link Targets.Mutant} does not carry.
    public static List<Target> groupTargets(List<Mutant> survivors) {
        List<Mutant> all = survivors == null ? List.of() : survivors;
        List<Target> out = new ArrayList<>();
        for (Targets.Target t : Targets.groupTargets(all.stream().map(Mutant::forTargeting).toList())) {
            out.add(new Target(t.marker(), t.method(), t.line(),
                    all.stream().filter(m -> Targets.targetMarker(m.forTargeting()).equals(t.marker())).toList()));
        }
        return out;
    }

    /// Which source lines of the method no test executes.
    ///
    /// `all` is its own field and not "a very long list": the JS spells it `{ lines: 'all' }`
    /// and every branch below asks which of the two it is before it counts anything.
    public record Uncovered(boolean all, List<Integer> lines) {

        /// The whole method — nothing executes it.
        public static Uncovered entireMethod() { return new Uncovered(true, List.of()); }

        public static Uncovered of(List<Integer> lines) { return new Uncovered(false, lines); }
    }

    /// What the last round on this unit actually achieved.
    ///
    /// Every field boxed. A first round has no last round at all, and `reached` is tested for
    /// FALSE specifically — "we do not know" must not read as "it did not reach the method",
    /// which is the branch that tells the model its entry point was wrong.
    ///
    /// @param coverage the coverage the last round measured; null is reported as 0, matching
    ///                 the JS `?? 0`
    public record LastRound(Boolean broken, String error, Boolean reached, Double coverage) {}

    /// The build/test output a repair is handed.
    public record Failure(String summary) {}

    /// One generated test file: where it goes and what is in it.
    public record TestFile(String path, String content) {}

    /// What the round put in front of the model, so the pipeline can mark it attempted.
    ///
    /// Two shapes in one record: {@link #mutationPrompt} offers MUTANTS (line, mutator,
    /// status) and {@link #batchPrompt} offers TARGETS (marker, line, method). Hence the
    /// nullable fields and the two factories — nothing else may fill in the other half.
    public record Offered(String marker, Integer line, String method, String mutator, String status) {

        public static Offered mutant(int line, String mutator, String status) {
            return new Offered(null, line, null, mutator, status);
        }

        public static Offered target(String marker, int line, String method) {
            return new Offered(marker, line, method, null, null);
        }
    }

    /// One model call — or the decision not to make it.
    ///
    /// @param skip null, never false, when the call IS to be made: the JS returns an object
    ///             with no `skip` key at all and callers test for its presence
    /// @param targetPath null on a repair, which rewrites files it was handed rather than
    ///                   naming a new one
    public record Ask(String targetPath, String projectTestPath, Boolean skip, String reason,
                      String system, String prompt, Boolean json, Integer maxTokens, Double temperature,
                      String stage, String stageDetail, List<Offered> offered) {

        static Ask skipped(String targetPath, String projectTestPath, String reason) {
            return new Ask(targetPath, projectTestPath, true, reason,
                    null, null, null, null, null, null, null, null);
        }

        static Ask call(String targetPath, String projectTestPath, String system, String prompt,
                        int maxTokens, double temperature, String stage, String stageDetail,
                        List<Offered> offered) {
            return new Ask(targetPath, projectTestPath, null, null, system, prompt, true,
                    maxTokens, temperature, stage, stageDetail, offered);
        }
    }

    /// Everything the prompts know about one unit of work.
    ///
    /// Boxed and nullable nearly throughout: this arrives as JSON from the workflow, most of
    /// it optional, and several fields are load-bearing precisely BECAUSE they can be absent.
    /// `coverage` null means "not measured", which is not 0% and must not skip the coverage
    /// phase; `covPhaseMaxPct` null means the JS `?? 0` default rather than a configured zero;
    /// `visibility` null means "not determined", which is not "private".
    ///
    /// @param pkg          `package` is a Java keyword — this is the JS `gaps.package`.
    /// @param targetMutant what the round aimed at, reusing {@link RoundOutcome.Mutant}: a
    ///                     mutator and a line is all a repair needs to be told.
    public record Gaps(String fqcn, String path, String module, Integer jdk, String pkg,
                       String method, Integer methodLine, String testFramework,
                       String source, String methodSource, String classHeader, List<String> siblingSignatures,
                       String visibility, List<List<JavaSrc.MethodRef>> routes,
                       Double coverage, Double covPhaseMaxPct, Uncovered uncovered,
                       List<Mutant> survived, Integer attemptedMutants, Integer mutantsPerRound,
                       String covTestPath, String mutTestPath, String projectTestPath, String existingTest,
                       List<String> constraints, LastRound lastRound, RoundOutcome.Mutant targetMutant) {

        public static Builder builder() { return new Builder(); }

        /// The JS `{ ...gaps, coverage: 0 }`: a copy with a few fields changed.
        public Builder toBuilder() {
            return new Builder()
                    .fqcn(fqcn).path(path).module(module).jdk(jdk).pkg(pkg)
                    .method(method).methodLine(methodLine).testFramework(testFramework)
                    .source(source).methodSource(methodSource).classHeader(classHeader)
                    .siblingSignatures(siblingSignatures).visibility(visibility).routes(routes)
                    .coverage(coverage).covPhaseMaxPct(covPhaseMaxPct).uncovered(uncovered)
                    .survived(survived).attemptedMutants(attemptedMutants).mutantsPerRound(mutantsPerRound)
                    .covTestPath(covTestPath).mutTestPath(mutTestPath).projectTestPath(projectTestPath)
                    .existingTest(existingTest).constraints(constraints).lastRound(lastRound)
                    .targetMutant(targetMutant);
        }

        /// Twenty-seven optional fields do not make a readable constructor call, and a
        /// positional one invites the silent swap of two adjacent strings.
        public static final class Builder {
            private String fqcn, path, module, pkg, method, testFramework, source, methodSource, classHeader,
                    visibility, covTestPath, mutTestPath, projectTestPath, existingTest;
            private Integer jdk, methodLine, attemptedMutants, mutantsPerRound;
            private Double coverage, covPhaseMaxPct;
            private List<String> siblingSignatures, constraints;
            private List<List<JavaSrc.MethodRef>> routes;
            private Uncovered uncovered;
            private List<Mutant> survived;
            private LastRound lastRound;
            private RoundOutcome.Mutant targetMutant;

            public Builder fqcn(String v) { this.fqcn = v; return this; }
            public Builder path(String v) { this.path = v; return this; }
            public Builder module(String v) { this.module = v; return this; }
            public Builder jdk(Integer v) { this.jdk = v; return this; }
            public Builder pkg(String v) { this.pkg = v; return this; }
            public Builder method(String v) { this.method = v; return this; }
            public Builder methodLine(Integer v) { this.methodLine = v; return this; }
            public Builder testFramework(String v) { this.testFramework = v; return this; }
            public Builder source(String v) { this.source = v; return this; }
            public Builder methodSource(String v) { this.methodSource = v; return this; }
            public Builder classHeader(String v) { this.classHeader = v; return this; }
            public Builder siblingSignatures(List<String> v) { this.siblingSignatures = v; return this; }
            public Builder visibility(String v) { this.visibility = v; return this; }
            public Builder routes(List<List<JavaSrc.MethodRef>> v) { this.routes = v; return this; }
            public Builder coverage(Double v) { this.coverage = v; return this; }
            public Builder covPhaseMaxPct(Double v) { this.covPhaseMaxPct = v; return this; }
            public Builder uncovered(Uncovered v) { this.uncovered = v; return this; }
            public Builder survived(List<Mutant> v) { this.survived = v; return this; }
            public Builder attemptedMutants(Integer v) { this.attemptedMutants = v; return this; }
            public Builder mutantsPerRound(Integer v) { this.mutantsPerRound = v; return this; }
            public Builder covTestPath(String v) { this.covTestPath = v; return this; }
            public Builder mutTestPath(String v) { this.mutTestPath = v; return this; }
            public Builder projectTestPath(String v) { this.projectTestPath = v; return this; }
            public Builder existingTest(String v) { this.existingTest = v; return this; }
            public Builder constraints(List<String> v) { this.constraints = v; return this; }
            public Builder lastRound(LastRound v) { this.lastRound = v; return this; }
            public Builder targetMutant(RoundOutcome.Mutant v) { this.targetMutant = v; return this; }

            public Gaps build() {
                return new Gaps(fqcn, path, module, jdk, pkg, method, methodLine, testFramework,
                        source, methodSource, classHeader, siblingSignatures, visibility, routes,
                        coverage, covPhaseMaxPct, uncovered, survived, attemptedMutants, mutantsPerRound,
                        covTestPath, mutTestPath, projectTestPath, existingTest, constraints,
                        lastRound, targetMutant);
            }
        }
    }

    // ── the blocks every prompt is assembled from ─────────────────────────────

    static String frameworkName(String f) {
        return "junit4".equals(f) ? "JUnit 4" : "testng".equals(f) ? "TestNG" : "JUnit 5";
    }

    /// The source the model needs: package + imports + class declaration, the target method in
    /// full, and sibling signatures for building inputs. Sending the whole class instead is
    /// what made every call slow — 12k characters of a 1500-line file to produce a 400-byte test.
    static String sourceBlock(Gaps gaps, int limit) {
        if (empty(gaps.methodSource())) return cut(gaps.source(), limit);
        return s(gaps.classHeader()) + "\n\n  // … other members omitted …\n\n" + gaps.methodSource();
    }

    static String signatureBlock(Gaps gaps) {
        List<String> sigs = gaps.siblingSignatures() == null ? List.of() : gaps.siblingSignatures();
        return sigs.isEmpty()
                ? ""
                : "\n\nOTHER MEMBERS (signatures only, for building inputs):\n"
                        + String.join("\n", sigs.subList(0, Math.min(40, sigs.size())));
    }

    /// A test cannot call a private (or package-private, from another package) method, and
    /// reflection is forbidden — so it has to arrive through something public. Told none of
    /// this, the model wrote a compiling, passing test for JSONObject's private
    /// isRecordStyleAccessor that executed none of it: coverage 0 → 0, all 14 mutants alive.
    static String reachBlock(Gaps gaps) {
        String vis = gaps.visibility();
        if (empty(vis) || vis.equals("public")) return "";
        List<List<JavaSrc.MethodRef>> routes = gaps.routes() == null ? List.of() : gaps.routes();
        if (routes.isEmpty()) return "";
        // the whole path, in the order a test travels it: public entry → private hops → target.
        // Naming only the direct caller pointed the model at another method it could not call.
        // the full declaration of each hop, not just its name: "call nextEntity()" when the
        // method is nextEntity(char) yields a test that does not compile, and six rounds on
        // XMLTokener#isValidDecimal were spent that way
        List<String> rendered = new ArrayList<>();
        for (List<JavaSrc.MethodRef> route : routes.subList(0, Math.min(3, routes.size()))) {
            List<String> hops = new ArrayList<>();
            for (int i = 0; i < route.size(); i++) {
                JavaSrc.MethodRef step = route.get(i);
                hops.add((empty(step.signature()) ? s(step.method()) + "()" : step.signature())
                        + (i == 0 ? "  [public — call this]" : ""));
            }
            rendered.add("- " + String.join("\n    → ", hops));
        }
        String list = String.join("\n", rendered);
        return "\n\nREACHED VIA: " + s(gaps.method()) + "() is " + vis
                + ", so a test CANNOT call it directly and must NOT use reflection."
                + " Reach it along this path, choosing arguments that make execution flow all the way through:\n" + list
                + "\nAssert on what the public call returns or changes — that is what distinguishes the real code from the mutation.";
    }

    /// A private method nothing calls cannot be executed by any test.
    static boolean unreachable(Gaps gaps) {
        String vis = gaps.visibility();
        return !empty(vis) && !vis.equals("public") && (gaps.routes() == null || gaps.routes().isEmpty());
    }

    /// What the last round on this unit actually achieved — the difference between "your test
    /// never ran this method" and "it ran it but asserted nothing that distinguishes the
    /// mutation". Those need opposite fixes, and the pipeline reported both as the second one:
    /// on JSONObject#isRecordStyleAccessor, called under `if (isRecordType && ...)`, the model
    /// built a plain bean, the guard was false, the method never executed — and round after
    /// round it rewrote assertions for code it had never reached.
    static String lastRoundBlock(Gaps gaps) {
        LastRound lr = gaps.lastRound();
        if (lr == null) return "";
        // Three outcomes, three different fixes — and this block had two branches. A test that
        // broke the suite is repaired once and then deleted, and the next round was told "the
        // path is right; the assertion is not": advice about an assertion in a file that never
        // compiled. CDL#getValue and JSONObject#parseEndOfKeyValuePair each spent their whole
        // miss budget on that.
        if (Boolean.TRUE.equals(lr.broken())) {
            return "\n\nYOUR PREVIOUS ATTEMPT: the test DID NOT COMPILE — it broke the build and was discarded,"
                    + " so nothing about it was measured."
                    + (empty(lr.error()) ? " " : " The build said:\n" + cut(lr.error(), 600) + "\n")
                    + "Do not refine the assertion; the file never ran. Write a SIMPLER test that you are certain"
                    + " compiles: call only methods and constructors you can see in the source above, with exactly"
                    + " the argument types their signatures declare, and import only what you use.";
        }
        if (Boolean.FALSE.equals(lr.reached())) {
            // a round that measured nothing reports 0, as the JS `?? 0` does — and showMetric
            // prints it 0 and not 0.0
            Double cov = lr.coverage() == null ? Double.valueOf(0) : lr.coverage();
            return "\n\nYOUR PREVIOUS ATTEMPT: the test compiled and passed, but it NEVER EXECUTED this method"
                    + " — coverage stayed at " + Util.showMetric(cov) + "%."
                    + " The entry point you chose does not flow into it."
                    + " Look at the conditions guarding the call path above and choose inputs that satisfy every one of them;"
                    + " if no input can satisfy them, return an empty tests array and say nothing was written.";
        }
        return "\n\nYOUR PREVIOUS ATTEMPT: the test reached and executed this method, but asserted nothing that"
                + " distinguishes the mutation from the real code. The path is right; the assertion is not.";
    }

    static String constraintBlock(Gaps gaps) {
        List<String> cs = gaps.constraints() == null ? List.of() : gaps.constraints();
        StringBuilder sb = new StringBuilder();
        for (String x : cs) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("- ").append(s(x));
        }
        String c = sb.toString();
        return c.isEmpty() ? "" : "\nTeam constraints:\n" + c;
    }

    // ── the three phases ──────────────────────────────────────────────────────

    /// Coverage phase — only for a method NOTHING executes. Above `covPhaseMaxPct` the mutation
    /// phase raises coverage by itself: killing a NO_COVERAGE mutant means running its line.
    public static Ask coveragePrompt(Gaps gaps) {
        String targetPath = gaps.covTestPath();
        Uncovered u = gaps.uncovered();
        boolean fullyUncovered = u != null && u.all();
        List<Integer> uLines = (u == null || u.lines() == null) ? List.of() : u.lines();
        // the JS Infinity: "the whole method" is never zero missed lines, and the value is only
        // ever compared against zero
        int missed = fullyUncovered ? Integer.MAX_VALUE : uLines.size();
        String projectTestPath = empty(gaps.projectTestPath()) ? null : gaps.projectTestPath();

        if (missed == 0) return Ask.skipped(targetPath, projectTestPath, "method fully covered");
        // "mutation tests will extend coverage as they kill NO_COVERAGE mutants" is a fair
        // general claim and it was measured false where it matters most. DataLoaderFactory's
        // two Publisher factories each had 6 mutants, every one NO_COVERAGE, at 16.67%
        // coverage: seven rounds apiece, coverage never off 16.67, mutation never off 0, about
        // fifteen minutes each. Killing a NO_COVERAGE mutant does require running its line —
        // which is exactly why "assert something that distinguishes this mutation" is the wrong
        // instruction for a line no test reaches. Ask for the line to be REACHED first.
        List<Mutant> survivors = gaps.survived() == null ? List.of() : gaps.survived();
        boolean noneReached = !survivors.isEmpty()
                && survivors.stream().allMatch(m -> "NO_COVERAGE".equals(m.status()));
        if (!fullyUncovered && !noneReached && gaps.coverage() != null
                && gaps.coverage() > (gaps.covPhaseMaxPct() == null ? 0.0 : gaps.covPhaseMaxPct())) {
            return Ask.skipped(targetPath, projectTestPath,
                    "method already executed (" + Util.showMetric(gaps.coverage()) + "% covered)"
                            + " — mutation tests will extend coverage as they kill NO_COVERAGE mutants");
        }

        if (unreachable(gaps)) {
            return Ask.skipped(targetPath, projectTestPath, s(gaps.method()) + "() is " + s(gaps.visibility())
                    + " and no public method of this class reaches it — no test can execute it,"
                    + " so there is nothing to write");
        }

        String testClass = className(targetPath);
        String system = "You are an expert Java test engineer writing the FIRST test for ONE METHOD that no test executes yet"
                + (empty(gaps.method()) ? "" : " (" + gaps.method() + "())")
                + ", in " + frameworkName(gaps.testFramework()) + "."
                + " Reply ONLY with JSON: {\"tests\":[{\"path\":\"...\",\"content\":\"full test file content\"}]}."
                + " Create a NEW test class only. Required file path: " + s(targetPath)
                + " (package " + s(gaps.pkg()) + ", public class " + testClass + "). Rules:" + COMMON_TEST_RULES
                + constraintBlock(gaps);
        String prompt = "CLASS UNDER TEST: " + s(gaps.fqcn()) + "  (file " + s(gaps.path())
                + ", module " + s(gaps.module()) + ", JDK " + n(gaps.jdk()) + ")\n"
                + (empty(gaps.method()) ? ""
                        : "TARGET METHOD: " + gaps.method() + "()"
                                + (truthy(gaps.methodLine()) ? " (around line " + gaps.methodLine() + ")" : "")
                                + " — the tests must exercise THIS method; coverage and mutation score are measured on it alone.\n")
                + sourceBlock(gaps, 14000) + signatureBlock(gaps) + reachBlock(gaps) + lastRoundBlock(gaps)
                + "\n\nUNCOVERED: " + (fullyUncovered
                        ? "THE ENTIRE METHOD (no test executes it at all)"
                        : "source lines " + jsonNumbers(uLines.subList(0, Math.min(140, uLines.size()))))
                + "\n\nEXISTING TEST (style reference — imports, assertion library, conventions; do NOT rewrite it):\n"
                + cut(empty(gaps.existingTest()) ? "(none)" : gaps.existingTest(), 6000)
                + "\n\nWrite the SIMPLEST test class that executes the uncovered lines of the target method:"
                + " 2-4 short test methods, straightforward inputs, direct assertions. This is only the first pass"
                + " — later rounds add the sharp, mutation-killing assertions, so do NOT try to be exhaustive."
                + " A small test that compiles and passes is worth far more than a large one that does not. JSON only.";

        return Ask.call(targetPath, projectTestPath, system, prompt, 3000, 0.2,
                STAGE_COVERAGE, "writing a first, simple test", null);
    }

    /// Mutation phase — the pipeline has already chosen the target (survivors arrive ranked by
    /// PIT's data and this run's kill record). The model is handed that one mutant and writes
    /// one short test for it.
    public static Ask mutationPrompt(Gaps gaps) {
        String targetPath = gaps.mutTestPath();
        int perRound = truthy(gaps.mutantsPerRound()) ? gaps.mutantsPerRound() : 1;
        boolean single = perRound == 1;
        List<Mutant> survived = gaps.survived() == null ? List.of() : gaps.survived();
        List<Mutant> choices = survived.subList(0, Math.max(0, Math.min(perRound, survived.size())));
        String projectTestPath = empty(gaps.projectTestPath()) ? null : gaps.projectTestPath();

        if (unreachable(gaps)) {
            return Ask.skipped(targetPath, projectTestPath, s(gaps.method()) + "() is " + s(gaps.visibility())
                    + " and no public method of this class reaches it — no test can execute it,"
                    + " so there is nothing to write");
        }
        if (choices.isEmpty()) {
            return Ask.skipped(targetPath, projectTestPath,
                    truthy(gaps.attemptedMutants())
                            ? "every remaining survivor has already been attempted (" + gaps.attemptedMutants() + " tried)"
                            : "no surviving mutants");
        }

        String testClass = className(targetPath);
        StringBuilder mutants = new StringBuilder();
        for (int i = 0; i < choices.size(); i++) {
            Mutant m = choices.get(i);
            if (i > 0) mutants.append('\n');
            mutants.append('#').append(i + 1).append(" [").append(s(m.status())).append("] ").append(s(m.mutator()))
                    .append(" at line ").append(m.line())
                    .append(" in method ").append(empty(m.method()) ? "?" : m.method()).append("() — ")
                    .append(s(m.description()));
        }

        String system = "You are an expert Java test engineer killing surviving PIT mutants of ONE METHOD,"
                + " one at a time, writing " + frameworkName(gaps.testFramework()) + " tests."
                + " A mutant is killed when at least one test FAILS on the mutated code while PASSING on the real code"
                + " — so the test must assert something that DISTINGUISHES the two."
                + " Reply ONLY with JSON: {\"tests\":[{\"path\":\"...\",\"content\":\"full test file content\"}]}."
                + " Create a NEW test class only. Required file path: " + s(targetPath)
                + " (package " + s(gaps.pkg()) + ", public class " + testClass + "). Rules:"
                + COMMON_TEST_RULES + MUTATOR_PLAYBOOK
                + constraintBlock(gaps);

        String prompt = "CLASS UNDER TEST: " + s(gaps.fqcn()) + "  (file " + s(gaps.path())
                + ", module " + s(gaps.module()) + ")\n"
                + sourceBlock(gaps, 12000) + signatureBlock(gaps) + reachBlock(gaps) + lastRoundBlock(gaps)
                + (empty(gaps.method()) ? ""
                        : "\n\nFOCUS: this unit of work IS the method " + gaps.method() + "()"
                                + " — shown in full above; the mutant below is inside it,"
                                + " and the score is measured on it alone.")
                + "\n\n" + (single ? "MUTANT TO KILL" : "SURVIVING MUTANTS")
                + " (SURVIVED = the line runs but nothing asserts on it; NO_COVERAGE = the line never runs):\n"
                + mutants
                + "\n\nEXISTING TEST (style reference — do NOT rewrite it):\n"
                + cut(empty(gaps.existingTest()) ? "(none)" : gaps.existingTest(), 4000)
                + (single
                        ? "\n\nKILL THE MUTANT ABOVE — it was selected for you. Write ONE test class with ONE short"
                                + " test method: call the method with inputs that reach that line and assert the value"
                                + " or side effect that DIFFERS between the real code and the mutation. If the mutation"
                                + " cannot change any observable behaviour, return an empty tests array rather than a"
                                + " test that cannot fail. Another round follows for the next mutant. JSON only."
                        : "\n\nWrite one FOCUSED test class: one short test method per mutant above, each with the"
                                + " single assertion that distinguishes the real code from that mutation. JSON only.");

        List<Offered> offered = choices.stream()
                .map(m -> Offered.mutant(m.line(), m.mutator(), m.status())).toList();
        // 2500 was marginal on real prompts: measured truncations at that ceiling with
        // thinking OFF and no reasoning at all, which throws the whole call away and pays for
        // it again at double. A ceiling is not a spend — an answer that fits in 900 tokens
        // still costs 900.
        return Ask.call(targetPath, projectTestPath, system, prompt, single ? 4000 : 6000, 0.25,
                STAGE_MUTATION,
                single ? "killing " + s(choices.get(0).mutator()) + " at line " + choices.get(0).line()
                       : "writing mutant-killing tests",
                offered);
    }

    /// Repair — the tests we just wrote do not compile or do not pass.
    ///
    /// The repair must not cost the round its purpose. A test written to kill
    /// BooleanTrueReturnVals asserted the mutant's absence, failed against real behaviour, and
    /// the repair simply flipped assertFalse to assertTrue: a passing test that distinguishes
    /// nothing, and a round that missed. Correcting a wrong expectation is right; asserting
    /// the behaviour the MUTATION would produce is worthless.
    public static Ask repairPrompt(Gaps gaps, Failure failure, List<TestFile> tests, String stage) {
        StringBuilder files = new StringBuilder();
        for (TestFile t : tests == null ? List.<TestFile>of() : tests) {
            if (files.length() > 0) files.append("\n\n---\n\n");
            files.append("PATH: ").append(s(t.path())).append('\n').append(cut(t.content(), 6000));
        }
        RoundOutcome.Mutant tm = gaps.targetMutant();
        String system = "You are an expert Java test engineer. Tests you previously wrote FAIL to compile or fail"
                + " against the current implementation. Fix them. Keep the SAME file path and class name."
                + " Reply ONLY with JSON: {\"tests\":[{\"path\":\"...\",\"content\":\"full corrected file content\"}]}."
                + " Compilation errors: fix imports, types, visibility and constructor/method signatures against the source shown."
                + " Assertion failures: correct the EXPECTED values to match the real behaviour of the source"
                + " — never weaken an assertion to make it pass trivially."
                + (tm == null ? ""
                        : " The corrected test MUST STILL DISTINGUISH the mutant named below: it has to pass on the"
                                + " real code AND still fail on the mutated code."
                                + " Flipping an assertion so that it asserts what the MUTATION would produce makes the"
                                + " test pass and kills nothing —"
                                + " if the test cannot be both correct and distinguishing, drop it and return an empty tests array.")
                + " If a test cannot be fixed, drop it from the output.";
        String prompt = "BUILD / TEST OUTPUT (failures):\n"
                + cut(failure == null ? "" : failure.summary(), 4000)
                + (tm == null ? ""
                        : "\n\nMUTANT THIS TEST MUST STILL KILL: " + s(tm.mutator()) + " at line " + tm.line()
                                + " — the test has to PASS on the real code and FAIL on that mutation."
                                + " A test that passes on both is worth nothing.")
                + "\n\nYOUR TEST FILE(S):\n" + files
                + "\n\nCLASS UNDER TEST " + s(gaps.fqcn()) + " (" + s(gaps.path())
                + (empty(gaps.method()) ? "" : ", method " + gaps.method() + "()") + "):\n"
                + sourceBlock(gaps, 10000)
                + "\n\nReply with corrected JSON now.";
        return new Ask(null, null, null, null, system, prompt, true, 7000, 0.2,
                stage, "repairing failing generated tests", null);
    }

    /// The JS default argument: a repair belongs to the mutation phase unless told otherwise.
    public static Ask repairPrompt(Gaps gaps, Failure failure, List<TestFile> tests) {
        return repairPrompt(gaps, failure, tests, STAGE_MUTATION);
    }

    /// One call for every surviving LINE of a class, measured once afterwards.
    ///
    /// The per-round loop asks about one mutant, re-measures, and repeats: two PIT runs per
    /// round per method-unit, 609 across 22 classes on one JSON-java run, 43% of its
    /// wall-clock, most of it re-measuring code that did not change.
    ///
    /// Here the model is handed every target at once and told the EXACT method name to give
    /// each test. That name is derived from method and line by {@link Targets}, so the next run
    /// can see which lines already have a test with a string check instead of a model call —
    /// 1000 mutants at ~100 tokens and 30 tokens/sec is not a loop worth entering twice.
    ///
    /// @param gaps    the usual gaps payload for the CLASS
    /// @param targets from {@link #groupTargets}, already filtered by pending targets
    public static Ask batchPrompt(Gaps gaps, List<Target> targets) {
        String targetPath = gaps.mutTestPath();
        String projectTestPath = empty(gaps.projectTestPath()) ? null : gaps.projectTestPath();
        List<Target> list = targets == null ? List.of() : targets;
        if (list.isEmpty()) {
            return Ask.skipped(targetPath, projectTestPath, "no targets left to write — nothing to ask");
        }

        String testClass = className(targetPath);
        StringBuilder block = new StringBuilder();
        for (Target t : list) {
            if (block.length() > 0) block.append('\n');
            Set<String> kinds = new LinkedHashSet<>();
            for (Mutant m : t.mutants()) {
                kinds.add(s(m.mutator()) + ("NO_COVERAGE".equals(m.status()) ? " [NO_COVERAGE]" : ""));
            }
            String uniq = String.join(", ", kinds);
            boolean never = t.mutants().stream().allMatch(m -> "NO_COVERAGE".equals(m.status()));
            String description = t.mutants().isEmpty() ? null : t.mutants().get(0).description();
            block.append("- ").append(s(t.method())).append("() line ").append(t.line())
                 .append("   → first line of the test body: // ").append(s(t.marker()))
                 .append("\n    mutations on that line: ").append(uniq);
            if (never) {
                block.append("\n    NOTE: this line NEVER RUNS in the current suite — the test must first REACH it.");
            }
            if (!empty(description)) block.append("\n    e.g. ").append(description);
        }

        String system = "You are an expert Java test engineer writing " + frameworkName(gaps.testFramework())
                + " tests that kill surviving PIT mutants."
                + " A mutant dies when a test FAILS on the mutated code and PASSES on the real code,"
                + " so every test must assert something that DISTINGUISHES the two."
                + " Reply ONLY with JSON: {\"tests\":[{\"path\":\"...\",\"content\":\"full test file content\"}]}."
                + " ONE file: " + s(targetPath) + " (package " + s(gaps.pkg()) + ", public class " + testClass + ")."
                + " NAME each test the way the project's own tests are named — look at the style reference below"
                + " and follow it (a short description of the case, e.g. emptyStringCookieList, clearCacheOnError)."
                + " Do NOT invent a scheme of your own."
                + " Then make the FIRST line of each test body the exact marker comment given for its target,"
                + " e.g. \"// covers mustEscape:170\". That comment is how the pipeline knows the line is covered;"
                + " a test without it is written again next run."
                + " Skip any target you cannot kill honestly rather than writing a test that cannot fail."
                + " Rules:" + COMMON_TEST_RULES + MUTATOR_PLAYBOOK
                + constraintBlock(gaps);

        String prompt = "CLASS UNDER TEST: " + s(gaps.fqcn()) + "  (file " + s(gaps.path())
                + ", module " + s(gaps.module()) + ")\n"
                + sourceBlock(gaps, 14000) + signatureBlock(gaps) + reachBlock(gaps) + lastRoundBlock(gaps)
                + "\n\nTARGETS — one test method each, named in the project's style,"
                + " each beginning with its marker comment:\n" + block
                + "\n\nEXISTING TEST (style reference — do NOT rewrite it):\n"
                + cut(empty(gaps.existingTest()) ? "(none)" : gaps.existingTest(), 3000)
                + "\n\nWrite ONE test class containing one short test method per target above."
                + " Name them as this project names its tests; give each the marker comment as its first body line."
                + " JSON only.";

        // ~450 tokens buys a short test method with its imports; the floor covers the class
        // shell and the ceiling is the model's hard limit, not an aspiration
        int maxTokens = Math.min(12000, Math.max(2000, 700 + list.size() * 450));
        List<Offered> offered = list.stream()
                .map(t -> Offered.target(t.marker(), t.line(), t.method())).toList();
        return Ask.call(targetPath, projectTestPath, system, prompt, maxTokens, 0.25,
                STAGE_MUTATION, "writing " + list.size() + " mutant-killing test(s)", offered);
    }

    // ── small helpers ─────────────────────────────────────────────────────────

    /// JS falsiness for a string: absent and empty are the same answer here.
    private static boolean empty(String v) { return v == null || v.isEmpty(); }

    /// JS falsiness for a count: absent and zero are the same answer here.
    private static boolean truthy(Integer v) { return v != null && v != 0; }

    /// An absent field interpolates as the word `undefined` in JS. Nothing at all is the
    /// better prompt, and `null` — what Java string concatenation would print — is the worse.
    private static String s(String v) { return v == null ? "" : v; }

    private static String n(Integer v) { return v == null ? "" : String.valueOf(v); }

    /// The JS `String(x).slice(0, n)`.
    private static String cut(String v, int n) {
        String x = s(v);
        return x.length() <= n ? x : x.substring(0, n);
    }

    /// `src/test/java/a/BMacMutTest.java` → `BMacMutTest`.
    private static String className(String targetPath) {
        String p = s(targetPath);
        return p.substring(p.lastIndexOf('/') + 1).replaceFirst("\\.java$", "");
    }

    /// `JSON.stringify([120, 121])` — no spaces between the numbers, and `[]` for none.
    private static String jsonNumbers(List<Integer> nums) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < nums.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(nums.get(i));
        }
        return sb.append(']').toString();
    }
}
