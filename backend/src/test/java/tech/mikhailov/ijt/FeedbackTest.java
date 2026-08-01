package tech.mikhailov.ijt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The repo's own record of what it was asked, what it produced, and what a human thought.
///
/// Append-only JSONL at the root of the repo being improved. Three kinds of line — attempt,
/// outcome, feedback — joined on read. Append rather than a rewritten JSON document because a run
/// has generation and repair in flight against one file, and read-modify-write loses records
/// under exactly that.
class FeedbackTest {

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

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

    // ── a comment is about the test that existed when it was written ──────

    /// A critique does not follow the unit into the next rewrite.
    ///
    /// THE MODELLING ERROR. A unit-targeted comment used to attach to every attempt for that
    /// unit, including ones written LATER. "this test asserts nothing" was made about round 3's
    /// output; round 4 rewrites the test completely and inherits the critique. Two things break:
    /// GEPA is handed a sentence describing output the attempt never produced, which is training
    /// signal pointing at the wrong artefact; and the dashboard shows the newest code and test
    /// above a comment that was about neither.
    @Test
    void aCritiqueDoesNotFollowTheUnitIntoTheNextRewrite(@TempDir Path tmp) throws Exception {
        Path repo = gitRepo(tmp);
        String first = Feedback.attempt(repo, REPO_URL, ROUND, UNIT, Map.of(), tests(), List.of());
        Feedback.feedback(repo, REPO_URL, UNIT, "this one asserts nothing useful", false, -1);
        String rewritten = Feedback.attempt(repo, REPO_URL, ROUND + "b", UNIT, Map.of(), tests(), List.of());

        Map<String, List<?>> byId = new java.util.LinkedHashMap<>();
        for (Map<String, Object> a : Feedback.join(repo)) byId.put(str(a.get("id")), (List<?>) a.get("feedback"));

        assertEquals(1, byId.get(first).size(), "the attempt it was written about keeps it");
        assertEquals(0, byId.get(rewritten).size(),
                "a test written AFTER the comment inherited a critique of the one it replaced");
    }

    @Test
    void aCommentRecordsWhichAttemptItWasAbout(@TempDir Path tmp) throws Exception {
        // stored on the line, so the pairing survives being read by anything that is not join()
        Path repo = gitRepo(tmp);
        String id = Feedback.attempt(repo, REPO_URL, ROUND, UNIT, Map.of(), tests(), List.of());
        Map<String, Object> fb = Feedback.feedback(repo, REPO_URL, UNIT, "asserts nothing", false, -1);
        assertEquals(id, fb.get("about"));
    }

    @Test
    void aCommentMadeBeforeAnythingExistedIsAboutWhatComesNext(@TempDir Path tmp) throws Exception {
        // "when you write this one, do not mock the parser" — said about a unit with no
        // generated test yet. Anchoring it to nothing would make it vanish; it belongs to the
        // next attempt, which is the output it was in force for.
        Path repo = gitRepo(tmp);
        Map<String, Object> fb = Feedback.feedback(repo, REPO_URL, UNIT, "do not mock the parser", false, -1);
        assertNull(fb.get("about"), "there was nothing to be about");
        String next = Feedback.attempt(repo, REPO_URL, ROUND, UNIT, Map.of(), tests(), List.of());

        List<Map<String, Object>> joined = Feedback.join(repo);
        assertEquals(1, joined.size());
        assertEquals(next, joined.get(0).get("id"));
        assertEquals(1, ((List<?>) joined.get(0).get("feedback")).size(),
                "a forward-looking comment never reached the test it was written for");
    }

    /// Aiming at a UNIT is how you comment without knowing an attempt id — not a claim that the
    /// sentence is true of every test ever written for it.
    ///
    /// This test used to assert the opposite, that the comment reached every attempt. It was
    /// wrong in a way that only shows up once the unit is rewritten: the critique describes the
    /// output that existed when it was typed, and round 4 replaces that output entirely.
    @Test
    void feedbackAimedAtAUnitLandsOnTheAttemptThatExistedWhenItWasWritten(@TempDir Path tmp) throws Exception {
        Path repo = gitRepo(tmp);
        String first = Feedback.attempt(repo, REPO_URL, ROUND, UNIT, Map.of(), tests(), List.of());
        String latest = Feedback.attempt(repo, REPO_URL, ROUND + "b", UNIT, Map.of(), tests(), List.of());
        Feedback.feedback(repo, REPO_URL, UNIT, "keeps missing the default branch", false, -1);

        for (Map<String, Object> a : Feedback.join(repo)) {
            int on = ((List<?>) a.get("feedback")).size();
            assertEquals(latest.equals(a.get("id")) ? 1 : 0, on,
                    "attempt " + a.get("id") + " carries " + on + "; only " + latest + " should");
        }
        assertNotEquals(first, latest);
    }

    /// The author is written WITH the line, not attached to the response afterwards.
    ///
    /// It used to be `item.put("author", …)` in the route, applied to the map that had already
    /// been appended to the file. The caller got their name back in the response and the stored
    /// line had no author at all, so every comment in the corpus read as "someone" — including
    /// four I had posted and believed were attributed. Found by being asked to prove that a
    /// comment was really there.
    @Test
    void theAuthorIsStoredWithTheComment(@TempDir Path tmp) throws Exception {
        Path repo = gitRepo(tmp);
        Feedback.feedback(repo, REPO_URL, null, "no mocks", true, -1, "Claude");

        Map<String, Object> stored = Feedback.lines(repo).stream()
                .filter(l -> "feedback".equals(l.get("kind"))).findFirst().orElseThrow();
        assertEquals("Claude", stored.get("author"), "the file, not the response: " + stored);
    }

    @Test
    void anAnonymousCommentCarriesNoAuthorKeyAtAll(@TempDir Path tmp) throws Exception {
        // absent, rather than the string "null" or an empty string that renders as a blank name
        Path repo = gitRepo(tmp);
        Feedback.feedback(repo, REPO_URL, null, "no mocks", true, null, "  ");
        Map<String, Object> stored = Feedback.lines(repo).stream()
                .filter(l -> "feedback".equals(l.get("kind"))).findFirst().orElseThrow();
        assertFalse(stored.containsKey("author"), stored.toString());
    }

    @Test
    void oneCommentIsAttachedExactlyOnceAcrossTheWholeCorpus(@TempDir Path tmp) throws Exception {
        // Written when a unit-targeted comment fanned out to every attempt and the dashboard
        // rendered it once per attempt — one sentence reading as a chorus. Anchoring removed the
        // fan-out at the source, so the claim is now the stronger one: across every record in
        // the file, the comment appears once. A reader needs no deduplication to be correct.
        Path repo = gitRepo(tmp);
        Feedback.attempt(repo, REPO_URL, ROUND, UNIT, Map.of(), tests(), List.of());
        Feedback.attempt(repo, REPO_URL, ROUND + "b", UNIT, Map.of(), tests(), List.of());
        Feedback.feedback(repo, REPO_URL, UNIT, "asserts on the message rather than the type", false, -1, "Claude");

        List<Object> ids = new ArrayList<>();
        for (Map<String, Object> a : Feedback.join(repo)) {
            for (Object fb : (List<?>) a.get("feedback")) ids.add(((Map<?, ?>) fb).get("id"));
        }
        assertEquals(1, ids.size(), "attached " + ids.size() + " times: " + ids);
    }

    // ── the corpus, as training data ──────────────────────────────────────

    @Test
    void aTrainingRowIsSourcePromptsOutputOutcomeAndWhatAPersonSaid(@TempDir Path tmp) throws Exception {
        Path repo = gitRepo(tmp);
        Feedback.attempt(repo, REPO_URL, ROUND, UNIT,
                Map.of("path", "src/main/java/org/json/XML.java", "fqcn", "org.json.XML",
                        "methodSource", "static String escape(String s) { … }"),
                tests(),
                List.of("no mocks"),
                Map.of("write_test", "no mocks", "pick_file", "prefer small methods"));
        Feedback.outcome(repo, REPO_URL, ROUND, UNIT, "kept",
                Map.of("mac", 4.94), Map.of("mac", 30.0), false);
        Feedback.feedback(repo, REPO_URL, UNIT, "asserts one branch of eight", false, -1, "Claude");

        Map<String, Object> row = Feedback.training(repo).get(0);
        assertEquals(UNIT, row.get("unit"));
        assertEquals("org.json.XML", ((Map<?, ?>) row.get("source")).get("fqcn"));
        // EVERY stage, not just the one that writes the test
        assertEquals("prefer small methods", ((Map<?, ?>) row.get("prompts")).get("pick_file"));
        assertEquals(1, ((List<?>) ((Map<?, ?>) row.get("output")).get("tests")).size());

        Map<?, ?> outcome = (Map<?, ?>) row.get("outcome");
        assertEquals("kept", outcome.get("verdict"));
        // derived, so no consumer has to know before/after are the ROUND's rather than lifetime
        assertEquals(25.06, (Double) outcome.get("macGain"), 0.001);

        List<?> said = (List<?>) row.get("feedback");
        assertEquals(1, said.size());
        assertEquals("Claude", ((Map<?, ?>) said.get(0)).get("author"));
        assertEquals("asserts one branch of eight", ((Map<?, ?>) said.get(0)).get("text"));
    }

    @Test
    void aRoundThatBrokeIsStillARow(@TempDir Path tmp) throws Exception {
        // a third of rounds never reach measurement; dropping them would teach an optimiser that
        // everything it writes gets measured
        Path repo = gitRepo(tmp);
        Feedback.attempt(repo, REPO_URL, ROUND, UNIT, Map.of(), tests(), List.of("no mocks"), Map.of());
        List<Map<String, Object>> rows = Feedback.training(repo);
        assertEquals(1, rows.size());
        assertNull(rows.get(0).get("outcome"), "an unmeasured attempt is a row about a broken round");
    }

    @Test
    void anOlderRecordStillReportsThePromptItDidStore(@TempDir Path tmp) throws Exception {
        // 1546 lines were written before `prompts` existed. An empty map would read as "no rules
        // were in force", which is a different and false claim.
        Path repo = gitRepo(tmp);
        Feedback.attempt(repo, REPO_URL, ROUND, UNIT, Map.of(), tests(), List.of("don\u0027t use reflection"));
        assertEquals("don\u0027t use reflection",
                ((Map<?, ?>) Feedback.training(repo).get(0).get("prompts")).get("write_test"));
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
