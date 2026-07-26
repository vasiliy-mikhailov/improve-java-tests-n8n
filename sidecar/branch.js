'use strict';
// Which branch to work on.
//
// The configured branch used to be handed straight to `git clone --branch` / `git fetch`,
// so pointing the pipeline at a repo whose default is `master` while REPO_BRANCH still
// said `main` ended the entire run at the first step with "couldn't find remote ref main".
// A team naming a branch that exists must get exactly that branch; a team that named
// nothing, or named one this repo does not have, gets the repo's own default and is told.

/**
 * @param {string|null} configured  REPO_BRANCH ('', null or 'auto' = whatever the repo defaults to)
 * @param {string} lsRemote  stdout of `git ls-remote --symref <url>`
 * @returns {{branch:string, fellBack:boolean, default:string, reason?:string}}
 */
function resolveBranch(configured, lsRemote) {
  const text = String(lsRemote || '');
  const heads = [];
  let symref = null;
  for (const line of text.split('\n')) {
    const sym = line.match(/^ref:\s+refs\/heads\/(\S+)\s+HEAD$/);
    if (sym) { symref = sym[1]; continue; }
    const head = line.match(/^\S+\s+refs\/heads\/(\S+)$/);
    if (head) heads.push(head[1]);
  }
  if (!heads.length) {
    throw new Error('remote has no branches (is the URL reachable, and does it need credentials?)');
  }
  // the symref is authoritative; without one, fall back to convention and then to the
  // first branch the remote lists
  const def = (symref && heads.includes(symref) ? symref : null)
    || ['main', 'master', 'trunk', 'develop'].find((b) => heads.includes(b))
    || heads[0];

  const want = String(configured || '').trim();
  if (!want || want === 'auto' || want === 'default') return { branch: def, fellBack: false, default: def };
  if (heads.includes(want)) return { branch: want, fellBack: false, default: def };
  return {
    branch: def,
    fellBack: true,
    default: def,
    reason: `this repo has no branch "${want}" — using its default branch "${def}" instead`,
  };
}

/**
 * Whether to start a unit's branch afresh from the base, or continue on the branch that
 * is already there.
 *
 * The branch is per FILE (that is what a PR is), but the unit of work is a METHOD. So the
 * second method of a file lands on a branch that already carries the first method's
 * accepted, committed — and possibly already PR'd — rounds. `git checkout -B <branch>
 * <base>` moved that branch back to the base, and the later force-push rewrote the PR to
 * contain only the second method's test. The first method's work disappeared from a pull
 * request that had already been opened.
 *
 * A branch left behind by an EARLIER run is a different matter: its commits belong to a
 * run that is over, the base may have moved, and starting from it would smuggle unmeasured
 * changes into this run's baseline. Those are reset.
 */
function branchAction({ branch, owner, runId } = {}) {
  if (owner && runId && owner === runId) {
    return { action: 'reuse', branch, reason: 'this run already committed work for this file on that branch' };
  }
  return {
    action: 'reset',
    branch,
    reason: owner ? 'the branch is left over from an earlier run' : 'no branch from this run yet',
  };
}

module.exports = { resolveBranch, branchAction };
