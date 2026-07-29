package tech.mikhailov.ijt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static tech.mikhailov.ijt.JavaSrc.MethodRef;

class JavaSrcTest {

    private static final String SRC = """
            package org.json;

            public class JSONObject {
                private static final Set<String> EXCLUDED = new HashSet<>();

                public JSONObject(Object bean) {
                    this.populateMap(bean);
                }

                private void populateMap(Object bean) {
                    for (final Method method : bean.getClass().getMethods()) {
                        if (isRecordType && isRecordStyleAccessor(name, method)) {
                            key = name;
                        }
                    }
                }

                public String toString(int indentFactor) throws JSONException {
                    return this.write(w, indentFactor, 0).toString();
                }

                private static boolean isRecordStyleAccessor(String methodName, Method method) {
                    return !className.startsWith("java.");
                }

                protected void helper() { }

                static int packagePrivate() { return isRecordStyleAccessor(null, null) ? 1 : 0; }
            }
            """;

    private static List<String> names(List<MethodRef> refs) {
        return refs.stream().map(MethodRef::method).sorted().toList();
    }

    @Test
    void theVisibilityATestHasToWorkWithIsReadOffTheDeclaration() {
        assertEquals("private", JavaSrc.methodVisibility(SRC, "isRecordStyleAccessor"));
        assertEquals("public", JavaSrc.methodVisibility(SRC, "toString"));
        assertEquals("protected", JavaSrc.methodVisibility(SRC, "helper"));
        assertEquals("package-private", JavaSrc.methodVisibility(SRC, "packagePrivate"));
        assertEquals("public", JavaSrc.methodVisibility(SRC, "JSONObject"), "the constructor counts");
    }

    @Test
    void aMethodTheSourceDoesNotDeclareHasUnknownVisibility() {
        assertNull(JavaSrc.methodVisibility(SRC, "noSuchThing"));
        assertNull(JavaSrc.methodVisibility("", "anything"));
    }

    @Test
    void callersWithinTheClassAreTheRoutesATestCanActuallyTake() {
        // isRecordStyleAccessor is private: a test can only reach it through something public.
        // Without being told this, the model wrote a passing test that executed none of it —
        // the round measured cov 0 → 0 and every mutant survived.
        assertEquals(List.of("packagePrivate", "populateMap"), names(JavaSrc.callersOf(SRC, "isRecordStyleAccessor")));
    }

    @Test
    void eachCarrierCarriesItsOwnVisibilitySoAPublicRouteIsRecognisable() {
        MethodRef populateMap = JavaSrc.callersOf(SRC, "isRecordStyleAccessor").stream()
                .filter(c -> c.method().equals("populateMap")).findFirst().orElseThrow();
        assertEquals("private", populateMap.visibility());
    }

    @Test
    void theDeclarationItselfIsNotMistakenForACall() {
        assertEquals(List.of(), names(JavaSrc.callersOf(SRC, "toString")),
                "the body of toString calling toString() on another object is not a self-call");
    }

    @Test
    void aMethodNothingCallsHasNoCallers() {
        assertEquals(List.of(), JavaSrc.callersOf(SRC, "helper"));
    }

    @Test
    void aMethodPastThePromptClipIsStillFound() {
        // gapsFor analysed the string it sends to the model, which is clipped to 24 000 chars
        // for prompt size. JSONObject#isRecordStyleAccessor is declared at line 2071, well past
        // that, so visibility came back null, the REACHED VIA section was omitted, and the fix
        // that was supposed to name the route silently did nothing in production.
        String filler = "    // padding padding padding padding padding padding padding\n".repeat(600);
        String big = "package org.json;\n\npublic class Big {\n" + filler + """
                    public void entryPoint() {
                        target(1);
                    }

                    private int target(int x) {
                        return x + 1;
                    }
                }
                """;
        assertTrue(big.length() > 24000, "fixture must exceed the prompt clip (was " + big.length() + ")");
        assertEquals("private", JavaSrc.methodVisibility(big, "target"));
        assertEquals(List.of("entryPoint"), names(JavaSrc.callersOf(big, "target")));
    }

    @Test
    void bracesInsideStringsCharsAndCommentsDoNotShiftTheNestingDepth() {
        // JSONObject.java writes JSON, so it is full of '{' and "}" in literals and javadoc.
        // Counting those as real braces drifted its depth to -4 by end of file; no line after
        // the drift ever looked like it sat at class level, so callersOf returned nothing for a
        // method with an obvious caller. The drift must come BEFORE the caller, as in the real file.
        String src = """
                package org.json;

                public class Writer {
                    /** Javadoc with an unbalanced brace: } and another } for good measure. */
                    public void drift() {
                        sb.append('}');
                        sb.append("}}");   // and a comment brace }
                    }

                    public void write(StringBuilder sb) {
                        final String name = sb.toString();
                        if (name.isEmpty()) {
                            return;
                        }
                        check(name);
                    }

                    private boolean check(String s) {
                        return s.isEmpty();
                    }
                }
                """;
        assertEquals("private", JavaSrc.methodVisibility(src, "check"));
        assertEquals(List.of("write"), names(JavaSrc.callersOf(src, "check")));
    }

    @Test
    void anEscapedQuoteDoesNotRunTheStringOnToTheEndOfTheFile() {
        String src = """
                package a;

                public class B {
                    public void drift() {
                        String s = "he said \\"}}}}\\" and left";
                    }

                    public void run() {
                        int n = 1;
                        n = n + 1;
                        helper();
                    }

                    private void helper() { }
                }
                """;
        assertEquals(List.of("run"), names(JavaSrc.callersOf(src, "helper")));
    }

    @Test
    void codeIsKeptLiteralsAndCommentsAreBlankedLineNumbersSurvive() {
        String src = String.join("\n",
                "class A {",
                "    String s = \"a { b } c\";  // trailing } comment",
                "    /* block } comment */",
                "    char c = '{';",
                "}");
        String out = JavaSrc.stripNonCode(src);
        assertEquals(5, out.split("\n", -1).length, "line count must be preserved");
        assertEquals(1, out.chars().filter(c -> c == '{').count(), "only the class brace is real");
        assertEquals(1, out.chars().filter(c -> c == '}').count());
        assertTrue(out.contains("String s ="), "code outside the literal is untouched");
    }

    // ── routes from public API to a hidden method ─────────────────────────
    private static final String CHAIN = """
            package org.json;

            public class JSONObject {
                public JSONObject(Object bean) {
                    this.populateMap(bean);
                }

                private void populateMap(Object bean) {
                    String k = getKeyNameFromMethod(m);
                    map.put(k, v);
                }

                private static String getKeyNameFromMethod(Method method) {
                    if (isRecordType && isRecordStyleAccessor(name, method)) {
                        return name;
                    }
                    return null;
                }

                private static boolean isRecordStyleAccessor(String methodName, Method method) {
                    return true;
                }

                private void deadHelper() {
                    onlyDead();
                }

                private void onlyDead() {
                }
            }
            """;

    @Test
    void theRouteToAHiddenMethodStartsAtPublicApiAndEndsAtTheMethod() {
        // isRecordStyleAccessor is three private hops from the nearest public entry. Listing
        // only its direct caller told the model to call another private method — no more use
        // than telling it nothing.
        List<MethodRef> route = JavaSrc.routesTo(CHAIN, "isRecordStyleAccessor").get(0);
        assertEquals(List.of("JSONObject", "populateMap", "getKeyNameFromMethod", "isRecordStyleAccessor"),
                route.stream().map(MethodRef::method).toList());
        assertEquals("public", route.get(0).visibility(), "a test can only start from something public");
    }

    @Test
    void aMethodWithADirectPublicCallerGetsTheShortRoute() {
        List<MethodRef> route = JavaSrc.routesTo(CHAIN, "populateMap").get(0);
        assertEquals(List.of("JSONObject", "populateMap"), route.stream().map(MethodRef::method).toList());
    }

    @Test
    void privateCodeNoPublicPathReachesHasNoRouteAtAll() {
        assertEquals(List.of(), JavaSrc.routesTo(CHAIN, "onlyDead"));
    }

    @Test
    void aPublicMethodNeedsNoRoute() {
        assertEquals(List.of(), JavaSrc.routesTo(CHAIN, "JSONObject"));
    }

    @Test
    void aCycleBetweenPrivateHelpersDoesNotHangTheSearch() {
        String cyclic = """
                public class C {
                    public void entry() { a(); }
                    private void a() { b(); }
                    private void b() { a(); target(); }
                    private void target() { }
                }
                """;
        List<MethodRef> route = JavaSrc.routesTo(cyclic, "target").get(0);
        assertEquals("entry", route.get(0).method());
        assertEquals("target", route.get(route.size() - 1).method());
    }

    @Test
    void aRouteStepCarriesTheSignatureATestMustActuallyCall() {
        // The hint printed "nextEntity()" — name and empty parens — so the model wrote exactly
        // that and the test would not compile: "method nextEntity cannot be applied to given
        // types". Six rounds on XMLTokener#isValidDecimal were lost to it.
        String src = """
                public class T {
                    public Object nextEntity(char ampersand) throws JSONException {
                        return isValidDecimal(buffer);
                    }

                    private boolean isValidDecimal(StringBuilder sb) { return true; }
                }
                """;
        MethodRef entry = JavaSrc.routesTo(src, "isValidDecimal").get(0).get(0);
        assertEquals("nextEntity", entry.method());
        assertTrue(entry.signature().contains("nextEntity(char ampersand)"),
                "the parameter list is what makes the call compile, got: " + entry.signature());
    }

    @Test
    void aNoArgumentRouteStepStillReadsAsACallableSignature() {
        String src = """
                public class T {
                    public void run() { helper(); }
                    private void helper() { }
                }
                """;
        MethodRef entry = JavaSrc.routesTo(src, "helper").get(0).get(0);
        assertTrue(entry.signature().matches(".*run\\(\\s*\\).*"), entry.signature());
    }
}
