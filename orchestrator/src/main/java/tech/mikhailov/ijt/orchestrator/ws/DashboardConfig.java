package tech.mikhailov.ijt.orchestrator.ws;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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

    /// `/dashboard/api/**` is the same API as `/api/**`.
    ///
    /// WITHOUT THIS THE DASHBOARD ONLY WORKS BEHIND CADDY. app.js fetches relative paths —
    /// `fetch('api/state')` — which the browser resolves against `/dashboard/`, so every request
    /// it makes goes to `/dashboard/api/…`. In the deployment Caddy strips the prefix before
    /// proxying, and nothing noticed that the application itself answers 404 to those paths.
    ///
    /// Point a browser at the container directly — `docker run -p 3000:3000`, which is exactly
    /// what "clone this and run it against your repo" means — and the page loads, renders an
    /// empty shell, and fetches nothing. It looks like a pipeline that has not started rather
    /// than a dashboard that cannot see.
    ///
    /// Found by the Playwright suite on its first run, which drives the page with no reverse
    /// proxy in front of it and therefore hit immediately what no amount of curling through
    /// Caddy could show.
    ///
    /// A forward rather than a redirect: a redirect would work but costs a round trip on every
    /// poll, and the two spellings should be the same resource rather than one pointing at the
    /// other.
    @RequestMapping("/dashboard/api/**")
    public String forwardApi(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "forward:" + path.substring("/dashboard".length());
    }

    /// Exposed for the test, which asserts against the same value the handler was built from
    /// rather than re-reading the property and agreeing with itself.
    public Path dashboardRoot() {
        return root;
    }
}
