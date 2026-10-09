plugins {
    id("org.springframework.boot") version "4.1.1"
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

// NETTY: NO PIN UNDER BOOT 4 — measured 2026-10-05 (38-15, #706). With no pin, every netty artifact
// resolves to Boot's managed 4.2.17.Final (`netty.version`, spring-boot-dependencies-4.1.1.pom
// l.159): io.netty:netty-codec-http is 4.2.17.Final (selected by rule), and azure-core-http-netty
// 1.16.7's request for 4.1.137.Final is moved to 4.2.17.Final by the BOM. The rebuilt image's
// app.jar carries netty-*-4.2.17.Final, and the Trivy image gate run locally with CI's flags
// (Trivy 0.70.0, DB 2026-10-05 13:07 UTC) found 0 fixable HIGH/CRITICAL in it. Do NOT reintroduce
// a netty version pin. If a future DB flags 4.2.17, raise the floor on the 4.2 line (4.2.18.Final
// exists on Central). A 4.1.x pin would put a 4.1 netty under a 4.2-line reactor-netty.
// History, 38-03: Boot 4.1.1 manages netty 4.2.17.Final (the
// `netty.version` key of spring-boot-dependencies-4.1.1.pom), which GitHub's advisories list as
// patched for CVE-2026-75595/-75596 (4.2.17) and CVE-2026-59901/-55831/-55833/-56745 (4.2.16):
// every CVE the Boot-3 pin of 4.1.137.Final existed to clear (#318, #752). The pin was DELETED,
// not raised: Boot 4's reactor-netty (2025.0.x) is built on the 4.2 line, so forcing a 4.1.x
// netty under it would be a cross-line mismatch, not a floor. azure-core-http-netty 1.16.7 still
// declares 4.1.137 and is moved to 4.2.17 by the BOM (38-RESEARCH Pitfall 8; the spike's Azurite
// integration classes passed on that mix). 38-15 re-proves resolution and runs the Trivy scan.
// The deleted pin's full rationale (the Trivy image gate, SslClientHelloHandler reachability,
// why 4.1.138.Final was not taken) is in this file's history before 38-03.

// TOMCAT FLOOR, BOOT 4.1.1 — measured 2026-10-05 (38-15, #706). The key is `tomcat.version`,
// declared at l.215 of spring-boot-dependencies-4.1.1.pom and managed there at 11.0.24. This line
// holds org.apache.tomcat.embed:tomcat-embed-core at 11.0.26.
//   Trivy names three CRITICAL on 11.0.24, all fixed in 11.0.25: CVE-2026-65182 (security
//   constraint bypass), CVE-2026-65905 (DIGEST authenticator replay) and CVE-2026-68525 (FORM
//   authentication bypass).
//   Tomcat's own security page (the advisory) also lists CVE-2026-76183 (WebSocket security
//   constraint bypass) and CVE-2026-86350 (request-header mix-up regression) as fixed in 11.0.26
//   only. Trivy's 2026-10-05 DB has no entry for either at ANY severity, and 11.0.25 scans clean.
//   So the gate needs >= 11.0.25; the advisory is the reason for 11.0.26. Do not lower this to
//   11.0.25 just because the gate allows it: /ws/** is on this app's serving path.
//   Arms, 2026-10-05, Trivy 0.70.0, DB 2026-10-05 13:07 UTC, the CI image-gate flags:
//     near-miss key `tomcat-version` = "11.0.26"  ->  11.0.24 (selected by rule); jar scan rc=1, the 3 CRITICAL
//     `tomcat.version` = "11.0.24", image rebuilt and scanned  ->  rc=1, CVE-2026-65182/-65905/-68525
//     `tomcat.version` = "11.0.25"  ->  11.0.25; jar scan rc=0
//     `tomcat.version` = "11.0.26" (this line)  ->  11.0.26 (selected by rule); image scan rc=0
// ENFORCEMENT (unchanged rule): the image gate runs in `build-and-push`, which runs on push and
// release ONLY, never on a pull request, so a revert of this line merges green and turns `main` red
// afterwards. A PR that touches this line must therefore prove resolution itself, with
// `dependencyInsight --dependency org.apache.tomcat.embed:tomcat-embed-core --configuration runtimeClasspath`.
// Evidence: .planning/phases/38-spring-boot-4-1-migration/evidence/38-15-cve-floors.txt.
//
// History, 38-03: Override the Tomcat version managed by Spring Boot 4.1.1. Boot 4.1.1 manages 11.0.24
// (`tomcat.version` in spring-boot-dependencies-4.1.1.pom), which is BELOW our floor: Tomcat's
// security page lists the same 12 CVEs fixed in 10.1.59 (our Boot-3 pin) and in 11.0.25, so the
// managed version would re-open them. 11.0.26 (2026-09-15) is taken over 11.0.25 because it also
// fixes "Bypass of security constraints for WebSocket endpoints" (CVE-2026-76183) and the
// request-header mix-up regression (CVE-2026-86350), both on this app's serving path (/ws/**).
// Spike measurement: removing the pin resolves `11.0.24 (selected by rule)`. 38-15 re-proves it.
// History (Boot 3.5.16): pinned 10.1.59 over the managed 10.1.55 for the authorization/
// authentication bypass CVEs in 10.1.57 and earlier, kept on the 10.1.x line then.
extra["tomcat.version"] = "11.0.26"

// AMQP-CLIENT FLOOR, BOOT 4.1.1 — measured 2026-10-05 (38-15, #706). The key is
// `rabbit-amqp-client.version`, declared at l.181 of spring-boot-dependencies-4.1.1.pom and managed
// there at 5.30.0. The BOM also overrides spring-rabbit 4.1.1's own request for 5.31.0. This line
// holds com.rabbitmq:amqp-client at 5.34.0.
//   Trivy names four HIGH on 5.30.0: CVE-2026-63337 (fixed 5.33.0), CVE-2026-69219 and
//   CVE-2026-69220 (fixed 5.33.1), and CVE-2026-75516 (fixed 5.34.0).
//   Arm, 2026-10-05, Trivy 0.70.0, DB 2026-10-05 13:07 UTC, the CI gate flags:
//     this line removed  ->  5.30.0 (selected by rule; spring-rabbit's 5.31.0 -> 5.30.0); jar scan rc=1 naming those four
//     this line          ->  5.34.0 (selected by rule; 5.31.0 -> 5.34.0); image scan rc=0
// The ENFORCEMENT rule below (a PR that touches this line proves resolution itself, because the
// image gate runs post-merge) is unchanged. The dated history follows.
// Evidence: .planning/phases/38-spring-boot-4-1-migration/evidence/38-15-cve-floors.txt.
//
// History: Override com.rabbitmq:amqp-client transitive dependency from
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
// fixable HIGH/CRITICAL. It lives in `build-and-push`, which runs on push and release
// ONLY, never on a pull request: a revert of this pin merges green and turns `main` red
// afterwards. So a PR that touches this line must prove resolution itself, with
// `dependencyInsight --dependency com.rabbitmq:amqp-client --configuration runtimeClasspath`.
// That gate is the enforcement; this line is only the fix.
//
// 5.33.1 -> 5.34.0 (2026-09-28, #754): CVE-2026-75516 (HIGH) was published against
// 5.33.1 after the last green main run; 5.34.0 is the version Trivy names as fixed.
// Taken as the smallest clearing bump, not the newest (5.36.0), for the same reason as
// above: every minor step is a chance for a runtime default to move.
//
// BOOT 4.1.1 (38-03, #706): the key is UNCHANGED. `rabbit-amqp-client.version` is still the
// property spring-boot-dependencies-4.1.1.pom declares (l.181), managed there at 5.30.0, below
// both this floor and spring-rabbit 4.1.1's own 5.31.0. So the pin stays, value unchanged.
extra["rabbit-amqp-client.version"] = "5.34.0"

// JACKSON FLOORS, BOOT 4.1.1 — measured 2026-10-05 (38-15, #706). spring-boot-dependencies-4.1.1.pom
// declares TWO keys, and each one moves a whole BOM:
//   `jackson-2-bom.version` (l.82): Jackson 2, com.fasterxml.jackson; managed 2.21.5, held at 2.22.3.
//   `jackson-bom.version`   (l.83): Jackson 3, tools.jackson; managed 3.1.5, held at 3.1.7.
// Under Boot 3 the second key moved Jackson 2. Every "jackson-bom.version" in the history below
// refers to that Boot-3 meaning.
//   Jackson 2. Trivy names five HIGH on 2.21.5: jackson-core CVE-2026-89407 and CVE-2026-89425,
//   and jackson-databind CVE-2026-68497 (fixed 2.18.10 / 2.21.6 / 2.22.2), CVE-2026-91776 and
//   CVE-2026-91777. All except CVE-2026-68497 are fixed in 2.18.11 / 2.21.7 / 2.22.3. 2.22.3 is
//   also what stops the BOM downgrading swagger-core-jakarta 2.2.55 (springdoc 3.1.1), which
//   requests databind 2.22.1.
//   Jackson 3. Trivy names five HIGH on 3.1.5: tools.jackson.core:jackson-core CVE-2026-89407
//   (fixed 3.1.7 / 3.2.2) and CVE-2026-89425 (3.1.7 / 3.2.3), and tools.jackson.core:jackson-databind
//   CVE-2026-68497 (3.1.6 / 3.2.2), CVE-2026-91776 and CVE-2026-91777 (3.1.7 / 3.2.3).
//   CVE-2026-91777 IS on the 3.x line, and 3.1.7 clears it. That was unconfirmed when 38-03 set this
//   floor.
//   Arms, 2026-10-05, Trivy 0.70.0, DB 2026-10-05 13:07 UTC, the CI gate flags:
//     near-miss `jackson2-bom.version` = "2.22.3"  ->  databind 2.21.5 (by constraint; swagger's
//       2.22.1 -> 2.21.5); jar scan rc=1, the 5 Jackson-2 HIGH
//     near-miss `jackson3-bom.version` = "3.1.7"   ->  tools.jackson databind 3.1.5; jar scan rc=1,
//       the 5 Jackson-3 HIGH
//     both correct keys (these lines)  ->  2.22.3 and 3.1.7 (by constraint); image scan rc=0
// ENFORCEMENT (unchanged rule): the image gate runs post-merge only. A PR that touches either line
// proves resolution itself with `dependencyInsight --configuration runtimeClasspath` for BOTH
// com.fasterxml.jackson.core:jackson-databind and tools.jackson.core:jackson-databind.
// Evidence: .planning/phases/38-spring-boot-4-1-migration/evidence/38-15-cve-floors.txt.
//
// History (Boot 3.5.16, then 38-03): Override the Jackson family managed by Spring Boot 3.5.16's BOM. Jackson is not
// declared below -- it arrives through the web/json starters (and flyway, swagger-core),
// and every artifact is pinned by io.spring.dependency-management ("selected by rule"
// in dependencyInsight). Boot manages it through ONE property, `jackson-bom.version`,
// set to 2.21.4 at line 79 of spring-boot-dependencies-3.5.16.pom, which imports
// com.fasterxml.jackson:jackson-bom:${jackson-bom.version} at lines 2188-2190.
//
// WHY THE PIN EXISTS AT ALL (2026-09-29): the Trivy image gate named
// com.fasterxml.jackson.core:jackson-databind 2.21.4 in app.jar for CVE-2026-68497
// (HIGH; fixed in 2.18.10 / 2.21.6 / 2.22.2). The same gate was green on 2026-09-28
// (run 36469503402) and red on 2026-09-29 (runs 36552432346, 36555251078). No change in
// this tree caused it: the vulnerability DB moved. 2.21.6 also closes the six medium
// advisories 2.21.4 carries -- CVE-2026-83557 and CVE-2026-19032 (fixed in 2.21.6), and
// CVE-2026-77310, CVE-2026-59889, GHSA-mhm7-754m-9p8w and CVE-2026-54515 (fixed in 2.21.5).
//
// WHY THE BOM PROPERTY and not a forced artifact: the property re-points the imported
// jackson-bom, so jackson-core, jackson-databind and the datatype/dataformat/module
// artifacts move together. Forcing jackson-databind alone would leave its siblings on
// 2.21.4, out of step. jackson-annotations resolves as `2.21` both before and after,
// by the BOM's own major.minor versioning (`jackson.version.annotations`) -- that is
// expected, not a leftover.
//
// THE PROPERTY NAME IS LOAD-BEARING. It must match the key Boot's BOM declares,
// `jackson-bom.version`. A near-miss sets a property nothing reads and silently changes
// nothing. Measured 2026-09-29, when the pin moved 2.21.4 -> 2.21.6, with
// `dependencyInsight --dependency com.fasterxml.jackson.core:jackson-databind`:
//   near-miss key `jackson.bom.version` = "2.21.6"  ->  2.21.4   (VULNERABLE)
//   correct key   `jackson-bom.version` = "2.21.6"  ->  2.21.6
// Do NOT add a direct `implementation("com.fasterxml.jackson...")` to prove it works:
// the amqp-client block above records how a second mechanism masked a broken property.
//
// MOVED TO 2.21.7 (2026-10-04): red again on `main` (run 37165437798, c55e545a), 4 HIGH
// CVEs in 2.21.6 -- jackson-core CVE-2026-89407/-89425, jackson-databind CVE-2026-91776/
// -91777 -- all fixed in 2.18.11 / 2.21.7 / 2.22.3. Again the DB moved, not this tree.
//
// WHY 2.21.7 and not 2.22.x: it is the smallest clearing bump on the 2.21 line
// Boot 3.5.16 already manages, for the same reason as the amqp-client bump above --
// every step is a chance for a runtime default to move.
//
// ENFORCEMENT: the image gate lives in `build-and-push`, which runs on push and release
// ONLY, never on a pull request. A revert of this line merges green and turns `main` red
// afterwards. So a PR that touches this line must prove resolution itself, with
// `dependencyInsight --dependency com.fasterxml.jackson.core:jackson-databind --configuration runtimeClasspath`.
// That gate is the enforcement; this line is only the fix.
//
// WHEN TO DELETE IT: once Boot's own BOM manages Jackson 2 at or above 2.21.7; a pin left
// behind a newer Boot holds Jackson BELOW Boot's version. Under Boot 4 (#706) this key names
// the Jackson 3 BOM and Jackson 2 moves to `jackson-2-bom.version`: re-key, do not delete;
// and CVE-2026-89407 also lists Jackson 3 (tools.jackson.core), so check the 3.x line too.
//
// RE-KEYED UNDER BOOT 4.1.1 (38-03, #706). spring-boot-dependencies-4.1.1.pom declares TWO
// Jackson keys (l.82-83): `jackson-2-bom.version` (Jackson 2, com.fasterxml, managed 2.21.5) and
// `jackson-bom.version`, which NOW MOVES JACKSON 3 (tools.jackson, managed 3.1.5). Leaving the
// Boot-3 line in place was measured on the first Boot-4 build (38-03 evidence, RED compile): it
// pointed tools.jackson:jackson-bom at 2.21.7, which does not exist, so the BOM import failed and
// EVERY Boot-managed version vanished from compileClasspath ("Could not find ...-starter-web:.").
//
// Jackson 2 stays on the classpath for transitive users only (springdoc's swagger-core, the
// Azure SDK, Stripe); main code moves to Jackson 3 (D-01). Its floor moves 2.21.7 -> 2.22.3:
// 2.22.3 clears the same four HIGH CVEs (CVE-2026-89407/-89425/-91776/-91777, fixed in
// 2.18.11 / 2.21.7 / 2.22.3) AND stops downgrading swagger-core 2.2.55 (springdoc 3.1.1), which
// requests databind 2.22.1. A 2.21.x pin would hold it below what its own consumer asks for.
extra["jackson-2-bom.version"] = "2.22.3"
//
// Jackson 3 floor (Boot 4 `jackson-bom.version`; measured 2026-10-05 at the top of this block).
// History, 38-03: Boot 4.1.1 manages 3.1.5. 3.1.7 is the smallest release clearing
// CVE-2026-89407/-89425 (tools.jackson.core:jackson-core, fixed 3.1.7 / 3.2.2) and
// CVE-2026-91776 (tools.jackson.core:jackson-databind, fixed 3.1.7 / 3.2.3). The near-miss
// key hazard above applies to both keys. 38-15 ran the near-miss-key arms, dependencyInsight
// before and after for each, and the local Trivy scan; the results are recorded at the top of
// this block.
extra["jackson-bom.version"] = "3.1.7"

dependencies {
    // Boot 4 (38-03, D-02): EXPLICIT per-module starters, one per technology the app uses. Boot 4
    // split auto-configuration into per-technology modules, and the third-party library alone no
    // longer triggers it: a forgotten module is SILENT (the spike measured 0 Flyway migrations
    // without spring-boot-flyway). The aggregate "classic" starter pair is deliberately not used:
    // it drags in Boot's gRPC auto-configuration without gRPC itself, which throws
    // NoClassDefFoundError on any filter chain that keeps CSRF on.
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // Carries the Flyway auto-configuration module; without it 0 migrations run and the RLS
    // schema never exists. flyway-core / flyway-database-postgresql stay explicit below.
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-aspectj")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // RestClient.Builder (KeycloakAdminClient) and RestTemplateBuilder (SecurityConfig,
    // CustomerJwtVerifier) are auto-configured by spring-boot-restclient in Boot 4.
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    // spring-retry is no longer managed by the Boot 4.1.1 BOM, so it carries an explicit version.
    implementation("org.springframework.retry:spring-retry:2.0.13")
    implementation("org.springframework.statemachine:spring-statemachine-starter:4.0.2")
    // D-03: spring-statemachine 4.0.2 references 24 org.springframework.security.access.* classes
    // that Security 7 moved into this separate artifact. No version: the Spring Security 7.1.1 BOM
    // (imported by Boot 4.1.1) manages it.
    implementation("org.springframework.security:spring-security-access")

    // Redis caching dependencies
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-cache")

    // RabbitMQ messaging
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    // amqp-client is pinned to 5.34.0 by `rabbit-amqp-client.version` at the top of this
    // file, not by a direct dependency here. See that comment: the direct pin used to be
    // on this line and was masking a misspelled property name.

    // WebSocket + STOMP for real-time KDS communication
    implementation("org.springframework.boot:spring-boot-starter-websocket")

    // Bucket4j for rate limiting
    implementation("com.bucket4j:bucket4j-core:8.10.1")
    implementation("com.bucket4j:bucket4j-redis:8.10.1")

    // Email notifications
    implementation("org.springframework.boot:spring-boot-starter-mail")

    // Azure Blob Storage (Phase 36, owner ruling 2026-09-28: Blob throughout). Azurite locally
    // and in the nightly (connection-string mode), AKS Workload Identity in staging/production.
    // Explicit latest-GA versions rather than azure-sdk-bom 1.3.8, which lags one patch.
    // Coordinates checked against Maven Central and github.com/Azure/azure-sdk-for-java
    // (36-RESEARCH.md, Package Legitimacy Audit).
    implementation("com.azure:azure-storage-blob:12.35.1")
    implementation("com.azure:azure-identity:1.18.6") {
        // Desktop token-cache persistence only; it pulls jna + jna-platform native libraries that
        // WorkloadIdentityCredential never loads (assumption A1, proven by 36-06's
        // credential-build test).
        exclude(group = "com.microsoft.azure", module = "msal4j-persistence-extension")
        // azure-identity 1.18.6 ALSO declares jna-platform 5.17.0 directly (not only through the
        // extension above), so excluding the extension alone left jna on the runtime classpath.
        // Measured in its class files: the JNA references sit in the Windows credential store,
        // the Linux keyring, the IntelliJ/VS Code caches, the persistent token cache and one
        // Platform.isWindows() call inside IdentityClient.authenticateWithAzurePowerShell. None
        // is on WorkloadIdentityCredential's path.
        exclude(group = "net.java.dev.jna")
    }

    // Phase 24 (IMG-02) — WebP transcode + image normalize pipeline.
    // scrimage-core decodes (via ImageIO) + resizes; scrimage-webp encodes the
    // WebP derivative/thumbnail by delegating to a `cwebp` binary (bundled on
    // glibc hosts; the musl runtime image overrides to the system cwebp via
    // -Dcom.sksamuel.scrimage.webp.binary.dir=/usr/bin — see Dockerfile).
    implementation("com.sksamuel.scrimage:scrimage-core:4.6.8")
    implementation("com.sksamuel.scrimage:scrimage-webp:4.6.8")
    // Read-only WebP ImageIO plugin — lets ImageReader header-read + decode-VERIFY
    // a WebP *upload* (stock JDK ImageIO cannot read WebP at all). Cannot encode.
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.15.2")
    implementation("com.twelvemonkeys.imageio:imageio-core:3.15.2")

    // Spring WebFlux for non-blocking HTTP client (Claude API calls)
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    // Boot 4: the WebClient.Builder bean (injected by FhrsClient and WebhookDeliveryClientConfig)
    // is auto-configured by spring-boot-webclient, which -webflux does NOT carry. Added ALONGSIDE
    // webflux (which D-02 lists literally) under D-02's rule "declare each module the app actually
    // uses"; a missing builder fails context startup loudly.
    implementation("org.springframework.boot:spring-boot-starter-webclient")

    // Observability: Micrometer for metrics and distributed tracing
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-tracing-bridge-brave")  // Brave (Zipkin) backend
    implementation("io.zipkin.reporter2:zipkin-reporter-brave")
    // Boot 4: tracing and Zipkin export auto-configuration live in spring-boot-micrometer-tracing-
    // brave and spring-boot-zipkin; the libraries above alone leave tracing silently off.
    implementation("org.springframework.boot:spring-boot-starter-zipkin")

    // Resilience4j circuit breaker (the Boot 4 artifact; 2.4.0 is its only release)
    implementation("io.github.resilience4j:resilience4j-spring-boot4:2.4.0")

    // Stripe payment processing
    implementation("com.stripe:stripe-java:34.0.0")

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
    // springdoc 3.x is the Boot-4 line (2.8.x is Boot-3 only); 3.1.1 supersedes dependabot #739.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1")

    // Lombok for boilerplate reduction
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // MapStruct for compile-time safe DTO mapping
    implementation("org.mapstruct:mapstruct:1.6.3")
    annotationProcessor("org.mapstruct:mapstruct-processor:1.6.3")
    // Lombok-MapStruct binding to ensure Lombok runs BEFORE MapStruct
    annotationProcessor("org.projectlombok:lombok-mapstruct-binding:0.2.0")

    // Boot 4 test slices are per module (38-03, D-02): each test starter carries its technology's
    // test auto-configuration (MockMvc, TestRestTemplate, DataJpaTest, ...).
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    // Boot's configuration metadata model: 38-11's unknown-key gate reads it.
    testImplementation("org.springframework.boot:spring-boot-configuration-metadata")
    // AutoConfigureTracing / AutoConfigureMetrics, used by 38-04's liveness test.
    testImplementation("org.springframework.boot:spring-boot-micrometer-tracing-test")
    testImplementation("org.springframework.boot:spring-boot-micrometer-metrics-test")
    testImplementation("org.testcontainers:testcontainers:1.21.4")
    testImplementation("org.testcontainers:postgresql:1.21.4")
    // #92: real-broker fan-out proof for the per-instance SSE queues
    testImplementation("org.testcontainers:rabbitmq:1.21.4")
    // Phase 36: org.testcontainers.azure.AzuriteContainer for the real-Blob storage tests
    testImplementation("org.testcontainers:azure:1.21.4")
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

    // 36-06, assumption A1: WorkloadIdentityCredentialBuildTest proves the credential works with
    // JNA excluded by loading azure-identity from a class loader made ONLY of the production
    // runtimeClasspath. The test classpath cannot answer that question: Testcontainers'
    // docker-java-transport-zerodep puts net.java.dev.jna:jna on it. The test fails closed when
    // this property is absent.
    val productionRuntimeClasspath = configurations.runtimeClasspath.get()
    inputs.files(productionRuntimeClasspath).withPropertyName("productionRuntimeClasspath")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Djtoye.productionRuntimeClasspath=" + productionRuntimeClasspath.asPath)
    })
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
    //     selector pool and AWS SDK v2's object-store connection reaper.
    //
    // So the accumulation had TWO causes; 27-04 fixed one. Until the WebClient/AWS-SDK clients are
    // shared or shut down per context, forkEvery must stay.
    //
    // Phase 36 (36-01) replaced the AWS SDK with the Azure Blob SDK, whose azure-core-http-netty
    // client has its own event-loop and connection pools. The measurement above was NOT re-taken
    // for it, so nothing here licenses dropping forkEvery: the first full run after the swap
    // (4 forks, forkEvery=4) passed 719/719 with no OOM, which says the setting still works, not
    // that it is no longer needed. Do not "simplify" it away on the
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
//   `tasks.test` above EXCLUDES the `testcontainers` tag and
//   `integrationTest` runs ONLY that tag. Both drive sourceSets["test"], so
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
//   redirect at :19). core-java/build/ is STALE, and reading it is a recorded
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
