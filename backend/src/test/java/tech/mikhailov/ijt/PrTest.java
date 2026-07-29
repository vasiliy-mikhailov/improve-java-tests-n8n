package tech.mikhailov.ijt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The last mile: what leaves this pipeline as a deliverable.
///
/// Two kinds of test live here, and the split is deliberate.
///
/// The decisions — which paths may be committed, what `git status -z` actually said, whether
/// `gh` reported an existing PR — are static functions and are tested as values. The rest
/// runs REAL git against a throwaway repository, because the defects this module carries are
/// defects in how git behaves (`git add -N` making an untracked file tracked; a rename
/// emitting two records where one path is wanted), and a mock would pin the mock. `gh` is the
/// one thing that stays scripted: it is not installed on a build machine, and it would not be
/// authorised if it were.
class PrTest {

    /// The JS fixture's own values, kept so the two suites can be read side by side.
    private static final String REPO_URL = "file:///tmp/fixture-repo";
    private static final String TEST_REL = "src/test/java/a/BMacMutTest.java";
    private static final String TEST_SRC = """
            package a;
            import org.junit.Test;
            public class BMacMutTest {
              @Test public void t() { assertEquals(1, 1); }
            }
            """;

    /// The record separator `--porcelain -z` uses, as an escape rather than as the character
    /// itself — a NUL byte in a source file is a byte nobody reading it can see.
    private static final String NUL = "\0";

    @TempDir
    Path tmp;

    private Path savedDataDir;
    private State.Run savedRun;

    @BeforeEach
    void rememberGlobalState() {
        // {@link Repo} finds its checkout through State.DATA_DIR and the run's config, both of
        // which are process-wide. The discard tests below re-point them at a throwaway data
        // directory and have to hand them back however the suite found them.
        savedDataDir = State.DATA_DIR;
        savedRun = State.STATE.run;
    }

    @AfterEach
    void restoreGlobalState() {
        State.DATA_DIR = savedDataDir;
        State.STATE.run = savedRun;
    }

    // ── what `git status -z` said ──────────────────────────────────────────────────────────

    @Test
    void aRenameKeepsTheNewPathAndDropsTheOldOne() {
        // -z reverses the field order and drops the arrow: `R  <to>NUL<from>NUL`. Reading that
        // as two records would put a path that no longer exists into `git add`, which fails the
        // commit for the whole round.
        List<String> out = Pr.parsePorcelainZ(
                "R  src/test/java/a/NewTest.java" + NUL + "src/test/java/a/OldTest.java" + NUL);

        assertEquals(List.of("src/test/java/a/NewTest.java"), out);
    }

    @Test
    void aCopyConsumesItsSourcePathToo() {
        // 'C' is the same two-record shape as 'R'. Rare, and free to get right.
        List<String> out = Pr.parsePorcelainZ("C  b.java" + NUL + "a.java" + NUL + "?? c.java" + NUL);

        assertEquals(List.of("b.java", "c.java"), out);
    }

    @Test
    void aPathWithSpacesInItSurvivesIntact() {
        // The whole reason for -z: the default porcelain format QUOTES such a path, and a
        // quoted path handed back to git is a path that does not exist.
        List<String> out = Pr.parsePorcelainZ("?? src/test/java/a/My Test.java" + NUL + " M pom.xml" + NUL);

        assertEquals(List.of("src/test/java/a/My Test.java", "pom.xml"), out);
    }

    @Test
    void aTruncatedRecordIsDroppedRatherThanCrashingTheRound() {
        // `rec.slice(3)` answers "" in JS where substring(3) throws IndexOutOfBounds. Nothing
        // sane produces a two-character record — but a status parse that can throw is a status
        // parse that can end a run, and the empty entry is filtered out either way.
        assertEquals(List.of("kept.java"), Pr.parsePorcelainZ("??" + NUL + " M kept.java" + NUL));
    }

    @Test
    void anEmptyStatusIsNoChangedFiles() {
        assertEquals(List.of(), Pr.parsePorcelainZ(""));
        assertEquals(List.of(), Pr.parsePorcelainZ(null));
    }

    // ── which paths may be committed ───────────────────────────────────────────────────────

    @Test
    void onlyJavaTestSourcesAreEverCommitted() {
        assertTrue(Pr.isCommittableTest("src/test/java/a/BMacMutTest.java"));
        assertTrue(Pr.isCommittableTest("core/src/test/java/a/BMacMutTest.java"), "multi-module repo");
        // never the PIT/JaCoCo wiring we inject, never the init scripts, never main sources
        assertFalse(Pr.isCommittableTest("pom.xml"));
        assertFalse(Pr.isCommittableTest("core/pom.xml"));
        assertFalse(Pr.isCommittableTest(".ijt-jacoco.gradle"));
        assertFalse(Pr.isCommittableTest("src/main/java/a/B.java"));
        assertFalse(Pr.isCommittableTest("src/test/resources/a.properties"), "not a .java file");
    }

    @Test
    void aTestSourceUnderBuildOutputIsStillBuildOutput() {
        // Both Maven and Gradle copy generated sources into their output directory, and a PR
        // carrying target/ is a PR nobody will read.
        assertFalse(Pr.isCommittableTest("target/generated-test-sources/src/test/java/a/BTest.java"));
        assertFalse(Pr.isCommittableTest("build/x/src/test/java/a/BTest.java"));
        assertFalse(Pr.isCommittableTest(".gradle/src/test/java/a/BTest.java"));
    }

    @Test
    void aPathThatKeptItsLineEndingIsNotAPath() {
        // Java's `$` matches before a trailing newline as well as at the end of input; JS's
        // does not. Translating the regex literally would let "…Test.java\n" through this gate
        // and hand it to `git add` as a file that is not there.
        assertFalse(Pr.isCommittableTest("src/test/java/a/BMacMutTest.java\n"));
        assertFalse(Pr.isCommittableTest(null), "JS tests the string \"null\", which matches nothing");
    }

    @Test
    void committedRoundsComeFirstAndNothingIsCountedTwice() {
        // `git diff --name-only base...HEAD` is newline-separated and its last line is empty;
        // `git status -z` output has already been split. The union is insertion-ordered so the
        // event lines and the cleanup pass see the branch's history before its working tree.
        List<String> out = Pr.testFilesIn(
                "src/test/java/a/OneTest.java\nsrc/test/java/a/TwoTest.java\npom.xml\n",
                List.of("src/test/java/a/TwoTest.java", "src/test/java/a/ThreeTest.java", "target/x/YTest.java"));

        assertEquals(List.of("src/test/java/a/OneTest.java", "src/test/java/a/TwoTest.java",
                "src/test/java/a/ThreeTest.java"), out);
    }

    // ── the small decisions ────────────────────────────────────────────────────────────────

    @Test
    void nothingToCommitIsNotAFailedCommit() {
        // The round's tests were already committed by an earlier round on this branch. The PR
        // still has something to open, and treating this as a failure would abandon it.
        assertFalse(Pr.commitFailed(new Pr.Proc(1, "nothing to commit, working tree clean", "", false)));
        assertFalse(Pr.commitFailed(new Pr.Proc(0, "", "", false)));
        assertTrue(Pr.commitFailed(new Pr.Proc(1, "", "error: pathspec did not match", false)));
    }

    @Test
    void aCommitCountWeCouldNotReadIsNotCommitsAhead() {
        // `parseInt('', 10)` is NaN and `NaN > 0` is false, so a rev-list that failed means
        // "nothing on this branch" and the caller refuses to open an empty PR. Integer.parseInt
        // would have thrown here instead and taken the route with it.
        assertTrue(Pr.aheadOfBase("3\n"));
        assertFalse(Pr.aheadOfBase("0\n"));
        assertFalse(Pr.aheadOfBase(""));
        assertFalse(Pr.aheadOfBase(null));
        assertFalse(Pr.aheadOfBase("fatal: bad revision"));
    }

    @Test
    void theBranchSlugIsSomethingAFileSystemCanHold() {
        // Every run of unsafe characters collapses to ONE dash, globally: a replace of the
        // first occurrence alone would leave the slash of `tests/improve-…` in the middle of a
        // file name and write the patch into a directory that does not exist.
        assertEquals("tests-improve-a-b-Foo.java-m1", Pr.slugOf("tests/improve/a/b/Foo.java#m1"));
        assertEquals("already_safe.name-1", Pr.slugOf("already_safe.name-1"));
    }

    @Test
    void thePrUrlIsPickedOutOfWhateverGhPrinted() {
        // gh writes the url to stdout and its warnings to stderr, and which is which has moved
        // between versions — so the caller searches both, concatenated.
        assertEquals("https://github.com/acme/lib/pull/42",
                Pr.prUrlIn("Warning: 3 uncommitted changes\nhttps://github.com/acme/lib/pull/42\n"));
        // the match stops at whitespace and at a quote, so a url inside a JSON blob comes back
        // without the closing quote attached
        assertEquals("https://github.com/acme/lib/pull/42",
                Pr.prUrlIn("{\"url\":\"https://github.com/acme/lib/pull/42\"}"));
        assertNull(Pr.prUrlIn("could not create pull request"));
        assertNull(Pr.prUrlIn(null));
        assertNull(Pr.prUrlIn("https://github.com/acme/lib/pulls"), "a pull LIST is not a pull request");
    }

    @Test
    void ghSayingThePrExistsIsMatchedWhateverItsCase() {
        assertTrue(Pr.alreadyExists("GraphQL: A pull request already exists for acme:tests/improve-a."));
        assertTrue(Pr.alreadyExists("a pull request Already Exists"));
        assertFalse(Pr.alreadyExists("could not create pull request"));
    }

    @Test
    void theDropReasonSaysWhichKindOfNothingItWas() {
        // A model that answered with prose and a completion that produced nothing at all are
        // different failures, and the event log is where anyone finds out which happened.
        assertEquals("empty file", Pr.dropReason(""));
        assertEquals("empty file", Pr.dropReason("  \n\t\n"));
        assertEquals("no test methods in it", Pr.dropReason("package a;\npublic class BTest {}\n"));
    }

    @Test
    void theTailOfAFailureIsWhatNamesIt() {
        assertEquals("cde", Pr.tail("abcde", 3));
        assertEquals("abc", Pr.tail("abc", 300));
        assertEquals("", Pr.tail(null, 300));
    }

    // ── the payload file ───────────────────────────────────────────────────────────────────

    @Test
    void thePayloadIsWrittenExactlyAsNodeWroteIt() {
        // `JSON.stringify(record, null, 2)`. Jackson's own pretty printer writes `"key" : value`
        // and `[ ]`; this file is a deliverable a human opens, so it is written the way every
        // prepared PR in /data/prs has been written so far.
        Pr.Record record = new Pr.Record("a/B.java", "tests/improve-a-B", "test: improve MAC", "body",
                List.of("automated"), "local", 1700000000000L, null, null);

        assertEquals("""
                {
                  "file": "a/B.java",
                  "branch": "tests/improve-a-B",
                  "title": "test: improve MAC",
                  "body": "body",
                  "labels": [
                    "automated"
                  ],
                  "mode": "local",
                  "createdAt": 1700000000000,
                  "url": null,
                  "patchPath": null
                }""", Pr.stringify(record));
    }

    @Test
    void aTimestampIsAnIntegerAndAnEmptyLabelListIsTwoBrackets() {
        // `String.valueOf(1.7e12)` would have put "1.7E12" in the file. Milliseconds are a long
        // here for exactly that reason, and an empty array is `[]` and not `[ ]`.
        String json = Pr.stringify(new Pr.Record("f", "b", "t", "", List.of(), "local",
                1700000000000L, null, null));

        assertTrue(json.contains("\"createdAt\": 1700000000000,"), json);
        assertTrue(json.contains("\"labels\": [],"), json);
    }

    // ── the working tree, against real git ─────────────────────────────────────────────────

    @Test
    void changedFilesReportsWhatGitItselfSaw() throws Exception {
        Path dir = fixtureAt(tmp.resolve("repo"));
        World w = new World(dir, tmp.resolve("data"));
        Files.writeString(dir.resolve(TEST_REL), TEST_SRC);
        Files.writeString(dir.resolve("pom.xml"), "<project><!-- pit --></project>\n");

        List<String> changed = Pr.changedFiles(w);

        assertTrue(changed.contains(TEST_REL), changed.toString());
        // the injected PIT wiring IS a changed file — filtering it is a later decision, and
        // conflating the two is how a round that wrote no test was credited with one
        assertTrue(changed.contains("pom.xml"), changed.toString());
    }

    @Test
    void aStagedRenameComesBackAsOnePath() throws Exception {
        Path dir = fixtureAt(tmp.resolve("repo"));
        World w = new World(dir, tmp.resolve("data"));
        Files.writeString(dir.resolve(TEST_REL), TEST_SRC);
        git(dir, "add", "--", TEST_REL);
        git(dir, "commit", "-q", "-m", "round 1");
        git(dir, "mv", TEST_REL, "src/test/java/a/CMacMutTest.java");

        assertEquals(List.of("src/test/java/a/CMacMutTest.java"), Pr.changedFiles(w));
    }

    @Test
    void aBrandNewTestShowsUpInTheDiffBecauseItIsStagedIntentToAdd() throws Exception {
        // Without `git add -N` a new file is untracked, `git diff` says nothing about it, and
        // the PR body — and the timesheet built from that diff — describe an empty change.
        Path dir = fixtureAt(tmp.resolve("repo"));
        World w = new World(dir, tmp.resolve("data"));
        Files.writeString(dir.resolve(TEST_REL), TEST_SRC);

        String diff = Pr.diffAgainstBase(w);

        assertTrue(diff.contains("+++ b/" + TEST_REL), diff);
        assertTrue(diff.contains("public class BMacMutTest"), diff);
    }

    @Test
    void theDiffNeverCarriesTheBuildFileWeInjectedPitInto() throws Exception {
        // `:!pom.xml` and `:!**/pom.xml`. The PIT and JaCoCo plugin block is ours, not the
        // repository's, and a PR proposing it is a PR proposing our scaffolding.
        Path dir = fixtureAt(tmp.resolve("repo"));
        World w = new World(dir, tmp.resolve("data"));
        Files.writeString(dir.resolve("pom.xml"), "<project><!-- injected pitest --></project>\n");
        Files.writeString(dir.resolve(TEST_REL), TEST_SRC);

        String diff = Pr.diffAgainstBase(w);

        assertFalse(diff.contains("pom.xml"), diff);
        assertTrue(diff.contains(TEST_REL), diff);
    }

    @Test
    void diffSinceShowsWhatThisUnitAddedAndNotWhatTheBranchAlreadyHeld() throws Exception {
        // A branch is per FILE and a unit is per METHOD: the second method of a class starts on
        // a branch that already carries the first method's committed tests. Diffing the whole
        // branch counted those again — one unit's timesheet claimed four test cases for the one
        // test it had written.
        Path dir = fixtureAt(tmp.resolve("repo"));
        World w = new World(dir, tmp.resolve("data"));
        // on a per-FILE branch, as a real round is: the base branch is where the diff is taken
        // from, and committing on it directly would make every diff empty
        git(dir, "checkout", "-q", "-b", "tests/improve-a-B");
        Files.writeString(dir.resolve(TEST_REL), TEST_SRC);
        git(dir, "add", "--", TEST_REL);
        git(dir, "commit", "-q", "-m", "first method");
        String startSha = git(dir, "rev-parse", "HEAD").strip();
        String second = "src/test/java/a/BMacMutR2Test.java";
        Files.writeString(dir.resolve(second), TEST_SRC.replace("BMacMutTest", "BMacMutR2Test"));

        String mine = Pr.diffSince(w, startSha);

        assertTrue(mine.contains(second), mine);
        assertFalse(mine.contains("BMacMutTest.java"), "the sibling method's committed test was counted again");
        // and the whole-branch diff — what the timesheet used to be built from — has both
        assertTrue(Pr.diffAgainstBase(w).contains(TEST_REL));
    }

    @Test
    void diffSinceWithNoStartShaFallsBackToTheWholeBranch() throws Exception {
        // Falsy, not merely null: a unit whose startSha was never recorded arrives with an
        // empty string, and `git diff ""` is an error, not a diff against the base.
        Path dir = fixtureAt(tmp.resolve("repo"));
        World w = new World(dir, tmp.resolve("data"));
        Files.writeString(dir.resolve(TEST_REL), TEST_SRC);

        assertTrue(Pr.diffSince(w, null).contains(TEST_REL));
        assertTrue(Pr.diffSince(w, "").contains(TEST_REL));
    }

    // ── files not worth a pull request ─────────────────────────────────────────────────────

    @Test
    void anEmptyGeneratedTestIsRemovedFromTheTreeAndSaidSo() throws Exception {
        // A zero-byte XMLMacMutR2Test.java reached a prepared patch as a new file with an empty
        // blob. Dead weight in a deliverable is what this pass exists to prevent.
        Path dir = fixtureAt(tmp.resolve("repo"));
        World w = new World(dir, tmp.resolve("data"));
        Files.writeString(dir.resolve(TEST_REL), TEST_SRC);
        String empty = "src/test/java/a/EmptyMacMutTest.java";
        String prose = "src/test/java/a/ProseMacMutTest.java";
        Files.writeString(dir.resolve(empty), "");
        Files.writeString(dir.resolve(prose), "package a;\npublic class ProseMacMutTest {}\n");

        List<String> kept = Pr.pruneEmptyTests(w, List.of(TEST_REL, empty, prose));

        assertEquals(List.of(TEST_REL), kept);
        assertFalse(Files.exists(dir.resolve(empty)), "the empty file was left in the tree");
        assertFalse(Files.exists(dir.resolve(prose)), "a class with no @Test was left in the tree");
        assertEquals(List.of("preparing_pr: dropped " + empty + ": empty file",
                "preparing_pr: dropped " + prose + ": no test methods in it"), w.events);
    }

    @Test
    void aFileWeCannotReadIsNeitherKeptNorDeleted() throws Exception {
        // The JS `catch { continue }`. A path we cannot open is not evidence that the file is
        // worthless, and unlinking on that basis would delete a test we simply failed to read.
        Path dir = fixtureAt(tmp.resolve("repo"));
        World w = new World(dir, tmp.resolve("data"));

        assertEquals(List.of(), Pr.pruneEmptyTests(w, List.of("src/test/java/a/GoneTest.java")));
        assertEquals(List.of(), w.events, "a file we never read was reported as dropped");
    }

    // ── committing ─────────────────────────────────────────────────────────────────────────

    @Test
    void commitTakesTheTestsAndNeverTheBuildFile() throws Exception {
        // Committing the injected PIT plugin block poisons the base branch: every later round
        // on this repository starts from a pom.xml this pipeline wrote.
        Path dir = fixtureAt(tmp.resolve("repo"));
        World w = new World(dir, tmp.resolve("data"));
        Files.writeString(dir.resolve(TEST_REL), TEST_SRC);
        Files.writeString(dir.resolve("pom.xml"), "<project><!-- injected pitest --></project>\n");

        Pr.Committed committed = Pr.commit(w, "test: improve MAC of a/B.java");

        assertEquals(git(dir, "rev-parse", "HEAD").strip(), committed.sha());
        String show = git(dir, "show", "--name-only", "--format=", "HEAD");
        assertTrue(show.contains(TEST_REL), show);
        assertFalse(show.contains("pom.xml"), show);
        // and the pom is still dirty, waiting to be reset — not committed, not reverted
        assertTrue(Pr.changedFiles(w).contains("pom.xml"));
    }

    @Test
    void anEmptyTestIsDroppedBeforeItCanBeCommitted() throws Exception {
        Path dir = fixtureAt(tmp.resolve("repo"));
        World w = new World(dir, tmp.resolve("data"));
        Files.writeString(dir.resolve("src/test/java/a/EmptyMacMutTest.java"), "");

        // nothing worth committing is left, and this branch has no commits of its own either
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Pr.commit(w, "m"));
        assertTrue(e.getMessage().contains("no changed test files"), e.getMessage());
    }

    @Test
    void aBranchWhoseRoundsAreAlreadyCommittedStillHasAHeadToOpenAPrFrom() throws Exception {
        // The working tree is clean by the time /api/pr/create runs, because every accepted
        // round committed itself. That is not "nothing to open a PR for".
        Path dir = fixtureAt(tmp.resolve("repo"));
        World w = new World(dir, tmp.resolve("data"));
        Files.writeString(dir.resolve(TEST_REL), TEST_SRC);
        git(dir, "checkout", "-q", "-b", "tests/improve-a-B");
        git(dir, "add", "--", TEST_REL);
        git(dir, "commit", "-q", "-m", "round 1");

        Pr.Committed committed = Pr.commit(w, "test: improve MAC of a/B.java");

        assertEquals(git(dir, "rev-parse", "HEAD").strip(), committed.sha());
    }

    @Test
    void aUnitThatImprovedNothingFailsWithTheWordingTheRouteMatches() throws Exception {
        // /api/pr/create tests this message with /no changed test files/ and turns the unit
        // into a skipped PR rather than a failed run. Reword it and every such unit 500s.
        Path dir = fixtureAt(tmp.resolve("repo"));
        World w = new World(dir, tmp.resolve("data"));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Pr.commit(w, "m"));

        assertEquals("no changed test files to commit", e.getMessage());
    }

    // ── the discard, which is what makes `git add -N` dangerous ────────────────────────────
    // Ported from the JS discard.test.js: the interaction is between THIS module staging an
    // intent-to-add and repo.discardUncommitted having to undo it.

    @Test
    void aMissedRoundLeavesNoTraceOfItsTestNotEvenAnEmptyFile() throws Exception {
        // diffAgainstBase() runs `git add -N` on the new test so it shows up in the diff. A
        // missed round then ran `git checkout -- .`, which MATERIALISES that intent-to-add
        // entry as an empty blob, and `git clean -fd` skipped it because it was by then
        // tracked. A zero-byte JSONArrayMacMutR2Test.java reached a prepared PR.
        Path dir = repoFixture();
        World w = new World(dir, tmp.resolve("data"));
        Files.writeString(dir.resolve(TEST_REL), TEST_SRC);

        Pr.diffAgainstBase(w);              // stages intent-to-add
        Repo.discardUncommitted();          // the round missed

        assertFalse(Files.exists(dir.resolve(TEST_REL)), "the file must be gone, not emptied");
        assertEquals("", git(dir, "status", "--porcelain").strip(), "and the tree must be clean");
    }

    @Test
    void anAcceptedRoundAlreadyCommittedSurvivesALaterDiscard() throws Exception {
        Path dir = repoFixture();
        World w = new World(dir, tmp.resolve("data"));
        Files.writeString(dir.resolve(TEST_REL), TEST_SRC);
        git(dir, "add", "--", TEST_REL);
        git(dir, "commit", "-q", "-m", "kept round");
        Files.writeString(dir.resolve("src/test/java/a/Other.java"), TEST_SRC);

        Pr.diffAgainstBase(w);
        Repo.discardUncommitted();

        assertEquals(TEST_SRC, Files.readString(dir.resolve(TEST_REL)), "committed work is untouched");
        assertFalse(Files.exists(dir.resolve("src/test/java/a/Other.java")));
    }

    @Test
    void aModifiedTrackedFileIsRestoredNotTruncated() throws Exception {
        Path dir = repoFixture();
        Files.writeString(dir.resolve(TEST_REL), TEST_SRC);
        git(dir, "add", "--", TEST_REL);
        git(dir, "commit", "-q", "-m", "round 1");
        Files.writeString(dir.resolve(TEST_REL), TEST_SRC + "// round 2 scribble\n");

        Repo.discardUncommitted();

        assertEquals(TEST_SRC, Files.readString(dir.resolve(TEST_REL)));
    }

    // ── preparing a PR locally ─────────────────────────────────────────────────────────────

    @Test
    void localModeKeepsThePatchAndThePayloadAsTheDeliverable() throws Exception {
        Path dir = fixtureAt(tmp.resolve("repo"));
        World w = new World(dir, tmp.resolve("data"));
        Files.writeString(dir.resolve(TEST_REL), TEST_SRC);

        Pr.Record rec = Pr.createPr(w, new Pr.Request("a/B.java", "tests/improve-a-B",
                "test: improve MAC of a/B.java", "body", List.of("automated")));

        Path patch = tmp.resolve("data").resolve("prs").resolve("tests-improve-a-B.patch");
        assertTrue(Files.exists(patch), "no patch was written");
        assertTrue(Files.readString(patch).contains("public class BMacMutTest"));
        assertEquals(patch.toString(), rec.patchPath());
        assertNull(rec.url(), "a local PR has no url — the dashboard renders the patch path instead");
        assertEquals("local", rec.mode());
        assertEquals(List.of(rec), w.ledger, "the run's PR ledger is what /api/state serves");
        assertEquals(List.of("preparing_pr: PR prepared locally: " + patch), w.events);
    }

    @Test
    void thePayloadOnDiskCarriesNoPatchPathBecauseNodeWroteItFirst() throws Exception {
        // The Node sidecar serialises the payload BEFORE assigning patchPath, so the .json on
        // disk says null while the record in state.prs carries the path. Preserved deliberately:
        // every prepared PR in /data/prs has this shape, and the shape is what anything written
        // against those files was written against.
        Path dir = fixtureAt(tmp.resolve("repo"));
        World w = new World(dir, tmp.resolve("data"));
        Files.writeString(dir.resolve(TEST_REL), TEST_SRC);

        Pr.Record rec = Pr.createPr(w, new Pr.Request("a/B.java", "tests/improve-a-B", "t", "b", null));

        String json = Files.readString(tmp.resolve("data").resolve("prs").resolve("tests-improve-a-B.json"));
        assertTrue(json.contains("\"patchPath\": null"), json);
        assertNotNull(rec.patchPath(), "the record handed back must carry it");
        // `labels = []` — the JS default parameter, and the payload says so
        assertTrue(json.contains("\"labels\": []"), json);
    }

    // ── opening a PR on GitHub ─────────────────────────────────────────────────────────────

    @Test
    void githubModeForcesThePushAndThenOpensThePr() {
        World w = githubWorld();
        w.canned = argv -> argv.contains("create")
                ? ok("https://github.com/acme/lib/pull/42\n")
                : ok("");

        Pr.Record rec = Pr.createPr(w, new Pr.Request("a/B.java", "tests/improve-a-B", "title", "body", List.of()));

        assertEquals(List.of("git", "push", "--force", "--set-upstream", "origin", "tests/improve-a-B", "--no-verify"),
                w.ran.get(0));
        // --force because the branch is rebuilt from the base every attempt; --no-verify because
        // a repo's own pre-push hook must not be able to end the run
        assertEquals("git push", w.labels.get(0), "the dashboard shows a two-minute push as 'git push'");
        // .longValue(), so this is assertEquals(long, long) and not an ambiguous overload
        assertEquals(Pr.PUSH_MS, w.timeouts.get(0).longValue(), "a push to a large repo takes minutes");
        assertEquals(List.of("gh", "pr", "create", "--base", "master", "--head", "tests/improve-a-B",
                "--title", "title", "--body", "body"), w.ran.get(1));
        assertEquals(2, w.ran.size(), "a PR that opened first time needs no further calls");
        assertEquals("https://github.com/acme/lib/pull/42", rec.url());
        assertNull(rec.patchPath(), "github mode writes no patch");
        assertEquals(List.of("preparing_pr: PR created: https://github.com/acme/lib/pull/42"), w.events);
    }

    @Test
    void aPrThatAlreadyExistsIsFoundAndKeptFresh() {
        // The normal case on a second attempt for the same unit: the branch was force-pushed
        // and its PR is still open. gh refuses to create a second one and says so WITHOUT
        // printing the url, so it has to be asked for separately.
        World w = githubWorld();
        w.canned = argv -> {
            if (argv.contains("create")) {
                return new Pr.Proc(1, "", "GraphQL: A pull request already exists for acme:tests/improve-a-B.", false);
            }
            if (argv.contains("view")) return ok("https://github.com/acme/lib/pull/7\n");
            return ok("");
        };

        Pr.Record rec = Pr.createPr(w, new Pr.Request("a/B.java", "tests/improve-a-B", "title", "body", null));

        assertEquals("https://github.com/acme/lib/pull/7", rec.url());
        assertEquals(List.of("gh", "pr", "view", "tests/improve-a-B", "--json", "url", "-q", ".url"), w.ran.get(2));
        // the description is rewritten, because the branch it describes has moved on
        assertEquals(List.of("gh", "pr", "edit", "tests/improve-a-B", "--title", "title", "--body", "body"),
                w.ran.get(3));
    }

    @Test
    void aPushThatFailedNeverReachesGh() {
        // Opening a PR for a branch the remote does not have produces a confusing gh error; the
        // push is the failure worth reporting, and its last 400 characters name it.
        World w = githubWorld();
        w.canned = argv -> argv.contains("push")
                ? new Pr.Proc(128, "", "remote: Permission to acme/lib.git denied", false)
                : ok("https://github.com/acme/lib/pull/1");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Pr.createPr(w, new Pr.Request("a/B.java", "b", "t", "b", null)));

        assertTrue(e.getMessage().startsWith("git push failed: "), e.getMessage());
        assertTrue(e.getMessage().contains("Permission to acme/lib.git denied"), e.getMessage());
        assertEquals(1, w.ran.size(), "gh was called after a push that failed");
        assertEquals(List.of(), w.ledger, "a PR that was never opened was recorded anyway");
    }

    @Test
    void ghSayingNothingWeCanUseIsAFailureAndNotAPrWithNoUrl() {
        // A record with a null url and a null patchPath is a PR nobody can find. Better to fail
        // the route: the branch is pushed and the work is not lost.
        World w = githubWorld();
        w.canned = argv -> argv.contains("create")
                ? new Pr.Proc(1, "", "X509: certificate signed by unknown authority", false)
                : ok("");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Pr.createPr(w, new Pr.Request("a/B.java", "b", "t", "b", null)));

        assertTrue(e.getMessage().startsWith("gh pr create failed: "), e.getMessage());
        assertTrue(e.getMessage().contains("certificate signed"), e.getMessage());
    }

    @Test
    void everyLabelIsItsOwnCall() {
        // `--add-label a,b` would be a single call, but one label the repository does not carry
        // fails it and takes the rest with it.
        World w = githubWorld();
        w.canned = argv -> argv.contains("create") ? ok("https://github.com/acme/lib/pull/3") : ok("");

        Pr.createPr(w, new Pr.Request("a/B.java", "b", "t", "body", List.of("automated", "tests")));

        assertEquals(List.of("gh", "pr", "edit", "b", "--add-label", "automated"), w.ran.get(2));
        assertEquals(List.of("gh", "pr", "edit", "b", "--add-label", "tests"), w.ran.get(3));
    }

    @Test
    void aDryRunInGithubModeStillPreparesTheDeliverableLocally() throws Exception {
        // `prMode === 'github' && !dryRun`. A dry run pushes nothing and calls no gh, but it
        // still produces the patch — which is the only way to see what a run WOULD have opened.
        Path dir = fixtureAt(tmp.resolve("repo"));
        World w = new World(dir, tmp.resolve("data"));
        w.prMode = "github";
        w.dryRun = true;
        Files.writeString(dir.resolve(TEST_REL), TEST_SRC);

        Pr.Record rec = Pr.createPr(w, new Pr.Request("a/B.java", "tests/improve-a-B", "t", "b", null));

        assertNull(rec.url());
        assertNotNull(rec.patchPath());
        // the mode recorded is the CONFIGURED one, not the branch that was taken — a dry run of
        // a github repo is what this record has to be readable as afterwards
        assertEquals("github", rec.mode());
        assertFalse(w.ran.stream().anyMatch(argv -> argv.get(0).equals("gh")), "gh ran during a dry run");
    }

    // ── the world these tests run in ───────────────────────────────────────────────────────

    /// The Node sidecar's `exec.run`, `state` and `repo.repoDir()`, as one object a test owns.
    ///
    /// A command with no canned answer really runs, in the fixture repository — which is the
    /// point for git. `strict` turns that off for the `gh` tests, where an unanswered command
    /// reaching a build machine's real `gh` would be a test that talks to GitHub.
    private static final class World implements Pr.Io {
        private final Path repo;
        private final Path data;
        String prBase = "master";
        String prMode = "local";
        boolean dryRun;
        boolean strict;
        Function<List<String>, Pr.Proc> canned = argv -> null;

        final List<List<String>> ran = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        final List<Long> timeouts = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final List<Pr.Record> ledger = new ArrayList<>();

        World(Path repo, Path data) {
            this.repo = repo;
            this.data = data;
        }

        @Override public Path repoDir() { return repo; }

        @Override public Path dataDir() { return data; }

        @Override public String prBase() { return prBase; }

        @Override public String prMode() { return prMode; }

        @Override public boolean dryRun() { return dryRun; }

        @Override public void event(String stage, String msg) { events.add(stage + ": " + msg); }

        @Override public void recordPr(Pr.Record record) { ledger.add(record); }

        @Override public Pr.Proc run(List<String> argv, long timeoutMs, String label) {
            ran.add(List.copyOf(argv));
            labels.add(label);
            timeouts.add(timeoutMs);
            Pr.Proc answer = canned.apply(argv);
            if (answer != null) return answer;
            if (strict) throw new AssertionError("no canned answer for: " + argv);
            return Pr.exec(argv, repo, timeoutMs, label);
        }
    }

    private static Pr.Proc ok(String stdout) {
        return new Pr.Proc(0, stdout, "", false);
    }

    /// A world where nothing may actually run: `gh` is not installed here, and if it were it
    /// would be authorised against a real account.
    private World githubWorld() {
        World w = new World(tmp, tmp.resolve("data"));
        w.prMode = "github";
        w.strict = true;
        return w;
    }

    /// A repository with one commit: a pom.xml and a test the project already had.
    ///
    /// That existing test is not decoration, and it is what the JS fixture is missing.
    /// `git status --porcelain` COLLAPSES a directory whose every entry is untracked, so a
    /// fixture where nothing under `src/` is tracked answers `?? src/` — which is not a
    /// committable test path, so nothing is ever intent-to-added and the `git add -N` half of
    /// this module is never reached. Every real repository has tests already; the fixture has
    /// to as well, or the tests below pass without exercising anything.
    private Path fixtureAt(Path dir) throws IOException {
        Files.createDirectories(dir.resolve("src/test/java/a"));
        git(dir, "init", "-q", "-b", "master");
        git(dir, "config", "user.email", "t@example.com");
        git(dir, "config", "user.name", "test");
        // Not in the JS fixture: a developer machine that signs every commit would fail
        // `git commit` here for a reason that has nothing to do with what these tests pin.
        git(dir, "config", "commit.gpgsign", "false");
        Files.writeString(dir.resolve("pom.xml"), "<project/>\n");
        Files.writeString(dir.resolve("src/test/java/a/AlreadyThereTest.java"),
                TEST_SRC.replace("BMacMutTest", "AlreadyThereTest"));
        git(dir, "add", "-A");
        git(dir, "commit", "-q", "-m", "base");
        return dir;
    }

    /// The same fixture, where {@link Repo#repoDir()} expects to find it — for the tests that
    /// drive the real discard.
    private Path repoFixture() throws IOException {
        State.DATA_DIR = tmp;
        State.STATE.run = State.freshRun(Map.<String, Object>of(
                "repoUrl", REPO_URL, "repoBranch", "master", "prBase", "master"));
        return fixtureAt(Repo.repoDir());
    }

    private static String git(Path dir, String... args) {
        List<String> argv = new ArrayList<>();
        argv.add("git");
        argv.addAll(List.of(args));
        Pr.Proc r = Pr.exec(argv, dir, 30_000L, null);
        assertEquals(0, r.code(), () -> "git " + String.join(" ", args) + " failed: " + r.stderr());
        return r.stdout();
    }
}
