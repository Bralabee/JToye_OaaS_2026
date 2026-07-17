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
    implementation("net.sf.jasperreports:jasperreports:6.21.3")
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
    testImplementation("org.testcontainers:testcontainers:2.0.5")
    testImplementation("org.testcontainers:postgresql:2.0.5")
    // #92: real-broker fan-out proof for the per-instance SSE queues
    testImplementation("org.testcontainers:rabbitmq:2.0.5")
    testImplementation("org.testcontainers:junit-jupiter:2.0.5")
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
