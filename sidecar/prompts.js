'use strict';
// Prompt construction. This used to live as JavaScript strings inside the workflow JSON,
// where it had no compiler, no linter and no tests — and duly shipped defects that only an
// hours-long run revealed: an identifier that was never defined, and an edit that silently
// matched nothing so the deployed prompt kept asking the model to choose a mutant days
// after that decision had moved into the pipeline.
//
// The workflow now calls these over HTTP and stays orchestration-only.

/** What a generated Java test must satisfy to compile, run and be worth committing. */
const COMMON_TEST_RULES = `
- ONE test file only. Its public class name MUST equal the file name, and the package declaration MUST match the directory under src/test/java.
- Use the SAME test framework and assertion library as the style reference (JUnit 5: org.junit.jupiter.api.Test/Assertions; JUnit 4: org.junit.Test/org.junit.Assert). Do NOT introduce a dependency the project does not already have.
- Call only the PUBLIC API of the class under test. Never use reflection, setAccessible, or read source/bytecode.
- Do NOT modify production code, existing tests, or the build files.
- Deterministic: no network, no clock/randomness without fixing them, no reliance on file-system state or test execution order.
- Every test must ASSERT a value or an observable side effect; a test that only checks "does not throw" is worthless.
- The tests MUST compile and PASS against the CURRENT implementation.`;

/** PIT mutator → the assertion that detects it. */
const MUTATOR_PLAYBOOK = `
Mutator → the assertion that detects it:
- ConditionalsBoundary (< ↔ <=, > ↔ >=): exercise the value EXACTLY at the boundary and assert the branch taken there.
- NegateConditionals (== ↔ !=, < ↔ >=): assert behaviour on BOTH sides of the condition.
- Math (+ ↔ -, * ↔ /, %): choose inputs where the two operations differ; assert the exact numeric result.
- Increments (i++ ↔ i--): assert the final accumulated/counted value.
- ReturnVals / NullReturnVals / PrimitiveReturnVals / BooleanTrueReturnVals / EmptyObjectReturnVals: assert the ACTUAL returned value or the returned object's contents — never merely that it is non-null.
- VoidMethodCall / ConstructorCall: assert the observable SIDE EFFECT of the removed call.
- RemoveConditional: assert that the guarded behaviour does NOT happen when the condition is false.
- InlineConstant: assert the exact constant-derived value.
- Switch: assert the outcome for each case label, including the default.`;

const frameworkName = (f) => (f === 'junit4' ? 'JUnit 4' : f === 'testng' ? 'TestNG' : 'JUnit 5');

/**
 * The source the model needs: package + imports + class declaration, the target method in
 * full, and sibling signatures for building inputs. Sending the whole class instead is
 * what made every call slow — 12k characters of a 1500-line file to produce a 400-byte test.
 */
function sourceBlock(gaps, limit) {
  if (!gaps.methodSource) return String(gaps.source || '').slice(0, limit);
  return `${gaps.classHeader || ''}\n\n  // … other members omitted …\n\n${gaps.methodSource}`;
}

function signatureBlock(gaps) {
  const sigs = gaps.siblingSignatures || [];
  return sigs.length
    ? `\n\nOTHER MEMBERS (signatures only, for building inputs):\n${sigs.slice(0, 40).join('\n')}`
    : '';
}

/**
 * A test cannot call a private (or package-private, from another package) method, and
 * reflection is forbidden — so it has to arrive through something public. Told none of
 * this, the model wrote a compiling, passing test for JSONObject's private
 * isRecordStyleAccessor that executed none of it: coverage 0 → 0, all 14 mutants alive.
 */
function reachBlock(gaps) {
  const vis = gaps.visibility;
  if (!vis || vis === 'public') return '';
  // every caller, not just the public ones: the route is often a chain (a public
  // constructor → a private helper → the target), and the model needs to see it
  const list = (gaps.callers || [])
    .map((c) => `- ${c.method}()${c.visibility === 'public' ? ' [public — a test can call this]' : ` [${c.visibility}, reachable only through one of the others]`}`)
    .join('\n');
  return `\n\nREACHED VIA: ${gaps.method}() is ${vis}, so a test CANNOT call it directly and must NOT use reflection.`
    + ` Reach it by calling one of these, choosing arguments that make execution flow into ${gaps.method}():\n${list}`
    + `\nAssert on what that public call returns or changes — that is what distinguishes the real code from the mutation.`;
}

/** A private method nothing calls cannot be executed by any test. */
function unreachable(gaps) {
  const vis = gaps.visibility;
  return vis && vis !== 'public' && !(gaps.callers || []).length;
}

function constraintBlock(gaps) {
  const c = (gaps.constraints || []).map((x) => `- ${x}`).join('\n');
  return c ? `\nTeam constraints:\n${c}` : '';
}

/**
 * Coverage phase — only for a method NOTHING executes. Above `covPhaseMaxPct` the mutation
 * phase raises coverage by itself: killing a NO_COVERAGE mutant means running its line.
 */
function coveragePrompt(gaps) {
  const targetPath = gaps.covTestPath;
  const u = gaps.uncovered || {};
  const fullyUncovered = u.lines === 'all';
  const missed = fullyUncovered ? Infinity : (u.lines || []).length;
  const base = { targetPath, projectTestPath: gaps.projectTestPath || null };

  if (missed === 0) return { ...base, skip: true, reason: 'method fully covered' };
  if (!fullyUncovered && gaps.coverage != null && gaps.coverage > (gaps.covPhaseMaxPct ?? 0)) {
    return {
      ...base,
      skip: true,
      reason: `method already executed (${gaps.coverage}% covered) — mutation tests will extend coverage as they kill NO_COVERAGE mutants`,
    };
  }

  if (unreachable(gaps)) {
    return { ...base, skip: true, reason: `${gaps.method}() is ${gaps.visibility} and has no caller in this class — no test can execute it, so there is nothing to write` };
  }

  const testClass = targetPath.split('/').pop().replace(/\.java$/, '');
  const system = `You are an expert Java test engineer writing the FIRST test for ONE METHOD that no test executes yet`
    + `${gaps.method ? ` (${gaps.method}())` : ''}, in ${frameworkName(gaps.testFramework)}.`
    + ` Reply ONLY with JSON: {"tests":[{"path":"...","content":"full test file content"}]}.`
    + ` Create a NEW test class only. Required file path: ${targetPath}`
    + ` (package ${gaps.package}, public class ${testClass}). Rules:${COMMON_TEST_RULES}`
    + constraintBlock(gaps);
  const prompt = `CLASS UNDER TEST: ${gaps.fqcn}  (file ${gaps.path}, module ${gaps.module}, JDK ${gaps.jdk})\n`
    + (gaps.method ? `TARGET METHOD: ${gaps.method}()${gaps.methodLine ? ` (around line ${gaps.methodLine})` : ''} — the tests must exercise THIS method; coverage and mutation score are measured on it alone.\n` : '')
    + sourceBlock(gaps, 14000) + signatureBlock(gaps) + reachBlock(gaps)
    + `\n\nUNCOVERED: ${fullyUncovered ? 'THE ENTIRE METHOD (no test executes it at all)' : `source lines ${JSON.stringify((u.lines || []).slice(0, 140))}`}`
    + `\n\nEXISTING TEST (style reference — imports, assertion library, conventions; do NOT rewrite it):\n${String(gaps.existingTest || '(none)').slice(0, 6000)}`
    + `\n\nWrite the SIMPLEST test class that executes the uncovered lines of the target method: 2-4 short test methods, straightforward inputs, direct assertions. This is only the first pass — later rounds add the sharp, mutation-killing assertions, so do NOT try to be exhaustive. A small test that compiles and passes is worth far more than a large one that does not. JSON only.`;

  return { ...base, system, prompt, json: true, maxTokens: 3000, temperature: 0.2,
    stage: 'improving_coverage', stageDetail: 'writing a first, simple test' };
}

/**
 * Mutation phase — the pipeline has already chosen the target (survivors arrive ranked by
 * PIT's data and this run's kill record). The model is handed that one mutant and writes
 * one short test for it.
 */
function mutationPrompt(gaps) {
  const targetPath = gaps.mutTestPath;
  const perRound = gaps.mutantsPerRound || 1;
  const single = perRound === 1;
  const survived = gaps.survived || [];
  const choices = survived.slice(0, perRound);
  const base = { targetPath, projectTestPath: gaps.projectTestPath || null };

  if (unreachable(gaps)) {
    return { ...base, skip: true, reason: `${gaps.method}() is ${gaps.visibility} and has no caller in this class — no test can execute it, so there is nothing to write` };
  }
  if (!choices.length) {
    return {
      ...base,
      skip: true,
      reason: gaps.attemptedMutants
        ? `every remaining survivor has already been attempted (${gaps.attemptedMutants} tried)`
        : 'no surviving mutants',
    };
  }

  const testClass = targetPath.split('/').pop().replace(/\.java$/, '');
  const mutantsTxt = choices.map((m, i) =>
    `#${i + 1} [${m.status}] ${m.mutator} at line ${m.line} in method ${m.method || '?'}() — ${m.description || ''}`).join('\n');

  const system = `You are an expert Java test engineer killing surviving PIT mutants of ONE METHOD, one at a time, writing ${frameworkName(gaps.testFramework)} tests.`
    + ` A mutant is killed when at least one test FAILS on the mutated code while PASSING on the real code — so the test must assert something that DISTINGUISHES the two.`
    + ` Reply ONLY with JSON: {"tests":[{"path":"...","content":"full test file content"}]}.`
    + ` Create a NEW test class only. Required file path: ${targetPath}`
    + ` (package ${gaps.package}, public class ${testClass}). Rules:${COMMON_TEST_RULES}${MUTATOR_PLAYBOOK}`
    + constraintBlock(gaps);

  const prompt = `CLASS UNDER TEST: ${gaps.fqcn}  (file ${gaps.path}, module ${gaps.module})\n`
    + sourceBlock(gaps, 12000) + signatureBlock(gaps) + reachBlock(gaps)
    + (gaps.method ? `\n\nFOCUS: this unit of work IS the method ${gaps.method}() — shown in full above; the mutant below is inside it, and the score is measured on it alone.` : '')
    + `\n\n${single ? 'MUTANT TO KILL' : 'SURVIVING MUTANTS'} (SURVIVED = the line runs but nothing asserts on it; NO_COVERAGE = the line never runs):\n${mutantsTxt}`
    + `\n\nEXISTING TEST (style reference — do NOT rewrite it):\n${String(gaps.existingTest || '(none)').slice(0, 4000)}`
    + (single
      ? `\n\nKILL THE MUTANT ABOVE — it was selected for you. Write ONE test class with ONE short test method: call the method with inputs that reach that line and assert the value or side effect that DIFFERS between the real code and the mutation. If the mutation cannot change any observable behaviour, return an empty tests array rather than a test that cannot fail. Another round follows for the next mutant. JSON only.`
      : `\n\nWrite one FOCUSED test class: one short test method per mutant above, each with the single assertion that distinguishes the real code from that mutation. JSON only.`);

  return { ...base, system, prompt, json: true,
    maxTokens: single ? 2500 : 5000, temperature: 0.25,
    stage: 'improving_mutation',
    stageDetail: single ? `killing ${choices[0].mutator} at line ${choices[0].line}` : 'writing mutant-killing tests',
    offered: choices.map((m) => ({ line: m.line, mutator: m.mutator, status: m.status })) };
}

/** Repair — the tests we just wrote do not compile or do not pass. */
function repairPrompt(gaps, failure, tests, stage = 'improving_mutation') {
  const filesTxt = (tests || []).map((t) => `PATH: ${t.path}\n${String(t.content || '').slice(0, 6000)}`).join('\n\n---\n\n');
  const system = 'You are an expert Java test engineer. Tests you previously wrote FAIL to compile or fail against the current implementation. Fix them. Keep the SAME file path and class name.'
    + ' Reply ONLY with JSON: {"tests":[{"path":"...","content":"full corrected file content"}]}.'
    + ' Compilation errors: fix imports, types, visibility and constructor/method signatures against the source shown.'
    + ' Assertion failures: correct the EXPECTED values to match the real behaviour of the source — never weaken an assertion to make it pass trivially.'
    + ' If a test cannot be fixed, drop it from the output.';
  const prompt = `BUILD / TEST OUTPUT (failures):\n${String(failure?.summary || '').slice(0, 4000)}`
    + `\n\nYOUR TEST FILE(S):\n${filesTxt}`
    + `\n\nCLASS UNDER TEST ${gaps.fqcn} (${gaps.path}${gaps.method ? `, method ${gaps.method}()` : ''}):\n${sourceBlock(gaps, 10000)}`
    + '\n\nReply with corrected JSON now.';
  return { system, prompt, json: true, maxTokens: 7000, temperature: 0.2,
    stage, stageDetail: 'repairing failing generated tests' };
}

module.exports = { coveragePrompt, mutationPrompt, repairPrompt, COMMON_TEST_RULES, MUTATOR_PLAYBOOK };
