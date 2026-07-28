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
