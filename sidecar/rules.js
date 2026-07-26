'use strict';
// Per-stage team rules. Rule text comes from env/run config; application = LLM
// interpretation with mechanical guardrails; results recorded in state.decisions.
const { state, event } = require('./state');
const { choosePick } = require('./pick');
const { readFileSafe } = require('./repo');
const { chat } = require('./llm');

function rules() { return state.run?.config?.rules || {}; }

function repoContextHead() {
  const parts = [];
  for (const f of ['AGENTS.md', 'CONTRIBUTING.md', 'README.md']) {
    const c = readFileSafe(f, 4000);
    if (c) parts.push(`--- ${f} (head) ---\n${c}`);
  }
  return parts.join('\n\n') || '(no AGENTS.md / CONTRIBUTING.md / README.md found)';
}

/** Free-text guidance assembled for test-writing prompts. */
function testWritingConstraints() {
  const out = [];
  const d = state.decisions || {};
  if (d.post_clone?.constraints?.length) out.push(...d.post_clone.constraints);
  if (rules().write_test) out.push(rules().write_test);
  return out;
}

async function apply(stage, context = {}) {
  const ruleText = rules()[stage] || '';
  let result;
  switch (stage) {
    case 'post_clone': result = await applyPostClone(ruleText); break;
    case 'pre_pick': result = await applyPrePick(ruleText); break;
    case 'pick_file': result = await applyPickFile(ruleText, context); break;
    case 'write_test': result = { constraints: testWritingConstraints() }; break;
    case 'check_changes': result = await applyCheckChanges(ruleText, context); break;
    case 'make_pr': result = await applyMakePr(ruleText, context); break;
    default: throw new Error('unknown rule stage: ' + stage);
  }
  state.decisions[stage] = { rule: ruleText, result, ts: Date.now() };
  event('rules', `applied ${stage} rules: ${JSON.stringify(result).slice(0, 240)}`);
  return result;
}

async function applyPostClone(ruleText) {
  if (!ruleText) return { constraints: [], notes: 'no post-clone rules configured' };
  const r = await chat({
    system: 'You are configuring an automated test-improvement pipeline. Extract actionable constraints from the team rule and repo docs. Reply ONLY with JSON: {"constraints": ["short imperative constraint", ...], "notes": "one line"}. Constraints are things the pipeline must honor when writing tests, naming branches, or making PRs.',
    prompt: `TEAM RULE (post-clone): ${ruleText}\n\nREPO DOCS:\n${repoContextHead()}`,
    json: true, maxTokens: 1200,
  });
  return r.json || { constraints: [ruleText], notes: 'LLM parse failed, using raw rule text' };
}

async function applyPrePick(ruleText) {
  const dflt = { branchTemplate: 'tests/improve-{file}', notes: 'default branch naming' };
  if (!ruleText) return dflt;
  const r = await chat({
    system: 'You configure branching for an automated test-improvement pipeline. From the team rule, produce JSON only: {"branchTemplate": "template containing {file} placeholder", "notes": "one line"}. The template must be a valid git branch name pattern; {file} will be replaced by a slug of the source file.',
    prompt: `TEAM RULE (before picking a file): ${ruleText}\n\nREPO DOCS:\n${repoContextHead()}`,
    json: true, maxTokens: 800,
  });
  const j = r.json;
  if (!j?.branchTemplate || !j.branchTemplate.includes('{file}')) return { ...dflt, notes: 'rule did not yield a usable template' };
  j.branchTemplate = j.branchTemplate.replace(/[^a-zA-Z0-9/_{}.-]+/g, '-');
  return j;
}

async function applyPickFile(ruleText, { candidates = [] }) {
  // The candidates arrive already ranked weakest-first, with unreachable units removed
  // (select.rankUnits). The model's job here is the TEAM RULE and nothing else: which
  // candidates does it exclude? It used to choose outright, and chose the fifth-ranked
  // unit — a private method three hops from any public entry — over three directly
  // callable ones. Measurements choose; the rule vetoes.
  if (!candidates.length) return choosePick([], []);
  if (!ruleText) return choosePick(candidates, []);

  const table = candidates.slice(0, 80).map((c) =>
    `${c.path} | ${c.method ? 'method=' + c.method + '() ' : ''}coverage=${c.coverage ?? '?'}% `
    + `mutation=${c.mutation ?? '?'}% mac=${c.mac ?? '?'} lines=${c.executableLines ?? c.lines ?? '?'}`).join('\n');
  const r = await chat({
    system: 'You apply ONE team rule to a list of candidate units for automated Java test improvement.'
      + ' A unit is "<source file>::<method>". Do NOT choose which to work on — that is decided by measurements.'
      + ' Your ONLY job: list the candidates the team rule EXCLUDES.'
      + ' Reply ONLY with JSON: {"exclude": ["<exact unit string, or a source file path to exclude all its methods>", ...], "reason": "one line"}.'
      + ' Exclude nothing unless the rule actually says so — an empty list is the normal answer for a rule that only expresses a preference.'
      + ' Never exclude a unit merely for being small, well covered, or unmeasured.',
    prompt: `TEAM RULE (how to pick a file): ${ruleText}\n\nCANDIDATES:\n${table}`,
    json: true, maxTokens: 900, stage: 'picking_file',
  });
  const j = r.json;
  if (!j || !Array.isArray(j.exclude)) {
    // a transient model hiccup: retry rather than pick blind and risk violating the rule
    state.pickFailures = (state.pickFailures || 0) + 1;
    const retry = state.pickFailures < 3;
    event('picking_file', `pick rule could not be applied (invalid LLM output, ${state.pickFailures}/3) — `
      + (retry ? 'retrying' : 'giving up on this batch'));
    return { file: null, retry, reason: 'pick rule could not be applied reliably; refusing a pick that might violate it' };
  }
  state.pickFailures = 0;
  const decision = choosePick(candidates, j.exclude);
  if (j.exclude.length) {
    event('picking_file', `team rule excluded ${j.exclude.length} candidate(s)`
      + (j.reason ? `: ${String(j.reason).slice(0, 160)}` : ''));
  }
  return decision;
}


async function applyCheckChanges(ruleText, ctx) {
  // Mechanical gate first: tests must be green and MAC must strictly improve.
  const mech = {
    testsGreen: !!ctx.testsGreen,
    macImproved: (ctx.macAfter ?? 0) > (ctx.macBefore ?? 0),
  };
  if (!mech.testsGreen || !mech.macImproved) {
    return { approved: false, reason: !mech.testsGreen ? 'test suite is red' : `MAC did not improve (${ctx.macBefore} → ${ctx.macAfter})`, mechanical: mech };
  }
  if (!ruleText) return { approved: true, reason: `MAC ${ctx.macBefore} → ${ctx.macAfter}, suite green`, mechanical: mech };
  const r = await chat({
    system: 'You review automated test changes against a team rule. Mechanical checks already passed (suite green, MAC improved). Judge ONLY the team rule. Reply ONLY with JSON: {"approved": true/false, "reason": "one line"}.',
    prompt: `TEAM RULE (how to check changes are good): ${ruleText}\n\nFILE: ${ctx.file}\nMAC: ${ctx.macBefore} → ${ctx.macAfter} (coverage ${ctx.coverageBefore} → ${ctx.coverageAfter}, mutation ${ctx.mutationBefore} → ${ctx.mutationAfter})\n\nDIFF OF CHANGES:\n${String(ctx.diff || '').slice(0, 12000)}`,
    json: true, maxTokens: 800,
  });
  const j = r.json || { approved: true, reason: 'LLM verdict unparseable — mechanical checks passed' };
  j.mechanical = mech;
  return j;
}

async function applyMakePr(ruleText, ctx) {
  const dfltTitle = `test: improve mutation-adjusted coverage of ${ctx.file}`;
  const dfltBody = [
    `## Test improvements for \`${ctx.file}\``, '',
    `| metric | before | after |`, `|---|---|---|`,
    `| line coverage | ${ctx.coverageBefore}% | ${ctx.coverageAfter}% |`,
    `| mutation score | ${ctx.mutationBefore}% | ${ctx.mutationAfter}% |`,
    `| **MAC** | **${ctx.macBefore}** | **${ctx.macAfter}** |`, '',
    'Generated by the improve-javascript-tests pipeline.',
  ].join('\n');
  const dflt = { title: dfltTitle, body: dfltBody, labels: [] };
  if (!ruleText) return dflt;
  const r = await chat({
    system: 'You prepare a GitHub pull request for automated test improvements, following the team rule for PR style. Reply ONLY with JSON: {"title": "...", "body": "markdown", "labels": ["..."]}. Include the before/after metrics table in the body.',
    prompt: `TEAM RULE (how to make a PR): ${ruleText}\n\nREPO DOCS:\n${repoContextHead()}\n\nFILE: ${ctx.file}\nBRANCH: ${ctx.branch}\nMETRICS: coverage ${ctx.coverageBefore}%→${ctx.coverageAfter}%, mutation ${ctx.mutationBefore}%→${ctx.mutationAfter}%, MAC ${ctx.macBefore}→${ctx.macAfter}\nCHANGED TEST FILES: ${(ctx.changedFiles || []).join(', ')}`,
    json: true, maxTokens: 2000,
  });
  const j = r.json;
  if (!j?.title || !j?.body) return dflt;
  if (!Array.isArray(j.labels)) j.labels = [];
  return j;
}

module.exports = { apply, testWritingConstraints, rules };
