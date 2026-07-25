'use strict';
// Child-process runner with live progress fed into state (for the dashboard).
const { spawn } = require('node:child_process');
const { setProgress } = require('./state');
const { nowSec } = require('./util');

const ANSI = /\x1b\[[0-9;]*[a-zA-Z]/g;

/**
 * Run a command (argv array, no shell). Streams last output line into stage progress.
 * @returns {Promise<{code:number, stdout:string, stderr:string, timedOut:boolean}>}
 */
function run(argv, { cwd, env, timeoutMs = 600000, label } = {}) {
  return new Promise((resolve) => {
    const start = nowSec();
    let stdout = '', stderr = '', lastLine = '(starting)', timedOut = false;
    const child = spawn(argv[0], argv.slice(1), {
      cwd,
      // LLM_API_KEY has no business in a child process: the repo's own install
      // scripts and test code inherit this environment. GH_TOKEN stays — `gh`
      // and the git credential helper need it.
      env: { ...process.env, LLM_API_KEY: undefined, CI: 'true', FORCE_COLOR: '0', ...env },
      // own process group, so a timeout can kill the whole tree (Maven/Gradle fork
      // Surefire and PIT minion JVMs that would otherwise be orphaned and keep burning CPU)
      detached: true,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    const onData = (buf, isErr) => {
      const text = buf.toString();
      if (isErr) stderr += text; else stdout += text;
      if (stdout.length > 4e6) stdout = stdout.slice(-2e6);
      if (stderr.length > 4e6) stderr = stderr.slice(-2e6);
      for (const l of text.split(/\r?\n/)) {
        const c = l.replace(ANSI, '').trim();
        if (c) lastLine = c.slice(0, 180);
      }
    };
    child.stdout.on('data', (d) => onData(d, false));
    child.stderr.on('data', (d) => onData(d, true));
    const hb = setInterval(() => {
      setProgress((label ? label + ': ' : '') + lastLine, nowSec() - start);
    }, 3000);
    const killer = setTimeout(() => {
      timedOut = true;
      try { process.kill(-child.pid, 'SIGKILL'); }   // whole process group
      catch { try { child.kill('SIGKILL'); } catch { } }
    }, timeoutMs);
    child.on('error', (e) => {
      clearInterval(hb); clearTimeout(killer);
      resolve({ code: 127, stdout, stderr: stderr + '\nspawn error: ' + e.message, timedOut });
    });
    child.on('close', (code) => {
      clearInterval(hb); clearTimeout(killer);
      setProgress((label ? label + ': ' : '') + 'done (exit ' + code + ')', nowSec() - start);
      resolve({ code: code == null ? 1 : code, stdout, stderr, timedOut });
    });
  });
}

module.exports = { run };
