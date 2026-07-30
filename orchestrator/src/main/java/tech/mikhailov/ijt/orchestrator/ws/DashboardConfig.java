package tech.mikhailov.ijt.orchestrator.ws;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

/// Serving the dashboard's static files.
///
/// It is not in the jar and is not going to be. It is plain HTML/CSS/JS that was never ported,
/// it lives in the image at /app/dashboard, and it is mounted from the filesystem so it
/// can be edited without a rebuild. So this is a FILE resource handler, not a classpath one —
/// the usual `static/` convention would silently serve nothing.
///
/// Caddy reverse-proxies /dashboard on the public host to this port, and deploy.sh smoke checks
/// it. A 404 here fails the deploy, which is how the gap was found.
@Configuration
@org.springframework.stereotype.Controller
public class DashboardConfig implements WebMvcConfigurer {

    private final Path root;

    public DashboardConfig(@Value("${ijt.dashboard-dir:${DASHBOARD_DIR:/app/dashboard}}") String dashboardDir) {
        this.root = Path.of(dashboardDir).toAbsolutePath().normalize();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Trailing separator is required. Without it Spring treats the value as a file prefix
        // rather than a directory, so /dashboard/app.js resolves against ".../dashboardapp.js".
        registry.addResourceHandler("/dashboard/**")
                .addResourceLocations("file:" + root + "/")
                // The dashboard reads its own state; a cached app.js after a deploy is a client
                // running yesterday's code against today's API, which presents as data bugs.
                .setCachePeriod(0);
    }

    /// `/dashboard` and `/dashboard/` both mean index.html.
    ///
    /// A controller rather than a clever resolver, because the resource handler CANNOT answer
    /// these. The path remaining after the handler prefix is empty, and
    /// ResourceHttpRequestHandler returns null on an empty path before it consults the resolver
    /// chain at all — so a custom PathResourceResolver never runs. That was tried; it produced
    /// exactly the asymmetry observed on a live instance, where /dashboard/app.js served 200
    /// and /dashboard/ served 404.
    ///
    /// Both spellings are answered directly rather than redirected. The Node sidecar answered
    /// both (`rel.slice('/dashboard'.length) || '/'`), and Caddy forwards whichever the browser
    /// sent — a redirect would change behaviour for one of the two and add a round trip to
    /// every cold load of the page.
    @GetMapping({"/dashboard", "/dashboard/"})
    @org.springframework.web.bind.annotation.ResponseBody
    public ResponseEntity<Resource> index() {
        Resource index = new FileSystemResource(root.resolve("index.html"));
        if (!index.exists() || !index.isReadable()) {
            // 404 with an empty body rather than a stack trace: the usual cause is
            // DASHBOARD_DIR pointing somewhere that does not exist (running outside the
            // container without setting it), and that should read as "no dashboard here".
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .cacheControl(CacheControl.noStore())
                .body(index);
    }

    /// Exposed for the test, which asserts against the same value the handler was built from
    /// rather than re-reading the property and agreeing with itself.
    public Path dashboardRoot() {
        return root;
    }
}
