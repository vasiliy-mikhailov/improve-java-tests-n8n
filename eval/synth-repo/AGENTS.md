# Contributor rules for synth-java-tests

These rules exist so an automated test-improvement pipeline can be checked against them.

## Scope

- Work only under `src/main/java/com/example/core/`.
- **Never touch UI code** (`com.example.ui`, anything under `src/main/java/com/example/ui/`).
  Rendering is verified by hand.

## Branching

- One branch per class, named `tests/improve-{file}`.

## Writing tests

- Tests go in `src/test/java`, mirroring the package of the class under test.
- JUnit 5 only (`org.junit.jupiter.api`). No new test dependencies.
- **No reflection, no `setAccessible`, no reading source or bytecode.**
- Assert real values and observable behaviour. A test that only checks "does not throw"
  is not acceptable.
- Never modify existing tests or production code.

## Accepting changes

- Changes are good only when the whole suite is green and MAC (line coverage × mutation
  score) improved.

## Pull requests

- Title: `test: strengthen tests for <ClassName>`.
- The body must contain a before/after table for line coverage, mutation score and MAC,
  and a one-line summary of which mutants the new tests now catch.
- Label PRs `tests`.
