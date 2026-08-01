package tech.mikhailov.ijt.orchestrator.ws;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tech.mikhailov.ijt.Feedback;
import tech.mikhailov.ijt.Repo;
import tech.mikhailov.ijt.State;
import tech.mikhailov.ijt.Util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The dashboard, in a real browser.
///
/// Every defect this page has had was invisible to the rest of the suite, because nothing drove
/// the page:
///
///   - GET/POST /api/feedback answered 404 from outside the process. The route-inventory test
///     passed throughout: it asserts the backend's map CONTAINS the key, which is a different
///     claim from anything serving it.
///   - Four standing rules shaped every prompt and the word "guidance" appeared nowhere in the
///     dashboard. Written, stored, never displayed.
///   - The 💬 dialog asked for a judgement on a generated test without showing the test.
///   - The apply checkbox rendered full-width and centred above its own label, because
///     `dialog#fb input` is an ID selector and beat the class that was meant to shrink it.
///
/// Not one of those is reachable from a unit test of a Java method, and the first three were
/// found by a person opening the page. This is the layer that was missing.
///
/// ## Why this is opt-in
///
/// Playwright downloads a browser (~150 MB) on first use. `mvn test` must stay runnable on a
/// laptop and in the image build without that, so these run only with `-Dui.tests=true`. That is
/// a real limitation, not a preference: a suite nobody can run is worse than one that is
/// explicitly gated. Run them with:
///
///     mvn -pl orchestrator -Dui.tests=true -Dtest=DashboardUiTest test
///
/// playwright-JAVA rather than the Node package, because this repo has no npm and no Node
/// runtime; reintroducing one for tests would undo that removal.
/// ## The bean-override property is a WORKAROUND, not a fix
///
/// Without it the context fails with `BeanDefinitionOverrideException: eventRepository`, because
/// Boot's JPA repository registrar runs twice in this test JVM — `OrchestratorApplication` is a
/// plain `@SpringBootApplication` with no `@EnableJpaRepositories` of its own, and production
/// starts cleanly, so the second registration comes from the test classpath rather than from the
/// application. I have not tracked down which starter contributes it. Allowing the override lets
/// the real subject of these tests — the page — be tested now, and the duplicate registration is
/// filed rather than silently accepted.
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.main.allow-bean-definition-overriding=true",
                // IJT_DB_URL, because the default is jdbc:h2:file:${DATA_DIR:/data}/ijt and a
                // developer machine has no writable /data. These tests are about the page, not
                // about persistence surviving a restart, so memory is the honest choice.
                "IJT_DB_URL=jdbc:h2:mem:uitest;DB_CLOSE_DELAY=-1",
                // DASHBOARD_DIR first, so that INSIDE THE CONTAINER this resolves exactly as
                // production does — /app/dashboard, the setting whose default was serving 404s
                // to the entire page. The working-directory fallback is for a laptop, where
                // there is no /app; without either the index handler 404s and the page never
                // loads at all, which is itself worth knowing.
                "ijt.dashboard-dir=${DASHBOARD_DIR:${user.dir}/../dashboard}"
        })
@EnabledIfSystemProperty(named = "ui.tests", matches = "true")
class DashboardUiTest {

    private static Playwright playwright;
    private static Browser browser;

    @LocalServerPort
    private int port;

    private static final String REPO_URL = "https://example.invalid/o/r";

    @BeforeAll
    static void launch() {
        playwright = Playwright.create();
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setHeadless(true);
        // Chromium's sandbox needs user namespaces, which an unprivileged container does not
        // have, so in the image the run needs --no-sandbox. It is passed as an ENV rather than
        // hardcoded because the alternative — dropping the sandbox everywhere — would also drop
        // it on a laptop, where the browser is a real one and the protection is worth keeping.
        String extra = System.getenv("IJT_UI_CHROMIUM_ARGS");
        if (extra != null && !extra.isBlank()) options.setArgs(List.of(extra.trim().split("\\s+")));
        browser = playwright.chromium().launch(options);
    }

    @AfterAll
    static void close() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    /// A run with one improved unit, one generated test, and something already said about it —
    /// the state a person is actually looking at when they click 💬.
    @BeforeEach
    void seedARun() throws Exception {
        Path data = Files.createTempDirectory("ijt-ui-");
        State.DATA_DIR = data;
        State.STATE.run = State.freshRun(State.envConfig(k -> null), Map.of("repoUrl", REPO_URL));
        State.STATE.files = new LinkedHashMap<>();

        Map<String, Object> f = new LinkedHashMap<>();
        f.put("status", "improved");
        f.put("key", "src/main/java/org/json/XML.java::escape");
        f.put("path", "src/main/java/org/json/XML.java");
        f.put("method", "escape");
        f.put("coverage", 100.0);
        f.put("mutation", 80.0);
        f.put("mac", 80.0);
        State.STATE.files.put("src/main/java/org/json/XML.java::escape", f);

        Path repo = Repo.repoDir();
        Files.createDirectories(repo.resolve(".git").resolve("info"));
        Feedback.attempt(repo, REPO_URL, "run-1|u|a1|r1", "src/main/java/org/json/XML.java::escape",
                Map.of("methodSource", "public static String escape(String s) { return s; }"),
                List.of(Map.of("path", "src/test/java/org/json/XMLEscapeMacMutTest.java",
                        "content", "class XMLEscapeMacMutTest { @Test void escapesAmp() { } }")),
                List.of("no mocks"));
        Feedback.outcome(repo, REPO_URL, "run-1|u|a1|r1", "src/main/java/org/json/XML.java::escape",
                "kept", Map.of("mac", 40.0), Map.of("mac", 80.0), false);
        Feedback.feedback(repo, REPO_URL, null, "i do not like too many mocks in these tests", true, -1);
        Feedback.feedback(repo, REPO_URL, "src/main/java/org/json/XML.java::escape",
                "this one asserts nothing useful", false, -1);
    }

    private Page open() {
        Page page = browser.newPage();
        page.navigate("http://localhost:" + port + "/dashboard/");
        page.waitForFunction("() => document.querySelectorAll('#files tbody tr').length > 0");
        return page;
    }

    // ── the guidance a person needs to see ────────────────────────────────

    @Test
    void theStandingGuidanceIsVisibleOnThePage() {
        // it shapes every prompt the pipeline sends; not rendering it made the whole feature
        // write-only
        try (Page page = open()) {
            page.waitForSelector("#guidance li");
            assertTrue(page.locator("#guidance-section").isVisible(), "the panel is hidden");
            assertTrue(page.locator("#guidance").innerText().contains("too many mocks"),
                    "got: " + page.locator("#guidance").innerText());
        }
    }

    @Test
    void thePanelHidesItselfWhenNothingHasBeenSaid() throws Exception {
        // an empty box with a heading reads as broken rather than unused
        Files.deleteIfExists(Feedback.fileIn(Repo.repoDir()));
        try (Page page = open()) {
            assertTrue(page.locator("#guidance-section").isHidden());
        }
    }

    // ── the thing being judged ────────────────────────────────────────────

    @Test
    void clickingSayShowsTheMethodAndTheGeneratedTest() {
        // asking for a judgement on a test without showing the test is asking someone to
        // remember it
        try (Page page = open()) {
            page.locator("button.say").first().click();
            page.waitForSelector("#fb[open]");

            String record = page.locator("#fb-record").innerText();
            assertTrue(record.contains("escape"), "the method under test is missing: " + record);
            assertTrue(record.contains("XMLEscapeMacMutTest"), "the generated test is missing: " + record);
            assertTrue(record.contains("kept"), "the verdict is missing: " + record);
            assertTrue(record.contains("40") && record.contains("80"), "MAC before/after missing: " + record);
        }
    }

    @Test
    void theDialogShowsWhatHasAlreadyBeenSaidAboutThatUnit() {
        try (Page page = open()) {
            page.locator("button.say").first().click();
            page.waitForSelector("#fb[open]");
            assertTrue(page.locator("#fb-existing").innerText().contains("asserts nothing useful"));
        }
    }

    /// One comment, said once, listed once.
    ///
    /// A unit-targeted comment attaches to EVERY attempt for that unit — that is the join's
    /// contract, so the pipeline sees it whichever record it reads. The dashboard was rendering
    /// the joined list verbatim, so a unit with two attempts showed the same sentence twice: it
    /// reads as two people independently complaining, which is exactly the signal a person uses
    /// to decide a critique is worth acting on.
    ///
    /// Found by being asked to prove a comment was really stored. It was, twice over.
    @Test
    void aCommentIsListedOnceHoweverManyAttemptsTheUnitHas() throws Exception {
        Feedback.attempt(Repo.repoDir(), REPO_URL, "run-1|u|a1|r2", "src/main/java/org/json/XML.java::escape",
                Map.of("methodSource", "public static String escape(String s) { return s; }"),
                List.of(Map.of("path", "src/test/java/org/json/XMLEscapeMacMutTest.java",
                        "content", "class XMLEscapeMacMutTest { }")),
                List.of("no mocks"));

        try (Page page = open()) {
            page.locator("button.say").first().click();
            page.waitForSelector("#fb[open]");

            Locator said = page.locator("#fb-existing div");
            assertEquals(1, said.count(),
                    "the same comment is listed " + said.count() + " times: "
                            + page.locator("#fb-existing").innerText());
        }
    }

    /// The name a person types is the name that comes back.
    ///
    /// The route used to put the author on the RESPONSE map after the line had already been
    /// appended, so the caller was handed back their own name while the stored line had no
    /// author at all. Every comment in the corpus rendered as "someone" — including several I
    /// had signed. This drives the whole path: type a name, save, reopen, read it back.
    @Test
    void theCommentComesBackAttributedToWhoeverSignedIt() {
        try (Page page = open()) {
            page.locator("button.say").first().click();
            page.waitForSelector("#fb[open]");
            page.locator("#fb-text").fill("asserts on the exception message rather than its type");
            page.locator("#fb-author").fill("Claude");
            page.locator("#fb-save").click();
            page.waitForFunction("() => document.getElementById('fb-status').textContent.length > 0");

            // The form is method="dialog": saving closes it, and the refresh it kicks off is a
            // separate fetch. The history is rendered ONLY when the dialog opens, so reopening
            // before that fetch lands shows the stale list and never updates — wait for the
            // comment to be back from the API first, then reopen.
            page.waitForFunction(
                    "() => (FB_BY_TARGET['src/main/java/org/json/XML.java::escape'] || [])"
                            // each entry is {fb, rec}: the comment WITH the attempt it was about
                            + ".some(x => x.fb.text.includes('exception message'))");
            page.locator("button.say").first().click();
            page.waitForSelector("#fb[open]");

            String history = page.locator("#fb-existing").innerText();
            assertTrue(history.contains("Claude"), "not attributed: " + history);
            assertTrue(history.contains("exception message"), history);
        }
    }

    /// A comment is shown with the test it was about, not the one on screen.
    ///
    /// The record pane shows the LATEST attempt for the unit. A comment from an earlier round was
    /// written about a test that has since been rewritten line for line, and the two rendered
    /// together with nothing between them read as a judgement on the code above — the one thing
    /// the comment is not. Raised by the user: "your ui shows comments without code/unit, but
    /// current code/unit could change and have nothing to do with comment".
    @Test
    void aCommentAboutAnEarlierTestSaysSoAndShowsThatTest() throws Exception {
        Path repo = Repo.repoDir();
        // the seeded comment in @BeforeEach is anchored to the seeded attempt; this rewrite
        // arrives afterwards and becomes what the record pane displays
        Feedback.attempt(repo, REPO_URL, "run-1|u|a1|r9", "src/main/java/org/json/XML.java::escape",
                Map.of("methodSource", "public static String escape(String s) { return s; }"),
                List.of(Map.of("path", "src/test/java/org/json/XMLEscapeMacMutTest.java",
                        "content", "class XMLEscapeMacMutTest { @Test void rewritten() { } }")),
                List.of("no mocks"));

        try (Page page = open()) {
            page.locator("button.say").first().click();
            page.waitForSelector("#fb[open]");

            String history = page.locator("#fb-existing").innerText();
            assertTrue(history.contains("since rewritten"),
                    "the comment is presented as though it were about the test on screen: " + history);

            // and the test it WAS about is reachable, because the old sentence is unreadable
            // without the old test
            page.locator("#fb-existing details.about summary").first().click();
            String shown = page.locator("#fb-existing details.about pre").first().innerText();
            assertTrue(shown.contains("escapesAmp"), "expected the ORIGINAL test body, got: " + shown);
            assertFalse(shown.contains("rewritten"), "that is the new test, not the one commented on");
        }
    }

    /// An interrupted run does not look like a healthy one.
    ///
    /// This is the whole symptom, rendered. A deploy restarted the container mid-run and the
    /// page showed: the same grey banner a never-run repo shows, a growing machine-time figure,
    /// and "at current pace, 303 file(s) left" — a prediction about a run that needed someone to
    /// restart it. Every counter was frozen-but-plausible and the one number that MOVED was the
    /// clock on a unit nothing was working on.
    @Test
    void anInterruptedRunIsNotRenderedAsAHealthyOne() {
        State.STATE.run.status = "interrupted";
        State.STATE.stage = new State.Stage("interrupted", "orchestrator restarted",
                Util.nowSec() - 240, null);
        State.upsertFile("src/main/java/org/json/XML.java::pick", Map.of(
                "status", "candidate", "path", "src/main/java/org/json/XML.java", "method", "pick"));

        try (Page page = open()) {
            page.waitForFunction("() => document.getElementById('stage-banner')"
                    + ".className.includes('interrupted')");

            String banner = page.locator("#stage-banner").getAttribute("class");
            assertFalse(banner.contains("idle"), "styled as a repo that has never run: " + banner);
            assertTrue(page.locator("#stage-name").innerText().contains("Interrupted"));
            assertTrue(page.locator("#stage-detail").innerText().contains("idle"),
                    "no sign of how long it has been still: " + page.locator("#stage-detail").innerText());
            assertEquals("–", page.locator("#c-eta").innerText());
            assertEquals("run not progressing", page.locator("#c-eta-d").innerText());
        }
    }

    // ── the layout bug a screenshot caught and no test did ────────────────

    @Test
    void theApplyCheckboxSitsBesideItsLabelNotAboveIt() {
        // `dialog#fb input` is an ID selector and beat `.fb-apply input`, so the checkbox kept
        // width:100% and rendered centred on its own line with the label orphaned beneath
        try (Page page = open()) {
            page.locator("button.say").first().click();
            page.waitForSelector("#fb[open]");

            Locator box = page.locator("#fb-apply");
            double width = box.boundingBox().width;
            double dialogWidth = page.locator("#fb").boundingBox().width;
            assertTrue(width < 40, "the checkbox is " + width + "px wide — it stretched to fill the row");
            assertTrue(width < dialogWidth / 2, "the checkbox is filling the dialog");
        }
    }

    // ── the write path, through the page rather than through curl ─────────

    @Test
    void typingACommentAndSavingStoresIt() {
        try (Page page = open()) {
            page.locator("button.say").first().click();
            page.waitForSelector("#fb[open]");
            page.locator("#fb-text").fill("the arrangement here is three lines longer than it needs to be");
            page.locator("#fb-author").fill("Claude");
            page.locator("#fb-save").click();
            page.waitForFunction("() => document.getElementById('fb-status').textContent.length > 0");

            String status = page.locator("#fb-status").innerText();
            assertTrue(status.contains("saved"), "got: " + status);

            List<Map<String, Object>> lines = Feedback.lines(Repo.repoDir());
            assertTrue(lines.stream().anyMatch(l -> "feedback".equals(l.get("kind"))
                            && String.valueOf(l.get("text")).contains("three lines longer")),
                    "the comment never reached the repo's file");
        }
    }

    @Test
    void theSayButtonIsOnlyOfferedForUnitsThatHaveTests() {
        // a candidate has had nothing written for it, so there is nothing to judge
        State.upsertFile("src/main/java/org/json/XML.java::escape", Map.of("status", "candidate"));
        try (Page page = open()) {
            assertEquals(0, page.locator("button.say").count());
        }
    }
}
