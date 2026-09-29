## Deferred Items

Out-of-scope discoveries logged by plan executors. Each predates the plan that found it and was not
caused by it; none breaks a gate this phase owns.

- 36-09: three live `file:line` citations already pointed at the wrong lines before 36-09
  status: open
  **What:** `core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java:153` and
  `SecurityHeadersIntegrationTest.java:155,166` cite `k8s/base/core-java-deployment.yaml:181-198`
  / `:196-202` for the security-header config, but at d17cb269 (pre-36-09) those lines were already
  the media-outbox env; `frontend/app/sitemap.ts:36` cites `k8s/scripts/check-env-contract.sh:210`
  for NEXT_PUBLIC_SITE_URL, which was already the CUSTOMER_KEYCLOAK_ISSUER_INTERNAL entry; and
  `k8s/base/core-java-deployment.yaml` (the log.path comment) cites
  `k8s/local/configmap-patch.yaml:169`, which was already `smtp.port`. `check-doc-citations.sh`
  classes them UNCHECKABLE or does not scan those files, so no gate is red. 36-09 fixed only the
  three citations its own line shifts broke.

- 36-09: the frontend NetworkPolicy still describes its public 443 rule as covering "S3 public URLs"
  status: open
  **What:** `k8s/base/networkpolicies/10-frontend.yaml` (header l.11, rule comment l.60) and the
  `10-frontend.yaml` row of `k8s/base/networkpolicies/README.md` name S3. The rule itself is right
  (443, RFC1918 excluded); only the prose is stale. Not in 36-09's or 36-10's file list.

- 36-09: pg-backup still injects `RETENTION_DAYS: "30"`, which nothing reads any more
  status: open
  **What:** since 36-08 `infra/backups/k8s-backup.sh` never prunes (retention is the container's
  WORM + soft delete + lifecycle rule), so the CronJob env in `k8s/base/pg-backup-cronjob.yaml`
  under `# --- retention ---` states a retention the job does not perform. No gate reads pg-backup
  env (check-env-contract covers core-java, edge-go and the frontend only). Removing it changes the
  goldens, so it belongs with the next pg-backup manifest change.

- 36-18: core-java image Trivy gate is red on jackson-databind 2.21.4 (CVE-2026-68497, HIGH, fixed in 2.21.6)
  status: open
  **What:** CI/CD runs 36552432346 (536daf41) and 36555251078 (ae4ceb43) on the phase branch both
  fail only at "Build and Push Images (core-java) / Trivy image gate — fail on fixable CRITICAL/HIGH",
  with one finding: `com.fasterxml.jackson.core:jackson-databind (app.jar) CVE-2026-68497 HIGH,
  installed 2.21.4, fixed 2.18.10 / 2.21.6 / 2.22.2`. Every test and gate job in both runs is green
  apart from this and the doc-citation failure 36-18 fixed in ae4ceb43. **Not caused by Phase 36:**
  `dependencyInsight` on HEAD says 2.21.4 is "Selected by rule" (the Spring Boot 3.5.16 BOM through
  io.spring.dependency-management); neither HEAD nor origin/main pins jackson in
  core-java/build.gradle.kts, and the Boot version is unchanged. The same job passed on 5b6e76bd at
  2026-09-28T19:49Z (run 36469503402), so the CVE entered Trivy's DB after that: the daily-DB
  time-bomb class. main's next core-java image build will red the same way. The fix is a
  dependency bump of that exact artifact to 2.21.6 in its own change, not part of 36-18.
