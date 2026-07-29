package tech.mikhailov.ijt.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/// The process that replaces n8n.
///
/// 66 nodes and 44 HTTP calls become a Spring Batch job calling the domain layer in process,
/// and the dashboard's 2-second poll of `/api/state` becomes a STOMP subscription. The control
/// flow is in docs/SPRING-MIGRATION.md and, authoritatively, in `n8n/generate-workflows.mjs`.
///
/// **`@EnableBatchProcessing` is deliberately ABSENT.** On Boot 2 it was how Batch was turned
/// on; on Boot 3 it is how Batch's AUTO-CONFIGURATION is turned OFF — `BatchAutoConfiguration`
/// backs off when it finds either that annotation or a `DefaultBatchConfiguration` bean, and
/// with it goes the DataSource-backed `JobRepository`, `JobExplorer`, and the schema
/// initialisation that `spring.batch.jdbc.initialize-schema` asks for. The symptom is a context
/// that starts and a first launch that fails on a missing BATCH_JOB_INSTANCE table, which reads
/// as a database problem and is an annotation problem. Adding a starter is all that is needed;
/// the only thing this application must say about Batch is that a job must not run at boot
/// (`spring.batch.job.enabled: false`), and it says that in application.yml.
///
/// `@ConfigurationPropertiesScan` rather than `@EnableConfigurationProperties(...)`: the
/// configuration surface is one class today ({@link tech.mikhailov.ijt.orchestrator.config.IjtProperties})
/// and a second one added by a later change should not have to remember to edit this file.
///
/// The `main` below is not the only one in the tree — `tech.mikhailov.ijt.Server` is the
/// standalone backend kept as the rollback path — which is why orchestrator/pom.xml names this
/// class as the boot jar's entry point instead of letting the plugin scan for one.
@SpringBootApplication
@ConfigurationPropertiesScan
public class OrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApplication.class, args);
    }
}
