plugins {
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    java
    // Plan 34-09 (TRUTH-02, #110). CORE Gradle plugin: no version coordinate, no
    // dependencies entry, no third-party supply-chain surface. Its configuration and
    // the measurement behind its floor live in the JaCoCo block near the test tasks.
    jacoco
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

// Redirect build directory to 'build-local' to avoid permission issues with the default 'build' directory
// (which is sometimes created/owned by root in this environment).
layout.buildDirectory.set(file("build-local"))

// Override the netty version managed by Spring Boot 3.5.16's BOM. Netty is not
// declared below — it arrives transitively via reactor-netty (starter-webflux)
// and software.amazon.awssdk:netty-nio-client, and every artifact is pinned by
// io.spring.dependency-management ("selected by rule" in dependencyInsight).
// Boot's documented override is this property, which re-points the imported
// netty-bom so the whole netty family moves together; forcing the two flagged
// artifacts alone would leave them out of step with their siblings.
//
// 4.1.136.Final is the exact fixed version for the Trivy image-gate findings
// CVE-2026-59901 (netty-codec, Bzip2Decoder infinite loop) and CVE-2026-55831 /
// CVE-2026-55833 / CVE-2026-56745 (netty-codec-http). Staying on 4.1.x keeps us
// on the line Boot 3.5.16 already manages — 4.2.x would be an unrequested jump.
extra["netty.version"] = "4.1.136.Final"

// Same shape, same reason, different family. Spring Boot 3.5.16's BOM pins
// httpcore5 to 5.3.6, and that pin DOWNGRADES what the AWS SDK asks for:
// software.amazon.awssdk:apache5-client:2.53.2 requests httpcore5 5.4.3 and
// httpclient5 5.6.4, and dependencyInsight shows "5.3.6 -> 5.4.3 (selected by
// rule)". So the vulnerable version is not something we or the SDK chose — it
// is Boot's managed version winning over a newer request.
//
// 5.4.3 is the exact fixed version for the Trivy image-gate findings
// CVE-2026-54399 (httpcore5) and CVE-2026-54428 (httpcore5-h2), both HIGH and
// both marked fixable. The two artifacts are released together from one
// project, so moving the property moves both and cannot leave them out of
// step. 5.5-beta2 is also listed as fixed and is deliberately NOT taken: a
// beta is not a version to put in an image over a HIGH that a stable release
// already closes.
extra["httpcore5.version"] = "5.4.3"

// Override Tomcat version managed by Spring Boot 3.5.16 (10.1.55) to remediate
// critical authorization/authentication bypass CVEs in 10.1.57 and earlier.
// Keep within the same 10.1.x line to minimize behavioral risk.
extra["tomcat.version"] = "10.1.59"

// Override com.rabbitmq:amqp-client transitive dependency from
// spring-boot-starter-amqp to patch 6 HIGH/MEDIUM severity CVEs discovered by
// appmod-validate-cves-for-java. Spring Boot 3.5.16 brings in 5.25.0 via
// spring-rabbit's transitive dependency, but 5.25.0 has:
//   CVE-2026-69220 (HIGH): Unbounded recursive table/array nesting → StackOverflowError DoS
//   CVE-2026-69219 (HIGH): Oversized LongString allocation → OOM
//   CVE-2026-63337 (HIGH): Unvalidated Class.forName in JSON-RPC → arbitrary class loading
//   CVE-2026-63335 (MEDIUM): Malformed body frame processing
//   CVE-2026-63336 (MEDIUM): TrustEverythingTrustManager MITM vulnerability
//   CVE-2026-61634 (LOW): Frame size validation bypass
//
// 5.33.1 is the exact fixed version addressing all 6 CVEs. It is a MINOR bump
// (5.25.0 -> 5.33.1), NOT a patch-level one: eight minor releases, across which
// runtime DEFAULTS can move even where the compiled API does not. See #658.
//
// THE PROPERTY NAME IS LOAD-BEARING AND EASY TO GET WRONG. It must match the key the
// Spring Boot BOM actually declares — `rabbit-amqp-client.version`, defined at line 174
// of spring-boot-dependencies-3.5.16.pom. A near-miss such as `rabbitmq-amqp-client`
// sets a property nothing reads and silently changes nothing.
//
// Measured on this tree with `dependencyInsight --dependency com.rabbitmq:amqp-client`:
//   misspelled property, no explicit pin  ->  5.25.0   (VULNERABLE)
//   correct property,    no explicit pin  ->  5.33.1
// so this one line is what closes the CVEs, and the fail direction was run rather than
// assumed. An explicit `implementation("com.rabbitmq:amqp-client:5.33.1")` was removed
// from the dependencies block below when this was corrected: it forced the right version
// while the property was broken, which is precisely why the broken property looked like
// it worked. Two mechanisms where one silently does nothing is how the defect hid.
//
// If a future Spring Boot renames this key again, the version silently reverts — the
// check that catches that is the Trivy gate in ci-cd.yaml, which fails the build on
// fixable HIGH/CRITICAL and is a required status check. That gate is the enforcement;
// this line is only the fix.
extra["rabbit-amqp-client.version"] = "5.33.1"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.retry:spring-retry")
    implementation("org.springframework.statemachine:spring-statemachine-starter:4.0.2")

    // Redis caching dependencies
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-cache")

    // RabbitMQ messaging
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    // amqp-client is pinned to 5.33.1 by `rabbit-amqp-client.version` at the top of this
    // file, not by a direct dependency here. See that comment: the direct pin used to be
    // on this line and was masking a misspelled property name.

    // WebSocket + STOMP for real-time KDS communication
    implementation("org.springframework.boot:spring-boot-starter-websocket")

    // Bucket4j for rate limiting
    implementation("com.bucket4j:bucket4j-core:8.10.1")
    implementation("com.bucket4j:bucket4j-redis:8.10.1")

    // Email notifications
    implementation("org.springframework.boot:spring-boot-starter-mail")

    // AWS S3 SDK v2 (works with MinIO for dev, real S3 for prod)
    implementation(platform("software.amazon.awssdk:bom:2.54.3"))
    implementation("software.amazon.awssdk:s3")

    // Phase 24 (IMG-02) — WebP transcode + image normalize pipeline.
    // scrimage-core decodes (via ImageIO) + resizes; scrimage-webp encodes the
    // WebP derivative/thumbnail by delegating to a `cwebp` binary (bundled on
    // glibc hosts; the musl runtime image overrides to the system cwebp via
    // -Dcom.sksamuel.scrimage.webp.binary.dir=/usr/bin — see Dockerfile).
    implementation("com.sksamuel.scrimage:scrimage-core:4.6.7")
    implementation("com.sksamuel.scrimage:scrimage-webp:4.6.7")
    // Read-only WebP ImageIO plugin — lets ImageReader header-read + decode-VERIFY
    // a WebP *upload* (stock JDK ImageIO cannot read WebP at all). Cannot encode.
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.14.0")
    implementation("com.twelvemonkeys.imageio:imageio-core:3.14.0")

    // Spring WebFlux for non-blocking HTTP client (Claude API calls)
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    // Observability: Micrometer for metrics and distributed tracing
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-tracing-bridge-brave")  // Brave (Zipkin) backend
    implementation("io.zipkin.reporter2:zipkin-reporter-brave")

    // Resilience4j circuit breaker
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.4.0")

    // Stripe payment processing
    implementation("com.stripe:stripe-java:33.3.0")

    // PDF generation for allergen labels
    implementation("com.github.librepdf:openpdf:2.0.3")

    // Use Spring Boot managed Hibernate ORM version to avoid mismatch
    implementation("org.hibernate.orm:hibernate-envers")
    // net.sf.jasperreports was REMOVED (2026-07-27). It was never used: zero
    // imports in core-java/src, zero .jrxml/.jasper templates in the repo, and
    // docs/status/SYSTEMS_ENGINEERING_REVIEW.md already listed it as an unused
    // dependency. It was also the SOLE source of commons-beanutils (directly and
    // via commons-digester), so removing it clears three Trivy image-gate HIGHs
    // — CVE-2025-48734 (beanutils), CVE-2025-10492 and CVE-2026-6009 (jasper) —
    // without bumping an unused library into JasperReports 7.x, which changes
    // artifact coordinates and licensing for no benefit. PDF generation is
    // OpenPDF (see com.github.librepdf:openpdf above), not JasperReports.
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.postgresql:postgresql:42.7.13")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.6")

    // Lombok for boilerplate reduction
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // MapStruct for compile-time safe DTO mapping
    implementation("org.mapstruct:mapstruct:1.6.3")
    annotationProcessor("org.mapstruct:mapstruct-processor:1.6.3")
    // Lombok-MapStruct binding to ensure Lombok runs BEFORE MapStruct
    annotationProcessor("org.projectlombok:lombok-mapstruct-binding:0.2.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:testcontainers:1.21.4")
    testImplementation("org.testcontainers:postgresql:1.21.4")
    // #92: real-broker fan-out proof for the per-instance SSE queues
    testImplementation("org.testcontainers:rabbitmq:1.21.4")
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
    testImplementation("com.h2database:h2") // for lightweight unit tests
}

tasks.test {
    useJUnitPlatform {
        // Exclude Testcontainers-dependent tests by default (require Docker with API >= 1.40)
        // Run them explicitly with: ./gradlew test -PincludeIntegration
        if (!project.hasProperty("includeIntegration")) {
            excludeTags("testcontainers")
        }
    }
    // Docker Engine 29+ requires API >= 1.40; Testcontainers 1.21.x defaults to 1.32.
    // docker-java's DefaultDockerClientConfig reads either DOCKER_API_VERSION env var
    // OR the "api.version" system property — set both so whichever code path the
    // selected DockerClientProviderStrategy uses, it negotiates an API the daemon accepts.
    environment("DOCKER_API_VERSION", "1.45")
    systemProperty("api.version", "1.45")
}

// QA-council #71: dedicated task for the @Tag("testcontainers") integration
// suite (real Postgres + FORCE RLS). Run by the "Integration Tests" CI job on
// every PR/push; `test` above keeps excluding the tag so the fast unit job is
// unchanged. Locally: ./gradlew :core-java:integrationTest
tasks.register<Test>("integrationTest") {
    description = "Runs the Testcontainers integration suite (real Postgres + RLS)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("testcontainers")
    }
    environment("DOCKER_API_VERSION", "1.45")
    systemProperty("api.version", "1.45")
    // Recycle the forked test JVM every few classes. Each Testcontainers class boots a distinct
    // Spring Boot context (many pin unique @MockBean/@SpyBean configs) whose threads are not all
    // reclaimed between classes; run in ONE fork, the suite accumulates enough live threads to hit
    // a native-thread OutOfMemoryError. Recycling bounds live threads to a handful of classes'
    // worth without changing any test's behaviour (containers are per-class static; tests use
    // fresh random tenants, so there is no cross-class JVM state to preserve).
    //
    // KEPT ON MEASUREMENT, NOT ON FAITH — 27-04 T7, three arms, same 88 tagged classes:
    //
    //   forkEvery(4), post-fix   2337s   peak 209 threads (median 80)    SUCCESS 102/414, 0 fail
    //   forkEvery(0), post-fix   3601s   peak 859 threads (median 820)   OOM, hit the 1h ceiling
    //   forkEvery(0), PRE-fix     937s   peak 1880 threads (median 1543) OOM, died before ceiling
    //
    // 27-04 expected the repaired listener factory to make this setting removable, because until
    // then `spring.rabbitmq.listener.simple.auto-startup=false` (registered by 22 test files) was
    // INERT: a bean named rabbitListenerContainerFactory made Boot's factory — and its configurer,
    // the only consumer of that property family — back off. THAT EXPECTATION IS REFUTED. The
    // repair helped a great deal but did not remove the need to recycle:
    //
    //   - Pre-fix, the OOM lands on `RabbitListenerEndpointContainer#7-37` — listener threads
    //     really were accumulating, exactly as this comment used to claim.
    //   - Post-fix, listener threads are gone from the picture: peak drops 1880 -> 859 (-54%),
    //     time-to-500-threads moves 0s -> 100s, and the OOM instead lands on
    //     `HttpClient-N-SelectorManager` and `idle-connection-reaper` — the reactive WebClient's
    //     selector pool and AWS SDK v2's S3/MinIO connection reaper.
    //
    // So the accumulation had TWO causes; 27-04 fixed one. Until the WebClient/AWS-SDK clients are
    // shared or shut down per context, forkEvery must stay. Do not "simplify" it away on the
    // reasoning that the listener bug is fixed — that is the specific wrong conclusion this block
    // exists to prevent, and re-deriving it costs an hour of wall clock.
    //
    // Raw series: .planning/phases/27-operational-maturity/baselines/ (T7 arms A/B/C).
    setForkEvery(4)

    // Run several forkEvery-bounded JVMs CONCURRENTLY. Container startup is
    // wait-bound (~16s per container sitting idle), so overlapping those waits is
    // most of the win. Measured on a 16-core dev box: full integrationTest
    // 2337s -> 911s (2.6x), 416 tests, 0 failures, 0 OOM at 4 forks.
    //
    // This does NOT reopen the OOM the block above documents: forkEvery(4) still
    // bounds native-thread accumulation PER JVM. Concurrency multiplies the number
    // of bounded JVMs; it does not raise any one JVM's ceiling.
    //
    // THE DIVISOR IS 2, AND THAT IS THE WHOLE POINT.
    //
    //   This started life as `availableProcessors() / 4`, which is correct on the
    //   16-core dev box (16/4 = 4, the validated cap) and INERT ON CI: a
    //   GitHub-hosted runner has 2 or 4 cores, so 4/4 = 1 and 2/4 = 0 -> coerced
    //   to 1. Behaviour unchanged on precisely the machine where the 45-minute job
    //   runs. A speed-up that cannot reach CI is a speed-up nobody sees, and the
    //   commit message claiming "39m -> 15m" was true only locally.
    //
    //   With /2 the dev box is UNCHANGED (16/2 = 8, coerced back to the validated
    //   cap of 4) while a 4-core runner moves 1 -> 2. The cap stays at 4 because
    //   4 forks is the largest value with a measured 0-OOM run behind it; going
    //   higher would be extrapolation, not measurement.
    //
    // Env-adaptive by construction — no hardcoded machine assumption. Override for
    // an experiment with -PitMaxParallelForks=N.
    maxParallelForks = (findProperty("itMaxParallelForks") as String?)?.toIntOrNull()
        ?: (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)

    doFirst {
        // Recorded in the job log so the next person reads a measurement rather
        // than re-deriving the arithmetic above from the runner's core count.
        logger.lifecycle(
            "integrationTest: availableProcessors=${Runtime.getRuntime().availableProcessors()}, " +
                "maxParallelForks=$maxParallelForks, forkEvery=4"
        )
    }

    shouldRunAfter(tasks.test)
}

// ---------------------------------------------------------------------------------
// JaCoCo — plan 34-09 (TRUTH-02, #110). Java coverage is measured on the AGGREGATE of
// `test` + `integrationTest`. That is a MEASUREMENT, not a preference.
//
// WHY AGGREGATE, AND WHY THE UNIT-ONLY FLOOR WAS REJECTED
//
//   `tasks.test` above EXCLUDES the `testcontainers` tag (:183-186) and
//   `integrationTest` runs ONLY that tag (:202-284). Both drive sourceSets["test"], so
//   the two halves of ONE suite execute in two tasks — and, in CI, in two different
//   jobs. Measured on this tree 2026-08-28 (JaCoCo 0.8.12, Gradle 8.10.2, JDK 21):
//
//       counter        `test` only    `test` + `integrationTest`     delta
//       INSTRUCTION        62.57%                        88.07%     +25.50
//       BRANCH             51.09%                        71.95%     +20.86
//       LINE               62.12%                        87.55%     +25.43
//       METHOD             65.01%                        87.53%     +22.52
//
//   `integrationTest` contributes 607 tests across 132 classes and +25.43 points of
//   LINE coverage. The alternative is rejected ON THOSE NUMBERS: a "60% line" gate on
//   `test` alone would sit about two points under a codebase that is actually at
//   87.55%. It could never catch a real regression — a quarter of the codebase could
//   stop being covered before it noticed — and it would publish a coverage figure
//   wrong by that same quarter. The unit-only floor is the cheap number, not the
//   honest one, and this project does not ship the cheap one while calling it
//   "coverage".
//
// A SKIPPED INTEGRATION JOB MUST VOID, NEVER PASS
//
//   ci-cd.yaml's `integration-tests` job is path-filtered and reports SUCCESS while
//   SKIPPING, deliberately, so it stays a satisfiable required check. An aggregate
//   gate running there unconditionally would be wrong on exactly the runs that skip.
//   So the coverage steps carry the SAME `if:` expression as the suite they measure,
//   and scripts/check-jacoco-coverage.sh exits 2 (VOID) when its inputs are absent.
//   "Could not measure" is not "measured and fine". DO NOT simplify that guard away:
//   removing it turns every skipped run into a false coverage pass, which is the one
//   failure this whole arrangement exists to prevent.
//
//   Note the same hazard inside Gradle: JacocoReport carries a built-in `onlyIf` that
//   SKIPS the task when no execution data file exists, so a missing .exec produces a
//   green build and NO report rather than an error. That is precisely why the gate
//   treats a missing/empty CSV as VOID rather than as 0%.
//
// TOOL VERSION IS PINNED
//
//   `jacoco` is a CORE Gradle plugin — no version coordinate in `plugins`, no
//   `dependencies` entry, no third-party supply-chain surface (threat T-34-09-SC).
//   toolVersion is pinned so a Gradle upgrade cannot silently move the numbers.
//   The table above was measured under 0.8.12 (Gradle 8.10.2, JDK 21). The JDK 25
//   bump FORCED a move to 0.8.15: 0.8.12 cannot read class file major version 69,
//   so jacocoTestReport/jacocoAggregateReport fail with "Error while creating
//   report" (measured on PR #707's first CI run — the suites passed, the report
//   step died). Java 25 support landed in 0.8.14; 0.8.15 (2026-06-04) is the
//   pinned release. The aggregate was re-measured under 0.8.15/JDK 25 in the same
//   change and stayed within the check-jacoco-coverage.sh floors' >=2-point
//   margins, so the floors were NOT re-anchored — see that script's header.
//
// NO TEST TASK IS FINALIZED BY A REPORT
//
//   Reports are produced by explicit steps that name the report task; nothing is
//   wired with `finalizedBy`. A developer running `./gradlew :core-java:test` locally
//   pays nothing for coverage they did not ask for, and the ~24-minute integration
//   suite is never triggered as a side effect of asking for a report.
//
// PATHS
//
//   Every artefact lands under core-java/build-local/ (the layout.buildDirectory
//   redirect at :15). core-java/build/ is STALE, and reading it is a recorded
//   stale-artifact trap in this repo. Both report destinations below are set
//   EXPLICITLY rather than left to the plugin's naming convention, so the gate's
//   input path is a fact in version control instead of an inference.
// ---------------------------------------------------------------------------------
jacoco {
    toolVersion = "0.8.15"
}

tasks.named<JacocoReport>("jacocoTestReport") {
    // UNIT-ONLY report (test.exec). Kept because the gate compares it against the
    // aggregate: an "aggregate" that merely equals the unit figure is a unit report
    // wearing the wrong name, and that comparison is what makes the gate a gate.
    reports {
        csv.required.set(true)
        csv.outputLocation.set(layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.csv"))
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml"))
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/test/html"))
    }
}

// The AGGREGATE. executionData is a fileTree over build-local/jacoco/*.exec, so it
// picks up test.exec AND integrationTest.exec whenever both are present. In CI the
// integration job downloads job `test`'s test.exec into that directory before running
// this task, which is the whole point of the cross-job artifact hand-off. The tree is
// resolved at EXECUTION time, so a file that arrives after configuration still counts.
//
// mustRunAfter, not dependsOn: requesting the report must never launch a suite, but
// when a suite IS requested on the same command line the report has to come second or
// it would read the previous run's .exec.
// Captured OUTSIDE the task-configuration lambda deliberately: inside a JacocoReport
// block `sourceSets` is the task's own vararg METHOD (JacocoReportBase.sourceSets), not
// the project's SourceSetContainer, and leaning on which receiver wins is the kind of
// thing a Gradle upgrade quietly changes. Naming it here removes the ambiguity.
val mainSourceSetForCoverage = sourceSets["main"]

tasks.register<JacocoReport>("jacocoAggregateReport") {
    description = "JaCoCo report over BOTH test.exec and integrationTest.exec — see the block above."
    group = "verification"
    // The two suites are named EXPLICITLY rather than globbed as `jacoco/*.exec`.
    // Every Test task gets a JacocoTaskExtension, so `generateOpenApiSpec` and
    // `updateOpenApiSnapshot` also drop .exec files here when a developer runs them —
    // a glob would then make this report's number depend on which unrelated commands
    // happened to run first, and the floor below was calibrated on exactly these two
    // suites. A future third suite is therefore EXCLUDED until it is added here, which
    // under-reports and turns the gate red; that is the safe direction to fail.
    executionData(
        fileTree(layout.buildDirectory)
            .include("jacoco/test.exec")
            .include("jacoco/integrationTest.exec")
    )
    sourceSets(mainSourceSetForCoverage)
    mustRunAfter(tasks.named("test"), tasks.named("integrationTest"))
    reports {
        csv.required.set(true)
        csv.outputLocation.set(layout.buildDirectory.file("reports/jacoco/aggregate/jacocoAggregateReport.csv"))
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/aggregate/jacocoAggregateReport.xml"))
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/aggregate/html"))
    }
}

// #97 AC3 — OpenAPI snapshot tooling. Both tasks run the single
// OpenApiSnapshotTest class, which boots the full Spring context against a
// throwaway Testcontainers Postgres and captures the normalized (byte-stable)
// /v3/api-docs output. The `check`-mode assertion of the same test class runs
// inside `integrationTest` above; these tasks switch its mode:
//   generateOpenApiSpec   → writes build-local/openapi/openapi-current.json only
//                           (CI's openapi-compat job diffs it with oasdiff)
//   updateOpenApiSnapshot → rewrites docs/api/openapi-snapshot.json; run this
//                           for INTENTIONAL API changes and commit the diff in
//                           the same PR so reviewers see the contract change.
fun Test.configureOpenApiSnapshotRun(mode: String) {
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("uk.jtoye.core.integration.OpenApiSnapshotTest") }
    environment("DOCKER_API_VERSION", "1.45")
    systemProperty("api.version", "1.45")
    systemProperty("jtoye.openapi.mode", mode)
    // The spec depends on the whole application source; never skip as up-to-date.
    outputs.upToDateWhen { false }
}

tasks.register<Test>("generateOpenApiSpec") {
    description = "Writes the normalized OpenAPI spec to build-local/openapi/openapi-current.json (no snapshot assertion)."
    group = "documentation"
    configureOpenApiSnapshotRun("generate")
}

tasks.register<Test>("updateOpenApiSnapshot") {
    description = "Regenerates docs/api/openapi-snapshot.json from current code. Commit the result in the same PR."
    group = "documentation"
    configureOpenApiSnapshotRun("update")
}
