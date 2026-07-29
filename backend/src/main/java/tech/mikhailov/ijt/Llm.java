package tech.mikhailov.ijt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.UnaryOperator;

/// OpenAI-compatible chat client for the vLLM endpoint (qwen).
///
/// The Node sidecar reached this with zero dependencies via the global `fetch`; here it is
/// `java.net.http.HttpClient`, which is equally in the platform. Jackson does the JSON, for
/// the reason the pom gives: hand-rolling a parser for model output is a bug farm.
///
/// Everything that DECIDES something — which messages to send, which of three things to do
/// when an answer does not parse, what the dashboard's live line says, how much of a prompt
/// to keep in the log — is a static function over its arguments and is tested as one. The
/// only IO is the HTTP exchange and the state writes, and both go through {@link Io}.
public final class Llm {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── the numbers ───────────────────────────────────────────────────────────────────────

    /// The endpoint path appended to `LLM_BASE_URL`, which already carries the `/v1`.
    static final String CHAT_PATH = "/chat/completions";

    /// The AbortController's deadline in the JS: five minutes for one completion.
    public static final long REQUEST_TIMEOUT_MS = 300_000L;

    /// How often the dashboard hears that the model is still thinking.
    static final long HEARTBEAT_MS = 3_000L;

    /// Backoff after a failed attempt: this multiplied by the number of the attempt that
    /// failed, so 3 s then 6 s.
    static final long BACKOFF_MS = 3_000L;

    /// `attempt < 2` in the JS, counting from zero — three tries in all.
    static final int MAX_ATTEMPTS = 3;

    /// `opts.temperature ?? 0.3`. Nullish, not falsy: an explicit 0 is a real request for a
    /// deterministic answer and must not be replaced by 0.3.
    public static final double DEFAULT_TEMPERATURE = 0.3;

    /// The repair retry drops the temperature — the model has already shown it can reach the
    /// content, and what is wanted the second time is obedience to the format, not invention.
    static final double REPAIR_TEMPERATURE = 0.1;

    /// The JS `body.max_tokens || 4096` fallback. In practice unreachable, because
    /// {@link Budget#ceiling} never returns 0 — kept because the JS kept it.
    static final int FALLBACK_CEILING = 4096;

    /// Defaults for the environment, matching the JS literals.
    static final String DEFAULT_MODEL = "qwen-3.6-27b-fp8";
    static final int DEFAULT_THINKING_BUDGET = 3_000;
    static final int DEFAULT_HARD_MAX = 16_000;

    /// Clip sizes for the dashboard's live dialog: a prompt can carry a whole method and an
    /// answer a whole test file, and the state file is read by a browser.
    static final int SYSTEM_HEAD = 900, SYSTEM_TAIL = 400;
    static final int PROMPT_HEAD = 1500, PROMPT_TAIL = 3000;
    static final int RESPONSE_HEAD = 3000, RESPONSE_TAIL = 1000;

    /// Below head+tail+this, eliding would save less than the notice announcing it.
    static final int ELIDE_SLACK = 40;

    /// How much of a malformed answer is quoted back to the model in the repair turn.
    static final int ASSISTANT_ECHO_CHARS = 4_000;

    /// How much of an error response body reaches the exception message.
    static final int ERROR_BODY_CHARS = 300;

    static final String REPAIR_NUDGE =
            "Your previous answer was not valid JSON. Reply again with ONLY the JSON object,"
            + " no prose, no markdown fences, no placeholders in angle brackets.";

    // ── configuration ─────────────────────────────────────────────────────────────────────

    /// The endpoint, as the environment describes it.
    ///
    /// @param base           `LLM_BASE_URL` with any trailing slash removed; `/v1` is part of it
    /// @param key            `LLM_API_KEY`, sent as a bearer token and never given to a child
    ///                       process (see {@link Exec})
    /// @param model          the model name sent in every body and shown on the dashboard
    /// @param enableThinking `chat_template_kwargs.enable_thinking`
    /// @param thinkingExtra  completion tokens reserved for thinking before any visible output;
    ///                       zero when thinking is off, whatever `LLM_THINKING_BUDGET` says
    /// @param hardMax        the ceiling no stage may exceed
    public record Config(String base, String key, String model, boolean enableThinking,
                         int thinkingExtra, int hardMax) {}

    /// Read the endpoint out of an environment.
    ///
    /// `env` is a parameter rather than `System::getenv` so this is testable, which matters:
    /// every one of these five reads has a falsy-default in the JS and each one of them is a
    /// place a wrong translation would be invisible until a live run.
    static Config envConfig(UnaryOperator<String> env) {
        // `.replace(/\/$/, '')` — no /g, and anchored, so exactly ONE trailing slash goes.
        // `https://host/v1//` becomes `https://host/v1/`, as it did in Node.
        String base = or(env, "LLM_BASE_URL", "").replaceFirst("/$", "");
        String key = or(env, "LLM_API_KEY", "");
        String model = or(env, "LLM_MODEL", DEFAULT_MODEL);
        // `String(x || 'false') === 'true'` — anything but the exact word is off.
        boolean thinking = "true".equals(or(env, "LLM_ENABLE_THINKING", "false"));
        // thinking consumes completion tokens before any visible output — give it headroom
        int extra = thinking ? intOr(env.apply("LLM_THINKING_BUDGET"), DEFAULT_THINKING_BUDGET) : 0;
        int hardMax = intOr(env.apply("LLM_MAX_TOKENS_HARD"), DEFAULT_HARD_MAX);
        return new Config(base, key, model, thinking, extra, hardMax);
    }

    /// `env.X || fallback`: an empty string is as good as unset.
    private static String or(UnaryOperator<String> env, String name, String fallback) {
        String v = env.apply(name);
        return (v == null || v.isEmpty()) ? fallback : v;
    }

    /// `parseInt(v || 'default', 10)`, with one deliberate difference: a value that parses to
    /// nothing takes the default rather than NaN.
    ///
    /// In JS, `LLM_MAX_TOKENS_HARD=lots` gives NaN, and NaN survives `opts.hardMax ?? 16000`
    /// because NaN is neither null nor undefined. Every `Math.min(hardMax, …)` downstream then
    /// answers NaN, `max_tokens` serialises as `null`, and the endpoint decides the ceiling for
    /// us. A typo in a container's environment is not worth that.
    private static int intOr(String raw, int fallback) {
        Integer parsed = State.jsParseInt(raw == null || raw.isEmpty() ? null : raw);
        return parsed == null ? fallback : parsed;
    }

    // ── the call ──────────────────────────────────────────────────────────────────────────

    /// One chat message. `content` is nullable only because a caller-supplied conversation can
    /// contain anything; everything this module builds itself fills it in.
    public record Message(String role, String content) {}

    /// What a caller asks for.
    ///
    /// @param system      the system turn, used only when `messages` is empty
    /// @param prompt      the user turn, used only when `messages` is empty
    /// @param messages    a whole conversation, which wins over system/prompt when non-empty
    /// @param maxTokens   what the caller thinks the ANSWER needs; null means "no view", and
    ///                    {@link Budget} supplies the default. Boxed, because a stated 0 and an
    ///                    unstated budget must not be the same value.
    /// @param temperature null means {@link #DEFAULT_TEMPERATURE}; an explicit 0 stays 0
    /// @param json        parse the answer, and buy a second attempt when it does not parse
    /// @param stage       which pipeline stage is asking — the key {@link Budget} learns per
    /// @param stageDetail free text shown beside the exchange in the dashboard's dialog
    public record Options(String system, String prompt, List<Message> messages,
                          Integer maxTokens, Double temperature, boolean json,
                          String stage, String stageDetail) {

        public static Options of() {
            return new Options(null, null, null, null, null, false, null, null);
        }

        public Options withSystem(String v) {
            return new Options(v, prompt, messages, maxTokens, temperature, json, stage, stageDetail);
        }

        public Options withPrompt(String v) {
            return new Options(system, v, messages, maxTokens, temperature, json, stage, stageDetail);
        }

        public Options withMessages(List<Message> v) {
            return new Options(system, prompt, v, maxTokens, temperature, json, stage, stageDetail);
        }

        public Options withMaxTokens(Integer v) {
            return new Options(system, prompt, messages, v, temperature, json, stage, stageDetail);
        }

        public Options withTemperature(Double v) {
            return new Options(system, prompt, messages, maxTokens, v, json, stage, stageDetail);
        }

        public Options withJson(boolean v) {
            return new Options(system, prompt, messages, maxTokens, temperature, v, stage, stageDetail);
        }

        public Options withStage(String v) {
            return new Options(system, prompt, messages, maxTokens, temperature, json, v, stageDetail);
        }

        public Options withStageDetail(String v) {
            return new Options(system, prompt, messages, maxTokens, temperature, json, stage, v);
        }

        /// `opts.stage || 'default'` — the budget key. An empty stage is not a stage.
        public String stageKey() { return (stage == null || stage.isEmpty()) ? "default" : stage; }

        /// `stage: opts.stage || null` as it is written into the exchange log.
        public String stageOrNull() { return (stage == null || stage.isEmpty()) ? null : stage; }
    }

    /// What a caller gets back.
    ///
    /// @param text the answer, ALWAYS the first one — see {@link #chat}, which returns the
    ///             retry's text only when the retry is the one that parsed
    /// @param json the parsed object, or null: absent when `json` was not asked for, and null
    ///             when it was asked for and no attempt produced valid JSON
    public record Answer(String text, JsonNode json) {}

    /// The request body, in the order the JS object literal wrote its keys.
    record Body(String model, List<Message> messages, int maxTokens, double temperature,
                boolean enableThinking) {

        Body withMaxTokens(int v) { return new Body(model, messages, v, temperature, enableThinking); }

        Body withMessages(List<Message> v) { return new Body(model, v, maxTokens, temperature, enableThinking); }

        Body withTemperature(double v) { return new Body(model, messages, maxTokens, v, enableThinking); }
    }

    /// One completion, as the endpoint reported it.
    ///
    /// The JS kept these in two module-level variables (`lastFinishReason`,
    /// `lastCompletionTokens`) that `post` wrote and `chat` read afterwards. That is safe on
    /// Node's single thread and is not safe here — this backend answers on virtual threads, and
    /// two concurrent chats would read each other's finish reason and escalate the wrong
    /// stage's budget. Threading them back as a return value is the same sequence of reads for
    /// one caller and is correct for several.
    ///
    /// @param content          `choices[0].message.content`, or "" — never null, as in the JS
    /// @param finish           `choices[0].finish_reason`, or null. `length` means the answer
    ///                         was cut off, which needs a bigger budget rather than a scolding
    ///                         about JSON formatting
    /// @param completionTokens `usage.completion_tokens`, or 0. Not boxed: the JS coerced an
    ///                         absent value to 0 here (`|| 0`) before anything read it
    /// @param promptTokens     `usage.prompt_tokens`, or 0, on the same reasoning
    public record Completion(String content, String finish, int completionTokens, long promptTokens) {}

    /// One HTTP round trip.
    public record Response(int status, String body) {}

    /// The endpoint answered, and its answer was a refusal.
    ///
    /// A distinct type rather than a message, because the retry rule has to tell it apart from
    /// an outage. The JS could not: it decided by matching the error's TEXT against
    /// `/abort|network|fetch failed|ECONN/i`, and that text ends with 300 characters of the
    /// server's own body — so an `LLM HTTP 400` whose body happened to mention a network was
    /// re-sent twice more to a server that had already rejected it on its merits.
    public static class HttpStatusException extends IOException {

        private final int status;

        public HttpStatusException(int status, String body) {
            super("LLM HTTP " + status + ": " + body);
            this.status = status;
        }

        public int status() { return status; }
    }

    /// The endpoint answered, in time and with a 200, and its answer is not a chat completion.
    ///
    /// The JS reached this as `await res.json()` throwing on an empty or non-JSON body, which
    /// its retry regex did not match — so it propagated and the route reported it. Jackson is
    /// more forgiving than `JSON.parse`: `readTree("")` answers a MissingNode rather than
    /// throwing, and without this check an empty 200 would read as a completion of "" and be
    /// sent round the repair loop for another five minutes.
    public static class MalformedAnswerException extends IOException {
        public MalformedAnswerException(String message) { super(message); }
    }

    // ── the outside world ─────────────────────────────────────────────────────────────────

    /// Everything this module does that is not a decision.
    ///
    /// Maps onto the Node sidecar one-for-one: `post` → `fetch`, `sleep` → the backoff
    /// `setTimeout`, and the last six onto state.js, which llm.js imported directly.
    public interface Io {

        /// POST a JSON body and read the whole response. Throws for anything that stops the
        /// exchange; a 4xx or 5xx is a {@link Response}, not an exception.
        Response post(String url, String jsonBody) throws IOException, InterruptedException;

        /// The backoff between attempts.
        void sleep(long millis) throws InterruptedException;

        /// The live line under the current stage.
        void setProgress(String line, long elapsedSec);

        /// Attribute a completion's usage to the run and to the unit being improved.
        void addTokens(long in, long out);

        /// Record one exchange for the dashboard's live dialog.
        void addLlmExchange(Map<String, Object> entry);

        /// Append to the event log.
        void event(String stage, String msg);

        /// `state.llmBudget = budget.toJSON()` — the learned ceilings, persisted with the run.
        void budgetSaved(Budget.Saved saved);

        /// `save()` — ask for a state flush.
        void save();

        /// The wall clock, in milliseconds. A seam because the two durations this module
        /// reports (how long a call took, how long it has been waiting) are the only things a
        /// test can hold it to without actually waiting.
        long nowMillis();
    }

    private final Config config;
    private final Budget budget;
    private final Io io;

    public Llm(Config config, Budget budget, Io io) {
        this.config = config;
        this.budget = budget;
        this.io = io;
    }

    public Config config() { return config; }

    public Budget budget() { return budget; }

    /// The process-wide client, built on FIRST USE.
    ///
    /// Deliberately lazy. Per-stage ceilings are learned from what actually happened and
    /// persisted with the run, so that a stage which once ran out of room starts wider next
    /// time instead of paying for the same truncated call again — and the seed for that comes
    /// from `state.llmBudget`, which only exists once {@link State#load()} has read the file.
    /// In the Node sidecar `require('./llm')` sat at the top of server.js and `S.load()` at the
    /// bottom, so the module-level Budget was seeded from an empty object and every restart
    /// walked into the same wall the previous process had already paid for. Building on demand
    /// puts the read after the load without anything having to remember to.
    public static Llm shared() { return Holder.INSTANCE; }

    private static final class Holder {
        static final Llm INSTANCE = forServer();
    }

    /// The real client: the environment's endpoint, the run's learned budget, real IO.
    public static Llm forServer() {
        Config cfg = envConfig(System::getenv);
        Budget b = new Budget(new Budget.Options(cfg.thinkingExtra(), cfg.hardMax()),
                              savedBudget(State.STATE.llmBudget));
        return new Llm(cfg, b, new StateIo(cfg.key()));
    }

    /// `state.llmBudget || null`, read back into the shape {@link Budget} wants.
    ///
    /// What the state file holds is whatever an earlier version of this backend wrote, and a
    /// shape it no longer understands must cost the run its learned ceilings and nothing else.
    static Budget.Saved savedBudget(Object stored) {
        if (stored == null) return null;
        try {
            return MAPPER.convertValue(stored, Budget.Saved.class);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ── chat ──────────────────────────────────────────────────────────────────────────────

    /// Ask the model.
    ///
    /// With `json` set, the answer is parsed and a failure to parse buys exactly one more
    /// attempt — of one of two quite different kinds, see below.
    public Answer chat(Options opts) throws IOException, InterruptedException {
        List<Message> messages = messagesFor(opts);
        String stage = opts.stageKey();
        Body body = new Body(config.model(), messages,
                budget.ceiling(stage, opts.maxTokens()),
                opts.temperature() == null ? DEFAULT_TEMPERATURE : opts.temperature(),
                config.enableThinking());

        long started = io.nowMillis();
        Completion first = post(body);
        budget.record(stage, new Budget.Completion(first.finish(), body.maxTokens(), first.completionTokens()));
        io.budgetSaved(budget.snapshot());

        String text = first.content();
        // The exchange itself, for the dashboard's live dialog — truncated, since a prompt can
        // carry a whole method and an answer a whole test file.
        //
        // Every attempt reaches the live dialog, not just the first. A truncated answer that
        // was then retried used to be shown as THE answer for that round — an empty response
        // and a duration a third of what the round really spent in the model.
        logExchange(opts, messages, started, text, null, first.finish());
        if (!opts.json()) return new Answer(text, null);

        JsonNode parsed = Util.extractJson(text);
        if (parsed == null) {
            // Diagnose before retrying. A truncated answer (finish_reason=length) is not a
            // formatting mistake — the model ran out of room mid-JSON, and asking it to "reply
            // with only the JSON" reruns the same wall for another minute or two. Give it room
            // instead; only a genuinely malformed answer gets the formatting nudge.
            int ceiling = body.maxTokens() == 0 ? FALLBACK_CEILING : body.maxTokens();
            String finish = first.finish();
            boolean canGrow = budget.grew(stage, ceiling);

            if ("length".equals(finish) && canGrow) {
                int bigger = budget.escalate(stage, ceiling);
                io.budgetSaved(budget.snapshot());
                io.save();
                io.event("llm", "response hit the token limit mid-JSON — retrying with " + bigger
                        + " tokens instead of " + body.maxTokens());
                Completion again = post(body.withMaxTokens(bigger));
                logExchange(opts, messages, started, again.content(),
                        "retry with " + bigger + " tokens", again.finish());
                parsed = Util.extractJson(again.content());
                if (parsed != null) return new Answer(again.content(), parsed);
                // Falling through, the JS re-read its module-level lastFinishReason — which the
                // retry above has just overwritten. The `canGrow` below, though, is still
                // computed from the ORIGINAL ceiling, so a stage that could grow once cannot
                // reach the "at the maximum budget" branch on this pass however the retry ended.
                // It goes to the repair nudge instead, at the original, smaller ceiling.
                finish = again.finish();
            }

            if ("length".equals(finish) && !canGrow) {
                // at the hard ceiling a retry is byte-identical: same request, same truncation,
                // another two minutes. Say so instead of spending them.
                io.event("llm", "response truncated at the maximum budget (" + body.maxTokens()
                        + " tokens) — not retrying an identical request");
                return new Answer(text, null);
            }

            io.event("llm", "JSON parse failed, retrying with repair nudge");
            List<Message> repair = new ArrayList<>(messages);
            repair.add(new Message("assistant", head(text, ASSISTANT_ECHO_CHARS)));
            repair.add(new Message("user", REPAIR_NUDGE));
            Completion again = post(body.withMessages(repair).withTemperature(REPAIR_TEMPERATURE));
            logExchange(opts, repair, started, again.content(), "retry after invalid JSON", again.finish());
            parsed = Util.extractJson(again.content());
        }
        // `text` is the FIRST answer even when the repair above is what parsed. Deliberate, and
        // the JS did the same: `json` is what the caller acts on, and `text` is the answer the
        // caller's own prompt actually produced.
        return new Answer(text, parsed);
    }

    // ── the decisions, kept out of the IO ─────────────────────────────────────────────────

    /// The conversation to send: the caller's own if it gave one, otherwise system + user.
    ///
    /// A caller-supplied conversation is COPIED — the JS `.slice()`. The repair turn appends to
    /// this list, and appending to the caller's own would leave a nudge in the history of
    /// whatever it does next.
    static List<Message> messagesFor(Options opts) {
        List<Message> messages = new ArrayList<>();
        if (opts.messages() != null && !opts.messages().isEmpty()) {
            messages.addAll(opts.messages());
            return messages;
        }
        // `if (opts.system)` — an empty system prompt adds no turn at all rather than an empty
        // one, which some templates render as a stray header.
        if (opts.system() != null && !opts.system().isEmpty()) {
            messages.add(new Message("system", opts.system()));
        }
        messages.add(new Message("user", opts.prompt() == null ? "" : opts.prompt()));
        return messages;
    }

    /// The request body as JSON, keys in the order the JS wrote them.
    static ObjectNode bodyJson(Body body) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", body.model());
        ArrayNode msgs = root.putArray("messages");
        for (Message m : body.messages()) {
            ObjectNode n = msgs.addObject();
            n.put("role", m.role());
            n.put("content", m.content());
        }
        root.put("max_tokens", body.maxTokens());
        root.put("temperature", body.temperature());
        root.putObject("chat_template_kwargs").put("enable_thinking", body.enableThinking());
        return root;
    }

    /// What the dashboard calls this endpoint.
    static String progressLabel(Config config) {
        return config.model() + (config.enableThinking() ? " (thinking)" : "");
    }

    /// The heartbeat line while the model is thinking.
    ///
    /// Child processes (Maven, PIT) feed the dashboard's live status line through the exec
    /// wrapper, but an LLM call is just a socket we are waiting on — so the line went blank for
    /// minutes at a time, during the phase the pipeline spends most of its time in, and the
    /// dashboard looked dead while it worked.
    static String waitingLine(String label, long secs, int attempt) {
        // `attempt ? ... : ''` — the first attempt is not "retry 0".
        return label + ": waiting for completion — " + secs + "s"
                + (attempt != 0 ? " (retry " + attempt + ")" : "");
    }

    /// The line left standing once a completion has arrived.
    ///
    /// The finish reason is appended only when it is worth reading: `stop` is the normal end
    /// and naming it on every call would bury the one time it says `length`.
    static String usageLine(String label, long in, long out, long secs, String finish) {
        return label + ": " + in + " in / " + out + " out tokens in " + secs + "s"
                + (finish != null && !finish.isEmpty() && !"stop".equals(finish) ? " [" + finish + "]" : "");
    }

    /// The `detail` field of a logged exchange.
    ///
    /// `(stageDetail || null) && note ? `${stageDetail} — ${note}` : (note || stageDetail || null)`.
    /// Both parts can be absent and the three surviving combinations all read differently, which
    /// is the whole reason this is a function: a Java `stageDetail + " — " + note` would print
    /// "null — retry after invalid JSON" on the exchange a reader most wants to understand.
    static String exchangeDetail(String stageDetail, String note) {
        boolean hasDetail = stageDetail != null && !stageDetail.isEmpty();
        boolean hasNote = note != null && !note.isEmpty();
        if (hasDetail && hasNote) return stageDetail + " — " + note;
        if (hasNote) return note;
        return hasDetail ? stageDetail : null;
    }

    /// What the exchange log records as the prompt.
    ///
    /// `opts.prompt || (last message).content || ''` — a caller that passed a whole conversation
    /// has no `prompt`, and the last turn is the one it wants to see.
    static String promptFor(Options opts, List<Message> messages) {
        if (opts.prompt() != null && !opts.prompt().isEmpty()) return opts.prompt();
        if (messages == null || messages.isEmpty()) return "";
        String content = messages.get(messages.size() - 1).content();
        return content == null ? "" : content;
    }

    /// Keep the beginning and the end; elide the middle, saying how much was dropped.
    static String clip(String text, int head, int tail) {
        String s = text == null ? "" : text;
        if (s.length() <= head + tail + ELIDE_SLACK) return s;
        return s.substring(0, head)
                + "\n\n… [" + (s.length() - head - tail) + " characters elided] …\n\n"
                + s.substring(s.length() - tail);
    }

    /// JS `String.prototype.slice(0, n)`, which clamps where `substring` would throw.
    static String head(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n);
    }

    /// `Math.round((now - started) / 1000)`.
    ///
    /// The division is in DOUBLES on purpose. `(nowMillis - startMillis) / 1000` between two
    /// Java longs truncates, so a 47.6-second call would be reported as 47 where the JS said 48
    /// — and these numbers are added up into what a unit's improvement is said to have cost.
    static long secondsBetween(long startMillis, long nowMillis) {
        return Math.round((nowMillis - startMillis) / 1000.0);
    }

    /// Is this status worth sending the same request again?
    ///
    /// 429 is the endpoint asking us to slow down and 5xx is it being unwell; both pass. A 4xx
    /// is an answer about the request itself, and re-sending it is three times the wait for the
    /// same refusal.
    static boolean retryableStatus(int status) {
        return status == 429 || status >= 500;
    }

    /// Is this failure worth sending the same request again?
    ///
    /// The JS matched the message text — `/abort|network|fetch failed|ECONN/i` — which in
    /// undici covers the AbortError from the 300 s deadline and the single "fetch failed" that
    /// every transport error is wrapped in. Java throws distinct types for those, so the type is
    /// what is matched here, and matching the type also closes the hole the text match left
    /// open: see {@link HttpStatusException}.
    ///
    /// An answer we could not parse is not retried either. The endpoint replied, in time, with
    /// something that is not a chat completion; asking again produces the same thing.
    static boolean retryableFailure(Throwable e) {
        if (e instanceof HttpStatusException) return false;
        if (e instanceof MalformedAnswerException) return false;
        if (e instanceof JsonProcessingException) return false;
        return e instanceof IOException;
    }

    /// Read a completion out of the endpoint's response.
    ///
    /// Every field has a fallback because every field is optional in practice: a vLLM build
    /// that streams `finish_reason` late, a proxy that drops `usage`, an error shape that came
    /// back with a 200. `finish_reason: ""` becomes null, exactly as `|| null` did — an empty
    /// reason is not a reason, and `"".equals("length")` is false either way but the value is
    /// written into the exchange log where a reader sees it.
    static Completion readCompletion(JsonNode data) {
        JsonNode choice = path(path(data, "choices"), 0);
        String finish = text(path(choice, "finish_reason"));
        JsonNode usage = path(data, "usage");
        return new Completion(
                orEmpty(text(path(path(choice, "message"), "content"))),
                (finish == null || finish.isEmpty()) ? null : finish,
                (int) number(path(usage, "completion_tokens")),
                number(path(usage, "prompt_tokens")));
    }

    // ── the IO ────────────────────────────────────────────────────────────────────────────

    /// One completion, with the retries the JS gave it: three attempts, 3 s then 6 s apart.
    private Completion post(Body body) throws IOException, InterruptedException {
        String url = config.base() + CHAT_PATH;
        String payload = MAPPER.writeValueAsString(bodyJson(body));
        String label = progressLabel(config);

        for (int attempt = 0; ; attempt++) {
            // The backoff the previous attempt earned. `3000 * (attempt + 1)` in the JS, where
            // `attempt` was the number of the one that had just failed.
            if (attempt > 0) io.sleep(BACKOFF_MS * attempt);

            long started = io.nowMillis();
            Thread beat = startHeartbeat(label, attempt, started);
            io.setProgress(label + ": request sent", 0);
            try {
                Response res = io.post(url, payload);
                if (res.status() < 200 || res.status() >= 300) {
                    if (attempt < MAX_ATTEMPTS - 1 && retryableStatus(res.status())) continue;
                    throw new HttpStatusException(res.status(), head(res.body(), ERROR_BODY_CHARS));
                }
                return complete(res.body(), label, started);
            } catch (IOException | RuntimeException e) {
                if (attempt < MAX_ATTEMPTS - 1 && retryableFailure(e)) continue;
                throw e;
            } finally {
                // Before the backoff, not after the whole recursion. The JS cleared its
                // interval in a `finally` that wrapped the recursive call too, so a retrying
                // request had two heartbeats writing the same line with different retry
                // numbers. Nothing broke; the line just flickered.
                beat.interrupt();
            }
        }
    }

    /// Account for a completion and report it, then hand it back.
    private Completion complete(String responseBody, String label, long started) throws IOException {
        JsonNode data = MAPPER.readTree(responseBody);
        if (data == null || !data.isObject()) {
            throw new MalformedAnswerException(
                    "LLM answered 200 with something that is not a completion: "
                    + head(responseBody, ERROR_BODY_CHARS));
        }
        Completion c = readCompletion(data);
        // token accounting: every call's prompt/completion usage is attributed to the unit
        // currently being worked on, so the dashboard can report what the improvement cost
        io.addTokens(c.promptTokens(), c.completionTokens());
        long secs = secondsBetween(started, io.nowMillis());
        io.setProgress(usageLine(label, c.promptTokens(), c.completionTokens(), secs, c.finish()), secs);
        return c;
    }

    /// Say that the model is still thinking, every three seconds, until interrupted.
    private Thread startHeartbeat(String label, int attempt, long started) {
        return Thread.ofVirtual().start(() -> {
            try {
                while (true) {
                    Thread.sleep(HEARTBEAT_MS);
                    long s = secondsBetween(started, io.nowMillis());
                    try {
                        io.setProgress(waitingLine(label, s, attempt), s);
                    } catch (RuntimeException e) {
                        // A progress line is a courtesy to the dashboard. Losing a five-minute
                        // completion because the state file could not be written is not a trade
                        // worth making.
                    }
                }
            } catch (InterruptedException e) {
                // the call is done; the caller writes the closing line itself
            }
        });
    }

    /// Record one exchange for the dashboard's live dialog.
    private void logExchange(Options opts, List<Message> messages, long started,
                             String response, String note, String finish) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("stage", opts.stageOrNull());
        entry.put("detail", exchangeDetail(opts.stageDetail(), note));
        entry.put("system", clip(opts.system() == null ? "" : opts.system(), SYSTEM_HEAD, SYSTEM_TAIL));
        entry.put("prompt", clip(promptFor(opts, messages), PROMPT_HEAD, PROMPT_TAIL));
        entry.put("response", clip(response == null ? "" : response, RESPONSE_HEAD, RESPONSE_TAIL));
        // Seconds since CHAT began, not since this attempt did — a round that truncated once and
        // was retried spent all of it, and reporting only the successful call was how a round
        // that took four minutes showed up as ninety seconds.
        entry.put("secs", secondsBetween(started, io.nowMillis()));
        entry.put("finish", finish);
        io.addLlmExchange(entry);
    }

    // ── the real world ────────────────────────────────────────────────────────────────────

    /// {@link Io} against the model endpoint and the state store.
    public static final class StateIo implements Io {

        /// One client for the process. Each `HttpClient` carries its own selector and executor
        /// threads, and building one per request leaks both.
        private static final HttpClient CLIENT = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        private final String key;

        public StateIo(String key) { this.key = key == null ? "" : key; }

        @Override
        public Response post(String url, String jsonBody) throws IOException, InterruptedException {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + key)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();
            // NOT HttpRequest.timeout(): that clock stops when the response HEADERS arrive, and
            // a model streaming a 16000-token answer sends its headers in the first second. The
            // AbortController this replaces cancelled the whole exchange, body included, so the
            // deadline is applied to the future that completes when the body is fully read.
            CompletableFuture<HttpResponse<String>> pending =
                    CLIENT.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            try {
                HttpResponse<String> res = pending.get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                return new Response(res.statusCode(), res.body());
            } catch (TimeoutException e) {
                // Cancel, or the connection stays open receiving a completion nobody will read —
                // and this deadline is retried, so the next attempt would queue behind it.
                pending.cancel(true);
                throw new HttpTimeoutException("no completion after " + REQUEST_TIMEOUT_MS + " ms");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException io) throw io;
                if (cause instanceof RuntimeException re) throw re;
                throw new IOException(cause);
            }
        }

        @Override public void sleep(long millis) throws InterruptedException { Thread.sleep(millis); }

        @Override public void setProgress(String line, long elapsedSec) { State.setProgress(line, elapsedSec); }

        @Override public void addTokens(long in, long out) { State.addTokens(in, out); }

        @Override public void addLlmExchange(Map<String, Object> entry) { State.addLlmExchange(entry); }

        @Override public void event(String stage, String msg) { State.event(stage, msg); }

        @Override
        public void budgetSaved(Budget.Saved saved) {
            synchronized (State.STATE) { State.STATE.llmBudget = saved; }
        }

        @Override public void save() { State.save(); }

        @Override public long nowMillis() { return System.currentTimeMillis(); }
    }

    // ── JSON reading ──────────────────────────────────────────────────────────────────────
    // `a?.b?.[0]?.c` in four lines: every step answers null rather than throwing, and null
    // propagates. Jackson's own path() answers MissingNode, which is not null and would make
    // every caller below test for two absences instead of one.

    private static JsonNode path(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v;
    }

    private static JsonNode path(JsonNode node, int index) {
        if (node == null || !node.isArray()) return null;
        JsonNode v = node.get(index);
        return (v == null || v.isNull()) ? null : v;
    }

    /// A JSON string, or null for anything that is not one. A number where a string was
    /// expected is a broken endpoint, not a finish reason.
    private static String text(JsonNode node) {
        return (node != null && node.isTextual()) ? node.asText() : null;
    }

    /// `x || 0` over a JSON number.
    private static long number(JsonNode node) {
        return (node != null && node.isNumber()) ? node.longValue() : 0L;
    }

    private static String orEmpty(String s) { return s == null ? "" : s; }
}
