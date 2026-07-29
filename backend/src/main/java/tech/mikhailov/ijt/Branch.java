package tech.mikhailov.ijt;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Which branch to work on.
///
/// The configured branch used to be handed straight to `git clone --branch` / `git fetch`,
/// so pointing the pipeline at a repo whose default is `master` while REPO_BRANCH still
/// said `main` ended the entire run at the first step with "couldn't find remote ref main".
/// A team naming a branch that exists must get exactly that branch; a team that named
/// nothing, or named one this repo does not have, gets the repo's own default and is told.
public final class Branch {

    private Branch() {}

    /// The outcome of {@link #resolveBranch}.
    ///
    /// @param branch        the branch this run will actually use
    /// @param fellBack      true when `configured` named a branch the remote does not have
    /// @param defaultBranch the repo's own default. The JS field is called `default`, which
    ///                      is a keyword in Java.
    /// @param reason        null unless `fellBack` — the sentence the caller reports
    public record Resolved(String branch, boolean fellBack, String defaultBranch, String reason) {}

    /// `git ls-remote --symref <url>`: the symref line names the repo's default branch,
    /// the rest are its actual refs.
    private static final Pattern SYMREF = Pattern.compile("^ref:\\s+refs/heads/(\\S+)\\s+HEAD$");
    private static final Pattern HEAD = Pattern.compile("^\\S+\\s+refs/heads/(\\S+)$");

    /// Only consulted when the remote sent no symref line.
    private static final List<String> CONVENTIONAL = List.of("main", "master", "trunk", "develop");

    /// @param configured REPO_BRANCH (null, "" or "auto" = whatever the repo defaults to)
    /// @param lsRemote   stdout of `git ls-remote --symref <url>`
    public static Resolved resolveBranch(String configured, String lsRemote) {
        String text = lsRemote == null ? "" : lsRemote;
        List<String> heads = new ArrayList<>();
        String symref = null;
        for (String line : text.split("\n", -1)) {
            Matcher sym = SYMREF.matcher(line);
            if (sym.matches()) { symref = sym.group(1); continue; }
            Matcher head = HEAD.matcher(line);
            if (head.matches()) heads.add(head.group(1));
        }
        if (heads.isEmpty()) {
            throw new IllegalArgumentException(
                    "remote has no branches (is the URL reachable, and does it need credentials?)");
        }
        // the symref is authoritative; without one, fall back to convention and then to the
        // first branch the remote lists
        String def = symref != null && heads.contains(symref)
                ? symref
                : CONVENTIONAL.stream().filter(heads::contains).findFirst().orElse(heads.get(0));

        String want = configured == null ? "" : configured.trim();
        if (want.isEmpty() || want.equals("auto") || want.equals("default")) {
            return new Resolved(def, false, def, null);
        }
        if (heads.contains(want)) return new Resolved(want, false, def, null);
        return new Resolved(def, true, def,
                "this repo has no branch \"" + want + "\" — using its default branch \"" + def + "\" instead");
    }

    /// Start the unit's branch afresh from the base, or continue the one already there.
    public enum Action { REUSE, RESET }

    /// @param action what to do with `branch`
    /// @param branch the branch the decision is about, echoed back for the caller
    /// @param reason the sentence the caller reports — never null
    public record Decision(Action action, String branch, String reason) {}

    /// Whether to start a unit's branch afresh from the base, or continue on the branch that
    /// is already there.
    ///
    /// The branch is per FILE (that is what a PR is), but the unit of work is a METHOD. So the
    /// second method of a file lands on a branch that already carries the first method's
    /// accepted, committed — and possibly already PR'd — rounds. `git checkout -B <branch>
    /// <base>` moved that branch back to the base, and the later force-push rewrote the PR to
    /// contain only the second method's test. The first method's work disappeared from a pull
    /// request that had already been opened.
    ///
    /// A branch left behind by an EARLIER run is a different matter: its commits belong to a
    /// run that is over, the base may have moved, and starting from it would smuggle unmeasured
    /// changes into this run's baseline. Those are reset.
    ///
    /// @param branch the branch name for this file
    /// @param owner  the id of the run that last claimed that branch, or null if none did
    /// @param runId  this run's id
    public static Decision branchAction(String branch, String owner, String runId) {
        // The JS guard is `owner && runId && owner === runId`, where an empty string is falsy.
        // A blank owner is no owner: it must not read as "this run already worked here".
        boolean owned = owner != null && !owner.isEmpty();
        boolean identified = runId != null && !runId.isEmpty();
        if (owned && identified && owner.equals(runId)) {
            return new Decision(Action.REUSE, branch,
                    "this run already committed work for this file on that branch");
        }
        return new Decision(Action.RESET, branch,
                owned ? "the branch is left over from an earlier run" : "no branch from this run yet");
    }
}
