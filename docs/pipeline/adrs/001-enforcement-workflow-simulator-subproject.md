# ADR-001: GOB simulator as a separate Gradle subproject

## Status

Accepted

## Context

CIMD-4372 requires a non-live simulator of three Libra Gateway hearing-event endpoints so CP can
exercise the enforcement hearing-result flow before the real Libra integration exists.

The HMCTS Spring Boot template (`service-hmcts-crime-springboot-template`), which this service was
scaffolded from, assumes a single Gradle module: one `build.gradle` at the root, one boot jar, one
Docker image. The simulator must never ship inside the service's own artefact — it fabricates
court data and answers a vendor API contract the real service does not implement, and it must be
impossible to accidentally run it as, or alongside, the production service in a live environment.

Two shapes were available: a profile-gated package inside the existing single module, or a
separate module with its own build output. A profile-gated package was rejected because a runtime
profile check is the *only* thing standing between it and packaging into the live image — one
missed `if` and it ships. A separate module gets a structural guarantee for free: a build output
that doesn't exist in the same place the packaging step looks.

## Decision

Add `:enforcement-workflow-simulator` as its own Gradle subproject with its own Spring Boot boot jar, declared in a
new root-level `settings.gradle`. It is deliberately excluded from publishing.

This required four parameterised `gradle/*.gradle` convention files to become usable by both the
root project and the subproject (the fourth was discovered during implementation, not planned
up front):

1. **`gradle/jar.gradle`** — the plain `jar` task disable and `bootJar` archive naming apply
   unchanged to a subproject.
2. **`gradle/pmd.gradle`** — the opt-in `pmdMain` gate (only runs when explicitly requested by
   task name, matching bare, qualified, or subproject-path invocation) needed to work whether
   invoked as `pmdMain` from within the subproject or `:enforcement-workflow-simulator:pmdMain` from the root.
3. **`gradle/repositories.gradle` / `gradle/publishing.gradle` split** — the simulator's
   `build.gradle` applies `repositories.gradle` (needed to resolve dependencies) but deliberately
   does **not** apply `publishing.gradle`, so it has no `maven-publish` publication and nothing to
   push to Azure Artifacts or GitHub Packages.
4. **`gradle/java.gradle`'s `wrapper` task guard** — the `wrapper` task only exists on the root
   project. Once `java.gradle` was applied to the subproject too (for the JDK 25 toolchain and
   Lombok/JavaExec settings), its `tasks.named('wrapper')` reference started failing subproject
   configuration outright. This was found only once the subproject was wired up, not anticipated
   in the original plan. Fixed by matching on task name (`tasks.matching { it.name == 'wrapper' }`)
   instead, so the block is a no-op wherever the `wrapper` task doesn't exist.

Three independent layers make it impossible for the simulator to run where it could be mistaken
for the real Libra Gateway:

1. **Nothing packages it.** The root `Dockerfile` builds `COPY build/libs/*.jar`, which is
   root-project-relative. `:enforcement-workflow-simulator`'s boot jar lands in `enforcement-workflow-simulator/build/libs/`, which
   that `COPY` line cannot reach — there is no packaging path into the service's container image at
   all, not merely one gated by configuration.
2. **A Spring profile allow-list** — the simulator's beans and endpoints only activate under the
   `enforcement-workflow-simulator` profile.
3. **A pre-bean fail-fast** (`LiveEnvironmentGuard`) that throws before any bean is created if a
   forbidden profile (`prod`, `production`, `live`, `perf`, `preprod`) is active, or if
   `enforcement-workflow-simulator` is not.

## Consequences

- The repo now has a `settings.gradle` it did not have before, and a second `build.gradle` under
  `enforcement-workflow-simulator/`. The template's single-module assumption no longer holds for this repo.
- The divergence from the template is now four parameterised `gradle/*.gradle` files rather than
  the three originally planned, each documented at the point it diverges (see the file comments in
  `gradle/pmd.gradle` and `gradle/java.gradle`).
- CI and local tooling that assume a single build output (`build/libs/*.jar`) continue to work
  unmodified for the service artefact; anything that needs the simulator's jar must reference
  `enforcement-workflow-simulator/build/libs/*.jar` explicitly and does not exist yet (Helm/deployment for the
  simulator is out of scope for this story — see the simulator README's "Known gaps" section and
  the deferred-work list in the implementation plan artifact).
- PMD for the simulator must be invoked explicitly (`./gradlew pmdMain` or
  `:enforcement-workflow-simulator:pmdMain`) — it does not run as part of `build` for either project.
