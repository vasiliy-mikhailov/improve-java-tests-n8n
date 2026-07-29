package tech.mikhailov.ijt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// What a test has to build before it can call the method under test.
///
/// The prompt shows the class under test and its sibling signatures. It does not show the
/// types that class's CONSTRUCTOR demands, so a test for
/// DelegatingStatisticsCollector — which takes a StatisticsCollector — was written against
/// an API the model had never seen. `cannot find symbol` was 8 of 11 recorded failures on
/// the v3 run, and DataLoaderHelper#load lost sixteen tests in one file to it.
///
/// The run supplied its own control: SimpleStatisticsCollector, same package and same batch
/// size but needing no collaborator, went MAC 0 → 100 in a single pass.
///
/// Two things fix it, in order of preference. Name a concrete implementation the project
/// already ships — java-dataloader has SimpleStatisticsCollector and NoOpStatisticsCollector
/// — because instantiating one is cheaper and safer than hand-rolling an anonymous class.
/// Failing that, show the interface's full public API, so a hand-rolled implementation
/// overrides every method instead of the three the model guessed at.
public final class Collaborators {

    private Collaborators() {}

    /// One source file of the project under improvement: where it lives and what is in it.
    ///
    /// `source` is nullable — the caller reads files off disk and a read can come back empty
    /// or not at all, and {@link #concreteImplementors} is expected to skip such a file rather
    /// than throw.
    public record SourceFile(String path, String source) {}

    /// A test never has to "construct" these.
    ///
    /// Primitives are deliberately NOT listed. The only consumer of this set is guarded by
    /// `^[A-Z]`, so `int`, `long`, `void` and friends are already excluded by capitalisation
    /// and can never reach the lookup — mutation testing found this by leaving exactly nine
    /// unkillable mutants on the nine primitive entries that used to sit here. Dead data that
    /// reads as deliberate is worse than no data: the next person adds `short` to it and
    /// believes that is what excludes short.
    public static final Set<String> BUILT_IN = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            "String", "Integer", "Long", "Short", "Byte", "Character", "Boolean", "Float", "Double",
            "Object", "Number", "CharSequence", "Class", "Throwable", "Exception", "RuntimeException",
            "List", "Map", "Set", "Collection", "Optional", "Iterable")));

    // `\z` and not `$`: JS `$` without the multiline flag means end of input, while Java's `$`
    // also matches before a final line terminator — which would strip from a `<` that JS leaves
    // alone.
    private static final Pattern TYPE_ARGS = Pattern.compile("<.*\\z");
    private static final Pattern ARRAY_OR_VARARGS = Pattern.compile("\\[\\]|\\.\\.\\.");
    private static final Pattern CAPITALISED = Pattern.compile("^[A-Z]");
    private static final Pattern INNERMOST_TYPE_ARGS = Pattern.compile("<[^<>]*>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /// `CacheMap<Object, String>` → `CacheMap`; `String...` → `String`; `int[]` → `int`.
    public static String bareType(String t) {
        String s = t == null ? "" : t;
        s = TYPE_ARGS.matcher(s).replaceFirst("");
        s = ARRAY_OR_VARARGS.matcher(s).replaceAll("");
        return s.trim();
    }

    /// The types this class's constructor demands — the ones a test must supply to build it at
    /// all. Primitives and java.lang types are excluded: nobody fails to construct a String.
    public static List<String> constructorTypes(String source, String className) {
        String code = JavaSrc.stripNonCode(source == null ? "" : source);
        Set<String> out = new LinkedHashSet<>();
        Pattern re = Pattern.compile("\\b" + Pattern.quote(className == null ? "" : className)
                + "\\s*\\(([^)]*)\\)\\s*\\{");
        Matcher m = re.matcher(code);
        while (m.find()) {
            // Type arguments have to go before anything is split: the comma in
            // `CacheMap<Object, String> m` is not a parameter separator, and splitting on it
            // leaves `String>` sitting where the type should be — which reads as java.lang.String
            // and drops the collaborator silently.
            String params = m.group(1);
            for (String prev = null; !params.equals(prev); ) {
                prev = params;
                params = INNERMOST_TYPE_ARGS.matcher(params).replaceAll("");
            }
            // -1: JS split keeps trailing empty segments and Java's default drops them, and a
            // dropped segment here is a parameter that stops being examined.
            for (String raw : params.split(",", -1)) {
                List<String> parts = new ArrayList<>();
                for (String p : WHITESPACE.split(raw.trim())) if (!p.isEmpty()) parts.add(p);
                if (parts.size() < 2) continue;                 // no type + name pair
                String t = bareType(parts.get(parts.size() - 2));
                if (!t.isEmpty() && CAPITALISED.matcher(t).find() && !BUILT_IN.contains(t)) out.add(t);
            }
        }
        return new ArrayList<>(out);
    }

    // interface methods have no modifier; class methods need public
    private static final Pattern METHOD_SIG = Pattern.compile(
            "(?:^|\n)\\s*(?:public\\s+|protected\\s+)?(?:abstract\\s+|default\\s+|static\\s+|final\\s+)*"
                    + "([A-Za-z_$][\\w$<>,.\\s\\[\\]]*?)\\s+([A-Za-z_$][\\w$]*)\\s*\\(([^)]*)\\)\\s*[;{]");

    private static final List<String> CONTROL_FLOW = List.of("if", "for", "while", "switch", "catch");

    /// Every public/abstract method signature of a type, WITH parameter types.
    ///
    /// The types matter more than the names: "call incrementBatchLoadCountBy()" when it takes a
    /// long produces a test that does not compile, which is how six rounds went on
    /// XMLTokener#isValidDecimal.
    public static List<String> publicApi(String source) {
        String code = JavaSrc.stripNonCode(source == null ? "" : source);
        List<String> out = new ArrayList<>();
        Matcher m = METHOD_SIG.matcher(code);
        while (m.find()) {
            String ret = m.group(1).trim(), name = m.group(2), args = m.group(3).trim();
            if (ret.isEmpty() || ret.equals("return") || ret.equals("new")) continue;
            if (CONTROL_FLOW.contains(name)) continue;
            String sig = ret + " " + name + "(" + args + ")";
            if (!out.contains(sig)) out.add(sig);
        }
        return out;
    }

    private static final Pattern CLASS_DECL =
            Pattern.compile("\\b(?:public\\s+)?(abstract\\s+)?class\\s+([A-Za-z_$][\\w$]*)([^{]*)\\{");

    /// Classes in the project that a test can instantiate as this type.
    ///
    /// Abstract ones are excluded — `new AbstractCollector()` does not compile, and offering it
    /// would trade one unknown symbol for another.
    public static List<String> concreteImplementors(String typeName, List<SourceFile> files) {
        List<String> out = new ArrayList<>();
        Pattern mentionsType = Pattern.compile("\\b" + Pattern.quote(typeName == null ? "" : typeName) + "\\b");
        for (SourceFile f : files == null ? List.<SourceFile>of() : files) {
            String src = JavaSrc.stripNonCode(f == null || f.source() == null ? "" : f.source());
            Matcher m = CLASS_DECL.matcher(src);
            if (!m.find()) continue;
            if (m.group(1) != null) continue;               // abstract — cannot be instantiated
            String decl = m.group(3) == null ? "" : m.group(3);
            if (!mentionsType.matcher(decl).find()) continue;
            if (!out.contains(m.group(2))) out.add(m.group(2));
        }
        return out;
    }
}
