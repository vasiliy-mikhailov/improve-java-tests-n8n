package tech.mikhailov.ijt;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Two facts about a target method that decide whether a test can reach it at all.
///
/// Units come from JaCoCo, which enumerates every method including private helpers. A test
/// cannot call a private method — reflection is forbidden by the write_test rules and would be
/// a worthless test anyway — so it has to arrive through something public. Told nothing about
/// this, the model wrote a compiling, passing test for JSONObject's private
/// isRecordStyleAccessor that executed none of it: coverage 0 → 0, every mutant alive, a whole
/// round spent.
///
/// Regex over Java source is approximate by nature. Everything here only ENRICHES a prompt and
/// never decides a number, so a miss costs a hint and never a false measurement.
public final class JavaSrc {

    private JavaSrc() {}

    /// A method reachable from somewhere, with enough detail to name it in a prompt.
    ///
    /// The `signature` is the whole declaration, not just the name: a hint that says "call
    /// nextEntity()" when the method is nextEntity(char) produces a test that will not compile.
    public record MethodRef(String method, String visibility, String signature) {}

    /// Blank out string literals, char literals and comments, keeping every newline so line
    /// numbers still line up.
    ///
    /// Java source that WRITES Java or JSON — which is most of what this pipeline is pointed
    /// at — is full of braces in literals: counting them drifted JSONObject.java's nesting
    /// depth to -4 by end of file, and once depth never rises above class level again, the
    /// code below loses track of which method it is inside. That is how a method with an
    /// obvious caller was reported as having none, and skipped as unreachable.
    public static String stripNonCode(String source) {
        String src = source == null ? "" : source;
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < src.length()) {
            char c = src.charAt(i);
            char next = i + 1 < src.length() ? src.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                int end = src.indexOf('\n', i);
                int stop = end == -1 ? src.length() : end;
                out.append(blankButNewlines(src.substring(i, stop)));
                i = stop;
            } else if (c == '/' && next == '*') {
                int end = src.indexOf("*/", i + 2);
                int stop = end == -1 ? src.length() : end + 2;
                out.append(blankButNewlines(src.substring(i, stop)));
                i = stop;
            } else if (c == '"' || c == '\'') {
                // text blocks (""") behave like a long string for our purposes
                boolean triple = c == '"' && src.startsWith("\"\"\"", i);
                int j = i + (triple ? 3 : 1);
                while (j < src.length()) {
                    if (src.charAt(j) == '\\') { j += 2; continue; }      // escape: skip the pair
                    if (triple ? src.startsWith("\"\"\"", j) : src.charAt(j) == c) { j += triple ? 3 : 1; break; }
                    if (!triple && src.charAt(j) == '\n') break;          // unterminated: do not run on
                    j += 1;
                }
                if (j > src.length()) j = src.length();
                out.append(blankButNewlines(src.substring(i, j)));
                i = j;
            } else {
                out.append(c);
                i += 1;
            }
        }
        return out.toString();
    }

    private static String blankButNewlines(String text) {
        return text.replaceAll("[^\n]", " ");
    }

    private static final Pattern CONTROL =
            Pattern.compile("\\b(?:if|while|for|switch|catch|do|else|synchronized)\\s*\\(");

    static String escape(String s) {
        return String.valueOf(s).replaceAll("([.*+?^$\\{\\}()|\\[\\]\\\\])", "\\\\$1");
    }

    /// Is this line the declaration of `name`? Decided from what precedes the name: modifiers
    /// and a return type, never a `(`, a `.`, or a `return` — which is what separates
    ///   `private static boolean isRecordStyleAccessor(String n, Method m) {`
    /// from `if (isRecordType && isRecordStyleAccessor(name, method)) {`.
    static boolean declares(String line, String name) {
        Matcher m = Pattern.compile("(^[\\s\\S]*?)(?:^|[^\\w.$])" + escape(name) + "\\s*\\(").matcher(line);
        if (!m.find()) return false;
        if (CONTROL.matcher(line).find()) return false;
        String prefix = m.group(1);
        if (!prefix.matches("^[\\s@\\w.$<>\\[\\],?&]*$")) return false;  // no (, {, =, ; before the name
        if (prefix.matches("(?s).*\\.\\s*$")) return false;              // this.foo( is a call
        if (Pattern.compile("\\b(?:return|new|throw)\\b").matcher(prefix).find()) return false;
        // A declaration is preceded by modifiers and/or a return type. Nothing at all means a
        // bare call statement — and `target(1);` ends in `);` exactly like an abstract method
        // declaration, so the shape check below cannot tell them apart on its own. The one
        // exception is a package-private constructor, which is named like the class.
        if (prefix.trim().isEmpty() && !name.matches("^[A-Z].*")) return false;
        // a declaration's parameter list is followed by a body or a semicolon
        String fromParen = line.substring(m.end() - 1);
        return Pattern.compile("\\)\\s*(?:throws\\s+[\\w.,\\s]+?)?\\s*[{;]").matcher(fromParen).find();
    }

    static String visibilityOf(String line) {
        if (Pattern.compile("\\bprivate\\b").matcher(line).find()) return "private";
        if (Pattern.compile("\\bprotected\\b").matcher(line).find()) return "protected";
        if (Pattern.compile("\\bpublic\\b").matcher(line).find()) return "public";
        return "package-private";
    }

    private static final Pattern NAME_IN = Pattern.compile("(?:^|[^\\w.$])([\\w$]+)\\s*\\(");

    static String nameIn(String line) {
        Matcher m = NAME_IN.matcher(line);
        return m.find() ? m.group(1) : null;
    }

    static int depthOf(String line) {
        return (int) line.chars().filter(c -> c == '{').count()
             - (int) line.chars().filter(c -> c == '}').count();
    }

    /// `public` | `private` | `protected` | `package-private`, or null if not declared here.
    public static String methodVisibility(String source, String method) {
        if (source == null || source.isEmpty() || method == null || method.isEmpty()) return null;
        for (String line : stripNonCode(source).split("\n", -1)) {
            if (declares(line, method)) return visibilityOf(line.substring(0, line.indexOf(method)));
        }
        return null;
    }

    /// Methods of this class whose body calls `method` — the routes a test can take to it.
    /// A method is never reported as calling itself.
    public static List<MethodRef> callersOf(String source, String method) {
        if (source == null || source.isEmpty() || method == null || method.isEmpty()) return List.of();
        Pattern call = Pattern.compile("(?:^|[^\\w.$])(?:this\\.|super\\.)?" + escape(method) + "\\s*\\(");
        List<MethodRef> out = new ArrayList<>();
        MethodRef current = null;
        int depth = 0;
        // Two views of the same line, and they are not interchangeable.
        //
        // The STRUCTURE — where a declaration starts, where a body ends, whether a call is real —
        // must be read off the stripped text, or a brace inside a string literal closes a method
        // early and a call inside a comment counts as a route.
        //
        // The SIGNATURE must not be. It is handed to the model as the thing to call, and the
        // stripped line has every string literal replaced by spaces:
        //
        //     Object nextEntity(@SuppressWarnings(          ) char ampersand) throws JSONException
        //
        // which is not Java and cannot be written. Four private XMLTokener methods described
        // their only entry point that way. stripNonCode preserves offsets and line count exactly
        // so the original can be read alongside it.
        String[] code = stripNonCode(source).split("\n", -1);
        String[] original = source.split("\n", -1);
        for (int i = 0; i < code.length; i++) {
            String line = code[i];
            String raw = i < original.length ? original[i] : line;
            String name = nameIn(line);
            boolean isDecl = depth <= 1 && name != null && declares(line, name);
            if (isDecl) {
                current = name.equals(method)
                        ? null                                  // inside the target itself: not a caller
                        : new MethodRef(
                                name,
                                visibilityOf(line.substring(0, line.indexOf(name))),
                                raw.trim().replaceAll("\\s*\\{\\s*$", "")
                                        .replaceAll("^(public|private|protected)\\s+", ""));
            }
            // a one-line method declares and calls on the same line, so check both, not either
            if (current != null && call.matcher(line).find()) {
                final MethodRef c = current;
                if (out.stream().noneMatch(x -> x.method().equals(c.method()))) out.add(c);
            }
            depth += depthOf(line);
            if (depth <= 1 && !isDecl) current = null;           // back at class level
        }
        return out;
    }

    /// How a test can get to a method it cannot call. Walks callers outward until it reaches
    /// something public, and returns the path in the order a test would travel it:
    /// `[public entry, …private hops…, target]`.
    ///
    /// Listing only DIRECT callers was not enough on the case that motivated this:
    /// JSONObject#isRecordStyleAccessor is called by getKeyNameFromMethod, which is itself
    /// private — so the hint named another method the test could not call either. The nearest
    /// public entry is three hops out, the constructor JSONObject(Object bean).
    ///
    /// Returns [] for a method that is already public, and for private code no public path
    /// reaches — that one is dead, and a round spent on it can only fail.
    public static List<List<MethodRef>> routesTo(String source, String method) {
        return routesTo(source, method, 6);
    }

    public static List<List<MethodRef>> routesTo(String source, String method, int maxHops) {
        String code = stripNonCode(source);
        if (method == null || method.isEmpty() || "public".equals(methodVisibility(code, method))) return List.of();
        List<List<MethodRef>> routes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(method);
        // breadth-first, so the SHORTEST route to public API is found first
        List<List<MethodRef>> frontier = new ArrayList<>();
        frontier.add(List.of(new MethodRef(method, methodVisibility(code, method), null)));
        for (int hop = 0; hop < maxHops && !frontier.isEmpty() && routes.isEmpty(); hop++) {
            List<List<MethodRef>> next = new ArrayList<>();
            for (List<MethodRef> path : frontier) {
                for (MethodRef caller : callersOf(code, path.get(0).method())) {
                    if (seen.contains(caller.method())) continue;   // a cycle between helpers must not loop
                    List<MethodRef> extended = new ArrayList<>();
                    extended.add(caller);
                    extended.addAll(path);
                    if ("public".equals(caller.visibility())) routes.add(extended);
                    else next.add(extended);
                }
            }
            next.forEach(p -> seen.add(p.get(0).method()));
            frontier = next;
        }
        return routes;
    }
}
