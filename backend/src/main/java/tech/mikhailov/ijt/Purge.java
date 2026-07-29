package tech.mikhailov.ijt;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/// Starting a repo again from nothing.
///
/// The pipeline is deliberately incremental: ledgers remember which units were settled and
/// what they measured, so a second batch does not redo the first. When the measurement
/// semantics change — or when you simply want to see the whole repo improved from a clean
/// slate — that memory has to go, along with the artifacts it points at: the prepared PR
/// patches and the per-file branches carrying earlier generated tests.
///
/// Deletion is narrow on purpose. Only patches under the prs directory and only branches
/// this pipeline creates are ever named; a ledger is data, and data can be wrong.
public final class Purge {

    private Purge() {}

    /// The branch names this pipeline creates — and the only ones it will ever delete.
    ///
    /// The caller that sweeps branches git itself lists (not only the ones the ledger knows
    /// about) tests each name against this, so it is public exactly as the JS export is.
    /// Match it with {@link #isOurs} or `find()`: the pattern is anchored at the start and
    /// says nothing about the rest of the name, so Java's whole-string `matches()` — the
    /// obvious-looking translation of JS `.test()` — would reject every real branch.
    public static final Pattern OURS = Pattern.compile("^tests/improve-");

    /// Where prepared PR artifacts live when the caller does not say.
    public static final String DEFAULT_PRS_DIR = "/data/prs";

    /// The JS `OURS.test(branch)`, including its falsy guard on an absent name.
    public static boolean isOurs(String branch) {
        return branch != null && !branch.isEmpty() && OURS.matcher(branch).find();
    }

    /// One entry of the improvement ledger, as far as purging is concerned.
    ///
    /// The live record carries more (`state`, `prUrl`, metrics — see {@link Measure.Improvement}),
    /// but this pass reads only the two fields that name something on disk. Both are nullable:
    /// a unit that produced no patch still has a branch, and the JS guards each field
    /// separately for exactly that case.
    ///
    /// @param branch     the per-file branch carrying the generated tests, or null
    /// @param patchPath  the prepared PR patch, or null when the unit produced none
    public record Entry(String branch, String patchPath) {}

    /// What a purge would remove.
    ///
    /// @param files     prepared PR artifacts to unlink, deduplicated, first-seen order
    /// @param branches  local branches to delete, deduplicated, first-seen order
    public record Plan(List<String> files, List<String> branches) {}

    /// @param ledger  the improvement ledger for ONE repo (unit key → record)
    public static Plan purgePlan(Map<String, Entry> ledger) {
        return purgePlan(ledger, DEFAULT_PRS_DIR);
    }

    /// @param ledger  the improvement ledger for ONE repo (unit key → record)
    /// @param prsDir  where prepared PR artifacts live
    public static Plan purgePlan(Map<String, Entry> ledger, String prsDir) {
        // insertion-ordered and deduplicating: two units of one file share a patch and a
        // branch, and each must be named once
        Set<String> files = new LinkedHashSet<>();
        Set<String> branches = new LinkedHashSet<>();
        for (Entry record : (ledger == null ? Map.<String, Entry>of() : ledger).values()) {
            if (record == null) continue;
            // the JS guard is falsy, so an empty string is "no patch" and never reaches dirname
            if (record.patchPath() != null && !record.patchPath().isEmpty()) {
                String p = record.patchPath();
                // never step outside the artifact directory, whatever the ledger says
                if (dirname(p).equals(prsDir)) {
                    files.add(p);
                    files.add(p.replaceFirst("\\.patch$", ".json"));   // the PR payload beside it
                }
            }
            if (isOurs(record.branch())) branches.add(record.branch());
        }
        return new Plan(List.copyOf(files), List.copyOf(branches));
    }

    /// Node's `path.dirname` over POSIX paths, which is what the ledger holds.
    ///
    /// Deliberately not `java.nio.file.Path#getParent`: that normalises the path, is
    /// platform-dependent, and answers null where node answers "." — and the comparison above
    /// is the containment gate that keeps a wrong ledger from naming /etc/passwd for deletion.
    /// A gate has to mean exactly what it meant before.
    static String dirname(String path) {
        if (path == null || path.isEmpty()) return ".";
        boolean hasRoot = path.charAt(0) == '/';
        int end = -1;
        boolean matchedSlash = true;
        // from the end, skipping any trailing slashes, to the separator before the last segment
        for (int i = path.length() - 1; i >= 1; i--) {
            if (path.charAt(i) == '/') {
                if (!matchedSlash) { end = i; break; }
            } else {
                matchedSlash = false;
            }
        }
        if (end == -1) return hasRoot ? "/" : ".";
        if (hasRoot && end == 1) return "//";
        return path.substring(0, end);
    }
}
