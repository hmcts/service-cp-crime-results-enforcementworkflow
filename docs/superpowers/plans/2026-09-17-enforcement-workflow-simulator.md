# GOB (Libra) Simulator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a non-live GOB (Libra) simulator that answers `POST /hearing/result` with a schema-valid `HearingResultedResponse`, so CP can render NOWs end-to-end without the real Libra integration.

**Architecture:** A separate `:enforcement-workflow-simulator` Gradle subproject with its own Spring Boot application and boot jar; the root project stays the enforcement-workflow service, untouched. Three YAML catalogue resources encode the CIMD-4372 "Result vs Required Field" table as data; a thin engine unions required field labels across posted result codes, filters them to the entities CP requested, resolves values from a per-`caseUrn` seed or deterministic defaults, and converts the resulting tree into typed response records.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Gradle, Jackson, Lombok, JUnit 5, `swagger-request-validator-mockmvc` 2.44.9 for OpenAPI conformance assertions.

**Spec:** `docs/superpowers/specs/2026-09-17-enforcement-workflow-simulator-design.md`

## Global Constraints

- **Contract source of truth:** `libra-gateway-hearing-events-openapi-v0.3.0.yml`. Where the CIMD-4372 ticket and the spec disagree, **the spec wins** (spec §3). Never emit a property the schema does not declare — `NowsDataItems` is `additionalProperties: false`.
- **Java 25**, toolchain pinned in `gradle/java.gradle`. Compilation runs with `-Xlint:unchecked -Werror` — warnings fail the build.
- **No security.** No token validation, no TLS, no auth filter. `POST /auth/token` returns an unvalidated dummy token (spec §2).
- **Never emit `enforcerCode`** anywhere in any response (AC5).
- **No PII, case data, or real court reference numbers** in seeds, defaults, tests, or fixtures. All values are invented.
- **JSON logging to stdout** is mandatory — the simulator reuses the root `logback.xml` pattern (`net.logstash.logback` encoder), never a plain-text appender.
- **At least one integration test per new endpoint**, and the suite must be green locally before review. Never weaken or skip a test to go green.
- **Monetary values** are `BigDecimal` with scale 2, serialised as JSON numbers (spec §10, OQ-9).
- **Dependencies are declared in `build.gradle` files**, never in the `gradle/*.gradle` apply-from files — dependabot does not track the latter.

## Prerequisite gate

`CLAUDE.md` forbids starting Stage 5 (Code) without an implementation-plan artifact in
`docs/pipeline/artifacts/`. Both HTML artifacts below are produced **before Task 1 begins** and are
not tasks in this plan:

- `docs/pipeline/artifacts/001-libra-v030-contract-gaps.html` — the design gap (spec §3, §12)
- `docs/pipeline/artifacts/002-enforcement-workflow-simulator-implementation-plan.html` — this plan

ADR-001 and ADR-002 are written in Task 9, after the decisions they record have been exercised in
code.

---

### Task 1: Gradle subproject skeleton and live-environment guard

Stands up `:enforcement-workflow-simulator` as its own Spring Boot application that refuses to start outside a non-live context. Nothing else works until this does.

**Files:**
- Create: `settings.gradle`
- Create: `enforcement-workflow-simulator/build.gradle`
- Create: `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/EnforcementWorkflowSimulatorApplication.java`
- Create: `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/guard/LiveEnvironmentGuard.java`
- Create: `enforcement-workflow-simulator/src/main/resources/application.yaml`
- Create: `enforcement-workflow-simulator/src/main/resources/logback.xml`
- Create: `gradle/publishing.gradle`
- Modify: `gradle/repositories.gradle` (remove the `publishing {}` block — it moves to `publishing.gradle`)
- Modify: `gradle/jar.gradle:6` (`rootProject.name` → `project.name`)
- Modify: `gradle/pmd.gradle:5` (`files(...)` → `rootProject.file(...)`)
- Modify: `build.gradle` (apply the new `publishing.gradle`)
- Test: `enforcement-workflow-simulator/src/test/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/guard/LiveEnvironmentGuardTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `LiveEnvironmentGuard.check(Set<String> activeProfiles)` (package-private static, throws `IllegalStateException`); `EnforcementWorkflowSimulatorApplication` as the `@SpringBootApplication` class every later `@SpringBootTest` boots.

- [ ] **Step 1: Write the failing guard test**

Create `enforcement-workflow-simulator/src/test/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/guard/LiveEnvironmentGuardTest.java`:

```java
package uk.gov.hmcts.cp.enforcementworkflowsimulator.guard;

import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiveEnvironmentGuardTest {

    @Test
    void accepts_the_simulator_profile_on_its_own() {
        assertThatCode(() -> LiveEnvironmentGuard.check(Set.of("enforcement-workflow-simulator")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejects_a_live_profile_even_alongside_the_simulator_profile() {
        assertThatThrownBy(() -> LiveEnvironmentGuard.check(Set.of("enforcement-workflow-simulator", "prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must never run in a live environment")
                .hasMessageContaining("prod");
    }

    @Test
    void rejects_every_forbidden_profile_name_case_insensitively() {
        for (final String profile : Set.of("prod", "PRODUCTION", "Live", "perf", "preprod")) {
            assertThatThrownBy(() -> LiveEnvironmentGuard.check(Set.of("enforcement-workflow-simulator", profile)))
                    .as("profile %s must be rejected", profile)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void rejects_startup_when_the_simulator_profile_is_absent() {
        assertThatThrownBy(() -> LiveEnvironmentGuard.check(Set.of("docker")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires the 'enforcement-workflow-simulator' profile");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :enforcement-workflow-simulator:test --tests '*LiveEnvironmentGuardTest'`
Expected: FAIL — the `:enforcement-workflow-simulator` project does not exist yet ("Project 'enforcement-workflow-simulator' not found").

- [ ] **Step 3: Create the Gradle wiring**

Create `settings.gradle`:

```groovy
rootProject.name = 'service-cp-crime-results-enforcementworkflow'

include ':enforcement-workflow-simulator'
```

> The root project previously had no `settings.gradle`, so its name defaulted to the directory name — which is the same string. Adding this file changes nothing for the service.

Create `enforcement-workflow-simulator/build.gradle`:

```groovy
plugins {
  id 'java'
  id 'org.springframework.boot' version '4.1.1'
  id 'io.spring.dependency-management' version '1.1.7'
  id 'jacoco'
}

group = 'uk.gov.hmcts.cp'
version = rootProject.version

apply {
  from("$rootDir/gradle/repositories.gradle")
  from("$rootDir/gradle/java.gradle")
  from("$rootDir/gradle/pmd.gradle")
  from("$rootDir/gradle/test.gradle")
  from("$rootDir/gradle/jar.gradle")
}

// Deliberately NOT applying gradle/publishing.gradle — the simulator must never
// be published to Azure Artifacts or GitHub Packages.

dependencies {
  implementation 'org.springframework.boot:spring-boot-starter-web'
  implementation 'net.logstash.logback:logstash-logback-encoder:9.0'
  implementation 'com.fasterxml.jackson.dataformat:jackson-dataformat-yaml'

  compileOnly 'org.projectlombok:lombok:1.18.48'
  annotationProcessor 'org.projectlombok:lombok:1.18.48'

  testImplementation 'org.springframework.boot:spring-boot-starter-webmvc-test'
  testImplementation('org.springframework.boot:spring-boot-starter-test') {
    exclude group: 'junit', module: 'junit'
    exclude group: 'org.junit.vintage', module: 'junit-vintage-engine'
  }
  testImplementation 'com.atlassian.oai:swagger-request-validator-mockmvc:2.44.9'
}
```

- [ ] **Step 4: Parameterise the three template conventions**

In `gradle/jar.gradle`, change the `bootJar` archive name so each project names its own jar:

```groovy
bootJar {
  archiveFileName = "${project.name}-${project.version}.jar"
```

> For the root project `project.name` equals `rootProject.name`, so the service jar name is unchanged.

In `gradle/pmd.gradle`, resolve the ruleset from the repo root:

```groovy
  ruleSetFiles = rootProject.files(".github/pmd-ruleset.xml")
```

Create `gradle/publishing.gradle` containing the `publishing { ... }` block **moved verbatim** out of `gradle/repositories.gradle`, together with the three `githubActor` / `githubToken` / `githubRepo` local variables and the `azureADOArtifact*` variables it references. Leave only the `repositories { ... }` block and the variables it needs in `gradle/repositories.gradle`.

In the root `build.gradle`, add the new file to the `apply` block, immediately after `repositories.gradle`:

```groovy
  from("$rootDir/gradle/repositories.gradle")
  from("$rootDir/gradle/publishing.gradle")
```

- [ ] **Step 5: Write the guard and the application class**

Create `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/guard/LiveEnvironmentGuard.java`:

```java
package uk.gov.hmcts.cp.enforcementworkflowsimulator.guard;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;

/**
 * Refuses to let the GOB simulator start anywhere it could be mistaken for the real Libra Gateway.
 *
 * <p>Listens for {@link ApplicationEnvironmentPreparedEvent}, which fires before any bean is
 * created — earlier than {@code @PostConstruct} — so a misdeployed pod crash-loops visibly rather
 * than serving fabricated court data.
 */
public final class LiveEnvironmentGuard implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    static final String REQUIRED_PROFILE = "enforcement-workflow-simulator";
    static final Set<String> FORBIDDEN_PROFILES =
            Set.of("prod", "production", "live", "perf", "preprod");

    @Override
    public void onApplicationEvent(final ApplicationEnvironmentPreparedEvent event) {
        check(Arrays.stream(event.getEnvironment().getActiveProfiles())
                .collect(Collectors.toUnmodifiableSet()));
    }

    static void check(final Set<String> activeProfiles) {
        final Set<String> normalised = activeProfiles.stream()
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());

        final Set<String> forbidden = new TreeSet<>(normalised);
        forbidden.retainAll(FORBIDDEN_PROFILES);
        if (!forbidden.isEmpty()) {
            throw new IllegalStateException(
                    "GOB simulator must never run in a live environment. Forbidden profile(s) active: "
                            + forbidden);
        }

        if (!normalised.contains(REQUIRED_PROFILE)) {
            throw new IllegalStateException(
                    "GOB simulator requires the '" + REQUIRED_PROFILE
                            + "' profile to be active. Active profiles: " + new TreeSet<>(normalised));
        }
    }
}
```

Create `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/EnforcementWorkflowSimulatorApplication.java`:

```java
package uk.gov.hmcts.cp.enforcementworkflowsimulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import uk.gov.hmcts.cp.enforcementworkflowsimulator.guard.LiveEnvironmentGuard;

@SpringBootApplication
public class EnforcementWorkflowSimulatorApplication {

    public static void main(final String[] args) {
        final SpringApplication application = new SpringApplication(EnforcementWorkflowSimulatorApplication.class);
        application.addListeners(new LiveEnvironmentGuard());
        application.run(args);
    }
}
```

Create `enforcement-workflow-simulator/src/main/resources/application.yaml`:

```yaml
spring:
  application:
    name: enforcement-workflow-simulator
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:enforcement-workflow-simulator}
  jackson:
    default-property-inclusion: non_null

server:
  port: ${SERVER_PORT:8091}

gob:
  simulator:
    seed-dir: ${ENFORCEMENT_WORKFLOW_SIMULATOR_SEED_DIR:}

logging:
  level:
    root: INFO
```

Create `enforcement-workflow-simulator/src/main/resources/logback.xml` as a byte-for-byte copy of the root `src/main/resources/logback.xml`, so JSON-to-stdout logging is identical.

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew :enforcement-workflow-simulator:test --tests '*LiveEnvironmentGuardTest'`
Expected: PASS — 4 tests.

- [ ] **Step 7: Verify the root build is unaffected**

Run: `./gradlew build`
Expected: PASS. Confirm `build/libs/service-cp-crime-results-enforcementworkflow-0.0.999.jar` still exists with its original name — the `jar.gradle` change must not have renamed the service jar.

- [ ] **Step 8: Commit**

```bash
git add settings.gradle enforcement-workflow-simulator build.gradle gradle/
git commit -m "feat(CIMD-4372): add :enforcement-workflow-simulator subproject with live-environment guard"
```

---

### Task 2: OpenAPI resource, conformance harness, and the two simple endpoints

Delivers `POST /auth/token` and `POST /hearing`, and — more importantly — the OpenAPI conformance test harness every later task asserts through.

**Files:**
- Create: `enforcement-workflow-simulator/src/main/resources/openapi/libra-gateway-hearing-events-v0.3.0.yml` (copy of the attachment)
- Create: `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/AuthController.java`
- Create: `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/HearingController.java`
- Create: `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/model/OAuthTokenResponse.java`
- Create: `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/model/HearingConfirmedRequest.java`
- Test: `enforcement-workflow-simulator/src/test/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/OpenApiConformance.java`
- Test: `enforcement-workflow-simulator/src/test/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/AuthControllerIT.java`
- Test: `enforcement-workflow-simulator/src/test/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/HearingControllerIT.java`

**Interfaces:**
- Consumes: `EnforcementWorkflowSimulatorApplication` (Task 1).
- Produces: `OpenApiConformance.conformsToSpec()` returning a `ResultMatcher` bound to the bundled contract — used by every later IT via `.andExpect(conformsToSpec())`; and `OpenApiConformance.SPEC_PATH` (`String`).

- [ ] **Step 1: Copy the OpenAPI document into the simulator**

```bash
mkdir -p enforcement-workflow-simulator/src/main/resources/openapi
cp ~/Downloads/libra-gateway-hearing-events-openapi-v0.3.0.yml \
   enforcement-workflow-simulator/src/main/resources/openapi/libra-gateway-hearing-events-v0.3.0.yml
```

Verify it copied intact:

```bash
grep -c 'HearingResultedResponse' enforcement-workflow-simulator/src/main/resources/openapi/libra-gateway-hearing-events-v0.3.0.yml
```
Expected: `2` (the `$ref` in the path, and the schema definition).

- [ ] **Step 2: Write the failing endpoint tests**

Create `enforcement-workflow-simulator/src/test/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/OpenApiConformance.java`:

```java
package uk.gov.hmcts.cp.enforcementworkflowsimulator.api;

import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import org.springframework.test.web.servlet.ResultMatcher;

/** Shared access to the bundled Libra Gateway contract for conformance assertions. */
public final class OpenApiConformance {

    public static final String SPEC_PATH = "openapi/libra-gateway-hearing-events-v0.3.0.yml";

    private OpenApiConformance() {
    }

    /** Asserts the response conforms to the spec's definition of the given operation. */
    public static ResultMatcher conformsToSpec() {
        return OpenApiValidationMatchers.openApi().isValid(SPEC_PATH);
    }
}
```

Create `enforcement-workflow-simulator/src/test/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/AuthControllerIT.java`:

```java
package uk.gov.hmcts.cp.enforcementworkflowsimulator.api;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.gov.hmcts.cp.enforcementworkflowsimulator.api.OpenApiConformance.conformsToSpec;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("enforcement-workflow-simulator")
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert") // MockMvc andExpect() calls are assertions
class AuthControllerIT {

    @Resource
    private MockMvc mockMvc;

    @Test
    void issues_a_bearer_token_for_client_credentials() throws Exception {
        mockMvc.perform(post("/auth/token")
                        .contentType(APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("client_id", "cp-test-client")
                        .param("client_secret", "not-a-real-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_in").value(3600))
                .andExpect(conformsToSpec());
    }

    @Test
    void issues_a_token_without_checking_the_credentials() throws Exception {
        mockMvc.perform(post("/auth/token")
                        .contentType(APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("client_id", "anything")
                        .param("client_secret", "anything"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty());
    }
}
```

Create `enforcement-workflow-simulator/src/test/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/HearingControllerIT.java`:

```java
package uk.gov.hmcts.cp.enforcementworkflowsimulator.api;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.gov.hmcts.cp.enforcementworkflowsimulator.api.OpenApiConformance.conformsToSpec;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("enforcement-workflow-simulator")
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert") // MockMvc andExpect() calls are assertions
class HearingControllerIT {

    @Resource
    private MockMvc mockMvc;

    @Test
    void accepts_a_hearing_confirmation() throws Exception {
        mockMvc.perform(post("/hearing")
                        .contentType(APPLICATION_JSON)
                        .header("X-Correlation-ID", "a1b2c3d4-1111-2222-3333-444455556666")
                        .content("""
                                {
                                  "caseUrn": "E011122334",
                                  "courtHearingLocation": "B02BR03",
                                  "dateOfHearing": "2026-04-24",
                                  "timeOfHearing": "14:00"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(conformsToSpec());
    }

    @Test
    void rejects_a_confirmation_missing_the_mandatory_court_location() throws Exception {
        mockMvc.perform(post("/hearing")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                { "caseUrn": "E011122334" }
                                """))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :enforcement-workflow-simulator:test --tests '*AuthControllerIT' --tests '*HearingControllerIT'`
Expected: FAIL — `AuthControllerIT` fails with 404 on `/auth/token`; `HearingControllerIT` fails with 404 on `/hearing`.

- [ ] **Step 4: Write the models and controllers**

Create `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/model/OAuthTokenResponse.java`:

```java
package uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OAuthTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") int expiresIn,
        @JsonProperty("scope") String scope) {
}
```

Create `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/model/HearingConfirmedRequest.java`:

```java
package uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

@JsonIgnoreProperties(ignoreUnknown = false)
public record HearingConfirmedRequest(
        @NotBlank String caseUrn,
        @NotBlank String courtHearingLocation,
        String dateOfHearing,
        String timeOfHearing) {
}
```

Create `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/AuthController.java`:

```java
package uk.gov.hmcts.cp.enforcementworkflowsimulator.api;

import java.util.Base64;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model.OAuthTokenResponse;

/**
 * Issues an opaque dummy bearer token. Credentials are never checked and the token is never
 * validated on any other endpoint — the simulator carries no security (spec §2).
 */
@RestController
public class AuthController {

    private static final int EXPIRES_IN_SECONDS = 3600;

    @PostMapping(path = "/auth/token",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public OAuthTokenResponse issueToken() {
        final String token = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new OAuthTokenResponse(token, "Bearer", EXPIRES_IN_SECONDS, null);
    }
}
```

Create `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/HearingController.java`:

```java
package uk.gov.hmcts.cp.enforcementworkflowsimulator.api;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model.HearingConfirmedRequest;

@Slf4j
@RestController
public class HearingController {

    @PostMapping(path = "/hearing", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> confirmHearing(@Valid @RequestBody final HearingConfirmedRequest request) {
        log.info("Hearing confirmation accepted: caseUrn={}, courtHearingLocation={}",
                request.caseUrn(), request.courtHearingLocation());
        return ResponseEntity.ok().build();
    }
}
```

Add validation support to `enforcement-workflow-simulator/build.gradle` dependencies:

```groovy
  implementation 'org.springframework.boot:spring-boot-starter-validation'
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :enforcement-workflow-simulator:test --tests '*AuthControllerIT' --tests '*HearingControllerIT'`
Expected: PASS — 4 tests.

- [ ] **Step 6: Commit**

```bash
git add enforcement-workflow-simulator
git commit -m "feat(CIMD-4372): add /auth/token and /hearing with OpenAPI conformance harness"
```

---

### Task 3: Catalogue resources, loader, and startup validation

Encodes the CIMD-4372 table as data and makes a broken catalogue a startup failure rather than a silent wrong answer.

**Files:**
- Create: `enforcement-workflow-simulator/src/main/resources/enforcement-workflow-simulator/catalogue/entity-names.yaml`
- Create: `enforcement-workflow-simulator/src/main/resources/enforcement-workflow-simulator/catalogue/field-paths.yaml`
- Create: `enforcement-workflow-simulator/src/main/resources/enforcement-workflow-simulator/catalogue/result-codes.yaml`
- Create: `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/catalogue/FieldPath.java`
- Create: `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/catalogue/ResultCodeEntry.java`
- Create: `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/catalogue/Catalogue.java`
- Create: `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/catalogue/CatalogueLoader.java`
- Test: `enforcement-workflow-simulator/src/test/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/catalogue/CatalogueLoaderTest.java`
- Test: `enforcement-workflow-simulator/src/test/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/catalogue/CatalogueCoverageTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `record FieldPath(String label, String path, String type, Object defaultValue, boolean unmapped, String reason)` with `String rootProperty()`
  - `record ResultCodeEntry(String code, List<String> fields, String alias, boolean postable, String note)`
  - `Catalogue` (a `record`, exposed as a bean in Task 6) with `List<String> propertiesFor(String nowsDataItemName)`, `List<String> fieldsFor(String resultCode)` (alias-resolved), `FieldPath fieldPath(String label)`, `boolean isKnown(String)`, `boolean isPostable(String)`, `Set<String> allCodes()`, `Set<String> allProperties()`, and the record accessors `entityNames()`, `fieldPaths()`, `resultCodes()`
  - `CatalogueLoader.load()` returning `Catalogue`, throwing `IllegalStateException` on any validation failure

- [ ] **Step 1: Write the catalogue resources**

Create `enforcement-workflow-simulator/src/main/resources/enforcement-workflow-simulator/catalogue/entity-names.yaml` — the 12 `NowsDataItemName` values mapped to real v0.3.0 properties (spec §6.1):

```yaml
# NowsDataItemName (as CP sends it) -> property of NowsDataItems in
# libra-gateway-hearing-events-openapi-v0.3.0.yml.
# NOTE: this is NOT a camelCase transform. Six of the twelve names do not
# camelCase to a real property — see spec §3 and ADR-002.
"Defendant Account": [defendant]
"Account History": [paymentHistory, transactionHistory]   # OQ-1: awaiting confirmation
"Account Offences and Penalties": [offences]
"Account Terms to Pay": [terms]
"Account Balance": [accountBalance]
"Account Warrant Number": [accountWarrantNumber]
"Warrant Contact Details": [warrantContactDetails]
"Account Bail Amount": [accountBailAmount]
"CT Account Bank Details": [ctBankDetails]
"Days Before Release of Warrant": [daysBeforeReleaseWarrant]
"Account Number": [accountNumber]
"Account Date Imposed": [accountDateImposed]
```

Create `enforcement-workflow-simulator/src/main/resources/enforcement-workflow-simulator/catalogue/field-paths.yaml` (spec §6.2 — every label used by any `result-codes.yaml` row must appear here):

```yaml
"Account No.":            { path: accountNumber,        type: string,  default: "ACC0001" }
"Total Balance":          { path: accountBalance,       type: number,  default: 1250.00 }
"Balance Outstanding":    { path: offences.accountTotal, type: number, default: 875.50 }
"Amount Paid or Cancelled": { path: offences.accountPaid, type: number, default: 375.00 }
"Amount Imposed":
  { path: "offences.offence[0].impositions.imposition[0].amountImposed", type: number, default: 1250.00 }
"Imposition type":
  { path: "offences.offence[0].impositions.imposition[0].impositionType", type: string, default: "Fine" }
"Date Imposed":           { path: accountDateImposed,   type: string,  default: "15 Jan 2026" }
"Time of Offence":        { path: "offences.offence[0].timeOfOffence", type: string, default: "14:00" }
"Place of offence":       { path: "offences.offence[0].placeOfOffence", type: string, default: "High Street" }
"Ticket No.":             { path: "offences.offence[0].ticketNumber", type: string, default: "TKT0042" }
"Vehicle Reg. No.":       { path: "offences.offence[0].vehicleReg", type: string, default: "AB12CDE" }
"Vehicle Make":           { path: defendant.assetVehicleMake, type: string, default: "Ford" }
"NTO issued on <date> & <time>":
  { path: "offences.offence[0].noticeToOwnerNoticeToHirer", type: string, default: "2026-02-01 09:15" }
"Payment Terms":          { path: terms.english_due,    type: string,  default: "Monthly" }
"Due Date":               { path: terms.english_firstDate, type: string, default: "31 May 2026" }
"Instalment Amount":      { path: terms.english_instalment, type: string, default: "20.00" }
"Lump Sum":               { path: terms.english_lumpsum, type: string,  default: "0.00" }
"Payment period":         { path: terms.english_instalmetPaymentPeriod, type: string, default: "Monthly" }
"Warrant No":             { path: accountWarrantNumber, type: string,  default: "012/26/00123" }
"Bail Amount":            { path: accountBailAmount,    type: number,  default: 500.00 }
"Days Before Release of Warrant": { path: daysBeforeReleaseWarrant, type: integer, default: 14 }
"Clamping Contractor name":
  { path: warrantContactDetails.warrantContactDetailsLine1, type: string, default: "Clamping Contractor Ltd" }
"Contractor's Name":
  { path: warrantContactDetails.warrantContactDetailsLine2, type: string, default: "Enforcement Contractor Ltd" }
"Contractor's Address":
  { path: warrantContactDetails.warrantContactDetailsLine3, type: string, default: "1 Contractor Way, Testville" }
"Process Server Name":
  { path: warrantContactDetails.warrantContactDetailsLine1, type: string, default: "Process Server Ltd" }
"Parent Name and Address":
  { path: defendant.parentGuardian.parentGuardianName, type: string, default: "Parent Guardian" }
"<Notes 1..3>":
  { path: defendant.accountNotes.accountNote1, type: string, default: "Simulator generated note" }

# Labels with no property in v0.3.0 — emitted nowhere. See spec §6.2 and OQ-7.
"Date of Offence":        { unmapped: true, reason: "dateIssued is the NTO issue date, not the offence date" }
"Start time of offence":  { unmapped: true, reason: "schema has a single timeOfOffence, not a range" }
"End time of offence":    { unmapped: true, reason: "no property in v0.3.0" }
"Reserve Terms":          { unmapped: true, reason: "no property in v0.3.0" }
"Reason for decision":    { unmapped: true, reason: "accountNotes are free text, not a decision reason" }
"[Directions]":           { unmapped: true, reason: "no property in v0.3.0; optional in the CIMD-4372 table" }
```

Create `enforcement-workflow-simulator/src/main/resources/enforcement-workflow-simulator/catalogue/result-codes.yaml` — transcribe **all 30 rows** of the CIMD-4372 "Result vs Required Field" table. The 20 postable rows, the aliases, and the gap rows are shown in full; the 10 non-enum rows are transcribed with `postable: false`:

```yaml
ABDC:   { fields: ["Account No.", "Total Balance", "Balance Outstanding"] }
AEO:    { fields: ["Account No.", "Total Balance", "Payment Terms", "Due Date",
                   "Instalment Amount", "Payment period", "Reserve Terms"] }
AEOC:   { fields: ["Account No.", "Total Balance", "Payment Terms"] }
BWTD:   { fields: ["Account No.", "Warrant No", "Total Balance", "Bail Amount",
                   "Days Before Release of Warrant"] }
BWTU:   { fields: ["Account No.", "Warrant No", "Total Balance", "Bail Amount",
                   "Days Before Release of Warrant", "Amount Imposed",
                   "Amount Paid or Cancelled", "Date Imposed", "Date of Offence",
                   "Time of Offence", "Payment Terms", "Due Date", "Instalment Amount",
                   "Lump Sum", "Payment period", "Reserve Terms"] }
CLAMPO: { fields: ["Account No.", "Warrant No", "Vehicle Reg. No.", "Vehicle Make",
                   "Total Balance", "Clamping Contractor name", "Contractor's Name",
                   "Contractor's Address", "Payment Terms", "Due Date",
                   "Instalment Amount", "[Directions]"] }
COLLO:  { fields: ["Account No.", "Total Balance", "Balance Outstanding", "Amount Imposed",
                   "Amount Paid or Cancelled", "Payment Terms", "Due Date",
                   "Instalment Amount", "Lump Sum"] }
CW:     { fields: ["Account No.", "Warrant No", "Total Balance", "Bail Amount",
                   "Days Before Release of Warrant", "Amount Imposed",
                   "Amount Paid or Cancelled", "Date Imposed", "Payment Terms", "Due Date",
                   "Instalment Amount", "Lump Sum"] }
CWN:    { fields: ["Account No.", "Total Balance", "Bail Amount"] }
FSN:    { fields: ["Account No.", "Total Balance"] }
MPSO:   { fields: ["Account No.", "Total Balance", "Balance Outstanding", "Payment Terms",
                   "Due Date", "Instalment Amount", "Payment period", "Reserve Terms"] }
NBWT:   { fields: ["Account No.", "Warrant No", "Total Balance", "Bail Amount",
                   "Days Before Release of Warrant", "Amount Imposed",
                   "Amount Paid or Cancelled", "Date Imposed", "Date of Offence",
                   "Time of Offence", "Payment Terms", "Due Date", "Instalment Amount",
                   "Lump Sum", "<Notes 1..3>", "[Directions]"] }
REGF:   { fields: ["Account No.", "Total Balance", "Date Imposed"] }
REM:    { fields: ["Account No.", "Total Balance"] }
S136:   { fields: ["Account No.", "Warrant No", "Total Balance", "Bail Amount",
                   "Amount Imposed", "Amount Paid or Cancelled", "Date Imposed",
                   "Date of Offence", "Time of Offence", "Place of offence", "Ticket No.",
                   "Vehicle Reg. No."] }
SC:     { fields: ["Account No.", "Total Balance", "Amount Imposed", "Date Imposed",
                   "Payment Terms", "Due Date"] }
SUMM:   { fields: ["Account No.", "Amount Imposed", "Amount Paid or Cancelled",
                   "Date Imposed", "Date of Offence", "Start time of offence",
                   "End time of offence", "Imposition type",
                   "NTO issued on <date> & <time>", "Ticket No.", "Total Balance",
                   "Vehicle Reg. No."] }
TFOOUT: { fields: ["Total Balance"] }
WC:     { fields: ["Account No.", "Warrant No", "Total Balance", "Amount Imposed",
                   "Amount Paid or Cancelled", "Date Imposed", "Date of Offence",
                   "Time of Offence", "Place of offence", "Ticket No.", "Vehicle Reg. No.",
                   "Vehicle Make", "Process Server Name"] }

# GOB/CP synonyms for the same outcome — both spellings are in the resultCode enum.
DW:     { alias: WC }        # DW (GOB) = WC (CP)
TFOUT:  { alias: TFOOUT }    # TFOOUT (GOB) = TFOUT (CP)
WWDN:   { alias: WDN }       # WDN (GOB) = WWDN (CP)

# Postable per the enum, but CIMD-4372 defines no required fields. See spec OQ-2.
NOENF:  { fields: [], note: "No row in the CIMD-4372 Result vs Required Field table" }
WDN:    { fields: [], note: "No row in the CIMD-4372 Result vs Required Field table" }

# In the CIMD-4372 table but absent from the v0.3.0 resultCode enum, so CP cannot
# post them. Transcribed for a future spec version. See spec OQ-3.
ACF:    { postable: false, fields: ["Account No.", "Amount Imposed", "Date of Offence",
                   "Time of Offence", "Place of offence", "Vehicle Reg. No.", "Vehicle Make",
                   "Ticket No.", "NTO issued on <date> & <time>", "Payment Terms", "Due Date",
                   "Instalment Amount", "Lump Sum", "[Directions]"] }
AEC:    { postable: false, fields: ["Account No.", "Total Balance"] }
CLAMPS: { postable: false, fields: ["Account No.", "Warrant No", "Vehicle Reg. No.",
                   "Vehicle Make", "Total Balance", "Clamping Contractor name",
                   "Contractor's Name", "Contractor's Address"] }
FIDIC:  { postable: false, fields: ["Account No.", "Amount Imposed", "Date of Offence",
                   "Time of Offence", "Start time of offence", "End time of offence",
                   "Place of offence", "Vehicle Reg. No.", "Vehicle Make", "Ticket No.",
                   "NTO issued on <date> & <time>", "[Directions]"] }
FIDICT: { postable: false, alias: FIDIC }
FTTP:   { postable: false, fields: ["Account No.", "Total Balance"] }
LATG:   { postable: false, fields: ["Account No."] }
LATR:   { postable: false, alias: LATG }
PGPAY:  { postable: false, fields: ["Account No.", "Total Balance", "Amount Imposed",
                   "Amount Paid or Cancelled", "Date Imposed", "Date of Offence",
                   "Time of Offence", "Payment Terms", "Due Date", "Instalment Amount",
                   "Lump Sum", "Payment period", "Parent Name and Address",
                   "Reason for decision"] }
PTNV:   { postable: false, fields: ["Account No.", "Total Balance", "Amount Imposed",
                   "Amount Paid or Cancelled", "Date Imposed", "Payment Terms"] }
```

- [ ] **Step 2: Write the failing loader tests**

Create `enforcement-workflow-simulator/src/test/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/catalogue/CatalogueLoaderTest.java`:

```java
package uk.gov.hmcts.cp.enforcementworkflowsimulator.catalogue;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogueLoaderTest {

    private final Catalogue catalogue = new CatalogueLoader().load();

    @Test
    void maps_request_names_that_do_not_camel_case_to_their_real_property() {
        assertThat(catalogue.propertiesFor("Defendant Account")).containsExactly("defendant");
        assertThat(catalogue.propertiesFor("Account Offences and Penalties")).containsExactly("offences");
        assertThat(catalogue.propertiesFor("Account Terms to Pay")).containsExactly("terms");
        assertThat(catalogue.propertiesFor("CT Account Bank Details")).containsExactly("ctBankDetails");
        assertThat(catalogue.propertiesFor("Days Before Release of Warrant"))
                .containsExactly("daysBeforeReleaseWarrant");
    }

    @Test
    void maps_account_history_to_both_history_properties() {
        assertThat(catalogue.propertiesFor("Account History"))
                .containsExactlyInAnyOrder("paymentHistory", "transactionHistory");
    }

    @Test
    void resolves_gob_to_cp_result_code_aliases() {
        assertThat(catalogue.fieldsFor("DW")).isEqualTo(catalogue.fieldsFor("WC"));
        assertThat(catalogue.fieldsFor("TFOUT")).isEqualTo(catalogue.fieldsFor("TFOOUT"));
        assertThat(catalogue.fieldsFor("WWDN")).isEqualTo(catalogue.fieldsFor("WDN"));
    }

    @Test
    void returns_no_fields_for_codes_with_no_table_row() {
        assertThat(catalogue.fieldsFor("NOENF")).isEmpty();
        assertThat(catalogue.fieldsFor("WDN")).isEmpty();
        assertThat(catalogue.fieldsFor("WWDN")).isEmpty();
    }

    @Test
    void exposes_the_root_property_of_a_nested_path() {
        assertThat(catalogue.fieldPath("Balance Outstanding").rootProperty()).isEqualTo("offences");
        assertThat(catalogue.fieldPath("Payment Terms").rootProperty()).isEqualTo("terms");
        assertThat(catalogue.fieldPath("Total Balance").rootProperty()).isEqualTo("accountBalance");
    }

    @Test
    void flags_labels_that_have_no_property_in_the_contract() {
        final List<String> unmapped =
                List.of("Date of Offence", "Start time of offence", "End time of offence",
                        "Reserve Terms", "Reason for decision", "[Directions]");
        for (final String label : unmapped) {
            assertThat(catalogue.fieldPath(label).unmapped())
                    .as("label %s must be marked unmapped", label).isTrue();
            assertThat(catalogue.fieldPath(label).reason())
                    .as("label %s must carry a reason", label).isNotBlank();
        }
    }
}
```

Create `enforcement-workflow-simulator/src/test/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/catalogue/CatalogueCoverageTest.java`:

```java
package uk.gov.hmcts.cp.enforcementworkflowsimulator.catalogue;

import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the catalogue against drifting away from the v0.3.0 resultCode enum. */
class CatalogueCoverageTest {

    /** Every value of the resultCode enum in libra-gateway-hearing-events-v0.3.0.yml. */
    private static final Set<String> ENUM_CODES = Set.of(
            "ABDC", "AEO", "AEOC", "BWTD", "BWTU", "CLAMPO", "COLLO", "CW", "CWN", "DW",
            "FSN", "MPSO", "NBWT", "NOENF", "REGF", "REM", "S136", "SC", "SUMM", "TFOOUT",
            "WDN", "TFOUT", "WC", "WWDN");

    /** In the CIMD-4372 table but absent from the enum — CP cannot post these. Spec OQ-3. */
    private static final Set<String> NOT_POSTABLE = Set.of(
            "ACF", "AEC", "CLAMPS", "FIDIC", "FIDICT", "FTTP", "LATG", "LATR", "PGPAY", "PTNV");

    private final Catalogue catalogue = new CatalogueLoader().load();

    @Test
    void every_postable_result_code_is_known_to_the_catalogue() {
        for (final String code : ENUM_CODES) {
            assertThat(catalogue.isKnown(code)).as("resultCode %s must be in the catalogue", code).isTrue();
        }
    }

    @Test
    void codes_absent_from_the_enum_are_marked_unpostable() {
        for (final String code : NOT_POSTABLE) {
            assertThat(catalogue.isPostable(code))
                    .as("%s is not in the v0.3.0 enum and must be marked postable: false", code)
                    .isFalse();
        }
    }

    @Test
    void every_field_label_used_by_a_result_code_has_a_field_path_entry() {
        for (final String code : catalogue.allCodes()) {
            for (final String label : catalogue.fieldsFor(code)) {
                assertThat(catalogue.fieldPath(label))
                        .as("code %s references label '%s' with no field-paths entry", code, label)
                        .isNotNull();
            }
        }
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :enforcement-workflow-simulator:test --tests '*Catalogue*'`
Expected: FAIL — `CatalogueLoader` and `Catalogue` do not exist (compilation error).

- [ ] **Step 4: Write the catalogue types and loader**

Create `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/catalogue/FieldPath.java`:

```java
package uk.gov.hmcts.cp.enforcementworkflowsimulator.catalogue;

/**
 * One row of field-paths.yaml: a CIMD-4372 field label mapped onto a JSON path within
 * NowsDataItems, or explicitly marked as having no home in the v0.3.0 contract.
 */
public record FieldPath(
        String label,
        String path,
        String type,
        Object defaultValue,
        boolean unmapped,
        String reason) {

    /** The top-level NowsDataItems property this path writes into, e.g. {@code offences}. */
    public String rootProperty() {
        if (unmapped) {
            throw new IllegalStateException("Label '" + label + "' is unmapped and has no root property");
        }
        final int dot = path.indexOf('.');
        final int bracket = path.indexOf('[');
        int end = path.length();
        if (dot >= 0) {
            end = Math.min(end, dot);
        }
        if (bracket >= 0) {
            end = Math.min(end, bracket);
        }
        return path.substring(0, end);
    }
}
```

Create `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/catalogue/ResultCodeEntry.java`:

```java
package uk.gov.hmcts.cp.enforcementworkflowsimulator.catalogue;

import java.util.List;

/**
 * One row of result-codes.yaml. Exactly one of {@code fields} or {@code alias} is meaningful:
 * an alias row defers entirely to the code it names.
 */
public record ResultCodeEntry(
        String code,
        List<String> fields,
        String alias,
        boolean postable,
        String note) {
}
```

Create `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/catalogue/Catalogue.java`:

```java
package uk.gov.hmcts.cp.enforcementworkflowsimulator.catalogue;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** The loaded, validated CIMD-4372 mapping tables. Immutable. */
public record Catalogue(
        Map<String, List<String>> entityNames,
        Map<String, FieldPath> fieldPaths,
        Map<String, ResultCodeEntry> resultCodes) {

    /** NowsDataItems properties for a requested NowsDataItemName. */
    public List<String> propertiesFor(final String nowsDataItemName) {
        final List<String> properties = entityNames.get(nowsDataItemName);
        if (properties == null) {
            throw new IllegalArgumentException("Unknown NowsDataItemName: " + nowsDataItemName);
        }
        return properties;
    }

    /** Required field labels for a result code, following any alias. */
    public List<String> fieldsFor(final String resultCode) {
        return resolve(resultCode, 0).fields();
    }

    public FieldPath fieldPath(final String label) {
        return fieldPaths.get(label);
    }

    public boolean isKnown(final String resultCode) {
        return resultCodes.containsKey(resultCode);
    }

    public boolean isPostable(final String resultCode) {
        return resultCodes.containsKey(resultCode) && resultCodes.get(resultCode).postable();
    }

    public Set<String> allCodes() {
        return resultCodes.keySet();
    }

    public Set<String> allProperties() {
        return Set.copyOf(entityNames.values().stream().flatMap(List::stream).toList());
    }

    private ResultCodeEntry resolve(final String resultCode, final int depth) {
        if (depth > entityNames.size() + resultCodes.size()) {
            throw new IllegalStateException("Alias cycle detected resolving result code: " + resultCode);
        }
        final ResultCodeEntry entry = resultCodes.get(resultCode);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown resultCode: " + resultCode);
        }
        return entry.alias() == null ? entry : resolve(entry.alias(), depth + 1);
    }
}
```

Create `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/catalogue/CatalogueLoader.java`:

```java
package uk.gov.hmcts.cp.enforcementworkflowsimulator.catalogue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.core.type.TypeReference;

import org.springframework.stereotype.Component;

/**
 * Loads the three catalogue resources and validates them against each other. Any inconsistency is a
 * startup failure — the catalogue must never silently return a wrong or empty answer.
 */
@Component
public class CatalogueLoader {

    private static final String BASE = "enforcement-workflow-simulator/catalogue/";
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public Catalogue load() {
        final Map<String, List<String>> entityNames =
                read("entity-names.yaml", new TypeReference<LinkedHashMap<String, List<String>>>() { });
        final Map<String, Map<String, Object>> rawPaths =
                read("field-paths.yaml", new TypeReference<LinkedHashMap<String, Map<String, Object>>>() { });
        final Map<String, Map<String, Object>> rawCodes =
                read("result-codes.yaml", new TypeReference<LinkedHashMap<String, Map<String, Object>>>() { });

        final Map<String, FieldPath> fieldPaths = new LinkedHashMap<>();
        rawPaths.forEach((label, row) -> fieldPaths.put(label, toFieldPath(label, row)));

        final Map<String, ResultCodeEntry> resultCodes = new LinkedHashMap<>();
        rawCodes.forEach((code, row) -> resultCodes.put(code, toResultCode(code, row)));

        final Catalogue catalogue = new Catalogue(
                Map.copyOf(entityNames), Map.copyOf(fieldPaths), Map.copyOf(resultCodes));
        validate(catalogue);
        return catalogue;
    }

    @SuppressWarnings("unchecked")
    private FieldPath toFieldPath(final String label, final Map<String, Object> row) {
        final boolean unmapped = Boolean.TRUE.equals(row.get("unmapped"));
        return new FieldPath(
                label,
                (String) row.get("path"),
                (String) row.get("type"),
                row.get("default"),
                unmapped,
                (String) row.get("reason"));
    }

    @SuppressWarnings("unchecked")
    private ResultCodeEntry toResultCode(final String code, final Map<String, Object> row) {
        final List<String> fields = (List<String>) row.getOrDefault("fields", List.of());
        return new ResultCodeEntry(
                code,
                List.copyOf(fields),
                (String) row.get("alias"),
                !Boolean.FALSE.equals(row.get("postable")),
                (String) row.get("note"));
    }

    private <T> T read(final String name, final TypeReference<T> type) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(BASE + name)) {
            if (in == null) {
                throw new IllegalStateException("Catalogue resource not found: " + BASE + name);
            }
            return yaml.readValue(in, type);
        } catch (final IOException e) {
            throw new IllegalStateException("Failed to read catalogue resource: " + BASE + name, e);
        }
    }

    /** Spec §6.5 — every failure mode here aborts startup. */
    private void validate(final Catalogue catalogue) {
        final List<String> errors = new ArrayList<>();

        catalogue.fieldPaths().forEach((label, fieldPath) -> {
            if (fieldPath.unmapped()) {
                if (fieldPath.reason() == null || fieldPath.reason().isBlank()) {
                    errors.add("Unmapped label '" + label + "' has no reason");
                }
            } else if (fieldPath.path() == null || fieldPath.path().isBlank()) {
                errors.add("Label '" + label + "' has neither a path nor unmapped: true");
            }
        });

        catalogue.resultCodes().forEach((code, entry) -> {
            if (entry.alias() != null && !catalogue.resultCodes().containsKey(entry.alias())) {
                errors.add("Result code '" + code + "' aliases unknown code '" + entry.alias() + "'");
            }
            entry.fields().stream()
                    .filter(label -> !catalogue.fieldPaths().containsKey(label))
                    .forEach(label -> errors.add(
                            "Result code '" + code + "' references unknown field label '" + label + "'"));
        });

        catalogue.allCodes().forEach(code -> {
            try {
                catalogue.fieldsFor(code);
            } catch (final IllegalStateException | IllegalArgumentException e) {
                errors.add("Result code '" + code + "' does not resolve: " + e.getMessage());
            }
        });

        if (!errors.isEmpty()) {
            throw new IllegalStateException("Invalid GOB simulator catalogue:\n  " + String.join("\n  ", errors));
        }
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :enforcement-workflow-simulator:test --tests '*Catalogue*'`
Expected: PASS — 9 tests.

- [ ] **Step 6: Commit**

```bash
git add enforcement-workflow-simulator
git commit -m "feat(CIMD-4372): add result-code catalogue with startup validation"
```

---

### Task 4: NowsDataItems response records

The typed response model. Every property name mirrors v0.3.0 exactly, including its inconsistent casing and its one spelling mistake.

**Files:**
- Create: `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/model/NowsDataItems.java`
- Create: `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/model/nows/Defendant.java`
- Create: `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/model/nows/DefAddress.java`
- Create: `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/model/nows/AccountNotes.java`
- Create: `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/model/nows/ImposingCourt.java`
- Create: `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/model/nows/ParentGuardian.java`
- Create: `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/model/nows/ParentGuardianAddress.java`
- Create: `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/model/nows/Offences.java`
- Create: `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/model/nows/Offence.java`
- Create: `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/model/nows/Impositions.java`
- Create: `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/model/nows/Imposition.java`
- Create: `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/model/nows/Terms.java`
- Create: `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/model/nows/WarrantContactDetails.java`
- Create: `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/model/nows/CtBankDetails.java`
- Create: `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/model/nows/PaymentHistory.java`
- Create: `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/model/nows/Payment.java`
- Create: `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/model/nows/TransactionHistory.java`
- Create: `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/model/nows/Transaction.java`
- Test: `enforcement-workflow-simulator/src/test/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/model/NowsDataItemsSerialisationTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `NowsDataItems` with the 13 components listed in Step 3 below. Later tasks build a `Map<String, Object>` tree and call `objectMapper.convertValue(tree, NowsDataItems.class)`, so the component names here **are** the catalogue's path vocabulary.

- [ ] **Step 1: Write the failing serialisation test**

Create `enforcement-workflow-simulator/src/test/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/model/NowsDataItemsSerialisationTest.java`:

```java
package uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model;

import java.math.BigDecimal;
import java.util.Map;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model.nows.Offences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NowsDataItemsSerialisationTest {

    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Test
    void omits_properties_that_were_not_requested() throws Exception {
        final NowsDataItems items = mapper.convertValue(
                Map.of("accountNumber", "ACC0001"), NowsDataItems.class);

        assertThat(mapper.writeValueAsString(items)).isEqualTo("{\"accountNumber\":\"ACC0001\"}");
    }

    @Test
    void keeps_two_decimal_places_on_monetary_values() throws Exception {
        final NowsDataItems items = mapper.convertValue(
                Map.of("accountBalance", new BigDecimal("1250.00")), NowsDataItems.class);

        assertThat(mapper.writeValueAsString(items)).isEqualTo("{\"accountBalance\":1250.00}");
    }

    @Test
    void rejects_a_property_the_contract_does_not_declare() {
        assertThatThrownBy(() -> mapper.convertValue(
                Map.of("defendantAccount", Map.of("accountNo", "ACC0042")), NowsDataItems.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defendantAccount");
    }

    @Test
    void preserves_the_contract_spelling_of_the_payment_period_term() throws Exception {
        final NowsDataItems items = mapper.convertValue(
                Map.of("terms", Map.of("english_instalmetPaymentPeriod", "Monthly")),
                NowsDataItems.class);

        assertThat(mapper.writeValueAsString(items))
                .contains("english_instalmetPaymentPeriod")
                .doesNotContain("english_instalmentPaymentPeriod");
    }

    @Test
    void nests_offence_totals_under_offences() throws Exception {
        final NowsDataItems items = mapper.convertValue(
                Map.of("offences", Map.of("accountTotal", new BigDecimal("875.50"))),
                NowsDataItems.class);

        assertThat(items.offences()).isNotNull();
        assertThat(items.offences().accountTotal()).isEqualByComparingTo("875.50");
        assertThat(mapper.writeValueAsString(items)).isEqualTo("{\"offences\":{\"accountTotal\":875.50}}");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :enforcement-workflow-simulator:test --tests '*NowsDataItemsSerialisationTest'`
Expected: FAIL — `NowsDataItems` does not exist (compilation error).

- [ ] **Step 3: Write the top-level record**

Create `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/model/NowsDataItems.java`:

```java
package uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

import uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model.nows.CtBankDetails;
import uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model.nows.Defendant;
import uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model.nows.Offences;
import uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model.nows.PaymentHistory;
import uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model.nows.Terms;
import uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model.nows.TransactionHistory;
import uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model.nows.WarrantContactDetails;

/**
 * The NOWS logical entities returned by GoB. Component names mirror
 * {@code NowsDataItems} in libra-gateway-hearing-events-openapi-v0.3.0.yml exactly; the schema is
 * {@code additionalProperties: false}, so nothing outside this list may ever be emitted.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NowsDataItems(
        Defendant defendant,
        PaymentHistory paymentHistory,
        TransactionHistory transactionHistory,
        Offences offences,
        Terms terms,
        BigDecimal accountBalance,
        String accountWarrantNumber,
        WarrantContactDetails warrantContactDetails,
        BigDecimal accountBailAmount,
        CtBankDetails ctBankDetails,
        Integer daysBeforeReleaseWarrant,
        String accountNumber,
        String accountDateImposed) {
}
```

- [ ] **Step 4: Write the nested records**

Every record in `…/api/model/nows/` carries `@JsonInclude(JsonInclude.Include.NON_NULL)` and mirrors its v0.3.0 schema component. Component names are copied from the spec verbatim — including `DoB`, the `english_` / `cymraeg_` prefixes, `ct_account_number`, and the spec's own misspelling `english_instalmetPaymentPeriod`. Monetary components are `BigDecimal`; counts are `Integer`.

```java
// Defendant.java — schema component "Defendant"
public record Defendant(String defName, String nationalInsuranceNumber, String DoB,
        String homeTelNo, String businessTelNo, String mobileTelNo, String email1, String email2,
        String assetVehicleReg, String assetVehicleMake, String alias1, String alias2,
        String alias3, String alias4, String alias5, DefAddress defAddress,
        AccountNotes accountNotes, ImposingCourt imposingCourt, ParentGuardian parentGuardian,
        Integer daysInDefault) { }

// DefAddress.java
public record DefAddress(String defAddressLine1, String defAddressLine2,
        String defAddressLine3, String defPostcode) { }

// AccountNotes.java
public record AccountNotes(String accountNote1, String accountNote2, String accountNote3) { }

// ImposingCourt.java
public record ImposingCourt(Integer ljaCode, String courtName) { }

// ParentGuardian.java
public record ParentGuardian(String parentToPayFlag, String parentGuardianName,
        ParentGuardianAddress parentGuardianAddress) { }

// ParentGuardianAddress.java
public record ParentGuardianAddress(String parentGuardianAddressLine1,
        String parentGuardianAddressLine2, String parentGuardianAddressLine3) { }

// Offences.java
public record Offences(List<Offence> offence, BigDecimal accountTotal, BigDecimal accountPaid) { }

// Offence.java
public record Offence(String dateImposed, String caseNumber, String offenceCode,
        String offenceTitle, String cymraeg_offenceTitle, String ticketNumber,
        String centralTicketOfficeName, String vehicleReg, String timeOfOffence,
        String placeOfOffence, String noticeToOwnerNoticeToHirer, String dateIssued,
        String licenceNo, Impositions impositions, BigDecimal offenceTotal) { }

// Impositions.java
public record Impositions(List<Imposition> imposition) { }

// Imposition.java  (creditor omitted — no CIMD-4372 field label maps into it)
public record Imposition(String impositionCode, String impositionType, String impositionText,
        String cymraeg_impositionText, BigDecimal amountImposed, BigDecimal amountPaid,
        BigDecimal impositionBalance) { }

// Terms.java
public record Terms(String english_due, String english_instalment, String english_lumpsum,
        String english_instalmetPaymentPeriod, String english_firstDate, String cymraeg_due,
        String cymraeg_instalment, String cymraeg_lumpsum, String cymraeg_instalmentPaymentPeriod,
        String cymraeg_firstDate) { }

// WarrantContactDetails.java
public record WarrantContactDetails(String warrantContactDetailsLine1,
        String warrantContactDetailsLine2, String warrantContactDetailsLine3,
        String warrantContactDetailsLine4, String warrantContactDetailsLine5) { }

// CtBankDetails.java
public record CtBankDetails(Integer ct_account_number, Integer ct_sort_code) { }

// PaymentHistory.java
public record PaymentHistory(List<Payment> payment) { }

// Payment.java
public record Payment(String paymentDate, String paymentEventCode, String paymentType,
        BigDecimal paymentAmount, String creditOrDebit) { }

// TransactionHistory.java
public record TransactionHistory(List<Transaction> transaction) { }

// Transaction.java
public record Transaction(String accountDate, String accountEventCode,
        String accountEventDetails) { }
```

Each goes in its own file under `uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model.nows`, with the package declaration, the `@JsonInclude(JsonInclude.Include.NON_NULL)` annotation, and imports for `java.math.BigDecimal` / `java.util.List` where used.

> PMD may flag the non-camelCase component names. Suppress narrowly on the affected records with `@SuppressWarnings("PMD.FieldNamingConventions")` and the comment `// mirrors libra-gateway-hearing-events-v0.3.0.yml verbatim` — do **not** rename them, and do not relax the shared ruleset.

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :enforcement-workflow-simulator:test --tests '*NowsDataItemsSerialisationTest'`
Expected: PASS — 5 tests.

- [ ] **Step 6: Commit**

```bash
git add enforcement-workflow-simulator
git commit -m "feat(CIMD-4372): add NowsDataItems response records mirroring v0.3.0"
```

---

### Task 5: Seed store and value resolution

Resolves a value for a given path: seeded per `caseUrn` if present, otherwise the catalogue's deterministic default.

**Files:**
- Create: `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/seed/SeedStore.java`
- Create: `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/seed/ValueResolver.java`
- Create: `enforcement-workflow-simulator/src/main/resources/enforcement-workflow-simulator/seeds/E011122334.json`
- Test: `enforcement-workflow-simulator/src/test/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/seed/SeedStoreTest.java`
- Test: `enforcement-workflow-simulator/src/test/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/seed/ValueResolverTest.java`

**Interfaces:**
- Consumes: `Catalogue.fieldPath(String)` and `FieldPath.defaultValue()` (Task 3).
- Produces:
  - `SeedStore` (`@Component`) with `Optional<JsonNode> seedFor(String caseUrn)`
  - `ValueResolver` (`@Component`) with `Object resolve(String caseUrn, FieldPath fieldPath)` — returns the seeded value at the field's path when present, else the catalogue default, coercing `number` to `BigDecimal` with scale 2

- [ ] **Step 1: Write the failing tests**

Create `enforcement-workflow-simulator/src/test/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/seed/SeedStoreTest.java`:

```java
package uk.gov.hmcts.cp.enforcementworkflowsimulator.seed;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SeedStoreTest {

    private final SeedStore store = new SeedStore("");

    @Test
    void finds_a_seed_bundled_on_the_classpath() {
        assertThat(store.seedFor("E011122334")).isPresent();
    }

    @Test
    void returns_empty_for_an_unseeded_case_urn() {
        assertThat(store.seedFor("E999999999")).isEmpty();
    }

    @Test
    void does_not_confuse_case_urns_with_path_traversal() {
        assertThat(store.seedFor("../../application")).isEmpty();
    }
}
```

Create `enforcement-workflow-simulator/src/test/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/seed/ValueResolverTest.java`:

```java
package uk.gov.hmcts.cp.enforcementworkflowsimulator.seed;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import uk.gov.hmcts.cp.enforcementworkflowsimulator.catalogue.Catalogue;
import uk.gov.hmcts.cp.enforcementworkflowsimulator.catalogue.CatalogueLoader;

import static org.assertj.core.api.Assertions.assertThat;

class ValueResolverTest {

    private final Catalogue catalogue = new CatalogueLoader().load();
    private final ValueResolver resolver = new ValueResolver(new SeedStore(""));

    @Test
    void falls_back_to_the_catalogue_default_when_the_case_urn_is_unseeded() {
        assertThat(resolver.resolve("E999999999", catalogue.fieldPath("Account No.")))
                .isEqualTo("ACC0001");
    }

    @Test
    void returns_monetary_defaults_as_big_decimal_with_two_decimal_places() {
        final Object balance = resolver.resolve("E999999999", catalogue.fieldPath("Total Balance"));

        assertThat(balance).isInstanceOf(BigDecimal.class);
        assertThat(((BigDecimal) balance).scale()).isEqualTo(2);
        assertThat(balance).isEqualTo(new BigDecimal("1250.00"));
    }

    @Test
    void uses_the_warrant_number_format_the_contract_declares_not_the_ticket_example() {
        assertThat(resolver.resolve("E999999999", catalogue.fieldPath("Warrant No")))
                .isEqualTo("012/26/00123")
                .asString().matches("^\\d{3}/\\d{2}/\\d{5}$");
    }

    @Test
    void uses_the_date_format_the_contract_declares_not_the_ticket_example() {
        assertThat(resolver.resolve("E999999999", catalogue.fieldPath("Date Imposed")))
                .isEqualTo("15 Jan 2026");
    }

    @Test
    void prefers_a_seeded_value_over_the_default() {
        assertThat(resolver.resolve("E011122334", catalogue.fieldPath("Account No.")))
                .isEqualTo("ACC9001");
    }

    @Test
    void reads_a_seeded_value_from_a_nested_path() {
        assertThat(resolver.resolve("E011122334", catalogue.fieldPath("Balance Outstanding")))
                .isEqualTo(new BigDecimal("340.00"));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :enforcement-workflow-simulator:test --tests '*SeedStoreTest' --tests '*ValueResolverTest'`
Expected: FAIL — `SeedStore` and `ValueResolver` do not exist (compilation error).

- [ ] **Step 3: Write the seed file**

Create `enforcement-workflow-simulator/src/main/resources/enforcement-workflow-simulator/seeds/E011122334.json` — a partial `NowsDataItems` tree. Values are invented; no real defendant, case, or court reference appears:

```json
{
  "accountNumber": "ACC9001",
  "accountBalance": 340.00,
  "offences": {
    "accountTotal": 340.00,
    "accountPaid": 0.00
  },
  "defendant": {
    "defName": "Simulated Defendant",
    "defAddress": {
      "defAddressLine1": "1 Example Street",
      "defAddressLine3": "Testville",
      "defPostcode": "ZZ1 1ZZ"
    }
  }
}
```

- [ ] **Step 4: Write the seed store and value resolver**

Create `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/seed/SeedStore.java`:

```java
package uk.gov.hmcts.cp.enforcementworkflowsimulator.seed;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Loads per-caseUrn stub data. Bundled seeds live on the classpath; an external directory set via
 * {@code ENFORCEMENT_WORKFLOW_SIMULATOR_SEED_DIR} takes precedence so an environment can carry its own data.
 */
@Slf4j
@Component
public class SeedStore {

    /** Case URNs are E followed by 9 digits; anything else cannot name a seed file. */
    private static final Pattern CASE_URN = Pattern.compile("^[A-Za-z0-9-]{1,36}$");
    private static final String CLASSPATH_BASE = "enforcement-workflow-simulator/seeds/";

    private final ObjectMapper mapper = new ObjectMapper();
    private final String seedDir;

    public SeedStore(@Value("${enforcement-workflow-simulator.seed-dir:}") final String seedDir) {
        this.seedDir = seedDir;
    }

    public Optional<JsonNode> seedFor(final String caseUrn) {
        if (caseUrn == null || !CASE_URN.matcher(caseUrn).matches()) {
            return Optional.empty();
        }
        return externalSeed(caseUrn).or(() -> classpathSeed(caseUrn));
    }

    private Optional<JsonNode> externalSeed(final String caseUrn) {
        if (seedDir == null || seedDir.isBlank()) {
            return Optional.empty();
        }
        final Path file = Path.of(seedDir).resolve(caseUrn + ".json");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readTree(file.toFile()));
        } catch (final IOException e) {
            log.warn("Ignoring unreadable seed file: caseUrn={}, file={}", caseUrn, file, e);
            return Optional.empty();
        }
    }

    private Optional<JsonNode> classpathSeed(final String caseUrn) {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream(CLASSPATH_BASE + caseUrn + ".json")) {
            return in == null ? Optional.empty() : Optional.of(mapper.readTree(in));
        } catch (final IOException e) {
            log.warn("Ignoring unreadable bundled seed: caseUrn={}", caseUrn, e);
            return Optional.empty();
        }
    }
}
```

Create `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/seed/ValueResolver.java`:

```java
package uk.gov.hmcts.cp.enforcementworkflowsimulator.seed;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.enforcementworkflowsimulator.catalogue.FieldPath;

/** Resolves the value for one field: seeded if available, otherwise the catalogue default. */
@Component
public class ValueResolver {

    private static final int MONEY_SCALE = 2;

    private final SeedStore seedStore;

    public ValueResolver(final SeedStore seedStore) {
        this.seedStore = seedStore;
    }

    public Object resolve(final String caseUrn, final FieldPath fieldPath) {
        if (fieldPath.unmapped()) {
            throw new IllegalArgumentException(
                    "Cannot resolve a value for unmapped label: " + fieldPath.label());
        }
        return seededValue(caseUrn, fieldPath)
                .orElseGet(() -> coerce(fieldPath.defaultValue(), fieldPath.type()));
    }

    private Optional<Object> seededValue(final String caseUrn, final FieldPath fieldPath) {
        return seedStore.seedFor(caseUrn)
                .map(seed -> seed.at(jsonPointer(fieldPath.path())))
                .filter(node -> !node.isMissingNode() && !node.isNull())
                .map(node -> coerce(toJava(node), fieldPath.type()));
    }

    /** Converts {@code offences.offence[0].timeOfOffence} to {@code /offences/offence/0/timeOfOffence}. */
    private String jsonPointer(final String path) {
        return "/" + path.replace("[", ".").replace("]", "").replace('.', '/');
    }

    private Object toJava(final JsonNode node) {
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.asText();
    }

    private Object coerce(final Object value, final String type) {
        if (value == null) {
            return null;
        }
        return switch (type) {
            case "number" -> new BigDecimal(value.toString()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            case "integer" -> Integer.valueOf(value.toString());
            default -> value.toString();
        };
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :enforcement-workflow-simulator:test --tests '*SeedStoreTest' --tests '*ValueResolverTest'`
Expected: PASS — 9 tests.

- [ ] **Step 6: Commit**

```bash
git add enforcement-workflow-simulator
git commit -m "feat(CIMD-4372): add seed store and deterministic value resolution"
```

---

### Task 6: The assembly engine

Unions required field labels across posted result codes, filters them to the requested entities, and builds the response tree.

**Files:**
- Create: `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/assembly/NowsDataItemsAssembler.java`
- Test: `enforcement-workflow-simulator/src/test/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/assembly/NowsDataItemsAssemblerTest.java`

**Interfaces:**
- Consumes: `Catalogue` (Task 3), `ValueResolver.resolve(String, FieldPath)` (Task 5), `NowsDataItems` (Task 4).
- Produces: `NowsDataItemsAssembler.assemble(String caseUrn, List<String> resultCodes, List<String> requestedNames)` returning `NowsDataItems`.

- [ ] **Step 1: Write the failing engine test**

Create `enforcement-workflow-simulator/src/test/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/assembly/NowsDataItemsAssemblerTest.java`:

```java
package uk.gov.hmcts.cp.enforcementworkflowsimulator.assembly;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model.NowsDataItems;
import uk.gov.hmcts.cp.enforcementworkflowsimulator.catalogue.CatalogueLoader;
import uk.gov.hmcts.cp.enforcementworkflowsimulator.seed.SeedStore;
import uk.gov.hmcts.cp.enforcementworkflowsimulator.seed.ValueResolver;

import static org.assertj.core.api.Assertions.assertThat;

class NowsDataItemsAssemblerTest {

    private final NowsDataItemsAssembler assembler = new NowsDataItemsAssembler(
            new CatalogueLoader().load(),
            new ValueResolver(new SeedStore("")),
            new ObjectMapper());

    @Test
    void returns_only_the_entities_that_were_requested() {
        final NowsDataItems items = assembler.assemble(
                "E999999999", List.of("SC"), List.of("Account Balance", "Account Number"));

        assertThat(items.accountBalance()).isEqualByComparingTo("1250.00");
        assertThat(items.accountNumber()).isEqualTo("ACC0001");
        assertThat(items.defendant()).isNull();
        assertThat(items.terms()).isNull();
    }

    @Test
    void emits_a_requested_entity_even_when_no_posted_code_contributes_a_field_to_it() {
        // FSN requires only Account No. and Total Balance — nothing lands in warrantContactDetails.
        final NowsDataItems items = assembler.assemble(
                "E999999999", List.of("FSN"), List.of("Warrant Contact Details"));

        assertThat(items.warrantContactDetails())
                .as("AC2 forbids missing keys — the entity must still be present")
                .isNotNull();
    }

    @Test
    void unions_required_fields_across_several_posted_codes() {
        // SC contributes Payment Terms and Due Date to terms; AEO adds Instalment Amount.
        final NowsDataItems items = assembler.assemble(
                "E999999999", List.of("SC", "AEO"), List.of("Account Terms to Pay"));

        assertThat(items.terms().english_due()).isNotNull();
        assertThat(items.terms().english_firstDate()).isNotNull();
        assertThat(items.terms().english_instalment()).isNotNull();
    }

    @Test
    void deduplicates_a_field_required_by_more_than_one_code() {
        final NowsDataItems both = assembler.assemble(
                "E999999999", List.of("FSN", "REM"), List.of("Account Balance"));
        final NowsDataItems one = assembler.assemble(
                "E999999999", List.of("FSN"), List.of("Account Balance"));

        assertThat(both.accountBalance()).isEqualByComparingTo(one.accountBalance());
    }

    @Test
    void resolves_gob_and_cp_spellings_of_the_same_code_identically() {
        final NowsDataItems viaDw = assembler.assemble(
                "E999999999", List.of("DW"), List.of("Account Warrant Number"));
        final NowsDataItems viaWc = assembler.assemble(
                "E999999999", List.of("WC"), List.of("Account Warrant Number"));

        assertThat(viaDw.accountWarrantNumber()).isEqualTo(viaWc.accountWarrantNumber());
    }

    @Test
    void emits_nothing_for_field_labels_that_have_no_property_in_the_contract() {
        // S136 requires "Date of Offence", which is unmapped — offences must still be valid.
        final NowsDataItems items = assembler.assemble(
                "E999999999", List.of("S136"), List.of("Account Offences and Penalties"));

        assertThat(items.offences()).isNotNull();
        assertThat(items.offences().offence()).isNotNull();
    }

    @Test
    void returns_defaults_only_for_a_code_with_no_table_row() {
        final NowsDataItems items = assembler.assemble(
                "E999999999", List.of("NOENF"), List.of("Account Balance"));

        assertThat(items.accountBalance()).isEqualByComparingTo("1250.00");
    }

    @Test
    void prefers_seeded_values_over_defaults() {
        final NowsDataItems items = assembler.assemble(
                "E011122334", List.of("ABDC"), List.of("Account Number", "Account Balance"));

        assertThat(items.accountNumber()).isEqualTo("ACC9001");
        assertThat(items.accountBalance()).isEqualByComparingTo("340.00");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :enforcement-workflow-simulator:test --tests '*NowsDataItemsAssemblerTest'`
Expected: FAIL — `NowsDataItemsAssembler` does not exist (compilation error).

- [ ] **Step 3: Write the assembler**

Create `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/assembly/NowsDataItemsAssembler.java`:

```java
package uk.gov.hmcts.cp.enforcementworkflowsimulator.assembly;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model.NowsDataItems;
import uk.gov.hmcts.cp.enforcementworkflowsimulator.catalogue.Catalogue;
import uk.gov.hmcts.cp.enforcementworkflowsimulator.catalogue.FieldPath;
import uk.gov.hmcts.cp.enforcementworkflowsimulator.seed.ValueResolver;

/**
 * Builds the NOWS response for one hearing result (spec §7).
 *
 * <p>Works on a nested {@code Map} tree and converts it to {@link NowsDataItems} at the end, so a
 * path the contract does not declare fails conversion rather than reaching CP.
 */
@Component
public class NowsDataItemsAssembler {

    private final Catalogue catalogue;
    private final ValueResolver valueResolver;
    private final ObjectMapper objectMapper;

    public NowsDataItemsAssembler(final Catalogue catalogue,
                                  final ValueResolver valueResolver,
                                  final ObjectMapper objectMapper) {
        this.catalogue = catalogue;
        this.valueResolver = valueResolver;
        this.objectMapper = objectMapper;
    }

    public NowsDataItems assemble(final String caseUrn,
                                  final List<String> resultCodes,
                                  final List<String> requestedNames) {

        final Set<String> requestedRoots = new LinkedHashSet<>();
        requestedNames.forEach(name -> requestedRoots.addAll(catalogue.propertiesFor(name)));

        final Set<String> requiredLabels = new LinkedHashSet<>();
        resultCodes.forEach(code -> requiredLabels.addAll(catalogue.fieldsFor(code)));

        final Map<String, Object> tree = new LinkedHashMap<>();
        for (final String label : requiredLabels) {
            final FieldPath fieldPath = catalogue.fieldPath(label);
            if (fieldPath.unmapped() || !requestedRoots.contains(fieldPath.rootProperty())) {
                continue;
            }
            put(tree, fieldPath.path(), valueResolver.resolve(caseUrn, fieldPath));
        }

        // AC2 — a requested entity is never missing, even when no posted code feeds it.
        for (final String root : requestedRoots) {
            tree.computeIfAbsent(root, key -> defaultFor(caseUrn, key));
        }

        return objectMapper.convertValue(tree, NowsDataItems.class);
    }

    /**
     * Minimal content for a requested entity no posted code contributed to: the first catalogue
     * default that writes into it, so schema-required fields are satisfied.
     */
    private Object defaultFor(final String caseUrn, final String rootProperty) {
        final Map<String, Object> branch = new LinkedHashMap<>();
        catalogue.fieldPaths().values().stream()
                .filter(fieldPath -> !fieldPath.unmapped())
                .filter(fieldPath -> rootProperty.equals(fieldPath.rootProperty()))
                .forEach(fieldPath -> put(branch, fieldPath.path(),
                        valueResolver.resolve(caseUrn, fieldPath)));
        final Object value = branch.get(rootProperty);
        return value == null ? new LinkedHashMap<String, Object>() : value;
    }

    /** Writes {@code value} into {@code tree} at a dotted path with optional {@code [n]} indexes. */
    @SuppressWarnings("unchecked")
    private void put(final Map<String, Object> tree, final String path, final Object value) {
        final List<String> segments = List.of(path.split("\\."));
        Object cursor = tree;

        for (int i = 0; i < segments.size(); i++) {
            final String segment = segments.get(i);
            final boolean indexed = segment.endsWith("]");
            final String name = indexed ? segment.substring(0, segment.indexOf('[')) : segment;
            final int index = indexed
                    ? Integer.parseInt(segment.substring(segment.indexOf('[') + 1, segment.length() - 1))
                    : -1;
            final boolean last = i == segments.size() - 1;

            final Map<String, Object> parent = (Map<String, Object>) cursor;
            if (indexed) {
                final List<Object> list = (List<Object>) parent.computeIfAbsent(name, k -> new ArrayList<>());
                while (list.size() <= index) {
                    list.add(new LinkedHashMap<String, Object>());
                }
                if (last) {
                    list.set(index, value);
                } else {
                    cursor = list.get(index);
                }
            } else if (last) {
                parent.put(name, value);
            } else {
                cursor = parent.computeIfAbsent(name, k -> new LinkedHashMap<String, Object>());
            }
        }
    }
}
```

Expose the loaded catalogue as a bean. Create `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/catalogue/CatalogueConfiguration.java`:

```java
package uk.gov.hmcts.cp.enforcementworkflowsimulator.catalogue;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CatalogueConfiguration {

    @Bean
    public Catalogue catalogue(final CatalogueLoader loader) {
        return loader.load();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :enforcement-workflow-simulator:test --tests '*NowsDataItemsAssemblerTest'`
Expected: PASS — 8 tests.

- [ ] **Step 5: Commit**

```bash
git add enforcement-workflow-simulator
git commit -m "feat(CIMD-4372): add NOWS data-items assembly engine"
```

---

### Task 7: POST /hearing/result with correlation, timestamp, and idempotency

Wires the engine to the endpoint and completes the response envelope.

**Files:**
- Create: `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/model/HearingResultedRequest.java`
- Create: `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/model/HearingResultedResponse.java`
- Create: `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/IdempotencyCache.java`
- Modify: `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/HearingController.java`
- Test: `enforcement-workflow-simulator/src/test/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/HearingResultControllerIT.java`

**Interfaces:**
- Consumes: `NowsDataItemsAssembler.assemble(String, List<String>, List<String>)` (Task 6), `NowsDataItems` (Task 4).
- Produces: `HearingResultedResponse(String caseUrn, String timestamp, String correlationId, NowsDataItems nowsDataItems)`; `IdempotencyCache.get(String key)` / `put(String key, HearingResultedResponse response)`.

- [ ] **Step 1: Write the failing endpoint test**

Create `enforcement-workflow-simulator/src/test/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/HearingResultControllerIT.java`:

```java
package uk.gov.hmcts.cp.enforcementworkflowsimulator.api;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.gov.hmcts.cp.enforcementworkflowsimulator.api.OpenApiConformance.conformsToSpec;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("enforcement-workflow-simulator")
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert") // MockMvc andExpect() calls are assertions
class HearingResultControllerIT {

    private static final String SC_REQUEST = """
            {
              "caseUrn": "E012345678",
              "dateOfHearing": "2026-05-03",
              "courtHearingLocation": "B01BH01",
              "defendantDetails": {
                "prosecutorDefendantId": "1234567890",
                "address1": "1 Example Street"
              },
              "paymentTerms": { "paymentDueDate": "2026-05-31" },
              "enforcement": {},
              "results": [ { "resultCode": "SC" } ],
              "nowsDataRequest": {
                "nowsDataItems": [ { "name": "Account Balance" }, { "name": "Account Number" } ]
              }
            }
            """;

    @Resource
    private MockMvc mockMvc;

    @Test
    void returns_a_schema_valid_response_for_a_suspended_committal() throws Exception {
        mockMvc.perform(post("/hearing/result").contentType(APPLICATION_JSON).content(SC_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseUrn").value("E012345678"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.nowsDataItems.accountBalance").value(1250.00))
                .andExpect(jsonPath("$.nowsDataItems.accountNumber").value("ACC0001"))
                .andExpect(conformsToSpec());
    }

    @Test
    void returns_exactly_the_requested_entities_and_nothing_more() throws Exception {
        mockMvc.perform(post("/hearing/result").contentType(APPLICATION_JSON).content(SC_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nowsDataItems.length()").value(2))
                .andExpect(jsonPath("$.nowsDataItems.defendant").doesNotExist())
                .andExpect(jsonPath("$.nowsDataItems.terms").doesNotExist());
    }

    @Test
    void echoes_the_correlation_id_when_the_caller_supplies_one() throws Exception {
        mockMvc.perform(post("/hearing/result")
                        .contentType(APPLICATION_JSON)
                        .header("X-Correlation-ID", "9f3d2e42-8d30-4d16-9dd6-6d4e26889d5c")
                        .content(SC_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correlationId").value("9f3d2e42-8d30-4d16-9dd6-6d4e26889d5c"));
    }

    @Test
    void omits_the_correlation_id_when_the_caller_supplies_none() throws Exception {
        mockMvc.perform(post("/hearing/result").contentType(APPLICATION_JSON).content(SC_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correlationId").doesNotExist());
    }

    @Test
    void returns_an_iso_8601_utc_timestamp() throws Exception {
        final String body = mockMvc.perform(
                        post("/hearing/result").contentType(APPLICATION_JSON).content(SC_REQUEST))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).containsPattern("\"timestamp\":\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z\"");
    }

    @Test
    void repeats_the_same_body_for_the_same_idempotency_key() throws Exception {
        final String first = mockMvc.perform(post("/hearing/result")
                        .contentType(APPLICATION_JSON)
                        .header("X-Idempotency-Key", "key-1")
                        .content(SC_REQUEST))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(post("/hearing/result")
                        .contentType(APPLICATION_JSON)
                        .header("X-Idempotency-Key", "key-1")
                        .content(SC_REQUEST))
                .andExpect(status().isOk())
                .andExpect(content().json(first, true));
    }

    @Test
    void issues_a_fresh_timestamp_for_a_different_idempotency_key() throws Exception {
        final String first = mockMvc.perform(post("/hearing/result")
                        .contentType(APPLICATION_JSON)
                        .header("X-Idempotency-Key", "key-A")
                        .content(SC_REQUEST))
                .andReturn().getResponse().getContentAsString();

        Thread.sleep(1100);

        final String second = mockMvc.perform(post("/hearing/result")
                        .contentType(APPLICATION_JSON)
                        .header("X-Idempotency-Key", "key-B")
                        .content(SC_REQUEST))
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void never_returns_an_enforcer_code() throws Exception {
        final String body = mockMvc.perform(post("/hearing/result")
                        .contentType(APPLICATION_JSON)
                        .content(SC_REQUEST.replace("\"Account Balance\"", "\"Defendant Account\"")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("enforcerCode");
    }

    @Test
    void rejects_a_request_carrying_a_property_the_contract_does_not_declare() throws Exception {
        mockMvc.perform(post("/hearing/result")
                        .contentType(APPLICATION_JSON)
                        .content(SC_REQUEST.replace("\"caseUrn\":", "\"unexpectedField\": 1, \"caseUrn\":")))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :enforcement-workflow-simulator:test --tests '*HearingResultControllerIT'`
Expected: FAIL — 404 on `/hearing/result`.

- [ ] **Step 3: Write the request and response models**

Create `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/model/HearingResultedRequest.java`:

```java
package uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * Only the parts of HearingResultedRequest the simulator reads are modelled; the remaining
 * mandatory objects are accepted as opaque maps so a contract-valid request is never rejected.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record HearingResultedRequest(
        @NotBlank String caseUrn,
        @NotBlank String dateOfHearing,
        @NotBlank String courtHearingLocation,
        @NotNull Map<String, Object> defendantDetails,
        Map<String, Object> employerDetails,
        Map<String, Object> parentGuardianDetails,
        @NotNull Map<String, Object> paymentTerms,
        @NotNull Map<String, Object> enforcement,
        @NotEmpty List<HearingResult> results,
        @NotNull NowsDataRequest nowsDataRequest) {

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record HearingResult(@NotBlank String resultCode, Number enforcerCode, Number jailDays) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record NowsDataRequest(@NotEmpty List<NowsDataItemRequest> nowsDataItems) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record NowsDataItemRequest(@NotBlank String name) {
    }
}
```

Create `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/model/HearingResultedResponse.java`:

```java
package uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record HearingResultedResponse(
        String caseUrn,
        String timestamp,
        String correlationId,
        NowsDataItems nowsDataItems) {
}
```

Create `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/IdempotencyCache.java`:

```java
package uk.gov.hmcts.cp.enforcementworkflowsimulator.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model.HearingResultedResponse;

/**
 * Remembers responses by X-Idempotency-Key (AC7). Bounded and in-memory: the simulator is a test
 * fixture, so surviving a restart is not required.
 */
@Component
public class IdempotencyCache {

    private static final int MAX_ENTRIES = 1000;

    private final Map<String, HearingResultedResponse> entries =
            java.util.Collections.synchronizedMap(
                    new LinkedHashMap<>(16, 0.75f, false) {
                        @Override
                        protected boolean removeEldestEntry(
                                final Map.Entry<String, HearingResultedResponse> eldest) {
                            return size() > MAX_ENTRIES;
                        }
                    });

    public Optional<HearingResultedResponse> get(final String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(entries.get(key));
    }

    public void put(final String key, final HearingResultedResponse response) {
        if (key != null) {
            entries.put(key, response);
        }
    }
}
```

- [ ] **Step 4: Add the endpoint to HearingController**

Add to `enforcement-workflow-simulator/src/main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/HearingController.java` — a constructor taking `NowsDataItemsAssembler` and `IdempotencyCache`, and:

```java
    @PostMapping(path = "/hearing/result",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public HearingResultedResponse resultHearing(
            @Valid @RequestBody final HearingResultedRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) final String correlationId,
            @RequestHeader(value = "X-Idempotency-Key", required = false) final String idempotencyKey) {

        final Optional<HearingResultedResponse> cached = idempotencyCache.get(idempotencyKey);
        if (cached.isPresent()) {
            log.info("Replaying cached response: caseUrn={}, idempotencyKey={}",
                    request.caseUrn(), idempotencyKey);
            return cached.get();
        }

        final List<String> resultCodes = request.results().stream()
                .map(HearingResultedRequest.HearingResult::resultCode)
                .toList();
        final List<String> requestedNames = request.nowsDataRequest().nowsDataItems().stream()
                .map(HearingResultedRequest.NowsDataItemRequest::name)
                .toList();

        final HearingResultedResponse response = new HearingResultedResponse(
                request.caseUrn(),
                DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS)),
                correlationId,
                assembler.assemble(request.caseUrn(), resultCodes, requestedNames));

        idempotencyCache.put(idempotencyKey, response);
        log.info("Hearing result processed: caseUrn={}, resultCodes={}, requestedItems={}",
                request.caseUrn(), resultCodes, requestedNames);
        return response;
    }
```

Add the imports the method needs: `java.time.Instant`, `java.time.format.DateTimeFormatter`, `java.time.temporal.ChronoUnit`, `java.util.List`, `java.util.Optional`, `org.springframework.web.bind.annotation.RequestHeader`, and the model and assembler types.

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :enforcement-workflow-simulator:test --tests '*HearingResultControllerIT'`
Expected: PASS — 9 tests.

- [ ] **Step 6: Commit**

```bash
git add enforcement-workflow-simulator
git commit -m "feat(CIMD-4372): add POST /hearing/result with correlation and idempotency"
```

---

### Task 8: Full result-code conformance suite

Proves AC1/AC3/AC4 across every code CP can actually post, rather than the one happy path Task 7 covers.

**Files:**
- Create: `enforcement-workflow-simulator/src/test/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/AllResultCodesConformanceIT.java`

**Interfaces:**
- Consumes: the `/hearing/result` endpoint (Task 7), `OpenApiConformance.conformsToSpec()` (Task 2).
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Write the failing parameterised conformance test**

Create `enforcement-workflow-simulator/src/test/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/api/AllResultCodesConformanceIT.java`:

```java
package uk.gov.hmcts.cp.enforcementworkflowsimulator.api;

import jakarta.annotation.Resource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.gov.hmcts.cp.enforcementworkflowsimulator.api.OpenApiConformance.conformsToSpec;

/** Every resultCode in the v0.3.0 enum must produce a schema-valid response (AC1, AC3, AC4). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("enforcement-workflow-simulator")
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert") // MockMvc andExpect() calls are assertions
class AllResultCodesConformanceIT {

    /** All twelve NowsDataItemName values — the widest request CP can make. */
    private static final String ALL_ITEMS = """
            { "name": "Defendant Account" }, { "name": "Account History" },
            { "name": "Account Offences and Penalties" }, { "name": "Account Terms to Pay" },
            { "name": "Account Balance" }, { "name": "Account Warrant Number" },
            { "name": "Warrant Contact Details" }, { "name": "Account Bail Amount" },
            { "name": "CT Account Bank Details" }, { "name": "Days Before Release of Warrant" },
            { "name": "Account Number" }, { "name": "Account Date Imposed" }
            """;

    @Resource
    private MockMvc mockMvc;

    @ParameterizedTest(name = "resultCode {0} returns a schema-valid response")
    @ValueSource(strings = {
        "ABDC", "AEO", "AEOC", "BWTD", "BWTU", "CLAMPO", "COLLO", "CW", "CWN", "DW",
        "FSN", "MPSO", "NBWT", "NOENF", "REGF", "REM", "S136", "SC", "SUMM", "TFOOUT",
        "WDN", "TFOUT", "WC", "WWDN"})
    void every_enum_result_code_returns_a_schema_valid_response(final String resultCode) throws Exception {
        mockMvc.perform(post("/hearing/result")
                        .contentType(APPLICATION_JSON)
                        .header("X-Correlation-ID", "conformance-" + resultCode)
                        .content(requestFor(resultCode)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseUrn").value("E012345678"))
                .andExpect(jsonPath("$.nowsDataItems").isNotEmpty())
                .andExpect(conformsToSpec());
    }

    @ParameterizedTest(name = "resultCode {0} returns every requested entity")
    @ValueSource(strings = {"ABDC", "BWTU", "NBWT", "S136", "SUMM", "WC", "NOENF"})
    void every_requested_entity_is_present_whatever_the_code(final String resultCode) throws Exception {
        mockMvc.perform(post("/hearing/result")
                        .contentType(APPLICATION_JSON)
                        .content(requestFor(resultCode)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nowsDataItems.defendant").exists())
                .andExpect(jsonPath("$.nowsDataItems.offences").exists())
                .andExpect(jsonPath("$.nowsDataItems.terms").exists())
                .andExpect(jsonPath("$.nowsDataItems.accountBalance").exists())
                .andExpect(jsonPath("$.nowsDataItems.accountNumber").exists())
                .andExpect(jsonPath("$.nowsDataItems.accountWarrantNumber").exists())
                .andExpect(jsonPath("$.nowsDataItems.warrantContactDetails").exists())
                .andExpect(jsonPath("$.nowsDataItems.accountBailAmount").exists())
                .andExpect(jsonPath("$.nowsDataItems.ctBankDetails").exists())
                .andExpect(jsonPath("$.nowsDataItems.daysBeforeReleaseWarrant").exists())
                .andExpect(jsonPath("$.nowsDataItems.accountDateImposed").exists())
                .andExpect(jsonPath("$.nowsDataItems.paymentHistory").exists())
                .andExpect(jsonPath("$.nowsDataItems.transactionHistory").exists());
    }

    private String requestFor(final String resultCode) {
        return """
                {
                  "caseUrn": "E012345678",
                  "dateOfHearing": "2026-05-03",
                  "courtHearingLocation": "B01BH01",
                  "defendantDetails": {
                    "prosecutorDefendantId": "1234567890",
                    "address1": "1 Example Street"
                  },
                  "paymentTerms": { "paymentDueDate": "2026-05-31" },
                  "enforcement": {},
                  "results": [ { "resultCode": "%s" } ],
                  "nowsDataRequest": { "nowsDataItems": [ %s ] }
                }
                """.formatted(resultCode, ALL_ITEMS);
    }
}
```

- [ ] **Step 2: Run the test to verify which codes fail**

Run: `./gradlew :enforcement-workflow-simulator:test --tests '*AllResultCodesConformanceIT'`
Expected: Some FAIL. Likely causes, each a real defect to fix rather than a test to weaken:
- a catalogue default that violates a schema `pattern` (e.g. `accountWarrantNumber`);
- a record component missing from `NowsDataItems`;
- an entity emitted as `{}` that has schema-required fields.

- [ ] **Step 3: Fix the defects the suite exposes**

For each failure, fix the **catalogue default or the record**, never the assertion. If an entity comes back empty because no catalogue default writes into it, add a default to `field-paths.yaml` for a field on that entity — `ctBankDetails`, `paymentHistory` and `transactionHistory` have no CIMD-4372 field label, so each needs a baseline default entry:

```yaml
"CT Account Number":  { path: ctBankDetails.ct_account_number, type: integer, default: 12345678 }
"CT Sort Code":       { path: ctBankDetails.ct_sort_code, type: integer, default: 112233 }
"Payment Date":       { path: "paymentHistory.payment[0].paymentDate", type: string, default: "01 Mar 2026" }
"Account Event Date": { path: "transactionHistory.transaction[0].accountDate", type: string, default: "01 Mar 2026" }
"Defendant Name":     { path: defendant.defName, type: string, default: "Simulated Defendant" }
"Offence Total":      { path: "offences.offence[0].offenceTotal", type: number, default: 1250.00 }
```

These labels belong to no result-code row, so they are only ever used as entity baselines.

- [ ] **Step 4: Run the full simulator suite to verify everything passes**

Run: `./gradlew :enforcement-workflow-simulator:test`
Expected: PASS — all tests green, including the 31 parameterised conformance cases.

- [ ] **Step 5: Commit**

```bash
git add enforcement-workflow-simulator
git commit -m "test(CIMD-4372): assert schema conformance for every v0.3.0 result code"
```

---

### Task 9: Documentation and pipeline artefacts

Records the decisions and gaps so the next reader — and the GOB integration team — inherits the reasoning rather than the surprise.

**Files:**
- Create: `enforcement-workflow-simulator/README.md`
- Create: `docs/pipeline/adrs/001-enforcement-workflow-simulator-subproject.md`
- Create: `docs/pipeline/adrs/002-v030-schema-over-jira-examples.md`
- Modify: `README.md` (add a "GOB simulator" section pointing at `enforcement-workflow-simulator/README.md`)

**Interfaces:**
- Consumes: everything built in Tasks 1–8.
- Produces: nothing consumed by code.

- [ ] **Step 1: Write the simulator README**

Create `enforcement-workflow-simulator/README.md` covering: what the simulator is and is not; how to run it
(`SPRING_PROFILES_ACTIVE=enforcement-workflow-simulator ./gradlew :enforcement-workflow-simulator:bootRun`, port 8091); the three
guard layers and why it cannot start in live; how to add a seed file; how to change the catalogue
when the CIMD-4372 table changes; and a prominent statement that responses follow
**v0.3.0, not the CIMD-4372 examples**, linking ADR-002.

- [ ] **Step 2: Write ADR-001**

Create `docs/pipeline/adrs/001-enforcement-workflow-simulator-subproject.md` using `skills/adr-template.md`. Context:
the HMCTS Spring Boot template assumes a single module; the simulator must never ship inside the
service artefact. Decision: a `:enforcement-workflow-simulator` Gradle subproject with its own boot jar, excluded
from publishing, plus the three `gradle/*.gradle` parameterisations from Task 1. Consequences: the
repo now has a `settings.gradle` and diverges from the template's single-module shape; the
divergence is three one-line changes, each documented in the file it touches.

- [ ] **Step 3: Write ADR-002**

Create `docs/pipeline/adrs/002-v030-schema-over-jira-examples.md`. Context: CIMD-4372's example
responses and its AC2/AC3 entity names do not validate against v0.3.0 — reproduce the 12-row
comparison table from spec §3. Decision: the schema is authoritative; AC2's camelCase rule is
re-expressed as the explicit mapping in `entity-names.yaml`. Consequences: CP integrates against a
contract that will match real Libra; the ticket's examples need correcting, or v0.4.0 needs to
adopt the nested shapes; all nine open questions from spec §12 are listed for the GOB team.

- [ ] **Step 4: Link the simulator from the service README**

Add to the root `README.md`, after the "Build & test" section:

```markdown
## GOB (Libra) simulator

A non-live simulator of the Libra Gateway hearing-event API lives in [`enforcement-workflow-simulator/`](enforcement-workflow-simulator/README.md).
It is a separate Gradle subproject producing its own boot jar; it is never published, never packaged
into the service image, and refuses to start under a live Spring profile.
```

- [ ] **Step 5: Verify the whole build is green**

Run: `./gradlew build :enforcement-workflow-simulator:build`
Expected: PASS for both projects.

- [ ] **Step 6: Commit**

```bash
git add enforcement-workflow-simulator/README.md README.md docs/pipeline/adrs
git commit -m "docs(CIMD-4372): add simulator README and ADRs 001/002"
```

---

## Deferred to follow-on work

Deliberately **not** in this plan, and not silently dropped:

- **Security** — token validation, TLS/mTLS. Excluded at your direction; the `/auth/token` endpoint
  exists but nothing checks its output.
- **Helm chart and non-live deployment** — this plan builds and tests the simulator; publishing an
  image and deploying it to a non-live AKS namespace is separate infrastructure work.
- **Seeding the remaining 20 worked examples** from the CIMD-4372 gallery. Task 5 bundles one seed
  and proves the mechanism; transcribing the rest is mechanical and belongs with QA.
- **Resolving the nine open questions** in spec §12. Each has an interim behaviour, so none blocks
  this plan, but OQ-1 (`Account History` → one property or both) and OQ-7 (six unmappable field
  labels) will cause rework if answered differently.
