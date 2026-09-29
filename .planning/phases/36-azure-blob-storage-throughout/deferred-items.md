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
