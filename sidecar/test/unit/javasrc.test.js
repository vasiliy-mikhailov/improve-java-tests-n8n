'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { methodVisibility, callersOf } = require('../../javasrc');

const SRC = `package org.json;

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
`;

test('the visibility a test has to work with is read off the declaration', () => {
  assert.equal(methodVisibility(SRC, 'isRecordStyleAccessor'), 'private');
  assert.equal(methodVisibility(SRC, 'toString'), 'public');
  assert.equal(methodVisibility(SRC, 'helper'), 'protected');
  assert.equal(methodVisibility(SRC, 'packagePrivate'), 'package-private');
  assert.equal(methodVisibility(SRC, 'JSONObject'), 'public', 'the constructor counts');
});

test('a method the source does not declare has unknown visibility', () => {
  assert.equal(methodVisibility(SRC, 'noSuchThing'), null);
  assert.equal(methodVisibility('', 'anything'), null);
});

test('callers within the class are the routes a test can actually take', () => {
  // isRecordStyleAccessor is private: a test can only reach it through something public.
  // Without being told this, the model wrote a passing test that executed none of it —
  // the round measured cov 0 → 0 and every mutant survived.
  const callers = callersOf(SRC, 'isRecordStyleAccessor');
  assert.deepEqual(callers.map((c) => c.method).sort(), ['packagePrivate', 'populateMap']);
});

test('each caller carries its own visibility, so a public route is recognisable', () => {
  const callers = callersOf(SRC, 'isRecordStyleAccessor');
  assert.equal(callers.find((c) => c.method === 'populateMap').visibility, 'private');
});

test('the declaration itself is not mistaken for a call', () => {
  assert.deepEqual(callersOf(SRC, 'toString').map((c) => c.method), [],
    'the body of toString calling toString() on another object is not a self-call');
});

test('a method nothing calls has no callers', () => {
  assert.deepEqual(callersOf(SRC, 'helper'), []);
});

test('a method past the prompt clip is still found — analysis needs the whole file', () => {
  // gapsFor analysed the string it sends to the model, which is clipped to 24 000 chars
  // for prompt size. JSONObject#isRecordStyleAccessor is declared at line 2071, well past
  // that, so visibility came back null, the REACHED VIA section was omitted, and the fix
  // that was supposed to name the route silently did nothing in production.
  const filler = '    // padding padding padding padding padding padding padding\n'.repeat(600);
  const big = `package org.json;\n\npublic class Big {\n${filler}
    public void entryPoint() {
        target(1);
    }

    private int target(int x) {
        return x + 1;
    }
}
`;
  assert.ok(big.length > 24000, `fixture must exceed the prompt clip (was ${big.length})`);
  assert.equal(methodVisibility(big, 'target'), 'private');
  assert.deepEqual(callersOf(big, 'target').map((c) => c.method), ['entryPoint']);
});
