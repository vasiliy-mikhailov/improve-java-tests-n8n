'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { gradlePitestPluginVersion, gradleInitScript } = require('../../pit');

test('the pitest plugin version follows the project Gradle, or nothing runs', () => {
  // 1.15.0 reads reporting.baseDir, which Gradle 9 removed: applying it there fails with
  // "Could not get unknown property 'baseDir'" — which is why every Gradle repo produced
  // nothing at all.
  assert.equal(gradlePitestPluginVersion('7.6.4'), '1.15.0');
  assert.equal(gradlePitestPluginVersion('8.10.2'), '1.19.0');
  assert.equal(gradlePitestPluginVersion('9.0.0'), '1.19.0');
});

test('an unknown Gradle gets the modern plugin rather than the one that cannot load', () => {
  assert.equal(gradlePitestPluginVersion(null), '1.19.0');
  assert.equal(gradlePitestPluginVersion(''), '1.19.0');
  assert.equal(gradlePitestPluginVersion('not-a-version'), '1.19.0');
});

const opts = { gradleVersion: '8.10.2', testFramework: 'junit5', pitVersion: '1.25.8', junit5PluginVersion: '1.2.3' };

test('the init script scopes PIT to one class and excludes the other methods', () => {
  const s = gradleInitScript('org.json.XML', '*XML*Test*', ['parse', 'toString'], opts);
  assert.match(s, /targetClasses = \['org\.json\.XML'\]/);
  assert.match(s, /targetTests = \['\*XML\*Test\*'\]/);
  assert.match(s, /excludedMethods = \['parse', 'toString'\]/);
  assert.match(s, /pitestVersion = '1\.25\.8'/);
  assert.match(s, /gradle-pitest-plugin:1\.19\.0/);
});

test('the JUnit 5 plugin is declared only for a JUnit 5 project', () => {
  assert.match(gradleInitScript('a.B', null, [], opts), /junit5PluginVersion = '1\.2\.3'/);
  assert.doesNotMatch(gradleInitScript('a.B', null, [], { ...opts, testFramework: 'junit4' }), /junit5PluginVersion/);
});

test('a class with nothing to exclude emits no excludedMethods at all', () => {
  const s = gradleInitScript('a.B', null, [], opts);
  assert.doesNotMatch(s, /excludedMethods/);
  assert.doesNotMatch(s, /targetTests/);
});

test('constructors survive into the exclusion list', () => {
  assert.match(gradleInitScript('a.B', null, ['<init>', 'run'], opts), /excludedMethods = \['<init>', 'run'\]/);
});

test('the http Nexus mirror is allowed explicitly, or Gradle 7+ refuses it', () => {
  const s = gradleInitScript('a.B', null, [], { ...opts, mirrorUrl: 'http://nexus:8081/repository/maven-public/' });
  assert.match(s, /url = uri\('http:\/\/nexus:8081\/repository\/maven-public\/'\)/);
  assert.match(s, /allowInsecureProtocol = true/);
});

test('reports are XML, untimestamped, and an empty method does not fail the build', () => {
  const s = gradleInitScript('a.B', null, [], opts);
  assert.match(s, /outputFormats = \['XML'\]/);
  assert.match(s, /timestampedReports = false/);
  assert.match(s, /failWhenNoMutations = false/);
});
