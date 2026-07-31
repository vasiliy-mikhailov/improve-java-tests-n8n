package tech.mikhailov.ijt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The repo's own record of what it was asked, what it produced, and what a human thought.
///
/// Append-only JSONL at the root of the repo being improved. Three kinds of line — attempt,
/// outcome, feedback — joined on read. Append rather than a rewritten JSON document because a run
/// has generation and repair in flight against one file, and read-modify-write loses records
/// under exactly that.
class FeedbackTest {

    private static final String REPO_URL = "https://github.com/stleary/JSON-java";
    private static final String UNIT = "src/main/java/org/json/XML.java::escape";
    private static final String ROUND = "run-1|" + UNIT + "|a1|r3";

    private static Path gitRepo(Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".git").resolve("info"));
        return dir;
    }

    private static List<Map<String, Object>> tests() {
        return List.of(Map.of("path", "src/test/java/org/json/XMLEscapeMacMutTest.java",
                "content", "class XMLEscapeMacMutTest { }"));
    }

    // ── the record GEPA needs ─────────────────────────────────────────────

    @Test
    void anAttemptKeepsTheRuleThatProducedTheTest(@TempDir Path tmp) throws Exception {
        // THE candidate. The rendered prompt is mostly the source under test — the same fourteen
        // kilobytes every round — while the rule is the line that varies and the line GEPA
        // rewrites. A record that cannot be attributed to a rule teaches nothing.
        Path repo = gitRepo(tmp);
        Feedback.attempt(repo, REPO_URL, ROUND, UNIT,
                Map.of("methodSource", "static String escape(String s) { … }"),
                tests(), List.of("no mocks; prefer real objects"));

        Map<String, Object> a = Feedback.join(repo).get(0);
        assertEquals(List.of("no mocks; prefer real objects"), a.get("writeTestRule"));
        assertEquals(UNIT, a.get("unit"));
        assertTrue(String.valueOf(((Map<?, ?>) a.get("code")).get("methodSource")).contains("escape"));
        assertEquals(1, ((List<?>) a.get("tests")).size());
    }

    @Test
    void anOutcomeClosesTheAttemptsOfItsRound(@TempDir Path tmp) throws Exception {
        Path repo = gitRepo(tmp);
        Feedback.attempt(repo, REPO_URL, ROUND, UNIT, Map.of(), tests(), List.of("no mocks"));
        Feedback.outcome(repo, REPO_URL, ROUND, UNIT, "kept",
                Map.of("mac", 4.94), Map.of("mac", 30.0), false);

        Map<String, Object> a = Feedback.join(repo).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) a.get("outcome");
        assertEquals("kept", out.get("verdict"));
        assertEquals(30.0, ((Map<?, ?>) out.get("after")).get("mac"));
    }

    @Test
    void generationAndRepairAreTwoAttemptsAndOneOutcome(@TempDir Path tmp) throws Exception {
        // Phase writes, the suite goes red, the repair rewrites, then ONE verify judges the round.
        // Both attempts belong to that verdict; crediting only the last would hide that the first
        // needed repairing at all.
        Path repo = gitRepo(tmp);
        Feedback.attempt(repo, REPO_URL, ROUND, UNIT, Map.of(), tests(), List.of("no mocks"));
        Feedback.attempt(repo, REPO_URL, ROUND, UNIT, Map.of(), tests(), List.of("no mocks"));
        Feedback.outcome(repo, REPO_URL, ROUND, UNIT, "missed", Map.of(), Map.of(), false);

        List<Map<String, Object>> joined = Feedback.join(repo);
        assertEquals(2, joined.size());
        assertTrue(joined.stream().allMatch(a -> a.get("outcome") != null));
    }

    @Test
    void aLaterRoundDoesNotStealAnEarlierRoundsOutcome(@TempDir Path tmp) throws Exception {
        // an outcome closes only what is still open — otherwise round 4's verdict would be
        // attached to round 3's attempt as well
        Path repo = gitRepo(tmp);
        Feedback.attempt(repo, REPO_URL, ROUND, UNIT, Map.of(), tests(), List.of("a"));
        Feedback.outcome(repo, REPO_URL, ROUND, UNIT, "missed", Map.of(), Map.of(), false);
        Feedback.attempt(repo, REPO_URL, ROUND, UNIT, Map.of(), tests(), List.of("b"));
        Feedback.outcome(repo, REPO_URL, ROUND, UNIT, "kept", Map.of(), Map.of(), false);

        List<Map<String, Object>> joined = Feedback.join(repo);
        assertEquals("missed", ((Map<?, ?>) joined.get(0).get("outcome")).get("verdict"));
        assertEquals("kept", ((Map<?, ?>) joined.get(1).get("outcome")).get("verdict"));
    }

    // ── the human's sentence ──────────────────────────────────────────────

    @Test
    void feedbackAboutTheRepoNeedsNoParticularTest(@TempDir Path tmp) throws Exception {
        // THE case as described: "i don't like too many mocks in these tests". Making someone
        // choose a specific test first would be asking the wrong question.
        Path repo = gitRepo(tmp);
        Map<String, Object> fb = Feedback.feedback(repo, REPO_URL, null,
                "i don't like too many mocks in these tests", true, -1);

        assertNull(fb.get("target"));
        assertEquals(true, fb.get("apply"));
        assertEquals(List.of("i don't like too many mocks in these tests"), Feedback.guidance(repo));
    }

    @Test
    void feedbackAboutOneTestNeverBecomesAStandingRule(@TempDir Path tmp) throws Exception {
        // The modelling error that would poison both loops. "escapesAmpersand asserts one branch
        // of eight" is true of that test and is not a policy for the repo.
        Path repo = gitRepo(tmp);
        String id = Feedback.attempt(repo, REPO_URL, ROUND, UNIT, Map.of(), tests(), List.of());
        Feedback.feedback(repo, REPO_URL, id, "this one asserts a single branch", true, -1);

        assertEquals(List.of(), Feedback.guidance(repo), "a per-test critique is not guidance");
    }

    @Test
    void feedbackFindsItsAttemptById(@TempDir Path tmp) throws Exception {
        Path repo = gitRepo(tmp);
        String id = Feedback.attempt(repo, REPO_URL, ROUND, UNIT, Map.of(), tests(), List.of());
        Feedback.feedback(repo, REPO_URL, id, "asserts nothing useful", false, -1);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> on = (List<Map<String, Object>>) Feedback.join(repo).get(0).get("feedback");
        assertEquals(1, on.size());
        assertEquals("asserts nothing useful", on.get(0).get("text"));
    }

    @Test
    void feedbackAimedAtAUNITReachesEveryAttemptOnIt(@TempDir Path tmp) throws Exception {
        // "the tests for XML::escape keep missing the default branch" is about the unit, not about
        // one generated file
        Path repo = gitRepo(tmp);
        Feedback.attempt(repo, REPO_URL, ROUND, UNIT, Map.of(), tests(), List.of());
        Feedback.attempt(repo, REPO_URL, ROUND + "b", UNIT, Map.of(), tests(), List.of());
        Feedback.feedback(repo, REPO_URL, UNIT, "keeps missing the default branch", false, -1);

        for (Map<String, Object> a : Feedback.join(repo)) {
            assertEquals(1, ((List<?>) a.get("feedback")).size());
        }
    }

    @Test
    void theNewestGuidanceComesLast(@TempDir Path tmp) throws Exception {
        // a later instruction should win a contradiction, the way the person writing it expects
        Path repo = gitRepo(tmp);
        Feedback.feedback(repo, REPO_URL, null, "no mocks", true, null);
        Feedback.feedback(repo, REPO_URL, null, "mocks are fine for the IO layer", true, null);
        assertEquals("mocks are fine for the IO layer", Feedback.guidance(repo).get(1));
    }

    // ── surviving the working copy ────────────────────────────────────────

    @Test
    void theFileIsIgnoredSoGitCleanCannotDeleteIt(@TempDir Path tmp) throws Exception {
        // `git clean -fd` removes untracked files and spares ignored ones. Without this the file
        // is gone on the first dropped round, and every round drops something.
        Path repo = gitRepo(tmp);
        Feedback.feedback(repo, REPO_URL, null, "no mocks", true, null);
        assertTrue(Files.readString(repo.resolve(".git/info/exclude"))
                .lines().anyMatch(l -> l.strip().equals(Feedback.FILE_NAME)));
    }

    @Test
    void theExcludeEntryIsWrittenOnce(@TempDir Path tmp) throws Exception {
        Path repo = gitRepo(tmp);
        for (int i = 0; i < 5; i++) Feedback.feedback(repo, REPO_URL, null, "n" + i, false, null);
        assertEquals(1, Files.readString(repo.resolve(".git/info/exclude"))
                .lines().filter(l -> l.strip().equals(Feedback.FILE_NAME)).count());
    }

    @Test
    void aTeamsOwnGitignoreIsNeverTouched(@TempDir Path tmp) throws Exception {
        // .git/info/exclude is local to the clone. Writing .gitignore would modify a tracked file
        // and appear in the diff of every PR this pipeline opens.
        Path repo = gitRepo(tmp);
        Files.writeString(repo.resolve(".gitignore"), "target/\n");
        Feedback.feedback(repo, REPO_URL, null, "no mocks", true, null);
        assertEquals("target/\n", Files.readString(repo.resolve(".gitignore")));
    }

    @Test
    void itComesBackAfterAFreshClone(@TempDir Path tmp) throws Exception {
        Path first = gitRepo(tmp.resolve("clone1"));
        Path saved = State.DATA_DIR;
        try {
            State.DATA_DIR = tmp.resolve("data");
            Feedback.feedback(first, REPO_URL, null, "i don't like too many mocks", true, null);

            Path second = gitRepo(tmp.resolve("clone2"));
            assertFalse(Files.exists(Feedback.fileIn(second)), "precondition: a fresh clone has nothing");
            Feedback.restoreInto(second, REPO_URL);

            assertEquals(List.of("i don't like too many mocks"), Feedback.guidance(second));
            assertTrue(Files.readString(second.resolve(".git/info/exclude")).contains(Feedback.FILE_NAME));
        } finally {
            State.DATA_DIR = saved;
        }
    }

    @Test
    void restoringNeverOverwritesWhatTheRepoAlreadyHas(@TempDir Path tmp) throws Exception {
        Path repo = gitRepo(tmp.resolve("r"));
        Path saved = State.DATA_DIR;
        try {
            State.DATA_DIR = tmp.resolve("data");
            Feedback.feedback(repo, REPO_URL, null, "first", true, null);
            Feedback.feedback(repo, REPO_URL, null, "second", true, null);
            Feedback.restoreInto(repo, REPO_URL);
            assertEquals(2, Feedback.guidance(repo).size(), "the working copy is newer and must win");
        } finally {
            State.DATA_DIR = saved;
        }
    }

    // ── it must never break a run ─────────────────────────────────────────

    @Test
    void aTruncatedLastLineIsSkippedNotFatal(@TempDir Path tmp) throws Exception {
        // a crash mid-append leaves half a line. One lost record, not a lost file — which is the
        // whole reason this is JSONL rather than one document.
        Path repo = gitRepo(tmp);
        Feedback.feedback(repo, REPO_URL, null, "no mocks", true, null);
        Files.writeString(Feedback.fileIn(repo), "{\"kind\":\"feedb",
                java.nio.file.StandardOpenOption.APPEND);

        assertEquals(List.of("no mocks"), Feedback.guidance(repo));
    }

    @Test
    void aDirectoryThatIsNotAGitCloneStillStoresFeedback(@TempDir Path tmp) {
        // no .git, so the exclude cannot be written — and that must not stop the record
        Feedback.feedback(tmp, REPO_URL, null, "no mocks", true, null);
        assertEquals(List.of("no mocks"), Feedback.guidance(tmp));
    }
}
