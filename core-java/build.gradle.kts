plugins {
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    java
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
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

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.retry:spring-retry")
    implementation("org.springframework.statemachine:spring-statemachine-starter:3.2.1")

    // Redis caching dependencies
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-cache")

    // RabbitMQ messaging
    implementation("org.springframework.boot:spring-boot-starter-amqp")

    // WebSocket + STOMP for real-time KDS communication
    implementation("org.springframework.boot:spring-boot-starter-websocket")

    // Bucket4j for rate limiting
    implementation("com.bucket4j:bucket4j-core:8.10.1")
    implementation("com.bucket4j:bucket4j-redis:8.10.1")

    // Email notifications
    implementation("org.springframework.boot:spring-boot-starter-mail")

    // AWS S3 SDK v2 (works with MinIO for dev, real S3 for prod)
    implementation(platform("software.amazon.awssdk:bom:2.47.6"))
    implementation("software.amazon.awssdk:s3")

    // Phase 24 (IMG-02) — WebP transcode + image normalize pipeline.
    // scrimage-core decodes (via ImageIO) + resizes; scrimage-webp encodes the
    // WebP derivative/thumbnail by delegating to a `cwebp` binary (bundled on
    // glibc hosts; the musl runtime image overrides to the system cwebp via
    // -Dcom.sksamuel.scrimage.webp.binary.dir=/usr/bin — see Dockerfile).
    implementation("com.sksamuel.scrimage:scrimage-core:4.6.6")
    implementation("com.sksamuel.scrimage:scrimage-webp:4.6.6")
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
    implementation("com.stripe:stripe-java:28.2.0")

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
    // Recycle the forked test JVM every few classes. Each Testcontainers class boots
    // a distinct Spring Boot context (many pin unique @MockBean/@SpyBean configs) whose
    // RabbitMQ listener + reactive HttpClient selector threads are not all reclaimed
    // between classes; run in ONE fork the whole 24-class suite accumulates enough live
    // threads to hit a native-thread OutOfMemoryError. Recycling bounds live threads to
    // a handful of classes' worth without changing any test's behaviour (containers are
    // per-class static; tests use fresh random tenants, so there is no cross-class JVM
    // state to preserve).
    setForkEvery(4)
    shouldRunAfter(tasks.test)
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
