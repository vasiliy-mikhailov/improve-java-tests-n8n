package tech.mikhailov.ijt.orchestrator.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// `/dashboard` and `/dashboard/` must serve index.html.
///
/// This is a regression guard for a bug that a green build could not see. A resource handler on
/// `/dashboard/**` serves `/dashboard/app.js` perfectly and answers `/dashboard/` with 404,
/// because the path remaining after the handler prefix is empty and
/// ResourceHttpRequestHandler returns null on an empty path BEFORE consulting the resolver
/// chain — so even a custom PathResourceResolver never runs. That was tried first and produced
/// exactly that asymmetry on a live instance.
///
/// Caddy reverse-proxies /dashboard to this port and deploy.sh smoke checks it, so the 404 does
/// not present as a missing page — it presents as a failed deploy.
class DashboardConfigTest {

    /// The DEFAULT, resolved the way the container resolves it.
    ///
    /// Every other test here hands the constructor a path, so the placeholder chain
    /// `${ijt.dashboard-dir:${DASHBOARD_DIR:/app/dashboard}}` is exercised by none of them —
    /// the one expression that decides where a real deployment looks for its own dashboard, and
    /// the one whose wrong answer presents as a 404 on the whole page rather than as a test
    /// failure. Nothing sets DASHBOARD_DIR: not the Dockerfile, not compose, not the entrypoint.
    /// The literal at the end of that chain IS the production configuration.
    ///
    /// Resolved through a real context rather than by reading the annotation's text, so a change
    /// that keeps the string and breaks the resolution is still caught.
    @Test
    void theDefaultDashboardDirIsTheOneTheImageShipsTheFilesTo() {
        // A machine that HAS the variable set would legitimately resolve to something else;
        // that is the chain working, not failing.
        assumeTrue(System.getenv("DASHBOARD_DIR") == null,
                "DASHBOARD_DIR is set in this environment, so the default is not what applies");

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(PropertySourcesPlaceholderConfigurer.class, DashboardConfig.class);
            ctx.refresh();
            assertEquals(Path.of("/app/dashboard"), ctx.getBean(DashboardConfig.class).dashboardRoot(),
                    "the Dockerfile COPYs dashboard/ to /app/dashboard — if this default moves, "
                            + "every deployment that does not set DASHBOARD_DIR serves 404");
        }
    }

    @Test
    void bothSpellingsServeIndexHtml(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("index.html"), "<!doctype html><html>dash</html>");
        ResponseEntity<Resource> r = new DashboardConfig(dir.toString()).index();

        assertEquals(200, r.getStatusCode().value());
        assertNotNull(r.getBody());
        assertTrue(r.getBody().exists());
        assertEquals("text/html", String.valueOf(r.getHeaders().getContentType()));
        // one method answers both /dashboard and /dashboard/ — see the @GetMapping. A redirect
        // for either would change behaviour the Node sidecar had and add a round trip to every
        // cold load.
    }

    @Test
    void servedWithoutCachingSoADeployIsNotFoughtByTheBrowser(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("index.html"), "<html></html>");
        var r = new DashboardConfig(dir.toString()).index();
        assertTrue(String.valueOf(r.getHeaders().getCacheControl()).contains("no-store"),
                "a cached dashboard after a deploy runs yesterday's code against today's API");
    }

    @Test
    void aMissingDashboardDirectoryIs404NotAStackTrace(@TempDir Path dir) {
        // the usual cause is running outside the container without setting DASHBOARD_DIR, and
        // it should read as "no dashboard here" rather than an internal error
        var r = new DashboardConfig(dir.resolve("nope").toString()).index();
        assertEquals(404, r.getStatusCode().value());
    }

    @Test
    void theRootIsAbsoluteAndNormalisedSoRelativeStartupDirsCannotMoveIt(@TempDir Path dir) {
        var cfg = new DashboardConfig(dir + "/../" + dir.getFileName());
        assertEquals(dir.toAbsolutePath().normalize(), cfg.dashboardRoot());
        assertTrue(cfg.dashboardRoot().isAbsolute());
    }
}
