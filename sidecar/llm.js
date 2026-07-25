'use strict';
// OpenAI-compatible chat client for the vLLM endpoint (qwen), zero-dep via global fetch.
const { extractJson } = require('./util');
const { event } = require('./state');

const BASE = (process.env.LLM_BASE_URL || '').replace(/\/$/, '');
const KEY = process.env.LLM_API_KEY || '';
const MODEL = process.env.LLM_MODEL || 'qwen-3.6-27b-fp8';
const ENABLE_THINKING = String(process.env.LLM_ENABLE_THINKING || 'false') === 'true';
// thinking consumes completion tokens before any visible output — give it headroom
const THINKING_EXTRA = ENABLE_THINKING ? parseInt(process.env.LLM_THINKING_BUDGET || '3000', 10) : 0;

/**
 * chat({system, prompt, messages, maxTokens, temperature, json})
 * json=true → returns parsed object (retries once with a repair nudge).
 */
async function chat(opts) {
  const messages = opts.messages && opts.messages.length ? opts.messages.slice() : [];
  if (!messages.length) {
    if (opts.system) messages.push({ role: 'system', content: opts.system });
    messages.push({ role: 'user', content: opts.prompt || '' });
  }
  const body = {
    model: MODEL,
    messages,
    max_tokens: (opts.maxTokens || 4096) + THINKING_EXTRA,
    temperature: opts.temperature ?? 0.3,
    chat_template_kwargs: { enable_thinking: ENABLE_THINKING },
  };
  const text = await post(body);
  if (!opts.json) return { text };
  let parsed = extractJson(text);
  if (parsed == null) {
    event('llm', 'JSON parse failed, retrying with repair nudge');
    messages.push({ role: 'assistant', content: text.slice(0, 4000) });
    messages.push({ role: 'user', content: 'Your previous answer was not valid JSON. Reply again with ONLY the JSON, no prose, no markdown fences.' });
    const retry = await post({ ...body, messages, temperature: 0.1 });
    parsed = extractJson(retry);
  }
  return { text, json: parsed };
}

async function post(body, attempt = 0) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 300000);
  try {
    const res = await fetch(BASE + '/chat/completions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + KEY },
      body: JSON.stringify(body),
      signal: ctrl.signal,
    });
    if (!res.ok) {
      const errText = (await res.text()).slice(0, 300);
      if (attempt < 2 && (res.status === 429 || res.status >= 500)) {
        await new Promise((r) => setTimeout(r, 3000 * (attempt + 1)));
        return post(body, attempt + 1);
      }
      throw new Error(`LLM HTTP ${res.status}: ${errText}`);
    }
    const data = await res.json();
    return data.choices?.[0]?.message?.content || '';
  } catch (e) {
    if (attempt < 2 && /abort|network|fetch failed|ECONN/i.test(String(e.message))) {
      await new Promise((r) => setTimeout(r, 3000 * (attempt + 1)));
      return post(body, attempt + 1);
    }
    throw e;
  } finally { clearTimeout(timer); }
}

module.exports = { chat };
