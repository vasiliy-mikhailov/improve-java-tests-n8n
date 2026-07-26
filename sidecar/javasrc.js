'use strict';
// Two facts about a target method that decide whether a test can reach it at all.
//
// Units come from JaCoCo, which enumerates every method including private helpers. A test
// cannot call a private method — reflection is forbidden by the write_test rules and would
// be a worthless test anyway — so it has to arrive through something public. Told nothing
// about this, the model wrote a compiling, passing test for JSONObject's private
// isRecordStyleAccessor that executed none of it: coverage 0 → 0, every mutant alive, a
// whole round spent.
//
// Regex over Java source is approximate by nature. Both functions only ENRICH a prompt,
// never decide a number, so a miss costs a hint and never a false measurement.

const CONTROL = /\b(?:if|while|for|switch|catch|do|else|synchronized)\s*\(/;

const escape = (s) => String(s).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/**
 * Is this line the declaration of `name`? Decided from what precedes the name: modifiers
 * and a return type, never a `(`, a `.`, or a `return` — which is what separates
 *   `private static boolean isRecordStyleAccessor(String n, Method m) {`
 * from `if (isRecordType && isRecordStyleAccessor(name, method)) {`.
 */
function declares(line, name) {
  const m = new RegExp(`(^[\\s\\S]*?)(?:^|[^\\w.$])${escape(name)}\\s*\\(`).exec(line);
  if (!m) return false;
  if (CONTROL.test(line)) return false;
  const prefix = m[1];
  if (!/^[\s@\w.$<>[\],?&]*$/.test(prefix)) return false;   // no (, {, =, ; before the name
  if (/\.\s*$/.test(prefix)) return false;                   // this.foo( is a call
  if (/\b(?:return|new|throw)\b/.test(prefix)) return false;
  // A declaration is preceded by modifiers and/or a return type. Nothing at all means a
  // bare call statement — and `target(1);` ends in `);` exactly like an abstract method
  // declaration, so the shape check below cannot tell them apart on its own. The one
  // exception is a package-private constructor, which is named like the class.
  if (!prefix.trim() && !/^[A-Z]/.test(name)) return false;
  // a declaration's parameter list is followed by a body or a semicolon
  return /\)\s*(?:throws\s+[\w.,\s]+?)?\s*[{;]/.test(line.slice(m[0].length - 1));
}

function visibilityOf(line) {
  if (/\bprivate\b/.test(line)) return 'private';
  if (/\bprotected\b/.test(line)) return 'protected';
  if (/\bpublic\b/.test(line)) return 'public';
  return 'package-private';
}

const nameIn = (line) => {
  const m = /(?:^|[^\w.$])([\w$]+)\s*\(/.exec(line);
  return m ? m[1] : null;
};

const depthOf = (line) => (line.match(/\{/g) || []).length - (line.match(/\}/g) || []).length;

/** `public` | `private` | `protected` | `package-private`, or null if not declared here. */
function methodVisibility(source, method) {
  if (!source || !method) return null;
  for (const line of String(source).split('\n')) {
    if (declares(line, method)) return visibilityOf(line.slice(0, line.indexOf(method)));
  }
  return null;
}

/**
 * Methods of this class whose body calls `method` — the routes a test can take to it.
 * A method is never reported as calling itself.
 */
function callersOf(source, method) {
  if (!source || !method) return [];
  const call = new RegExp(`(?:^|[^\\w.$])(?:this\\.|super\\.)?${escape(method)}\\s*\\(`);
  const lines = String(source).split('\n');
  const out = [];
  let current = null, depth = 0;
  for (const line of lines) {
    const name = nameIn(line);
    const isDecl = depth <= 1 && name && declares(line, name);
    if (isDecl) {
      current = name === method
        ? null                                    // inside the target itself: not a caller
        : { method: name, visibility: visibilityOf(line.slice(0, line.indexOf(name))) };
    }
    // a one-line method declares and calls on the same line, so check both, not either
    if (current && call.test(line) && !out.some((c) => c.method === current.method)) {
      out.push(current);
    }
    depth += depthOf(line);
    if (depth <= 1 && !isDecl) current = null;    // back at class level
  }
  return out;
}

module.exports = { methodVisibility, callersOf };
