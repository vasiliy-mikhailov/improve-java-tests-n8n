package tech.mikhailov.ijt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The prompt shows one overload; PIT scores all of them.
///
/// A unit is `path::name`. `expandFilesIntoMethodUnits` keys on the JaCoCo method NAME, and
/// `Pit.scopeToMethod` filters on `method.equals(m.method())` — also name-only. So one unit spans
/// every overload of that name, and PIT reports survivors across all of them.
///
/// `methodContextOf` walks back from a single `methodLine` and returns ONE body. The batch prompt
/// then lists targets like `write() line 91` and `write() line 104` while showing the model only
/// the overload that happens to own `methodLine`. Those targets are unwritable by construction:
/// the model is asked to kill a mutant on code it cannot see.
///
/// This is where the heaviest zero-conversion units in the log live:
///
///     JSONObject::toString        21 rounds, 0 conversions
///     StringBuilderWriter::write  21 rounds, 0 hits — 14 mutants across 4 overloads,
///                                 while the record carries methodLine 36
///     JSONTokener::next           23 rounds, 0
///     JSONArray::addAll           29 rounds, 0
///
/// Overload counts in the sources under improvement run to JSONArray::put x17,
/// JSONObject::<init> x15, JSONArray::<init> x12.
///
/// So the context becomes the union of the declaration blocks that own the target line AND the
/// surviving mutant lines — bounded by the survivors, so only overloads with live targets are
/// added. Splitting the unit by descriptor instead would change what the ledgers key on, and is
/// deliberately not done here.
class OverloadContextTest {

    /// Four overloads, shaped after org/json/StringBuilderWriter.java.
    private static final String SRC = """
            package org.json;

            public class StringBuilderWriter extends Writer {

                public void write(int c) {
                    builder.append((char) c);
                }

                public void write(char[] cbuf) {
                    builder.append(cbuf);
                }

                public void write(String str) {
                    builder.append(str);
                }

                public void write(String str, int off, int len) {
                    builder.append(str, off, off + len);
                }
            }
            """;

    private static int lineOf(String needle) {
        String[] lines = SRC.split("\n", -1);
        for (int i = 0; i < lines.length; i++) if (lines[i].contains(needle)) return i + 1;
        throw new AssertionError("no such line: " + needle);
    }

    @Test
    void theOverloadOwningASurvivorIsInTheContext() {
        // THE bug. methodLine points at write(int); the survivor is inside write(String,int,int),
        // which the model never sees and therefore cannot write a test against.
        int methodLine = lineOf("builder.append((char) c)");
        int survivor = lineOf("builder.append(str, off, off + len)");

        Repo.MethodContext ctx = Repo.methodContextOf(SRC, "write", methodLine, List.of(survivor));
        assertTrue(ctx.body().contains("off + len"),
                "the overload that owns the surviving mutant must be visible. got:\n" + ctx.body());
    }

    @Test
    void theTargetOverloadIsStillThereAndStillFirst() {
        // it is the unit's own declaration line — it must not be displaced by the additions
        int methodLine = lineOf("builder.append((char) c)");
        int survivor = lineOf("builder.append(str, off, off + len)");

        String body = Repo.methodContextOf(SRC, "write", methodLine, List.of(survivor)).body();
        assertTrue(body.contains("(char) c"), "the target overload is still shown");
        assertTrue(body.indexOf("(char) c") < body.indexOf("off + len"),
                "and still leads, because it is the one the unit is named for");
    }

    @Test
    void onlyOverloadsWithLiveTargetsAreAdded() {
        // The bound that keeps the prompt finite. write(char[]) and write(String) have no
        // survivors here and must not be pulled in just for being overloads.
        int methodLine = lineOf("builder.append((char) c)");
        int survivor = lineOf("builder.append(str, off, off + len)");

        String body = Repo.methodContextOf(SRC, "write", methodLine, List.of(survivor)).body();
        assertTrue(!body.contains("builder.append(cbuf)"),
                "an overload with nothing to kill is not worth prompt budget. got:\n" + body);
    }

    @Test
    void anOverloadIsNotShownTwice() {
        // several survivors in the same overload are one block, not one block each
        int methodLine = lineOf("builder.append((char) c)");
        int a = lineOf("builder.append(str, off, off + len)");

        String body = Repo.methodContextOf(SRC, "write", methodLine, List.of(a, a, a)).body();
        assertEquals(1, countOf(body, "off + len"), "one block per overload:\n" + body);
    }

    @Test
    void aSurvivorInsideTheTargetOverloadAddsNothing() {
        // the common case — one overload, survivors inside it. It must read exactly as before.
        int methodLine = lineOf("builder.append((char) c)");
        int inside = lineOf("builder.append((char) c)");

        String withHint = Repo.methodContextOf(SRC, "write", methodLine, List.of(inside)).body();
        String without = Repo.methodContextOf(SRC, "write", methodLine).body();
        assertEquals(without, withHint);
    }

    @Test
    void noSurvivorLinesBehavesExactlyAsBefore() {
        // the compatibility guarantee: every existing caller passes no lines at all
        assertEquals(Repo.methodContextOf(SRC, "write", lineOf("builder.append(cbuf)")).body(),
                Repo.methodContextOf(SRC, "write", lineOf("builder.append(cbuf)"), List.of()).body());
    }

    /// And it has to be CALLED. `gapsFor` reaches the context through `Repo.methodContext`, a
    /// different entry point from the one the tests above exercise — so a correct helper wired to
    /// nothing would pass every one of them. That has happened five times in this project.
    @Test
    void theRepositoryEntryPointCarriesTheSurvivorLinesThrough(@org.junit.jupiter.api.io.TempDir
                                                              java.nio.file.Path tmp) throws Exception {
        java.nio.file.Path savedData = State.DATA_DIR;
        State.Run savedRun = State.STATE.run;
        try {
            State.DATA_DIR = tmp;
            String repoUrl = "https://example.invalid/o/r";
            State.STATE.run = State.freshRun(State.envConfig(k -> null), java.util.Map.of("repoUrl", repoUrl));

            java.nio.file.Path pkg = Repo.repoDir().resolve("src/main/java/org/json");
            java.nio.file.Files.createDirectories(pkg);
            java.nio.file.Files.writeString(pkg.resolve("StringBuilderWriter.java"), SRC);

            Repo.MethodContext ctx = Repo.methodContext(
                    "src/main/java/org/json/StringBuilderWriter.java", "write",
                    lineOf("builder.append((char) c)"),
                    List.of(lineOf("builder.append(str, off, off + len)")));

            assertTrue(ctx != null && ctx.body().contains("off + len"),
                    "the survivor's overload must survive the trip through the repository reader");
        } finally {
            State.DATA_DIR = savedData;
            State.STATE.run = savedRun;
        }
    }

    /// Overloads that DELEGATE — the shape every fixture above lacks, and the reason this bug
    /// shipped.
    ///
    /// `SRC` uses `builder.append(...)` bodies, which never contain the token `write(`, so the
    /// walk-back always reached a declaration and every assertion passed. Real overloads forward
    /// to each other: `return this.put(value);`. The walk-back used the same pattern that DETECTS
    /// a call, so it stopped on that body line, and with no `{` seen yet the forward scan ran
    /// past the method's closing brace and ended on the NEXT overload's signature.
    ///
    /// Measured on the real org/json/JSONArray.java: of 17 `put` overloads, **2** blocks began on
    /// a declaration. The model was handed 9 054 characters of bare `return` statements followed
    /// by dangling signatures and asked to write tests against them. After the fix: 17 of 17,
    /// and 3 457 characters.
    private static final String DELEGATING = """
            package org.json;

            public class JSONArray {

                public JSONArray put(boolean value) {
                    return this.put(value ? Boolean.TRUE : Boolean.FALSE);
                }

                public JSONArray put(double value) throws JSONException {
                    return this.put(Double.valueOf(value));
                }

                public JSONArray put(Object value) {
                    this.myArrayList.add(value);
                    return this;
                }
            }
            """;

    private static int lineIn(String src, String needle) {
        String[] lines = src.split("\n", -1);
        for (int i = 0; i < lines.length; i++) if (lines[i].contains(needle)) return i + 1;
        throw new AssertionError("no such line: " + needle);
    }

    @Test
    void everyBlockOfADelegatingOverloadStartsOnItsDeclaration() {
        int target = lineIn(DELEGATING, "return this.put(value ? Boolean.TRUE");
        List<Integer> survivors = List.of(
                lineIn(DELEGATING, "return this.put(Double.valueOf(value))"),
                lineIn(DELEGATING, "this.myArrayList.add(value)"));

        String body = Repo.methodContextOf(DELEGATING, "put", target, survivors).body();
        for (String block : body.split("// … other members omitted …")) {
            String first = block.strip().split("\n")[0].strip();
            assertTrue(first.matches(".*\\b(public|private|protected|static)\\b.*\\(.*"),
                    "a block began mid-body — the model cannot write a test against a fragment: ["
                            + first + "]\nfull body:\n" + body);
        }
    }

    @Test
    void aDelegatingOverloadIsNotRunTogetherWithTheNextOne() {
        // the compounding half: without a `{` seen, the forward scan swallowed the rest of the
        // method AND the following declaration, so one block held two signatures
        int target = lineIn(DELEGATING, "return this.put(value ? Boolean.TRUE");
        String body = Repo.methodContextOf(DELEGATING, "put", target,
                List.of(lineIn(DELEGATING, "return this.put(Double.valueOf(value))"))).body();

        for (String block : body.split("// … other members omitted …")) {
            assertTrue(countOf(block, "public JSONArray put(") <= 1,
                    "one block carried two declarations:\n" + block);
        }
    }

    private static int countOf(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) n++;
        return n;
    }
}
