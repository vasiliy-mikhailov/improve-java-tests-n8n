package tech.mikhailov.ijt.orchestrator.store;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import tech.mikhailov.ijt.Util;

import java.util.LinkedHashMap;
import java.util.Map;

/// Shared wiring for the store's tests.
///
/// `@Import(StateRepository.class)`: `@DataJpaTest` deliberately does not register application
/// components, so the facade has to be asked for by name. That is the right shape here anyway —
/// these tests exercise the seam every other package is allowed to see, not the repositories
/// behind it.
///
/// `ddl-auto` is pinned rather than inherited. The schema for an embedded database defaults to
/// `create-drop`, but a `spring.jpa.hibernate.ddl-auto` in the application's own configuration
/// would win over that default and take the tests with it — and this module's configuration is
/// written by a different phase.
@DataJpaTest
@Import(StateRepository.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
})
abstract class StoreTestSupport {

    @Autowired
    protected StateRepository store;

    @Autowired
    protected TestEntityManager em;

    /// Push everything pending to the database and forget it.
    ///
    /// Without this the assertions run against the same in-memory instances the test just
    /// wrote, and a JSON column that never round-tripped — or a null that the converter turned
    /// into an empty list on the way back — would pass. The only `EntityManager` in this module
    /// is here, in the store's own test, and it exists to prove the store's own storage.
    protected void reload() {
        em.flush();
        em.clear();
    }

    /// A run shaped like `State.freshRun` produces one: an id derived from the wall clock, a
    /// status of `running`, and no measurements at all.
    protected static Run newRun(String id, String repoUrl) {
        Run run = new Run(id);
        run.setRepoUrl(repoUrl);
        run.setRepoBranch("main");
        run.setStatus("running");
        run.setStartedAt(Util.nowSec());
        run.setConfig(new LinkedHashMap<>(Map.of("repoUrl", repoUrl, "maxAttemptsPerFile", 3)));
        return run;
    }

    /// `Map.of` rejects null values, and null values are exactly what several of these tests are
    /// about — a patch carrying `{attemptStartedAt: null}` is how a caller stops a stopwatch.
    protected static Map<String, Object> patch(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put(String.valueOf(kv[i]), kv[i + 1]);
        return out;
    }
}
