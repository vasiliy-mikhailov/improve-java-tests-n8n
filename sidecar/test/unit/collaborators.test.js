'use strict';
// The test cannot construct a type it has never seen.
//
// Eight of eleven recorded v3 failures were `cannot find symbol`, and the diagnosis is
// unusually clean because the run supplied its own control: SimpleStatisticsCollector went
// MAC 0 → 100 in one pass, while DelegatingStatisticsCollector — same package, same method
// names, same 3-target batch — lost four consecutive units that v1 had improved. The only
// difference is that the Delegating one's test must build a StatisticsCollector to pass to
// the constructor, and the prompt never shows what StatisticsCollector looks like.
//
// DataLoaderHelper#load then lost SIXTEEN tests in one file to the same missing symbol.
//
// The prompt carries the class under test and its sibling signatures. It has to also carry
// the CONSTRUCTOR's parameter types, and — where the project defines them — a concrete
// implementation the test can instantiate instead of hand-rolling one.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { constructorTypes, publicApi, concreteImplementors } = require('../../collaborators');

const DELEGATING = `package org.dataloader.stats;

public class DelegatingStatisticsCollector implements StatisticsCollector {
    private final StatisticsCollector delegateCollector;
    private final StatisticsCollector collector = new SimpleStatisticsCollector();

    public DelegatingStatisticsCollector(StatisticsCollector delegateCollector) {
        this.delegateCollector = nonNull(delegateCollector);
    }

    @Override
    public long incrementLoadCount(IncrementLoadCountStatisticsContext context) {
        delegateCollector.incrementLoadCount(context);
        return collector.incrementLoadCount(context);
    }
}
`;

const COLLECTOR = `package org.dataloader.stats;

public interface StatisticsCollector {
    long incrementLoadCount(IncrementLoadCountStatisticsContext context);

    @Deprecated
    long incrementLoadCount();

    long incrementBatchLoadCountBy(long delta);

    Statistics getStatistics();
}
`;

// ── which types does a test have to build? ────────────────────────────────
test('constructor parameter types are what the test must supply', () => {
  const t = constructorTypes(DELEGATING, 'DelegatingStatisticsCollector');
  assert.ok(t.includes('StatisticsCollector'), `got ${JSON.stringify(t)}`);
});

test('primitives and java.lang types are not collaborators', () => {
  const src = `public class A { public A(int n, String s, long ms, boolean b) {} }`;
  assert.deepEqual(constructorTypes(src, 'A'), [], 'nothing here needs constructing');
});

test('generics are reduced to the type a test would name', () => {
  const src = `public class A { public A(CacheMap<Object, String> m) {} }`;
  assert.deepEqual(constructorTypes(src, 'A'), ['CacheMap']);
});

test('a class with no declared constructor asks for nothing', () => {
  assert.deepEqual(constructorTypes('public class A { public void go() {} }', 'A'), []);
});

// ── what does that type look like? ────────────────────────────────────────
test('the public API of a collaborator is extracted, so the test can implement it', () => {
  const api = publicApi(COLLECTOR);
  assert.ok(api.some((s) => s.includes('incrementBatchLoadCountBy')));
  assert.ok(api.some((s) => s.includes('getStatistics')));
  assert.ok(api.length >= 4, `expected every abstract method, got ${api.length}`);
});

test('the extracted API keeps parameter types, which is the whole point', () => {
  // "call incrementBatchLoadCountBy()" when it takes a long is how six rounds were lost on
  // XMLTokener#isValidDecimal
  const api = publicApi(COLLECTOR);
  assert.ok(api.some((s) => /incrementBatchLoadCountBy\(\s*long/.test(s)), api.join(' | '));
});

test('an empty or unreadable source yields nothing, not a broken signature', () => {
  assert.deepEqual(publicApi(''), []);
  assert.deepEqual(publicApi(null), []);
});

// ── is there something the project already ships? ─────────────────────────
test('a concrete implementation the project provides is preferred to hand-rolling one', () => {
  // java-dataloader ships SimpleStatisticsCollector and NoOpStatisticsCollector; naming
  // them is cheaper and safer than asking for an anonymous class with every method
  const found = concreteImplementors('StatisticsCollector', [
    { path: 'src/main/java/org/dataloader/stats/SimpleStatisticsCollector.java', source: 'public class SimpleStatisticsCollector implements StatisticsCollector {}' },
    { path: 'src/main/java/org/dataloader/stats/NoOpStatisticsCollector.java', source: 'public class NoOpStatisticsCollector implements StatisticsCollector {}' },
    { path: 'src/main/java/org/dataloader/stats/Statistics.java', source: 'public class Statistics {}' },
  ]);
  assert.deepEqual(found.sort(), ['NoOpStatisticsCollector', 'SimpleStatisticsCollector']);
});

test('an abstract implementation is not something a test can instantiate', () => {
  const found = concreteImplementors('StatisticsCollector', [
    { path: 'a/AbstractCollector.java', source: 'public abstract class AbstractCollector implements StatisticsCollector {}' },
  ]);
  assert.deepEqual(found, []);
});

test('a subclass counts too, not only a direct implementor', () => {
  const found = concreteImplementors('StatisticsCollector', [
    { path: 'a/Delegating.java', source: 'public class DelegatingStatisticsCollector extends SimpleStatisticsCollector implements StatisticsCollector {}' },
  ]);
  assert.deepEqual(found, ['DelegatingStatisticsCollector']);
});

test('nothing implements it — say so rather than invent a name', () => {
  assert.deepEqual(concreteImplementors('Nothing', [{ path: 'a/A.java', source: 'public class A {}' }]), []);
  assert.deepEqual(concreteImplementors('X', []), []);
});

// ── every built-in, named one at a time ───────────────────────────────────
// The BUILT_IN table is data, and data rots silently: drop an entry and the only symptom
// is a prompt that asks the model to construct a String. Mutation testing showed ~30
// living mutants across these four lines, one per name, because the tests happened to
// exercise only four of them.
//
// The expected names are written out HERE rather than read from BUILT_IN. Iterating the
// set under test would be a tautology — a mutant that blanks 'Integer' would make the
// loop assert about '' and pass. This list has to be maintained by hand; that is the
// point of it.
const BUILT_INS = [
  'String', 'Integer', 'Long', 'Short', 'Byte', 'Character', 'Boolean', 'Float', 'Double',
  'Object', 'Number', 'CharSequence', 'Class', 'Throwable', 'Exception', 'RuntimeException',
  'List', 'Map', 'Set', 'Collection', 'Optional', 'Iterable',
];

// Primitives take the other route out — capitalisation, not the table. Listed separately
// so that the two mechanisms stay visibly distinct: if someone ever drops the /^[A-Z]/
// guard, these fail and the table above does not.
const PRIMITIVES = ['int', 'long', 'short', 'byte', 'char', 'boolean', 'float', 'double', 'void'];

for (const t of PRIMITIVES) {
  test(`${t} is excluded by being lower-case, not by the table`, () => {
    assert.deepEqual(constructorTypes(`public class A { public A(${t} v) {} }`, 'A'), []);
  });
}

for (const t of BUILT_INS) {
  test(`${t} is something a test can produce without help`, () => {
    assert.deepEqual(constructorTypes(`public class A { public A(${t} v) {} }`, 'A'), [],
      `${t} was reported as a collaborator the test must construct`);
  });
}

test('a type outside that table IS a collaborator', () => {
  // the negative half: without it, "return [] always" would pass every test above
  assert.deepEqual(constructorTypes('public class A { public A(CacheMap v) {} }', 'A'), ['CacheMap']);
  assert.deepEqual(constructorTypes('public class A { public A(StatisticsCollector v) {} }', 'A'), ['StatisticsCollector']);
});

// ── reducing a written type to the name a test would use ──────────────────
test('generics, arrays and varargs are stripped to the bare type', () => {
  assert.deepEqual(constructorTypes('public class A { public A(CacheMap<K, V> m) {} }', 'A'), ['CacheMap']);
  assert.deepEqual(constructorTypes('public class A { public A(Widget[] w) {} }', 'A'), ['Widget']);
  assert.deepEqual(constructorTypes('public class A { public A(Widget... w) {} }', 'A'), ['Widget']);
});

test('nested generics are unwrapped completely, not one layer', () => {
  // `Map<String, List<Widget>>` split naively leaves `List<Widget>>` sitting where the
  // type belongs, and the collaborator is lost without a trace
  assert.deepEqual(constructorTypes('public class A { public A(Map<String, List<Widget>> m, Gauge g) {} }', 'A'), ['Gauge']);
});

test('a lower-case type is not treated as a class to construct', () => {
  // primitives beyond the table, and any identifier that cannot be a class name
  assert.deepEqual(constructorTypes('public class A { public A(foo bar) {} }', 'A'), []);
});

test('the same collaborator named twice is reported once', () => {
  assert.deepEqual(constructorTypes('public class A { public A(Gauge a, Gauge b) {} }', 'A'), ['Gauge']);
});

test('several constructors contribute all of their collaborators', () => {
  const src = `public class A {
    public A(Gauge g) {}
    public A(Clock c, Gauge g) {}
  }`;
  assert.deepEqual(constructorTypes(src, 'A').sort(), ['Clock', 'Gauge']);
});

test('a parameter with no name is not mistaken for a type', () => {
  assert.deepEqual(constructorTypes('public class A { public A() {} }', 'A'), []);
});

// ── reading a collaborator's signatures ───────────────────────────────────
test('every modifier spelling is still a method signature', () => {
  // 18 living mutants sat on this one regex. Each modifier it accepts needs a case, or
  // removing that alternative from the pattern changes nothing any test can see.
  const src = `public interface I {
    long plain(int a);
    public long pub(int a);
    protected long prot(int a);
    abstract long abs(int a);
    default long def(int a) { return 0; }
    static long stat(int a) { return 0; }
    final long fin(int a) { return 0; }
  }`;
  const api = publicApi(src);
  for (const n of ['plain', 'pub', 'prot', 'abs', 'def', 'stat', 'fin']) {
    assert.ok(api.some((s) => s.includes(` ${n}(`)), `${n} was not recognised: ${api.join(' | ')}`);
  }
});

test('a generic, qualified or array return type survives extraction', () => {
  const api = publicApi(`public interface I {
    java.util.List<String> qualified();
    Map<String, List<Long>> nested();
    int[] arr();
  }`);
  assert.ok(api.some((s) => /java\.util\.List<String>\s+qualified\(\)/.test(s)), api.join(' | '));
  assert.ok(api.some((s) => /nested\(\)/.test(s)), api.join(' | '));
  assert.ok(api.some((s) => /arr\(\)/.test(s)), api.join(' | '));
});

test('both an interface declaration and a class body are read', () => {
  // interface methods end in `;`, class methods in `{` — the pattern accepts either, and
  // a mutant that drops one alternative must fail something
  assert.ok(publicApi('public interface I {\n  long a(int x);\n}').some((s) => s.includes('a(int x)')));
  assert.ok(publicApi('public class C {\n  public long b(int x) { return 0; }\n}').some((s) => s.includes('b(int x)')));
});

test('a member has to begin a line to be seen', () => {
  // Not a bug worth fixing: real Java declares one member per line, and the line anchor is
  // what stops the pattern from matching every call expression inside a method body. It IS
  // a limitation though, so it is pinned here — a future reader should meet it in a test
  // rather than in a prompt that silently listed half an interface.
  assert.deepEqual(publicApi('public interface I { long a(int x); }'), [],
    'everything crammed onto one line yields nothing');
});

test('control-flow keywords are not signatures', () => {
  // `if (x) {` matches the shape of a method declaration almost exactly
  const api = publicApi(`public class C {
    public void go(int x) { if (x > 0) { return; } while (x > 0) { x--; } for (;;) { break; } }
  }`);
  assert.deepEqual(api.filter((s) => /\b(if|while|for|switch|catch)\s*\(/.test(s)), [], api.join(' | '));
});

test('a signature appearing twice is listed once', () => {
  const api = publicApi('public interface I {\n  long a(int x);\n  long a(int x);\n}');
  assert.equal(api.filter((s) => s.includes('a(int x)')).length, 1, api.join(' | '));
});

test('a method taking no arguments keeps its empty parameter list', () => {
  const api = publicApi('public interface I {\n  Statistics getStatistics();\n}');
  assert.ok(api.some((s) => /getStatistics\(\)$/.test(s)), api.join(' | '));
});

// ── which classes can stand in for a type ─────────────────────────────────
test('extends and implements both count as standing in for the type', () => {
  const files = [
    { path: 'a/A.java', source: 'public class A implements Gauge {}' },
    { path: 'b/B.java', source: 'public class B extends AbstractGauge implements Gauge {}' },
  ];
  assert.deepEqual(concreteImplementors('Gauge', files).sort(), ['A', 'B']);
});

test('a class without the `public` modifier still counts', () => {
  assert.deepEqual(concreteImplementors('Gauge', [{ path: 'a/A.java', source: 'class A implements Gauge {}' }]), ['A']);
});

test('a generic class is named without its type parameters', () => {
  const found = concreteImplementors('Gauge', [{ path: 'a/A.java', source: 'public class A<T> implements Gauge {}' }]);
  assert.deepEqual(found, ['A'], 'the test writes `new A<>()`, so the name is what matters');
});

test('a type merely mentioned in the body is not an implementation', () => {
  // the declaration is everything before `{`; matching the whole file would make any
  // class that so much as references Gauge look like an implementation of it
  const files = [{ path: 'a/A.java', source: 'public class A implements Other { Gauge g; }' }];
  assert.deepEqual(concreteImplementors('Gauge', files), []);
});

test('a similarly-named type is not a match', () => {
  const files = [{ path: 'a/A.java', source: 'public class A implements GaugeFactory {}' }];
  assert.deepEqual(concreteImplementors('Gauge', files), []);
});

test('a file with no class declaration at all is skipped', () => {
  assert.deepEqual(concreteImplementors('Gauge', [
    { path: 'a/I.java', source: 'public interface I extends Gauge {}' },
    { path: 'a/E.java', source: '' },
    { path: 'a/N.java', source: null },
  ]), [], 'an interface cannot be instantiated either');
});
