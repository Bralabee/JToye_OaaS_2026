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
#          staging or production render must contain no `localhost`, `127.0.0.1`,
#          `UseDevelopmentStorage`, `devstoreaccount1` or `AccountKey=` literal.
#          Thirteen placeholders used to resolve to local-only defaults; plan
#          26-02 supplied them. This is the non-regression half —
#          check-env-contract.sh guards the config side, this guards the rendered
#          side. The three storage literals replaced the retired object store's
#          default credential in Phase 36 (T-36-34): an Azurite emulator string, the
#          emulator account name or ANY account key must never render outside local.
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
#   INV-7  ISSUE #271 + PHASE 29 (Blockers D/F, D-29), RENDER level, EVERY target.
#          The rendered NetworkPolicy set must equal a DECLARED allow-list,
#          exactly: per policy, per target, the full multiset of rule tuples.
#
#          HISTORY (#271). Phase 26 made DB_PORT Secret-driven (DEF-1/INV-1
#          above) so a non-default Postgres port needs no manifest edit. The
#          egress rules in networkpolicies/20-core-java.yaml and 40-datastores.yaml
#          kept their own `port: 5432`. So the first environment to USE that
#          flexibility — the local overlay genuinely runs 5433 — gets a
#          NetworkPolicy denial under an enforcing CNI: every core-java replica
#          CrashLooping and the nightly pg-backup dump failing, with logs pointing
#          at the application layer rather than the network. Fixing one half of a
#          coupling is what created the trap. INV-7 was born asserting the
#          egress PORT multiset toward one infrastructure namespace with
#          app-config `db.port` substituted, and that substitution is still here.
#
#          WHY IT WAS REWRITTEN (Phase 29, plan 29-02). Asserting one
#          namespace's egress ports left everything else unasserted, and that is
#          where the real defects were: core-java admitted no traffic from the
#          ingress controller and nothing on the 9091 scrape port (Blocker F),
#          the Postgres peer was a namespace that exists nowhere while the
#          managed server is a public IP (Blocker D), and the controller
#          namespace was a literal in four files (D-29). None of those could make
#          the old INV-7 fail. Measured on main before the rewrite: an undeclared
#          policy, a CiliumNetworkPolicy and a narrowed ipBlock exception list
#          each exited 0, and the 9091 rule did not exist to be dropped.
#
#          THE CONTRACT NOW.
#            1. Every rendered NetworkPolicy matches exactly ONE row of
#               NETPOL_RULES_EXPECTED (`<policy>@<target-glob>`); a policy with
#               no row FAILS ("undeclared policy"), and a row whose policy is
#               absent from a target it applies to FAILS.
#            2. The rendered tuple multiset EQUALS the row's, with __DB_PORT__ =
#               the target's app-config `db.port` and __INGRESS_NS__ = its
#               app-config `ingress.controller-namespace` (both read from the
#               render; absent = PARSE ERROR). Extra and missing tuples are each
#               named. Tuples: `sel:<selector>` (which pods the policy selects),
#               `types:<policyTypes>`, and `in|eg:<peer>:<port>` per (peer, port)
#               pair of every rule, where peer is `ns=<name>`, `pod=<k>=<v>`,
#               `nspod=<ns>/<k>=<v>`, `ip=<cidr>` or `*` (a rule with no
#               from/to), and port is the number for TCP, `<n>/<PROTO>` otherwise,
#               or `*` (a rule with no ports).
#            3. Every ipBlock peer's (direction, cidr, except-list) shape is in
#               NETPOL_IPBLOCK_EXPECTED, on a declared port only; a declared shape
#               no target renders FAILS as stale.
#            4. Every document whose kind ends in NetworkPolicy is kind
#               NetworkPolicy at apiVersion networking.k8s.io/v1. The engine is
#               Cilium (plan 29-01), and its own CRDs (CiliumNetworkPolicy and
#               friends) would bypass every assertion above.
#
#          ASSERTION SHAPE IS LOAD-BEARING.
#            a. Tuples are read through RENDER_PATHS_AWK (full key paths), never a
#               forward scan: `kubectl kustomize` sorts map keys, so inside a rule
#               `ports:` is emitted BEFORE the `to:` that says where it applies.
#            b. A rendered port must be BARE DIGITS. In NetworkPolicy semantics a
#               STRING port is a NAMED port: `port: "5433"` renders, applies, and
#               matches no traffic. The db.port replacement source is a ConfigMap
#               string, so this is a live failure mode of the mechanism.
#            c. Zero NetworkPolicies extracted from a target is a PARSE ERROR
#               (blind), never a pass.
#          PROVEN FALSIFIABLE by `--selftest-inv7`: five planted holes (an extra
#          egress port, the dropped 9091 rule, an undeclared policy, an
#          undeclared ipBlock shape, a CiliumNetworkPolicy), each rc 1 with the
#          message naming it, and the clean copy rc 0.
#
#          WHAT THIS DOES NOT PROVE. app-config `db.port` is the RENDER-TIME
#          declaration; the value the pods actually dial is the
#          `postgres-credentials` Secret `port` key, which by design never appears
#          in a render (check-no-plaintext-secrets.sh guarantees it). Their
#          agreement is asserted where both are observable —
#          scripts/k8s-local-secrets.sh refuses to create the local Secret on a
#          mismatch — and is an operator step for staging/production, in the same
#          shape as the RABBITMQ_USER pre-rollout check in
#          k8s/base/core-java-deployment.yaml. Nor does a render prove
#          ENFORCEMENT: that is plan 29-17's captured denial on the live cluster.
#
#   INV-8  PHASE 36 D-01/D-06/D-07/D-11, RENDER level, staging + production only
#          (WI_TARGETS). The storage model is ENFORCED, not described: both
#          storage.blob.auth-mode and backup.blob.auth-mode are workload-identity;
#          storage.blob.endpoint and backup.blob.endpoint each match
#          ^https://[a-z0-9]{3,24}\.blob\.core\.windows\.net$ (a raw account
#          endpoint, no port, no path — the shape core-java's validator and
#          blobctl both demand); public-url is EXACTLY the media endpoint +
#          /jtoye-images (D-06: the origin every persisted image URL carries);
#          the containers are jtoye-images / jtoye-quarantine (D-07);
#          create-containers is "false" (Set Container ACL cannot be authorized
#          with Entra ID); and the media endpoint DIFFERS from the backup endpoint
#          (D-01: a separate backup account, so a compromised media identity
#          cannot reach the dumps).
#
#   INV-9  PHASE 36 D-02 / T-36-32, RENDER level, staging + production only. No
#          stored storage credential of any kind: zero secretKeyRef, envFrom
#          secretRef or volume secretName whose Secret name matches
#          s3|storage|blob|azure (case-insensitive); zero env named
#          STORAGE_CONNECTION_STRING, AZURE_CLIENT_SECRET, or starting AWS_ or
#          AZURE_STORAGE_; zero app-config key containing `connection-string`.
#          Workload Identity means the credential is a projected, short-lived
#          token the webhook mounts — never something a manifest names.
#
#   INV-10 PHASE 36 D-02 / T-36-33 / T-36-36, RENDER level, staging + production
#          only. The pod TEMPLATES of Deployment core-java and CronJob pg-backup
#          (spec.jobTemplate.spec.template) carry the label
#          azure.workload.identity/use: "true" and serviceAccountName core-java /
#          pg-backup; ServiceAccounts core-java and pg-backup exist with
#          automountServiceAccountToken false; and NO other pod template opts in
#          (least privilege — only the two Blob workloads get an Entra token).
#          Without the label the webhook injects nothing and core-java refuses to
#          boot; on the controller's own metadata instead of the pod template it
#          does nothing at all, which is why the PATH is asserted, not the token.
#
#          ASSERTION SHAPE FOR INV-8..INV-10 + LOC-7. Each document is walked into
#          full key PATHS by indentation (RENDER_PATHS_AWK), list items indexed,
#          and values read by exact path. kubectl kustomize sorts map keys, so in
#          a container `env:` is emitted BEFORE its own `name:` — a forward scan
#          from the container name sees none of its env. Paths are immune to that
#          ordering by construction.
#
#   INV-11 PHASE 36 D-08 (code review WR-01), RENDER level, EVERY target. No
#          render can switch off core-java's boot-time storage probe
#          (StorageStartupValidator, the runtime check behind #626). The probe is
#          gated on storage.blob.validate-on-startup, and application.yml sets it
#          as a literal `true` — but a literal in application.yml is not a guard:
#          Spring's relaxed binding lets an env var (STORAGE_BLOB_VALIDATEONSTARTUP,
#          STORAGE_BLOB_VALIDATE_ON_STARTUP, or the dotted name itself, which a
#          Kubernetes env name may carry), SPRING_APPLICATION_JSON, a command-line
#          argument or a -D system property (JAVA_OPTS, which core-java's image
#          ENTRYPOINT expands) outrank it and silently remove the bean. This makes
#          "no runtime switches it off" true for every rendered target:
#            a. the render text contains NO spelling of validate-on-startup at all
#               (case-insensitive, `-`/`_`/`.`/nothing between the words). One
#               raw-text rule covers every visible channel at once: env names and
#               values, args, command, ConfigMap keys and values reached through
#               configMapKeyRef, JSON bodies and block scalars the path walker skips;
#            b. no core-java container sets SPRING_APPLICATION_JSON (any spelling),
#               and none sources JAVA_OPTS / JAVA_TOOL_OPTIONS / JDK_JAVA_OPTIONS
#               from a Secret — a Secret's value never appears in the render, so
#               rule (a) could not see a flag arriving that way;
#            c. no core-java container uses envFrom, which imports env names the
#               render cannot enumerate when the source is a Secret.
#          "core-java container" = any container, in any workload kind, whose image
#          is the core-java image, found by exact PATH (::containers::[i]::image),
#          so a second workload running the image is covered too. Zero such
#          containers in a render is a PARSE ERROR (blind), never a pass.
#          Test contexts keep their opt-out: they read core-java/src/test/resources,
#          which no render contains.
#
#   INV-12 PHASE 37 D-19 / RWO-006 (plan 37-24, UXT-006), RENDER level, EVERY
#          target. app-config security.trusted-proxies — the CIDR list whose
#          X-Forwarded-For core-java believes — must be PRESENT in every render
#          (absent is a PARSE ERROR: k8s/base declares "" so the posture is always
#          stated), and every entry in it must be a CIDR that is
#            a. not /0 (0.0.0.0/0 or ::/0 trusts every client);
#            b. no shorter than /8;
#            c. inside a PRIVATE range: RFC 1918 (10/8, 172.16/12, 192.168/16) or
#               IPv6 unique-local fc00::/7 — the ingress controller's pod CIDR is
#               always one of these; a public range means some internet address
#               is believed when it writes the header.
#          An EMPTY value is allowed (trust none): on staging and production it
#          WARNS — every client behind the ingress then shares one public bucket
#          until the operator declares the ingress-nginx pod CIDR (an input not
#          in this repo, 37-24 user_setup) — and never fails, because a warning
#          nobody can act on from the repo is not a gate. The persona run
#          (UXT-006) showed that a resolver which believed the header from anyone
#          let a rotated header mint a fresh public bucket per request; a trusted
#          range that admits the internet re-opens exactly that, so this is
#          refused at render time, and core-java refuses /0 again at startup.
#          The judge has a SELF-TEST (inv12_selftest): dangerous shapes must be
#          refused and legitimate private ranges accepted before any render is
#          judged, so a blind judge is a PARSE ERROR, never a pass.
#
#   INV-14 PHASE 29 plan 29-08, Blocker A / T-29-28, RENDER level, staging +
#          production (WI_TARGETS). app-config keycloak.client-id — the frontend's
#          KEYCLOAK_CLIENT_ID — must be a clientId in
#          infra/keycloak/realm-export.template.json (the one template every
#          deployed realm is derived from) that a confidential browser login can
#          use: not a Keycloak built-in (account, admin-cli, …), enabled,
#          standardFlowEnabled, not bearerOnly, not publicClient. The base value was
#          `frontend` from plan 26-08 until 29-08: kept byte-identical to a
#          hardcoded literal, it named a client NO realm has, so every deployed
#          vendor login could only have ended in invalid_client while every gate
#          stayed green. jq missing, the template missing or unparseable, or zero
#          clients read is a PARSE ERROR. The live invalid_client arm is plan 29-14.
#
# THE LOCAL-OVERLAY INVARIANTS (LOC-*), Phase 26 / INFRA-01
#   These run ONLY when k8s/local/kustomization.yaml exists, so the script stays
#   valid if the overlay is ever removed. They assert the shape of the committed
#   local overlay that replaced the imperative in-cluster patches used during the
#   2026-07-14 live-deploy rehearsal.
#
#   LOC-1  Endpoint shims. Each of redis.host, rabbitmq.host,
#          stomp.broker.relay-host, storage.blob.connection-string,
#          backup.blob.connection-string, smtp.host, keycloak.issuer.uri and
#          keycloak.admin.base-url must resolve to a host.minikube.internal value
#          (the two connection strings carry it as DevelopmentStorageProxyUri). Asserted PER KEY BY NAME, not by a total
#          count: a count alone lets a LOST shim hide behind an ADDED one, which
#          is not hypothetical — it was demonstrated (redis.host -> localhost plus
#          one extra shimmed value keeps the total at 8 and a count-only
#          assertion passes).
#
#   LOC-2  The D-09 scale triple. Exactly 3 Deployments at `replicas: 1`, 3 HPAs
#          at `minReplicas: 1`, 3 PDBs at `minAvailable: 1` — an HPA floor of 3
#          would scale a 1-replica Deployment straight back up, and a PDB
#          minAvailable of 2 over one replica makes the pod undrainable. AND the
#          local HPA maxReplicas multiset must equal the one k8s/base renders:
#          maxReplicas is an input to check-connection-math.sh's Postgres
#          connection budget, so lowering it locally would silently stop the
#          local render proving the same arithmetic. Compared AGAINST BASE rather
#          than against hardcoded numbers, so a legitimate future base change
#          carries through instead of going stale.
#
#   LOC-3  The backup repoint (INFRA-01 / INFRA-02c, Phase 36). backup.blob.auth-mode
#          is exactly connection-string and backup.blob.connection-string is
#          exactly UseDevelopmentStorage=true;DevelopmentStorageProxyUri=
#          http://host.minikube.internal — the host Azurite. Base names the
#          PRODUCTION backup account under workload identity; locally that aims a
#          database dump at a real Azure account with no identity, and makes the
#          #101 restore rehearsal impossible to run.
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
#   LOC-7  The local emulator storage path is WIRED (Phase 36). storage.blob.auth-mode
#          is connection-string, storage.blob.create-containers is "true" (Azurite
#          starts empty), and BOTH the core-java and the pg-backup container carry
#          a STORAGE_CONNECTION_STRING env from app-config (keys
#          storage.blob.connection-string / backup.blob.connection-string).
#          Without it core-java dials its bare UseDevelopmentStorage=true default —
#          127.0.0.1:10000, the POD's own loopback — and every upload fails, and
#          blobctl refuses to start. Those two keys exist only in k8s/local, so an
#          unwired key would otherwise be a silently ignored value.
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
# Usage: ./k8s/scripts/check-render-invariants.sh [--selftest-inv7]
#   (run from anywhere; paths resolve relative to the repo root)
#   --selftest-inv7  plant five holes in a copy of k8s/ and require INV-7 to
#                    catch each (see inv7_selftest below)

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

# ===========================================================================
# --selftest-inv7 (Phase 29, plan 29-02): INV-7 shown CATCHING planted holes.
#
# A gate observed only passing may be incapable of failing. This arm copies the
# whole k8s/ tree to a temp dir, plants ONE hole per arm in the copy, runs the
# COPY's own gate (REPO_ROOT resolves from the script's location, so it renders
# the planted tree, never this one) and requires rc 1 plus the INV-7 message
# that names the hole. The unmodified copy must exit 0. Arms:
#   extra-port         core-java-allow's Redis egress rule gains TCP 9000
#   dropped-9091       core-java-allow loses its Prometheus 9091 ingress rule
#   undeclared-policy  a NetworkPolicy with no NETPOL_RULES_EXPECTED row
#   undeclared-ipblock core-java-allow's public-443 ipBlock loses one RFC1918
#                      exception (a shape NETPOL_IPBLOCK_EXPECTED does not declare)
#   foreign-kind       a CiliumNetworkPolicy in the render
# A plant that does not change its file is reported caught=no: an arm that
# planted nothing would otherwise "pass" on the clean tree.
# Output: one `ARM <name> caught=yes|no` line per arm, then `CLEAN rc=<n>`;
# exit 0 only when all five are caught and CLEAN rc=0. INV7_SELFTEST_FORMAT=tap
# renders TAP 13 instead (for the RED-evidence classifier).
# ===========================================================================
# selftest_stage <pristine k8s copy> <arm root> — lay out a throwaway repo root
# the copy's own gate can run against: the k8s/ tree plus every file OUTSIDE k8s/
# the gate reads (today only INV-14's realm template). The copy's REPO_ROOT is
# <arm root>, so anything missing here would VOID every arm instead of judging it.
selftest_stage() {
    rm -rf "$2"; mkdir -p "$2/infra/keycloak"
    cp -a "$1" "$2/k8s"
    cp "$REPO_ROOT/infra/keycloak/realm-export.template.json" "$2/infra/keycloak/" \
        || { echo "PARSE ERROR: selftest could not copy the realm template" >&2; exit 2; }
}

inv7_plant() {
    local arm="$1" k="$2" f before after
    case "$arm" in
        extra-port)
            f="$k/base/networkpolicies/20-core-java.yaml"
            before=$(cksum < "$f")
            awk '{ print } /^          port: 6379$/ && !done { print "        - protocol: TCP"; print "          port: 9000"; done = 1 }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        dropped-9091)
            f="$k/base/networkpolicies/20-core-java.yaml"
            before=$(cksum < "$f")
            awk '/# ingress.2 — Prometheus scrape/ { skip = 1 } skip && /port: 9091$/ { skip = 0; next } !skip { print }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        undeclared-policy)
            f="$k/base/kustomization.yaml"
            before=$(cksum < "$f")
            cat > "$k/base/networkpolicies/99-planted.yaml" <<'YAML'
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: planted-allow
spec:
  podSelector:
    matchLabels:
      app: core-java
  policyTypes:
    - Ingress
  ingress:
    - from:
        - podSelector:
            matchLabels:
              app: planted
      ports:
        - protocol: TCP
          port: 9090
YAML
            awk '{ print } /^  - networkpolicies\/60-acme-http01-solver.yaml$/ { print "  - networkpolicies/99-planted.yaml" }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        undeclared-ipblock)
            f="$k/base/networkpolicies/20-core-java.yaml"
            before=$(cksum < "$f")
            # Drop the LAST 192.168.0.0/16 exception in the file: the public-443
            # rule's. Its (peer, port) tuple is unchanged, so only the ipBlock
            # shape assertion can see this hole.
            last=$(awk '/^              - 192\.168\.0\.0\/16$/ { l = NR } END { print l + 0 }' "$f")
            if (( last > 0 )); then awk -v l="$last" 'NR != l' "$f" > "$f.new" && mv "$f.new" "$f"; fi ;;
        foreign-kind)
            f="$k/base/kustomization.yaml"
            before=$(cksum < "$f")
            cat > "$k/base/networkpolicies/98-planted-cilium.yaml" <<'YAML'
apiVersion: cilium.io/v2
kind: CiliumNetworkPolicy
metadata:
  name: planted-cilium
spec:
  endpointSelector: {}
  ingress:
    - fromEntities:
        - world
YAML
            awk '{ print } /^  - networkpolicies\/60-acme-http01-solver.yaml$/ { print "  - networkpolicies/98-planted-cilium.yaml" }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        *) return 1 ;;
    esac
    after=$(cksum < "$f")
    [[ "$before" != "$after" ]]
}

inv7_selftest() {
    local fmt="${INV7_SELFTEST_FORMAT:-plain}" st arm want out rc caught n=0 bad=0 tap=() line
    st="$(mktemp -d)"
    # shellcheck disable=SC2064
    trap "rm -rf '$st'" EXIT
    cp -a "$K8S_DIR" "$st/pristine" || { echo "PARSE ERROR: --selftest-inv7 could not copy $K8S_DIR" >&2; exit 2; }
    local -a ARMS=(extra-port dropped-9091 undeclared-policy undeclared-ipblock foreign-kind)
    declare -A WANT=(
        [extra-port]="INV-7: NetworkPolicy 'core-java-allow' renders undeclared rule(s): eg:pod=app=redis:9000"
        [dropped-9091]="INV-7: NetworkPolicy 'core-java-allow' is missing declared rule(s): in:pod=app=prometheus:9091"
        [undeclared-policy]="INV-7: undeclared policy 'planted-allow'"
        [undeclared-ipblock]="INV-7: NetworkPolicy 'core-java-allow' has an undeclared ipBlock"
        [foreign-kind]="INV-7: foreign NetworkPolicy kind 'CiliumNetworkPolicy'"
    )
    for arm in "${ARMS[@]}"; do
        n=$((n + 1))
        selftest_stage "$st/pristine" "$st/arm"
        if ! inv7_plant "$arm" "$st/arm/k8s"; then
            caught=no; line="plant did not apply"
        else
            # `if` form: under set -e a failing $(...) assignment would end the
            # selftest itself, and rc must be the gate's, captured on the spot.
            if out=$(bash "$st/arm/k8s/scripts/check-render-invariants.sh" 2>&1); then rc=0; else rc=$?; fi
            want="${WANT[$arm]}"
            if (( rc == 1 )) && grep -qF -- "$want" <<<"$out"; then
                caught=yes; line="rc=1"
            else
                caught=no
                line="expected rc 1 and '$want'; got rc=$rc: $(awk '/INV-7|PARSE ERROR/ && c < 2 { printf "%s ", $0; c++ }' <<<"$out")"
            fi
        fi
        [[ "$caught" == yes ]] || bad=$((bad + 1))
        if [[ "$fmt" == tap ]]; then
            if [[ "$caught" == yes ]]; then tap+=("ok $n - $arm"); else
                tap+=("not ok $n - $arm" "  ---" "  message: '${line//\'/}'" "  ..."); fi
        else
            echo "ARM $arm caught=$caught"
            [[ "$caught" == yes ]] || echo "  ($line)" >&2
        fi
    done
    selftest_stage "$st/pristine" "$st/arm"
    if out=$(bash "$st/arm/k8s/scripts/check-render-invariants.sh" 2>&1); then rc=0; else rc=$?; fi
    n=$((n + 1))
    (( rc == 0 )) || bad=$((bad + 1))
    if [[ "$fmt" == tap ]]; then
        if (( rc == 0 )); then tap+=("ok $n - clean"); else
            tap+=("not ok $n - clean" "  ---" "  message: 'expected rc 0 on the unmodified copy, got rc=$rc'" "  ..."); fi
        printf '%s\n' "TAP version 13" "1..$n" "${tap[@]}"
    else
        echo "CLEAN rc=$rc"
        (( rc == 0 )) || awk '/FAIL|PARSE ERROR/ && c < 5 { print; c++ }' <<<"$out" >&2
    fi
    (( bad == 0 ))
}

if [[ "${1:-}" == "--selftest-inv7" ]]; then
    inv7_selftest; exit $?
fi

# ---------------------------------------------------------------------------
# INV-4 exclusion list.
#
# Matched on the EXACT repo-relative target path, never as a substring, so a
# future `k8s/local-staging` overlay is NOT silently excluded by `k8s/local`.
#
# k8s/local exists as of plan 26-04, and the exclusion is load-bearing rather
# than defensive: that overlay DELIBERATELY carries localhost-family literals.
# Two of its values must be BROWSER-reachable rather than pod-reachable —
# `storage.blob.public-url` (http://localhost:10000/devstoreaccount1/jtoye-images:
# the browser is what loads image URLs) and `keycloak.public.issuer.uri` (http://localhost:8085/...: the
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

# The live-verified Postgres SUPERUSER role name. `jtoye` is a superuser and
# `jtoye_app` is NOSUPERUSER (both confirmed against the running dev Postgres in
# 26-RESEARCH.md § Live Facts). Only the superuser is a defect in a recipe.
DB_SUPERUSER_ROLE="jtoye"

# Literals that must never appear in a non-local render.
FORBIDDEN_RENDER_LITERALS=(
  'localhost'
  '127\.0\.0\.1'
  # Phase 36 (T-36-34): an Azurite emulator string, the emulator account name or
  # ANY storage account key. These replaced the retired object store's default
  # credential; the emulator path is local-only and D-02 stores no key anywhere.
  'UseDevelopmentStorage'
  'devstoreaccount1'
  'AccountKey='
)

# ---------------------------------------------------------------------------
# INV-7 (issue #271, Phase 29 plan 29-02): the COMPLETE declared rule set of
# every NetworkPolicy, per target. See the INV-7 header above for the tuple
# grammar. Keyed `<policy metadata.name>@<target glob>`; the glob is matched
# against the repo-relative target path (`*` = every target), and every rendered
# policy must match EXACTLY one row. Order inside a value is irrelevant (both
# sides are sorted); multiplicity is not (a duplicated rule is an extra tuple).
#
# __DB_PORT__ / __INGRESS_NS__ are substituted with the target's rendered
# app-config db.port / ingress.controller-namespace — those substitutions ARE the
# #271 and D-29 invariants. Everything else is a literal, deliberately: an exact
# allow-list is strictly stronger than "the port is in there somewhere", which
# passes on a tree where a kustomize replacement got retargeted onto a
# neighbouring rule.
#
# Adding, removing or widening a rule MUST be accompanied by the same edit here
# and in k8s/base/networkpolicies/README.md's flow matrix. That is the intended
# friction: an allow-list is a security boundary, and a gate that silently
# accepted new holes in it would not be one. The pod labels named here are the
# contract in that README ("The pod-label contract").
# ---------------------------------------------------------------------------
NETPOL_DNS_EG="eg:nspod=kube-system/k8s-app=kube-dns:53/UDP eg:nspod=kube-system/k8s-app=kube-dns:53"
declare -A NETPOL_RULES_EXPECTED=(
  [default-deny@*]="sel:* types:Egress,Ingress"
  [frontend-allow@*]="sel:app=frontend types:Egress,Ingress
      in:ns=__INGRESS_NS__:3000
      $NETPOL_DNS_EG eg:pod=app=core-java:9090 eg:pod=app=keycloak:8080 eg:ip=0.0.0.0/0:443"
  [core-java-allow@*]="sel:app=core-java types:Egress,Ingress
      in:pod=app=frontend:9090 in:pod=app=edge-go:9090 in:ns=__INGRESS_NS__:9090 in:pod=app=prometheus:9091
      $NETPOL_DNS_EG eg:ip=0.0.0.0/0:__DB_PORT__ eg:pod=app=redis:6379
      eg:pod=app.kubernetes.io/name=rabbitmq:5672 eg:pod=app.kubernetes.io/name=rabbitmq:61613
      eg:pod=app=mailpit:1025 eg:pod=app=alertmanager:9093 eg:pod=app=keycloak:8080 eg:ip=0.0.0.0/0:443"
  [edge-go-allow@*]="sel:app=edge-go types:Egress,Ingress
      in:ns=__INGRESS_NS__:8080 in:pod=app=prometheus:8080
      $NETPOL_DNS_EG eg:pod=app=core-java:9090 eg:pod=app=keycloak:8080 eg:ip=0.0.0.0/0:443"
  [pg-backup-allow@*]="sel:app.kubernetes.io/name=pg-backup types:Egress,Ingress
      $NETPOL_DNS_EG eg:ip=0.0.0.0/0:__DB_PORT__ eg:ip=0.0.0.0/0:443"
  [acme-http01-solver-allow@*]="sel:acme.cert-manager.io/http01-solver=true types:Egress,Ingress
      in:ns=__INGRESS_NS__:8089"
  # Plan 29-23 replaces the placeholder with the monitoring policies.
  [observability-placeholder@*]="sel:app=nonexistent-placeholder types:Egress,Ingress"
)

# INV-7 arm 3: the ONLY ipBlock shapes a policy may render, as
# `<in|eg>|<cidr>|<except list, sorted, comma-joined>` -> the ports allowed on it.
# One shape today: the RFC1918-excluding public range, egress, on 443 (Keycloak's
# public host, Stripe, CDNs, Azure Blob, Entra ID) and on db.port (the managed
# Postgres public endpoint, Blocker D, accepted as T-29-05). A private endpoint
# (36-PHASE29-HANDOFF.md §5) would be a NEW, scoped row here, reviewed as a
# NetworkPolicy change. Bolting another port onto the public shape, narrowing its
# except list, or any ingress ipBlock FAILS.
declare -A NETPOL_IPBLOCK_EXPECTED=(
  ["eg|0.0.0.0/0|10.0.0.0/8,172.16.0.0/12,192.168.0.0/16"]="443 __DB_PORT__"
)
# Hygiene: rows and shapes used by at least one target (checked after the loop).
declare -A INV7_ROW_USED=()
declare -A INV7_SHAPE_USED=()

# ---------------------------------------------------------------------------
# INV-8 / INV-9 / INV-10 (Phase 36): the targets that ship to a real AKS cluster
# and must therefore run Azure Blob under Workload Identity with no stored
# credential. Matched on the EXACT repo-relative path, like LOCAL_ONLY_TARGETS.
#
# Every entry must be FOUND among the discovered targets (checked after the
# loop): if an overlay is renamed, these invariants must not quietly stop
# running against it — that would be the vacuous pass this gate exists to refuse.
# k8s/base is deliberately absent: it carries the production values but not the
# pod-template label, which each real overlay adds (and local must never carry).
# ---------------------------------------------------------------------------
WI_TARGETS=(
  "k8s/staging"
  "k8s/production"
)
WI_LABEL_KEY="azure.workload.identity/use"
# A raw account endpoint: https, a 3-24 char lower-case alphanumeric account
# name, the public-cloud Blob suffix, no port, no path, no trailing slash. The
# same shape StorageProperties.ACCOUNT_ENDPOINT and blobctl require.
BLOB_ACCOUNT_ENDPOINT_RE='^https://[a-z0-9]{3,24}\.blob\.core\.windows\.net$'
BLOB_PUBLIC_CONTAINER="jtoye-images"        # D-07
BLOB_QUARANTINE_CONTAINER="jtoye-quarantine" # D-07
# Secret names that would mean a STORED storage credential (INV-9).
STORAGE_SECRET_NAME_RE='s3|storage|blob|azure'
# Env names that would mean a stored credential or a connection string (INV-9).
STORAGE_CRED_ENV_RE='^(STORAGE_CONNECTION_STRING|AZURE_CLIENT_SECRET|AWS_.*|AZURE_STORAGE_.*)$'
# The workloads allowed to opt into Workload Identity, as "<kind>/<name>" with the
# pod-template path prefix and the ServiceAccount each must run as (INV-10).
declare -A WI_WORKLOAD_TEMPLATE=(
  [Deployment/core-java]="spec::template"
  [CronJob/pg-backup]="spec::jobTemplate::spec::template"
)
declare -A WI_WORKLOAD_SA=(
  [Deployment/core-java]="core-java"
  [CronJob/pg-backup]="pg-backup"
)

# INV-14 (Phase 29 plan 29-08, Blocker A): the realm every deployed realm is
# derived from (infra/keycloak/staging-realm.jq transforms this ONE template and
# keeps its clients). Read once with jq; jq missing, the file missing or
# unparseable, or zero clients extracted is a PARSE ERROR, never a pass.
REALM_TEMPLATE="$REPO_ROOT/infra/keycloak/realm-export.template.json"
# Keycloak's own per-realm clients. A vendor login naming one of these is a
# misconfiguration even though the template "contains" it.
KC_BUILTIN_CLIENTS=" account account-console admin-cli broker realm-management security-admin-console "
command -v jq >/dev/null 2>&1 || parse_fail "INV-14: jq is not installed; the realm template's client list cannot be read."
[[ -f "$REALM_TEMPLATE" ]] || parse_fail "INV-14: realm template not found: $REALM_TEMPLATE"
# One TSV row per client: clientId, enabled, standardFlowEnabled, bearerOnly, publicClient.
if ! REALM_CLIENTS_TSV=$(jq -r '.clients[] | [.clientId, (.enabled // false | tostring), (.standardFlowEnabled // false | tostring), (.bearerOnly // false | tostring), (.publicClient // false | tostring)] | @tsv' "$REALM_TEMPLATE" 2>&1); then
    parse_fail "INV-14: jq could not read the clients of $REALM_TEMPLATE: $REALM_CLIENTS_TSV"
fi
[[ -n "$REALM_CLIENTS_TSV" ]] || parse_fail "INV-14: jq extracted 0 clients from $REALM_TEMPLATE — every client id would be reported missing, or (worse) a blind reader could pass. Fix the reader, do not delete the invariant."
# The clients a vendor browser login may name: present in the template, not a
# Keycloak built-in, enabled, authorization-code flow on, not bearer-only, and
# confidential (the frontend presents keycloak-credentials/frontend-client-secret).
REALM_LOGIN_CLIENTS=" $(awk -F'\t' -v b="$KC_BUILTIN_CLIENTS" 'index(b, " " $1 " ") == 0 && $2 == "true" && $3 == "true" && $4 == "false" && $5 == "false" { printf "%s ", $1 }' <<< "$REALM_CLIENTS_TSV")"
[[ -n "${REALM_LOGIN_CLIENTS// /}" ]] || parse_fail "INV-14: the realm template has no client a vendor login could use (non-built-in, enabled, standard flow, confidential). Either the template lost core-api or the filter is blind."

# INV-11 (Phase 36 D-08, code review WR-01): the storage-probe switch in every
# spelling Spring's relaxed binding accepts, matched case-insensitively on the raw
# render text; the image that makes a container a core-java container; and the
# env names that could carry the switch from a source the render cannot read.
PROBE_SWITCH_RE='validate[-_.]?on[-_.]?startup'
CORE_JAVA_IMAGE_RE='/jtoye-core-java(:|@|$)'
PROBE_JSON_ENV_NORM='springapplicationjson'
PROBE_JVM_OPTS_ENV_NORM_RE='^(javaopts|javatooloptions|jdkjavaoptions)$'

command -v kubectl > /dev/null \
    || parse_fail "kubectl not on PATH (client-side 'kubectl kustomize' is required)."

# INV-11 self-test: PROBE_SWITCH_RE, through the SAME engine and flags as the scan
# (grep -iE on a file), must see every spelling of the switch and none of its
# neighbours. A pattern that cannot match makes rule (a) pass on anything.
inv11_selftest() {
    local pos neg n_pos n_neg want rc_pos=0 rc_neg=0
    pos="$(mktemp)"; neg="$(mktemp)"
    printf '%s\n' 'storage.blob.validate-on-startup=false' 'STORAGE_BLOB_VALIDATEONSTARTUP' \
        'STORAGE_BLOB_VALIDATE_ON_STARTUP' '{"storage":{"blob":{"validateOnStartup":false}}}' \
        '-Dstorage.blob.validate.on.startup=false' > "$pos"
    printf '%s\n' 'STORAGE_CREATE_CONTAINERS' 'storage.blob.create-containers' 'validate' \
        'startupProbe' 'on-startup' > "$neg"
    want=$(wc -l < "$pos")
    n_pos=$(grep -ciE -e "$PROBE_SWITCH_RE" "$pos") || rc_pos=$?
    n_neg=$(grep -ciE -e "$PROBE_SWITCH_RE" "$neg") || rc_neg=$?
    rm -f "$pos" "$neg"
    if (( rc_pos > 1 )) || [[ "$n_pos" != "$want" ]]; then
        parse_fail "INV-11 self-test: PROBE_SWITCH_RE matched ${n_pos:-?} of $want spellings of the probe switch (grep rc=$rc_pos). Rule (a) would be blind. Fix the pattern, do not delete the invariant."
    fi
    if (( rc_neg > 1 )) || [[ "$n_neg" != "0" ]]; then
        parse_fail "INV-11 self-test: PROBE_SWITCH_RE matched ${n_neg:-?} negative control(s) (grep rc=$rc_neg) — it would fire on a legitimate neighbour."
    fi
}
inv11_selftest

# INV-12 (37-24, D-19 / RWO-006): judge ONE trusted-proxy entry. Prints nothing
# when the entry is an acceptable private CIDR, otherwise the reason it is
# refused. Pure bash integer math on purpose: no external tool, no DNS, nothing
# that could resolve a hostname an entry smuggles in.
tp_entry_verdict() {
    local e="$1" addr prefix o1 o2 o3 o4 ip mask net first g
    if [[ "$e" == */* ]]; then addr="${e%%/*}"; prefix="${e#*/}"; else addr="$e"; prefix=""; fi
    if [[ -n "$prefix" && ! "$prefix" =~ ^[0-9]+$ ]]; then
        echo "has a prefix length that is not a number"; return 0
    fi
    if [[ "$addr" =~ ^([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})$ ]]; then
        o1=$((10#${BASH_REMATCH[1]})); o2=$((10#${BASH_REMATCH[2]})); o3=$((10#${BASH_REMATCH[3]})); o4=$((10#${BASH_REMATCH[4]}))
        if (( o1 > 255 || o2 > 255 || o3 > 255 || o4 > 255 )); then
            echo "is not a valid IPv4 address"; return 0
        fi
        [[ -n "$prefix" ]] || prefix=32
        prefix=$((10#$prefix))
        if (( prefix == 0 )); then
            echo "is the whole IPv4 address space (/0): it trusts EVERY client and re-opens UXT-006"; return 0
        fi
        if (( prefix < 8 )); then
            echo "has a prefix shorter than /8 (wider than any private block)"; return 0
        fi
        if (( prefix > 32 )); then
            echo "has a prefix longer than /32"; return 0
        fi
        ip=$(( (o1 << 24) | (o2 << 16) | (o3 << 8) | o4 ))
        mask=$(( (0xFFFFFFFF << (32 - prefix)) & 0xFFFFFFFF ))
        net=$(( ip & mask ))
        if (( prefix >= 8  && (net & 0xFF000000) == 0x0A000000 )); then return 0; fi   # 10.0.0.0/8
        if (( prefix >= 12 && (net & 0xFFF00000) == 0xAC100000 )); then return 0; fi   # 172.16.0.0/12
        if (( prefix >= 16 && (net & 0xFFFF0000) == 0xC0A80000 )); then return 0; fi   # 192.168.0.0/16
        echo "is not inside a private range (RFC 1918: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16)"; return 0
    fi
    if [[ "$addr" == *:* ]]; then
        [[ -n "$prefix" ]] || prefix=128
        prefix=$((10#$prefix))
        if (( prefix == 0 )); then
            echo "is the whole IPv6 address space (::/0): it trusts EVERY client and re-opens UXT-006"; return 0
        fi
        if (( prefix < 8 )); then
            echo "has a prefix shorter than /8"; return 0
        fi
        if (( prefix > 128 )); then
            echo "has a prefix longer than /128"; return 0
        fi
        if [[ ! "$addr" =~ ^[0-9A-Fa-f:.]+$ ]]; then
            echo "is not a valid IPv6 address"; return 0
        fi
        first="${addr%%:*}"
        if [[ "$first" =~ ^[0-9A-Fa-f]{1,4}$ ]]; then
            g=$((16#$first))
            if (( (g & 0xFE00) == 0xFC00 )); then return 0; fi                        # fc00::/7
        fi
        echo "is not inside the IPv6 unique-local range fc00::/7"; return 0
    fi
    echo "is not an IPv4 or IPv6 CIDR"; return 0
}

# INV-12 self-test: the judge must REFUSE every dangerous shape and ACCEPT every
# legitimate private range before a single render is judged. A judge that cannot
# refuse makes INV-12 pass on anything; one that refuses private ranges fails
# every correct overlay. Either is a PARSE ERROR, never a verdict.
inv12_selftest() {
    local e v
    for e in '0.0.0.0/0' '::/0' '10.0.0.0/7' 'fc00::/7' '8.8.8.0/24' '203.0.113.0/24' '2001:db8::/32' \
             '172.15.0.0/16' '172.32.0.0/12' '192.168.0.0/15' '10.0.0.0/33' 'fd00::/129' \
             'ingress.example/8' '10.0.0.0/x' '10.0.0.256/8' '::ffff:8.8.8.8/104'; do
        v="$(tp_entry_verdict "$e")"
        [[ -n "$v" ]] || parse_fail "INV-12 self-test: '$e' was ACCEPTED — the range judge is blind to a dangerous entry. Fix the judge, do not delete the invariant."
    done
    for e in '10.244.0.0/16' '10.0.0.0/8' '172.16.0.0/12' '172.31.255.0/24' '192.168.1.0/24' \
             'fd00::/8' 'fd12:3456::/32' 'FC00::/8' '10.1.2.3'; do
        v="$(tp_entry_verdict "$e")"
        [[ -z "$v" ]] || parse_fail "INV-12 self-test: '$e' was REFUSED ($v) — the judge would fire on a legitimate private range."
    done
}
inv12_selftest
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

# --- awk: walk every document into full key PATHS (INV-8/9/10, LOC-7).
#     Output: <kind> <TAB> <metadata.name> <TAB> <path> <TAB> <raw value>
#     Path segments are joined with "::"; a sequence item becomes "[i]", indexed
#     from 0 per parent path, so containers::[0]::env::[3]::name is unambiguous.
#
#     WHY PATHS. kubectl kustomize sorts map keys alphabetically, so inside a
#     container `env:` is emitted BEFORE that container's own `name:`, and inside
#     a pod template `metadata:` precedes `spec:`. Any scan that finds a container
#     by its name and reads FORWARD sees none of its env — the ordering trap INV-3
#     and INV-7 already document. A full path is independent of emission order.
#
#     kustomize emits a sequence at the SAME indent as its parent key
#     ("containers:" and "- env:" share a column), which is why a list item pops
#     only deeper frames and earlier sibling items, never its parent. Block
#     scalars (| and >) are skipped whole so their text is never read as keys.
RENDER_PATHS_AWK='
function flush(  i) {
    if (n == 0) return
    kind = ""; nm = ""
    for (i = 1; i <= n; i++) {
        if (buf[i] ~ /^kind: /)   { kind = buf[i]; sub(/^kind:[[:space:]]*/, "", kind) }
        if (nm == "" && buf[i] ~ /^  name: /) { nm = buf[i]; sub(/^  name:[[:space:]]*/, "", nm) }
    }
    sp = 0; delete sind; delete sseg; delete slist; delete cnt; blockind = -1
    for (i = 1; i <= n; i++) walk(buf[i])
    n = 0; delete buf
}
function path(  j, p) {
    p = ""
    for (j = 1; j <= sp; j++) p = p (p == "" ? "" : "::") sseg[j]
    return p
}
function keyline(rest, ind,   k, v) {
    k = rest; sub(/:([[:space:]].*)?$/, "", k)
    v = rest; if (v ~ /^[^:]*:[[:space:]]/) sub(/^[^:]*:[[:space:]]*/, "", v); else v = ""
    sp++; sind[sp] = ind; sseg[sp] = k; slist[sp] = 0
    printf "%s\t%s\t%s\t%s\n", kind, nm, path(), v
    if (v ~ /^[|>][-+0-9]*$/) blockind = ind
}
function walk(l,   ind, rest, P) {
    if (l ~ /^[[:space:]]*$/ || l ~ /^[[:space:]]*#/) return
    ind = match(l, /[^ ]/) - 1
    if (blockind >= 0) { if (ind > blockind) return; blockind = -1 }
    rest = substr(l, ind + 1)
    if (rest ~ /^- / || rest == "-") {
        while (sp > 0 && (sind[sp] > ind || (sind[sp] == ind && slist[sp]))) sp--
        P = path()
        sp++; sind[sp] = ind; sseg[sp] = "[" (cnt[P]++) "]"; slist[sp] = 1
        rest = substr(rest, 3)
        if (rest ~ /^[A-Za-z0-9_.\/-]+:([[:space:]]|$)/) keyline(rest, ind + 2)
        else if (rest != "") printf "%s\t%s\t%s\t%s\n", kind, nm, path(), rest
        return
    }
    while (sp > 0 && sind[sp] >= ind) sp--
    if (rest ~ /^[A-Za-z0-9_.\/-]+:([[:space:]]|$)/) keyline(rest, ind)
}
/^---[[:space:]]*$/ { flush(); next }
{ buf[++n] = $0 }
END { flush() }
'

# --- awk: NetworkPolicy rule TUPLES from the path walk (INV-7).
#     Input: the RENDER_PATHS_AWK output (<kind> TAB <name> TAB <path> TAB <value>).
#     Output records:
#       DOC <TAB> <kind> <TAB> <name> <TAB> <apiVersion>   every *NetworkPolicy doc
#       T   <TAB> <policy> <TAB> <tuple>                   see the INV-7 header
#       IPB <TAB> <policy> <TAB> <dir>|<cidr>|<except> <TAB> <space-joined ports>
#     Paths, not a forward scan: kustomize sorts map keys, so `ports:` precedes
#     the `from:`/`to:` it belongs to. Label values and cidrs lose one pair of
#     YAML quotes (they are strings either way); PORTS keep theirs, because a
#     quoted port is a NAMED port and must be visible as such.
NETPOL_TUPLES_AWK='
function unq(v) { if (v ~ /^".*"$/ || v ~ /^\047.*\047$/) v = substr(v, 2, length(v) - 2); return v }
function jsort(a, n,   i, j, t, s) {
    for (i = 2; i <= n; i++) { t = a[i]; j = i - 1; while (j >= 1 && a[j] > t) { a[j + 1] = a[j]; j-- } a[j + 1] = t }
    s = ""; for (i = 1; i <= n; i++) s = s (i > 1 ? "," : "") a[i]
    return s
}
function labels(store, cnt, key,   a, k, n) {
    n = cnt[key] + 0
    for (k = 1; k <= n; k++) a[k] = store[key, k]
    return jsort(a, n)
}
function peertok(pk,   ns, pod, t) {
    if (pk in hasip) return "ip=" cidr[pk]
    ns = ""; pod = ""
    if (pk in hasns) {
        if (pk in nsall) ns = "*"
        else if (nsln[pk] == 1 && nsl[pk, 1] ~ /^kubernetes\.io\/metadata\.name=/) { ns = nsl[pk, 1]; sub(/^[^=]*=/, "", ns) }
        else ns = "{" labels(nsl, nsln, pk) "}"
    }
    if (pk in haspod) pod = (pk in podall) ? "*" : labels(pdl, pdln, pk)
    if (ns != "" && pod != "") t = "nspod=" ns "/" pod
    else if (ns != "") t = "ns=" ns
    else if (pod != "") t = "pod=" pod
    else t = "?"
    if (pk in pexpr) t = t "+expr"
    if (pk in pother) t = t "+" pother[pk]
    return t
}
function porttok(qk,   v, pr) {
    v = (qk in pval) ? pval[qk] : "*"
    if (qk in pend) v = v "-" pend[qk]
    pr = (qk in pproto) ? pproto[qk] : "TCP"
    return (pr == "TCP") ? v : v "/" pr
}
BEGIN { FS = "\t" }
$1 ~ /NetworkPolicy$/ {
    id = $1 SUBSEP $2
    if (!(id in seen)) { seen[id] = 1; order[++nd] = id; dkind[id] = $1; dname[id] = $2 }
    if ($3 == "apiVersion") api[id] = $4
    if ($1 != "NetworkPolicy") next
    n = split($3, P, "::")
    if (P[1] != "spec") next
    if (P[2] == "podSelector") {
        if (n == 2 && $4 == "{}") selall[id] = 1
        else if (n == 4 && P[3] == "matchLabels") sel[id, ++seln[id]] = P[4] "=" unq($4)
        else if (n >= 3 && P[3] == "matchExpressions") selexpr[id] = 1
        hassel[id] = 1
    }
    if (P[2] == "policyTypes" && n == 3) types[id, ++typen[id]] = $4
    if ((P[2] == "ingress" || P[2] == "egress") && n >= 3) {
        d = (P[2] == "ingress") ? "in" : "eg"
        rk = id SUBSEP d SUBSEP P[3]
        if (!(rk in rseen)) { rseen[rk] = 1; rules[id, ++rulen[id]] = rk; rdir[rk] = d }
        if (n >= 5 && (P[4] == "from" || P[4] == "to")) {
            pk = rk SUBSEP P[5]
            if (!(pk in pseen)) { pseen[pk] = 1; peers[rk, ++peern[rk]] = pk }
            if (n >= 6) {
                if (P[6] == "namespaceSelector") hasns[pk] = 1
                else if (P[6] == "podSelector") haspod[pk] = 1
                else if (P[6] == "ipBlock") hasip[pk] = 1
                else pother[pk] = P[6]
            }
            if (n == 6 && P[6] == "namespaceSelector" && $4 == "{}") nsall[pk] = 1
            if (n == 6 && P[6] == "podSelector" && $4 == "{}") podall[pk] = 1
            if (n == 8 && P[6] == "namespaceSelector" && P[7] == "matchLabels") nsl[pk, ++nsln[pk]] = P[8] "=" unq($4)
            if (n == 8 && P[6] == "podSelector" && P[7] == "matchLabels") pdl[pk, ++pdln[pk]] = P[8] "=" unq($4)
            if (n >= 7 && (P[6] == "namespaceSelector" || P[6] == "podSelector") && P[7] == "matchExpressions") pexpr[pk] = 1
            if (n == 7 && P[6] == "ipBlock" && P[7] == "cidr") cidr[pk] = unq($4)
            if (n == 8 && P[6] == "ipBlock" && P[7] == "except") exc[pk, ++excn[pk]] = unq($4)
        }
        if (n >= 6 && P[4] == "ports") {
            qk = rk SUBSEP P[5]
            if (!(qk in qseen)) { qseen[qk] = 1; ports[rk, ++portn[rk]] = qk }
            if (n == 6 && P[6] == "port") pval[qk] = $4
            if (n == 6 && P[6] == "protocol") pproto[qk] = $4
            if (n == 6 && P[6] == "endPort") pend[qk] = $4
        }
    }
}
END {
    for (i = 1; i <= nd; i++) {
        id = order[i]
        printf "DOC\t%s\t%s\t%s\n", dkind[id], dname[id], api[id]
        if (dkind[id] != "NetworkPolicy") continue
        nm = dname[id]
        if (id in selall) s = "*"
        else if (seln[id] + 0 > 0) s = labels(sel, seln, id)
        else s = "?"
        if (id in selexpr) s = s "+expr"
        printf "T\t%s\tsel:%s\n", nm, s
        printf "T\t%s\ttypes:%s\n", nm, labels(types, typen, id)
        for (r = 1; r <= rulen[id] + 0; r++) {
            rk = rules[id, r]; d = rdir[rk]
            np = peern[rk] + 0; nq = portn[rk] + 0
            plist = ""
            for (q = 1; q <= nq; q++) plist = plist (q > 1 ? " " : "") porttok(ports[rk, q])
            if (nq == 0) plist = "*"
            for (p = 1; p <= (np > 0 ? np : 1); p++) {
                if (np == 0) { pt = "*"; pk = "" } else { pk = peers[rk, p]; pt = peertok(pk) }
                m = split(plist, PL, " ")
                for (q = 1; q <= m; q++) printf "T\t%s\t%s:%s:%s\n", nm, d, pt, PL[q]
                if (pk != "" && (pk in hasip)) printf "IPB\t%s\t%s|%s|%s\t%s\n", nm, d, cidr[pk], labels(exc, excn, pk), plist
            }
        }
    }
}
'

# cfg_get <cfg.tsv> <key> — the app-config value with ONE pair of surrounding
# quotes removed, or "(ABSENT)". kustomize quotes values that would otherwise
# parse as non-strings ("false", "", "5432") and leaves URLs bare, so comparing
# raw tokens would make an assertion depend on YAML quoting rather than content.
cfg_get() {
    awk -F'\t' -v k="$2" '
        $1 == k { v = $2; if (v ~ /^".*"$/ || v ~ /^\047.*\047$/) v = substr(v, 2, length(v) - 2); print v; found = 1; exit }
        END { if (!found) print "(ABSENT)" }' "$1"
}

# path_get <paths.tsv> <kind> <name> <path> — the raw value at an exact path, or
# "(ABSENT)". Raw on purpose: INV-10 must tell the string "true" from a bare
# boolean true (a label value must be a string; the bare form is rejected at apply).
path_get() {
    awk -F'\t' -v k="$2" -v n="$3" -v p="$4" '
        $1 == k && $2 == n && $3 == p { print $4; found = 1; exit }
        END { if (!found) print "(ABSENT)" }' "$1"
}

is_wi_target() {
    local rel="$1" t
    for t in "${WI_TARGETS[@]}"; do
        [[ "$rel" == "$t" ]] && return 0
    done
    return 1
}
declare -A WI_TARGET_SEEN=()

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
            inv4_msg="OK (0 localhost / 127.0.0.1 / UseDevelopmentStorage / devstoreaccount1 / AccountKey= literals)"
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

    # ---------------- INV-7 ----------------
    # Issue #271 + Phase 29: the rendered NetworkPolicy set equals the declared
    # allow-list exactly, in every target (see the INV-7 header).
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
    ingress_ns=$(cfg_get "$TMP/cfg.tsv" "ingress.controller-namespace")
    if [[ "$ingress_ns" == "(ABSENT)" || -z "$ingress_ns" ]]; then
        parse_fail "[$rel] INV-7 found no app-config key 'ingress.controller-namespace' in the render. It is the single render-time declaration of the namespace every NetworkPolicy admits the ingress controller from (D-29) and the source of the kustomize replacement; without it the controller rules have nothing to compare against — restore the key, do not delete the invariant."
    fi
    if [[ ! "$ingress_ns" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ ]]; then
        parse_fail "[$rel] INV-7: app-config 'ingress.controller-namespace' is '$ingress_ns', which is not a valid namespace name, so no namespaceSelector carrying it can match anything."
    fi

    awk "$RENDER_PATHS_AWK" "$render" > "$TMP/inv7_paths.tsv"
    awk "$NETPOL_TUPLES_AWK" "$TMP/inv7_paths.tsv" > "$TMP/netpol.tsv"
    mapfile -t inv7_pols < <(awk -F'\t' '$1 == "DOC" && $2 == "NetworkPolicy" { print $3 }' "$TMP/netpol.tsv" | LC_ALL=C sort -u)
    (( ${#inv7_pols[@]} > 0 )) || parse_fail "[$rel] INV-7 found 0 NetworkPolicy documents in the render. This platform ships seven; zero means the parser is blind and every rule assertion below would pass vacuously. Fix the parser, do not delete the invariant."

    inv7_bad=0
    inv7_tuples=0

    # (4) the policy DIALECT: plain networking.k8s.io/v1 only.
    while IFS='#' read -r fk fname fapi; do
        [[ -n "$fk" ]] || continue
        echo "  FAIL [$rel] INV-7: foreign NetworkPolicy kind '$fk' (apiVersion '${fapi:-<none>}') in document '$fname'." >&2
        echo "        Only kind NetworkPolicy at networking.k8s.io/v1 is declared. A Cilium/Calico/admin" >&2
        echo "        policy CRD is enforced by the engine but invisible to every assertion in this gate." >&2
        inv7_bad=1
    done < <(awk -F'\t' '$1 == "DOC" && !($2 == "NetworkPolicy" && $4 == "networking.k8s.io/v1") { print $2 "#" $3 "#" $4 }' "$TMP/netpol.tsv")

    # (1) + (2) every rendered policy has exactly one row, and its tuples equal it.
    declare -A inv7_seen=()
    for pol in "${inv7_pols[@]}"; do
        inv7_seen["$pol"]=1
        row_key=""; nrow=0
        for key in "${!NETPOL_RULES_EXPECTED[@]}"; do
            [[ "${key%@*}" == "$pol" ]] || continue
            # shellcheck disable=SC2053  # the glob is the point
            [[ "$rel" == ${key#*@} ]] || continue
            row_key="$key"; nrow=$((nrow + 1))
        done
        (( nrow <= 1 )) || parse_fail "[$rel] INV-7: NetworkPolicy '$pol' matches $nrow NETPOL_RULES_EXPECTED rows; exactly one may apply to a target."
        if (( nrow == 0 )); then
            echo "  FAIL [$rel] INV-7: undeclared policy '$pol' — no NETPOL_RULES_EXPECTED row applies to it in this target." >&2
            echo "        Every rendered NetworkPolicy is part of the allow-list; declare its exact rule set." >&2
            inv7_bad=1
            continue
        fi
        INV7_ROW_USED["$row_key"]=1
        row="${NETPOL_RULES_EXPECTED[$row_key]}"
        row="${row//__DB_PORT__/$db_port}"
        row="${row//__INGRESS_NS__/$ingress_ns}"
        tr -s ' \n' '\n\n' <<<"$row" | sed '/^$/d' | LC_ALL=C sort > "$TMP/inv7_exp"
        awk -F'\t' -v p="$pol" '$1 == "T" && $2 == p { print $3 }' "$TMP/netpol.tsv" | LC_ALL=C sort > "$TMP/inv7_act"
        inv7_tuples=$((inv7_tuples + $(wc -l < "$TMP/inv7_act")))
        while IFS= read -r tok; do
            ptok="${tok##*:}"; ptok="${ptok%%/*}"
            if [[ "$tok" == in:* || "$tok" == eg:* ]] && [[ "$ptok" != "*" && ! "$ptok" =~ ^[0-9]+(-[0-9]+)?$ ]]; then
                echo "  FAIL [$rel] INV-7: NetworkPolicy '$pol' has a non-numeric port token in '$tok'." >&2
                echo "        A STRING port in a NetworkPolicy is a NAMED port, not a number: it renders," >&2
                echo "        applies, and matches NO traffic. The db.port replacement source is a" >&2
                echo "        ConfigMap string, so this is a real failure mode of the mechanism." >&2
                inv7_bad=1
            fi
        done < "$TMP/inv7_act"
        extra=$(LC_ALL=C comm -13 "$TMP/inv7_exp" "$TMP/inv7_act" | tr '\n' ' ')
        missing=$(LC_ALL=C comm -23 "$TMP/inv7_exp" "$TMP/inv7_act" | tr '\n' ' ')
        if [[ -n "$extra" ]]; then
            echo "  FAIL [$rel] INV-7: NetworkPolicy '$pol' renders undeclared rule(s): ${extra% }" >&2
            inv7_bad=1
        fi
        if [[ -n "$missing" ]]; then
            echo "  FAIL [$rel] INV-7: NetworkPolicy '$pol' is missing declared rule(s): ${missing% }" >&2
            inv7_bad=1
        fi
        if [[ -n "$extra$missing" ]]; then
            echo "        declared ($row_key, db.port=$db_port, controller ns=$ingress_ns): $(tr '\n' ' ' < "$TMP/inv7_exp")" >&2
            echo "        rendered: $(tr '\n' ' ' < "$TMP/inv7_act")" >&2
        fi
    done
    # A declared row that applies to this target must be rendered in it.
    for key in "${!NETPOL_RULES_EXPECTED[@]}"; do
        # shellcheck disable=SC2053
        [[ "$rel" == ${key#*@} ]] || continue
        if [[ -z "${inv7_seen[${key%@*}]:-}" ]]; then
            echo "  FAIL [$rel] INV-7: declared policy '${key%@*}' ($key) is absent from the render." >&2
            inv7_bad=1
        fi
    done
    unset inv7_seen

    # (3) ipBlock shapes and the ports on them.
    while IFS='#' read -r ipol shape iports; do
        [[ -n "$ipol" ]] || continue
        if [[ -z "${NETPOL_IPBLOCK_EXPECTED[$shape]+set}" ]]; then
            echo "  FAIL [$rel] INV-7: NetworkPolicy '$ipol' has an undeclared ipBlock '$shape' (direction|cidr|except) on port(s) [$iports]." >&2
            echo "        Declared shapes: ${!NETPOL_IPBLOCK_EXPECTED[*]}. A narrowed except list re-opens a" >&2
            echo "        cluster-internal range to the whole public rule; a new shape is a reviewed change." >&2
            inv7_bad=1
            continue
        fi
        INV7_SHAPE_USED["$shape"]=1
        allowed=" ${NETPOL_IPBLOCK_EXPECTED[$shape]//__DB_PORT__/$db_port} "
        read -r -a inv7_ipl <<<"$iports"   # read -a: no pathname expansion of a `*`
        for ip_port in "${inv7_ipl[@]}"; do
            if [[ "$allowed" != *" $ip_port "* ]]; then
                echo "  FAIL [$rel] INV-7: NetworkPolicy '$ipol' allows ipBlock '$shape' on undeclared port '$ip_port' (declared:${allowed% })." >&2
                inv7_bad=1
            fi
        done
    done < <(awk -F'\t' '$1 == "IPB" { print $2 "#" $3 "#" $4 }' "$TMP/netpol.tsv")

    if (( inv7_bad != 0 )); then
        echo "        INV-7 asserts the WHOLE allow-list. Change NETPOL_RULES_EXPECTED (and the README flow" >&2
        echo "        matrix) in the same commit as the policy, never the other way round. THE PORT AND" >&2
        echo "        THE CONTROLLER NAMESPACE ARE NEVER EDITED IN A POLICY: set app-config 'db.port' /" >&2
        echo "        'ingress.controller-namespace' and let the 'replacements:' blocks derive them. If" >&2
        echo "        this is an OVERLAY that patches either key, check that its own kustomization.yaml" >&2
        echo "        repeats the replacement: kustomize does NOT re-run a base replacement against an" >&2
        echo "        overlay's patched ConfigMap (issue #271)." >&2
        FAILED=1
        inv7_msg="FAIL"
    else
        inv7_msg="OK (${#inv7_pols[@]} policies, $inv7_tuples rule tuples exact, db.port=$db_port, controller ns=$ingress_ns)"
    fi

    # ---------------- INV-8 / INV-9 / INV-10 (Phase 36) ----------------
    # Staging and production only (WI_TARGETS). Every value is read by exact key
    # or exact PATH, never by a forward scan (kustomize sorts map keys).
    if is_wi_target "$rel"; then
        WI_TARGET_SEEN["$rel"]=1
        awk "$RENDER_PATHS_AWK" "$render" > "$TMP/paths.tsv"
        (( $(wc -l < "$TMP/paths.tsv") > 0 )) || parse_fail "[$rel] INV-8..INV-10: the path walker emitted 0 records for the render — it is blind, and every storage assertion below would pass vacuously. Fix RENDER_PATHS_AWK, do not delete the invariants."
        if [[ "$(path_get "$TMP/paths.tsv" Deployment core-java 'spec::template::spec::containers::[0]::name')" == "(ABSENT)" ]]; then
            parse_fail "[$rel] INV-8..INV-10: no Deployment core-java container found by path — either the render lost core-java (a far bigger problem) or the path walker is blind. Fix it, do not delete the invariants."
        fi

        # ---- INV-8: the storage model ----
        inv8_bad=0
        m_mode="$(cfg_get "$TMP/cfg.tsv" storage.blob.auth-mode)"
        b_mode="$(cfg_get "$TMP/cfg.tsv" backup.blob.auth-mode)"
        m_ep="$(cfg_get "$TMP/cfg.tsv" storage.blob.endpoint)"
        b_ep="$(cfg_get "$TMP/cfg.tsv" backup.blob.endpoint)"
        m_url="$(cfg_get "$TMP/cfg.tsv" storage.blob.public-url)"
        m_pub="$(cfg_get "$TMP/cfg.tsv" storage.blob.public-container)"
        m_quar="$(cfg_get "$TMP/cfg.tsv" storage.blob.quarantine-container)"
        m_create="$(cfg_get "$TMP/cfg.tsv" storage.blob.create-containers)"
        for pair in "storage.blob.auth-mode=$m_mode" "backup.blob.auth-mode=$b_mode"; do
            if [[ "${pair#*=}" != "workload-identity" ]]; then
                echo "  FAIL [$rel] INV-8: app-config '${pair%%=*}' is '${pair#*=}', expected 'workload-identity' (D-02)." >&2
                inv8_bad=1
            fi
        done
        for pair in "storage.blob.endpoint=$m_ep" "backup.blob.endpoint=$b_ep"; do
            if [[ ! "${pair#*=}" =~ $BLOB_ACCOUNT_ENDPOINT_RE ]]; then
                echo "  FAIL [$rel] INV-8: app-config '${pair%%=*}' is '${pair#*=}', which is not a raw Blob account endpoint ($BLOB_ACCOUNT_ENDPOINT_RE, D-06)." >&2
                inv8_bad=1
            fi
        done
        if [[ "$m_url" != "$m_ep/$BLOB_PUBLIC_CONTAINER" ]]; then
            echo "  FAIL [$rel] INV-8: storage.blob.public-url is '$m_url', expected exactly the media endpoint + /$BLOB_PUBLIC_CONTAINER = '$m_ep/$BLOB_PUBLIC_CONTAINER' (D-06: it is the origin every persisted image URL carries)." >&2
            inv8_bad=1
        fi
        if [[ "$m_pub" != "$BLOB_PUBLIC_CONTAINER" || "$m_quar" != "$BLOB_QUARANTINE_CONTAINER" ]]; then
            echo "  FAIL [$rel] INV-8: containers are public='$m_pub' quarantine='$m_quar', expected '$BLOB_PUBLIC_CONTAINER' / '$BLOB_QUARANTINE_CONTAINER' (D-07)." >&2
            inv8_bad=1
        fi
        if [[ "$m_create" != "false" ]]; then
            echo "  FAIL [$rel] INV-8: storage.blob.create-containers is '$m_create', expected 'false' — Set Container ACL cannot be authorized with Entra ID, and core-java refuses 'true' in workload-identity mode." >&2
            inv8_bad=1
        fi
        if [[ "$m_ep" == "$b_ep" ]]; then
            echo "  FAIL [$rel] INV-8: the media and backup endpoints are the SAME account ('$m_ep'). D-01 puts the logical dump in a DEDICATED account (another region), so a compromised media identity cannot reach it." >&2
            inv8_bad=1
        fi
        if (( inv8_bad != 0 )); then
            FAILED=1
            inv8_msg="FAIL"
        else
            inv8_msg="OK (workload-identity x2, media ${m_ep#https://} != backup ${b_ep#https://}, public-url = media + /$BLOB_PUBLIC_CONTAINER)"
        fi

        # ---- INV-9: no stored storage credential ----
        inv9_bad=0
        secret_refs=$(awk -F'\t' '$3 ~ /::secretKeyRef::name$/ || $3 ~ /::secretRef::name$/ || $3 ~ /::secret::secretName$/ || $3 ~ /::sources::\[[0-9]+\]::secret::name$/' "$TMP/paths.tsv")
        [[ -n "$secret_refs" ]] || parse_fail "[$rel] INV-9 found 0 Secret references in the render. core-java and pg-backup both read postgres-credentials, so zero means the path walker is blind and the 'no storage Secret' assertion would pass vacuously. Fix it, do not delete the invariant."
        secret_ref_count=$(wc -l <<< "$secret_refs")
        while IFS=$'\t' read -r k n pth v; do
            if [[ "${v,,}" =~ $STORAGE_SECRET_NAME_RE ]]; then
                echo "  FAIL [$rel] INV-9: $k/$n references Secret '$v' at $pth — a stored storage credential (D-02 forbids one in any Secret, sealed or not)." >&2
                inv9_bad=1
            fi
        done <<< "$secret_refs"
        env_names=$(awk -F'\t' '$3 ~ /::env::\[[0-9]+\]::name$/' "$TMP/paths.tsv")
        [[ -n "$env_names" ]] || parse_fail "[$rel] INV-9 found 0 env names by path — the path walker is blind. Fix it, do not delete the invariant."
        while IFS=$'\t' read -r k n pth v; do
            if [[ "$v" =~ $STORAGE_CRED_ENV_RE ]]; then
                echo "  FAIL [$rel] INV-9: $k/$n sets env '$v' ($pth) — a connection string or stored cloud credential outside the local overlay (D-02)." >&2
                inv9_bad=1
            fi
        done <<< "$env_names"
        if awk -F'\t' '$1 ~ /connection-string/ { found = 1 } END { exit !found }' "$TMP/cfg.tsv"; then
            echo "  FAIL [$rel] INV-9: app-config carries a connection-string key: $(awk -F'\t' '$1 ~ /connection-string/ { printf "%s ", $1 }' "$TMP/cfg.tsv")" >&2
            inv9_bad=1
        fi
        if (( inv9_bad != 0 )); then
            FAILED=1
            inv9_msg="FAIL"
        else
            inv9_msg="OK ($secret_ref_count Secret ref(s), none storage; 0 connection-string/AWS_/AZURE_STORAGE_ env; 0 connection-string key)"
        fi

        # ---- INV-10: Workload Identity wiring ----
        inv10_bad=0
        for wl in $(printf '%s\n' "${!WI_WORKLOAD_TEMPLATE[@]}" | sort); do
            wk="${wl%%/*}"; wn="${wl#*/}"; tp="${WI_WORKLOAD_TEMPLATE[$wl]}"
            lbl="$(path_get "$TMP/paths.tsv" "$wk" "$wn" "$tp::metadata::labels::$WI_LABEL_KEY")"
            san="$(path_get "$TMP/paths.tsv" "$wk" "$wn" "$tp::spec::serviceAccountName")"
            if [[ "$lbl" != '"true"' ]]; then
                echo "  FAIL [$rel] INV-10: $wl pod template label $WI_LABEL_KEY is $lbl, expected the STRING \"true\" at $tp::metadata::labels. Without it the webhook injects no identity and core-java refuses to boot." >&2
                inv10_bad=1
            fi
            if [[ "$san" != "${WI_WORKLOAD_SA[$wl]}" ]]; then
                echo "  FAIL [$rel] INV-10: $wl runs as serviceAccountName '$san', expected '${WI_WORKLOAD_SA[$wl]}' (the federated credential's subject names exactly that ServiceAccount)." >&2
                inv10_bad=1
            fi
        done
        for sa in $(printf '%s\n' "${WI_WORKLOAD_SA[@]}" | sort -u); do
            am="$(path_get "$TMP/paths.tsv" ServiceAccount "$sa" automountServiceAccountToken)"
            if [[ "$am" != "false" ]]; then
                echo "  FAIL [$rel] INV-10: ServiceAccount '$sa' has automountServiceAccountToken=$am, expected false (it must exist, and neither workload needs a Kubernetes API token)." >&2
                inv10_bad=1
            fi
        done
        # Least privilege: no pod template other than the two Blob workloads opts in.
        while IFS=$'\t' read -r k n pth v; do
            [[ -n "$k" ]] || continue
            tp="${WI_WORKLOAD_TEMPLATE[$k/$n]:-}"
            if [[ -z "$tp" || "$pth" != "$tp::metadata::labels::$WI_LABEL_KEY" ]]; then
                echo "  FAIL [$rel] INV-10: $k/$n carries $WI_LABEL_KEY at $pth — only the pod templates of ${!WI_WORKLOAD_TEMPLATE[*]} may opt into Workload Identity." >&2
                inv10_bad=1
            fi
        done <<< "$(awk -F'\t' -v key="$WI_LABEL_KEY" '{ n = split($3, seg, "::"); if (seg[n] == key) print }' "$TMP/paths.tsv")"
        if (( inv10_bad != 0 )); then
            FAILED=1
            inv10_msg="FAIL"
        else
            inv10_msg="OK (${#WI_WORKLOAD_TEMPLATE[@]} pod templates labelled + dedicated SA; 2 ServiceAccounts, automount false; no other opt-in)"
        fi
    else
        inv8_msg="SKIP (not in WI_TARGETS)"
        inv9_msg="SKIP (not in WI_TARGETS)"
        inv10_msg="SKIP (not in WI_TARGETS)"
    fi

    # ---------------- INV-11 (Phase 36 D-08, code review WR-01) ----------------
    # EVERY target: no render can switch off the boot-time storage probe.
    inv11_bad=0
    # (a) raw render text, every document, every field.
    inv11_hits=""; inv11_rc=0
    inv11_hits=$(grep -niE -e "$PROBE_SWITCH_RE" "$render") || inv11_rc=$?
    if (( inv11_rc > 1 )); then
        parse_fail "[$rel] INV-11: grep failed (rc=$inv11_rc) reading the render — refusing to report 'no probe switch' from a scan that did not run."
    fi
    if [[ -n "$inv11_hits" ]]; then
        while IFS= read -r hit; do
            echo "  FAIL [$rel] INV-11: the render names the storage-probe switch (render line ${hit%%:*}): ${hit#*:}" >&2
        done <<< "$inv11_hits"
        inv11_bad=1
    fi
    # (b) + (c) per core-java container, by exact path.
    awk "$RENDER_PATHS_AWK" "$render" > "$TMP/inv11_paths.tsv"
    inv11_ctrs=$(awk -F'\t' -v img="$CORE_JAVA_IMAGE_RE" \
        '$3 ~ /::containers::\[[0-9]+\]::image$/ && $4 ~ img { p = $3; sub(/::image$/, "", p); print $1 "\t" $2 "\t" p }' \
        "$TMP/inv11_paths.tsv")
    [[ -n "$inv11_ctrs" ]] || parse_fail "[$rel] INV-11 found 0 containers running the core-java image by path — either the render lost core-java or the path walker is blind, and the per-container probe checks would pass vacuously. Fix it, do not delete the invariant."
    inv11_n=0
    while IFS=$'\t' read -r ck cn cp; do
        (( ++inv11_n ))
        while IFS=$'\t' read -r _k _n pth v; do
            sub="${pth#"$cp"::}"
            if [[ "$sub" == envFrom ]]; then
                echo "  FAIL [$rel] INV-11: $ck/$cn container at $cp uses envFrom ($pth) — it imports env names the render cannot enumerate, so the storage-probe switch could arrive unseen (D-08)." >&2
                inv11_bad=1
            elif [[ "$sub" =~ ^env::\[([0-9]+)\]::name$ ]]; then
                # Capture the index NOW: the next =~ overwrites BASH_REMATCH.
                item="$cp::env::[${BASH_REMATCH[1]}]"
                norm="${v,,}"; norm="${norm//[^a-z0-9]/}"
                if [[ "$norm" == "$PROBE_JSON_ENV_NORM" ]]; then
                    echo "  FAIL [$rel] INV-11: $ck/$cn container at $cp sets env '$v' — SPRING_APPLICATION_JSON can set any property, the storage-probe switch included (D-08)." >&2
                    inv11_bad=1
                elif [[ "$norm" =~ $PROBE_JVM_OPTS_ENV_NORM_RE ]]; then
                    if [[ "$(path_get "$TMP/inv11_paths.tsv" "$ck" "$cn" "$item::valueFrom::secretKeyRef::name")" != "(ABSENT)" ]]; then
                        echo "  FAIL [$rel] INV-11: $ck/$cn container at $cp sources '$v' from a Secret — a -D flag in it would switch the storage probe off where no render check can read it (D-08)." >&2
                        inv11_bad=1
                    fi
                fi
            fi
        done < <(awk -F'\t' -v k="$ck" -v n="$cn" -v p="$cp::" '$1 == k && $2 == n && index($3, p) == 1' "$TMP/inv11_paths.tsv")
    done <<< "$inv11_ctrs"
    if (( inv11_bad != 0 )); then
        echo "        D-08: the boot-time storage probe (#626 container levels) is ON in every runtime." >&2
        echo "        A literal in application.yml does not make that true — env, SPRING_APPLICATION_JSON," >&2
        echo "        args and -D all outrank it. Remove the override; test contexts opt out in" >&2
        echo "        core-java/src/test/resources, never in a manifest." >&2
        FAILED=1
        inv11_msg="FAIL"
    else
        inv11_msg="OK ($inv11_n core-java container(s); 0 probe-switch spellings in the render; no SPRING_APPLICATION_JSON, no Secret-sourced JVM options, no envFrom)"
    fi

    # ---------------- INV-12 (37-24, D-19 / RWO-006) ----------------
    # EVERY target: the trusted-proxy list is present, and every entry is a private
    # CIDR no wider than /8 and never /0. Empty is trust none (WARN outside local).
    inv12_bad=0
    inv12_n=0
    inv12_tp="$(cfg_get "$TMP/cfg.tsv" security.trusted-proxies)"
    if [[ "$inv12_tp" == "(ABSENT)" ]]; then
        parse_fail "[$rel] INV-12: app-config has no security.trusted-proxies key. k8s/base/configmap.yaml must declare it (\"\" = trust none) so every render states the posture; a missing key would let this invariant pass vacuously. Fix the manifest, do not delete the invariant."
    fi
    inv12_clean="${inv12_tp//[[:space:]]/}"
    if [[ -z "$inv12_clean" ]]; then
        if is_wi_target "$rel"; then
            echo "  WARN [$rel] INV-12: security.trusted-proxies is EMPTY (trust none). Behind the ingress every client is the ingress pod, so ALL clients share ONE public rate-limit bucket until the operator declares the ingress-nginx controller's pod CIDR (37-24 user_setup; cluster-specific, not in this repo). Not a failure."
            inv12_msg="OK (empty = trust none; WARN: operator input outstanding)"
        else
            inv12_msg="OK (empty = trust none)"
        fi
    else
        IFS=',' read -ra inv12_entries <<< "$inv12_clean"
        for inv12_e in "${inv12_entries[@]}"; do
            [[ -n "$inv12_e" ]] || continue
            inv12_n=$((inv12_n + 1))
            inv12_v="$(tp_entry_verdict "$inv12_e")"
            if [[ -n "$inv12_v" ]]; then
                echo "  FAIL [$rel] INV-12: security.trusted-proxies entry '$inv12_e' $inv12_v." >&2
                inv12_bad=1
            fi
        done
        (( inv12_n > 0 )) || parse_fail "[$rel] INV-12: security.trusted-proxies is '$inv12_tp' but the splitter yielded 0 entries — it is blind. Fix it, do not delete the invariant."
        if (( inv12_bad != 0 )); then
            echo "        D-19: X-Forwarded-For is believed ONLY from these ranges, so a range that admits" >&2
            echo "        the public internet (or everyone, /0) lets any client choose its own rate-limit" >&2
            echo "        identity by writing the header — UXT-006 exactly, the #88 regression. Declare the" >&2
            echo "        ingress-nginx controller's POD CIDR: a private range (RFC 1918 or fc00::/7)," >&2
            echo "        /8 or narrower. core-java refuses /0 at startup as well; this gate refuses it" >&2
            echo "        before a rollout ever reaches that." >&2
            FAILED=1
            inv12_msg="FAIL"
        else
            inv12_msg="OK ($inv12_n private range(s): $inv12_clean)"
        fi
    fi

    # ---------------- INV-14 (29-08, Blocker A) ----------------
    # Deployed targets only: the vendor login's OIDC client must be a client the
    # realm template actually has, usable for a confidential browser login.
    if is_wi_target "$rel"; then
        inv14_cid="$(cfg_get "$TMP/cfg.tsv" keycloak.client-id)"
        if [[ "$inv14_cid" == "(ABSENT)" || -z "$inv14_cid" ]]; then
            echo "  FAIL [$rel] INV-14: app-config keycloak.client-id is ${inv14_cid:-empty}. The frontend's KEYCLOAK_CLIENT_ID is read from it; a deployed render must name the realm's vendor client." >&2
            FAILED=1
            inv14_msg="FAIL"
        elif [[ "$REALM_LOGIN_CLIENTS" != *" $inv14_cid "* ]]; then
            inv14_why="is not a clientId in the realm template"
            if awk -F'\t' -v c="$inv14_cid" '$1 == c { f = 1 } END { exit !f }' <<< "$REALM_CLIENTS_TSV"; then
                inv14_why="is in the realm template but is not usable for a vendor login (Keycloak built-in, disabled, no standard flow, bearer-only or public)"
            fi
            echo "  FAIL [$rel] INV-14: app-config keycloak.client-id '$inv14_cid' $inv14_why ($REALM_TEMPLATE)." >&2
            echo "        Login clients the template offers:${REALM_LOGIN_CLIENTS% }. Blocker A: the base once named" >&2
            echo "        'frontend', a client no realm has, so every deployed login could only end in invalid_client." >&2
            FAILED=1
            inv14_msg="FAIL"
        else
            inv14_msg="OK (keycloak.client-id '$inv14_cid' is a login client of the realm template)"
        fi
    else
        inv14_msg="SKIP (not in WI_TARGETS)"
    fi

    if [[ "$inv1_msg" == FAIL*|| "$inv2_msg" == FAIL* || "$inv3_msg" == FAIL* \
          || "$inv4_msg" == FAIL* || "$inv6_msg" == FAIL* || "$inv7_msg" == FAIL* \
          || "$inv8_msg" == FAIL* || "$inv9_msg" == FAIL* || "$inv10_msg" == FAIL* \
          || "$inv11_msg" == FAIL* || "$inv12_msg" == FAIL* || "$inv14_msg" == FAIL* ]]; then
        echo "FAIL [$rel]: INV-1 $inv1_msg | INV-2 $inv2_msg | INV-3 $inv3_msg | INV-4 $inv4_msg | INV-6 $inv6_msg | INV-7 $inv7_msg | INV-8 $inv8_msg | INV-9 $inv9_msg | INV-10 $inv10_msg | INV-11 $inv11_msg | INV-12 $inv12_msg | INV-14 $inv14_msg" >&2
    else
        echo "OK   [$rel]: INV-1 $inv1_msg | INV-2 $inv2_msg | INV-3 $inv3_msg | INV-4 $inv4_msg | INV-6 $inv6_msg | INV-7 $inv7_msg | INV-8 $inv8_msg | INV-9 $inv9_msg | INV-10 $inv10_msg | INV-11 $inv11_msg | INV-12 $inv12_msg | INV-14 $inv14_msg"
    fi
done

# WI_TARGETS non-vacuity: a renamed or removed overlay must not make INV-8..INV-10
# silently stop running. Every listed target must have been rendered and checked.
for t in "${WI_TARGETS[@]}"; do
    [[ -n "${WI_TARGET_SEEN[$t]:-}" ]] || parse_fail "INV-8..INV-10: WI_TARGETS names '$t', but no kustomize target at that path was discovered, so its storage invariants never ran. Update WI_TARGETS in the SAME change that renames an overlay."
done

# INV-7 hygiene: a declared row or ipBlock shape that no target used is a
# standing allowance for something that no longer exists — remove it rather than
# leave a hole waiting for a policy to grow into it.
for key in "${!NETPOL_RULES_EXPECTED[@]}"; do
    if [[ -z "${INV7_ROW_USED[$key]:-}" ]]; then
        echo "FAIL: INV-7: STALE NETPOL_RULES_EXPECTED row '$key' — no target renders that policy. Remove the row in the same change that removed the policy." >&2
        FAILED=1
    fi
done
for shape in "${!NETPOL_IPBLOCK_EXPECTED[@]}"; do
    if [[ -z "${INV7_SHAPE_USED[$shape]:-}" ]]; then
        echo "FAIL: INV-7: STALE NETPOL_IPBLOCK_EXPECTED shape '$shape' — no target renders it. Remove it rather than keep an unused allowance." >&2
        FAILED=1
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
# LOC-1..LOC-7 — the k8s/local overlay (Phase 26 / INFRA-01; LOC-7 Phase 36)
#
# CONDITIONAL BY DESIGN: if the overlay is ever removed this section is skipped
# and the script stays valid, rather than failing on a missing directory.
# ===========================================================================
LOCAL_SECTION="LOC-1..LOC-6, LOC-7 SKIPPED (k8s/local/kustomization.yaml not present)"

if [[ -f "$LOCAL_KUSTOMIZATION" ]]; then
    echo "LOC-1..LOC-7 (INFRA-01 + Phase 36, k8s/local): the committed local overlay's shape"

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
      # Phase 36: the pod reaches the host Azurite through the emulator string's
      # DevelopmentStorageProxyUri, so the CONNECTION STRINGS carry the shim
      # (storage.blob.endpoint / backup.blob.endpoint are empty: unused in
      # connection-string mode).
      'storage.blob.connection-string'
      'backup.blob.connection-string'
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
        echo "        not at build time: a wrong storage.blob.connection-string breaks image upload" >&2
        echo "        only, a wrong smtp.host breaks email only. DELIBERATE EXCEPTIONS, both browser-" >&2
        echo "        reachable and both correctly NOT in the list above: storage.blob.public-url (the browser loads image" >&2
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

    loc2_bad=0
    for spec in "Deployment replicas 3 $dep_replicas $dep_ones" \
                "HorizontalPodAutoscaler minReplicas 3 $hpa_mins $hpa_ones" \
                "PodDisruptionBudget minAvailable 3 $pdb_mins $pdb_ones"; do
        read -r kind field want total ones <<< "$spec"
        if (( total != want || ones != want )); then
            echo "  FAIL [k8s/local] LOC-2: expected $want $kind object(s) with '$field: 1'; found $total object(s), $ones of them at 1." >&2
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
        loc2_msg="OK (replicas/minReplicas/minAvailable = 1 x3 each; maxReplicas [$loc_max] == base)"
    fi

    # ---------------- LOC-3 ----------------
    # Phase 36: the backup target is the host Azurite, through the emulator string.
    LOCAL_BACKUP_CONNECTION_STRING="UseDevelopmentStorage=true;DevelopmentStorageProxyUri=http://$LOCAL_HOST_SHIM"
    backup_mode="$(cfg_get "$TMP/loc_cfg.tsv" backup.blob.auth-mode)"
    backup_cs="$(cfg_get "$TMP/loc_cfg.tsv" backup.blob.connection-string)"
    if [[ "$backup_mode" != "connection-string" || "$backup_cs" != "$LOCAL_BACKUP_CONNECTION_STRING" ]]; then
        echo "  FAIL [k8s/local] LOC-3: app-config backup.blob.auth-mode='$backup_mode' backup.blob.connection-string='$backup_cs';" >&2
        echo "        expected exactly 'connection-string' and '$LOCAL_BACKUP_CONNECTION_STRING'." >&2
        echo "        Base names the PRODUCTION backup account under workload identity. Locally that" >&2
        echo "        aims a database dump at a real Azure account with no identity, and it makes the" >&2
        echo "        restore rehearsal (issue #101) impossible to run at all." >&2
        FAILED=1
        loc3_msg="FAIL"
    else
        loc3_msg="OK (connection-string -> $backup_cs)"
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

    # ---------------- LOC-7 ----------------
    # Phase 36: the emulator path is WIRED, not just configured. The connection
    # strings exist only in k8s/local, so an unwired key is a silently ignored value.
    awk "$RENDER_PATHS_AWK" "$LOCAL_RENDER" > "$TMP/loc_paths.tsv"
    (( $(wc -l < "$TMP/loc_paths.tsv") > 0 )) || parse_fail "LOC-7: the path walker emitted 0 records for the k8s/local render — it is blind. Fix RENDER_PATHS_AWK, do not delete the invariant."
    loc7_bad=0
    loc_mode="$(cfg_get "$TMP/loc_cfg.tsv" storage.blob.auth-mode)"
    loc_create="$(cfg_get "$TMP/loc_cfg.tsv" storage.blob.create-containers)"
    if [[ "$loc_mode" != "connection-string" ]]; then
        echo "  FAIL [k8s/local] LOC-7: storage.blob.auth-mode is '$loc_mode', expected 'connection-string' (local has no Workload Identity webhook)." >&2
        loc7_bad=1
    fi
    if [[ "$loc_create" != "true" ]]; then
        echo "  FAIL [k8s/local] LOC-7: storage.blob.create-containers is '$loc_create', expected 'true' (Azurite starts empty)." >&2
        loc7_bad=1
    fi
    # <kind> <name> <pod-template path prefix> <container> <expected app-config key>
    for spec in "Deployment core-java spec::template core-java storage.blob.connection-string" \
                "CronJob pg-backup spec::jobTemplate::spec::template pg-backup backup.blob.connection-string"; do
        read -r wk wn tp cn want <<< "$spec"
        wired=$(awk -F'\t' -v k="$wk" -v n="$wn" -v tp="$tp" -v cn="$cn" -v want="$want" '
            $1 != k || $2 != n { next }
            index($3, tp "::spec::containers::[") == 1 {
                rest = substr($3, length(tp "::spec::containers::[") + 1)
                ci = rest; sub(/\].*$/, "", ci); tail = rest; sub(/^[0-9]+\]::/, "", tail)
                if (tail == "name") cname[ci] = $4
                if (tail ~ /^env::\[[0-9]+\]::name$/ && $4 == "STORAGE_CONNECTION_STRING") { ei = tail; sub(/^env::\[/, "", ei); sub(/\].*$/, "", ei); envat[ci] = ei }
                if (tail ~ /^env::\[[0-9]+\]::valueFrom::configMapKeyRef::key$/) { ei = tail; sub(/^env::\[/, "", ei); sub(/\].*$/, "", ei); keyof[ci "," ei] = $4 }
            }
            END {
                for (c in cname) if (cname[c] == cn && (c in envat) && keyof[c "," envat[c]] == want) { print "yes"; exit }
                print "no"
            }' "$TMP/loc_paths.tsv")
        if [[ "$wired" != "yes" ]]; then
            echo "  FAIL [k8s/local] LOC-7: $wk/$wn container '$cn' has no STORAGE_CONNECTION_STRING env from app-config '$want'." >&2
            loc7_bad=1
        fi
    done
    if (( loc7_bad != 0 )); then
        echo "        Without it core-java falls back to the bare UseDevelopmentStorage=true default," >&2
        echo "        which dials 127.0.0.1:10000 — the POD's own loopback — so every upload fails, and" >&2
        echo "        blobctl refuses to run the backup. The env comes from k8s/local/storage-env-patch.yaml;" >&2
        echo "        check it is listed under patches: in k8s/local/kustomization.yaml." >&2
        FAILED=1
        loc7_msg="FAIL"
    else
        loc7_msg="OK (connection-string, create-containers true, STORAGE_CONNECTION_STRING wired on core-java + pg-backup)"
    fi

    if [[ "$loc1_msg" == FAIL* || "$loc2_msg" == FAIL* || "$loc3_msg" == FAIL* \
          || "$loc4_msg" == FAIL* || "$loc5_msg" == FAIL* || "$loc6_msg" == FAIL* \
          || "$loc7_msg" == FAIL* ]]; then
        echo "FAIL [k8s/local]: LOC-1 $loc1_msg | LOC-2 $loc2_msg | LOC-3 $loc3_msg | LOC-4 $loc4_msg | LOC-5 $loc5_msg | LOC-6 $loc6_msg | LOC-7 $loc7_msg" >&2
    else
        echo "  OK   [k8s/local] LOC-1 $loc1_msg"
        echo "  OK   [k8s/local] LOC-2 $loc2_msg"
        echo "  OK   [k8s/local] LOC-3 $loc3_msg"
        echo "  OK   [k8s/local] LOC-4 $loc4_msg"
        echo "  OK   [k8s/local] LOC-5 $loc5_msg"
        echo "  OK   [k8s/local] LOC-6 $loc6_msg"
        echo "  OK   [k8s/local] LOC-7 $loc7_msg"
    fi
    LOCAL_SECTION="LOC-1..LOC-6, LOC-7 checked on k8s/local"
    echo
fi

# ===========================================================================
if (( FAILED != 0 )); then
    fail "one or more rendered-manifest invariants are broken — see above. Each invariant pins a defect that already shipped once; fix the manifest or the docs rather than relaxing the assertion."
fi

echo "PASS: INV-1..INV-7, INV-11 and INV-12 hold across ${#TARGETS[@]} kustomize target(s); INV-8, INV-9, INV-10 and INV-14 hold on ${WI_TARGETS[*]}; $LOCAL_SECTION."
