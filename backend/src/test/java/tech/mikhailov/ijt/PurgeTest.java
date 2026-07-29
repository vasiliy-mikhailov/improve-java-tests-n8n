package tech.mikhailov.ijt;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// Purging is deletion, so every rule here is about what must NOT be removed: a path the
/// ledger names but that does not live in the artifact directory, and a branch this pipeline
/// did not create.
class PurgeTest {

    /// The improvement ledger for one repo, shaped like the live one: two units of a single
    /// file share a branch and a patch, and a third unit produced a branch but no patch.
    ///
    /// The live record also carries `state` (`improved`, `no_improvement`), which purging never
    /// reads — hence {@link Purge.Entry} does not model it.
    private static Map<String, Purge.Entry> ledger() {
        Map<String, Purge.Entry> m = new LinkedHashMap<>();
        m.put("src/main/java/org/json/XML.java::parse", new Purge.Entry(
                "tests/improve-src-main-java-org-json-xml-java",
                "/data/prs/tests-improve-src-main-java-org-json-xml-java.patch"));
        m.put("src/main/java/org/json/XML.java::toString", new Purge.Entry(
                "tests/improve-src-main-java-org-json-xml-java",
                "/data/prs/tests-improve-src-main-java-org-json-xml-java.patch"));
        m.put("src/main/java/org/json/Cookie.java::<init>", new Purge.Entry(
                "tests/improve-src-main-java-org-json-cookie-java", null));
        return m;
    }

    @Test
    void everyPreparedPrArtifactOfThisRepoIsNamedForRemoval() {
        Purge.Plan p = Purge.purgePlan(ledger());
        assertTrue(p.files().contains("/data/prs/tests-improve-src-main-java-org-json-xml-java.patch"));
        assertTrue(p.files().contains("/data/prs/tests-improve-src-main-java-org-json-xml-java.json"),
                "the payload beside the patch goes too");
    }

    @Test
    void aPatchSharedByTwoUnitsOfOneFileIsRemovedOnce() {
        Purge.Plan p = Purge.purgePlan(ledger());
        assertEquals(p.files().size(), new LinkedHashSet<>(p.files()).size());
    }

    @Test
    void everyBranchThisRepoProducedIsNamedIncludingOnesWithNoPatch() {
        Purge.Plan p = Purge.purgePlan(ledger());
        assertEquals(
                List.of("tests/improve-src-main-java-org-json-cookie-java",
                        "tests/improve-src-main-java-org-json-xml-java"),
                p.branches().stream().sorted().toList());
    }

    @Test
    void anEmptyLedgerAsksForNothing() {
        Purge.Plan p = Purge.purgePlan(Map.of());
        assertEquals(List.of(), p.files());
        assertEquals(List.of(), p.branches());
        assertEquals(List.of(), Purge.purgePlan(null).branches());
    }

    @Test
    void onlyArtifactsUnderThePrsDirectoryAreEverNamed() {
        Map<String, Purge.Entry> evil = Map.of("u", new Purge.Entry("b", "/etc/passwd"));
        assertEquals(List.of(), Purge.purgePlan(evil).files(),
                "a path outside /data/prs is not ours to delete");
    }

    @Test
    void aBranchNameThatIsNotOneOfOursIsLeftAlone() {
        Purge.Plan p = Purge.purgePlan(Map.of("u", new Purge.Entry("master", null)));
        assertEquals(List.of(), p.branches(), "only tests/improve-* branches belong to this pipeline");
    }

    // ── the port's own footguns ───────────────────────────────────────────
    // Everything above is the JavaScript suite. What follows pins the two places where an
    // idiomatic Java translation would silently widen what gets deleted.

    @Test
    void theContainmentGateIsTheDirectoryItselfNotAPrefixOfIt() {
        // startsWith("/data/prs") would let both of these through: a sibling directory whose
        // name merely begins the same way, and a nested one the pipeline never writes to
        Map<String, Purge.Entry> near = new LinkedHashMap<>();
        near.put("a", new Purge.Entry(null, "/data/prs-old/x.patch"));
        near.put("b", new Purge.Entry(null, "/data/prs/nested/x.patch"));
        assertEquals(List.of(), Purge.purgePlan(near).files());
    }

    @Test
    void aCallerMayNameItsOwnArtifactDirectory() {
        // the live caller passes DATA_DIR/prs, which is only /data/prs by default
        Map<String, Purge.Entry> l = Map.of("a", new Purge.Entry(null, "/srv/state/prs/x.patch"));
        assertEquals(List.of("/srv/state/prs/x.patch", "/srv/state/prs/x.json"),
                Purge.purgePlan(l, "/srv/state/prs").files());
        assertEquals(List.of(), Purge.purgePlan(l).files(), "and nothing else is in scope");
    }

    @Test
    void onlyTheSuffixBecomesTheJsonPayloadAndOnlyAtTheEnd() {
        // JS `.replace(/\.patch$/, '.json')` is anchored and single-shot: replaceAll on an
        // unanchored pattern would rewrite the directory name too
        Map<String, Purge.Entry> l = Map.of("a", new Purge.Entry(null, "/data/prs/a.patch.b.patch"));
        assertEquals(List.of("/data/prs/a.patch.b.patch", "/data/prs/a.patch.b.json"),
                Purge.purgePlan(l).files());
    }

    @Test
    void aPathThatIsNotAPatchIsStillNamedExactlyOnce() {
        // the substitution is a no-op there, and the set absorbs the duplicate
        Map<String, Purge.Entry> l = Map.of("a", new Purge.Entry(null, "/data/prs/README"));
        assertEquals(List.of("/data/prs/README"), Purge.purgePlan(l).files());
    }

    @Test
    void anAbsentOrEmptyPathNamesNothing() {
        Map<String, Purge.Entry> l = new LinkedHashMap<>();
        l.put("a", new Purge.Entry("tests/improve-a", null));
        l.put("b", new Purge.Entry("tests/improve-b", ""));
        Purge.Plan p = Purge.purgePlan(l);
        assertEquals(List.of(), p.files(), "an empty string is falsy in JS, and no path here");
        assertEquals(List.of("tests/improve-a", "tests/improve-b"), p.branches());
    }

    @Test
    void aNullRecordIsSkippedRatherThanThrown() {
        Map<String, Purge.Entry> l = new LinkedHashMap<>();
        l.put("gone", null);                       // a ledger is data, and data can be wrong
        l.put("a", new Purge.Entry("tests/improve-a", "/data/prs/a.patch"));
        assertEquals(List.of("tests/improve-a"), Purge.purgePlan(l).branches());
    }

    @Test
    void oursMatchesAPrefixNotTheWholeName() {
        // JS `.test()` is a search; Java's `matches()` demands the whole string, and swapping
        // one for the other here would spare every branch the pipeline made
        assertTrue(Purge.isOurs("tests/improve-src-main-java-org-json-xml-java"));
        assertFalse(Purge.OURS.matcher("tests/improve-x").matches(), "the trap, pinned");
        // ...and it is anchored, so a name that merely CONTAINS ours is not ours
        assertFalse(Purge.isOurs("feature/tests/improve-x"));
        assertFalse(Purge.isOurs("master"));
        assertFalse(Purge.isOurs(""));
        assertFalse(Purge.isOurs(null));
    }

    @Test
    void dirnameAnswersWhatNodeAnswers() {
        assertEquals("/data/prs", Purge.dirname("/data/prs/x.patch"));
        assertEquals("/etc", Purge.dirname("/etc/passwd"));
        assertEquals("/data", Purge.dirname("/data/prs/"), "a trailing slash names the parent");
        assertEquals(".", Purge.dirname("x.patch"), "not null, as Path#getParent would answer");
        assertEquals("/", Purge.dirname("/x.patch"));
        assertEquals(".", Purge.dirname(""));
        assertEquals(".", Purge.dirname(null));
    }
}
