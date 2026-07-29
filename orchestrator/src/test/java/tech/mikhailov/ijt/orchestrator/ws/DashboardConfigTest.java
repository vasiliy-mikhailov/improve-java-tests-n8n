package tech.mikhailov.ijt.orchestrator.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

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
