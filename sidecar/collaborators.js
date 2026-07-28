'use strict';
// What a test has to build before it can call the method under test.
//
// The prompt shows the class under test and its sibling signatures. It does not show the
// types that class's CONSTRUCTOR demands, so a test for
// DelegatingStatisticsCollector — which takes a StatisticsCollector — was written against
// an API the model had never seen. `cannot find symbol` was 8 of 11 recorded failures on
// the v3 run, and DataLoaderHelper#load lost sixteen tests in one file to it.
//
// The run supplied its own control: SimpleStatisticsCollector, same package and same batch
// size but needing no collaborator, went MAC 0 → 100 in a single pass.
//
// Two things fix it, in order of preference. Name a concrete implementation the project
// already ships — java-dataloader has SimpleStatisticsCollector and NoOpStatisticsCollector
// — because instantiating one is cheaper and safer than hand-rolling an anonymous class.
// Failing that, show the interface's full public API, so a hand-rolled implementation
// overrides every method instead of the three the model guessed at.

const { stripNonCode } = require('./javasrc');

// A test never has to "construct" these.
const BUILT_IN = new Set([
  'int', 'long', 'short', 'byte', 'char', 'boolean', 'float', 'double', 'void',
  'String', 'Integer', 'Long', 'Short', 'Byte', 'Character', 'Boolean', 'Float', 'Double',
  'Object', 'Number', 'CharSequence', 'Class', 'Throwable', 'Exception', 'RuntimeException',
  'List', 'Map', 'Set', 'Collection', 'Optional', 'Iterable',
]);

/** `CacheMap<Object, String>` → `CacheMap`; `String...` → `String`; `int[]` → `int`. */
const bareType = (t) => String(t || '').replace(/<.*$/, '').replace(/\[\]|\.\.\./g, '').trim();

/**
 * The types this class's constructor demands — the ones a test must supply to build it at
 * all. Primitives and java.lang types are excluded: nobody fails to construct a String.
 */
function constructorTypes(source, className) {
  const code = stripNonCode(String(source || ''));
  const out = new Set();
  const re = new RegExp(`\\b${className}\\s*\\(([^)]*)\\)\\s*\\{`, 'g');
  for (const m of code.matchAll(re)) {
    // Type arguments have to go before anything is split: the comma in
    // `CacheMap<Object, String> m` is not a parameter separator, and splitting on it
    // leaves `String>` sitting where the type should be — which reads as java.lang.String
    // and drops the collaborator silently.
    let params = m[1];
    for (let prev = null; prev !== params;) { prev = params; params = params.replace(/<[^<>]*>/g, ''); }
    for (const raw of params.split(',')) {
      const parts = raw.trim().split(/\s+/).filter(Boolean);
      if (parts.length < 2) continue;                 // no type + name pair
      const t = bareType(parts[parts.length - 2]);
      if (t && /^[A-Z]/.test(t) && !BUILT_IN.has(t)) out.add(t);
    }
  }
  return [...out];
}

/**
 * Every public/abstract method signature of a type, WITH parameter types.
 *
 * The types matter more than the names: "call incrementBatchLoadCountBy()" when it takes a
 * long produces a test that does not compile, which is how six rounds went on
 * XMLTokener#isValidDecimal.
 */
function publicApi(source) {
  const code = stripNonCode(String(source || ''));
  const out = [];
  // interface methods have no modifier; class methods need public
  const re = /(?:^|\n)\s*(?:public\s+|protected\s+)?(?:abstract\s+|default\s+|static\s+|final\s+)*([A-Za-z_$][\w$<>,.\s[\]]*?)\s+([A-Za-z_$][\w$]*)\s*\(([^)]*)\)\s*[;{]/g;
  for (const m of code.matchAll(re)) {
    const ret = m[1].trim(), name = m[2], args = m[3].trim();
    if (!ret || ret === 'return' || ret === 'new') continue;
    if (['if', 'for', 'while', 'switch', 'catch'].includes(name)) continue;
    const sig = `${ret} ${name}(${args})`;
    if (!out.includes(sig)) out.push(sig);
  }
  return out;
}

/**
 * Classes in the project that a test can instantiate as this type.
 *
 * Abstract ones are excluded — `new AbstractCollector()` does not compile, and offering it
 * would trade one unknown symbol for another.
 */
function concreteImplementors(typeName, files) {
  const out = [];
  for (const f of files || []) {
    const src = stripNonCode(String((f && f.source) || ''));
    const m = src.match(/\b(?:public\s+)?(abstract\s+)?class\s+([A-Za-z_$][\w$]*)([^{]*)\{/);
    if (!m) continue;
    if (m[1]) continue;                                // abstract — cannot be instantiated
    const decl = m[3] || '';
    if (!new RegExp(`\\b${typeName}\\b`).test(decl)) continue;
    if (!out.includes(m[2])) out.push(m[2]);
  }
  return out;
}

module.exports = { constructorTypes, publicApi, concreteImplementors, bareType, BUILT_IN };
