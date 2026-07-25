'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { globsToMatcher, slugify, fileSlug, mac, round2, extractJson, redact } = require('../util');

test('glob matcher: ** spans directories, * does not', () => {
  const m = globsToMatcher('lib/**/*.ts');
  assert.ok(m('lib/a.ts'));
  assert.ok(m('lib/deep/nested/a.ts'));
  assert.ok(!m('src/a.ts'));
  assert.ok(!m('lib/a.tsx'));

  const single = globsToMatcher('src/*.js');
  assert.ok(single('src/a.js'));
  assert.ok(!single('src/deep/a.js'));
});

test('glob matcher: brace alternatives and comma-separated globs', () => {
  const m = globsToMatcher('src/**/*.{js,ts,jsx,tsx}');
  for (const p of ['src/a.js', 'src/a.ts', 'src/d/b.jsx', 'src/d/b.tsx']) assert.ok(m(p), p);
  assert.ok(!m('src/a.css'));

  const multi = globsToMatcher('lib/**/*.ts,components/ui/**/*.tsx');
  assert.ok(multi('lib/x/y.ts'));
  assert.ok(multi('components/ui/Button.tsx'));
  assert.ok(!multi('components/other/Button.tsx'));
});

test('glob matcher: regex metacharacters in the glob are literal', () => {
  const m = globsToMatcher('src/a+b/*.ts');
  assert.ok(m('src/a+b/x.ts'));
  assert.ok(!m('src/aab/x.ts'));
});

test('slugify / fileSlug produce stable, filesystem-safe ids', () => {
  assert.equal(slugify('https://github.com/org/Repo-Name.git'), 'github-com-org-repo-name');
  assert.equal(fileSlug('lib/api-schemas/models/config.ts'), 'lib-api-schemas-models-config');
  assert.ok(!/[^a-z0-9-]/.test(fileSlug('components/ui/ChartCard.tsx')));
});

test('MAC is the product of the two percentages', () => {
  assert.equal(mac(100, 50), 50);
  assert.equal(mac(80, 90), 72);
  assert.equal(mac(0, 100), 0);
  assert.equal(mac(null, 50), null);
  assert.equal(mac(50, null), null);
  assert.equal(round2(33.333333), 33.33);
});

test('extractJson survives thinking blocks, fences and trailing prose', () => {
  assert.deepEqual(extractJson('<think>hmm</think>{"file":"a.ts"}'), { file: 'a.ts' });
  assert.deepEqual(extractJson('```json\n{"tests":[]}\n```'), { tests: [] });
  assert.deepEqual(extractJson('prose {"a":{"b":1}} more prose'), { a: { b: 1 } });
  assert.deepEqual(extractJson('[{"path":"x"}]'), [{ path: 'x' }]);
  assert.equal(extractJson('no json here'), null);
});

test('extractJson does not truncate at braces inside strings', () => {
  assert.deepEqual(extractJson('{"content":"if (x) { y }","n":1}'), { content: 'if (x) { y }', n: 1 });
});

test('redact removes tokens, api keys and URL credentials', () => {
  // assembled at runtime so the literal never trips secret scanners
  const fakeToken = 'gh' + 'p_' + 'abcdefghijklmnopqrstuvwxyz012345';
  const fakeKey = 'sk' + '-' + '0123456789abcdef0123';
  const out = redact(`fatal: repository 'https://x-access-token:${fakeToken}@github.com/o/r' not found`);
  assert.ok(!out.includes(fakeToken));
  assert.ok(!out.includes('x-access-token:'));
  assert.ok(out.includes('github.com/o/r'));
  assert.ok(!redact('key ' + fakeKey).includes(fakeKey));
  assert.equal(redact(null), '');
});
