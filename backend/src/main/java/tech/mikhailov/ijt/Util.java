package tech.mikhailov.ijt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/// Helpers shared across the backend.
public final class Util {

    private Util() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /// Split a comma-separated glob list WITHOUT breaking brace groups:
    /// `src/**;/*.{js,ts},lib/*.ts` → [`src/**;/*.{js,ts}`, `lib/*.ts`].
    /// A naive split on ',' shreds `{js,ts}` into separate globs that match nothing.
    public static List<String> splitGlobList(String globCsv) {
        String src = (globCsv == null || globCsv.isEmpty()) ? "**/*" : globCsv;
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < src.length(); i++) {
            char ch = src.charAt(i);
            if (ch == '{') depth += 1;
            else if (ch == '}') depth = Math.max(0, depth - 1);
            if (ch == ',' && depth == 0) { out.add(cur.toString()); cur.setLength(0); continue; }
            cur.append(ch);
        }
        out.add(cur.toString());
        return out.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /// A matcher over repo-relative paths for a comma-separated glob list.
    public static Predicate<String> globsToMatcher(String globCsv) {
        List<Pattern> regs = splitGlobList(globCsv).stream().map(Util::globToRegExp).toList();
        return p -> regs.stream().anyMatch(r -> r.matcher(p).matches());
    }

    public static Pattern globToRegExp(String glob) {
        StringBuilder re = new StringBuilder();
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    // '**/' spans directories and also matches none of them; '**' spans anything
                    if (i + 2 < glob.length() && glob.charAt(i + 2) == '/') { re.append("(?:.*/)?"); i += 3; }
                    else { re.append(".*"); i += 2; }
                } else { re.append("[^/]*"); i += 1; }
            } else if (c == '?') { re.append("[^/]"); i += 1; }
            else if (c == '{') {
                int end = glob.indexOf('}', i);
                if (end == -1) { re.append("\\{"); i += 1; }
                else {
                    String[] alts = glob.substring(i + 1, end).split(",", -1);
                    re.append("(?:");
                    for (int a = 0; a < alts.length; a++) {
                        if (a > 0) re.append('|');
                        re.append(escapeRe(alts[a]));
                    }
                    re.append(')');
                    i = end + 1;
                }
            } else { re.append(escapeRe(String.valueOf(c))); i += 1; }
        }
        return Pattern.compile("^" + re + "$");
    }

    /// Escapes only what the glob translator does not handle itself — `*`, `?`, `{` and `}`
    /// are deliberately absent, because they carry glob meaning and are consumed above.
    static String escapeRe(String s) {
        return s.replaceAll("([.+^$()|\\[\\]\\\\])", "\\\\$1");
    }

    /// `Locale.ROOT`, not the default locale. `String.prototype.toLowerCase` is defined against
    /// the Unicode default case mappings and is locale-INDEPENDENT; `String.toLowerCase()`
    /// resolves against `Locale.getDefault()`, and under `tr-TR` it maps `I` to `ı` (dotless).
    /// This slug is the key of improvedLedger, measureLedger and overheadLedger and names the
    /// local PR patch files, so a host whose locale differs would make a resumed run fail to
    /// find its own ledger: `https://github.com/API/Item.git` slugs as `github-com-ap-tem`
    /// there and `github-com-api-item` everywhere else.
    public static String slugify(String s) {
        String out = String.valueOf(s).toLowerCase(java.util.Locale.ROOT)
                .replaceAll("^https?://", "")
                .replaceAll("\\.git$", "")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return out.length() > 80 ? out.substring(0, 80) : out;
    }

    public static String fileSlug(String p) {
        String out = String.valueOf(p)
                .replaceAll("\\.[jt]sx?$", "")
                .replaceAll("[^a-zA-Z0-9]+", "-")
                .replaceAll("^-+|-+$", "")
                // Locale.ROOT for the reason slugify gives: `SRC/MAIN/Item.java` must not become
                // `src-maın-ıtem-java` on a Turkish host
                .toLowerCase(java.util.Locale.ROOT);
        return out.length() > 60 ? out.substring(0, 60) : out;
    }

    public static long nowSec() { return System.currentTimeMillis() / 1000; }

    public static double round2(double x) { return Math.round(x * 100.0) / 100.0; }

    /// MAC = coverage% × mutation% / 100, both in [0,100]. Null when either is unknown —
    /// an unknown metric is not a zero, and reporting it as one flatters the result.
    public static Double mac(Double coveragePct, Double mutationPct) {
        if (coveragePct == null || mutationPct == null) return null;
        return round2((coveragePct * mutationPct) / 100.0);
    }

    /// The first balanced JSON object or array in LLM output, or null.
    public static JsonNode extractJson(String text) {
        if (text == null || text.isEmpty()) return null;
        String s = String.valueOf(text)
                .replaceAll("(?s)^.*?</think>", "")   // drop a thinking block if present
                .replaceAll("```(?:json)?", "");
        // Try whichever bracket opens FIRST — otherwise a top-level array response
        // ("[{...}]") yields its first element instead of the array.
        List<Character> openers = new ArrayList<>();
        int braceAt = s.indexOf('{'), bracketAt = s.indexOf('[');
        if (braceAt != -1 && (bracketAt == -1 || braceAt < bracketAt)) {
            openers.add('{'); if (bracketAt != -1) openers.add('[');
        } else if (bracketAt != -1) {
            openers.add('['); if (braceAt != -1) openers.add('{');
        }
        for (char opener : openers) {
            int start = s.indexOf(opener);
            char closer = opener == '{' ? '}' : ']';
            int depth = 0;
            boolean inStr = false, esc = false;
            for (int i = start; i < s.length(); i++) {
                char c = s.charAt(i);
                if (esc) { esc = false; continue; }
                if (c == '\\') { esc = true; continue; }
                if (c == '"') { inStr = !inStr; continue; }
                if (inStr) continue;
                if (c == opener) depth++;
                else if (c == closer) {
                    depth--;
                    if (depth == 0) {
                        try { return MAPPER.readTree(s.substring(start, i + 1)); }
                        catch (Exception e) { break; }
                    }
                }
            }
        }
        return null;
    }

    public static int clamp(int x, int lo, int hi) { return Math.max(lo, Math.min(hi, x)); }

    private static final Pattern GH_TOKEN = Pattern.compile("\\b(gh[pousr]_[A-Za-z0-9]{16,})");
    private static final Pattern GH_PAT = Pattern.compile("\\b(github_pat_[A-Za-z0-9_]{20,})");
    private static final Pattern API_KEY = Pattern.compile("\\bsk-[A-Za-z0-9-]{16,}");
    private static final Pattern URL_CREDS = Pattern.compile("(https?://)[^/@\\s:]+:[^/@\\s]+@");

    /// Strip secrets from anything reaching the event log, the dashboard or an HTTP
    /// response. Tool output (git, gh) can echo credentials we passed in.
    public static String redact(String text) {
        String s = text == null ? "" : text;
        for (String var : new String[]{"GH_TOKEN", "LLM_API_KEY"}) {
            String secret = System.getenv(var);
            if (secret != null && secret.length() >= 8) s = s.replace(secret, "«redacted»");
        }
        s = GH_TOKEN.matcher(s).replaceAll("«redacted-gh-token»");
        s = GH_PAT.matcher(s).replaceAll("«redacted-gh-token»");
        s = API_KEY.matcher(s).replaceAll("«redacted-api-key»");
        // `$1` is the URL scheme, put back verbatim — the JS replacement is '$1«redacted»@'.
        // Quoting it (Matcher.quoteReplacement) would emit the literal characters "$1" and
        // destroy the scheme, turning `https://user:pw@host` into `$1«redacted»@host`.
        s = URL_CREDS.matcher(s).replaceAll("$1«redacted»@");
        return s;
    }

    /// A metric for human eyes: the number when we have one, "?" when we do not.
    ///
    /// A live round line read "cov undefined→0, mut undefined→0, mac null→0" for a unit whose
    /// every stored field was correctly null. The ledger invented nothing; the sentence did.
    /// Zero stays zero — 0% mutation is a real and common measurement, and hiding it would be
    /// the opposite error.
    public static String showMetric(Double v) {
        return (v == null || v.isNaN()) ? "?" : trimNumber(v);
    }

    /// JS prints 100 rather than 100.0, and these strings go in front of people.
    private static String trimNumber(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) return String.valueOf((long) v);
        return String.valueOf(v);
    }
}
