package tech.mikhailov.ijt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Typing "no mocks" changes the NEXT round's prompt.
///
/// The short loop. GEPA is the long one over the same sentences — a corpus, a reflection step, a
/// Pareto frontier — and it needs enough records to be worth running. This needs one sentence and
/// takes effect on the next unit. It is most of the value at a fraction of the cost, and it is
/// what makes writing the feedback down worth a person's time before any of the rest exists.
///
/// These assert through `Rules.forServer()`, which is what the pipeline calls. A test of
/// `Feedback.guidance` alone would pass with the rule reading nothing — this codebase has shipped
/// eight components that were correct and connected to nothing, so the wiring is the assertion.
class RepoGuidanceTest {

    private static final String REPO_URL = "https://example.invalid/o/r";

    private Path savedData;
    private State.Run savedRun;

    private Path repoWithGuidance(Path tmp, String... guidance) throws Exception {
        savedData = State.DATA_DIR;
        savedRun = State.STATE.run;
        State.DATA_DIR = tmp;
        State.STATE.run = State.freshRun(State.envConfig(k -> null), Map.of("repoUrl", REPO_URL));
        Path repo = Repo.repoDir();
        Files.createDirectories(repo.resolve(".git").resolve("info"));
        for (String g : guidance) Feedback.feedback(repo, REPO_URL, null, g, true, null);
        return repo;
    }

    @AfterEach
    void restore() {
        if (savedData != null) State.DATA_DIR = savedData;
        State.STATE.run = savedRun;
    }

    @Test
    void theWriteTestRuleCarriesTheRepoGuidance(@TempDir Path tmp) throws Exception {
        repoWithGuidance(tmp, "i don't like too many mocks in these tests");

        String rule = Rules.forServer().io().rule("write_test");
        assertTrue(rule.contains("too many mocks"),
                "the next prompt has to say it, or writing it down changed nothing. got: " + rule);
    }

    @Test
    void severalSentencesAllArriveNewestLast(@TempDir Path tmp) throws Exception {
        repoWithGuidance(tmp, "no mocks", "prefer real JSON documents over builders");

        String rule = Rules.forServer().io().rule("write_test");
        assertTrue(rule.indexOf("no mocks") < rule.indexOf("prefer real JSON"),
                "a later instruction should win a contradiction: " + rule);
    }

    @Test
    void theDeploymentsOwnRuleIsKeptNotReplaced(@TempDir Path tmp) throws Exception {
        // DEFAULT_RULES_WRITE_TEST is policy for every repo this instance improves; the guidance
        // is what one team learned about theirs. Both are true and neither replaces the other.
        savedData = State.DATA_DIR;
        savedRun = State.STATE.run;
        State.DATA_DIR = tmp;
        State.STATE.run = State.freshRun(
                State.envConfig(k -> "DEFAULT_RULES_WRITE_TEST".equals(k) ? "no reflection" : null),
                Map.of("repoUrl", REPO_URL));
        Path repo = Repo.repoDir();
        Files.createDirectories(repo.resolve(".git").resolve("info"));
        Feedback.feedback(repo, REPO_URL, null, "no mocks", true, null);

        String rule = Rules.forServer().io().rule("write_test");
        assertTrue(rule.contains("no reflection"), "the deployment's rule survives: " + rule);
        assertTrue(rule.contains("no mocks"), "and so does the team's: " + rule);
    }

    @Test
    void aRepoThatHasSaidNothingReadsExactlyAsBefore(@TempDir Path tmp) throws Exception {
        repoWithGuidance(tmp);   // no feedback at all
        assertEquals("", Rules.forServer().io().rule("write_test"));
    }

    @Test
    void feedbackNotMarkedApplyIsStoredButNotInjected(@TempDir Path tmp) throws Exception {
        // "this test asserts nothing" is worth keeping for GEPA and is not an instruction
        savedData = State.DATA_DIR;
        savedRun = State.STATE.run;
        State.DATA_DIR = tmp;
        State.STATE.run = State.freshRun(State.envConfig(k -> null), Map.of("repoUrl", REPO_URL));
        Path repo = Repo.repoDir();
        Files.createDirectories(repo.resolve(".git").resolve("info"));
        Feedback.feedback(repo, REPO_URL, null, "the mocks bother me", false, -1);

        assertFalse(Rules.forServer().io().rule("write_test").contains("bother"));
    }

    @Test
    void onlyTheWriteTestStageGetsIt(@TempDir Path tmp) throws Exception {
        // pick_file decides WHICH unit, make_pr decides how a PR is worded. "no mocks" is not an
        // answer to either, and putting it there is noise in prompts that fail for other reasons.
        repoWithGuidance(tmp, "no mocks");
        Rules.Io io = Rules.forServer().io();
        for (String stage : java.util.List.of("post_clone", "pre_pick", "pick_file", "check_changes", "make_pr")) {
            assertFalse(io.rule(stage).contains("no mocks"), stage + " should not carry it");
        }
    }

    @Test
    void aPromptBuiltWithNoRunAtAllStillWorks(@TempDir Path tmp) {
        // repoDir() throws without a configured run. A prompt built outside a run has no repo to
        // read guidance from, and must not blow up asking.
        savedRun = State.STATE.run;
        State.STATE.run = null;
        assertEquals("", Rules.forServer().io().rule("write_test"));
    }
}
