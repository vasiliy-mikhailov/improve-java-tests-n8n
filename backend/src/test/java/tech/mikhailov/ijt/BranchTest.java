package tech.mikhailov.ijt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static tech.mikhailov.ijt.Branch.Action;
import static tech.mikhailov.ijt.Branch.Decision;
import static tech.mikhailov.ijt.Branch.Resolved;

class BranchTest {

    // `git ls-remote --symref <url>` — the symref line names the repo's default branch,
    // the rest are its actual refs.
    private static final String JSON_JAVA = String.join("\n",
            "ref: refs/heads/master\tHEAD",
            "b3f1a2c\tHEAD",
            "b3f1a2c\trefs/heads/master",
            "9de1122\trefs/heads/gh-pages",
            "44ab001\trefs/tags/20231013",
            "");
    private static final String MODERN = String.join("\n",
            "ref: refs/heads/main\tHEAD",
            "aa11\tHEAD",
            "aa11\trefs/heads/main",
            "bb22\trefs/heads/release/2.x",
            "");

    @Test
    void aConfiguredBranchThatExistsIsUsedUnchanged() {
        Resolved r = Branch.resolveBranch("master", JSON_JAVA);
        assertEquals("master", r.branch());
        assertFalse(r.fellBack());
    }

    @Test
    void aConfiguredBranchTheRemoteDoesNotHaveFallsBackToItsDefault() {
        // JSON-java's default is master; a stale REPO_BRANCH=main killed a whole run at clone
        Resolved r = Branch.resolveBranch("main", JSON_JAVA);
        assertEquals("master", r.branch());
        assertTrue(r.fellBack());
        assertTrue(r.reason().contains("main"), r.reason());
        assertTrue(r.reason().contains("master"), r.reason());
    }

    @Test
    void noConfiguredBranchMeansTheRepoDefaultWhateverItIsCalled() {
        assertEquals("master", Branch.resolveBranch("", JSON_JAVA).branch());
        assertEquals("main", Branch.resolveBranch(null, MODERN).branch());
        assertEquals("master", Branch.resolveBranch("auto", JSON_JAVA).branch());
    }

    @Test
    void aBranchThatIsNotTheDefaultIsStillHonoured() {
        assertEquals("release/2.x", Branch.resolveBranch("release/2.x", MODERN).branch());
        assertEquals("gh-pages", Branch.resolveBranch("gh-pages", JSON_JAVA).branch());
    }

    @Test
    void aTagIsNotABranch() {
        Resolved r = Branch.resolveBranch("20231013", JSON_JAVA);
        assertEquals("master", r.branch());
        assertTrue(r.fellBack());
    }

    @Test
    void anUnreachableRemoteFailsLoudlyRatherThanGuessingABranch() {
        Exception e = assertThrows(IllegalArgumentException.class, () -> Branch.resolveBranch("main", ""));
        assertTrue(e.getMessage().toLowerCase().contains("no branches"), e.getMessage());
    }

    @Test
    void withoutASymrefLineTheBranchesThemselvesDecideTheDefault() {
        String noSymref = "aa11\trefs/heads/trunk\nbb22\trefs/heads/wip\n";
        assertEquals("trunk", Branch.resolveBranch("trunk", noSymref).branch());
        Resolved r = Branch.resolveBranch("main", noSymref);
        assertTrue(r.fellBack());
        assertTrue(r.reason().contains("trunk"), r.reason());
    }

    // ---- branchAction: reuse vs reset (ported from branchreuse.test.js) ----

    private static final String RUN = "run-1785086035026";

    @Test
    void theFirstUnitOfAFileStartsItsBranchFromTheBase() {
        assertEquals(Action.RESET, Branch.branchAction("tests/improve-xml", null, RUN).action());
    }

    @Test
    void aSecondUnitOfTheSameFileContinuesOnTheBranchItDoesNotResetIt() {
        // XML::parse improved, was committed and PR'd on tests/improve-...xml-java. XML::format
        // was then picked, `checkout -B <branch> master` moved the branch back to master, and
        // the force-push rewrote the PR to contain only format's test — parse's work vanished
        // from a PR that had already been opened.
        Decision r = Branch.branchAction("tests/improve-xml", RUN, RUN);
        assertEquals(Action.REUSE, r.action());
        assertTrue(r.reason().toLowerCase().contains("this run"), r.reason());
    }

    @Test
    void aBranchLeftByAnEarlierRunIsResetItsCommitsAreNotThisRunS() {
        Decision r = Branch.branchAction("tests/improve-xml", "run-1784000000000", RUN);
        assertEquals(Action.RESET, r.action());
        assertTrue(r.reason().toLowerCase().contains("earlier run"), r.reason());
    }

    @Test
    void resetIsTheSafeDefaultWhenOwnershipIsUnknown() {
        assertEquals(Action.RESET, Branch.branchAction("tests/improve-xml", null, RUN).action());
        assertEquals(Action.RESET, Branch.branchAction("tests/improve-xml", null, null).action());
    }
}
