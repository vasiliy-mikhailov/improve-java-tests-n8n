package tech.mikhailov.ijt;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class UtilTest {

    @Test
    void globMatcherDoubleStarSpansDirectoriesSingleDoesNot() {
        Predicate<String> m = Util.globsToMatcher("lib/**/*.ts");
        assertTrue(m.test("lib/a.ts"));
        assertTrue(m.test("lib/deep/nested/a.ts"));
        assertFalse(m.test("src/a.ts"));
        assertFalse(m.test("lib/a.tsx"));

        Predicate<String> single = Util.globsToMatcher("src/*.js");
        assertTrue(single.test("src/a.js"));
        assertFalse(single.test("src/deep/a.js"));
    }

    @Test
    void globMatcherBraceAlternativesAndCommaSeparatedGlobs() {
        Predicate<String> m = Util.globsToMatcher("src/**/*.{js,ts,jsx,tsx}");
        for (String p : new String[]{"src/a.js", "src/a.ts", "src/d/b.jsx", "src/d/b.tsx"}) {
            assertTrue(m.test(p), p);
        }
        assertFalse(m.test("src/a.css"));

        Predicate<String> multi = Util.globsToMatcher("lib/**/*.ts,components/ui/**/*.tsx");
        assertTrue(multi.test("lib/x/y.ts"));
        assertTrue(multi.test("components/ui/Button.tsx"));
        assertFalse(multi.test("components/other/Button.tsx"));
    }

    @Test
    void globMatcherRegexMetacharactersAreLiteral() {
        Predicate<String> m = Util.globsToMatcher("src/a+b/*.ts");
        assertTrue(m.test("src/a+b/x.ts"));
        assertFalse(m.test("src/aab/x.ts"));
    }

    @Test
    void slugifyAndFileSlugProduceStableFilesystemSafeIds() {
        assertEquals("github-com-org-repo-name", Util.slugify("https://github.com/org/Repo-Name.git"));
        assertEquals("lib-api-schemas-models-config", Util.fileSlug("lib/api-schemas/models/config.ts"));
        assertFalse(Util.fileSlug("components/ui/ChartCard.tsx").matches(".*[^a-z0-9-].*"));
    }

    @Test
    void macIsTheProductOfTheTwoPercentages() {
        assertEquals(50.0, Util.mac(100.0, 50.0));
        assertEquals(72.0, Util.mac(80.0, 90.0));
        assertEquals(0.0, Util.mac(0.0, 100.0));
        assertNull(Util.mac(null, 50.0));
        assertNull(Util.mac(50.0, null));
        assertEquals(33.33, Util.round2(33.333333));
    }

    @Test
    void extractJsonSurvivesThinkingBlocksFencesAndTrailingProse() {
        assertEquals("a.ts", Util.extractJson("<think>hmm</think>{\"file\":\"a.ts\"}").get("file").asText());
        assertTrue(Util.extractJson("```json\n{\"tests\":[]}\n```").get("tests").isArray());
        assertEquals(1, Util.extractJson("prose {\"a\":{\"b\":1}} more prose").get("a").get("b").asInt());

        // a top-level array must come back as the array, not as its first element
        JsonNode arr = Util.extractJson("[{\"path\":\"x\"}]");
        assertTrue(arr.isArray(), "expected the array itself");
        assertEquals("x", arr.get(0).get("path").asText());

        assertNull(Util.extractJson("no json here"));
    }

    @Test
    void extractJsonDoesNotTruncateAtBracesInsideStrings() {
        JsonNode n = Util.extractJson("{\"content\":\"if (x) { y }\",\"n\":1}");
        assertEquals("if (x) { y }", n.get("content").asText());
        assertEquals(1, n.get("n").asInt());
    }

    @Test
    void redactRemovesTokensApiKeysAndUrlCredentials() {
        // assembled at runtime so the literal never trips secret scanners
        String fakeToken = "gh" + "p_" + "abcdefghijklmnopqrstuvwxyz012345";
        String fakeKey = "sk" + "-" + "0123456789abcdef0123";
        String out = Util.redact("fatal: repository 'https://x-access-token:" + fakeToken + "@github.com/o/r' not found");
        assertFalse(out.contains(fakeToken));
        assertFalse(out.contains("x-access-token:"));
        assertTrue(out.contains("github.com/o/r"));
        // Beyond the JS test, and it belongs here: the credential rule replaces with '$1…',
        // where $1 is the URL scheme. Quoting that replacement — which the first Java port did
        // — emits the two literal characters "$1" instead, so the line a human reads becomes
        // "repository '$1«redacted»@github.com/o/r'". Every assertion above still passed.
        assertTrue(out.contains("https://«redacted»@github.com/o/r"),
                "the scheme is a capture group put back verbatim, not a literal $1");
        assertFalse(Util.redact("key " + fakeKey).contains(fakeKey));
        assertEquals("", Util.redact(null));
    }

    // ── the round line must not invent the numbers it prints ──────────────
    // A live round reported:
    //   cov undefined→0, mut undefined→0, mac null→0 — MISS
    // Nothing about that unit was measured — every stored field is null, correctly — but the
    // line rendered the absent baseline as the literal "undefined" and the absent result as a
    // confident 0. The numbers were not invented in the ledger, only in the sentence a human
    // reads to judge the run.
    @Test
    void anAbsentMetricReadsAsUnmeasuredNotAsZero() {
        assertEquals("?", Util.showMetric(null));
    }

    @Test
    void aRealZeroStillReadsAsZero() {
        // 0% mutation is a measurement and a common one — it must not be hidden
        assertEquals("0", Util.showMetric(0.0));
    }

    @Test
    void measuredValuesPassThrough() {
        assertEquals("66.67", Util.showMetric(66.67));
        assertEquals("100", Util.showMetric(100.0));
    }

    @Test
    void nanIsNotAMeasurementEither() {
        assertEquals("?", Util.showMetric(Double.NaN));
    }
}
