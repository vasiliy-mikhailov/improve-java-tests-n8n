'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { methodVisibility, callersOf, stripNonCode, routesTo } = require('../../javasrc');

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

test('braces inside strings, chars and comments do not shift the nesting depth', () => {
  // JSONObject.java writes JSON, so it is full of '{' and "}" in literals and javadoc.
  // Counting those as real braces drifted its depth to -4 by end of file; no line after
  // the drift ever looked like it sat at class level, so callersOf returned nothing for a
  // method with an obvious caller — and the prompt reported "no caller in this class, no
  // test can execute it" for a method that populateMap() calls directly.
  // The drift must therefore come BEFORE the caller, as it does in the real file.
  const src = `package org.json;

public class Writer {
    /** Javadoc with an unbalanced brace: } and another } for good measure. */
    public void drift() {
        sb.append('}');
        sb.append("}}");   // and a comment brace }
    }

    public void write(StringBuilder sb) {
        final String name = sb.toString();
        if (name.isEmpty()) {
            return;
        }
        check(name);
    }

    private boolean check(String s) {
        return s.isEmpty();
    }
}
`;
  assert.equal(methodVisibility(src, 'check'), 'private');
  assert.deepEqual(callersOf(src, 'check').map((c) => c.method), ['write']);
});

test('an escaped quote does not run the string on to the end of the file', () => {
  const src = `package a;

public class B {
    public void drift() {
        String s = "he said \\"}}}}\\" and left";
    }

    public void run() {
        int n = 1;
        n = n + 1;
        helper();
    }

    private void helper() { }
}
`;
  assert.deepEqual(callersOf(src, 'helper').map((c) => c.method), ['run']);
});

test('code is kept, literals and comments are blanked, line numbers survive', () => {
  const src = [
    'class A {',
    '    String s = "a { b } c";  // trailing } comment',
    '    /* block } comment */',
    "    char c = '{';",
    '}',
  ].join('\n');
  const out = stripNonCode(src);
  assert.equal(out.split('\n').length, 5, 'line count must be preserved');
  assert.equal((out.match(/\{/g) || []).length, 1, 'only the class brace is real');
  assert.equal((out.match(/\}/g) || []).length, 1);
  assert.match(out, /String s =/, 'code outside the literal is untouched');
});

// ── routes from public API to a hidden method ─────────────────────────────
const CHAIN = `package org.json;

public class JSONObject {
    public JSONObject(Object bean) {
        this.populateMap(bean);
    }

    private void populateMap(Object bean) {
        String k = getKeyNameFromMethod(m);
        map.put(k, v);
    }

    private static String getKeyNameFromMethod(Method method) {
        if (isRecordType && isRecordStyleAccessor(name, method)) {
            return name;
        }
        return null;
    }

    private static boolean isRecordStyleAccessor(String methodName, Method method) {
        return true;
    }

    private void deadHelper() {
        onlyDead();
    }

    private void onlyDead() {
    }
}
`;

test('the route to a hidden method starts at public API and ends at the method', () => {
  // JSONObject#isRecordStyleAccessor is three private hops from the nearest public entry.
  // Listing only its direct caller told the model to call another private method — no more
  // use than telling it nothing.
  const [route] = routesTo(CHAIN, 'isRecordStyleAccessor');
  assert.deepEqual(route.map((s) => s.method),
    ['JSONObject', 'populateMap', 'getKeyNameFromMethod', 'isRecordStyleAccessor']);
  assert.equal(route[0].visibility, 'public', 'a test can only start from something public');
});

test('a method with a direct public caller gets the short route', () => {
  const [route] = routesTo(CHAIN, 'populateMap');
  assert.deepEqual(route.map((s) => s.method), ['JSONObject', 'populateMap']);
});

test('private code no public path reaches has no route at all', () => {
  assert.deepEqual(routesTo(CHAIN, 'onlyDead'), []);
});

test('a public method needs no route', () => {
  assert.deepEqual(routesTo(CHAIN, 'JSONObject'), []);
});

test('a cycle between private helpers does not hang the search', () => {
  const cyclic = `public class C {
    public void entry() { a(); }
    private void a() { b(); }
    private void b() { a(); target(); }
    private void target() { }
}
`;
  const [route] = routesTo(cyclic, 'target');
  assert.equal(route[0].method, 'entry');
  assert.equal(route[route.length - 1].method, 'target');
});
