'use strict';
// How many completion tokens to allow a model call.
//
// max_tokens is a CEILING, not a spend: a model that answers in 800 tokens costs 800
// whatever the ceiling was. A ceiling set too low, on the other hand, costs everything —
// the answer is cut off mid-JSON and the whole call is re-run, prompt reprocessed and
// reasoning redone. In the JSON-java run, 4 of 12 completions finished with
// finish_reason=length at ~100 s each, and nothing remembered it, so the next call for
// the same stage walked into the same wall.
//
// So: start from the answer budget plus a thinking reserve, and let each stage learn from
// what actually happened to it.

const NEAR_CEILING = 0.9;   // an answer this close to the ceiling nearly did not fit

class Budget {
  constructor(opts = {}, saved = null) {
    this.thinkingReserve = opts.thinkingReserve ?? 0;
    this.hardMax = opts.hardMax ?? 16000;
    this.learned = { ...(saved?.learned || {}) };   // stage → ceiling this stage has earned
    this.near = { ...(saved?.near || {}) };         // stage → consecutive near-ceiling answers
  }

  /** Ceiling for the next call of this stage: the base need, or whatever it has learned. */
  ceiling(stage, answerTokens) {
    const base = (answerTokens || 4096) + this.thinkingReserve;
    return Math.min(this.hardMax, Math.max(base, this.learned[stage] || 0));
  }

  /** After a truncation: double, capped — and remember, so the next call starts there. */
  escalate(stage, current) {
    const next = Math.min(this.hardMax, current * 2);
    this.learned[stage] = Math.max(this.learned[stage] || 0, next);
    return next;
  }

  /** Would escalating from this ceiling actually buy room? */
  grew(stage, current) { return Math.min(this.hardMax, current * 2) > current; }

  /** Feed back what a completion actually did. */
  record(stage, { finish, ceiling, completionTokens } = {}) {
    if (finish === 'length') { this.near[stage] = 0; this.escalate(stage, ceiling); return; }
    const ratio = ceiling ? (completionTokens || 0) / ceiling : 0;
    if (ratio < NEAR_CEILING) { this.near[stage] = 0; return; }
    // stopping cleanly but only just — widen before the next answer runs off the end.
    // Two in a row, so one unusually long test does not inflate the stage for good.
    this.near[stage] = (this.near[stage] || 0) + 1;
    if (this.near[stage] >= 2) {
      this.learned[stage] = Math.min(this.hardMax, Math.max(this.learned[stage] || 0, Math.round(ceiling * 1.5)));
      this.near[stage] = 0;
    }
  }

  toJSON() { return { learned: this.learned, near: this.near }; }
}

module.exports = { Budget, NEAR_CEILING };
