plugins {
    id("org.springframework.boot") version "3.4.2"
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
    implementation("org.springframework.statemachine:spring-statemachine-starter:3.2.1")

    // Redis caching dependencies
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-cache")

    // RabbitMQ messaging
    implementation("org.springframework.boot:spring-boot-starter-amqp")

    // Bucket4j for rate limiting
    implementation("com.bucket4j:bucket4j-core:8.10.1")
    implementation("com.bucket4j:bucket4j-redis:8.10.1")

    // Email notifications
    implementation("org.springframework.boot:spring-boot-starter-mail")

    // AWS S3 SDK v2 (works with MinIO for dev, real S3 for prod)
    implementation(platform("software.amazon.awssdk:bom:2.25.60"))
    implementation("software.amazon.awssdk:s3")

    // Spring WebFlux for non-blocking HTTP client (Claude API calls)
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    // Observability: Micrometer for metrics and distributed tracing
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-tracing-bridge-brave")  // Brave (Zipkin) backend
    implementation("io.zipkin.reporter2:zipkin-reporter-brave")

    // PDF generation for allergen labels
    implementation("com.github.librepdf:openpdf:2.0.3")

    // Use Spring Boot managed Hibernate ORM version to avoid mismatch
    implementation("org.hibernate.orm:hibernate-envers")
    implementation("net.sf.jasperreports:jasperreports:6.21.3")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.postgresql:postgresql:42.7.3")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.6")

    // Lombok for boilerplate reduction
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // MapStruct for compile-time safe DTO mapping
    implementation("org.mapstruct:mapstruct:1.5.5.Final")
    annotationProcessor("org.mapstruct:mapstruct-processor:1.5.5.Final")
    // Lombok-MapStruct binding to ensure Lombok runs BEFORE MapStruct
    annotationProcessor("org.projectlombok:lombok-mapstruct-binding:0.2.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:testcontainers:1.21.3")
    testImplementation("org.testcontainers:postgresql:1.21.3")
    testImplementation("org.testcontainers:junit-jupiter:1.21.3")
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
    // Docker Engine 29+ requires API >= 1.40; Testcontainers 1.21.x defaults to 1.32
    environment("DOCKER_API_VERSION", "1.45")
}
