#!/usr/bin/env bash
# check-render-invariants.sh — rendered-manifest assertions that pin the
# Phase 26 fixes so their defect classes cannot silently return.
#
# WHY A SEPARATE, RENDER-LEVEL GATE
#   Everything this script asserts was a REAL defect that a raw-file review, a
#   passing CI run and (for two of them) a live cluster rehearsal all missed. The
#   common cause: the thing that reaches the cluster is the kustomize RENDER, and
#   nothing was asserting on the render.
#   k8s/scripts/validate-networkpolicies.py parses the RAW files, so it is
#   structurally incapable of seeing INV-3 — the label transformer only injects
#   the offending labels at render time. That is why INV-3 lives here.
#
# THE INVARIANTS, THE DEFECT EACH PINS, AND THE PLAN THAT FIXED IT
#
#   INV-1  DEF-1 / INFRA-02a, RENDER level, per target. Every rendered DB_PORT
#          EnvVar must carry `valueFrom:` and NO `value:`. A hardcoded port made
#          every environment's DB port a manifest edit; the host dev Postgres
#          publishes 5433, so a local cluster could not work at all. Fixed in plan
#          26-01 by routing DB_PORT through postgres-credentials/port.
#
#          THIS ASSERTION WAS REWRITTEN (code review WR-01). It used to be a
#          SOURCE-level grep for a single anchored literal:
#              grep -nE '^[[:space:]]+value: "5432"' k8s/base/core-java-deployment.yaml
#          which matched ONLY the double-quoted spelling and read only that one
#          file. That was EVADABLE, and the evasion was DEMONSTRATED: with DB_PORT
#          regressed to the single-quoted `value: '5432'`, this gate AND
#          check-env-contract.sh both still exited 0 with the defect they exist to
#          pin fully restored. YAML has at least three equivalent spellings
#          ("5432", '5432', bare 5432); kustomize normalises all of them to
#          `value: "5432"` in the render, so asserting on the RENDER removes the
#          spelling degree of freedom entirely. Asserting on the SHAPE
#          (valueFrom present, value absent) removes the literal too: a regression
#          to `value: "5433"` or to any other port literal fails just the same,
#          because the defect was never the digits — it was the port being a
#          manifest constant instead of config (GLOBAL_RULE_6).
#          Two further gains fall out of moving it into the per-target loop: it now
#          covers ALL FOUR targets rather than one base file (an overlay that
#          patched a literal back in was previously invisible), and it covers BOTH
#          DB_PORT sites per render (the core-java Deployment and the pg-backup
#          CronJob) rather than only the Deployment.
#
#          HOW INV-1 AND INV-2 DIFFER (they are complementary, not redundant):
#            INV-1 is DB_PORT-specific and fires on `value:` ALONE — the plain
#                  regression, where the secretKeyRef is REPLACED by a literal.
#                  INV-2 is blind to that case: nothing carries both keys, so the
#                  render is a legal manifest that applies cleanly and quietly
#                  hardcodes the port again.
#            INV-2 is universal (every EnvVar) and fires only when `value:` and
#                  `valueFrom:` are BOTH present — the apply-time rejection
#                  (PIT-2), which is a different failure mode: it never reaches a
#                  running pod at all.
#          A DB_PORT carrying both trips BOTH, which is correct: it is
#          simultaneously a hardcoded port and an unappliable manifest.
#
#   INV-2  PIT-2, RENDER level, per target. No rendered EnvVar may carry BOTH
#          `value:` and `valueFrom:`. `kubectl kustomize` emits that combination
#          without complaint (a strategic-merge patch that adds `valueFrom` to an
#          env item which still has `value:` merges rather than replaces), but the
#          API server rejects the apply:
#            env[i].valueFrom: Invalid value: "": may not be specified when
#            'value' is not empty
#          So the render can look fine in CI and fail at rollout. This is the
#          reason DEF-1 had to DELETE the literal in the base rather than patch
#          around it in an overlay. Pinned by plan 26-01.
#
#   INV-3  D-17, RENDER level, per target. A `matchLabels` block that selects
#          kube-dns (`k8s-app: kube-dns`) must contain NOTHING ELSE. The base
#          kustomization's label transformer used `includeSelectors: true`, which
#          injected app.kubernetes.io/managed-by, app.kubernetes.io/part-of and
#          environment into the DNS-egress podSelector of
#          networkpolicies/20-core-java.yaml. Real kube-dns pods carry none of
#          those, so the selector matched NOTHING and core-java had ZERO DNS
#          egress under an enforcing CNI — a total outage. Inert on minikube
#          (default CNI does not enforce NetworkPolicies), which is exactly why
#          it survived the live rehearsal. Fixed in plan 26-01 by replacing
#          includeSelectors with an explicit `fields:` list.
#
#          ASSERTION SHAPE IS LOAD-BEARING. This walks each `matchLabels:` block
#          by INDENTATION and inspects that block's own keys. A forward
#          `grep -A N 'k8s-app: kube-dns'` scan is UNFALSIFIABLE: kustomize sorts
#          map keys alphabetically, so the poisoned labels sort BEFORE `k8s-app`
#          and a forward scan returns 0 on the poisoned baseline too.
#
#   INV-4  DEF-6 recurrence, RENDER level, per target except local overlays. A
#          staging or production render must contain no `localhost`, `127.0.0.1`
#          or `minioadmin` literal. Thirteen placeholders used to resolve to
#          local-only defaults; plan 26-02 supplied them. This is the
#          non-regression half — check-env-contract.sh guards the config side,
#          this guards the rendered side.
#
#   INV-5  DEF-2 / INFRA-02b, DOCS level. Neither k8s/QUICK_START.md nor
#          k8s/base/secrets-template.yaml.example may name the DB SUPERUSER as
#          the postgres-credentials app username. A superuser BYPASSES EVERY RLS
#          POLICY, which is the whole multi-tenant isolation boundary, so
#          DatabaseConfigurationValidator fails core-java's boot fast when it
#          detects one. A copy-pasteable superuser recipe is therefore a latent
#          RLS bypass. Fixed in plan 26-02 (recipe, template stringData, and the
#          template's own comment-block recipe).
#
#          ASSERTION SHAPE IS LOAD-BEARING here too. The token `jtoye` appears
#          LEGITIMATELY in both files as the RabbitMQ BROKER username, so a
#          whole-file grep for it fails on a CORRECT tree. Both halves are
#          therefore BLOCK-SCOPED: each `kubectl create secret generic <name>`
#          recipe and each YAML document is attributed to its Secret name, and
#          only postgres-credentials is asserted on.
#
#   INV-6  DANGLING INGRESS BACKEND, RENDER level, EVERY target. Each Ingress
#          backend `service.name` in a render must match a `kind: Service`
#          present in that SAME render. k8s/base/ingress.yaml used to publish the
#          Keycloak hostname and route it to a Service named `keycloak` that
#          exists in NO render — the complete rendered Service set is core-java,
#          edge-go and frontend, and neither overlay adds one — so staging and
#          production each published a host for which nginx answers 503, and no
#          gate saw it. Worse, that hostname also sat in the single `jtoye-tls`
#          SAN list, so a cert-manager HTTP-01 challenge for a host this
#          controller does not serve could fail the whole certificate order and
#          stall issuance for api and app too. Fixed in plan 26-04 by REMOVING
#          the rule and the SAN in k8s/base (Keycloak is an external managed IdP,
#          so there is no Service to add), and pinned here so the class cannot
#          return through a future overlay or a re-added rule.
#          This one is deliberately NOT in the k8s/local-only section: the defect
#          it pins was a PRODUCTION defect. Proven so — with the rule restored,
#          base/staging/production all FAIL while k8s/local stays OK, because the
#          local overlay's `rules:` replacement hides it. A local-only assertion
#          would have missed the real defect entirely.
#
#   INV-7  ISSUE #271, RENDER level, EVERY target. The NetworkPolicy egress rules
#          that PERMIT the Postgres connection must allow the port the render
#          DECLARES — app-config `db.port` — and not a literal of their own.
#
#          THE DEFECT. Phase 26 made DB_PORT Secret-driven (DEF-1/INV-1 above) so
#          a non-default Postgres port needs no manifest edit. The egress rules in
#          networkpolicies/20-core-java.yaml and 40-datastores.yaml kept their own
#          `port: 5432`. So the first environment to USE that flexibility — which
#          is the reason the flexibility exists; the local overlay genuinely runs
#          5433 — gets a NetworkPolicy denial under an enforcing CNI: every
#          core-java replica CrashLooping and the nightly pg-backup dump failing,
#          with logs pointing at the application layer rather than the network.
#          Fixing one half of a coupling is what created the trap.
#
#          INVISIBLE TO EVERY OTHER GATE, which is why it needed a new one:
#          check-env-contract.sh reads env names, not policy ports;
#          validate-networkpolicies.py walks podSelectors, not ports; the goldens
#          would have happily frozen the WRONG port. And it is invisible at
#          RUNTIME too, in both directions — minikube's default CNI does not
#          enforce NetworkPolicies at all (D-11), and staging/production have
#          never been deployed. So a render-level assertion is not a convenience
#          here, it is the only instrument that exists.
#
#          ASSERTION SHAPE IS LOAD-BEARING, twice over.
#            1. It compares the COMPLETE port multiset of the rules targeting the
#               `jtoye-infrastructure` namespace against an expected set with
#               `db.port` substituted — not "is db.port somewhere in the list".
#               The weak form passes on a tree where the replacement got
#               retargeted onto Redis (db.port lands in the set, 6379 is lost);
#               the exact form fails. The other five ports are literals here on
#               purpose: they are outside #271's scope, and an exact allow-list is
#               strictly stronger than a partial one — a new datastore port must
#               be added here in the same change, which is the right friction for
#               an egress allow-list.
#            2. Each rule is buffered and evaluated WHOLE. `kubectl kustomize`
#               sorts map keys alphabetically, so within an egress rule `ports:`
#               is emitted BEFORE the `to:` that says which namespace it applies
#               to. A forward scan from the namespace line therefore sees none of
#               that rule's ports — the same output-ordering trap that makes a
#               `grep -A` scan unfalsifiable for INV-3.
#            3. A rendered port must be BARE DIGITS. In NetworkPolicy semantics a
#               STRING port is a NAMED port; `port: "5433"` renders happily,
#               applies happily, and matches no traffic at all. The replacement
#               source is a ConfigMap string, so this is a live failure mode of
#               the mechanism itself, not a hypothetical.
#
#          WHAT THIS DOES NOT PROVE. app-config `db.port` is the RENDER-TIME
#          declaration; the value the pods actually dial is the
#          `postgres-credentials` Secret `port` key, which by design never appears
#          in a render (check-no-plaintext-secrets.sh guarantees it). Their
#          agreement is asserted where both are observable —
#          scripts/k8s-local-secrets.sh refuses to create the local Secret on a
#          mismatch — and is an operator step for staging/production, in the same
#          shape as the RABBITMQ_USER pre-rollout check in
#          k8s/base/core-java-deployment.yaml.
#
#          SECOND ARM (Blocker D, plan 29-04): THE ipBlock EGRESS SURFACE.
#          The arm above keys entirely on the `jtoye-infrastructure`
#          namespaceSelector, so it is STRUCTURALLY BLIND to a rule addressed by
#          `ipBlock` — and D-09 moves Postgres and Redis out of the cluster,
#          where an ipBlock is the only way to address them. Measured on this
#          tree before the arm existed: two brand-new egress holes (5432 and 6380
#          to 0.0.0.0/0) were added to core-java-allow and INV-7 reported
#          `OK (2 policy/policies, db.port=5432 honoured)` on all four targets.
#          An invariant that cannot see the new rules is worse than no invariant,
#          because it reads as coverage. T-29-04-03.
#
#          So the ipBlock surface gets its OWN exact declaration, in the same
#          shape and with the same friction: `NETPOL_IPBLOCK_EXPECTED` is the
#          complete `<cidr>:<port>` multiset per policy, with `__DB_PORT__`,
#          `__REDIS_PORT__`, `__DB_CIDR__` and `__REDIS_CIDR__` substituted from
#          the rendered app-config. Three properties fall out of it:
#            a. The 0.0.0.0/0 rules stay 443-ONLY. Bolting 5432 or 6380 onto them
#               is the obvious shortcut for Blocker D and it is forbidden
#               (T-29-04-01); this arm makes that an assertion rather than a code
#               review note.
#            b. A policy that HAS an ipBlock egress rule but NO entry in the map
#               FAILS. Without that rule a new policy could open a hole the gate
#               never looks at, which is the same vacuity as (a) one level up.
#            c. Every `except:` entry must be strictly WITHIN its rule's `cidr`.
#               The Kubernetes API enforces this and REJECTS the manifest
#               otherwise, so narrowing `db.egress-cidr` to a `<ip>/32` while the
#               RFC1918 excepts remain is a failed deploy. Asserting it on the
#               render turns that into a CI failure instead. IPv4 only; anything
#               else exits 2 rather than being skipped.
#
#   INV-8  D-19 / Security Domain V4, RENDER level, EVERY target. No Ingress in
#          any render may route to a Service named `prometheus` or `alertmanager`.
#
#          WHY IT IS A GATE AND NOT A CONVENTION. Both have NO authentication of
#          their own. A reachable Prometheus hands over every series this platform
#          emits — request paths, queue names, tenant-shaped cardinality. A
#          reachable Alertmanager is worse than a leak: its UI CREATES SILENCES,
#          so anyone who finds the hostname holds a mute button for the platform's
#          entire alerting surface, and the failure is INVISIBLE by construction
#          (a silenced alert looks exactly like an alert that never fired).
#          D-19 gives a public hostname to GRAFANA ALONE, which has a login.
#
#          KEYED ON THE BACKEND SERVICE, NOT THE HOSTNAME, deliberately: an
#          Ingress is dangerous because of what it ROUTES TO, and a rule can
#          publish any hostname at all — including one that reads as internal.
#
#          THE ALLOWLIST IS EMPTY AND IS MEANT TO STAY EMPTY. It exists so the
#          gate has the same hygiene as INV-6's (blank reason FAILS, duplicate
#          FAILS, stale entry FAILS, and an entry naming a Service that is not on
#          the never-publish list FAILS because it would govern nothing) rather
#          than because an exemption is anticipated. The supported ways to reach
#          these two are `kubectl port-forward` and an authenticating proxy.
#
#          RUNS ON k8s/local TOO, unlike INV-4. A laptop cluster with a published
#          Alertmanager is still a mute button, and the local overlay's `rules:`
#          replacement is precisely the mechanism that hides such a rule from a
#          base-only assertion — the lesson INV-6's own header records in the
#          opposite direction.
#
#          ITS PARSE GUARD IS ITS OWN. INV-8 re-extracts the Ingress backend
#          records into its own file and asserts a non-zero count with its own
#          message, rather than leaning on INV-6's guard: an assertion whose
#          fail-closed guard belongs to a DIFFERENT assertion cannot be shown to
#          fail on its own, and "found no Ingress backends" must never read as
#          "nothing is published".
#
# THE LOCAL-OVERLAY INVARIANTS (LOC-*), Phase 26 / INFRA-01
#   These run ONLY when k8s/local/kustomization.yaml exists, so the script stays
#   valid if the overlay is ever removed. They assert the shape of the committed
#   local overlay that replaced the imperative in-cluster patches used during the
#   2026-07-14 live-deploy rehearsal.
#
#   LOC-1  Endpoint shims. Each of redis.host, rabbitmq.host,
#          stomp.broker.relay-host, s3.endpoint, s3.backup.endpoint, smtp.host,
#          keycloak.issuer.uri and keycloak.admin.base-url must resolve to a
#          host.minikube.internal value. Asserted PER KEY BY NAME, not by a total
#          count: a count alone lets a LOST shim hide behind an ADDED one, which
#          is not hypothetical — it was demonstrated (redis.host -> localhost plus
#          one extra shimmed value keeps the total at 8 and a count-only
#          assertion passes).
#
#   LOC-2  The D-09 scale triple. EVERY Deployment the base renders must appear in
#          the local render at `replicas: 1`, and likewise every HPA at
#          `minReplicas: 1` and every PDB at `minAvailable: 1`. The three expected
#          COUNTS are read out of the k8s/base render rather than written here as
#          the literal `3`, which went stale the moment base gained the monitoring
#          workloads (plan 29-06) — see the note beside the comparison. An HPA floor of 3
#          would scale a 1-replica Deployment straight back up, and a PDB
#          minAvailable of 2 over one replica makes the pod undrainable. AND the
#          local HPA maxReplicas multiset must equal the one k8s/base renders:
#          maxReplicas is an input to check-connection-math.sh's Postgres
#          connection budget, so lowering it locally would silently stop the
#          local render proving the same arithmetic. Compared AGAINST BASE rather
#          than against hardcoded numbers, so a legitimate future base change
#          carries through instead of going stale.
#
#   LOC-3  The backup repoint (INFRA-01 / INFRA-02c). s3.backup.endpoint is
#          exactly http://host.minikube.internal:9000. Base leaves it EMPTY,
#          which means "real AWS S3" — locally that aims a database dump at real
#          AWS with no credentials, and makes the #101 restore rehearsal
#          impossible to run.
#
#   LOC-4  Ingress admissibility (PIT-1 / PIT-10). No configuration-snippet, no
#          cert-manager issuer, no limit-rps/limit-connections/
#          limit-burst-multiplier and no `tls:` block in any local Ingress.
#          PIT-1 is the hard one: minikube v1.36.0 bundles ingress-nginx
#          v1.12.2, where allow-snippet-annotations defaults to FALSE and
#          annotations-risk-level to High, so its validating admission webhook
#          REJECTS the base ingress outright. The base annotation is deliberately
#          PRESERVED for staging/production — the fix belongs in the local
#          overlay, never on the cluster addon.
#
#   LOC-5  Host scoping (D-12) + no dangling Keycloak backend. Local Ingress
#          hosts are exactly api.jtoye.local and app.jtoye.local, no production
#          hostname survives into the local render, and no Ingress routes to a
#          Service named keycloak.
#
#   LOC-6  D-01 at the SOURCE level. No authored file under k8s/local/ may use
#          kustomize secret generation or carry an unsubstituted placeholder
#          literal. check-no-plaintext-secrets.sh already guards the BUILD
#          OUTPUT; this guards the input, so the intent is visible where the
#          mistake would be made.
#
# NON-VACUITY
#   Every render-level invariant also asserts that it FOUND something to check
#   (a DB_PORT EnvVar, a kube-dns selector block, a postgres-credentials recipe).
#   A gate that passes because it looked at nothing is worse than no gate, so a
#   missing subject exits 2 (the parser is blind — fix the parser) rather than 0.
#
# EXTENSION POINT
#   Plan 26-04 took this up: the local-overlay assertions live here as
#   LOC-1..LOC-6 and the all-target dangling-backend assertion as INV-6, rather
#   than as a sixth gate script. Keep extending here. An assertion that applies to
#   EVERY render belongs in the per-target loop as INV-N; one that only makes
#   sense for the local overlay belongs in the conditional LOCAL section as LOC-N.
#
#   RULE FOR ANY NEW ASSERTION (learned the hard way in this phase — six
#   acceptance criteria across plans 26-01..26-04 were unfalsifiable as written):
#   before trusting a new assertion, run it against a DELIBERATELY BROKEN input
#   and confirm it FAILS. An assertion that is already-true on the correct tree,
#   or still-true on the broken tree, proves nothing.
#
# Requires: kubectl (client-side `kubectl kustomize` only — no cluster access),
#           bash (>= 4 for mapfile and associative arrays), awk, grep, find, sed,
#           sort.
# Exit codes: 0 = all invariants hold, 1 = violation, 2 = build/parse/tooling
#             failure (including a blind assertion).
#
# Usage: ./k8s/scripts/check-render-invariants.sh
#   (run from anywhere; paths resolve relative to the repo root)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
K8S_DIR="$REPO_ROOT/k8s"

# NOTE (WR-01): there is deliberately NO $CORE_DEPLOYMENT here any more. INV-1 was
# the only reader, and it read the SOURCE file — which is what made it evadable by
# quoting style and blind to overlays. INV-1 now asserts on the render inside the
# per-target loop, so this script no longer depends on any single base filename;
# renaming or splitting core-java-deployment.yaml cannot make the gate go blind.
QUICK_START="$K8S_DIR/QUICK_START.md"
SECRETS_TEMPLATE="$K8S_DIR/base/secrets-template.yaml.example"
LOCAL_DIR="$K8S_DIR/local"
LOCAL_KUSTOMIZATION="$LOCAL_DIR/kustomization.yaml"

fail() { echo "FAIL: $*" >&2; exit 1; }
parse_fail() { echo "PARSE ERROR: $*" >&2; exit 2; }

# ---------------------------------------------------------------------------
# INV-4 exclusion list.
#
# Matched on the EXACT repo-relative target path, never as a substring, so a
# future `k8s/local-staging` overlay is NOT silently excluded by `k8s/local`.
#
# k8s/local exists as of plan 26-04, and the exclusion is load-bearing rather
# than defensive: that overlay DELIBERATELY carries localhost-family literals.
# Two of its values must be BROWSER-reachable rather than pod-reachable —
# `s3.public-url` (http://localhost:9000/jtoye-images: the browser is what loads
# image URLs) and `keycloak.public.issuer.uri` (http://localhost:8085/...: the
# issuer Keycloak actually stamps into `iss`) — so INV-4 would be asserting
# against the CORRECT content there. Every other target ships to a real cluster,
# where such a literal is the DEF-6 defect.
#
# The local overlay is NOT unguarded as a result: LOC-1..LOC-6 below assert its
# endpoints POSITIVELY, per key by name, which is a stronger statement than
# INV-4's "no forbidden literal" ever was.
# ---------------------------------------------------------------------------
LOCAL_ONLY_TARGETS=(
  "k8s/local"
)

# ---------------------------------------------------------------------------
# INV-6 allowlist: an Ingress backend Service name that is knowingly not in the
# render. Format: '<service-name>|<reason>'.
#
# It is EMPTY, and that is the correct state. The one entry it could have had —
# `keycloak` — was the DEFECT, not an exemption: Keycloak is an external managed
# IdP, so the right fix was removing the rule that claimed its hostname, not
# excusing a backend that resolves nowhere. An entry here means "this render
# intentionally routes to a Service created outside kustomize", which should be
# rare enough to always need an explanation.
#
# Hygiene (same rules as check-env-contract.sh's allowlists): a blank reason
# FAILS, a duplicate FAILS, and a STALE entry — one whose Service now resolves in
# every target — FAILS, so the allowlist cannot quietly become a standing excuse
# for something that is already fixed.
# ---------------------------------------------------------------------------
ALLOW_UNRESOLVED_INGRESS_BACKEND=()

# ---------------------------------------------------------------------------
# INV-8 (D-19 / Security Domain V4, plan 29-07): the monitoring workloads that
# must NEVER be published.
#
# Prometheus and Alertmanager have NO authentication of their own. Anything that
# reaches Prometheus reads every series this platform emits — request paths,
# queue names, tenant-shaped cardinality. Anything that reaches Alertmanager can
# CREATE SILENCES, i.e. hold a mute button for the platform's entire alerting
# surface. D-19 therefore gives a public hostname to GRAFANA ALONE, which has a
# login; the other two stay ClusterIP and are reached by port-forward.
#
# That was a convention, and a convention is a review question. This makes it a
# CI failure with a name on it. The list is deliberately the SERVICE NAMES rather
# than the hostnames: an Ingress is dangerous because of what it ROUTES TO, and a
# rule can publish any hostname at all.
#
# THE ALLOWLIST IS EMPTY AND SHOULD STAY EMPTY. There is no "we exposed
# Prometheus temporarily" that is not a decision to re-take deliberately: the
# right move is a port-forward, or an authenticating proxy in front of it, both
# of which leave this list empty. Same hygiene as INV-6's: a blank reason FAILS,
# a duplicate FAILS, and a STALE entry — one whose Service is not in fact
# published anywhere — FAILS, so it cannot rot into a standing excuse.
# ---------------------------------------------------------------------------
# MAILHOG JOINS THE LIST (D-13, plan 29-09), and it is the sharpest entry of the
# three. Prometheus and Alertmanager leak metrics and a mute button; Mailhog's
# unauthenticated HTTP API serves the CAPTURED CONTENT of every application email
# staging sends — customer names, delivery addresses, order contents, unsubscribe
# tokens. The compose stack learned this at first hand (#441: an all-interfaces
# bind published dev-tenant mail to the local network with no credential, which is
# why docker-compose.full-stack.yml pins both its ports to loopback). Publishing
# it through the ingress would be that same defect with a public hostname and a
# valid certificate on it.
#
# It is a STAGING-ONLY Service, so on three of four targets this entry can never
# match. That is not a reason to leave it out: the list is checked against Ingress
# BACKENDS, and an Ingress rule can be added in any overlay at all.
NEVER_PUBLISHED_BACKENDS=(
  'prometheus'
  'alertmanager'
  'mailhog'
)
ALLOW_PUBLISHED_MONITORING_BACKEND=()

# The live-verified Postgres SUPERUSER role name. `jtoye` is a superuser and
# `jtoye_app` is NOSUPERUSER (both confirmed against the running dev Postgres in
# 26-RESEARCH.md § Live Facts). Only the superuser is a defect in a recipe.
DB_SUPERUSER_ROLE="jtoye"

# Literals that must never appear in a non-local render.
FORBIDDEN_RENDER_LITERALS=(
  'localhost'
  '127\.0\.0\.1'
  'minioadmin'
)

# ---------------------------------------------------------------------------
# INV-7 (issue #271): the COMPLETE expected TCP egress port multiset toward the
# `jtoye-infrastructure` namespace, per NetworkPolicy, sorted numerically.
#
# __DB_PORT__ is substituted with the rendered app-config `db.port` of the target
# under test — that substitution IS the invariant. Everything else is a literal,
# deliberately: an exact allow-list is strictly stronger than "db.port is in
# there somewhere", which passes on a tree where the kustomize replacement got
# retargeted onto a neighbouring port.
#
# Adding a datastore port to a policy MUST be accompanied by adding it here. That
# is the intended friction: an egress allow-list is a security boundary, and a
# gate that silently accepted new holes in it would not be one.
#
# The map is keyed by NetworkPolicy metadata.name, which the overlays do not
# rename (only namespace and labels differ per target).
# ---------------------------------------------------------------------------
# PLAN 29-09 REMOVED 5672 AND 61613 FROM core-java-allow, AND THE DIRECTION IS
# THE POINT. Every previous edit to this map ADDED a port; this one takes two
# away, because D-09 moved the broker from the `jtoye-infrastructure` namespace
# to an operator-managed StatefulSet in the app's OWN namespace
# (k8s/base/rabbitmq-cluster.yaml). This map keys strictly on the
# jtoye-infrastructure namespaceSelector, so the two ports genuinely left its
# jurisdiction: they are now `spec.egress.6` and `.7` of core-java-allow,
# addressed by podSelector, and this arm cannot see them by construction.
#
# THAT HAS A CONSEQUENCE FOR ANYONE WRITING A FAIL ARM AGAINST THIS GATE.
# "Change a RabbitMQ port in the policy and watch INV-7 name it" WAS a falsifiable
# assertion and is no longer one — after this change INV-7's first arm is blind to
# both ports, so that arm would pass on a broken tree. The strictly stronger form,
# and the one plan 29-09 actually ran: leave THIS map at its old six-port value
# while the policy has moved to four, and confirm the gate FAILS naming [5672,
# 61613] on all four targets. That arm proves the friction this map exists for —
# the declaration must move with the policy — and it is the direction a future
# editor is actually at risk of getting wrong.
declare -A NETPOL_INFRA_EXPECTED=(
  [core-java-allow]="__DB_PORT__ 6379 9000 9093"
  [pg-backup-allow]="__DB_PORT__ 9000"
)
INFRA_NAMESPACE_LABEL="jtoye-infrastructure"

# ---------------------------------------------------------------------------
# INV-7 SECOND ARM (Blocker D / plan 29-04): the COMPLETE expected
# `<cidr>:<port>` multiset of every egress rule addressed by an `ipBlock`, per
# NetworkPolicy.
#
# WHY IT IS A SEPARATE MAP. The map above keys on the `jtoye-infrastructure`
# namespaceSelector and therefore cannot see an ipBlock-addressed rule at all.
# D-09 puts Postgres and Redis OUTSIDE the cluster, where an ipBlock is the only
# way to address them, so without this map the entire out-of-cluster egress
# surface is ungoverned — and the gate would keep printing OK while it grew.
# That is not hypothetical: it was measured on this tree (see the INV-7 header).
#
# SUBSTITUTIONS ARE THE INVARIANT; everything else is a literal, deliberately.
#   __DB_PORT__     <- rendered app-config db.port
#   __REDIS_PORT__  <- rendered app-config redis.port
#   __DB_CIDR__     <- rendered app-config db.egress-cidr
#   __REDIS_CIDR__  <- rendered app-config redis.egress-cidr
# The `0.0.0.0/0:443` entries are literals because that rule must STAY 443-only:
# widening it to carry a datastore port is the shortcut Blocker D invites and
# T-29-04-01 forbids.
#
# EVERY POLICY WITH AN ipBlock RULE MUST APPEAR HERE. A policy that renders an
# ipBlock egress rule and has no entry FAILS — an undeclared hole is exactly what
# this arm exists to prevent, and silently ignoring it would reproduce the
# blindness one level up. The four below are every such policy today;
# 00-default-deny and 50-observability have no egress ipBlock and correctly do
# not appear.
#
# Adding an out-of-cluster egress rule to a policy MUST be accompanied by adding
# it here. Same intended friction as the map above, for the same reason.
# ---------------------------------------------------------------------------
# PLAN 29-07 ADDS THREE MONITORING POLICIES TO THIS MAP, and the gate FIRED on all
# four targets before they were added — the intended friction working unprompted:
#   FAIL … NetworkPolicy 'alertmanager-allow' renders an ipBlock egress rule but
#          has no entry in NETPOL_IPBLOCK_EXPECTED.
# (same for postgres-exporter-allow and redis-exporter-allow). Recorded because it
# is a real, unsolicited demonstration that arm (b) can fail.
#
# __SMTP_PORT__ IS A DIFFERENT KIND OF SUBSTITUTION FROM THE OTHER FOUR, and the
# difference is the point. The other four are also applied to the manifest by a
# kustomize `replacements:` block, so the gate is confirming a rewrite. This one
# is NOT: app-config `alerting.smtp.smarthost` is a `host:port` string and
# kustomize cannot split it, so the NetworkPolicy port is authored. Deriving the
# EXPECTED value from the smarthost's port suffix is therefore the only thing that
# couples them at all — move the relay to a submission port on 465 or 2525 and this
# arm FAILS by name until the policy moves with it. Without it, the two would drift
# silently and Alertmanager would be denied its own relay under an enforcing CNI:
# every alert queued, retried and dropped, with the UI showing them as firing.
declare -A NETPOL_IPBLOCK_EXPECTED=(
  [core-java-allow]="0.0.0.0/0:443 __DB_CIDR__:__DB_PORT__ __REDIS_CIDR__:__REDIS_PORT__"
  [pg-backup-allow]="0.0.0.0/0:443 __DB_CIDR__:__DB_PORT__"
  [frontend-allow]="0.0.0.0/0:443"
  [edge-go-allow]="0.0.0.0/0:443"
  # DPLY-03 / plan 29-07. Each exporter reaches the SAME managed datastore
  # core-java does, so each carries exactly the pair core-java carries — and
  # NOTHING ELSE. In particular neither has a 0.0.0.0/0:443 rule: an exporter has
  # no business on the public internet, and the absence is asserted here rather
  # than merely intended.
  [postgres-exporter-allow]="__DB_CIDR__:__DB_PORT__"
  [redis-exporter-allow]="__REDIS_CIDR__:__REDIS_PORT__"
  # The SMTP relay, and only the SMTP relay. No 443 — see the paragraph above for
  # why the port is derived from the smarthost rather than replaced into the
  # manifest.
  [alertmanager-allow]="0.0.0.0/0:__SMTP_PORT__"
  # DPLY-01 / D-02 / plan 29-08. Keycloak reaches the SAME managed Postgres
  # core-java does — its OWN database on that server — so it carries the same
  # derived pair and NOTHING ELSE. The absence of a 0.0.0.0/0:443 rule is the
  # assertion that matters here and it is asserted rather than intended: an
  # identity provider with general internet egress is an SSRF surface reachable
  # from an unauthenticated public endpoint, and this realm configures no external
  # identity provider and no outbound webhook that would need one. Adding one
  # later must add it here in the same change.
  #
  # THE FRICTION FIRED, unprompted, before this entry existed — recorded because
  # it is a real demonstration that arm (b) can fail:
  #   FAIL [k8s/base] INV-7: NetworkPolicy 'keycloak-allow' renders an ipBlock
  #        egress rule but has no entry in NETPOL_IPBLOCK_EXPECTED.
  # on all four targets.
  [keycloak-allow]="__DB_CIDR__:__DB_PORT__"
)

command -v kubectl > /dev/null \
    || parse_fail "kubectl not on PATH (client-side 'kubectl kustomize' is required)."
[[ -f "$QUICK_START" ]]      || parse_fail "not found: $QUICK_START"
[[ -f "$SECRETS_TEMPLATE" ]] || parse_fail "not found: $SECRETS_TEMPLATE"

# Same auto-discovery loop as check-no-plaintext-secrets.sh, so a new overlay is
# covered the moment it exists. `sort` keeps the output order deterministic.
mapfile -t TARGETS < <(find "$K8S_DIR" -maxdepth 2 -name 'kustomization.yaml' -printf '%h\n' | sort)
(( ${#TARGETS[@]} > 0 )) || parse_fail "no kustomization.yaml found under $K8S_DIR"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

FAILED=0

# ===========================================================================
# Per-target render assertions: INV-1, INV-2, INV-3, INV-4, INV-6
# ===========================================================================

# --- awk: emit one record per rendered EnvVar (name, line, has value, has
#     valueFrom). An EnvVar is a `- name: X` sequence item whose IMMEDIATE
#     children include `value` or `valueFrom` — restricting to immediate
#     children is what stops a container's `- name: <container>` block from
#     swallowing its own nested env items.
ENVVAR_AWK='
function endenv() {
    if (in_item && (has_value || has_valuefrom))
        printf "%s\t%d\t%d\t%d\n", name, start_line, has_value, has_valuefrom
    in_item = 0; has_value = 0; has_valuefrom = 0
}
{
    if ($0 ~ /^[[:space:]]*$/) { endenv(); next }
    ind = match($0, /[^ ]/) - 1
    if (in_item && ind <= item_ind) endenv()
    if ($0 ~ /^[[:space:]]*- name: /) {
        endenv()
        in_item = 1; item_ind = ind; start_line = NR
        name = $0
        sub(/^[[:space:]]*- name:[[:space:]]*/, "", name)
        next
    }
    if (in_item && ind == item_ind + 2) {
        key = $0
        sub(/^[[:space:]]*/, "", key)
        sub(/:.*$/, "", key)
        if (key == "value")     has_value = 1
        if (key == "valueFrom") has_valuefrom = 1
    }
}
END { endenv() }
'

# --- awk: walk every `matchLabels:` block by indentation and emit
#     "<start-line>\t<key-count>\t<is-kube-dns>\t<comma-joined keys>".
#     Block-scoped BY CONSTRUCTION: a forward grep -A scan cannot work because
#     kustomize sorts map keys alphabetically, so injected labels sort BEFORE
#     `k8s-app` and land ABOVE the anchor line.
MATCHLABELS_AWK='
function endblock() {
    if (in_block)
        printf "%d\t%d\t%d\t%s\n", start_line, nkeys, is_dns, keys
    in_block = 0; nkeys = 0; is_dns = 0; keys = ""
}
{
    if ($0 ~ /^[[:space:]]*$/) { endblock(); next }
    ind = match($0, /[^ ]/) - 1
    if (in_block && ind <= block_ind) endblock()
    if ($0 ~ /^[[:space:]]*matchLabels:[[:space:]]*$/) {
        endblock()
        in_block = 1; block_ind = ind; start_line = NR; nkeys = 0; is_dns = 0; keys = ""
        next
    }
    if (in_block) {
        key = $0; sub(/^[[:space:]]*/, "", key); sub(/:.*$/, "", key)
        val = $0; sub(/^[^:]*:[[:space:]]*/, "", val)
        nkeys++
        keys = keys (keys == "" ? "" : ",") key
        if (key == "k8s-app" && val == "kube-dns") is_dns = 1
    }
}
END { endblock() }
'

# --- awk: per-DOCUMENT walk emitting the Service inventory and every Ingress
#     backend reference of a render.
#
#     DOCUMENT-SCOPED BY CONSTRUCTION, and that is load-bearing: `kubectl
#     kustomize` emits each document's top-level keys ALPHABETICALLY, so a
#     ConfigMap's `data:` block precedes its own `kind:` line. A "track the last
#     kind seen" scan therefore attributes those lines to the PREVIOUS document
#     (a real mis-attribution hit in plan 26-02). This buffers each
#     `---`-delimited document and resolves kind + metadata.name from the buffer.
#
#     Output records:
#       SVC <TAB> <service name>
#       ING <TAB> <ingress name> <TAB> <host|(default)> <TAB> <backend service name>
INGRESS_BACKEND_AWK='
function meta_name(  i, v) {
    # The FIRST 2-space `name:` in a document is metadata.name: in a rendered
    # document only the metadata block has a key at that indent, while backend
    # service names sit far deeper.
    for (i = 1; i <= n; i++)
        if (buf[i] ~ /^  name: /) { v = buf[i]; sub(/^  name:[[:space:]]*/, "", v); return v }
    return "(unnamed)"
}
function flush(  i, kind, nm, host, insvc, b, l) {
    if (n == 0) return
    kind = ""
    for (i = 1; i <= n; i++)
        if (buf[i] ~ /^kind: /) { kind = buf[i]; sub(/^kind:[[:space:]]*/, "", kind) }

    if (kind == "Service") {
        printf "SVC\t%s\n", meta_name()
    } else if (kind == "Ingress") {
        nm = meta_name(); host = "(default)"; insvc = 0
        for (i = 1; i <= n; i++) {
            l = buf[i]
            if (l ~ /^[[:space:]]*- host: /) {
                host = l; sub(/^[[:space:]]*- host:[[:space:]]*/, "", host)
            }
            if (l ~ /^[[:space:]]*service:[[:space:]]*$/) { insvc = 1; continue }
            if (insvc && l ~ /^[[:space:]]*name:[[:space:]]*/) {
                b = l; sub(/^[[:space:]]*name:[[:space:]]*/, "", b)
                printf "ING\t%s\t%s\t%s\n", nm, host, b
                insvc = 0
            }
        }
    }
    n = 0; delete buf
}
/^---[[:space:]]*$/ { flush(); next }
{ buf[++n] = $0 }
END { flush() }
'

# --- awk: per-DOCUMENT walk emitting the scale-relevant top-level spec scalars.
#     Output: <kind> <TAB> <name> <TAB> <field> <TAB> <value>
SCALE_AWK='
function meta_name(  i, v) {
    for (i = 1; i <= n; i++)
        if (buf[i] ~ /^  name: /) { v = buf[i]; sub(/^  name:[[:space:]]*/, "", v); return v }
    return "(unnamed)"
}
function flush(  i, kind, nm, l, f, v) {
    if (n == 0) return
    kind = ""
    for (i = 1; i <= n; i++)
        if (buf[i] ~ /^kind: /) { kind = buf[i]; sub(/^kind:[[:space:]]*/, "", kind) }
    nm = meta_name()
    for (i = 1; i <= n; i++) {
        l = buf[i]
        if (l ~ /^  (replicas|minReplicas|maxReplicas|minAvailable): /) {
            f = l; sub(/^  /, "", f); sub(/:.*$/, "", f)
            v = l; sub(/^  [a-zA-Z]+:[[:space:]]*/, "", v)
            printf "%s\t%s\t%s\t%s\n", kind, nm, f, v
        }
    }
    n = 0; delete buf
}
/^---[[:space:]]*$/ { flush(); next }
{ buf[++n] = $0 }
END { flush() }
'

# --- awk: emit `<key>\t<value>` for every entry in the rendered app-config
#     ConfigMap `data:` map. Document-scoped for the same alphabetical-key reason.
CONFIGMAP_DATA_AWK='
function flush(  i, kind, nm, indata, l, k, v) {
    if (n == 0) return
    kind = ""; nm = ""
    for (i = 1; i <= n; i++) {
        if (buf[i] ~ /^kind: /)   { kind = buf[i]; sub(/^kind:[[:space:]]*/, "", kind) }
        if (buf[i] ~ /^  name: /) { if (nm == "") { nm = buf[i]; sub(/^  name:[[:space:]]*/, "", nm) } }
    }
    if (kind == "ConfigMap" && nm == "app-config") {
        indata = 0
        for (i = 1; i <= n; i++) {
            l = buf[i]
            if (l ~ /^data:[[:space:]]*$/) { indata = 1; continue }
            if (indata && l ~ /^[^ ]/)     { indata = 0; continue }
            if (indata && l ~ /^  [^ ].*:/) {
                k = l; sub(/^  /, "", k); sub(/:.*$/, "", k)
                v = l; sub(/^  [^:]*:[[:space:]]*/, "", v)
                printf "%s\t%s\n", k, v
            }
        }
    }
    n = 0; delete buf
}
/^---[[:space:]]*$/ { flush(); next }
{ buf[++n] = $0 }
END { flush() }
'

# --- awk: per-DOCUMENT walk emitting, for every NetworkPolicy, the ports of the
#     egress rules that target the jtoye-infrastructure namespace (INV-7).
#
#     RULE-BUFFERED BY CONSTRUCTION, and that is the whole point. `kubectl
#     kustomize` sorts map keys alphabetically, so inside an egress rule the
#     `ports:` block is emitted BEFORE the `to:` block that says which namespace
#     the rule applies to. Any scan that reads forward from the namespace line
#     sees ZERO of that rule's ports and reports a clean run on a broken tree —
#     the same output-ordering trap documented for INV-3. So each `  - ` rule is
#     collected whole, then classified, then emitted.
#
#     Output records:
#       POL  <TAB> <policy name>                 (one per NetworkPolicy seen)
#       NP   <TAB> <policy name> <TAB> <port token as rendered, unstripped>
NETPOL_INFRA_AWK='
function meta_name(  i, v) {
    for (i = 1; i <= n; i++)
        if (buf[i] ~ /^  name: /) { v = buf[i]; sub(/^  name:[[:space:]]*/, "", v); return v }
    return "(unnamed)"
}
function emit_rule(  j, isinfra, p) {
    if (rn == 0) { return }
    isinfra = 0
    for (j = 1; j <= rn; j++)
        if (rule[j] ~ /kubernetes\.io\/metadata\.name:[[:space:]]*INFRA_NS[[:space:]]*$/) isinfra = 1
    if (isinfra)
        for (j = 1; j <= rn; j++)
            if (rule[j] ~ /^[[:space:]]*-[[:space:]]*port:[[:space:]]*/) {
                p = rule[j]
                sub(/^[[:space:]]*-[[:space:]]*port:[[:space:]]*/, "", p)
                sub(/[[:space:]]*$/, "", p)
                printf "NP\t%s\t%s\n", polname, p
            }
    rn = 0; delete rule
}
function flush(  i, kind, l, inegress) {
    if (n == 0) return
    kind = ""
    for (i = 1; i <= n; i++)
        if (buf[i] ~ /^kind: /) { kind = buf[i]; sub(/^kind:[[:space:]]*/, "", kind) }
    if (kind == "NetworkPolicy") {
        polname = meta_name()
        inegress = 0; rn = 0; delete rule
        for (i = 1; i <= n; i++) {
            l = buf[i]
            if (l ~ /^  egress:[[:space:]]*$/) { inegress = 1; continue }
            if (inegress) {
                # Any other spec-level key ends the egress list. In the render the
                # next one is `  ingress:` (alphabetical), and `  policyTypes:`
                # brings indent-2 list items of its own — so leaving on the first
                # non-list key at indent 2 is what keeps `- Ingress` out of here.
                if (l ~ /^  [A-Za-z]/) { emit_rule(); inegress = 0; continue }
                if (l ~ /^  - /)       { emit_rule() }
                rule[++rn] = l
            }
        }
        emit_rule()
        printf "POL\t%s\n", polname
    }
    n = 0; delete buf
}
/^---[[:space:]]*$/ { flush(); next }
{ buf[++n] = $0 }
END { flush() }
'
# The namespace label is a parameter of the platform, not of awk syntax, so it is
# injected rather than duplicated as a second literal.
NETPOL_INFRA_AWK="${NETPOL_INFRA_AWK//INFRA_NS/$INFRA_NAMESPACE_LABEL}"

# --- awk: the same per-DOCUMENT, rule-buffered walk, for the egress rules
#     addressed by an `ipBlock` (INV-7 second arm, Blocker D).
#
#     RULE-BUFFERED FOR THE SAME REASON as the parser above, and here the
#     ordering trap is worse: `kubectl kustomize` sorts map keys alphabetically,
#     so a rule renders `ports:` FIRST and `to: - ipBlock: cidr:` AFTER. A scan
#     that reads forward from the `cidr:` line finds ZERO ports and would report
#     an empty ipBlock surface on a tree full of holes — a clean run over a
#     broken tree. Buffering the whole rule makes the order irrelevant.
#
#     Output records:
#       IPPOL   <TAB> <policy>                            (policy has >=1 ipBlock rule)
#       IPMULTI <TAB> <policy>                            (>1 cidr in ONE rule — see below)
#       IPB     <TAB> <policy> <TAB> <cidr> <TAB> <port>  (one per cidr x port)
#       IPX     <TAB> <policy> <TAB> <cidr> <TAB> <except entry>
#
#     IPMULTI is a fail-closed signal, not a finding. With two ipBlocks in one
#     rule the `except:` entries cannot be attributed to a cidr by position, so
#     the containment check below would be guessing. The caller exits 2.
NETPOL_IPBLOCK_AWK='
function meta_name(  i, v) {
    for (i = 1; i <= n; i++)
        if (buf[i] ~ /^  name: /) { v = buf[i]; sub(/^  name:[[:space:]]*/, "", v); return v }
    return "(unnamed)"
}
function emit_rule(  j, k, m, ncidr, nport, nexc, inexcept, t) {
    if (rn == 0) { return }
    ncidr = 0; nport = 0; nexc = 0; inexcept = 0
    delete ipc; delete ipp; delete ipe
    for (j = 1; j <= rn; j++) {
        t = rule[j]
        if (t ~ /^[[:space:]]*cidr:[[:space:]]*/) {
            sub(/^[[:space:]]*cidr:[[:space:]]*/, "", t); sub(/[[:space:]]*$/, "", t)
            ipc[++ncidr] = t; inexcept = 0; continue
        }
        if (t ~ /^[[:space:]]*except:[[:space:]]*$/) { inexcept = 1; continue }
        if (t ~ /^[[:space:]]*-[[:space:]]*port:[[:space:]]*/) {
            sub(/^[[:space:]]*-[[:space:]]*port:[[:space:]]*/, "", t); sub(/[[:space:]]*$/, "", t)
            ipp[++nport] = t; inexcept = 0; continue
        }
        # An except entry is a bare scalar list item under `except:`. Restricting
        # to a leading digit keeps `- ipBlock:` / `- namespaceSelector:` out.
        if (inexcept && t ~ /^[[:space:]]*-[[:space:]]*[0-9]/) {
            sub(/^[[:space:]]*-[[:space:]]*/, "", t); sub(/[[:space:]]*$/, "", t)
            ipe[++nexc] = t; continue
        }
        if (inexcept && t !~ /^[[:space:]]*-/) inexcept = 0
    }
    if (ncidr > 0) {
        printf "IPPOL\t%s\n", polname
        if (ncidr > 1) printf "IPMULTI\t%s\n", polname
        for (k = 1; k <= nport; k++)
            for (m = 1; m <= ncidr; m++)
                printf "IPB\t%s\t%s\t%s\n", polname, ipc[m], ipp[k]
        for (k = 1; k <= nexc; k++)
            printf "IPX\t%s\t%s\t%s\n", polname, ipc[1], ipe[k]
    }
    rn = 0; delete rule
}
function flush(  i, kind, l, inegress) {
    if (n == 0) return
    kind = ""
    for (i = 1; i <= n; i++)
        if (buf[i] ~ /^kind: /) { kind = buf[i]; sub(/^kind:[[:space:]]*/, "", kind) }
    if (kind == "NetworkPolicy") {
        polname = meta_name()
        inegress = 0; rn = 0; delete rule
        for (i = 1; i <= n; i++) {
            l = buf[i]
            if (l ~ /^  egress:[[:space:]]*$/) { inegress = 1; continue }
            if (inegress) {
                if (l ~ /^  [A-Za-z]/) { emit_rule(); inegress = 0; continue }
                if (l ~ /^  - /)       { emit_rule() }
                rule[++rn] = l
            }
        }
        emit_rule()
    }
    n = 0; delete buf
}
/^---[[:space:]]*$/ { flush(); next }
{ buf[++n] = $0 }
END { flush() }
'

# ---------------------------------------------------------------------------
# IPv4 CIDR containment, for INV-7's `except:`-within-`cidr` assertion.
#
# The Kubernetes API rejects a NetworkPolicy whose `except` entry is not
# strictly within its `cidr`, so a tree that renders one is a manifest that
# cannot be applied — a failed deploy rather than a caught mistake. This is the
# exact shape narrowing `db.egress-cidr` to a `<ip>/32` produces if the RFC1918
# excepts are left behind, which is the one-key change the whole Blocker D
# mechanism is designed to make safe.
#
# IPv4 ONLY, ON PURPOSE. An IPv6 CIDR is not evaluated, and rather than being
# skipped (which would silently reduce coverage to nothing the day the platform
# dual-stacks) it exits 2. "Cannot evaluate" is never "clean".
# ---------------------------------------------------------------------------
CIDR4_RE='^[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}/[0-9]{1,2}$'

ipv4_to_int() {
    local ip="$1" a b c d o
    IFS='.' read -r a b c d <<< "$ip"
    for o in "$a" "$b" "$c" "$d"; do
        [[ "$o" =~ ^[0-9]+$ ]] || return 1
        (( o <= 255 )) || return 1
    done
    echo $(( (a << 24) + (b << 16) + (c << 8) + d ))
}

# cidr4_contains <supernet> <subnet>
#   0 = subnet lies within supernet
#   1 = it does not
#   2 = cannot be evaluated (not IPv4, malformed, prefix > 32) -> caller VOIDs
cidr4_contains() {
    local sup="$1" sub="$2" sup_ip sup_len sub_ip sub_len si su mask
    [[ "$sup" =~ $CIDR4_RE && "$sub" =~ $CIDR4_RE ]] || return 2
    sup_ip="${sup%%/*}"; sup_len="${sup##*/}"
    sub_ip="${sub%%/*}"; sub_len="${sub##*/}"
    (( sup_len <= 32 && sub_len <= 32 )) || return 2
    si=$(ipv4_to_int "$sup_ip") || return 2
    su=$(ipv4_to_int "$sub_ip") || return 2
    (( sub_len >= sup_len )) || return 1
    if (( sup_len == 0 )); then mask=0; else mask=$(( (0xFFFFFFFF << (32 - sup_len)) & 0xFFFFFFFF )); fi
    (( (si & mask) == (su & mask) )) && return 0
    return 1
}

is_local_only_target() {
    local rel="$1" excluded
    for excluded in "${LOCAL_ONLY_TARGETS[@]}"; do
        [[ "$rel" == "$excluded" ]] && return 0
    done
    return 1
}

# ---------------------------------------------------------------------------
# INV-6 allowlist parse + hygiene (malformed / blank reason / duplicate). The
# STALE rule is evaluated AFTER the per-target loop, once it is known which
# entries were actually needed.
# ---------------------------------------------------------------------------
declare -A ALLOW_INGRESS_REASON=()
declare -A ALLOW_INGRESS_USED=()
# The size guard (rather than the `${arr[@]+"${arr[@]}"}` idiom) keeps this safe
# under `set -u` on an EMPTY array while still quoting each element properly —
# reasons contain spaces, so an unquoted expansion would word-split them into
# bogus entries.
if (( ${#ALLOW_UNRESOLVED_INGRESS_BACKEND[@]} > 0 )); then
    for entry in "${ALLOW_UNRESOLVED_INGRESS_BACKEND[@]}"; do
        svc="${entry%%|*}"
        reason="${entry#*|}"
        if [[ -z "$svc" || "$svc" == "$entry" ]]; then
            fail "INV-6 allowlist: entry '$entry' is malformed — the required shape is '<service-name>|<reason>'."
        fi
        if [[ -z "${reason//[[:space:]]/}" ]]; then
            fail "INV-6 allowlist: entry '$svc' has a blank reason. An unexplained exemption is indistinguishable from a forgotten defect — a backend that resolves nowhere answers 503 for a published host, which is exactly the shape INV-6 exists to catch."
        fi
        if [[ -n "${ALLOW_INGRESS_REASON[$svc]:-}" ]]; then
            fail "INV-6 allowlist: duplicate entry '$svc'."
        fi
        ALLOW_INGRESS_REASON["$svc"]="$reason"
    done
fi

# ---------------------------------------------------------------------------
# INV-8 allowlist parse + hygiene. Same three rules as INV-6's, evaluated the
# same way: malformed / blank-reason / duplicate here, STALE after the per-target
# loop once it is known which entries were actually needed.
# ---------------------------------------------------------------------------
declare -A ALLOW_PUBLISHED_REASON=()
declare -A ALLOW_PUBLISHED_USED=()
if (( ${#ALLOW_PUBLISHED_MONITORING_BACKEND[@]} > 0 )); then
    for entry in "${ALLOW_PUBLISHED_MONITORING_BACKEND[@]}"; do
        svc="${entry%%|*}"
        reason="${entry#*|}"
        if [[ -z "$svc" || "$svc" == "$entry" ]]; then
            fail "INV-8 allowlist: entry '$entry' is malformed — the required shape is '<service-name>|<reason>'."
        fi
        if [[ -z "${reason//[[:space:]]/}" ]]; then
            fail "INV-8 allowlist: entry '$svc' has a blank reason. Publishing an unauthenticated monitoring surface is a decision, and a decision with no stated reason is indistinguishable from an accident."
        fi
        if [[ -n "${ALLOW_PUBLISHED_REASON[$svc]:-}" ]]; then
            fail "INV-8 allowlist: duplicate entry '$svc'."
        fi
        ALLOW_PUBLISHED_REASON["$svc"]="$reason"
    done
fi
# An entry naming something that is not on the never-publish list governs nothing
# and would read as coverage. Fail rather than ignore.
for svc in "${!ALLOW_PUBLISHED_REASON[@]}"; do
    _known=0
    for _n in "${NEVER_PUBLISHED_BACKENDS[@]}"; do
        [[ "$svc" == "$_n" ]] && _known=1
    done
    (( _known == 1 )) || fail "INV-8 allowlist: entry '$svc' is not on NEVER_PUBLISHED_BACKENDS, so it exempts nothing. Either add it to that list or remove the entry."
done

for dir in "${TARGETS[@]}"; do
    rel="${dir#"$REPO_ROOT"/}"
    render="$TMP/${rel//\//_}.yaml"

    if ! kubectl kustomize "$dir" > "$render" 2> "$TMP/stderr"; then
        echo "ERROR [$rel]: 'kubectl kustomize' build failed:" >&2
        cat "$TMP/stderr" >&2
        exit 2
    fi

    # ---------------- INV-2 ----------------
    awk "$ENVVAR_AWK" "$render" > "$TMP/envvars.tsv"
    envvar_count=$(wc -l < "$TMP/envvars.tsv")
    (( envvar_count > 0 )) || parse_fail "[$rel] INV-2 found 0 EnvVar items in the render — the EnvVar shape changed and this assertion is now blind. Fix the parser, do not delete the invariant."
    if ! grep -qP '^DB_PORT\t' "$TMP/envvars.tsv"; then
        parse_fail "[$rel] INV-1/INV-2 found no DB_PORT EnvVar in the render — the assertion would pass vacuously. DB_PORT must be injected (DEF-1); if it was deliberately renamed, update this gate in the same change."
    fi

    # ---------------- INV-1 ----------------
    # SHAPE, on the RENDER, per target: every DB_PORT EnvVar carries valueFrom and
    # no value. No pipes here on purpose — awk counts internally, so there is no
    # `grep -q` writer to signal under `set -o pipefail` (the SIGPIPE class this
    # phase fixed elsewhere; the code-review's own suggested fix used
    # `awk ... | grep -q .`, which reintroduces it).
    dbport_total=$(awk   -F'\t' '$1 == "DB_PORT"              { c++ } END { print c+0 }' "$TMP/envvars.tsv")
    dbport_literal=$(awk -F'\t' '$1 == "DB_PORT" && $3 == 1   { c++ } END { print c+0 }' "$TMP/envvars.tsv")
    dbport_ref=$(awk     -F'\t' '$1 == "DB_PORT" && $4 == 1   { c++ } END { print c+0 }' "$TMP/envvars.tsv")

    (( dbport_total > 0 )) || parse_fail "[$rel] INV-1 counted 0 DB_PORT EnvVar records after the presence check passed — the TSV field layout changed and this assertion is now blind. Fix the parser, do not delete the invariant."

    if (( dbport_literal > 0 || dbport_ref != dbport_total )); then
        awk -F'\t' -v rel="$rel" '
            $1 == "DB_PORT" && $3 == 1 { printf "  FAIL [%s] INV-1: DB_PORT (render line %s) carries a LITERAL \047value:\047.\n", rel, $2 }
            $1 == "DB_PORT" && $4 == 0 { printf "  FAIL [%s] INV-1: DB_PORT (render line %s) has NO \047valueFrom:\047.\n", rel, $2 }
        ' "$TMP/envvars.tsv" >&2
        echo "        The Postgres port is CONFIG, not a constant (GLOBAL_RULE_6): the host dev" >&2
        echo "        Postgres publishes 5433, so a hardcoded 5432 makes a local cluster impossible" >&2
        echo "        and makes every environment's port a manifest edit. Route DB_PORT through" >&2
        echo "        postgres-credentials/port, exactly as k8s/base/pg-backup-cronjob.yaml already" >&2
        echo "        does (grep 'key: port'). This asserts the SHAPE on the RENDER, so no quoting" >&2
        echo "        style evades it and no overlay can patch a literal back in unseen — the" >&2
        echo "        previous source-level 'value: \"5432\"' grep was evaded by 'value: '\''5432'\''" >&2
        echo "        with both gates still green (code review WR-01)." >&2
        FAILED=1
        inv1_msg="FAIL"
    else
        inv1_msg="OK ($dbport_total DB_PORT EnvVar(s), all valueFrom, 0 literal)"
    fi

    inv2_bad=0
    while IFS=$'\t' read -r name line hv hvf; do
        if (( hv == 1 && hvf == 1 )); then
            echo "  FAIL [$rel] INV-2: EnvVar '$name' (render line $line) carries BOTH 'value:' and 'valueFrom:'." >&2
            inv2_bad=1
        fi
    done < "$TMP/envvars.tsv"
    if (( inv2_bad != 0 )); then
        echo "        kubectl kustomize accepts this, but the API server REJECTS the apply:" >&2
        echo "          env[i].valueFrom: Invalid value: \"\": may not be specified when 'value' is not empty" >&2
        echo "        A strategic-merge patch MERGES into the base env item and cannot remove a" >&2
        echo "        scalar, so 'value:' must be DELETED in k8s/base — there is no overlay" >&2
        echo "        shortcut (26-RESEARCH.md PIT-2)." >&2
        FAILED=1
        inv2_msg="FAIL"
    else
        inv2_msg="OK ($envvar_count EnvVars, DB_PORT present, 0 with both value+valueFrom)"
    fi

    # ---------------- INV-3 ----------------
    awk "$MATCHLABELS_AWK" "$render" > "$TMP/matchlabels.tsv"
    dns_blocks=$(awk -F'\t' '$3 == 1' "$TMP/matchlabels.tsv" | wc -l)
    (( dns_blocks > 0 )) || parse_fail "[$rel] INV-3 found 0 matchLabels blocks selecting 'k8s-app: kube-dns' — either the DNS egress rules vanished or the parser is blind. Either way this assertion would pass vacuously; investigate, do not delete the invariant."

    inv3_bad=0
    while IFS=$'\t' read -r line nkeys is_dns keys; do
        [[ "$is_dns" == "1" ]] || continue
        if [[ "$keys" != "k8s-app" ]]; then
            echo "  FAIL [$rel] INV-3: the kube-dns podSelector at render line $line has $nkeys key(s): $keys" >&2
            inv3_bad=1
        fi
    done < "$TMP/matchlabels.tsv"
    if (( inv3_bad != 0 )); then
        echo "        A kube-dns selector must contain ONLY 'k8s-app'. Real kube-dns pods carry no" >&2
        echo "        app.kubernetes.io/* or environment label, so ANY extra key narrows the" >&2
        echo "        selector to zero pods and core-java loses ALL DNS egress under an enforcing" >&2
        echo "        CNI — a total outage (D-17). The usual cause is the base kustomization's" >&2
        echo "        label transformer reverting to 'includeSelectors: true' instead of the" >&2
        echo "        explicit 'fields:' list. This is invisible to" >&2
        echo "        k8s/scripts/validate-networkpolicies.py, which parses raw files, not the" >&2
        echo "        render — which is why the assertion lives here." >&2
        FAILED=1
        inv3_msg="FAIL"
    else
        inv3_msg="OK ($dns_blocks kube-dns selector block(s), each exactly 1 key)"
    fi

    # ---------------- INV-4 ----------------
    if is_local_only_target "$rel"; then
        inv4_msg="SKIP (LOCAL_ONLY_TARGETS: this overlay deliberately targets host services)"
    else
        inv4_bad=0
        for lit in "${FORBIDDEN_RENDER_LITERALS[@]}"; do
            if grep -nE "$lit" "$render" > "$TMP/hits" 2> /dev/null; then
                echo "  FAIL [$rel] INV-4: forbidden local-only literal '$lit' in the render:" >&2
                sed 's/^/        /' "$TMP/hits" >&2
                inv4_bad=1
            fi
        done
        if (( inv4_bad != 0 )); then
            echo "        This is the DEF-6 recurrence shape: a value that is only correct on a" >&2
            echo "        developer laptop reaching a real cluster, where it fails SILENTLY (media" >&2
            echo "        writes nowhere, email to a loopback relay, a production link pointing at" >&2
            echo "        localhost). Supply the environment's real value via app-config or a" >&2
            echo "        Secret. If this genuinely IS a local overlay, add its exact path to" >&2
            echo "        LOCAL_ONLY_TARGETS with a reason." >&2
            FAILED=1
            inv4_msg="FAIL"
        else
            inv4_msg="OK (0 localhost / 127.0.0.1 / minioadmin literals)"
        fi
    fi

    # ---------------- INV-6 ----------------
    # Every Ingress backend Service name must resolve to a Service in this SAME
    # render. Runs for EVERY target (base, local, staging, production): the
    # defect it pins was a production defect, not a local-overlay one.
    awk "$INGRESS_BACKEND_AWK" "$render" > "$TMP/ingress.tsv"
    awk -F'\t' '$1 == "SVC" { print $2 }' "$TMP/ingress.tsv" | sort -u > "$TMP/services.txt"
    awk -F'\t' '$1 == "ING"' "$TMP/ingress.tsv" > "$TMP/backends.tsv"

    svc_count=$(wc -l < "$TMP/services.txt")
    backend_count=$(wc -l < "$TMP/backends.tsv")
    (( svc_count > 0 )) || parse_fail "[$rel] INV-6 found 0 'kind: Service' documents in the render. Either the render lost every Service (a far bigger problem) or the parser is blind — in which case every backend would be reported unresolved, or, if the backend parse is equally blind, nothing would be checked at all. Fix the parser, do not delete the invariant."
    (( backend_count > 0 )) || parse_fail "[$rel] INV-6 found 0 Ingress backend references in the render. This platform ships two Ingresses (jtoye-ingress + jtoye-sse-ingress) in every target, so zero backends means the Ingress shape changed and the assertion is now vacuous. Fix the parser, do not delete the invariant."

    inv6_bad=0
    inv6_allowed=0
    while IFS=$'\t' read -r _tag ing host backend; do
        if grep -qxF "$backend" "$TMP/services.txt"; then
            continue
        fi
        if [[ -n "${ALLOW_INGRESS_REASON[$backend]:-}" ]]; then
            ALLOW_INGRESS_USED["$backend"]=1
            (( ++inv6_allowed ))
            echo "  INFO [$rel] INV-6: backend Service '$backend' (host '$host', Ingress '$ing') is ALLOWLISTED: ${ALLOW_INGRESS_REASON[$backend]}"
            continue
        fi
        echo "  FAIL [$rel] INV-6: Ingress '$ing' publishes host '$host' and routes it to Service '$backend', which does NOT exist in the $rel render." >&2
        echo "        Services present in this render: $(tr '\n' ' ' < "$TMP/services.txt")" >&2
        inv6_bad=1
    done < "$TMP/backends.tsv"

    if (( inv6_bad != 0 )); then
        echo "        nginx answers 503 for a published host with no backend — a broken endpoint that" >&2
        echo "        looks configured. It is also a TLS hazard: hosts share one certificate secret," >&2
        echo "        and a cert-manager HTTP-01 challenge for a hostname this controller does not" >&2
        echo "        actually serve can fail the WHOLE order, stalling issuance for the hosts that" >&2
        echo "        DO work. This is exactly what the Keycloak host rule did in staging and" >&2
        echo "        production until plan 26-04 removed it." >&2
        echo "        NOTE: k8s/base deliberately ships NO Keycloak workload — Keycloak is an" >&2
        echo "        EXTERNAL managed identity provider (see app-config keycloak.issuer.uri), and" >&2
        echo "        public DNS for its hostname resolves to that IdP, not to this controller. So" >&2
        echo "        the fix is to REMOVE the rule, NOT to add a Service. If a backend really is" >&2
        echo "        created outside kustomize, add it to ALLOW_UNRESOLVED_INGRESS_BACKEND WITH a" >&2
        echo "        reason." >&2
        FAILED=1
        inv6_msg="FAIL"
    else
        inv6_msg="OK ($backend_count backend ref(s) -> $svc_count Service(s))"
        if (( inv6_allowed > 0 )); then
            inv6_msg="OK ($backend_count backend ref(s) -> $svc_count Service(s), $inv6_allowed allowlisted)"
        fi
    fi

    # ---------------- INV-8 ----------------
    # D-19: no Ingress in ANY render may route to a Service that has no
    # authentication of its own. Runs for every target, including k8s/local — a
    # laptop cluster with a published Alertmanager is still a mute button, and the
    # local overlay's `rules:` replacement is exactly the mechanism that would hide
    # such a rule from a base-only assertion (the INV-6 lesson).
    #
    # ITS OWN EXTRACTION AND ITS OWN GUARDS, deliberately, rather than reusing the
    # arrays INV-6 just built: an assertion whose fail-closed guard belongs to a
    # different assertion cannot be shown to fail on its own.
    awk -F'\t' '$1 == "ING"' "$TMP/ingress.tsv" > "$TMP/published.tsv"
    pub_count=$(wc -l < "$TMP/published.tsv")
    (( pub_count > 0 )) || parse_fail "[$rel] INV-8 found 0 Ingress backend references in the render, so it examined nothing and would report a clean run over any tree at all. This platform ships two Ingresses in every target; zero means the Ingress shape changed or the parser is blind. 'Found nothing' is never 'nothing is published' — fix the parser, do not delete the invariant."

    inv8_bad=0
    inv8_allowed=0
    while IFS=$'\t' read -r _tag ing host backend; do
        for never in "${NEVER_PUBLISHED_BACKENDS[@]}"; do
            [[ "$backend" == "$never" ]] || continue
            if [[ -n "${ALLOW_PUBLISHED_REASON[$backend]:-}" ]]; then
                ALLOW_PUBLISHED_USED["$backend"]=1
                (( ++inv8_allowed ))
                echo "  INFO [$rel] INV-8: backend Service '$backend' (host '$host', Ingress '$ing') is ALLOWLISTED: ${ALLOW_PUBLISHED_REASON[$backend]}"
                continue
            fi
            echo "  FAIL [$rel] INV-8: Ingress '$ing' publishes host '$host' and routes it to Service '$backend', which must never be published." >&2
            inv8_bad=1
        done
    done < "$TMP/published.tsv"

    if (( inv8_bad != 0 )); then
        echo "        D-19 / Security Domain V4. Prometheus and Alertmanager have NO authentication" >&2
        echo "        of their own. A reachable Prometheus hands over every series this platform" >&2
        echo "        emits; a reachable Alertmanager lets anyone CREATE SILENCES, i.e. mute the" >&2
        echo "        platform's entire alerting surface from a browser." >&2
        echo "        GRAFANA is the one monitoring surface that may be published, because it has a" >&2
        echo "        login. For the other two use a port-forward:" >&2
        echo "            kubectl -n <ns> port-forward svc/prometheus 9090:9090" >&2
        echo "            kubectl -n <ns> port-forward svc/alertmanager 9093:9093" >&2
        echo "        or put an authenticating proxy in front and publish THAT Service. If this is" >&2
        echo "        genuinely deliberate, add an entry to ALLOW_PUBLISHED_MONITORING_BACKEND WITH a" >&2
        echo "        reason — the list is empty today and that is the correct state." >&2
        FAILED=1
        inv8_msg="FAIL"
    else
        inv8_msg="OK ($pub_count backend ref(s) scanned, 0 publish an unauthenticated monitoring Service)"
        if (( inv8_allowed > 0 )); then
            inv8_msg="OK ($pub_count backend ref(s) scanned, $inv8_allowed allowlisted)"
        fi
    fi

    # ---------------- INV-7 ----------------
    # Issue #271: the Postgres egress allowance must follow the RENDERED
    # app-config db.port, in every target, in both policies.
    awk "$CONFIGMAP_DATA_AWK" "$render" > "$TMP/cfg.tsv"
    (( $(wc -l < "$TMP/cfg.tsv") > 0 )) || parse_fail "[$rel] INV-7 found no app-config data keys in the render — the ConfigMap shape changed and the port comparison has no source. Fix the parser, do not delete the invariant."
    # The rendered ConfigMap value keeps its quotes — kustomize QUOTES a
    # numeric-looking string so it stays a string (`db.port: "5432"`), which is
    # correct for a ConfigMap and is exactly why the NetworkPolicy side has to be
    # checked for the opposite property (bare digits, i.e. a real integer port).
    db_port=$(awk -F'\t' '$1 == "db.port" { v = $2; gsub(/^["\047]|["\047]$/, "", v); print v; found = 1 } END { if (!found) print "" }' "$TMP/cfg.tsv")
    if [[ -z "$db_port" ]]; then
        parse_fail "[$rel] INV-7 found no app-config key 'db.port' in the render. That key is the single RENDER-TIME declaration of the Postgres port and the source of the kustomize replacement that drives both NetworkPolicies (issue #271). Without it the comparison has nothing to compare against and would pass vacuously — restore the key, do not delete the invariant."
    fi
    if [[ ! "$db_port" =~ ^[0-9]+$ ]]; then
        parse_fail "[$rel] INV-7: app-config 'db.port' is '$db_port', which is not a bare port number. A NetworkPolicy port that is not an integer is a NAMED port and matches no traffic."
    fi

    awk "$NETPOL_INFRA_AWK" "$render" > "$TMP/netpol.tsv"
    pol_seen=$(awk -F'\t' '$1 == "POL" { print $2 }' "$TMP/netpol.tsv" | sort -u | wc -l)
    # The count in this message is DELIBERATELY not asserted — it is orientation
    # for whoever reads the failure, and the assertion is `> 0`. Writing an exact
    # expected count here would be a second place to update every time a policy is
    # added, and the thing being guarded against is a BLIND PARSER, not a miscount.
    (( pol_seen > 0 )) || parse_fail "[$rel] INV-7 found 0 NetworkPolicy documents in the render. This platform ships fourteen (five app-tier + seven observability + keycloak-allow (plan 29-08) + rabbitmq-allow (plan 29-09) — counted off the render, not remembered); zero means the parser is blind and every port assertion below would pass vacuously. Fix the parser, do not delete the invariant."

    inv7_bad=0
    inv7_checked=0
    inv7_ip_checked=0
    inv7_except_checked=0
    for pol in $(printf '%s\n' "${!NETPOL_INFRA_EXPECTED[@]}" | sort); do
        # $'\t' rather than a literal tab: a stripped-whitespace edit would turn
        # this into a pattern that never matches, and a never-matching presence
        # check fails CLOSED here (parse_fail) — loud, but for the wrong reason.
        if ! grep -qxF "POL"$'\t'"$pol" "$TMP/netpol.tsv"; then
            parse_fail "[$rel] INV-7 expected a NetworkPolicy named '$pol' in the render and found none. Either the policy was renamed/removed (in which case update NETPOL_INFRA_EXPECTED in the SAME change) or the parser is blind — either way the egress-port assertion for it would never run."
        fi
        # Raw tokens first, so a QUOTED port (a named port in NetworkPolicy
        # semantics — renders fine, applies fine, matches nothing) is caught
        # before it is normalised away by the numeric sort.
        mapfile -t raw_ports < <(awk -F'\t' -v p="$pol" '$1 == "NP" && $2 == p { print $3 }' "$TMP/netpol.tsv")
        if (( ${#raw_ports[@]} == 0 )); then
            parse_fail "[$rel] INV-7 found no '$INFRA_NAMESPACE_LABEL' egress ports on NetworkPolicy '$pol'. Either that policy lost its datastore egress entirely (core-java would have no database at all under an enforcing CNI) or the rule parser is blind. Fix it, do not delete the invariant."
        fi
        for tok in "${raw_ports[@]}"; do
            if [[ ! "$tok" =~ ^[0-9]+$ ]]; then
                echo "  FAIL [$rel] INV-7: NetworkPolicy '$pol' has a non-numeric egress port token '$tok'." >&2
                echo "        A STRING port in a NetworkPolicy is a NAMED port, not a number: it renders," >&2
                echo "        applies, and matches NO traffic. The db.port replacement source is a" >&2
                echo "        ConfigMap string, so this is a real failure mode of the mechanism." >&2
                inv7_bad=1
            fi
        done
        actual=$(printf '%s\n' "${raw_ports[@]}" | sort -n | tr '\n' ' ')
        expected=$(printf '%s\n' ${NETPOL_INFRA_EXPECTED[$pol]//__DB_PORT__/$db_port} | sort -n | tr '\n' ' ')
        (( ++inv7_checked ))
        if [[ "$actual" != "$expected" ]]; then
            echo "  FAIL [$rel] INV-7: NetworkPolicy '$pol' allows egress ports [${actual% }] toward namespace '$INFRA_NAMESPACE_LABEL'; expected [${expected% }] (app-config db.port = $db_port)." >&2
            inv7_bad=1
        fi
    done

    # ---------------- INV-7, second arm: the ipBlock egress surface ----------
    # Blocker D / plan 29-04. The arm above cannot see these rules at all — it
    # keys on the jtoye-infrastructure namespaceSelector — so this one carries
    # its own declaration, its own fail-closed parse guards, and its own
    # substitutions. Every guard below is "found zero => parse_fail", never a
    # pass: an ipBlock arm that silently checked nothing is precisely the
    # vacuity this arm was added to remove.
    for _key in redis.port db.egress-cidr redis.egress-cidr; do
        _val=$(awk -F'\t' -v k="$_key" '$1 == k { v = $2; gsub(/^["\047]|["\047]$/, "", v); print v; found = 1 } END { if (!found) print "" }' "$TMP/cfg.tsv")
        case "$_key" in
            redis.port)         redis_port="$_val" ;;
            db.egress-cidr)     db_cidr="$_val" ;;
            redis.egress-cidr)  redis_cidr="$_val" ;;
        esac
        if [[ -z "$_val" ]]; then
            parse_fail "[$rel] INV-7 found no app-config key '$_key' in the render. It is the RENDER-TIME declaration the out-of-cluster egress rule is derived from (Blocker D); without it this arm has nothing to compare against and would pass vacuously — restore the key, do not delete the invariant."
        fi
    done
    if [[ ! "$redis_port" =~ ^[0-9]+$ ]]; then
        parse_fail "[$rel] INV-7: app-config 'redis.port' is '$redis_port', which is not a bare port number. A NetworkPolicy port that is not an integer is a NAMED port and matches no traffic — it renders, it applies, and the managed cache is unreachable."
    fi
    for _c in "$db_cidr" "$redis_cidr"; do
        [[ "$_c" =~ $CIDR4_RE ]] || parse_fail "[$rel] INV-7: app-config egress CIDR '$_c' is not an IPv4 CIDR. This arm evaluates IPv4 only and does not skip what it cannot evaluate."
    done

    # __SMTP_PORT__ (plan 29-07): derived from the PORT SUFFIX of app-config
    # `alerting.smtp.smarthost`, which is the single declaration of the alert
    # relay. Unlike db.port / redis.port there is no kustomize replacement to
    # confirm — kustomize cannot split a `host:port` string — so this derivation IS
    # the only coupling between the relay the config names and the port the
    # NetworkPolicy permits.
    smtp_smarthost=$(awk -F'\t' '$1 == "alerting.smtp.smarthost" { v = $2; gsub(/^["\047]|["\047]$/, "", v); print v; found = 1 } END { if (!found) print "" }' "$TMP/cfg.tsv")
    if [[ -z "$smtp_smarthost" ]]; then
        parse_fail "[$rel] INV-7 found no app-config key 'alerting.smtp.smarthost' in the render. It is the RENDER-TIME declaration of the alert relay and the source of the expected SMTP egress port; without it the alertmanager-allow comparison has nothing to compare against and would pass vacuously — restore the key, do not delete the invariant."
    fi
    smtp_port="${smtp_smarthost##*:}"
    if [[ "$smtp_port" == "$smtp_smarthost" || ! "$smtp_port" =~ ^[0-9]+$ ]]; then
        parse_fail "[$rel] INV-7: app-config 'alerting.smtp.smarthost' is '$smtp_smarthost', from which no numeric port could be read. Alertmanager needs an explicit host:port smarthost, and this arm needs that port to know what egress to expect — a smarthost with no port is a relay nothing can reach and an assertion with nothing to assert."
    fi

    awk "$NETPOL_IPBLOCK_AWK" "$render" > "$TMP/netpol-ip.tsv"
    ippol_seen=$(awk -F'\t' '$1 == "IPPOL" { print $2 }' "$TMP/netpol-ip.tsv" | sort -u | wc -l)
    (( ippol_seen > 0 )) || parse_fail "[$rel] INV-7 found 0 NetworkPolicies with an ipBlock egress rule in the render. This platform ships seven (four app-tier + both exporters + alertmanager, plan 29-07); zero means the ipBlock parser is blind and every out-of-cluster assertion below would pass vacuously. Fix the parser, do not delete the invariant."
    # NOT `awk … | grep -q .`: under `set -o pipefail` grep exits at the first
    # match, the writer takes SIGPIPE, and pipefail promotes it to 141 — so the
    # guard would fire on the CLEAN case and stay silent on the broken one. It
    # fails OPEN, which for a fail-closed parse guard is the worst direction.
    # Count into a variable instead; no pipe, no inversion.
    ipmulti_seen=$(awk -F'\t' '$1 == "IPMULTI" { n++ } END { print n+0 }' "$TMP/netpol-ip.tsv")
    if (( ipmulti_seen > 0 )); then
        parse_fail "[$rel] INV-7 found $ipmulti_seen egress rule(s) carrying more than one ipBlock. The 'except' entries can then no longer be attributed to a cidr by position, so the containment check would be guessing. Split the rule (one peer, one port per rule) or teach this parser to pair them."
    fi

    # (b) A policy with an ipBlock rule and NO declaration is an UNDECLARED hole.
    while read -r undeclared; do
        [[ -n "$undeclared" ]] || continue
        if [[ -z "${NETPOL_IPBLOCK_EXPECTED[$undeclared]+set}" ]]; then
            echo "  FAIL [$rel] INV-7: NetworkPolicy '$undeclared' renders an ipBlock egress rule but has no entry in NETPOL_IPBLOCK_EXPECTED." >&2
            echo "        An out-of-cluster egress hole that this gate does not look at reads as coverage" >&2
            echo "        while governing nothing. Declare its exact <cidr>:<port> multiset in the SAME" >&2
            echo "        change that adds the rule." >&2
            inv7_bad=1
        fi
    done < <(awk -F'\t' '$1 == "IPPOL" { print $2 }' "$TMP/netpol-ip.tsv" | sort -u)

    for pol in $(printf '%s\n' "${!NETPOL_IPBLOCK_EXPECTED[@]}" | sort); do
        if ! grep -qxF "POL"$'\t'"$pol" "$TMP/netpol.tsv"; then
            parse_fail "[$rel] INV-7 expected a NetworkPolicy named '$pol' in the render and found none, so its ipBlock declaration governs nothing. Either the policy was renamed/removed (update NETPOL_IPBLOCK_EXPECTED in the SAME change) or the parser is blind."
        fi
        mapfile -t ip_pairs < <(awk -F'\t' -v p="$pol" '$1 == "IPB" && $2 == p { print $3 ":" $4 }' "$TMP/netpol-ip.tsv")
        if (( ${#ip_pairs[@]} == 0 )); then
            parse_fail "[$rel] INV-7 found no ipBlock <cidr>:<port> pairs on NetworkPolicy '$pol'. Either that policy lost its public/out-of-cluster egress entirely or the rule parser is blind. Fix it, do not delete the invariant."
        fi
        for pair in "${ip_pairs[@]}"; do
            if [[ ! "${pair##*:}" =~ ^[0-9]+$ ]]; then
                echo "  FAIL [$rel] INV-7: NetworkPolicy '$pol' has a non-numeric ipBlock egress port in '$pair'." >&2
                echo "        A STRING port is a NAMED port: it renders, it applies, and it matches NO traffic." >&2
                inv7_bad=1
            fi
        done
        ip_actual=$(printf '%s\n' "${ip_pairs[@]}" | sort | tr '\n' ' ')
        ip_expected_raw="${NETPOL_IPBLOCK_EXPECTED[$pol]}"
        ip_expected_raw="${ip_expected_raw//__DB_PORT__/$db_port}"
        ip_expected_raw="${ip_expected_raw//__REDIS_PORT__/$redis_port}"
        ip_expected_raw="${ip_expected_raw//__DB_CIDR__/$db_cidr}"
        ip_expected_raw="${ip_expected_raw//__REDIS_CIDR__/$redis_cidr}"
        ip_expected_raw="${ip_expected_raw//__SMTP_PORT__/$smtp_port}"
        ip_expected=$(printf '%s\n' $ip_expected_raw | sort | tr '\n' ' ')
        (( ++inv7_ip_checked ))
        if [[ "$ip_actual" != "$ip_expected" ]]; then
            echo "  FAIL [$rel] INV-7: NetworkPolicy '$pol' allows ipBlock egress [${ip_actual% }]; expected [${ip_expected% }]" >&2
            echo "        (app-config db.port=$db_port redis.port=$redis_port db.egress-cidr=$db_cidr redis.egress-cidr=$redis_cidr" >&2
            echo "         alerting.smtp.smarthost=$smtp_smarthost -> smtp port $smtp_port)." >&2
            inv7_bad=1
        fi
    done

    # (c) Every `except:` entry must be strictly WITHIN its rule's cidr, or the
    #     API server rejects the manifest at apply time.
    while IFS=$'\t' read -r _tag xpol xcidr xexc; do
        [[ "$_tag" == "IPX" ]] || continue
        (( ++inv7_except_checked ))
        # `_rc=$?` on its own line after the call would report the status of
        # whatever bash ran last, and `set -e` would abort on a legitimate
        # non-zero anyway. The `|| _rc=$?` form captures the FUNCTION's status,
        # on the same statement, in a context set -e tolerates.
        _rc=0
        cidr4_contains "$xcidr" "$xexc" || _rc=$?
        case "$_rc" in
            0)  continue ;;
            2)
                parse_fail "[$rel] INV-7 could not evaluate ipBlock containment for '$xexc' within '$xcidr' on NetworkPolicy '$xpol' (not IPv4 / malformed). Cannot-evaluate is not clean." ;;
            *)
                echo "  FAIL [$rel] INV-7: NetworkPolicy '$xpol' has ipBlock except '$xexc' which is NOT within cidr '$xcidr'." >&2
                echo "        The Kubernetes API rejects this manifest — 'except values must be within the cidr" >&2
                echo "        range' — so this is a FAILED DEPLOY, not a style point. It is what narrowing" >&2
                echo "        db.egress-cidr / redis.egress-cidr to a /32 produces if the RFC1918 except list" >&2
                echo "        is left behind. Drop the excepts in the same change that narrows the cidr." >&2
                inv7_bad=1 ;;
        esac
    done < "$TMP/netpol-ip.tsv"

    if (( inv7_bad != 0 )); then
        echo "        ISSUE #271. DB_PORT is Secret-driven (DEF-1) so an environment can move its" >&2
        echo "        Postgres port with no manifest edit — but THIS policy is what permits the" >&2
        echo "        connection. If it does not follow, an enforcing CNI denies the connection and" >&2
        echo "        every core-java replica CrashLoops (and the nightly pg-backup dump fails)," >&2
        echo "        with a network denial that reads like an application fault." >&2
        echo "        THE FIX IS NEVER TO EDIT THE PORT LITERAL IN THE POLICY. Set app-config" >&2
        echo "        'db.port' and let the 'replacements:' block derive it. If this is an OVERLAY," >&2
        echo "        check that overlay's own kustomization.yaml carries the replacements block:" >&2
        echo "        kustomize does NOT re-run a base replacement against an overlay's patched" >&2
        echo "        ConfigMap, so a base-only block leaves the overlay on the base port." >&2
        echo "" >&2
        echo "        BLOCKER D (the ipBlock arm). The same coupling exists for the OUT-OF-CLUSTER" >&2
        echo "        managed datastores: D-09 puts Postgres and Redis outside the cluster, where" >&2
        echo "        neither the jtoye-infrastructure rules nor the 443-only public rule reaches" >&2
        echo "        them. The dedicated single-port rules are what permit those connections, and" >&2
        echo "        their ports and addresses come from app-config db.port / redis.port /" >&2
        echo "        db.egress-cidr / redis.egress-cidr. DO NOT fix a mismatch by widening the" >&2
        echo "        0.0.0.0/0:443 rule to carry 5432 or 6380 — that is the shortcut this arm" >&2
        echo "        exists to refuse (T-29-04-01), and it is not the allow-list it claims to be." >&2
        FAILED=1
        inv7_msg="FAIL"
    else
        inv7_msg="OK ($inv7_checked infra policy/policies db.port=$db_port; $inv7_ip_checked ipBlock policy/policies redis.port=$redis_port, $inv7_except_checked except entry/entries contained)"
    fi

    if [[ "$inv1_msg" == FAIL* || "$inv2_msg" == FAIL* || "$inv3_msg" == FAIL* \
          || "$inv4_msg" == FAIL* || "$inv6_msg" == FAIL* || "$inv7_msg" == FAIL* \
          || "$inv8_msg" == FAIL* ]]; then
        echo "FAIL [$rel]: INV-1 $inv1_msg | INV-2 $inv2_msg | INV-3 $inv3_msg | INV-4 $inv4_msg | INV-6 $inv6_msg | INV-7 $inv7_msg | INV-8 $inv8_msg" >&2
    else
        echo "OK   [$rel]: INV-1 $inv1_msg | INV-2 $inv2_msg | INV-3 $inv3_msg | INV-4 $inv4_msg | INV-6 $inv6_msg | INV-7 $inv7_msg | INV-8 $inv8_msg"
    fi
done
echo

# ---------------------------------------------------------------------------
# INV-6 allowlist STALE rule: an entry nobody needed is a standing excuse for
# something already fixed, so it fails rather than rotting silently.
# ---------------------------------------------------------------------------
if (( ${#ALLOW_INGRESS_REASON[@]} > 0 )); then
    for svc in "${!ALLOW_INGRESS_REASON[@]}"; do
        if [[ -z "${ALLOW_INGRESS_USED[$svc]:-}" ]]; then
            echo "FAIL: INV-6 allowlist: STALE entry '$svc' — every target's render now resolves that backend (or no Ingress references it at all), so the exemption is unnecessary. Remove the entry rather than leaving a standing excuse for a defect that is already fixed." >&2
            FAILED=1
        fi
    done
fi

# ---------------------------------------------------------------------------
# INV-8 allowlist STALE rule. Same reasoning, and if anything it matters more
# here: an unused exemption for publishing an unauthenticated monitoring surface
# is a standing permission slip for the exact thing the invariant forbids.
# ---------------------------------------------------------------------------
if (( ${#ALLOW_PUBLISHED_REASON[@]} > 0 )); then
    for svc in "${!ALLOW_PUBLISHED_REASON[@]}"; do
        if [[ -z "${ALLOW_PUBLISHED_USED[$svc]:-}" ]]; then
            echo "FAIL: INV-8 allowlist: STALE entry '$svc' — no Ingress in any target publishes that Service, so the exemption grants nothing and is a standing permission slip for exposing it later without a second thought. Remove the entry." >&2
            FAILED=1
        fi
    done
fi

# ===========================================================================
# INV-5 — docs level, block-scoped per Secret name
# ===========================================================================
echo "INV-5 (DEF-2 / INFRA-02b, docs): the DB superuser is never the postgres-credentials app username"

# --- awk: attribute every `--from-literal=username=<v>` to the
#     `kubectl create secret generic <name>` command it belongs to. A leading
#     comment marker is stripped first, so a recipe living INSIDE a comment
#     block (the template has one) is covered exactly like a live recipe.
RECIPE_AWK='
{
    line = $0
    sub(/^[[:space:]]*#[[:space:]]?/, "", line)
    if (line ~ /kubectl create secret generic[[:space:]]+[A-Za-z0-9._-]+/) {
        secret = line
        sub(/.*kubectl create secret generic[[:space:]]+/, "", secret)
        sub(/[^A-Za-z0-9._-].*$/, "", secret)
    }
    if (secret != "" && line ~ /--from-literal=username=/) {
        v = line
        sub(/.*--from-literal=username=/, "", v)
        sub(/[[:space:]\\].*$/, "", v)
        gsub(/^['"'"'"]|['"'"'"]$/, "", v)
        printf "%s\t%s\t%d\n", secret, v, NR
    }
}
'

# --- awk: attribute every `username:` value to the YAML document whose
#     metadata.name it belongs to. Documents are split on a top-level `---`.
STRINGDATA_AWK='
/^---[[:space:]]*$/ { secret = ""; next }
{
    line = $0
    if (line ~ /^[[:space:]]*#/) next
    if (line ~ /^[[:space:]]+name:[[:space:]]/ && secret == "") {
        secret = line
        sub(/^[[:space:]]+name:[[:space:]]*/, "", secret)
        gsub(/^["'"'"']|["'"'"']$/, "", secret)
    }
    if (secret != "" && line ~ /^[[:space:]]+username:[[:space:]]/) {
        v = line
        sub(/^[[:space:]]+username:[[:space:]]*/, "", v)
        sub(/[[:space:]]*#.*$/, "", v)
        gsub(/^["'"'"']|["'"'"']$/, "", v)
        printf "%s\t%s\t%d\n", secret, v, NR
    }
}
'

inv5_bad=0
inv5_checked=0

check_pg_username() {
    # check_pg_username <file-label> <tsv-file> <what>
    local label="$1" tsv="$2" what="$3" secret value line
    while IFS=$'\t' read -r secret value line; do
        [[ "$secret" == "postgres-credentials" ]] || continue
        (( ++inv5_checked ))
        if [[ "$value" == "$DB_SUPERUSER_ROLE" ]]; then
            echo "  FAIL [$label:$line] $what names the DB SUPERUSER '$value' as the postgres-credentials username." >&2
            inv5_bad=1
        else
            echo "  OK   [$label:$line] $what -> postgres-credentials username='$value'"
        fi
    done < "$tsv"
}

awk "$RECIPE_AWK"     "$QUICK_START"      > "$TMP/qs-recipe.tsv"
awk "$RECIPE_AWK"     "$SECRETS_TEMPLATE" > "$TMP/tpl-recipe.tsv"
awk "$STRINGDATA_AWK" "$SECRETS_TEMPLATE" > "$TMP/tpl-data.tsv"

check_pg_username "k8s/QUICK_START.md"                  "$TMP/qs-recipe.tsv"  "create-secret recipe"
check_pg_username "k8s/base/secrets-template.yaml.example" "$TMP/tpl-recipe.tsv" "comment-block recipe"
check_pg_username "k8s/base/secrets-template.yaml.example" "$TMP/tpl-data.tsv"   "stringData"

(( inv5_checked >= 3 )) || parse_fail "INV-5 located only $inv5_checked postgres-credentials username site(s); 3 are expected (the QUICK_START recipe, the template's comment-block recipe, and the template stringData). A missing site means the assertion is partly blind — fix the parser or the docs, do not delete the invariant."

if (( inv5_bad != 0 )); then
    echo >&2
    echo "        A Postgres SUPERUSER BYPASSES EVERY RLS POLICY, and RLS is this platform's" >&2
    echo "        entire multi-tenant isolation boundary. DatabaseConfigurationValidator fails" >&2
    echo "        core-java's boot fast when it detects a superuser precisely for that reason, so" >&2
    echo "        a copy-pasteable superuser recipe is a latent RLS bypass AND a guaranteed" >&2
    echo "        CrashLoopBackOff. Use the NOSUPERUSER app role (.env DB_USER, 'jtoye_app')." >&2
    echo "        NOTE: 'jtoye' IS correct for rabbitmq-credentials (the broker user) and" >&2
    echo "        'jtoye_backup' IS correct for backup-username (the BYPASSRLS dump role) — this" >&2
    echo "        invariant is block-scoped to postgres-credentials on purpose." >&2
    FAILED=1
fi
echo

# ===========================================================================
# LOC-1..LOC-6 — the k8s/local overlay (Phase 26 / INFRA-01)
#
# CONDITIONAL BY DESIGN: if the overlay is ever removed this section is skipped
# and the script stays valid, rather than failing on a missing directory.
# ===========================================================================
LOCAL_SECTION="LOC-1..LOC-6 SKIPPED (k8s/local/kustomization.yaml not present)"

if [[ -f "$LOCAL_KUSTOMIZATION" ]]; then
    echo "LOC-1..LOC-6 (INFRA-01, k8s/local): the committed local overlay's shape"

    LOCAL_RENDER="$TMP/loc_local.yaml"
    if ! kubectl kustomize "$LOCAL_DIR" > "$LOCAL_RENDER" 2> "$TMP/stderr"; then
        echo "ERROR [k8s/local]: 'kubectl kustomize' build failed:" >&2
        cat "$TMP/stderr" >&2
        exit 2
    fi
    BASE_RENDER="$TMP/loc_base.yaml"
    if ! kubectl kustomize "$K8S_DIR/base" > "$BASE_RENDER" 2> "$TMP/stderr"; then
        echo "ERROR [k8s/base]: 'kubectl kustomize' build failed (needed as the LOC-2 maxReplicas reference):" >&2
        cat "$TMP/stderr" >&2
        exit 2
    fi

    LOCAL_HOST_SHIM="host.minikube.internal"

    # Keys whose value MUST resolve through the minikube host gateway. Asserted
    # per key BY NAME, so a lost shim cannot hide behind an added one.
    SHIMMED_KEYS=(
      'redis.host'
      'rabbitmq.host'
      'stomp.broker.relay-host'
      's3.endpoint'
      's3.backup.endpoint'
      'smtp.host'
      'keycloak.issuer.uri'
      'keycloak.admin.base-url'
    )

    awk "$CONFIGMAP_DATA_AWK" "$LOCAL_RENDER" > "$TMP/loc_cfg.tsv"
    cfg_keys=$(wc -l < "$TMP/loc_cfg.tsv")
    (( cfg_keys > 0 )) || parse_fail "LOC-1 found no app-config data keys in the k8s/local render — the ConfigMap shape changed and every LOC-1/LOC-3 assertion would pass vacuously. Fix the parser, do not delete the invariant."

    cfg_value() {
        awk -F'\t' -v k="$1" '$1 == k { print $2; found=1 } END { if (!found) print "(ABSENT)" }' "$TMP/loc_cfg.tsv"
    }

    # ---------------- LOC-1 ----------------
    loc1_bad=0
    for key in "${SHIMMED_KEYS[@]}"; do
        val="$(cfg_value "$key")"
        if [[ "$val" != *"$LOCAL_HOST_SHIM"* ]]; then
            echo "  FAIL [k8s/local] LOC-1: app-config key '$key' is '$val' — it must resolve through '$LOCAL_HOST_SHIM'." >&2
            loc1_bad=1
        fi
    done
    shim_total=$(grep -c "$LOCAL_HOST_SHIM" "$LOCAL_RENDER" || true)
    if (( shim_total < ${#SHIMMED_KEYS[@]} )); then
        echo "  FAIL [k8s/local] LOC-1: only $shim_total '$LOCAL_HOST_SHIM' occurrence(s) in the render; at least ${#SHIMMED_KEYS[@]} are required (one per shimmed key)." >&2
        loc1_bad=1
    fi
    if (( loc1_bad != 0 )); then
        echo "        A pod cannot reach the host's docker-compose backing services on localhost —" >&2
        echo "        that is the POD's own loopback. minikube maintains the host-gateway mapping as" >&2
        echo "        '$LOCAL_HOST_SHIM' (its underlying IP varies by driver, so an IP literal is" >&2
        echo "        the DEF-1 defect class). An unshimmed endpoint fails at RUNTIME, per feature," >&2
        echo "        not at build time: a wrong s3.endpoint breaks image upload only, a wrong" >&2
        echo "        smtp.host breaks email only. DELIBERATE EXCEPTIONS, both browser-reachable and" >&2
        echo "        both correctly NOT in the list above: s3.public-url (the browser loads image" >&2
        echo "        URLs) and keycloak.public.issuer.uri (the issuer Keycloak STAMPS into 'iss')." >&2
        FAILED=1
        loc1_msg="FAIL"
    else
        loc1_msg="OK (${#SHIMMED_KEYS[@]} keys shimmed by name, $shim_total render occurrence(s))"
    fi

    # ---------------- LOC-2 ----------------
    awk "$SCALE_AWK" "$LOCAL_RENDER" > "$TMP/loc_scale.tsv"
    awk "$SCALE_AWK" "$BASE_RENDER"  > "$TMP/base_scale.tsv"

    dep_replicas=$(awk -F'\t' '$1=="Deployment" && $3=="replicas"'                 "$TMP/loc_scale.tsv" | wc -l)
    dep_ones=$(awk -F'\t'     '$1=="Deployment" && $3=="replicas" && $4=="1"'      "$TMP/loc_scale.tsv" | wc -l)
    hpa_mins=$(awk -F'\t'     '$1=="HorizontalPodAutoscaler" && $3=="minReplicas"' "$TMP/loc_scale.tsv" | wc -l)
    hpa_ones=$(awk -F'\t'     '$1=="HorizontalPodAutoscaler" && $3=="minReplicas" && $4=="1"' "$TMP/loc_scale.tsv" | wc -l)
    pdb_mins=$(awk -F'\t'     '$1=="PodDisruptionBudget" && $3=="minAvailable"'    "$TMP/loc_scale.tsv" | wc -l)
    pdb_ones=$(awk -F'\t'     '$1=="PodDisruptionBudget" && $3=="minAvailable" && $4=="1"' "$TMP/loc_scale.tsv" | wc -l)

    (( dep_replicas > 0 && hpa_mins > 0 && pdb_mins > 0 )) || parse_fail "LOC-2 found Deployment replicas=$dep_replicas, HPA minReplicas=$hpa_mins, PDB minAvailable=$pdb_mins in the k8s/local render — a zero means the parser is blind and the count assertions would pass vacuously. Fix the parser, do not delete the invariant."

    # THE EXPECTED COUNTS COME FROM THE BASE RENDER, NOT FROM A LITERAL (plan 29-06).
    #
    # They were the literal `3` — the three app Deployments, their three HPAs and
    # their three PDBs. That number went stale the moment k8s/base gained the
    # monitoring workloads (DPLY-03): the local render legitimately carries six
    # Deployments, all six correctly at `replicas: 1`, and LOC-2 reported
    # "expected 3 … found 6 object(s), 6 of them at 1" — an expected-N that is wrong
    # on a CORRECT tree.
    #
    # Deriving the expectation from base is the same discipline the maxReplicas arm
    # a few lines below already uses, and which this invariant's header calls for by
    # name ("Compared AGAINST BASE rather than against hardcoded numbers, so a
    # legitimate future base change carries through instead of going stale").
    #
    # IT IS STRICTLY STRONGER THAN THE LITERAL, not a relaxation. The weak form
    # `ones == total` would pass on a local render that had LOST a Deployment
    # entirely; comparing the count against base catches that, catches a Deployment
    # local has and base does not, AND still catches the original defect (a workload
    # left unscaled). A workload with no HPA or no PDB — every monitoring singleton
    # is deliberately both, see k8s/base/monitoring/prometheus-deployment.yaml —
    # simply contributes nothing to those two counts on either side.
    base_deps=$(awk -F'\t' '$1=="Deployment" && $3=="replicas"'                 "$TMP/base_scale.tsv" | wc -l)
    base_hpas=$(awk -F'\t' '$1=="HorizontalPodAutoscaler" && $3=="minReplicas"' "$TMP/base_scale.tsv" | wc -l)
    base_pdbs=$(awk -F'\t' '$1=="PodDisruptionBudget" && $3=="minAvailable"'    "$TMP/base_scale.tsv" | wc -l)
    (( base_deps > 0 && base_hpas > 0 && base_pdbs > 0 )) || parse_fail "LOC-2 found Deployment=$base_deps, HPA=$base_hpas, PDB=$base_pdbs in the k8s/base render, so the local-vs-base count comparison has no reference and would pass vacuously. Fix the parser, do not delete the invariant."

    loc2_bad=0
    for spec in "Deployment replicas $base_deps $dep_replicas $dep_ones" \
                "HorizontalPodAutoscaler minReplicas $base_hpas $hpa_mins $hpa_ones" \
                "PodDisruptionBudget minAvailable $base_pdbs $pdb_mins $pdb_ones"; do
        read -r kind field want total ones <<< "$spec"
        if (( total != want || ones != want )); then
            echo "  FAIL [k8s/local] LOC-2: expected $want $kind object(s) with '$field: 1' (the count k8s/base renders); found $total object(s), $ones of them at 1." >&2
            awk -F'\t' -v k="$kind" -v f="$field" '$1==k && $3==f { print "        " $1 "/" $2 ": " $3 ": " $4 }' "$TMP/loc_scale.tsv" >&2
            loc2_bad=1
        fi
    done

    loc_max=$(awk -F'\t' '$1=="HorizontalPodAutoscaler" && $3=="maxReplicas" { print $4 }' "$TMP/loc_scale.tsv" | sort -n | tr '\n' ' ')
    base_max=$(awk -F'\t' '$1=="HorizontalPodAutoscaler" && $3=="maxReplicas" { print $4 }' "$TMP/base_scale.tsv" | sort -n | tr '\n' ' ')
    [[ -n "${base_max// /}" ]] || parse_fail "LOC-2 found no HPA maxReplicas values in the k8s/base render, so the local-vs-base comparison has no reference and would pass vacuously. Fix the parser, do not delete the invariant."
    if [[ "$loc_max" != "$base_max" ]]; then
        echo "  FAIL [k8s/local] LOC-2: the local HPA maxReplicas multiset [$loc_max] DIVERGES from the k8s/base multiset [$base_max]." >&2
        echo "        maxReplicas is an INPUT to k8s/scripts/check-connection-math.sh: it asserts" >&2
        echo "        maxReplicas x DB_POOL_SIZE (plus Keycloak, the exporter, healthchecks and" >&2
        echo "        pg-backup) fits Postgres max_connections with >= 20% headroom. Changing it in" >&2
        echo "        the local overlay makes the local render stop proving the same arithmetic the" >&2
        echo "        gate checks, and it buys nothing: an HPA with no metrics-server never scales" >&2
        echo "        up regardless of its ceiling. Scale local with 'replicas:' + minReplicas/" >&2
        echo "        minAvailable (D-09), never by lowering the ceiling." >&2
        loc2_bad=1
    fi
    if (( loc2_bad != 0 )); then
        FAILED=1
        loc2_msg="FAIL"
    else
        # The counts are PRINTED rather than described as "x3": the literal in this
        # message would have kept saying 3 while the render carried 6, and a summary
        # line that disagrees with what was measured is how a reviewer stops reading
        # them (issue #385, the pin-not-at-site label, is the same defect one file over).
        loc2_msg="OK (replicas x$dep_ones/$base_deps, minReplicas x$hpa_ones/$base_hpas, minAvailable x$pdb_ones/$base_pdbs all = 1; maxReplicas [$loc_max] == base)"
    fi

    # ---------------- LOC-3 ----------------
    LOCAL_BACKUP_ENDPOINT="http://$LOCAL_HOST_SHIM:9000"
    backup_val="$(cfg_value 's3.backup.endpoint')"
    if [[ "$backup_val" != "$LOCAL_BACKUP_ENDPOINT" ]]; then
        echo "  FAIL [k8s/local] LOC-3: app-config 's3.backup.endpoint' is '$backup_val', expected exactly '$LOCAL_BACKUP_ENDPOINT'." >&2
        echo "        The base value is the EMPTY string, which the backup script reads as \"real AWS" >&2
        echo "        S3\". Locally that aims a database dump at real AWS with no credentials, and it" >&2
        echo "        makes the restore rehearsal (issue #101) impossible to run at all." >&2
        FAILED=1
        loc3_msg="FAIL"
    else
        loc3_msg="OK ($backup_val)"
    fi

    # ---------------- LOC-4 ----------------
    awk 'BEGIN{RS="\n---"} /kind: Ingress/{print}' "$LOCAL_RENDER" > "$TMP/loc_ingress.yaml"
    loc_ing_docs=$(grep -c '^kind: Ingress$' "$LOCAL_RENDER" || true)
    (( loc_ing_docs > 0 )) || parse_fail "LOC-4/LOC-5 found 0 Ingress documents in the k8s/local render. The local overlay must render both jtoye-ingress and jtoye-sse-ingress; zero means the parser is blind and every 'must not contain' assertion below would pass vacuously. Fix the parser, do not delete the invariant."

    loc4_bad=0
    for pat in 'configuration-snippet' 'cert-manager.io/cluster-issuer' \
               'nginx.ingress.kubernetes.io/limit-rps' \
               'nginx.ingress.kubernetes.io/limit-connections' \
               'nginx.ingress.kubernetes.io/limit-burst-multiplier'; do
        if grep -n "$pat" "$TMP/loc_ingress.yaml" > "$TMP/hits" 2> /dev/null; then
            echo "  FAIL [k8s/local] LOC-4: '$pat' is present in a local Ingress:" >&2
            sed 's/^/        /' "$TMP/hits" >&2
            loc4_bad=1
        fi
    done
    if grep -n '^  tls:' "$TMP/loc_ingress.yaml" > "$TMP/hits" 2> /dev/null; then
        echo "  FAIL [k8s/local] LOC-4: a local Ingress still carries a 'tls:' block:" >&2
        sed 's/^/        /' "$TMP/hits" >&2
        loc4_bad=1
    fi
    if (( loc4_bad != 0 )); then
        echo "        PIT-1: minikube v1.36.0 bundles ingress-nginx controller v1.12.2, where" >&2
        echo "        allow-snippet-annotations defaults to FALSE and annotations-risk-level to" >&2
        echo "        High. Its validating admission webhook REJECTS a snippet annotation" >&2
        echo "        outright, so 'kubectl apply -k k8s/local' fails for BOTH Ingress objects and" >&2
        echo "        nothing deploys cleanly around it. The three rate-limit annotations are" >&2
        echo "        PIT-10: a Playwright run from one source IP can trip the per-IP connection" >&2
        echo "        cap and produce 503s that look like application faults. tls: must be absent" >&2
        echo "        because no cert-manager runs locally, so 'secretName: jtoye-tls' would never" >&2
        echo "        exist and nginx would serve its self-signed fallback." >&2
        echo "        FIX IT IN THE LOCAL OVERLAY, NOT ON THE CLUSTER. The base annotation is" >&2
        echo "        DELIBERATELY PRESERVED for staging/production (it sets six security headers)." >&2
        echo "        Setting allow-snippet-annotations: \"true\" / annotations-risk-level:" >&2
        echo "        \"Critical\" on the addon would make the apply succeed by re-enabling a" >&2
        echo "        documented Critical-risk annotation class that ingress-nginx disables by" >&2
        echo "        default — weakening the cluster to satisfy a local convenience." >&2
        FAILED=1
        loc4_msg="FAIL"
    else
        loc4_msg="OK ($loc_ing_docs Ingress doc(s): no snippet, no cert-manager, no rate limits, no tls)"
    fi

    # ---------------- LOC-5 ----------------
    LOCAL_EXPECTED_HOSTS="api.jtoye.local app.jtoye.local"
    loc_hosts=$(grep -E '^[[:space:]]*- host: ' "$TMP/loc_ingress.yaml" | sed 's/^[[:space:]]*- host:[[:space:]]*//' | sort -u | tr '\n' ' ')
    loc_hosts="${loc_hosts% }"
    loc5_bad=0
    if [[ "$loc_hosts" != "$LOCAL_EXPECTED_HOSTS" ]]; then
        echo "  FAIL [k8s/local] LOC-5: local Ingress hosts are [$loc_hosts], expected exactly [$LOCAL_EXPECTED_HOSTS] (D-12)." >&2
        loc5_bad=1
    fi
    # A production hostname surviving into the local render. Occurrences of the
    # domain as an ANNOTATION KEY NAMESPACE (`jtoye.co.uk/<name>:`) are excluded:
    # those are k8s annotation keys on a NetworkPolicy, not endpoints, and driving
    # them to zero would mean renaming an annotation for no benefit. Anything else
    # — a host, a TLS SAN, a CORS origin, a config value — is a real leak.
    if grep -E 'jtoye\.co\.uk' "$LOCAL_RENDER" | grep -vE '^[[:space:]]+jtoye\.co\.uk/' > "$TMP/hits" 2> /dev/null; then
        echo "  FAIL [k8s/local] LOC-5: a production hostname survives into the local render:" >&2
        sed 's/^/        /' "$TMP/hits" >&2
        loc5_bad=1
    fi
    if grep -qE '^[[:space:]]+name: keycloak$' "$TMP/loc_ingress.yaml"; then
        echo "  FAIL [k8s/local] LOC-5: a local Ingress routes to a Service named 'keycloak', which exists in no render." >&2
        loc5_bad=1
    fi
    if (( loc5_bad != 0 )); then
        echo "        Local is reached through the minikube ingress addon plus /etc/hosts entries" >&2
        echo "        for those two names. A production hostname in the local render either routes" >&2
        echo "        local traffic at production or (more usually) at nothing, and it means the" >&2
        echo "        local run is not exercising the ingress path it claims to. There is no" >&2
        echo "        keycloak host locally on purpose: Keycloak is a compose service the browser" >&2
        echo "        reaches directly, not an in-cluster workload." >&2
        FAILED=1
        loc5_msg="FAIL"
    else
        loc5_msg="OK (hosts: $loc_hosts)"
    fi

    # ---------------- LOC-6 ----------------
    # D-01 at the SOURCE level. check-no-plaintext-secrets.sh already fails on any
    # `kind: Secret` or placeholder in the BUILD OUTPUT; this asserts the input, so
    # the constraint is visible in the directory where the mistake would be made.
    loc6_bad=0
    if grep -rn 'secretGenerator' "$LOCAL_DIR" > "$TMP/hits" 2> /dev/null; then
        echo "  FAIL [k8s/local] LOC-6: kustomize secret generation is used under k8s/local:" >&2
        sed 's/^/        /' "$TMP/hits" >&2
        echo "        D-01 forbids it: it emits a 'kind: Secret' into the build output, and" >&2
        echo "        check-no-plaintext-secrets.sh auto-discovers k8s/local at 'find -maxdepth 2'" >&2
        echo "        and fails on exactly that. Local Secrets come OUT-OF-BAND from" >&2
        echo "        scripts/k8s-local-secrets.sh, which sources the gitignored .env." >&2
        loc6_bad=1
    fi
    if grep -rn 'REPLACE_WITH' "$LOCAL_DIR" > "$TMP/hits" 2> /dev/null; then
        echo "  FAIL [k8s/local] LOC-6: an unsubstituted placeholder literal is present under k8s/local:" >&2
        sed 's/^/        /' "$TMP/hits" >&2
        echo "        Local has no CI substitution step, so a placeholder here reaches the render" >&2
        echo "        verbatim and fails check-no-plaintext-secrets.sh (which exempts only the one" >&2
        echo "        deploy-timestamp annotation staging/production stamp)." >&2
        loc6_bad=1
    fi
    if (( loc6_bad != 0 )); then
        FAILED=1
        loc6_msg="FAIL"
    else
        loc6_msg="OK (no kustomize secret generation, no placeholder literal)"
    fi

    if [[ "$loc1_msg" == FAIL* || "$loc2_msg" == FAIL* || "$loc3_msg" == FAIL* \
          || "$loc4_msg" == FAIL* || "$loc5_msg" == FAIL* || "$loc6_msg" == FAIL* ]]; then
        echo "FAIL [k8s/local]: LOC-1 $loc1_msg | LOC-2 $loc2_msg | LOC-3 $loc3_msg | LOC-4 $loc4_msg | LOC-5 $loc5_msg | LOC-6 $loc6_msg" >&2
    else
        echo "  OK   [k8s/local] LOC-1 $loc1_msg"
        echo "  OK   [k8s/local] LOC-2 $loc2_msg"
        echo "  OK   [k8s/local] LOC-3 $loc3_msg"
        echo "  OK   [k8s/local] LOC-4 $loc4_msg"
        echo "  OK   [k8s/local] LOC-5 $loc5_msg"
        echo "  OK   [k8s/local] LOC-6 $loc6_msg"
    fi
    LOCAL_SECTION="LOC-1..LOC-6 checked on k8s/local"
    echo
fi

# ===========================================================================
if (( FAILED != 0 )); then
    fail "one or more rendered-manifest invariants are broken — see above. Each invariant pins a defect that already shipped once; fix the manifest or the docs rather than relaxing the assertion."
fi

echo "PASS: INV-1..INV-8 hold across ${#TARGETS[@]} kustomize target(s); $LOCAL_SECTION."
