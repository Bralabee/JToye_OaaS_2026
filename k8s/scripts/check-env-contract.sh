#!/usr/bin/env bash
# check-env-contract.sh — the two-direction env contract gate for ALL THREE
# built services: core-java, edge-go and the frontend.
#
# WHY THIS EXISTS (Phase 26, D-07 / D-08 — the DEF-4 and DEF-6 bug classes)
#
#   DEF-4 (both sides of it). k8s/base/core-java-deployment.yaml injected the
#   RabbitMQ credential under the env name RABBITMQ_USERNAME — mirroring the
#   SECRET KEY instead of the Spring placeholder. No application*.yml has ever
#   read that name, so the injected value reached nothing and the primary AMQP
#   pool silently fell back to its literal Spring default while still using the
#   secret's real password. The pool connected, so nothing looked broken. Two
#   more injected envs (the STOMP relay login/passcode) had the identical shape.
#
#   DEF-6 (the local-default class). Thirteen further placeholders that NO
#   manifest supplied at all, each carrying a LOCAL-ONLY default: media uploads
#   resolved to a dev MinIO endpoint with a dev access key, notification email
#   resolved to a loopback relay, and every production unsubscribe link and
#   Stripe Connect vendor return pointed at http://localhost:3000.
#
#   Both classes survived every review, every CI gate and a live cluster
#   rehearsal for the same reason: a wrong-or-missing env NAME resolves to a
#   working-looking default, and nothing ever compared the two sides. This gate
#   compares them. A one-time fix without a gate is a fix that returns.
#
# WHY IT NOW COVERS THREE SERVICES (issue #298)
#
#   Until 2026-08-03 this gate read core-java ONLY, and said so. That limit was
#   not cosmetic: the SAME bug class was live in edge-go and invisible here.
#   edge-go/cmd/edge/main.go:141 has read JWT_EXPECTED_ISSUER since the issuer/
#   JWKS decoupling fix (issue #87), and NO k8s manifest ever supplied it — plan
#   26-02 wired it by hand, and a core-java-only gate could not have caught it.
#   The frontend had the mirror-image problem (D-18): NEXT_PUBLIC_API_URL was
#   injected as a RUNTIME env where it could never reach the browser bundle,
#   i.e. dead config that a naive "is it injected?" check would score as GOOD.
#
#   So each service gets its own parser, and the frontend additionally gets the
#   build-time/runtime distinction encoded (see the FRONTEND section).
#
# THE TWO DIRECTIONS (the same two, per service)
#
#   (a) INJECTED-BUT-UNREAD. Every env NAME the service's k8s Deployment
#       injects must be READ by that service's source/config, or be on the
#       service's direction-(a) allowlist with a reason. This direction IS
#       DEF-4: a manifest feeding an env that nothing reads.
#
#   (b) EXPECTED-BUT-UNSUPPLIED. Every env NAME the service READS must either
#       be supplied by the manifest (or, for the frontend only, by the enforced
#       BUILD-ARG channel), or be on the service's direction-(b) allowlist with
#       a reason. Per-service refinements are documented in each section.
#
# ALLOWLIST HYGIENE IS PART OF THE GATE (D-08 says "reasoned", not "listed")
#   - an entry with a blank / whitespace-only reason FAILS;
#   - a duplicate entry FAILS;
#   - an entry that is no longer needed FAILS as STALE — the variable is now
#     injected, or is now read, or no longer has a local-only default, or has
#     disappeared from the config entirely.
#   - a reason that starts with the marker OPEN DEFECT must cite an issue
#     number (#NNN). Those entries are NOT "reviewed omissions" — they are
#     tracked live gaps, and the gate prints them under their own heading on
#     every run so they cannot pass as settled.
#   So the allowlist cannot rot into a permanent excuse-store; it stays a
#   reviewed inventory that a human signed off on for a stated reason.
#
# TEST-COUNT NOTE
#   A bash gate under k8s/scripts/ contributes 0 to docs/metrics.json:
#   scripts/docs-freshness.sh counts only Java @Test methods, Go Test* funcs,
#   Jest/vitest it()/test() blocks and Playwright test() blocks — no bash. That
#   is deliberate. A JUnit equivalent would add +N to metrics.json and force a
#   --write reconcile of a documented cross-branch merge-conflict hotspot in the
#   same PR, for no extra assurance.
#
# PARSING NOTES (each one is a real trap found in the actual files)
#   1. Injected envs are matched anchored to the env-list item indent
#      (`^\s+- name: NAME$`, all-uppercase rest-of-line). The container name,
#      port names and HPA metric names are lowercase, so they do not match.
#   2. The Spring placeholder regex tolerates ONE level of nesting. A naive
#      `\$\{([A-Z_]+):([^}]*)\}` mis-terminates on the real nested defaults in
#      application.yml (the two expected-issuer chains and the four STOMP
#      credential chains), truncating the name/default split. Nested INNER
#      placeholders are recorded too, so a name that appears only as a fallback
#      inside another placeholder still counts as read.
#   3. The uppercase filter IS the env-vs-property discriminator:
#      ${spring.application.name} and ${jtoye.security...} are Spring PROPERTY
#      references, not env vars, and [A-Z0-9_]+ excludes them.
#   4. FULL-LINE comments are stripped before extraction, in YAML, in Go and in
#      TypeScript. A comment that merely MENTIONS a placeholder would otherwise
#      make direction (a) believe a dead env is read. All three cases are real
#      on this tree:
#        - application.yml contains `${RABBITMQ_USER:guest}` inside prose;
#        - frontend/lib/customer-orders-server.ts:27 contains the text
#          `process.env.NEXT_PUBLIC_*` inside a block comment;
#        - edge-go/cmd/edge/main.go carries several `// NAME is ...` lines.
#      Trailing (same-line) comments are deliberately NOT stripped: a `#` or a
#      `//` can legitimately appear inside a quoted value or a URL, and cutting
#      at one would silently SHORTEN real code — which produces a false
#      "not read" and therefore a false direction-(a) violation. The residual
#      risk (a name mentioned only in a trailing comment) is accepted and does
#      not occur on this tree.
#   5. One name can carry SEVERAL different defaults across profiles (real cases
#      exist). Defaults are collected as a SET per name and the local-only rule
#      trips if ANY member matches — matched per-default, never against a joined
#      string, because an anchored test on a joined string misses the member.
#   6. Local-only means bare words as much as URLs. `minioadmin` and a bare-word
#      broker default are the DEF-4/DEF-6 signature; a URL-only regex misses
#      both.
#   7. Every extractor is SELF-TESTED against a synthetic control string before
#      it is trusted (see selftest_regex). A regex that silently matches nothing
#      returns an EMPTY set, which is indistinguishable from "this service is
#      perfectly configured" — the exact shape that makes a gate vacuous. An
#      extractor that cannot match its own control exits 2 (VOID), never 0.
#
# Requires: bash >= 4.3 (associative arrays + namerefs), GNU grep with -P (PCRE),
#           sed, find. ubuntu-latest (the CI runner) ships bash 5.x.
# Exit codes: 0 = contract holds, 1 = violation, 2 = parse/tooling failure.
#
# Usage: ./k8s/scripts/check-env-contract.sh
#   (run from anywhere; paths resolve relative to the repo root)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

CORE_DEPLOYMENT="$REPO_ROOT/k8s/base/core-java-deployment.yaml"
CORE_APP_DIR="$REPO_ROOT/core-java/src/main/resources"
EDGE_DEPLOYMENT="$REPO_ROOT/k8s/base/edge-go-deployment.yaml"
EDGE_SRC_DIR="$REPO_ROOT/edge-go"
FE_DEPLOYMENT="$REPO_ROOT/k8s/base/frontend-deployment.yaml"
FE_SRC_DIR="$REPO_ROOT/frontend"
FE_DOCKERFILE="$REPO_ROOT/frontend/Dockerfile"
FE_ENV_VALIDATION="$REPO_ROOT/frontend/lib/env-validation.ts"

fail() { echo "FAIL: $*" >&2; exit 1; }
parse_fail() { echo "PARSE ERROR: $*" >&2; exit 2; }

# ===========================================================================
# CORE-JAVA ALLOWLIST — direction (a): injected by k8s, read by no
# application*.yml.
#
# Format: NAME|reason. A blank reason, a duplicate, or an entry that is no
# longer needed (not injected any more, or now genuinely read) fails the gate.
# NEVER widen this list to make the gate pass: if the gate is right and the
# manifest is wrong, fix the manifest.
# ===========================================================================
ALLOW_CORE_A=(
  'SPRING_PROFILES_ACTIVE|Spring relaxed-binding environment variable, not a ${} placeholder. Spring Boot binds it directly onto spring.profiles.active before any property source is read, so it correctly appears in no application*.yml. It is load-bearing (26-CONTEXT.md D-10 keeps every k8s environment on the prod profile) and must not be removed to satisfy direction (a).'
)

# ===========================================================================
# CORE-JAVA ALLOWLIST — direction (b): read by Spring, supplied by no manifest,
# and the default is local-only, absent, or an unresolved property chain. Each
# entry is a REVIEWED omission.
#
# Format: NAME|reason. Same hygiene rules as above. An entry here that becomes
# manifest-supplied, or whose defaults stop being local-only, fails as STALE so
# the inventory cannot silently rot.
# ===========================================================================
ALLOW_CORE_B=(
  'OLLAMA_URL|Reviewed omission (issue #303): there is no in-cluster Ollama, and the media vision stage is advisory-only behind jtoye.media.vision.enabled, which defaults false (Phase 24 IMG-03 — a vision failure never rejects an upload, it only flags for review). Supplying this would point core-java at a host that does not exist; leaving the unreachable default keeps the stage inert, which is the intended k8s behaviour. Revisit only when a phase actually deploys or points at a real inference endpoint — inventing a value first is the DEF-6 defect class in reverse.'
  'ZIPKIN_ENDPOINT|Reviewed omission (issue #303): no in-cluster Zipkin/OTLP collector is deployed, and Micrometer tracing export is best-effort — spans are dropped silently and no request path degrades. A supplied-but-wrong endpoint would be worse than an unreachable default. Revisit when the observability phase actually adds a collector (overlaps #98); until then this entry, not a manifest value, is the record.'
  'CUSTOMER_KC_ISSUER_URI|OPEN DEFECT #299 — the customer-storefront realm is unconfigured in EVERY k8s environment (base, staging, production and local). This is a tracked live gap, NOT a reasoned omission. It is carried here rather than half-fixed because supplying only this one issuer would make a broken realm look configured; the whole set (this, CUSTOMER_JWT_EXPECTED_ISSUER, and the frontend CUSTOMER_KEYCLOAK_* trio) has to land together with the storefront/CID work.'
  'CUSTOMER_JWT_EXPECTED_ISSUER|OPEN DEFECT #299 — same realm, same gap. Its default is the property chain ${jtoye.security.customer-jwt.issuer-uri}, which resolves to ${CUSTOMER_KC_ISSUER_URI:http://localhost:8085/realms/jtoye-customers}, i.e. transitively local-only. Before the chained-default rule existed this name scored as "pass by rule (safe non-local default)" and #299 was HALF-INVISIBLE to its own gate.'
  'DB_MIGRATION_USER|Reviewed omission (Phase 28 SEC-04 / D-01, the runtime/migrator role split). Unlike the #299 chains above, THIS one terminates in a manifest-supplied secret, not a localhost literal: unset, spring.flyway.user (application.yml:115) falls back to ${spring.datasource.username} = ${DB_USER}, which k8s supplies from the postgres-credentials secret (core-java-deployment.yaml:98-102, key username). This gate does not resolve property chains, hence the entry naming where it lands. The compose/local stack splits the app role (jtoye_runtime, DML-only) from the migrator (jtoye_app, owner); the k8s cluster still connects as a single role and adopts the split with the Phase 29 deploy work (DPLY), at which point a distinct migration-username secret key is wired here alongside an in-cluster jtoye_runtime role. Supplying a distinct migrator credential now, before that role exists in-cluster, would break Flyway.'
  'DB_MIGRATION_PASSWORD|Reviewed omission (Phase 28 SEC-04 / D-01). Unset, spring.flyway.password (application.yml:116) falls back to ${spring.datasource.password} = ${DB_PASSWORD}, supplied by k8s from the postgres-credentials secret (core-java-deployment.yaml:103-107, key password). Same terminates-in-a-secret rationale and the same Phase 29 (DPLY) revisit as DB_MIGRATION_USER — the migration credential pair is wired to a distinct secret key when the runtime/migrator split is deployed to the cluster.'
)

# ===========================================================================
# EDGE-GO ALLOWLIST — direction (a): injected by k8s, read by no Go source.
# ===========================================================================
ALLOW_EDGE_A=()

# ===========================================================================
# EDGE-GO ALLOWLIST — direction (b): read by edge-go, supplied by no manifest.
#
# Go has no "hard fail on unresolved placeholder" equivalent — an unset env is
# simply "", and every read site here supplies its own fallback. So absence is
# never a boot failure; it is a SILENT behaviour change, which is exactly the
# DEF-6 shape. Each omission therefore has to be stated, not inferred.
# ===========================================================================
ALLOW_EDGE_B=(
  'EDGE_JWT_AUDIENCE|Reviewed omission: unset falls back to the fail-closed constant defaultJWTAudience = "core-api" (edge-go/internal/middleware/jwt.go:31), which is the audience this platform actually mints. Absence never DISABLES the aud check (issue #87 P1-5, threat T-bl2-02) — it only selects the default — so injecting it would restate the default and add a value that can drift out of step with the realm.'
  'JWKS_REFRESH_INTERVAL|Reviewed omission: optional cadence override for the JWKS re-fetch. Unset uses defaultJWKSRefreshInterval = 5m; an unparseable value logs a WARN and keeps the default. Absence is the intended, safe state.'
  'RATE_LIMIT_RPS|Reviewed omission: per-replica DoS-guard tuning knob (default 20). This valve is deliberately NOT the per-tenant quota — Core Bucket4j is the authoritative limit — so a cluster-wide value here would express a policy the edge does not own.'
  'RATE_LIMIT_BURST|Reviewed omission: the burst half of the same per-replica DoS guard (default 40). Same reasoning as RATE_LIMIT_RPS.'
  'EDGE_MANAGEMENT_PORT|Reviewed omission, and still one that MUST STAY AN OMISSION — but NOT for the reason recorded before issue #550. The old reason was "the Phase 27 scrape config targets the app port with no credentials", and that is now FALSE for compose: #550 sets EDGE_MANAGEMENT_PORT on the edge-go service in docker-compose.full-stack.yml and moves the scrape target with it, both from the single .env key EDGE_GO_METRICS_PORT. The k8s conclusion is unchanged and the k8s reason is different: NOTHING SCRAPES EDGE-GO IN K8S AT ALL. k8s/ ships zero monitoring manifests (DPLY-03) — no Prometheus, no ServiceMonitor — so the only consumer of a k8s edge-go metrics port would be the prometheus.io/scrape+port+path annotations on k8s/base/edge-go-deployment.yaml:26-28, which name 8080 and which no controller in this repo reads. Supplying a port here would move /metrics off 8080 and make those three annotations lie, for no scraper. Revisit as ONE change with DPLY-03: whoever deploys in-cluster Prometheus sets this variable AND the annotations AND the scrape target together, exactly as compose now does. Do not "fix" this entry by supplying the variable on its own.'
  'WHATSAPP_APP_SECRET|Reviewed omission: the Meta WhatsApp intake is not provisioned in any k8s environment. Absence is FAIL-CLOSED by design — edge-go/cmd/edge/handlers.go:249-255 refuses the webhook with 503 + Retry-After, "webhook signing not configured", rather than skipping signature verification — so an unconfigured cluster rejects unsigned webhooks instead of accepting them. The status became 503 in issue #450 item 3 (it was 500): the refusal is unchanged, but an unset secret is a known operator-fixable state, not an internal error. Belongs with the phase that provisions the Meta integration; supplying part of the set would half-wire it.'
  'WHATSAPP_DEFAULT_SHOP_ID|Reviewed omission: part of the same unprovisioned WhatsApp intake set as WHATSAPP_APP_SECRET. The route is fail-closed on the secret before any of these are used.'
  'WHATSAPP_DEFAULT_TENANT_ID|Reviewed omission: part of the same unprovisioned WhatsApp intake set. Scopes the edge->Core service-token call that stands in for the (impossible) caller JWT.'
  'WHATSAPP_SERVICE_CLIENT_ID|Reviewed omission: part of the same unprovisioned WhatsApp intake set. Its Keycloak client does not exist in the k8s realms either, so a value here would name a client that cannot authenticate.'
  'WHATSAPP_SERVICE_CLIENT_SECRET|Reviewed omission: part of the same unprovisioned WhatsApp intake set, and it would additionally require a Secret that no k8s environment creates.'
)

# ===========================================================================
# FRONTEND ALLOWLIST — direction (a): injected by k8s, read by no frontend
# source and not declared in env-validation.ts.
# ===========================================================================
ALLOW_FE_A=()

# ===========================================================================
# FRONTEND ALLOWLIST — direction (b): read by the frontend, supplied by
# neither the manifest nor the enforced build-arg channel.
# ===========================================================================
ALLOW_FE_B=(
  'NEXT_RUNTIME|Reviewed omission: set by the Next.js runtime itself, never by an operator. frontend/instrumentation.ts:12 reads it only to tell the nodejs runtime from the edge runtime. Injecting it would override a value Next.js owns.'
  'APP_PUBLIC_ORIGIN|Reviewed omission, and one that SHOULD stay omitted: it is an optional override at the head of the resolvePublicOrigin chain (frontend/lib/public-origin.ts:87), not a required input. Absent, resolution falls straight through to NEXTAUTH_URL, which k8s/base/frontend-deployment.yaml:148-152 supplies from app-config/frontend.url — patched per overlay to the real public origin in every environment — and which frontend/lib/env-validation.ts:45 already lists as REQUIRED. So the value this name would carry is already supplied, correctly, by the very next term. It exists for the day the app public origin and NextAuth s diverge, or NextAuth is replaced; supplying it now would be a second source of truth for one origin, and the #504 defect it was written for was a bind address reaching an IdP, which resolvePublicOrigin rejects via isBindAddress regardless of which term wins.'
  'CSP_REPORT_ONLY|Reviewed omission, and one that must stay an omission in staging/production: unset means the Content-Security-Policy is ENFORCING (frontend/middleware.ts:33). Setting it to "true" would downgrade the policy to report-only cluster-wide. The only legitimate value is a temporary local one.'
  'CSP_UPGRADE_INSECURE_REQUESTS|Reviewed omission with a known caveat: unset means the CSP omits upgrade-insecure-requests. frontend/lib/security-headers.ts:33-40 records that real HTTPS deployments SHOULD set it to "true", so staging/production are leaving a hardening directive on the table. It is deliberately off in base because the base render is shared with the local overlay, which serves http and would break MinIO images at http://localhost:9000 under an unconditional upgrade. Needs a per-overlay value, not a base one.'
  'CUSTOMER_KEYCLOAK_ISSUER|OPEN DEFECT #299 — the customer-storefront realm is unconfigured in EVERY k8s environment. Read by frontend/lib/customer-token-refresh.ts:42 for the customer refresh-token exchange; supplied by docker-compose only. This is a tracked live gap, NOT a reasoned omission. Note that #299 named three variables and this is one of THREE MORE it did not name.'
  'CUSTOMER_KEYCLOAK_ISSUER_INTERNAL|OPEN DEFECT #299 — same realm, same gap. The pod-reachable half of the customer issuer split (frontend/lib/customer-token-refresh.ts:41). Unsupplied, the refresh falls through to CUSTOMER_KEYCLOAK_ISSUER, which is itself unsupplied.'
  'CUSTOMER_KEYCLOAK_CLIENT_ID|OPEN DEFECT #299 — same realm, same gap. frontend/lib/customer-token-refresh.ts:48 falls back to the literal "storefront-client", so the refresh silently assumes a client id instead of being configured with one.'
  'NEXT_PUBLIC_SITE_URL|Reviewed omission with a known caveat: frontend/app/sitemap.ts:11 falls back to http://localhost:3100, so sitemap.xml advertises a loopback origin in every k8s environment. This is a DEF-6-shaped SEO gap rather than a runtime failure (no request path degrades). It cannot be fixed by a runtime env: entry — it is a NEXT_PUBLIC_* with no Dockerfile ARG, so it needs the same frontend runtime-config decision as NEXT_PUBLIC_KEYCLOAK_URL, or a new build-arg.'
  'NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY|Reviewed omission with a known caveat: frontend/app/shop/[slug]/checkout/page.tsx:69-70 makes Stripe conditional on it, so an absent key disables card checkout rather than breaking the page. docker-compose passes it through as a runtime value with an empty default; no k8s path supplies it and it has no Dockerfile ARG. Card checkout is therefore inert in k8s. Belongs with the payments-enablement work, which also owns the Stripe secret.'
  'NEXT_PUBLIC_SHOPS_PAGE_SIZE|Reviewed omission: deliberately in neither requiredEnvVars nor optionalEnvVars (frontend/lib/env-validation.ts:29-36). resolveShopsPageSize() falls back to DEFAULT_SHOPS_PAGE_SIZE = 200 for anything that is not a positive integer, and the caller pages until the API says there is no more, so absence costs nothing.'
  'NEXT_PUBLIC_KITCHEN_ORDERS_PAGE_SIZE|Reviewed omission, and one with a stronger reason than its NEXT_PUBLIC_SHOPS_PAGE_SIZE sibling above: there is nothing here for an operator to tune, because the API itself caps the value. Declared in neither requiredEnvVars nor optionalEnvVars (frontend/lib/env-validation.ts:38-42); resolveKitchenOrdersPageSize (frontend/lib/env-validation.ts:138) returns DEFAULT_KITCHEN_ORDERS_PAGE_SIZE = 100 (frontend/lib/env-validation.ts:130) for anything that is not a positive integer, so unset, blank and malformed all resolve identically to the shipped default. 100 is the MAXIMUM core-java will serve: measured 2026-08-04 against a shop with 125 orders, size=100 and size=500 both returned 100 rows with totalPages 2 and last false. A larger value is a no-op on the wire and a smaller one only costs extra requests. Absence cannot lose a ticket either, because the caller pages until the API reports no next page, bounded by MAX_KITCHEN_ORDER_PAGES = 20 (frontend/lib/kitchen-orders-api.ts:22), and a bound that fires returns truncated: true (frontend/lib/kitchen-orders-api.ts:93) which the kitchen board renders as a visible notice rather than truncating silently. The ARG plus --build-arg route was considered and rejected: a build arg for a value that cannot change behaviour is dead config in the same D-18 sense as a runtime env entry for a NEXT_PUBLIC_ name.'
  'NEXT_PUBLIC_WEBHOOK_RETENTION_DAYS|Reviewed omission: display-only copy on the webhooks pages, defaulted to "30" at both read sites. A wrong value would only mis-word a hint; absence cannot break a request path.'
  'NEXT_PUBLIC_COMPANY_LEGAL_NAME|Reviewed omission: frontend/lib/company.ts:38 falls back to a committed default. These four company-identity values are footer copy, not configuration a cluster needs to resolve.'
  'NEXT_PUBLIC_COMPANY_NUMBER|Reviewed omission: frontend/lib/company.ts:39 falls back to a committed default. See NEXT_PUBLIC_COMPANY_LEGAL_NAME.'
  'NEXT_PUBLIC_COMPANY_REGISTRATION|Reviewed omission: frontend/lib/company.ts:41 falls back to a committed default. See NEXT_PUBLIC_COMPANY_LEGAL_NAME.'
  'NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE|Reviewed omission: frontend/lib/company.ts:42 falls back to "" and the renderer omits the line entirely. See NEXT_PUBLIC_COMPANY_LEGAL_NAME.'
)

# Bare words and hostnames that are only ever correct on a developer laptop.
LOCAL_ONLY_WORDS=(
  localhost
  127.0.0.1
  0.0.0.0
  minioadmin
  guest
  mailhog
  host.docker.internal
)

# ---------------------------------------------------------------------------
# Tooling preflight
# ---------------------------------------------------------------------------
if (( ${BASH_VERSINFO[0]:-0} < 4 )) \
   || { (( ${BASH_VERSINFO[0]:-0} == 4 )) && (( ${BASH_VERSINFO[1]:-0} < 3 )); }; then
    parse_fail "bash >= 4.3 is required (associative arrays + namerefs); found ${BASH_VERSION:-unknown}"
fi

if ! printf 'probe\n' | grep -qP 'pro\w+' 2> /dev/null; then
    parse_fail "GNU 'grep -P' (PCRE) is required — the placeholder regex needs a non-capturing group and a lookahead. On ubuntu-latest (the CI runner) and any GNU grep this is available; on BSD/macOS grep it is not."
fi

for f in "$CORE_DEPLOYMENT" "$EDGE_DEPLOYMENT" "$FE_DEPLOYMENT" "$FE_DOCKERFILE" "$FE_ENV_VALIDATION"; do
    [[ -f "$f" ]] || parse_fail "required input not found: $f"
done
for d in "$CORE_APP_DIR" "$EDGE_SRC_DIR" "$FE_SRC_DIR"; do
    [[ -d "$d" ]] || parse_fail "required source directory not found: $d"
done

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# ---------------------------------------------------------------------------
# selftest_regex — PARSING NOTE 7.
#
# Prove an extractor CAN match before believing what it did not match. Every
# extraction below returns a set, and an empty set reads exactly like a
# perfectly-configured service. A regex broken by a refactor, a locale, or a
# grep that quietly lacks PCRE would hand back that empty set and this gate
# would print PASS over three unexamined services.
# ---------------------------------------------------------------------------
selftest_regex() {
    # selftest_regex <label> <regex> <control-input> <expected-match>
    local label="$1" re="$2" input="$3" expected="$4" got
    got="$(printf '%s\n' "$input" | grep -oP "$re" | head -1 || true)"
    [[ "$got" == "$expected" ]] \
        || parse_fail "extractor self-test FAILED for $label: control input '$input' should yield '$expected' but yielded '${got:-<nothing>}'. The regex matches nothing, so every set it produces would be empty and this gate would report a clean contract over an unexamined service. Fix the regex, do not delete the gate."
}

# Anchored env-list item, shared by all three manifests (PARSING NOTE 1).
INJECTED_RE='^\s+- name: \K[A-Z0-9_]+(?=\s*$)'
selftest_regex 'k8s injected env name' "$INJECTED_RE" '        - name: JTOYE_GATE_SELFTEST' 'JTOYE_GATE_SELFTEST'

extract_injected() {
    # extract_injected <manifest> <service-label>
    local manifest="$1" label="$2"
    local -a names
    mapfile -t names < <(grep -oP "$INJECTED_RE" "$manifest" | sort -u)
    (( ${#names[@]} > 0 )) \
        || parse_fail "extracted 0 injected env names from $manifest ($label) — the '- name: NAME' shape changed and this gate is now blind for that service. Fix the parser, do not delete the gate."
    printf '%s\n' "${names[@]}"
}

# ---------------------------------------------------------------------------
# Local-only default detection
#   Word-boundary match so `localhost` hits inside http://localhost:3000 and a
#   bare-word credential hits as a whole value, without matching a longer word
#   that merely contains it.
# ---------------------------------------------------------------------------
is_local_only_value() {
    # is_local_only_value <value> -> echoes the matched word, returns 1 if none
    local def="$1" word esc
    [[ -n "$def" ]] || return 1
    for word in "${LOCAL_ONLY_WORDS[@]}"; do
        esc="${word//./\\.}"
        if grep -qE "(^|[^[:alnum:]])${esc}([^[:alnum:]]|\$)" <<< "$def"; then
            printf '%s\n' "$word"
            return 0
        fi
    done
    return 1
}

# ---------------------------------------------------------------------------
# Allowlist parsing + hygiene (shared by all six lists)
# ---------------------------------------------------------------------------
HYGIENE_ERRORS=()
OPEN_DEFECTS=()   # "service<TAB>direction<TAB>NAME" for every OPEN DEFECT entry

parse_allowlist() {
    # parse_allowlist <name-of-target-map> <label> <entry>...
    local -n MAP="$1"
    local label="$2"; shift 2
    local entry name reason
    for entry in "$@"; do
        if [[ "$entry" != *"|"* ]]; then
            HYGIENE_ERRORS+=("$label: malformed entry (no '|' separator): '$entry'")
            continue
        fi
        name="${entry%%|*}"
        reason="${entry#*|}"
        if [[ ! "$name" =~ ^[A-Z0-9_]+$ ]]; then
            HYGIENE_ERRORS+=("$label: entry name '$name' is not an uppercase env name")
            continue
        fi
        if [[ -z "${reason//[[:space:]]/}" ]]; then
            HYGIENE_ERRORS+=("$label: entry '$name' has a blank reason. D-08 requires a REASONED allowlist — an unexplained entry is indistinguishable from a forgotten defect.")
            continue
        fi
        if [[ "$reason" == OPEN\ DEFECT* ]] && [[ ! "$reason" =~ \#[0-9]+ ]]; then
            HYGIENE_ERRORS+=("$label: entry '$name' is marked OPEN DEFECT but cites no issue number (#NNN). A tracked gap that names no tracker is an untracked gap.")
            continue
        fi
        if [[ -n "${MAP["$name"]-}" ]]; then
            HYGIENE_ERRORS+=("$label: duplicate entry '$name'")
            continue
        fi
        MAP["$name"]="$reason"
    done
}

note_open_defects() {
    # note_open_defects <name-of-map> <service> <direction>
    local -n M="$1"
    local svc="$2" dir="$3" n
    for n in $(printf '%s\n' "${!M[@]}" | sort); do
        [[ "${M["$n"]}" == OPEN\ DEFECT* ]] && OPEN_DEFECTS+=("$svc	$dir	$n")
    done
    return 0
}

# ===========================================================================
# SERVICE 1 — core-java
#
# Read set  : uppercase ${} placeholders across every application*.yml.
# Injected  : k8s/base/core-java-deployment.yaml.
# Direction (b) refinement, in evaluation order:
#   - no default at all + not injected            -> FAIL (hard-fails boot)
#   - ANY default in the local-only word list      -> FAIL (the DEF-6 shape)
#   - default is an UNRESOLVED property chain      -> FAIL (added for #299)
#   - anything else                                -> pass by rule, counted
#
# The chained-default rule exists because CUSTOMER_JWT_EXPECTED_ISSUER's default
# is `${jtoye.security.customer-jwt.issuer-uri}` — a Spring PROPERTY reference,
# not a value. This gate does not resolve property chains, and pretending an
# unresolved chain is a "safe non-local default" is how half of #299 stayed
# invisible to the very gate that carried the other half in its allowlist.
# Exactly two placeholders on this tree have that shape, and both are the
# expected-issuer family, i.e. the #87 / #299 bug class itself.
# ===========================================================================
mapfile -t CORE_INJECTED < <(extract_injected "$CORE_DEPLOYMENT" 'core-java')

mapfile -t CORE_APP_FILES < <(find "$CORE_APP_DIR" -maxdepth 1 -type f -name 'application*.yml' | sort)
(( ${#CORE_APP_FILES[@]} > 0 )) || parse_fail "no application*.yml found under $CORE_APP_DIR"

CORE_STRIPPED="$TMP/application-all.stripped.yml"
for f in "${CORE_APP_FILES[@]}"; do
    # Strip FULL-LINE comments only (PARSING NOTE 4).
    sed -E 's/^[[:space:]]*#.*$//' "$f"
done > "$CORE_STRIPPED"

# Tolerates one level of nesting in the default (PARSING NOTE 2).
PLACEHOLDER_RE='\$\{([A-Z0-9_]+)(?::((?:[^{}]|\$\{[^}]*\})*))?\}'
# Inner (non-nested) form, used to recover names that only appear as a fallback.
INNER_RE='\$\{[A-Z0-9_]+(?::[^{}]*)?\}'
selftest_regex 'spring placeholder' "$PLACEHOLDER_RE" 'x: ${JTOYE_GATE_SELFTEST:${a.b.c}}' '${JTOYE_GATE_SELFTEST:${a.b.c}}'

mapfile -t PLACEHOLDER_MATCHES < <(grep -ohP "$PLACEHOLDER_RE" "$CORE_STRIPPED" | sort -u)
(( ${#PLACEHOLDER_MATCHES[@]} > 0 )) \
    || parse_fail "extracted 0 \${} placeholders from ${#CORE_APP_FILES[@]} application*.yml file(s) — the extraction regex is broken and this gate is now blind. Fix the parser, do not delete the gate."

declare -A CORE_IS_INJECTED=() CORE_IS_READ=() CORE_HAS_NODEF=() CORE_DEFAULTS=() CORE_IS_CHAINED=()
for n in "${CORE_INJECTED[@]}"; do CORE_IS_INJECTED["$n"]=1; done

record_core_placeholder() {
    # record_core_placeholder '${NAME}' | '${NAME:default}'
    local m="$1" body name def
    body="${m:2}"        # strip the leading '${'
    body="${body%\}}"    # strip the trailing '}'

    if [[ "$body" == *:* ]]; then
        name="${body%%:*}"
        def="${body#*:}"
    else
        name="$body"
        def=""
    fi

    [[ "$name" =~ ^[A-Z0-9_]+$ ]] \
        || parse_fail "extracted a non-env placeholder name '$name' from '$m' — the regex and the uppercase filter disagree."

    CORE_IS_READ["$name"]=1

    if [[ "$body" == *:* ]]; then
        # An unresolved Spring PROPERTY reference is not a value (see the
        # section header). Recorded separately from the default set.
        if [[ "$def" =~ ^\$\{[a-z][a-zA-Z0-9_.\-]*\}$ ]]; then
            CORE_IS_CHAINED["$name"]=1
        fi
        # Set semantics: never append the same default twice (PARSING NOTE 5).
        if [[ $'\n'"${CORE_DEFAULTS["$name"]-}" != *$'\n'"$def"$'\n'* ]]; then
            CORE_DEFAULTS["$name"]="${CORE_DEFAULTS["$name"]-}$def"$'\n'
        fi
    else
        CORE_HAS_NODEF["$name"]=1
    fi
}

for m in "${PLACEHOLDER_MATCHES[@]}"; do
    record_core_placeholder "$m"
    # One level of nesting: recover the inner placeholder(s) from the default,
    # so a name used only as a fallback still counts as read.
    if [[ "$m" == *':${'* ]]; then
        while IFS= read -r inner; do
            [[ -n "$inner" ]] && record_core_placeholder "$inner"
        done < <(printf '%s\n' "${m#*:}" | grep -ohP "$INNER_RE" || true)
    fi
done

mapfile -t CORE_READ_NAMES < <(printf '%s\n' "${!CORE_IS_READ[@]}" | sort)

core_matched_local_default() {
    # core_matched_local_default <name> -> "word<TAB>default" of the FIRST
    # local-only member found, returns 1 if none.
    local name="$1" def word
    while IFS= read -r def; do
        [[ -n "$def" ]] || continue
        if word="$(is_local_only_value "$def")"; then
            printf '%s\t%s\n' "$word" "$def"
            return 0
        fi
    done <<< "${CORE_DEFAULTS["$name"]-}"
    return 1
}

# ===========================================================================
# SERVICE 2 — edge-go
#
# Read set : every env NAME passed as a string literal to os.Getenv,
#            os.LookupEnv, or the getEnv/getEnvInt wrappers, across
#            edge-go/**/*.go EXCLUDING *_test.go (a test's throwaway
#            JTOYE_TEST_* name is not a deployment contract).
# Injected : k8s/base/edge-go-deployment.yaml.
#
# Direction (b) is the STRONG form — read and not injected is a violation
# unless allowlisted — and that is deliberate. The weak form ("only complain
# about a local-only default literal") would have been VACUOUS for this gate's
# own motivating example: JWT_EXPECTED_ISSUER's default is the VARIABLE
# keycloakIssuer, not a localhost literal, so a default-shape rule could never
# have seen the very defect that justified extending the gate here.
# Local-only default literals are still detected — they escalate the message.
# ===========================================================================
mapfile -t EDGE_INJECTED < <(extract_injected "$EDGE_DEPLOYMENT" 'edge-go')

GO_READ_RE='\b(?:os\.Getenv|os\.LookupEnv|getEnv|getEnvInt|getEnvBool|getEnvDuration)\(\s*"\K[A-Z][A-Z0-9_]*(?=")'
GO_DEFAULT_RE='\bgetEnv\(\s*"[A-Z][A-Z0-9_]*"\s*,\s*"[^"]*"'
selftest_regex 'go env read'    "$GO_READ_RE"    'v := getEnv("JTOYE_GATE_SELFTEST", "x")' 'JTOYE_GATE_SELFTEST'
selftest_regex 'go env default' "$GO_DEFAULT_RE" 'v := getEnv("JTOYE_GATE_SELFTEST", "http://localhost:1")' 'getEnv("JTOYE_GATE_SELFTEST", "http://localhost:1"'

mapfile -t EDGE_GO_FILES < <(find "$EDGE_SRC_DIR" -type f -name '*.go' -not -name '*_test.go' | sort)
(( ${#EDGE_GO_FILES[@]} > 0 )) \
    || parse_fail "found 0 non-test .go files under $EDGE_SRC_DIR — edge-go would score a perfect contract on an empty read set."

EDGE_STRIPPED="$TMP/edge-go-all.stripped.go"
for f in "${EDGE_GO_FILES[@]}"; do
    # Strip FULL-LINE comments only (PARSING NOTE 4): `// ...` and the body of
    # a block comment (` * ...` / `/* ... */` on its own line).
    sed -E 's@^[[:space:]]*(//|\*|/\*).*$@@' "$f"
done > "$EDGE_STRIPPED"

declare -A EDGE_IS_INJECTED=() EDGE_IS_READ=() EDGE_DEFAULT=()
for n in "${EDGE_INJECTED[@]}"; do EDGE_IS_INJECTED["$n"]=1; done

mapfile -t EDGE_READ_NAMES < <(grep -ohP "$GO_READ_RE" "$EDGE_STRIPPED" | sort -u)
(( ${#EDGE_READ_NAMES[@]} > 0 )) \
    || parse_fail "extracted 0 env reads from ${#EDGE_GO_FILES[@]} edge-go .go file(s) — the os.Getenv/getEnv shape changed and this gate is now blind for edge-go. Fix the parser, do not delete the gate."
for n in "${EDGE_READ_NAMES[@]}"; do EDGE_IS_READ["$n"]=1; done

while IFS= read -r hit; do
    [[ -n "$hit" ]] || continue
    # hit looks like: getEnv("NAME", "DEFAULT"
    ename="${hit#*\"}"; ename="${ename%%\"*}"
    edef="${hit#*,}"; edef="${edef#*\"}"; edef="${edef%\"*}"
    [[ -n "${EDGE_DEFAULT["$ename"]-}" ]] || EDGE_DEFAULT["$ename"]="$edef"
done < <(grep -ohP "$GO_DEFAULT_RE" "$EDGE_STRIPPED" || true)

# ===========================================================================
# SERVICE 3 — frontend
#
# THE BUILD-TIME / RUNTIME DISTINCTION IS THE WHOLE POINT HERE (issue #298).
#
# Next.js inlines every LITERAL `process.env.NEXT_PUBLIC_*` reference into the
# bundle at Docker BUILD time, for the names that are PRESENT in the build
# environment. So for a NEXT_PUBLIC_* name that frontend/Dockerfile declares as
# an ARG, a runtime `env:` entry in the Deployment reaches NOTHING — it is DEAD
# CONFIG, and a naive "is it injected?" check would score it as correctly
# supplied, i.e. report the opposite of the truth. That is D-18 (Phase 26),
# where NEXT_PUBLIC_API_URL was injected at runtime, reached nothing, and
# additionally MASKED the boot-time validator.
#
# Hence two channels, and two rules:
#   - build-arg channel : `ARG NAME` in frontend/Dockerfile. Enforced end to
#     end — the Dockerfile refuses to build on an empty required build-arg,
#     scripts/k8s-local-up.sh dies on one, and ci-cd.yaml passes them from repo
#     variables. A NEXT_PUBLIC_* name on this channel is SUPPLIED.
#   - runtime channel   : `env:` in k8s/base/frontend-deployment.yaml. Correct
#     for server-side names, and for the NEXT_PUBLIC_* names that deliberately
#     have NO ARG (frontend/Dockerfile explains, with measured evidence, that
#     leaving a name absent at build time is what keeps it runtime-resolvable).
#
#   Direction (a2) therefore fails a NEXT_PUBLIC_* name that is injected at
#   RUNTIME while ALSO being declared as a build ARG — that combination is
#   provably dead config, and it is the exact shape D-18 removed.
#
# Read set : literal `process.env.NAME` across the frontend APPLICATION tree
#   (e2e specs, __tests__, *.test.*, *.spec.*, playwright/jest config excluded —
#   a Playwright fixture variable is not a deployment contract), UNION the names
#   declared in env-validation.ts's requiredEnvVars/optionalEnvVars arrays.
#   The union is load-bearing: env-validation.ts reads its list through the
#   DYNAMIC form `process.env[envVar]`, which is not statically resolvable, and
#   NEXTAUTH_SECRET appears NOWHERE else in the application tree. Without the
#   array parse it would be scored injected-but-unread — a false direction-(a)
#   violation against a correct manifest.
# ===========================================================================
mapfile -t FE_INJECTED < <(extract_injected "$FE_DEPLOYMENT" 'frontend')

TS_READ_RE='\bprocess\.env\.\K[A-Z][A-Z0-9_]*'
TS_LIST_RE="'\K[A-Z][A-Z0-9_]*(?=')"
ARG_RE='^ARG \K[A-Z][A-Z0-9_]*(?=\s*$)'
selftest_regex 'ts process.env read' "$TS_READ_RE" 'const x = process.env.JTOYE_GATE_SELFTEST || ""' 'JTOYE_GATE_SELFTEST'
selftest_regex 'ts declared list'    "$TS_LIST_RE" "  'JTOYE_GATE_SELFTEST'," 'JTOYE_GATE_SELFTEST'
selftest_regex 'dockerfile build arg' "$ARG_RE"    'ARG JTOYE_GATE_SELFTEST' 'JTOYE_GATE_SELFTEST'

mapfile -t FE_APP_FILES < <(
    find "$FE_SRC_DIR" -type f \( -name '*.ts' -o -name '*.tsx' -o -name '*.mjs' -o -name '*.js' \) \
        -not -path '*/node_modules/*' \
        -not -path '*/.next/*' \
        -not -path "$FE_SRC_DIR/e2e/*" \
        -not -path '*/__tests__/*' \
        -not -name '*.test.*' \
        -not -name '*.spec.*' \
        -not -name 'playwright.config.ts' \
        -not -name 'jest.setup.js' \
        -not -name 'jest.config.js' \
    | sort
)
(( ${#FE_APP_FILES[@]} > 0 )) \
    || parse_fail "found 0 frontend application source files under $FE_SRC_DIR — the frontend would score a perfect contract on an empty read set."

FE_STRIPPED="$TMP/frontend-all.stripped.ts"
for f in "${FE_APP_FILES[@]}"; do
    # Strip FULL-LINE comments only (PARSING NOTE 4). The block-comment body
    # form ` * ...` is required: frontend/lib/customer-orders-server.ts:27
    # contains the literal text `process.env.NEXT_PUBLIC_*` inside one.
    sed -E 's@^[[:space:]]*(//|\*|/\*).*$@@' "$f"
done > "$FE_STRIPPED"

declare -A FE_IS_INJECTED=() FE_IS_READ=() FE_IS_BUILD_ARG=()
for n in "${FE_INJECTED[@]}"; do FE_IS_INJECTED["$n"]=1; done

mapfile -t FE_LITERAL_READS < <(grep -ohP "$TS_READ_RE" "$FE_STRIPPED" | sort -u)
(( ${#FE_LITERAL_READS[@]} > 0 )) \
    || parse_fail "extracted 0 process.env reads from ${#FE_APP_FILES[@]} frontend source file(s) — the extraction regex is broken and this gate is now blind for the frontend. Fix the parser, do not delete the gate."
for n in "${FE_LITERAL_READS[@]}"; do FE_IS_READ["$n"]=1; done

# The DYNAMIC form: names declared in env-validation.ts's two arrays.
FE_DECLARED_SRC="$TMP/frontend-declared-lists.ts"
sed -n '/const requiredEnvVars/,/\];/p;/const optionalEnvVars/,/\];/p' "$FE_ENV_VALIDATION" > "$FE_DECLARED_SRC"
mapfile -t FE_DECLARED < <(grep -ohP "$TS_LIST_RE" "$FE_DECLARED_SRC" | sort -u)
(( ${#FE_DECLARED[@]} > 0 )) \
    || parse_fail "extracted 0 declared env names from the requiredEnvVars/optionalEnvVars arrays in $FE_ENV_VALIDATION — env-validation.ts reads them through the dynamic process.env[expr] form, so losing them makes every one of them look injected-but-unread. Fix the parser, do not delete the gate."
for n in "${FE_DECLARED[@]}"; do FE_IS_READ["$n"]=1; done

mapfile -t FE_BUILD_ARGS < <(grep -oP "$ARG_RE" "$FE_DOCKERFILE" | sort -u)
(( ${#FE_BUILD_ARGS[@]} > 0 )) \
    || parse_fail "extracted 0 'ARG NAME' build args from $FE_DOCKERFILE — with an empty build-arg channel every NEXT_PUBLIC_* name would look unsupplied AND no runtime injection could ever be flagged as dead config. Fix the parser, do not delete the gate."
for n in "${FE_BUILD_ARGS[@]}"; do FE_IS_BUILD_ARG["$n"]=1; done

mapfile -t FE_READ_NAMES < <(printf '%s\n' "${!FE_IS_READ[@]}" | sort)

# ===========================================================================
# Allowlist parsing (after every read/injected set exists, because the STALE
# rules are evaluated against them)
# ===========================================================================
declare -A A_CORE_A=() A_CORE_B=() A_EDGE_A=() A_EDGE_B=() A_FE_A=() A_FE_B=()

parse_allowlist A_CORE_A 'core-java allowlist (a)' ${ALLOW_CORE_A[@]+"${ALLOW_CORE_A[@]}"}
parse_allowlist A_CORE_B 'core-java allowlist (b)' ${ALLOW_CORE_B[@]+"${ALLOW_CORE_B[@]}"}
parse_allowlist A_EDGE_A 'edge-go allowlist (a)'   ${ALLOW_EDGE_A[@]+"${ALLOW_EDGE_A[@]}"}
parse_allowlist A_EDGE_B 'edge-go allowlist (b)'   ${ALLOW_EDGE_B[@]+"${ALLOW_EDGE_B[@]}"}
parse_allowlist A_FE_A   'frontend allowlist (a)'  ${ALLOW_FE_A[@]+"${ALLOW_FE_A[@]}"}
parse_allowlist A_FE_B   'frontend allowlist (b)'  ${ALLOW_FE_B[@]+"${ALLOW_FE_B[@]}"}

note_open_defects A_CORE_A 'core-java' '(a)'
note_open_defects A_CORE_B 'core-java' '(b)'
note_open_defects A_EDGE_A 'edge-go'   '(a)'
note_open_defects A_EDGE_B 'edge-go'   '(b)'
note_open_defects A_FE_A   'frontend'  '(a)'
note_open_defects A_FE_B   'frontend'  '(b)'

# --- staleness: core-java ---------------------------------------------------
for name in $(printf '%s\n' "${!A_CORE_A[@]}" | sort); do
    if [[ -z "${CORE_IS_INJECTED["$name"]-}" ]]; then
        HYGIENE_ERRORS+=("core-java allowlist (a): STALE entry '$name' — no manifest injects that env any more, so the exemption is dead. Remove the entry.")
    elif [[ -n "${CORE_IS_READ["$name"]-}" ]]; then
        HYGIENE_ERRORS+=("core-java allowlist (a): STALE entry '$name' — some application*.yml now reads it as a \${} placeholder, so it is no longer an injected-but-unread env. Remove the entry.")
    fi
done

for name in $(printf '%s\n' "${!A_CORE_B[@]}" | sort); do
    if [[ -z "${CORE_IS_READ["$name"]-}" ]]; then
        HYGIENE_ERRORS+=("core-java allowlist (b): STALE entry '$name' — no application*.yml reads that placeholder any more, so the exemption is dead. Remove the entry.")
    elif [[ -n "${CORE_IS_INJECTED["$name"]-}" ]]; then
        HYGIENE_ERRORS+=("core-java allowlist (b): STALE entry '$name' — a manifest now SUPPLIES it, so it is no longer an unsupplied omission. Remove the entry rather than leaving a standing excuse for a variable that is already fixed.")
    elif [[ -z "${CORE_HAS_NODEF["$name"]-}" ]] \
         && [[ -z "${CORE_IS_CHAINED["$name"]-}" ]] \
         && ! core_matched_local_default "$name" > /dev/null; then
        HYGIENE_ERRORS+=("core-java allowlist (b): STALE entry '$name' — its default(s) are no longer local-only, it is not default-less, and it is not an unresolved property chain, so it would pass by rule without an exemption. Remove the entry.")
    fi
done

# --- staleness: edge-go -----------------------------------------------------
for name in $(printf '%s\n' "${!A_EDGE_A[@]}" | sort); do
    if [[ -z "${EDGE_IS_INJECTED["$name"]-}" ]]; then
        HYGIENE_ERRORS+=("edge-go allowlist (a): STALE entry '$name' — k8s/base/edge-go-deployment.yaml no longer injects it. Remove the entry.")
    elif [[ -n "${EDGE_IS_READ["$name"]-}" ]]; then
        HYGIENE_ERRORS+=("edge-go allowlist (a): STALE entry '$name' — edge-go now reads it, so it is no longer injected-but-unread. Remove the entry.")
    fi
done

for name in $(printf '%s\n' "${!A_EDGE_B[@]}" | sort); do
    if [[ -z "${EDGE_IS_READ["$name"]-}" ]]; then
        HYGIENE_ERRORS+=("edge-go allowlist (b): STALE entry '$name' — no edge-go source reads it any more, so the exemption is dead. Remove the entry.")
    elif [[ -n "${EDGE_IS_INJECTED["$name"]-}" ]]; then
        HYGIENE_ERRORS+=("edge-go allowlist (b): STALE entry '$name' — the manifest now SUPPLIES it. Remove the entry rather than leaving a standing excuse for a variable that is already fixed.")
    fi
done

# --- staleness: frontend ----------------------------------------------------
for name in $(printf '%s\n' "${!A_FE_A[@]}" | sort); do
    if [[ -z "${FE_IS_INJECTED["$name"]-}" ]]; then
        HYGIENE_ERRORS+=("frontend allowlist (a): STALE entry '$name' — k8s/base/frontend-deployment.yaml no longer injects it. Remove the entry.")
    elif [[ -n "${FE_IS_READ["$name"]-}" ]] && [[ -z "${FE_IS_BUILD_ARG["$name"]-}" ]]; then
        HYGIENE_ERRORS+=("frontend allowlist (a): STALE entry '$name' — the frontend now reads it and it is not a build-time-only name, so it is no longer injected-but-unread. Remove the entry.")
    fi
done

for name in $(printf '%s\n' "${!A_FE_B[@]}" | sort); do
    if [[ -z "${FE_IS_READ["$name"]-}" ]]; then
        HYGIENE_ERRORS+=("frontend allowlist (b): STALE entry '$name' — no frontend source reads it and env-validation.ts does not declare it, so the exemption is dead. Remove the entry.")
    elif [[ -n "${FE_IS_INJECTED["$name"]-}" ]]; then
        HYGIENE_ERRORS+=("frontend allowlist (b): STALE entry '$name' — the manifest now SUPPLIES it at runtime. Remove the entry rather than leaving a standing excuse for a variable that is already fixed.")
    elif [[ "$name" == NEXT_PUBLIC_* ]] && [[ -n "${FE_IS_BUILD_ARG["$name"]-}" ]]; then
        HYGIENE_ERRORS+=("frontend allowlist (b): STALE entry '$name' — frontend/Dockerfile now declares it as a build ARG, so it is supplied through the enforced build-arg channel. Remove the entry.")
    fi
done

# ===========================================================================
# Direction (a) — injected but unread, per service
# ===========================================================================
CORE_A_VIOLATIONS=(); CORE_A_ALLOWED=0; CORE_A_OK=0
for name in "${CORE_INJECTED[@]}"; do
    if [[ -n "${CORE_IS_READ["$name"]-}" ]]; then
        (( ++CORE_A_OK ))
    elif [[ -n "${A_CORE_A["$name"]-}" ]]; then
        (( ++CORE_A_ALLOWED ))
    else
        CORE_A_VIOLATIONS+=("$name")
    fi
done

EDGE_A_VIOLATIONS=(); EDGE_A_ALLOWED=0; EDGE_A_OK=0
for name in "${EDGE_INJECTED[@]}"; do
    if [[ -n "${EDGE_IS_READ["$name"]-}" ]]; then
        (( ++EDGE_A_OK ))
    elif [[ -n "${A_EDGE_A["$name"]-}" ]]; then
        (( ++EDGE_A_ALLOWED ))
    else
        EDGE_A_VIOLATIONS+=("$name")
    fi
done

FE_A_VIOLATIONS=(); FE_A_DEADCONFIG=(); FE_A_ALLOWED=0; FE_A_OK=0
for name in "${FE_INJECTED[@]}"; do
    if [[ "$name" == NEXT_PUBLIC_* ]] && [[ -n "${FE_IS_BUILD_ARG["$name"]-}" ]]; then
        # (a2) build-time name injected at runtime: dead config even though the
        # code "reads" it. This is D-18 and it is not allowlistable — the fix is
        # to delete the runtime env: entry and pass the build-arg instead.
        FE_A_DEADCONFIG+=("$name")
    elif [[ -n "${FE_IS_READ["$name"]-}" ]]; then
        (( ++FE_A_OK ))
    elif [[ -n "${A_FE_A["$name"]-}" ]]; then
        (( ++FE_A_ALLOWED ))
    else
        FE_A_VIOLATIONS+=("$name")
    fi
done

# ===========================================================================
# Direction (b) — expected but unsupplied, per service
# ===========================================================================
CORE_B_NODEF=(); CORE_B_LOCAL=(); CORE_B_CHAINED=()
CORE_B_ALLOWED=0; CORE_B_SUPPLIED=0; CORE_B_BYRULE=0
for name in "${CORE_READ_NAMES[@]}"; do
    if [[ -n "${CORE_IS_INJECTED["$name"]-}" ]]; then
        (( ++CORE_B_SUPPLIED ))
        continue
    fi
    if [[ -n "${CORE_HAS_NODEF["$name"]-}" ]]; then
        if [[ -n "${A_CORE_B["$name"]-}" ]]; then (( ++CORE_B_ALLOWED )); else CORE_B_NODEF+=("$name"); fi
        continue
    fi
    if hit="$(core_matched_local_default "$name")"; then
        if [[ -n "${A_CORE_B["$name"]-}" ]]; then (( ++CORE_B_ALLOWED )); else CORE_B_LOCAL+=("$name	${hit}"); fi
        continue
    fi
    if [[ -n "${CORE_IS_CHAINED["$name"]-}" ]]; then
        if [[ -n "${A_CORE_B["$name"]-}" ]]; then (( ++CORE_B_ALLOWED )); else CORE_B_CHAINED+=("$name"); fi
        continue
    fi
    (( ++CORE_B_BYRULE ))
done

EDGE_B_VIOLATIONS=(); EDGE_B_ALLOWED=0; EDGE_B_SUPPLIED=0
for name in "${EDGE_READ_NAMES[@]}"; do
    if [[ -n "${EDGE_IS_INJECTED["$name"]-}" ]]; then
        (( ++EDGE_B_SUPPLIED ))
    elif [[ -n "${A_EDGE_B["$name"]-}" ]]; then
        (( ++EDGE_B_ALLOWED ))
    else
        edef="${EDGE_DEFAULT["$name"]-}"
        if word="$(is_local_only_value "$edef")"; then
            EDGE_B_VIOLATIONS+=("$name	LOCAL-ONLY default '$edef' (token '$word')")
        else
            EDGE_B_VIOLATIONS+=("$name	default '${edef:-<none/non-literal>}'")
        fi
    fi
done

FE_B_VIOLATIONS=(); FE_B_ALLOWED=0; FE_B_SUPPLIED_RUNTIME=0; FE_B_SUPPLIED_BUILD=0
for name in "${FE_READ_NAMES[@]}"; do
    if [[ -n "${FE_IS_INJECTED["$name"]-}" ]]; then
        (( ++FE_B_SUPPLIED_RUNTIME ))
    elif [[ "$name" == NEXT_PUBLIC_* ]] && [[ -n "${FE_IS_BUILD_ARG["$name"]-}" ]]; then
        (( ++FE_B_SUPPLIED_BUILD ))
    elif [[ -n "${A_FE_B["$name"]-}" ]]; then
        (( ++FE_B_ALLOWED ))
    else
        if [[ "$name" == NEXT_PUBLIC_* ]]; then
            FE_B_VIOLATIONS+=("$name	NEXT_PUBLIC_* with no Dockerfile ARG and no runtime env: — supplied by NEITHER channel")
        else
            FE_B_VIOLATIONS+=("$name	server-side runtime env supplied by no manifest")
        fi
    fi
done

# ===========================================================================
# Classification summary — printed before any verdict so a reviewer sees the
# shape of the inventory without reading the code.
# ===========================================================================
echo "env contract (D-07 / D-08, extended to all three services by #298)"
echo
echo "core-java"
echo "  manifest : k8s/base/core-java-deployment.yaml"
echo "  config   : ${#CORE_APP_FILES[@]} application*.yml file(s) under core-java/src/main/resources/"
printf '  (a) %-44s %d\n' 'injected env names'                   "${#CORE_INJECTED[@]}"
printf '  (a) %-44s %d\n' 'read by some application*.yml'        "$CORE_A_OK"
printf '  (a) %-44s %d\n' 'allowlisted (reasoned)'               "$CORE_A_ALLOWED"
printf '  (a) %-44s %d\n' 'VIOLATIONS'                           "${#CORE_A_VIOLATIONS[@]}"
printf '  (b) %-44s %d\n' 'distinct ${} placeholders'            "${#CORE_READ_NAMES[@]}"
printf '  (b) %-44s %d\n' 'supplied by the manifest'             "$CORE_B_SUPPLIED"
printf '  (b) %-44s %d\n' 'allowlisted (reasoned)'               "$CORE_B_ALLOWED"
printf '  (b) %-44s %d\n' 'pass by rule (safe non-local default)' "$CORE_B_BYRULE"
printf '  (b) %-44s %d\n' 'VIOLATIONS (no default at all)'       "${#CORE_B_NODEF[@]}"
printf '  (b) %-44s %d\n' 'VIOLATIONS (local-only default)'      "${#CORE_B_LOCAL[@]}"
printf '  (b) %-44s %d\n' 'VIOLATIONS (unresolved property chain)' "${#CORE_B_CHAINED[@]}"
echo
echo "edge-go"
echo "  manifest : k8s/base/edge-go-deployment.yaml"
echo "  source   : ${#EDGE_GO_FILES[@]} non-test .go file(s) under edge-go/"
printf '  (a) %-44s %d\n' 'injected env names'                   "${#EDGE_INJECTED[@]}"
printf '  (a) %-44s %d\n' 'read by edge-go source'               "$EDGE_A_OK"
printf '  (a) %-44s %d\n' 'allowlisted (reasoned)'               "$EDGE_A_ALLOWED"
printf '  (a) %-44s %d\n' 'VIOLATIONS'                           "${#EDGE_A_VIOLATIONS[@]}"
printf '  (b) %-44s %d\n' 'distinct env names read'              "${#EDGE_READ_NAMES[@]}"
printf '  (b) %-44s %d\n' 'supplied by the manifest'             "$EDGE_B_SUPPLIED"
printf '  (b) %-44s %d\n' 'allowlisted (reasoned)'               "$EDGE_B_ALLOWED"
printf '  (b) %-44s %d\n' 'VIOLATIONS'                           "${#EDGE_B_VIOLATIONS[@]}"
echo
echo "frontend"
echo "  manifest : k8s/base/frontend-deployment.yaml (runtime channel)"
echo "  build    : frontend/Dockerfile — ${#FE_BUILD_ARGS[@]} ARG name(s) (build-time channel)"
echo "  source   : ${#FE_APP_FILES[@]} application file(s) + ${#FE_DECLARED[@]} name(s) declared in env-validation.ts"
printf '  (a) %-44s %d\n' 'injected env names'                   "${#FE_INJECTED[@]}"
printf '  (a) %-44s %d\n' 'read by frontend source/declaration'  "$FE_A_OK"
printf '  (a) %-44s %d\n' 'allowlisted (reasoned)'               "$FE_A_ALLOWED"
printf '  (a) %-44s %d\n' 'VIOLATIONS (injected, never read)'    "${#FE_A_VIOLATIONS[@]}"
printf '  (a) %-44s %d\n' 'VIOLATIONS (build-time name, dead at runtime)' "${#FE_A_DEADCONFIG[@]}"
printf '  (b) %-44s %d\n' 'distinct env names read'              "${#FE_READ_NAMES[@]}"
printf '  (b) %-44s %d\n' 'supplied at runtime (env:)'           "$FE_B_SUPPLIED_RUNTIME"
printf '  (b) %-44s %d\n' 'supplied at build (Dockerfile ARG)'   "$FE_B_SUPPLIED_BUILD"
printf '  (b) %-44s %d\n' 'allowlisted (reasoned)'               "$FE_B_ALLOWED"
printf '  (b) %-44s %d\n' 'VIOLATIONS'                           "${#FE_B_VIOLATIONS[@]}"
echo

if (( ${#OPEN_DEFECTS[@]} > 0 )); then
    echo "OPEN DEFECTS carried in the allowlists — tracked live gaps, NOT reasoned omissions."
    echo "These are printed on every run so a green gate never reads as a settled contract:"
    while IFS=$'\t' read -r svc dir nm; do
        echo "  - [$svc $dir] $nm"
    done < <(printf '%s\n' "${OPEN_DEFECTS[@]}")
    echo
fi

# ===========================================================================
# Verdict
# ===========================================================================
VIOLATION=0

if (( ${#HYGIENE_ERRORS[@]} > 0 )); then
    echo "ALLOWLIST HYGIENE — an allowlist is not in a reviewable state:" >&2
    for e in "${HYGIENE_ERRORS[@]}"; do echo "  - $e" >&2; done
    echo >&2
    VIOLATION=1
fi

report_direction_a() {
    # report_direction_a <service> <read-surface> <name>...
    local svc="$1" surface="$2"; shift 2
    (( $# > 0 )) || return 0
    echo "DIRECTION (a) VIOLATION [$svc] — env injected by the manifest but read by $surface:" >&2
    local n; for n in "$@"; do echo "  - $n" >&2; done
    echo >&2
    echo "  This is exactly DEF-4: a manifest feeding an env that nothing reads." >&2
    echo "  The injected value reaches NOTHING and the service silently uses its own" >&2
    echo "  literal default instead — which is why the class survived review, CI and a" >&2
    echo "  live rehearsal. Fix the env NAME in the manifest to match the name the code" >&2
    echo "  actually reads (do NOT rename the secret key), or add the read to the code." >&2
    echo "  Only add an allowlist entry if the env is genuinely runtime-native." >&2
    echo >&2
    VIOLATION=1
}

report_direction_a 'core-java' 'NO application*.yml'          ${CORE_A_VIOLATIONS[@]+"${CORE_A_VIOLATIONS[@]}"}
report_direction_a 'edge-go'   'NO edge-go source'            ${EDGE_A_VIOLATIONS[@]+"${EDGE_A_VIOLATIONS[@]}"}
report_direction_a 'frontend'  'NO frontend source'           ${FE_A_VIOLATIONS[@]+"${FE_A_VIOLATIONS[@]}"}

if (( ${#FE_A_DEADCONFIG[@]} > 0 )); then
    echo "DIRECTION (a) VIOLATION [frontend] — BUILD-TIME name injected as a RUNTIME env (dead config, D-18):" >&2
    for name in "${FE_A_DEADCONFIG[@]}"; do
        echo "  - $name  (declared 'ARG $name' in frontend/Dockerfile)" >&2
    done
    echo >&2
    echo "  Next.js inlines literal process.env.NEXT_PUBLIC_* references into the bundle" >&2
    echo "  at BUILD time, so a runtime env: entry for a build-arg name reaches NOTHING." >&2
    echo "  Worse, it MASKS the boot-time validator: env-validation.ts reads its list via" >&2
    echo "  the dynamic process.env[envVar] form, which Next.js does NOT inline, so the" >&2
    echo "  injected runtime value satisfies the required-var check while every inlined" >&2
    echo "  literal in the app is still undefined. That is defect D-18 verbatim." >&2
    echo "  Fix: DELETE the env: entry from k8s/base/frontend-deployment.yaml and pass" >&2
    echo "  the value as --build-arg (see .github/workflows/ci-cd.yaml build-and-push and" >&2
    echo "  scripts/k8s-local-up.sh). This is deliberately NOT allowlistable." >&2
    echo >&2
    VIOLATION=1
fi

if (( ${#CORE_B_NODEF[@]} > 0 )); then
    echo "DIRECTION (b) VIOLATION [core-java] — placeholder with NO default that no manifest supplies:" >&2
    for name in "${CORE_B_NODEF[@]}"; do echo "  - $name" >&2; done
    echo >&2
    echo "  Spring cannot resolve these, so the container hard-fails at boot." >&2
    echo "  Supply them from app-config or a Secret in k8s/base/core-java-deployment.yaml." >&2
    echo >&2
    VIOLATION=1
fi

if (( ${#CORE_B_LOCAL[@]} > 0 )); then
    echo "DIRECTION (b) VIOLATION [core-java] — placeholder whose default is LOCAL-ONLY and that no manifest supplies:" >&2
    while IFS=$'\t' read -r name word def; do
        echo "  - $name  (default: '$def'  — local-only token: '$word')" >&2
    done < <(printf '%s\n' "${CORE_B_LOCAL[@]}")
    echo >&2
    echo "  This is the DEF-6 shape: outside a developer laptop the default is wrong," >&2
    echo "  and the failure is SILENT — media writes go nowhere, email goes to a" >&2
    echo "  loopback relay, a production link points at localhost. Either supply the" >&2
    echo "  value from app-config / a Secret, or add an ALLOWLIST entry WITH A REASON" >&2
    echo "  so the omission becomes a reviewed inventory instead of a surprise." >&2
    echo "  Never widen the allowlist just to make this gate pass." >&2
    echo >&2
    VIOLATION=1
fi

if (( ${#CORE_B_CHAINED[@]} > 0 )); then
    echo "DIRECTION (b) VIOLATION [core-java] — placeholder whose ONLY default is an unresolved property chain:" >&2
    for name in "${CORE_B_CHAINED[@]}"; do
        echo "  - $name  (default: '$(printf '%s' "${CORE_DEFAULTS["$name"]-}" | tr -d '\n')')" >&2
    done
    echo >&2
    echo "  The default is a Spring PROPERTY reference, not a value. This gate does not" >&2
    echo "  resolve property chains, so it cannot certify the chain terminates in" >&2
    echo "  something safe outside a laptop — and on this tree one such chain terminates" >&2
    echo "  in a localhost literal (#299). Treating an unresolved chain as a 'safe" >&2
    echo "  non-local default' is how that half of #299 stayed invisible. Either supply" >&2
    echo "  the value, or add an ALLOWLIST entry naming where the chain lands." >&2
    echo >&2
    VIOLATION=1
fi

if (( ${#EDGE_B_VIOLATIONS[@]} > 0 )); then
    echo "DIRECTION (b) VIOLATION [edge-go] — env read by edge-go that no manifest supplies:" >&2
    while IFS=$'\t' read -r name detail; do
        echo "  - $name  ($detail)" >&2
    done < <(printf '%s\n' "${EDGE_B_VIOLATIONS[@]}")
    echo >&2
    echo "  Go has no unresolved-placeholder boot failure: an unset env is just \"\" and" >&2
    echo "  the read site quietly takes its fallback. That is precisely why this needs a" >&2
    echo "  gate — JWT_EXPECTED_ISSUER was read here from the issue #87 fix onward while" >&2
    echo "  NO manifest supplied it, and every k8s environment silently validated 'iss'" >&2
    echo "  against the JWKS host. Either inject it in" >&2
    echo "  k8s/base/edge-go-deployment.yaml, or add an ALLOWLIST entry WITH A REASON." >&2
    echo >&2
    VIOLATION=1
fi

if (( ${#FE_B_VIOLATIONS[@]} > 0 )); then
    echo "DIRECTION (b) VIOLATION [frontend] — env read by the frontend that NEITHER channel supplies:" >&2
    while IFS=$'\t' read -r name detail; do
        echo "  - $name  ($detail)" >&2
    done < <(printf '%s\n' "${FE_B_VIOLATIONS[@]}")
    echo >&2
    echo "  The frontend has TWO supply channels and they are not interchangeable:" >&2
    echo "    - a NEXT_PUBLIC_* name is inlined at BUILD time, so it needs an 'ARG NAME'" >&2
    echo "      in frontend/Dockerfile plus a --build-arg from CI / k8s-local-up.sh;" >&2
    echo "      a runtime env: entry for such a name is DEAD CONFIG (D-18)." >&2
    echo "    - a server-side name is read from the container environment, so it needs" >&2
    echo "      an env: entry in k8s/base/frontend-deployment.yaml." >&2
    echo "  Pick the channel that matches the name, or add an ALLOWLIST entry WITH A" >&2
    echo "  REASON stating what the code does when the value is absent." >&2
    echo >&2
    VIOLATION=1
fi

if (( VIOLATION != 0 )); then
    fail "the env contract is broken — see the violations above. Fix the manifest, the build args or the code; only widen an allowlist when the omission is genuinely reviewed and you can state why."
fi

echo "PASS: core-java ${#CORE_INJECTED[@]} injected / ${#CORE_READ_NAMES[@]} read; edge-go ${#EDGE_INJECTED[@]} injected / ${#EDGE_READ_NAMES[@]} read; frontend ${#FE_INJECTED[@]} injected / ${#FE_BUILD_ARGS[@]} build-arg / ${#FE_READ_NAMES[@]} read. Both directions hold for all three services, with $((CORE_A_ALLOWED + CORE_B_ALLOWED + EDGE_A_ALLOWED + EDGE_B_ALLOWED + FE_A_ALLOWED + FE_B_ALLOWED)) allowlisted omission(s), of which ${#OPEN_DEFECTS[@]} are tracked OPEN DEFECTS listed above."
