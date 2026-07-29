package tech.mikhailov.ijt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The model client — the one call the pipeline makes hundreds of times and waits minutes for.
///
/// The JS module had no unit test of its own; it was IO, and IO was left to the live run
/// (sidecar/test/model/llm.test.js calls the real endpoint and asserts what a correct answer
/// must satisfy, which is a different question from the one below). That is affordable when
/// the whole retry policy is fifteen lines of `await`; it is not affordable here, because the
/// rules that cost real money are the ones a translation loses silently:
///
///   a truncated answer must be given ROOM, not a lecture about JSON formatting — the lecture
///   walks into the same wall for another two minutes, and four of twelve completions in one
///   run finished truncated at ~100 s each;
///
///   at the hard ceiling the retry is byte-identical and must not happen at all;
///
///   every attempt is charged and every attempt is shown, because a round that truncated once
///   and was retried used to report the retry's ninety seconds as the whole cost.
class LlmTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── clip: what the dashboard's live dialog keeps ──────────────────────────────────────

    @Test
    void aPromptThatFitsIsShownWhole() {
        assertEquals("short", Llm.clip("short", 10, 10));
        assertEquals("", Llm.clip("", 10, 10));
        // null is not a length-zero string in Java the way `String(undefined || '')` was in JS
        assertEquals("", Llm.clip(null, 10, 10));
    }

    @Test
    void theElisionOnlyStartsWhenItWouldActuallySaveSomething() {
        // `text.length <= head + tail + 40` — at exactly the boundary the notice announcing the
        // elision would be longer than what it elides, so the text is kept whole.
        String exact = "x".repeat(10 + 10 + Llm.ELIDE_SLACK);
        assertEquals(exact, Llm.clip(exact, 10, 10));

        String justOver = "x".repeat(10 + 10 + Llm.ELIDE_SLACK + 1);
        assertTrue(Llm.clip(justOver, 10, 10).contains("characters elided"));
    }

    @Test
    void aClippedPromptKeepsBothEndsAndSaysHowMuchWentMissing() {
        // A prompt can carry a whole method and an answer a whole test file, and the state file
        // this lands in is read by a browser. Keeping the beginning AND the end matters: the
        // beginning is the instruction and the end is the code the model was actually given.
        String text = "HEAD" + "z".repeat(500) + "TAIL";
        String clipped = Llm.clip(text, 4, 4);

        assertTrue(clipped.startsWith("HEAD"), clipped);
        assertTrue(clipped.endsWith("TAIL"), clipped);
        assertTrue(clipped.contains("\n\n… [500 characters elided] …\n\n"), clipped);
        // the count is the middle, so head + elided + tail accounts for every character
        assertEquals(text.length(), 4 + 500 + 4);
    }

    // ── the detail line beside an exchange ────────────────────────────────────────────────

    @Test
    void anExchangeDetailNamesTheStageTheNoteOrBoth() {
        // `(stageDetail || null) && note ? `${stageDetail} — ${note}` : (note || stageDetail || null)`
        assertEquals("writing tests for XML#parse — retry with 8192 tokens",
                Llm.exchangeDetail("writing tests for XML#parse", "retry with 8192 tokens"));
        assertEquals("writing tests for XML#parse",
                Llm.exchangeDetail("writing tests for XML#parse", null));
        assertEquals("retry after invalid JSON", Llm.exchangeDetail(null, "retry after invalid JSON"));
        assertNull(Llm.exchangeDetail(null, null));
    }

    @Test
    void anEmptyStageDetailIsNoStageDetail() {
        // Falsy in JS, and a Java null-check alone would print " — retry" with nothing in front
        // of it on precisely the exchange a reader is trying to understand.
        assertEquals("retry", Llm.exchangeDetail("", "retry"));
        assertNull(Llm.exchangeDetail("", null));
        assertEquals("stage", Llm.exchangeDetail("stage", ""));
    }

    // ── the conversation that is actually sent ────────────────────────────────────────────

    @Test
    void systemAndPromptBecomeTwoTurnsInThatOrder() {
        List<Llm.Message> m = Llm.messagesFor(Llm.Options.of().withSystem("be strict").withPrompt("do it"));

        assertEquals(2, m.size());
        assertEquals(new Llm.Message("system", "be strict"), m.get(0));
        assertEquals(new Llm.Message("user", "do it"), m.get(1));
    }

    @Test
    void anAbsentSystemPromptAddsNoTurnAtAll() {
        // `if (opts.system)` — an empty system turn is not the same as none, and some chat
        // templates render it as a stray header the model then tries to obey.
        assertEquals(List.of(new Llm.Message("user", "do it")),
                Llm.messagesFor(Llm.Options.of().withPrompt("do it")));
        assertEquals(List.of(new Llm.Message("user", "do it")),
                Llm.messagesFor(Llm.Options.of().withSystem("").withPrompt("do it")));
    }

    @Test
    void anAbsentPromptStillSendsAUserTurn() {
        // `opts.prompt || ''`. The endpoint rejects a conversation whose last turn is the
        // system one, so the empty user turn is load-bearing.
        assertEquals(List.of(new Llm.Message("user", "")), Llm.messagesFor(Llm.Options.of()));
    }

    @Test
    void aSuppliedConversationWinsAndIsCopiedRatherThanUsed() {
        // `opts.messages.slice()`. The repair turn appends to this list; appending to the
        // caller's own would leave a "that was not valid JSON" nudge in the history of whatever
        // it asks next.
        List<Llm.Message> caller = new ArrayList<>(List.of(new Llm.Message("user", "hello")));
        List<Llm.Message> used = Llm.messagesFor(
                Llm.Options.of().withSystem("ignored").withPrompt("ignored").withMessages(caller));

        assertEquals(caller, used);
        used.add(new Llm.Message("user", "nudge"));
        assertEquals(1, caller.size(), "the caller's own conversation was appended to");
    }

    @Test
    void anEmptyConversationFallsBackToSystemAndPrompt() {
        // `opts.messages && opts.messages.length` — an empty array is not a conversation.
        assertEquals(2, Llm.messagesFor(Llm.Options.of()
                .withMessages(List.of()).withSystem("s").withPrompt("p")).size());
    }

    // ── the request body ──────────────────────────────────────────────────────────────────

    @Test
    void theRequestBodyIsTheShapeAnOpenAiCompatibleEndpointExpects() {
        ObjectNode body = Llm.bodyJson(new Llm.Body("qwen-3.6-27b-fp8",
                List.of(new Llm.Message("system", "s"), new Llm.Message("user", "u")),
                4096, 0.3, true));

        assertEquals("qwen-3.6-27b-fp8", body.get("model").asText());
        assertEquals(2, body.get("messages").size());
        assertEquals("system", body.get("messages").get(0).get("role").asText());
        assertEquals("u", body.get("messages").get(1).get("content").asText());
        assertEquals(4096, body.get("max_tokens").asInt());
        assertEquals(0.3, body.get("temperature").asDouble());
        // vLLM's qwen template reads thinking out of here, and nowhere else
        assertTrue(body.get("chat_template_kwargs").get("enable_thinking").asBoolean());
    }

    @Test
    void maxTokensIsSerialisedAsAWholeNumber() {
        // String.valueOf(4096.0) is "4096.0" in Java and "4096" in JS. An endpoint that
        // validates max_tokens as an integer rejects the first outright.
        String json = Llm.bodyJson(new Llm.Body("m", List.of(), 8192, 0.3, false)).toString();
        assertTrue(json.contains("\"max_tokens\":8192"), json);
    }

    // ── the endpoint, as the environment describes it ─────────────────────────────────────

    @Test
    void anUnconfiguredEnvironmentStillProducesAUsableModelName() {
        Llm.Config c = Llm.envConfig(env());

        assertEquals("", c.base());
        assertEquals("", c.key());
        assertEquals("qwen-3.6-27b-fp8", c.model());
        assertFalse(c.enableThinking());
        assertEquals(0, c.thinkingExtra());
        assertEquals(16000, c.hardMax());
    }

    @Test
    void exactlyOneTrailingSlashIsRemovedFromTheBaseUrl() {
        // `.replace(/\/$/, '')` — anchored and without /g. The path appended to it starts with
        // a slash, so a base kept whole would ask for `/v1//chat/completions`.
        assertEquals("https://h/qwen/v1", Llm.envConfig(env("LLM_BASE_URL", "https://h/qwen/v1/")).base());
        assertEquals("https://h/qwen/v1", Llm.envConfig(env("LLM_BASE_URL", "https://h/qwen/v1")).base());
        assertEquals("https://h/qwen/v1/", Llm.envConfig(env("LLM_BASE_URL", "https://h/qwen/v1//")).base());
    }

    @Test
    void thinkingIsOffUnlessTheEnvironmentSaysExactlyTrue() {
        // `String(x || 'false') === 'true'`. "1", "yes" and "TRUE" are all off — which is worth
        // pinning, because turning thinking on silently adds 3000 tokens to every ceiling.
        assertTrue(Llm.envConfig(env("LLM_ENABLE_THINKING", "true")).enableThinking());
        assertFalse(Llm.envConfig(env("LLM_ENABLE_THINKING", "TRUE")).enableThinking());
        assertFalse(Llm.envConfig(env("LLM_ENABLE_THINKING", "1")).enableThinking());
        assertFalse(Llm.envConfig(env("LLM_ENABLE_THINKING", "")).enableThinking());
    }

    @Test
    void theThinkingReserveIsZeroWhileThinkingIsOffHoweverBigTheBudgetSays() {
        // thinking consumes completion tokens before any visible output, so it gets headroom —
        // but reserving it with thinking off would shrink every answer's real room for nothing.
        Map<String, String> both = new HashMap<>();
        both.put("LLM_THINKING_BUDGET", "5000");
        assertEquals(0, Llm.envConfig(both::get).thinkingExtra());

        both.put("LLM_ENABLE_THINKING", "true");
        assertEquals(5000, Llm.envConfig(both::get).thinkingExtra());
    }

    @Test
    void aTypoInTheCeilingTakesTheDefaultRatherThanPoisoningEveryCall() {
        // parseInt('lots') is NaN in JS, NaN survives `?? 16000`, and every Math.min downstream
        // answers NaN — max_tokens serialises as null and the endpoint picks the ceiling for us.
        assertEquals(16000, Llm.envConfig(env("LLM_MAX_TOKENS_HARD", "lots")).hardMax());
        assertEquals(20000, Llm.envConfig(env("LLM_MAX_TOKENS_HARD", "20000")).hardMax());
        // parseInt's own tolerance is kept: '20000 ' and '20000tokens' are both 20000 in JS
        assertEquals(20000, Llm.envConfig(env("LLM_MAX_TOKENS_HARD", " 20000tokens")).hardMax());
    }

    // ── the live status line ──────────────────────────────────────────────────────────────

    @Test
    void theStatusLineNamesTheModelAndWhetherItIsThinking() {
        assertEquals("qwen-3.6-27b-fp8", Llm.progressLabel(config(false, 0, 16000)));
        assertEquals("qwen-3.6-27b-fp8 (thinking)", Llm.progressLabel(config(true, 3000, 16000)));
    }

    @Test
    void theFirstAttemptIsNotCalledARetry() {
        // `attempt ? ` (retry ${attempt})` : ''` — 0 is falsy, and "retry 0" would report every
        // single call as a retry.
        assertEquals("m: waiting for completion — 12s", Llm.waitingLine("m", 12, 0));
        assertEquals("m: waiting for completion — 12s (retry 1)", Llm.waitingLine("m", 12, 1));
    }

    @Test
    void theClosingStatusLineNamesAFinishReasonOnlyWhenItIsWorthReading() {
        // `stop` is the normal end; printing it on every call would bury the one time the line
        // says `length`, which is the whole diagnosis for a round that produced nothing.
        assertEquals("m: 1200 in / 800 out tokens in 42s", Llm.usageLine("m", 1200, 800, 42, "stop"));
        assertEquals("m: 1200 in / 800 out tokens in 42s", Llm.usageLine("m", 1200, 800, 42, null));
        assertEquals("m: 1200 in / 800 out tokens in 42s [length]",
                Llm.usageLine("m", 1200, 800, 42, "length"));
    }

    @Test
    void aDurationIsRoundedRatherThanTruncated() {
        // `Math.round((Date.now() - started) / 1000)`. Between two Java longs the division
        // truncates, so a 47.6 s call would be reported as 47 — and these seconds are summed
        // into what a unit's improvement is said to have cost.
        assertEquals(48, Llm.secondsBetween(1_000_000L, 1_047_600L));
        assertEquals(47, Llm.secondsBetween(1_000_000L, 1_047_400L));
        assertEquals(1, Llm.secondsBetween(1_000_000L, 1_000_500L), "JS Math.round takes .5 upward");
        assertEquals(0, Llm.secondsBetween(1_000_000L, 1_000_000L));
    }

    // ── when to send the same request again ───────────────────────────────────────────────

    @Test
    void onlyRateLimitsAndServerFaultsAreWorthResending() {
        assertTrue(Llm.retryableStatus(429));
        assertTrue(Llm.retryableStatus(500));
        assertTrue(Llm.retryableStatus(503));
        // a 4xx is an answer about the request itself; three times the wait, same refusal
        assertFalse(Llm.retryableStatus(400));
        assertFalse(Llm.retryableStatus(401));
        assertFalse(Llm.retryableStatus(404));
    }

    @Test
    void aRefusalIsNeverMistakenForAnOutage() {
        // THE reason the status carries its own exception type. The JS decided this by matching
        // the error's TEXT — /abort|network|fetch failed|ECONN/i — and that text ends with 300
        // characters of the server's own body, so an `LLM HTTP 400` whose body happened to
        // mention a network policy was re-sent twice more to a server that had already rejected
        // it on its merits.
        assertFalse(Llm.retryableFailure(
                new Llm.HttpStatusException(400, "{\"error\":\"network policy denies this model\"}")));
        assertFalse(Llm.retryableFailure(new Llm.MalformedAnswerException("not a completion")));
        // the transport, which is what the regex was really for
        assertTrue(Llm.retryableFailure(new java.net.http.HttpTimeoutException("no completion")));
        assertTrue(Llm.retryableFailure(new java.net.ConnectException("connection refused")));
        assertTrue(Llm.retryableFailure(new IOException("connection reset by peer")));
        // and nothing else: a bug in this backend is not an outage to sit out
        assertFalse(Llm.retryableFailure(new IllegalStateException("boom")));
    }

    // ── reading a completion ──────────────────────────────────────────────────────────────

    @Test
    void aNormalCompletionIsReadOffTheFirstChoice() {
        Llm.Completion c = Llm.readCompletion(json(completion("{\"ok\":1}", "stop", 1200, 800)));

        assertEquals("{\"ok\":1}", c.content());
        assertEquals("stop", c.finish());
        assertEquals(800, c.completionTokens());
        assertEquals(1200, c.promptTokens());
    }

    @Test
    void aResponseWithNoUsageBlockCostsZeroRatherThanFailing() {
        // `data.usage || {}` then `|| 0` on each field. A proxy that strips usage must not take
        // the answer down with it — the answer is what the round needs; the accounting is not.
        Llm.Completion c = Llm.readCompletion(json("{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}"));

        assertEquals("hi", c.content());
        assertNull(c.finish());
        assertEquals(0, c.completionTokens());
        assertEquals(0, c.promptTokens());
    }

    @Test
    void anEmptyFinishReasonIsNoFinishReason() {
        // `|| null`. It is written straight into the exchange log a human reads, and `[]` beside
        // a token count says nothing at all.
        assertNull(Llm.readCompletion(json(completion("hi", "", 1, 1))).finish());
        assertEquals("length", Llm.readCompletion(json(completion("hi", "length", 1, 1))).finish());
    }

    @Test
    void aResponseWithNoChoicesIsAnEmptyAnswerNotANullOne() {
        // `|| ''`. Everything downstream calls .length or feeds it to a regex.
        assertEquals("", Llm.readCompletion(json("{\"choices\":[]}")).content());
        assertEquals("", Llm.readCompletion(json("{}")).content());
    }

    // ── chat: the plain call ──────────────────────────────────────────────────────────────

    @Test
    void aPlainCallReturnsTheTextAndChargesItToTheRun() throws Exception {
        FakeIo io = new FakeIo();
        io.answers.add(ok(completion("the answer", "stop", 1200, 800)));
        Llm llm = client(io, config(false, 0, 16000));

        Llm.Answer a = llm.chat(Llm.Options.of().withSystem("be strict").withPrompt("do it"));

        assertEquals("the answer", a.text());
        assertNull(a.json(), "json was not asked for and must not be invented");
        assertEquals(1, io.requests.size());
        // token accounting: every call's usage is attributed to the unit being worked on, so the
        // dashboard can report what the improvement cost
        assertEquals(List.of("1200/800"), io.tokens);
        assertEquals("https://h/v1/chat/completions", io.urls.get(0));
    }

    @Test
    void theCallerSuppliesTheAnswerBudgetAndTheStageKeepsWhatItLearns() throws Exception {
        FakeIo io = new FakeIo();
        io.answers.add(ok(completion("x", "stop", 10, 10)));
        Llm llm = client(io, config(true, 3000, 16000));

        llm.chat(Llm.Options.of().withPrompt("p").withMaxTokens(2000).withStage("writing_tests"));

        // the answer budget plus the thinking reserve — thinking spends completion tokens
        // before any visible output, and a ceiling that ignores it truncates every answer
        assertEquals(5000, sent(io, 0).get("max_tokens").asInt());
        assertEquals(1, io.budgets.size(), "the learned ceilings are persisted with the run");
    }

    @Test
    void anExplicitTemperatureOfZeroIsNotReplacedByTheDefault() throws Exception {
        // `opts.temperature ?? 0.3` — nullish, not falsy. A caller asking for a deterministic
        // answer must get one.
        FakeIo io = new FakeIo();
        io.answers.add(ok(completion("x", "stop", 1, 1)));
        client(io, config(false, 0, 16000))
                .chat(Llm.Options.of().withPrompt("p").withTemperature(0.0));

        assertEquals(0.0, sent(io, 0).get("temperature").asDouble());
    }

    @Test
    void anUnstatedTemperatureIsThreeTenths() throws Exception {
        FakeIo io = new FakeIo();
        io.answers.add(ok(completion("x", "stop", 1, 1)));
        client(io, config(false, 0, 16000)).chat(Llm.Options.of().withPrompt("p"));

        assertEquals(0.3, sent(io, 0).get("temperature").asDouble());
    }

    @Test
    void everyCallReachesTheDashboardsLiveDialog() throws Exception {
        FakeIo io = new FakeIo();
        io.tickPerCall = 42_000L;
        io.answers.add(ok(completion("the answer", "stop", 1200, 800)));

        client(io, config(false, 0, 16000)).chat(Llm.Options.of()
                .withSystem("be strict").withPrompt("do it")
                .withStage("writing_tests").withStageDetail("XML#parse"));

        assertEquals(1, io.exchanges.size());
        Map<String, Object> e = io.exchanges.get(0);
        assertEquals("writing_tests", e.get("stage"));
        assertEquals("XML#parse", e.get("detail"));
        assertEquals("be strict", e.get("system"));
        assertEquals("do it", e.get("prompt"));
        assertEquals("the answer", e.get("response"));
        assertEquals("stop", e.get("finish"));
        assertEquals(42L, e.get("secs"));
    }

    @Test
    void aConversationWithNoPromptIsLoggedByItsLastTurn() throws Exception {
        // `opts.prompt || (messages[messages.length - 1] || {}).content || ''` — a caller that
        // passed a whole conversation has no `prompt`, and an empty box in the dialog is how a
        // live run looked like it was asking nothing.
        FakeIo io = new FakeIo();
        io.answers.add(ok(completion("a", "stop", 1, 1)));

        client(io, config(false, 0, 16000)).chat(Llm.Options.of().withMessages(
                List.of(new Llm.Message("user", "first"), new Llm.Message("user", "last"))));

        assertEquals("last", io.exchanges.get(0).get("prompt"));
    }

    @Test
    void anUnnamedStageIsLoggedAsNoneAndBudgetedAsDefault() throws Exception {
        // `stage: opts.stage || null` in the log, `const stage = opts.stage || 'default'` for the
        // budget. Two different falsy fallbacks on the same field, three lines apart.
        FakeIo io = new FakeIo();
        io.answers.add(ok(completion("a", "length", 1, 1)));
        Llm llm = client(io, config(false, 0, 16000));

        llm.chat(Llm.Options.of().withPrompt("p"));

        assertNull(io.exchanges.get(0).get("stage"));
        assertEquals(8192, llm.budget().snapshot().learned().get("default"),
                "the truncation was learned against the 'default' key");
    }

    // ── chat: JSON, and the two quite different second chances ────────────────────────────

    @Test
    void anAnswerThatParsesIsReturnedWithoutASecondCall() throws Exception {
        FakeIo io = new FakeIo();
        io.answers.add(ok(completion("here you go: {\"exclude\": []}", "stop", 10, 10)));

        Llm.Answer a = client(io, config(false, 0, 16000))
                .chat(Llm.Options.of().withPrompt("p").withJson(true));

        assertNotNull(a.json());
        assertEquals(0, a.json().get("exclude").size());
        assertEquals(1, io.requests.size(), "a parseable answer must not cost a second call");
        assertEquals(List.of(), io.events);
    }

    @Test
    void aTruncatedAnswerIsGivenRoomRatherThanALectureAboutFormatting() throws Exception {
        // The rule this module exists for. finish_reason=length is not a formatting mistake —
        // the model ran out of room mid-JSON, and "reply with ONLY the JSON" reruns the same
        // wall for another minute or two at the same ceiling.
        FakeIo io = new FakeIo();
        io.answers.add(ok(completion("{\"tests\": [\"partial", "length", 100, 4096)));
        io.answers.add(ok(completion("{\"tests\": [\"whole\"]}", "stop", 100, 5000)));

        Llm.Answer a = client(io, config(false, 0, 16000))
                .chat(Llm.Options.of().withPrompt("p").withJson(true).withMaxTokens(4096));

        assertEquals(2, io.requests.size());
        assertEquals(4096, sent(io, 0).get("max_tokens").asInt());
        assertEquals(8192, sent(io, 1).get("max_tokens").asInt(), "the retry was given no more room");
        // the SAME conversation, not a scolded one
        assertEquals(1, sent(io, 1).get("messages").size());
        assertEquals(List.of("llm | response hit the token limit mid-JSON — retrying with 8192 tokens"
                + " instead of 4096"), io.events);
        // and the answer that parsed is the one that comes back, text and all
        assertEquals("{\"tests\": [\"whole\"]}", a.text());
        assertEquals("whole", a.json().get("tests").get(0).asText());
    }

    @Test
    void theWiderCeilingIsRememberedSoTheNextRoundStartsThere() throws Exception {
        // Nothing remembered this in the JSON-java run: 4 of 12 completions finished truncated
        // at ~100 s each, and the next call for the same stage walked into the same wall.
        FakeIo io = new FakeIo();
        io.answers.add(ok(completion("{ partial", "length", 10, 4096)));
        io.answers.add(ok(completion("{\"ok\":1}", "stop", 10, 10)));
        Llm llm = client(io, config(false, 0, 16000));

        llm.chat(Llm.Options.of().withPrompt("p").withJson(true).withMaxTokens(4096).withStage("mutation"));

        assertEquals(8192, llm.budget().ceiling("mutation", 4096));
        assertEquals(8192, llm.budget().snapshot().learned().get("mutation"));
        // persisted after the first completion and again after the escalation, and a flush asked
        // for in between — a ceiling learned and then lost to a crash was learned for nothing
        assertEquals(2, io.budgets.size());
        assertEquals(1, io.saves);
    }

    @Test
    void atTheHardCeilingTheIdenticalRetryIsNotEvenAttempted() throws Exception {
        // A retry here is byte-identical: same request, same truncation, another two minutes.
        FakeIo io = new FakeIo();
        io.answers.add(ok(completion("{ never closes", "length", 100, 4096)));

        Llm.Answer a = client(io, config(false, 0, 4096))
                .chat(Llm.Options.of().withPrompt("p").withJson(true).withMaxTokens(4096));

        assertEquals(1, io.requests.size(), "the same request was sent again for the same answer");
        assertNull(a.json());
        assertEquals("{ never closes", a.text(), "the truncated text still reaches the caller");
        assertEquals(List.of("llm | response truncated at the maximum budget (4096 tokens)"
                + " — not retrying an identical request"), io.events);
    }

    @Test
    void aMalformedAnswerGetsTheFormattingNudgeAndNothingElse() throws Exception {
        FakeIo io = new FakeIo();
        io.answers.add(ok(completion("Sure! Here are the tests you asked for.", "stop", 100, 200)));
        io.answers.add(ok(completion("{\"tests\": []}", "stop", 300, 50)));

        Llm.Answer a = client(io, config(false, 0, 16000))
                .chat(Llm.Options.of().withSystem("s").withPrompt("p").withJson(true).withMaxTokens(4096));

        assertEquals(2, io.requests.size());
        JsonNode retry = sent(io, 1);
        // the original conversation, the answer quoted back, and the nudge
        assertEquals(4, retry.get("messages").size());
        assertEquals("assistant", retry.get("messages").get(2).get("role").asText());
        assertEquals("Sure! Here are the tests you asked for.",
                retry.get("messages").get(2).get("content").asText());
        assertEquals(Llm.REPAIR_NUDGE, retry.get("messages").get(3).get("content").asText());
        // colder, because what is wanted the second time is obedience to the format
        assertEquals(0.1, retry.get("temperature").asDouble());
        assertEquals(4096, retry.get("max_tokens").asInt(), "a malformed answer is not a cramped one");
        assertEquals(List.of("llm | JSON parse failed, retrying with repair nudge"), io.events);
        assertNotNull(a.json());
    }

    @Test
    void theRepairedJsonComesBackBesideTheAnswerTheCallersOwnPromptProduced() throws Exception {
        // `return { text, json: parsed }` at the end of chat: `text` is the FIRST answer even
        // when the repair is what parsed. The caller acts on `json`; `text` is what its prompt
        // actually produced, and a prompt tuner reading the dialog needs to see that.
        FakeIo io = new FakeIo();
        io.answers.add(ok(completion("no json here at all", "stop", 1, 1)));
        io.answers.add(ok(completion("{\"repaired\":true}", "stop", 1, 1)));

        Llm.Answer a = client(io, config(false, 0, 16000))
                .chat(Llm.Options.of().withPrompt("p").withJson(true));

        assertEquals("no json here at all", a.text());
        assertTrue(a.json().get("repaired").asBoolean());
    }

    @Test
    void anEscalationThatStillFailsFallsThroughToTheNudge() throws Exception {
        // The fall-through the JS reached by re-reading a module-level lastFinishReason the
        // retry had just overwritten. `grew` is still computed from the ORIGINAL ceiling, so a
        // stage that could grow once cannot reach the "at the maximum budget" branch on this
        // pass — it gets the nudge instead, at the original, smaller ceiling.
        FakeIo io = new FakeIo();
        io.answers.add(ok(completion("{ partial", "length", 10, 4096)));
        io.answers.add(ok(completion("{ still partial", "length", 10, 8192)));
        io.answers.add(ok(completion("{\"ok\":1}", "stop", 10, 10)));

        Llm.Answer a = client(io, config(false, 0, 16000))
                .chat(Llm.Options.of().withPrompt("p").withJson(true).withMaxTokens(4096));

        assertEquals(3, io.requests.size());
        assertEquals(8192, sent(io, 1).get("max_tokens").asInt());
        assertEquals(4096, sent(io, 2).get("max_tokens").asInt(), "the nudge went at the original ceiling");
        assertEquals(3, sent(io, 2).get("messages").size());
        assertEquals(List.of(
                "llm | response hit the token limit mid-JSON — retrying with 8192 tokens instead of 4096",
                "llm | JSON parse failed, retrying with repair nudge"), io.events);
        assertNotNull(a.json());
    }

    @Test
    void everyAttemptIsChargedAndEveryAttemptIsShown() throws Exception {
        // A truncated answer that was then retried used to be shown as THE answer for that
        // round — an empty response and a duration a third of what the round really spent in the
        // model. The cost of a unit is everything spent getting there, not the successful call.
        FakeIo io = new FakeIo();
        io.tickPerCall = 30_000L;
        io.answers.add(ok(completion("{ partial", "length", 500, 4096)));
        io.answers.add(ok(completion("{\"ok\":1}", "stop", 500, 900)));

        client(io, config(false, 0, 16000)).chat(Llm.Options.of()
                .withPrompt("p").withJson(true).withMaxTokens(4096).withStageDetail("XML#parse"));

        assertEquals(List.of("500/4096", "500/900"), io.tokens);
        assertEquals(2, io.exchanges.size());
        assertEquals("XML#parse", io.exchanges.get(0).get("detail"));
        assertEquals("XML#parse — retry with 8192 tokens", io.exchanges.get(1).get("detail"));
        assertEquals("length", io.exchanges.get(0).get("finish"));
        assertEquals("stop", io.exchanges.get(1).get("finish"));
        // seconds since CHAT began, not since this attempt did
        assertEquals(30L, io.exchanges.get(0).get("secs"));
        assertEquals(60L, io.exchanges.get(1).get("secs"));
    }

    @Test
    void aNudgedRetryIsShownAsOneToo() throws Exception {
        FakeIo io = new FakeIo();
        io.answers.add(ok(completion("prose", "stop", 1, 1)));
        io.answers.add(ok(completion("{\"ok\":1}", "stop", 1, 1)));

        client(io, config(false, 0, 16000)).chat(Llm.Options.of().withPrompt("p").withJson(true));

        assertEquals(2, io.exchanges.size());
        assertEquals("retry after invalid JSON", io.exchanges.get(1).get("detail"));
    }

    // ── chat: the transport ───────────────────────────────────────────────────────────────

    @Test
    void aRateLimitedRequestIsBackedOffAndSentAgain() throws Exception {
        FakeIo io = new FakeIo();
        io.answers.add(new Llm.Response(429, "slow down"));
        io.answers.add(ok(completion("finally", "stop", 1, 1)));

        Llm.Answer a = client(io, config(false, 0, 16000)).chat(Llm.Options.of().withPrompt("p"));

        assertEquals("finally", a.text());
        assertEquals(List.of(3_000L), io.sleeps);
        assertEquals(sent(io, 0), sent(io, 1), "the retry must be the same request");
    }

    @Test
    void aServerFaultIsSentThreeTimesAndThenGivenUpOn() throws Exception {
        // `attempt < 2`, counting from zero — three tries, 3 s and then 6 s apart.
        FakeIo io = new FakeIo();
        for (int i = 0; i < 3; i++) io.answers.add(new Llm.Response(503, "upstream is restarting"));

        Llm.HttpStatusException e = assertThrows(Llm.HttpStatusException.class,
                () -> client(io, config(false, 0, 16000)).chat(Llm.Options.of().withPrompt("p")));

        assertEquals(3, io.requests.size());
        assertEquals(List.of(3_000L, 6_000L), io.sleeps);
        assertEquals(503, e.status());
        assertTrue(e.getMessage().startsWith("LLM HTTP 503: upstream is restarting"), e.getMessage());
    }

    @Test
    void aRefusedRequestIsNotSentAgain() throws Exception {
        FakeIo io = new FakeIo();
        io.answers.add(new Llm.Response(400, "unknown model"));

        Llm.HttpStatusException e = assertThrows(Llm.HttpStatusException.class,
                () -> client(io, config(false, 0, 16000)).chat(Llm.Options.of().withPrompt("p")));

        assertEquals(1, io.requests.size());
        assertEquals(List.of(), io.sleeps);
        assertEquals(400, e.status());
    }

    @Test
    void onlyTheFirstThreeHundredCharactersOfAnErrorPageAreQuoted() throws Exception {
        // `(await res.text()).slice(0, 300)`. A gateway answers a 502 with an HTML page, and the
        // whole of it would go into an event line, the dashboard and an n8n error field.
        FakeIo io = new FakeIo();
        io.answers.add(new Llm.Response(400, "E".repeat(5000)));

        Llm.HttpStatusException e = assertThrows(Llm.HttpStatusException.class,
                () -> client(io, config(false, 0, 16000)).chat(Llm.Options.of().withPrompt("p")));

        assertEquals("LLM HTTP 400: " + "E".repeat(300), e.getMessage());
    }

    @Test
    void aDeadlineOrADroppedConnectionIsRetried() throws Exception {
        // The AbortController's five minutes, and every transport failure undici wrapped in the
        // single message "fetch failed".
        FakeIo io = new FakeIo();
        io.answers.add(new java.net.http.HttpTimeoutException("no completion after 300000 ms"));
        io.answers.add(new java.net.ConnectException("connection refused"));
        io.answers.add(ok(completion("third time", "stop", 1, 1)));

        Llm.Answer a = client(io, config(false, 0, 16000)).chat(Llm.Options.of().withPrompt("p"));

        assertEquals("third time", a.text());
        assertEquals(List.of(3_000L, 6_000L), io.sleeps);
    }

    @Test
    void anAnswerThatIsNotACompletionIsNotRetried() throws Exception {
        // The endpoint replied, in time, with a 200 and something that is not a chat completion.
        // Asking again produces the same thing. Jackson is more forgiving than JSON.parse —
        // readTree("") answers a MissingNode — so this is checked rather than assumed.
        FakeIo io = new FakeIo();
        io.answers.add(new Llm.Response(200, ""));

        assertThrows(Llm.MalformedAnswerException.class,
                () -> client(io, config(false, 0, 16000)).chat(Llm.Options.of().withPrompt("p")));
        assertEquals(1, io.requests.size());
    }

    @Test
    void theRequestSentIsWhatTheDashboardIsToldIsInFlight() throws Exception {
        FakeIo io = new FakeIo();
        io.tickPerCall = 42_000L;
        io.answers.add(ok(completion("x", "length", 1200, 800)));

        client(io, config(true, 3000, 16000)).chat(Llm.Options.of().withPrompt("p"));

        assertEquals(List.of(
                "qwen-3.6-27b-fp8 (thinking): request sent",
                "qwen-3.6-27b-fp8 (thinking): 1200 in / 800 out tokens in 42s [length]"), io.progress);
    }

    // ── the real transport ────────────────────────────────────────────────────────────────

    @Test
    void theRequestCarriesTheBearerTokenAndTheBodyItWasGiven() throws Exception {
        List<String> auth = new CopyOnWriteArrayList<>();
        List<String> type = new CopyOnWriteArrayList<>();
        List<String> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = stub(exchange -> {
            auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            type.add(exchange.getRequestHeaders().getFirst("Content-Type"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            return new Llm.Response(200, completion("hi", "stop", 1, 1));
        });
        try {
            Llm.Response res = new Llm.StateIo("sk-secret")
                    .post(base(server) + "/chat/completions", "{\"model\":\"m\"}");

            assertEquals(200, res.status());
            assertEquals(List.of("Bearer sk-secret"), auth);
            assertEquals(List.of("application/json"), type);
            assertEquals(List.of("{\"model\":\"m\"}"), bodies);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aRefusalComesBackAsAStatusRatherThanAnException() throws Exception {
        // The retry rule reads the status, so the transport must hand it over instead of
        // throwing — a 429 and a 400 take opposite paths and both arrive here.
        HttpServer server = stub(exchange -> new Llm.Response(429, "slow down"));
        try {
            Llm.Response res = new Llm.StateIo("k").post(base(server) + "/chat/completions", "{}");

            assertEquals(429, res.status());
            assertEquals("slow down", res.body());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aUtf8AnswerSurvivesTheWire() throws Exception {
        // Model output carries the em dashes and ellipses this codebase writes in its own event
        // lines, and a Java default charset of anything but UTF-8 would mangle a generated test.
        HttpServer server = stub(exchange -> new Llm.Response(200, completion("assertEquals(\"café — ok\", x);", "stop", 1, 1)));
        try {
            Llm.Response res = new Llm.StateIo("k").post(base(server) + "/chat/completions", "{}");

            assertTrue(res.body().contains("café — ok"), res.body());
        } finally {
            server.stop(0);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────

    private static Llm.Config config(boolean thinking, int reserve, int hardMax) {
        return new Llm.Config("https://h/v1", "sk-test", "qwen-3.6-27b-fp8", thinking, reserve, hardMax);
    }

    private static Llm client(FakeIo io, Llm.Config cfg) {
        return new Llm(cfg, new Budget(new Budget.Options(cfg.thinkingExtra(), cfg.hardMax())), io);
    }

    /// An environment with the named variables set and nothing else.
    private static java.util.function.UnaryOperator<String> env(String... pairs) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        return m::get;
    }

    private static Llm.Response ok(String body) { return new Llm.Response(200, body); }

    /// A completion in the shape an OpenAI-compatible endpoint returns one.
    private static String completion(String content, String finish, int promptTokens, int completionTokens) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode choice = root.putArray("choices").addObject();
        choice.putObject("message").put("content", content);
        choice.put("finish_reason", finish);
        ObjectNode usage = root.putObject("usage");
        usage.put("prompt_tokens", promptTokens);
        usage.put("completion_tokens", completionTokens);
        return root.toString();
    }

    private static JsonNode json(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /// The nth request body this client actually sent.
    private static JsonNode sent(FakeIo io, int n) { return json(io.requests.get(n)); }

    /// A one-shot HTTP server on a free port, answering every request the same way.
    private static HttpServer stub(Responder responder) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            Llm.Response answer;
            try {
                answer = responder.answer(exchange);
            } catch (Exception e) {
                answer = new Llm.Response(500, String.valueOf(e.getMessage()));
            }
            byte[] out = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(answer.status(), out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static String base(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort()).toString();
    }

    @FunctionalInterface
    private interface Responder {
        Llm.Response answer(com.sun.net.httpserver.HttpExchange exchange) throws Exception;
    }

    /// The world, scripted.
    ///
    /// `answers` holds either a {@link Llm.Response} or an exception to throw, one per request,
    /// so a test can say "429, then a completion" and check what actually went out in between.
    private static final class FakeIo implements Llm.Io {

        final Deque<Object> answers = new ArrayDeque<>();
        final List<String> urls = new ArrayList<>();
        final List<String> requests = new ArrayList<>();
        final List<String> progress = new CopyOnWriteArrayList<>();
        final List<String> events = new ArrayList<>();
        final List<Map<String, Object>> exchanges = new ArrayList<>();
        final List<Budget.Saved> budgets = new ArrayList<>();
        final List<Long> sleeps = new ArrayList<>();
        final List<String> tokens = new ArrayList<>();
        int saves = 0;

        /// A clock the test moves, so the durations this module reports can be asserted without
        /// anything actually waiting. Volatile because the heartbeat thread reads it.
        volatile long clock = 1_000_000L;

        /// How much wall clock each request costs.
        long tickPerCall = 0L;

        @Override
        public Llm.Response post(String url, String jsonBody) throws IOException {
            urls.add(url);
            requests.add(jsonBody);
            clock += tickPerCall;
            Object next = answers.poll();
            if (next == null) throw new AssertionError("an unscripted request went out: " + jsonBody);
            if (next instanceof IOException e) throw e;
            return (Llm.Response) next;
        }

        @Override public void sleep(long millis) { sleeps.add(millis); }

        @Override public void setProgress(String line, long elapsedSec) { progress.add(line); }

        @Override public void addTokens(long in, long out) { tokens.add(in + "/" + out); }

        @Override public void addLlmExchange(Map<String, Object> entry) { exchanges.add(entry); }

        @Override public void event(String stage, String msg) { events.add(stage + " | " + msg); }

        @Override public void budgetSaved(Budget.Saved saved) { budgets.add(saved); }

        @Override public void save() { saves++; }

        @Override public long nowMillis() { return clock; }
    }
}
