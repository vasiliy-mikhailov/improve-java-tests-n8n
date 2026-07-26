'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { mergeReport, lineCounter } = require('../../coverage');

// Shape of a real JaCoCo report: <class> holds a <method> per method, each with its OWN
// counters, and the class totals come LAST. Here the first method is uncovered (0/3) and
// the class as a whole is 12/15 — reading the first counter reports 0 %.
const XML = `<?xml version="1.0" encoding="UTF-8"?>
<report name="demo">
<package name="org/apache/commons/cli">
  <class name="org/apache/commons/cli/MissingOptionException" sourcefilename="MissingOptionException.java">
    <method name="&lt;init&gt;" desc="(Ljava/util/List;)V" line="41">
      <counter type="INSTRUCTION" missed="7" covered="0"/>
      <counter type="LINE" missed="3" covered="0"/>
    </method>
    <method name="createMessage" desc="(Ljava/util/List;)Ljava/lang/String;" line="55">
      <counter type="INSTRUCTION" missed="0" covered="30"/>
      <counter type="LINE" missed="0" covered="12"/>
    </method>
    <counter type="INSTRUCTION" missed="7" covered="30"/>
    <counter type="LINE" missed="3" covered="12"/>
  </class>
  <sourcefile name="MissingOptionException.java">
    <line nr="41" mi="2" ci="0" mb="0" cb="0"/>
    <line nr="42" mi="1" ci="0" mb="0" cb="0"/>
    <line nr="55" mi="0" ci="4" mb="0" cb="0"/>
    <counter type="LINE" missed="3" covered="12"/>
  </sourcefile>
</package>
<counter type="LINE" missed="3" covered="12"/>
</report>`;

test('class coverage comes from the class total, not its first method', () => {
  const acc = { classes: {}, missed: 0, covered: 0 };
  mergeReport(acc, XML);
  const c = acc.classes['org.apache.commons.cli.MissingOptionException'];
  assert.ok(c, 'class must be keyed by fully-qualified name');
  assert.equal(c.covered, 12);
  assert.equal(c.missed, 3);
  const pct = (c.covered * 100) / (c.covered + c.missed);
  assert.equal(Math.round(pct), 80, 'not 0 % — that was the first method');
});

test('lineCounter takes the last LINE counter in the element', () => {
  const frag = '<counter type="LINE" missed="3" covered="0"/><counter type="LINE" missed="1" covered="9"/>';
  assert.deepEqual(lineCounter(frag), { missed: 1, covered: 9 });
});

test('lineCounter returns null when the element has no LINE counter', () => {
  assert.equal(lineCounter('<counter type="BRANCH" missed="1" covered="2"/>'), null);
});

test('per-line hits are collected for the uncovered-lines prompt', () => {
  const acc = { classes: {}, missed: 0, covered: 0 };
  mergeReport(acc, XML);
  const lines = acc.classes['org.apache.commons.cli.MissingOptionException'].lines;
  assert.deepEqual(lines['41'], { mi: 2, ci: 0 });
  assert.deepEqual(lines['55'], { mi: 0, ci: 4 });
});

test('a self-closing <class/> does not swallow the classes after it', () => {
  // JaCoCo emits self-closing tags for synthetic classes with no methods. A paired
  // <class>…</class> regex reads such a tag as an opening tag and consumes the NEXT
  // class's body, so everything after it reported 0 % coverage.
  const xml = `<report name="d"><package name="a/b">
    <class name="a/b/Outer$1" sourcefilename="Outer.java"/>
    <class name="a/b/Victim" sourcefilename="Victim.java">
      <method name="m" desc="()V" line="9"><counter type="LINE" missed="0" covered="7"/></method>
      <counter type="LINE" missed="0" covered="7"/>
    </class>
  </package></report>`;
  const acc = { classes: {}, missed: 0, covered: 0 };
  mergeReport(acc, xml);
  assert.ok(acc.classes['a.b.Victim'], 'the class after a self-closing sibling must still be seen');
  assert.equal(acc.classes['a.b.Victim'].covered, 7);
  assert.equal(acc.classes['a.b.Outer']?.covered ?? 0, 0, 'the synthetic class contributes nothing');
});

test('inner classes fold into their outer class', () => {
  const xml = `<report name="d"><package name="a/b">
    <class name="a/b/Outer" sourcefilename="Outer.java"><counter type="LINE" missed="1" covered="4"/></class>
    <class name="a/b/Outer$Inner" sourcefilename="Outer.java"><counter type="LINE" missed="0" covered="6"/></class>
  </package></report>`;
  const acc = { classes: {}, missed: 0, covered: 0 };
  mergeReport(acc, xml);
  assert.equal(acc.classes['a.b.Outer'].covered, 10);
  assert.equal(acc.classes['a.b.Outer'].missed, 1);
});
