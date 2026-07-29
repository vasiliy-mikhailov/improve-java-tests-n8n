package tech.mikhailov.ijt.orchestrator.store;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/// The context the store's tests run in.
///
/// `@DataJpaTest` needs a `@SpringBootConfiguration` and finds the nearest one by walking UP
/// from the test's package. This one sits in the store's own package, so these tests do not
/// depend on `OrchestratorApplication` — a class this phase does not own and which is not
/// needed to prove that a unit round-trips.
///
/// Deliberately NOT `@SpringBootApplication`: that implies a component scan of this package,
/// which would register {@link StateRepository} a second time under a different bean name
/// beside the `@Import` the tests do, and `@Autowired` by type would then fail on two
/// candidates. `@EnableAutoConfiguration` still marks this package as the auto-configuration
/// package, which is what makes Hibernate find the entities and Spring Data find the
/// repositories.
@SpringBootConfiguration
@EnableAutoConfiguration
class StoreTestApplication {
}
