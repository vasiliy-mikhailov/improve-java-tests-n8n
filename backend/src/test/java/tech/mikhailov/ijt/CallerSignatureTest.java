package tech.mikhailov.ijt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The "call it like this" line handed to the model is not valid Java.
///
/// `callersOf` walks `stripNonCode(source)` — which blanks string literals and comments while
/// preserving offsets, so that a brace inside a string cannot be mistaken for structure. That is
/// right for deciding where a declaration starts and where a body ends. It is wrong for the
/// SIGNATURE, which is built from the same blanked line and handed to the model as the route to
/// a private method:
///
///     Object nextEntity(@SuppressWarnings(          ) char ampersand) throws JSONException
///
/// Every string literal in a declaration comes out as a run of spaces. Reproduced over the real
/// org/json sources on XMLTokener#parseHexEntity, #isValidDecimal, #isValidHex and
/// #parseDecimalEntity — all four are private, so all four reach the model only through this
/// path, and all four describe their entry point with a hole in it.
///
/// This matters more than it looks. Compile errors are 62% of the suite failures still being
/// recorded now that salvage handles the assertion cases, and showing the model an uncompilable
/// signature to copy is a direct way to produce one.
///
/// The fix is to keep the structural decisions on the stripped text and take the signature from
/// the original line. `stripNonCode` preserves offsets and line count precisely so the two can be
/// read side by side.
class CallerSignatureTest {

    /// Shaped after org/json/XMLTokener.java, which is where this was found.
    private static final String SOURCE = """
            package org.json;

            public class XMLTokener {

                public Object nextEntity(@SuppressWarnings("unused") char ampersand) throws JSONException {
                    return parseHexEntity("0x1F");
                }

                private Object parseHexEntity(String s) {
                    return null;
                }
            }
            """;

    @Test
    void theSignatureKeepsItsStringLiterals() {
        List<JavaSrc.MethodRef> callers = JavaSrc.callersOf(SOURCE, "parseHexEntity");
        assertEquals(1, callers.size(), "nextEntity calls it");
        String sig = callers.get(0).signature();
        assertTrue(sig.contains("\"unused\""),
                "the model is told to call this — it has to be something it can actually write. got: " + sig);
    }

    @Test
    void theSignatureIsNotFullOfHoles() {
        String sig = JavaSrc.callersOf(SOURCE, "parseHexEntity").get(0).signature();
        assertFalse(sig.matches(".*\\(\\s{3,}\\).*"),
                "a blanked literal leaves a run of spaces where an argument should be: " + sig);
    }

    @Test
    void theMethodAndVisibilityAreStillRight() {
        // the negative half: reading the original line must not break what already worked
        JavaSrc.MethodRef ref = JavaSrc.callersOf(SOURCE, "parseHexEntity").get(0);
        assertEquals("nextEntity", ref.method());
        assertEquals("public", ref.visibility());
        assertFalse(ref.signature().startsWith("public "), "the modifier is stripped, as before");
    }

    @Test
    void aBraceInsideAStringStillDoesNotEndTheBody() {
        // WHY stripNonCode is there, and what must not regress. If the structural pass started
        // reading original lines too, this brace would close the method early and `helper` would
        // not be seen as a caller at all.
        String tricky = """
            public class A {

                public String entry() {
                    String s = "}";
                    return helper(s);
                }

                private String helper(String s) {
                    return s;
                }
            }
            """;
        List<JavaSrc.MethodRef> callers = JavaSrc.callersOf(tricky, "helper");
        assertEquals(1, callers.size(), "the brace in the literal is not structure");
        assertEquals("entry", callers.get(0).method());
    }

    @Test
    void aCommentedOutCallIsStillNotACall() {
        // the other half of stripNonCode's job
        String commented = """
            public class A {

                public void entry() {
                    // helper(1);
                }

                private void helper(int i) {
                }
            }
            """;
        assertTrue(JavaSrc.callersOf(commented, "helper").isEmpty(),
                "a call in a comment is not a route to anything");
    }
}
