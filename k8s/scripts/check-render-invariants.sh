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
#            a. Tuples are read through render_paths.py (full key paths), never a
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
#   INV-19 PHASE 29 D-20 (owner ruling 2026-10-18, review round 1 F1), RENDER level,
#          staging + production only. STARTTLS is REQUIRED there, by LITERALS in
#          core-java's application-prod.yml (every cluster runs the prod profile), so
#          no render may carry a way to turn it down: no env, on Deployment core-java
#          (the one JavaMail client; Mailpit's own MP_SMTP_REQUIRE_STARTTLS is the server
#          half and only tightens it), whose
#          NAME or literal VALUE contains `starttls` except core-java's own
#          SMTP_STARTTLS (which application-prod.yml's literal already outranks), so no
#          SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_REQUIRED=false, no
#          SPRING_APPLICATION_JSON or JAVA_TOOL_OPTIONS naming it. The one place that
#          may relax it is k8s/local (Mailhog offers no STARTTLS), and LOC-8 requires it
#          to, because without it every local-k8s email fails with "STARTTLS is required
#          but host does not support STARTTLS".
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
#          WIDENED BY PLAN 29-12 (D-23): the allowed set is the two Blob workloads
#          PLUS every pod template that has a csi volume whose driver is
#          secrets-store.csi.k8s.io, DERIVED from the render (never listed by
#          hand). Each such template must carry the label (the STRING "true") at
#          its template path and run as a named ServiceAccount that exists with
#          automountServiceAccountToken false and a GUID-shaped
#          azure.workload.identity/client-id annotation (the identity the CSI
#          provider fetches Key Vault as). A label on any other pod template still
#          FAILS. Which Secret each mount produces, and that the SPC's clientID is
#          the ServiceAccount's, is k8s/scripts/check-keyvault-chain.sh's job.
#
#          ASSERTION SHAPE FOR INV-8..INV-10 + LOC-7. Each document is walked into
#          full key PATHS by render_paths.py (PyYAML), list items indexed,
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
#          is the core-java image, found by exact PATH (::containers::[i]::image
#          or ::initContainers::[i]::image, plan 29-22: the truststore-builder init
#          container runs the core-java image), so a second workload running the
#          image is covered too. Zero such
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
#   INV-15 PHASE 29 plan 29-08, Pitfall 4 / D-08 / D-29 / T-29-29 / T-29-30,
#          RENDER level, staging + production (WI_TARGETS), every Ingress:
#            a. every tls entry carries EXACTLY ONE host and names a Secret. api
#               and app once shared one entry, so one failed HTTP-01 challenge
#               failed the whole order and left BOTH hosts without a certificate;
#            b. among Ingresses carrying cert-manager.io/cluster-issuer (or
#               /issuer), no Secret is requested by two tls entries. The SSE
#               Ingress used to request the main Ingress's Secret too: two
#               Certificates for one Secret contend over it;
#            c. NOINDEX_TARGETS (staging): every Ingress has a
#               configuration-snippet with X-Robots-Tag noindex AND every
#               more_set_headers line of base jtoye-ingress's snippet (read from
#               the base render; the staging copy replaces base's and cannot
#               append, so a drifted copy would silently drop a security header).
#               Every OTHER deployed target must serve NO noindex: production is
#               indexable once live, and a stray noindex would de-list it;
#            d. every nginx.ingress.kubernetes.io/* annotation suffix is listed in
#               k8s/platform/traefik/supported-annotations.txt (plan 29-03): the
#               Traefik provider ignores what it does not implement.
#          The list missing or header-only, zero Ingresses, zero tls entries, zero
#          nginx annotations or zero base header lines is a PARSE ERROR. Shown
#          catching each hole by --selftest-inv15 (planted arms). The live header
#          proof (curl -sI on each staging host) is plan 29-15.
#
#   INV-16 PHASE 29 plan 29-22, D-21 / D-30 / T-29-81, RENDER level, every target
#          in TRUSTSTORE_TARGETS (k8s/staging) and every other target whose
#          core-java Deployment renders a truststore-builder init container or a
#          JAVA_TOOL_OPTIONS env. core-java trusts the cluster CA through a
#          JVM-WIDE truststore, so the store must be the JDK's own cacerts PLUS
#          the CA, never the CA alone (that breaks Entra, Blob and Stripe TLS at
#          once, D-30):
#            a. every init container of Deployment core-java that runs the
#               core-java image and runs keytool -importcert COPIES a path ending
#               lib/security/cacerts into /truststore/cacerts on an earlier,
#               uncommented line of its command (read from the raw render, since
#               the command is a block scalar the path walker skips);
#            b. the core-java container's JAVA_TOOL_OPTIONS is exactly one literal
#               `value:` naming -Djavax.net.ssl.trustStore=/truststore/cacerts
#               (never valueFrom; INV-11 refuses a Secret source as well), and it
#               mounts /truststore;
#            c. no container of Deployment core-java sets JAVA_OPTS: the image's
#               ENTRYPOINT expands the Dockerfile's JAVA_OPTS (container sizing,
#               G1, the scrimage WebP dir), which an env value would replace;
#            d. a TRUSTSTORE_TARGETS render with no truststore-builder FAILS.
#          Production and local carry no truststore component today and print
#          "not applicable". Shown catching each hole by --selftest-inv16.
#
#   INV-17 PHASE 29 plan 29-26, #859 (UXT-090) / T-29-95, RENDER level, staging +
#          production (WI_TARGETS). The customer realm's anti-impersonation gate,
#          jtoye.security.customer-jwt.require-verified-email, must be ON in every
#          deployed core-java: with it off, a self-registered customer account
#          carrying a victim's UNVERIFIED address can list the victim's orders.
#            a. core-java/src/main/resources/application.yml's default reads true
#               (the line missing or named twice is a PARSE ERROR);
#            b. no core-java-image container sets an env that binds the property
#               (any name whose lower-cased alphanumerics end in
#               requireverifiedemail, i.e. CUSTOMER_JWT_REQUIRE_VERIFIED_EMAIL or a
#               relaxed-binding spelling) to anything but true: a literal is read
#               as is, an app-config configMapKeyRef by its RENDERED value, any
#               other source fails as unreadable; envFrom on such a container
#               fails for the same reason;
#            c. where no env sets it, no active profile (SPRING_PROFILES_ACTIVE,
#               literal or app-config) has an application-<p>.yml defaulting it
#               to anything but true (application-dev.yml relaxes it, so a
#               deployed render activating dev fails).
#          Shown catching each hole by --selftest-inv17.
#
#   INV-18 PHASE 29 plan 29-22, D-33 / T-29-108..T-29-110, RENDER level, every
#          target that renders the mailpit component (a Deployment or Service named
#          mailpit). The testers' mail UI is public, so its render must keep it
#          authenticated, keep SMTP private and keep Mailpit from sending:
#            a. every Ingress path whose backend is Service mailpit names port 8025
#               (by number, or by a port name whose Service port is 8025), and its
#               Ingress carries auth-type basic, a non-empty auth-secret with no `/`
#               (a same-namespace Secret: Traefik's cross-namespace resources are
#               off) and auth-secret-type absent or auth-file;
#            b. every Service selecting app=mailpit is ClusterIP (no LoadBalancer or
#               NodePort ever publishes 1025 or an unauthenticated 8025);
#            c. the mailpit container sets no MP_SMTP_RELAY_* / MP_SMTP_FORWARD_* /
#               MP_SEND_API_AUTH_ACCEPT_ANY / MP_ENABLE_CHAOS env and no envFrom, the
#               render text names none of --smtp-relay, --smtp-forward,
#               --send-api-auth-accept-any, --enable-chaos, and the container DOES
#               set MP_SEND_API_AUTH_FILE as a literal (the Send API lock).
#          A target with a mailpit Deployment but zero Ingress backends to it, or a
#          mailpit Service but no mailpit Deployment, is a PARSE ERROR (a blind
#          reader never passes). Production and local print "not applicable". Shown
#          catching each hole by --selftest-inv18; the live proof is
#          scripts/staging-mail-ui.sh --probe (UI-1..UI-8).
#
#   INV-20 #976 (plan 29-10 finding), RENDER level, every target that renders a
#          Keycloak container (KEYCLOAK_TARGETS, k8s/staging, MUST render one;
#          production and local print "not applicable" until they do). A container
#          is Keycloak's by image name, by Keycloak's own server envs or args, or by
#          living in a pod labelled app: keycloak (see KEYCLOAK_CTRS_AWK).
#          Keycloak's proxy headers make it believe X-Forwarded-Host/-Proto/-For
#          from whoever connects, unless --proxy-trusted-addresses is set ("By
#          default all addresses are trusted", `kc.sh start --help`): a client could
#          then choose the issuer URL, the scheme and the address Keycloak records
#          for it. INV-20 judges the container's EFFECTIVE configuration, wherever
#          it is spelled:
#            env           KC_PROXY_HEADERS, KC_PROXY_TRUSTED_ADDRESSES, KC_PROXY, and
#                          any JAVA_OPTS* / JAVA_TOOL_OPTIONS / JDK_JAVA_OPTIONS value
#                          carrying -Dkc.proxy-headers / -Dkc.proxy-trusted-addresses /
#                          -Dkc.proxy
#            args, command --proxy-headers, --proxy-trusted-addresses=, --proxy and the
#                          -Dkc.proxy* spelling, one per item; an item that only
#                          MENTIONS one (a sh -c script) or is a block scalar cannot be
#                          read and FAILS closed
#            envFrom       every key of a ConfigMap in the render (prefix applied); a
#                          Secret, or a ConfigMap that is not rendered, FAILS closed
#            configMapKeyRef  followed into ANY ConfigMap in the render
#          If headers are on in ANY of them the container must
#            a. have exactly ONE source of trusted addresses across all of the above
#               (none = trusts every source; two = which wins cannot be judged, and
#               a repeated flag is two);
#            b. get it as a literal, or from a NON-optional configMapKeyRef on a key
#               present in the render (a Secret or fieldRef source, an unrendered
#               ConfigMap and an optional ref are refused: the value could not be
#               judged, or could silently vanish into trust-everyone);
#            c. have a non-empty value with NO whitespace and no empty entry (26.8.1
#               refuses an empty value and does NOT trim " 10.0.0.0/8", both measured
#               with the image; this fails them at render time instead of at rollout);
#            d. have every entry pass the SAME judge as INV-12 (tp_entry_verdict):
#               not /0, no shorter than /8, inside RFC 1918 / fc00::/7.
#          And a Keycloak container must not use the legacy KC_PROXY / --proxy
#          (edge, reencrypt, passthrough): it trusts the headers and has no trusted
#          addresses option at all.
#          Keycloak's setting has its OWN app-config key, keycloak.proxy-trusted-addresses,
#          not core-java's security.trusted-proxies (empty = trust none to core-java,
#          a boot failure to Keycloak).
#          NOT SEEN, recorded: a keycloak.conf or Quarkus (QUARKUS_HTTP_PROXY_*) setting
#          shipped in the image or mounted from a volume; the render cannot read those.
#          ACCEPTED RESIDUAL, recorded: the range is the pod network, so the in-cluster
#          callers that keycloak-allow admits on 8080 are inside it. Traefik has no
#          narrower stable range. Shown catching each hole by --selftest-inv20.
#
#   INV-21 #968 item 3 (owner approval 2026-10-20, option A), RENDER level, every
#          target that renders the Grafana component (a Deployment named grafana or
#          an Ingress route to Service grafana). Grafana's public login page was open
#          to the internet; it is now behind the same Traefik basic-auth chain as the
#          testers' mail UI (INV-18), so its render must keep it there:
#            a. every Ingress path whose backend is Service grafana sits on an
#               Ingress that carries auth-type basic, a non-empty auth-secret with no
#               `/` (a same-namespace Secret: Traefik's cross-namespace resources are
#               off) and auth-secret-type absent or auth-file;
#            b. that auth-secret names a Secret that a SecretProviderClass in the
#               render syncs as key `auth` (the key Traefik reads). An auth-secret
#               nothing produces makes Traefik skip the route (fail closed, but a
#               Grafana nobody can reach); a missing auth-type serves the page open;
#            c. every Service that reaches Grafana's pod (selector app=grafana, or
#               named grafana) is ClusterIP, as INV-18 does for mailpit, and the
#               routes in (a) are found by ANY such Service, through
#               spec.rules[].http.paths[] AND spec.defaultBackend, not by the name
#               `grafana` alone.
#          A target with a grafana Deployment but zero Ingress backends to it, or a
#          grafana route but no grafana Deployment, is a PARSE ERROR (a blind reader
#          never passes). Production and local print "not applicable" (neither renders
#          monitoring). Shown catching each hole by --selftest-inv21; the live proof is
#          scripts/staging-grafana-edge.sh --probe (G-1..G-4). That the SPC is MOUNTED
#          by secret-sync is k8s/scripts/check-keyvault-chain.sh's rule, not this one's.
#
#   INV-22 #999, RENDER level, EVERY target; each must be named in INV22_EXPECTED (an
#          unlisted target FAILS, review round 1 F3): k8s/staging -> staging, k8s/local ->
#          local, k8s/production and k8s/base -> production. k8s runs the prod
#          Spring profile in every environment (D-10), and application-prod.yml sets the
#          Micrometer common tag management.metrics.tags.environment to `production`, so a
#          staging core-java that does not override it labels every series it exports
#          environment="production" (observed on live staging 2026-10-21): a dashboard,
#          federation or production Prometheus filtering on the label would read staging
#          traffic as production. So:
#            a. the prod profile still sets that key (the reader finds the line: if the key
#               moved, the override below binds nothing and this rule would pass over it);
#            b. every core-java-image APP container (containers, not initContainers) of
#               Deployment core-java sets MANAGEMENT_METRICS_TAGS_ENVIRONMENT (any spelling
#               whose lower-cased alphanumerics are managementmetricstagsenvironment) once, to
#               the target's label, as a literal or an app-config configMapKeyRef read by its
#               RENDERED value. A target whose label is `production` may leave it unset (the
#               profile's own value is right) but may not set it to anything else; any other
#               target in the table MUST set it.
#            c. a container that sets it from a source the render cannot read, or twice,
#               fails: which value wins cannot be shown.
#          Shown catching each hole by --selftest-inv22; the live proof is
#          curl -s localhost:9091/actuator/prometheus | grep environment= in the core-java pod.
#   INV-23 ISSUE #972, RENDER level, every target that renders the mailpit component
#          (a Deployment named mailpit). Mailpit reads its SMTP certificate ONLY at process
#          start, cert-manager renews mailpit-tls 15 days before expiry and kubelet rewrites the
#          mounted Secret in place, so without a restart the running Mailpit serves the OLD leaf
#          until it expires: every STARTTLS then fails, core-java's mail health goes DOWN and the
#          deploy-staging health step turns red like a code regression (dated 2027-01-17). The
#          container therefore runs a supervisor that hashes the cert and key it started with and
#          stops Mailpit when they change, so the kubelet restarts the container with the new
#          ones. The render must keep that, and must keep STARTTLS required:
#            a. container `mailpit` sets MP_SMTP_REQUIRE_STARTTLS to the literal true and
#               MP_SMTP_TLS_CERT / MP_SMTP_TLS_KEY to literal paths (the files the supervisor
#               hashes are named by exactly these variables);
#            b. the text of THAT container alone (not the whole `containers:` section, so a sidecar
#               cannot satisfy the markers; kustomize drops comments, so only live script is read)
#               contains every marker of the supervisor: the hash of BOTH files named by those
#               variables (`sha256sum "$MP_SMTP_TLS_CERT" "$MP_SMTP_TLS_KEY"`), Mailpit run in
#               the background (`/mailpit &`), the comparison against the start hash
#               (`[ "$now" != "$start" ]`), the stop (`kill -TERM "$pid"`) and a `trap`, and it
#               never `exec`s Mailpit (any quoting), which would replace the supervisor with the process
#               that cannot see a renewal.
#          A mailpit Deployment whose containers text cannot be read is a PARSE ERROR (a blind
#          reader never passes). Production and local print "not applicable". Shown catching
#          each hole by --selftest-inv23; the live proof is the rotation test in the PR for
#          issue #972 (a Mailpit serving the old certificate after the Secret changed, then the
#          new one with the supervisor).
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
#   LOC-8  Local can still SEND mail (review round 1 F1). Deployment core-java sets
#          SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_REQUIRED to the literal false, the
#          only relaxation of the D-20 STARTTLS requirement anywhere (INV-19 forbids it
#          in staging/production). Mailhog offers no STARTTLS and application-prod.yml
#          requires it by literal, so without this every local email fails.
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
# Usage: ./k8s/scripts/check-render-invariants.sh [--selftest-inv7|--selftest-inv15|--selftest-inv16|--selftest-inv17|--selftest-inv18|--selftest-inv20|--selftest-inv21|--selftest-inv22|--selftest-inv23]
#   (run from anywhere; paths resolve relative to the repo root)
#   --selftest-inv7  plant five holes in a copy of k8s/ and require INV-7 to
#                    catch each (see inv7_selftest below)
#   --selftest-inv15 the same for INV-15 (inv15_selftest)
#   --selftest-inv16 the same for INV-16 (inv16_selftest)
#   --selftest-inv17 the same for INV-17 (inv17_selftest)
#   --selftest-inv18 the same for INV-18 (inv18_selftest)
#   --selftest-inv20 the same for INV-20 (inv20_selftest)
#   --selftest-inv21 the same for INV-21 (inv21_selftest)
#   --selftest-inv22 the same for INV-22 (inv22_selftest)
#   --selftest-inv23 the same for INV-23 (inv23_selftest)

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
# the gate reads (INV-14's realm template, INV-17's application*.yml). The copy's REPO_ROOT is
# <arm root>, so anything missing here would VOID every arm instead of judging it.
selftest_stage() {
    rm -rf "$2"; mkdir -p "$2/infra/keycloak" "$2/core-java/src/main/resources"
    cp -a "$1" "$2/k8s"
    cp "$REPO_ROOT/infra/keycloak/realm-export.template.json" "$2/infra/keycloak/" \
        || { echo "PARSE ERROR: selftest could not copy the realm template" >&2; exit 2; }
    # INV-17 (29-26) reads core-java's application*.yml for the
    # require-verified-email default and the per-profile overrides.
    cp "$REPO_ROOT"/core-java/src/main/resources/application*.yml "$2/core-java/src/main/resources/" \
        || { echo "PARSE ERROR: selftest could not copy core-java's application*.yml" >&2; exit 2; }
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

# ===========================================================================
# --selftest-inv15 (Phase 29, plan 29-08): INV-15 shown CATCHING planted holes.
#
# Same harness shape as --selftest-inv7: the k8s/ tree (plus the realm template,
# selftest_stage) is copied, ONE hole is planted per arm in the copy, and the
# COPY's own gate must exit with the arm's expected rc AND print the message that
# names the hole. The unmodified copy must exit 0. Arms:
#   two-host               a second host added to staging jtoye-ingress tls[0]
#   shared-secret          base jtoye-sse-ingress regains cert-manager.io/cluster-issuer,
#                          so two managed tls entries request one Secret
#   missing-noindex        staging jtoye-sse-ingress loses its X-Robots-Tag line
#   unsupported-annotation base jtoye-ingress gains nginx.ingress.kubernetes.io/limit-rate
#                          (on Traefik's Unsupported list)
#   snippet-drift          staging jtoye-ingress's snippet loses base's HSTS line
#   production-noindex     base jtoye-ingress's snippet gains X-Robots-Tag noindex,
#                          which production must NOT serve (D-08: indexable once live)
#   supported-list-empty   supported-annotations.txt reduced to its header (rc 2)
#   supported-list-missing supported-annotations.txt deleted (rc 2)
# A plant that does not change its file is reported caught=no.
# INV15_SELFTEST_FORMAT=tap renders TAP 13 (for the RED-evidence classifier).
# ===========================================================================
inv15_plant() {
    local arm="$1" root="$2" f before
    case "$arm" in
        two-host)               f="$root/staging/ingress-hosts-patch.yaml" ;;
        shared-secret)          f="$root/base/sse-ingress.yaml" ;;
        missing-noindex)        f="$root/staging/sse-ingress-hosts-patch.yaml" ;;
        unsupported-annotation) f="$root/base/ingress.yaml" ;;
        snippet-drift)          f="$root/staging/ingress-hosts-patch.yaml" ;;
        production-noindex)     f="$root/base/ingress.yaml" ;;
        supported-list-empty|supported-list-missing) f="$root/platform/traefik/supported-annotations.txt" ;;
        *) return 1 ;;
    esac
    [[ -f "$f" ]] || return 1
    before="$(sha256sum "$f")"
    case "$arm" in
        two-host)
            awk '!done && /^        - api-staging\.olajay\.co\.uk$/ { print; print "        - www-staging.olajay.co.uk"; done = 1; next } { print }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        shared-secret)
            awk '!done && /^  annotations:$/ { print; print "    cert-manager.io/cluster-issuer: \"letsencrypt-prod\""; done = 1; next } { print }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        missing-noindex)
            awk '!/X-Robots-Tag/' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        unsupported-annotation)
            awk '!done && /^  annotations:$/ { print; print "    nginx.ingress.kubernetes.io/limit-rate: \"100\""; done = 1; next } { print }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        snippet-drift)
            awk '!/Strict-Transport-Security/' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        production-noindex)
            awk '{ print } !done && /more_set_headers "X-Frame-Options: DENY";/ { sub(/more_set_headers.*/, "more_set_headers \"X-Robots-Tag: noindex\";"); print; done = 1 }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        supported-list-empty)
            awk '/^#/ || /^[[:space:]]*$/' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        supported-list-missing)
            rm -f "$f"; [[ ! -e "$f" ]] && return 0 ;;
    esac
    [[ "$(sha256sum "$f")" != "$before" ]]
}

inv15_selftest() {
    local fmt="${INV15_SELFTEST_FORMAT:-plain}" st arm want wrc out rc caught n=0 bad=0 tap=() line
    st="$(mktemp -d)"
    # shellcheck disable=SC2064
    trap "rm -rf '$st'" EXIT
    cp -a "$K8S_DIR" "$st/pristine" || { echo "PARSE ERROR: --selftest-inv15 could not copy $K8S_DIR" >&2; exit 2; }
    local -a ARMS=(two-host shared-secret missing-noindex unsupported-annotation snippet-drift production-noindex supported-list-empty supported-list-missing)
    declare -A WANT=(
        [two-host]="INV-15: Ingress 'jtoye-ingress' tls[0] carries 2 hosts"
        [shared-secret]="INV-15: cert-manager-managed TLS Secret 'jtoye-staging-api-tls' is requested by 2 tls entries"
        [missing-noindex]="INV-15: Ingress 'jtoye-sse-ingress' serves no X-Robots-Tag noindex"
        [unsupported-annotation]="INV-15: Ingress 'jtoye-ingress' uses nginx.ingress.kubernetes.io/limit-rate"
        [snippet-drift]="INV-15: Ingress 'jtoye-ingress' configuration-snippet lacks base header line"
        [production-noindex]="INV-15: Ingress 'jtoye-ingress' serves X-Robots-Tag noindex in k8s/production"
        [supported-list-empty]="PARSE ERROR: INV-15:"
        [supported-list-missing]="PARSE ERROR: INV-15:"
    )
    declare -A WANT_RC=([supported-list-empty]=2 [supported-list-missing]=2)
    for arm in "${ARMS[@]}"; do
        n=$((n + 1))
        selftest_stage "$st/pristine" "$st/arm"
        if ! inv15_plant "$arm" "$st/arm/k8s"; then
            caught=no; line="plant did not apply"
        else
            if out=$(bash "$st/arm/k8s/scripts/check-render-invariants.sh" 2>&1); then rc=0; else rc=$?; fi
            want="${WANT[$arm]}"; wrc="${WANT_RC[$arm]:-1}"
            if (( rc == wrc )) && grep -qF -- "$want" <<<"$out"; then
                caught=yes; line="rc=$rc"
            else
                caught=no
                line="expected rc $wrc and '$want'; got rc=$rc: $(awk '/INV-15|PARSE ERROR/ && c < 2 { printf "%s ", $0; c++ }' <<<"$out")"
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

if [[ "${1:-}" == "--selftest-inv15" ]]; then
    inv15_selftest; exit $?
fi

# ===========================================================================
# --selftest-inv16 (Phase 29, plan 29-22): INV-16 shown CATCHING planted holes.
#
# Same harness shape as --selftest-inv7/-inv15: the k8s/ tree (plus the realm
# template, selftest_stage) is copied, ONE hole is planted per arm in the copy's
# k8s/components/cluster-ca-truststore, and the COPY's own gate must exit 1 AND
# print the message that names the hole. The unmodified copy must exit 0. Arms:
#   ca-only              the builder no longer copies the JDK cacerts, so keytool
#                        -importcert creates a store holding the cluster CA ALONE
#                        (it would break Entra, Blob and Stripe TLS at once, D-30)
#   java-opts            the core-java container sets JAVA_OPTS, which would replace
#                        the Dockerfile's JVM flags
#   tool-options-secret  JAVA_TOOL_OPTIONS comes from a Secret instead of a literal
#                        (INV-11 also refuses it; INV-16 must name it on its own)
#   component-missing    k8s/staging stops listing the component, so its core-java
#                        trusts no cluster CA and every required-STARTTLS send fails
# A plant that does not change its file is reported caught=no.
# INV16_SELFTEST_FORMAT=tap renders TAP 13 (for the RED-evidence classifier).
# ===========================================================================
inv16_plant() {
    local arm="$1" root="$2" f before
    case "$arm" in
        ca-only|java-opts|tool-options-secret) f="$root/components/cluster-ca-truststore/core-java-truststore-patch.yaml" ;;
        component-missing) f="$root/staging/kustomization.yaml" ;;
        *) return 1 ;;
    esac
    [[ -f "$f" ]] || return 1
    before="$(sha256sum "$f")"
    case "$arm" in
        ca-only)
            awk '!/^ *cp "\$JAVA_HOME\/lib\/security\/cacerts" \/truststore\/cacerts$/' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        java-opts)
            awk '{ print } !done && /^        - name: JAVA_TOOL_OPTIONS$/ { held = 1; next } held && !done { done = 1; held = 0; print "        - name: JAVA_OPTS"; print "          value: \"-Xmx512m\"" }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        tool-options-secret)
            awk '!done && /^          value: "-Djavax\.net\.ssl\.trustStore=/ { print "          valueFrom:"; print "            secretKeyRef:"; print "              name: jvm-options"; print "              key: tool-options"; done = 1; next } { print }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        component-missing)
            awk '!/^  - \.\.\/components\/cluster-ca-truststore$/' "$f" > "$f.new" && mv "$f.new" "$f" ;;
    esac
    [[ "$(sha256sum "$f")" != "$before" ]]
}

inv16_selftest() {
    local fmt="${INV16_SELFTEST_FORMAT:-plain}" st arm want out rc caught n=0 bad=0 tap=() line
    st="$(mktemp -d)"
    # shellcheck disable=SC2064
    trap "rm -rf '$st'" EXIT
    cp -a "$K8S_DIR" "$st/pristine" || { echo "PARSE ERROR: --selftest-inv16 could not copy $K8S_DIR" >&2; exit 2; }
    local -a ARMS=(ca-only java-opts tool-options-secret component-missing)
    declare -A WANT=(
        [ca-only]="INV-16: Deployment core-java init container 'truststore-builder' builds a CA-only truststore"
        [java-opts]="INV-16: Deployment core-java container 'core-java' sets JAVA_OPTS"
        [tool-options-secret]="INV-16: Deployment core-java container 'core-java' JAVA_TOOL_OPTIONS is not a literal value"
        [component-missing]="INV-16: k8s/staging renders no truststore-builder init container on Deployment core-java"
    )
    for arm in "${ARMS[@]}"; do
        n=$((n + 1))
        selftest_stage "$st/pristine" "$st/arm"
        if ! inv16_plant "$arm" "$st/arm/k8s"; then
            caught=no; line="plant did not apply"
        else
            if out=$(bash "$st/arm/k8s/scripts/check-render-invariants.sh" 2>&1); then rc=0; else rc=$?; fi
            want="${WANT[$arm]}"
            if (( rc == 1 )) && grep -qF -- "$want" <<<"$out"; then
                caught=yes; line="rc=1"
            else
                caught=no
                line="expected rc 1 and '$want'; got rc=$rc: $(awk '/INV-16|PARSE ERROR/ && c < 2 { printf "%s ", $0; c++ }' <<<"$out")"
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

if [[ "${1:-}" == "--selftest-inv16" ]]; then
    inv16_selftest; exit $?
fi

# ===========================================================================
# --selftest-inv17 (Phase 29, plan 29-26, #859 / T-29-95): INV-17 shown CATCHING
# planted holes. Same harness shape as --selftest-inv7/-inv15/-inv16/-inv18: the
# k8s/ tree, the realm template and core-java's application*.yml are copied
# (selftest_stage), ONE hole is planted per arm in the copy, and the COPY's own
# gate must exit with the arm's rc AND print the message that names the hole. The
# unmodified copy must exit 0. Arms:
#   literal-false      base core-java sets CUSTOMER_JWT_REQUIRE_VERIFIED_EMAIL
#                      to the literal "false"
#   configmap-false    the env comes from app-config key
#                      customer.jwt.require-verified-email, which staging and the
#                      base (production) set to "false": the RENDERED value is read
#   relaxed-spelling   JTOYE_SECURITY_CUSTOMERJWT_REQUIREVERIFIEDEMAIL "false",
#                      the same property under Spring's relaxed env binding
#   dev-profile        SPRING_PROFILES_ACTIVE "prod,dev": application-dev.yml
#                      defaults the property to false and no env overrides it
#   yaml-default-false application.yml's default flips to false
#   yaml-line-missing  application.yml no longer names the property (PARSE ERROR,
#                      rc 2: a reader that finds nothing never passes)
# A plant that does not change its file is reported caught=no.
# INV17_SELFTEST_FORMAT=tap renders TAP 13 (for the RED-evidence classifier).
# ===========================================================================
inv17_plant() {
    local arm="$1" root="$2" f before f2
    case "$arm" in
        literal-false|configmap-false|relaxed-spelling|dev-profile) f="$root/k8s/base/core-java-deployment.yaml" ;;
        yaml-default-false|yaml-line-missing) f="$root/core-java/src/main/resources/application.yml" ;;
        *) return 1 ;;
    esac
    [[ -f "$f" ]] || return 1
    before="$(sha256sum "$f")"
    case "$arm" in
        literal-false)
            awk '{ print } !done && /^          value: "prod"$/ { print "        - name: CUSTOMER_JWT_REQUIRE_VERIFIED_EMAIL"; print "          value: \"false\""; done = 1 }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        relaxed-spelling)
            awk '{ print } !done && /^          value: "prod"$/ { print "        - name: JTOYE_SECURITY_CUSTOMERJWT_REQUIREVERIFIEDEMAIL"; print "          value: \"false\""; done = 1 }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        configmap-false)
            awk '{ print } !done && /^          value: "prod"$/ { print "        - name: CUSTOMER_JWT_REQUIRE_VERIFIED_EMAIL"; print "          valueFrom:"; print "            configMapKeyRef:"; print "              name: app-config"; print "              key: customer.jwt.require-verified-email"; done = 1 }' "$f" > "$f.new" && mv "$f.new" "$f"
            for f2 in "$root/k8s/base/configmap.yaml" "$root/k8s/staging/configmap-patch.yaml"; do
                awk '{ print } !done && /^data:$/ { print "  customer.jwt.require-verified-email: \"false\""; done = 1 }' "$f2" > "$f2.new" && mv "$f2.new" "$f2"
            done ;;
        dev-profile)
            awk '!done && /^          value: "prod"$/ { print "          value: \"prod,dev\""; done = 1; next } { print }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        yaml-default-false)
            awk '{ sub(/\$\{CUSTOMER_JWT_REQUIRE_VERIFIED_EMAIL:true\}/, "${CUSTOMER_JWT_REQUIRE_VERIFIED_EMAIL:false}"); print }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        yaml-line-missing)
            awk '!/^[[:space:]]*require-verified-email:/' "$f" > "$f.new" && mv "$f.new" "$f" ;;
    esac
    [[ "$(sha256sum "$f")" != "$before" ]]
}

inv17_selftest() {
    local fmt="${INV17_SELFTEST_FORMAT:-plain}" st arm want wrc out rc caught n=0 bad=0 tap=() line
    st="$(mktemp -d)"
    # shellcheck disable=SC2064
    trap "rm -rf '$st'" EXIT
    cp -a "$K8S_DIR" "$st/pristine" || { echo "PARSE ERROR: --selftest-inv17 could not copy $K8S_DIR" >&2; exit 2; }
    local -a ARMS=(literal-false configmap-false relaxed-spelling dev-profile yaml-default-false yaml-line-missing)
    declare -A WANT=(
        [literal-false]="INV-17: Deployment core-java container 'core-java' sets CUSTOMER_JWT_REQUIRE_VERIFIED_EMAIL to 'false'"
        [configmap-false]="INV-17: Deployment core-java container 'core-java' sets CUSTOMER_JWT_REQUIRE_VERIFIED_EMAIL to 'false' (app-config key customer.jwt.require-verified-email)"
        [relaxed-spelling]="INV-17: Deployment core-java container 'core-java' sets JTOYE_SECURITY_CUSTOMERJWT_REQUIREVERIFIEDEMAIL to 'false'"
        [dev-profile]="INV-17: Deployment core-java container 'core-java' activates profile 'dev', whose application-dev.yml defaults require-verified-email to 'false'"
        [yaml-default-false]="INV-17: core-java/src/main/resources/application.yml defaults require-verified-email to 'false'"
        [yaml-line-missing]="PARSE ERROR: INV-17:"
    )
    declare -A WANT_RC=([yaml-line-missing]=2)
    for arm in "${ARMS[@]}"; do
        n=$((n + 1))
        selftest_stage "$st/pristine" "$st/arm"
        if ! inv17_plant "$arm" "$st/arm"; then
            caught=no; line="plant did not apply"
        else
            if out=$(bash "$st/arm/k8s/scripts/check-render-invariants.sh" 2>&1); then rc=0; else rc=$?; fi
            want="${WANT[$arm]}"; wrc="${WANT_RC[$arm]:-1}"
            if (( rc == wrc )) && grep -qF -- "$want" <<<"$out"; then
                caught=yes; line="rc=$rc"
            else
                caught=no
                line="expected rc $wrc and '$want'; got rc=$rc: $(awk '/INV-17|PARSE ERROR/ && c < 2 { printf "%s ", $0; c++ }' <<<"$out")"
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

if [[ "${1:-}" == "--selftest-inv17" ]]; then
    inv17_selftest; exit $?
fi

# ===========================================================================
# --selftest-inv18 (Phase 29, plan 29-22, D-33): INV-18 shown CATCHING planted
# holes in the testers' mail UI render. Same harness shape as -inv16. Arms (each
# in the copy's k8s/components/mailpit):
#   no-auth          Ingress mailpit-ui loses auth-type (an open mail UI)
#   port-1025        its backend port becomes 1025 (SMTP on the public edge)
#   cross-ns-secret  auth-secret becomes traefik/x (a cross-namespace Secret)
#   loadbalancer     Service mailpit becomes type LoadBalancer
#   relay-all        the mailpit container sets MP_SMTP_RELAY_ALL=true
#   lock-removed     the mailpit container loses MP_SEND_API_AUTH_FILE (Send API open)
# INV18_SELFTEST_FORMAT=tap renders TAP 13 (for the RED-evidence classifier).
# ===========================================================================
inv18_plant() {
    local arm="$1" root="$2" f before
    case "$arm" in
        no-auth|port-1025|cross-ns-secret) f="$root/components/mailpit/mailpit-ui-ingress.yaml" ;;
        loadbalancer|relay-all|lock-removed) f="$root/components/mailpit/mailpit.yaml" ;;
        *) return 1 ;;
    esac
    [[ -f "$f" ]] || return 1
    before="$(sha256sum "$f")"
    case "$arm" in
        no-auth)
            awk '!/^    nginx\.ingress\.kubernetes\.io\/auth-type:/' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        port-1025)
            awk '{ if ($0 ~ /^              number: 8025$/) sub(/8025/, "1025"); print }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        cross-ns-secret)
            awk '{ if ($0 ~ /^    nginx\.ingress\.kubernetes\.io\/auth-secret: /) $0 = "    nginx.ingress.kubernetes.io/auth-secret: \"traefik/x\""; print }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        loadbalancer)
            awk '{ if ($0 ~ /^  type: ClusterIP$/ && !done) { $0 = "  type: LoadBalancer"; done = 1 } print }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        relay-all)
            awk '{ print } /^        - name: MP_SMTP_REQUIRE_STARTTLS$/ { held = 1; next } held { held = 0; print "        - name: MP_SMTP_RELAY_ALL"; print "          value: \"true\"" }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        lock-removed)
            awk '/^        - name: MP_SEND_API_AUTH_FILE$/ { skip = 1; next } skip { skip = 0; next } { print }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
    esac
    [[ "$(sha256sum "$f")" != "$before" ]]
}

inv18_selftest() {
    local fmt="${INV18_SELFTEST_FORMAT:-plain}" st arm want out rc caught n=0 bad=0 tap=() line
    st="$(mktemp -d)"
    # shellcheck disable=SC2064
    trap "rm -rf '$st'" EXIT
    cp -a "$K8S_DIR" "$st/pristine" || { echo "PARSE ERROR: --selftest-inv18 could not copy $K8S_DIR" >&2; exit 2; }
    local -a ARMS=(no-auth port-1025 cross-ns-secret loadbalancer relay-all lock-removed)
    declare -A WANT=(
        [no-auth]="INV-18: Ingress 'mailpit-ui' routes host 'mail-staging.olajay.co.uk' to Service mailpit without basic auth"
        [port-1025]="INV-18: Ingress 'mailpit-ui' routes host 'mail-staging.olajay.co.uk' to Service mailpit on port 1025"
        [cross-ns-secret]="INV-18: Ingress 'mailpit-ui' auth-secret 'traefik/x'"
        [loadbalancer]="INV-18: Service 'mailpit' selects app=mailpit and is type LoadBalancer"
        [relay-all]="INV-18: Deployment mailpit container 'mailpit' sets MP_SMTP_RELAY_ALL"
        [lock-removed]="INV-18: Deployment mailpit container 'mailpit' does not set MP_SEND_API_AUTH_FILE"
    )
    for arm in "${ARMS[@]}"; do
        n=$((n + 1))
        selftest_stage "$st/pristine" "$st/arm"
        if ! inv18_plant "$arm" "$st/arm/k8s"; then
            caught=no; line="plant did not apply"
        else
            if out=$(bash "$st/arm/k8s/scripts/check-render-invariants.sh" 2>&1); then rc=0; else rc=$?; fi
            want="${WANT[$arm]}"
            if (( rc == 1 )) && grep -qF -- "$want" <<<"$out"; then
                caught=yes; line="rc=1"
            else
                caught=no
                line="expected rc 1 and '$want'; got rc=$rc: $(awk '/INV-18|PARSE ERROR/ && c < 2 { printf "%s ", $0; c++ }' <<<"$out")"
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

if [[ "${1:-}" == "--selftest-inv18" ]]; then
    inv18_selftest; exit $?
fi

# ===========================================================================
# --selftest-inv20 (#976): INV-20 shown CATCHING planted holes in Keycloak's
# proxy-trust configuration. Same harness shape as -inv18. Each arm plants ONE hole
# in a copy (k8s/components/keycloak/keycloak.yaml unless noted) and must make the
# script exit 1 with that arm's message; the unmodified copy must exit 0.
#
# The trusted-addresses setting itself:
#   env-removed        KC_PROXY_TRUSTED_ADDRESSES is deleted (trust every source)
#   optional-ref       its configMapKeyRef becomes optional: true
#   key-renamed        the configMapKeyRef names a key the ConfigMap does not define
#   secret-ref         it comes from a secretKeyRef (cannot be judged)
#   foreign-cm-absent  it reads a ConfigMap (kc-foreign) that is not in the render
#   foreign-cm-bad     it reads a ConfigMap (kc-extra) that IS rendered, holding 0.0.0.0/0
#                      (a ConfigMap other than app-config is judged, not trusted)
#   envfrom-duplicate  a second source of it: an envFrom ConfigMap beside the env item
#                      (a second env ITEM of the same name cannot be planted: kustomize
#                      collapses duplicate env names in the staging render, measured, so
#                      the count is shown on distinct sources here, on -D in
#                      jvm-trusted-override and on repeated flags in flag-repeated)
#   literal-any        the value is the literal 0.0.0.0/0
#   literal-single     the same, single-quoted: '0.0.0.0/0' (judged unquoted)
#   literal-public     the literal public range 8.8.8.0/24
#   literal-empty      the literal empty string
#   whitespace-literal "10.244.0.0/16, 10.0.0.0/8" (26.8.1 refuses it: measured)
#   config-empty       keycloak.proxy-trusted-addresses is "" in k8s/staging
#   config-whitespace  the same key holds a spaced list (k8s/staging)
#   config-comma       the same key ends in a comma (k8s/staging)
# The legacy proxy option:
#   legacy-proxy       KC_PROXY=edge
#   flag-legacy        args --proxy=edge
# JVM system properties (JAVA_OPTS_APPEND):
#   jvm-headers-only   -Dkc.proxy-headers with no trusted addresses anywhere
#   jvm-trusted-any    -Dkc.proxy-trusted-addresses=0.0.0.0/0 as the only source
#   jvm-trusted-override  the same on top of the env (a second source)
#   jvm-secret         JAVA_OPTS_APPEND from a secretKeyRef (cannot be read)
# command / args:
#   flag-headers-only  args --proxy-headers=xforwarded, no trusted addresses
#   flag-trusted-any   args --proxy-trusted-addresses=0.0.0.0/0
#   flag-repeated      that flag twice (0.0.0.0/0, then the good range)
#   flag-bare          --proxy-trusted-addresses with the value as the next item
#   shell-wrapped      command sh -c "exec kc.sh start --proxy-headers=xforwarded"
#   block-scalar       the same script as a | block scalar
# Values kubectl FOLDS onto a continuation line (round 2 of #988: the path walker used
# to emit only the first line, so a word past the fold was never judged):
#   jvm-trusted-override-wrapped  jvm-trusted-override behind a 133-character head
#   shell-wrapped-long            shell-wrapped, the proxy flag past the fold
#   long-harmless                 a long JAVA_OPTS_APPEND with no proxy word: must PASS
#                                 (a control arm: rc 0, not rc 1)
# Values kubectl folds at a space that is followed by `#` (round 3 of #988: the awk path
# walker took the continuation line "#b..." for a comment and cut the value off, rc 0;
# the walker is PyYAML now, k8s/scripts/render_paths.py):
#   hash-after-fold          -Djava.security.egd="file:/dev/./urandom #b" -Dkc.proxy-
#                            trusted-addresses=0.0.0.0/0, folded before the `#`: the
#                            flag past the fold must be judged (a second source)
#   hash-after-fold-harmless the same fold with no proxy word after it: must PASS (control)
# envFrom:
#   envfrom-cm         a rendered ConfigMap supplies KC_PROXY_TRUSTED_ADDRESSES=0.0.0.0/0
#   envfrom-secret     envFrom a Secret
# Which containers are Keycloak's:
#   image-quoted       image: "keycloak:26.8.1" (quoted, bare) and no trusted addresses
#   image-custom       image: .../idp:26 (no keycloak in the name) and none
#   container-by-label a sidecar with no keycloak image and no KC_* env, in the pod
#                      labelled app: keycloak, turning headers on by -D
#   idp-by-kcenv       a second Deployment (not labelled keycloak, image .../idp:1)
#                      whose only Keycloak sign is KC_PROXY_HEADERS
#   idp-by-args        the same, whose only sign is args --proxy-headers=xforwarded
# Unit arms (the unquoting helper and the image finder, called directly, because
# `kubectl kustomize` re-emits '0.0.0.0/0' and "keycloak:26.8.1" UNQUOTED, so a quoted
# value cannot be planted through a render; the helpers are still the ones that decide):
#   unit-unq           unq strips one pair of double or single quotes and nothing else
#   unit-quoted-flag   a single-quoted --proxy-trusted-addresses=... and a double-quoted
#                      --proxy-headers=... item are read as the flags they are
#   unit-quoted-image  a quoted bare image, a single-quoted full image and a custom
#                      image on a labelled pod are found as Keycloak; core-java's is not
#   unit-walker-fold   the path walker, called directly on a ConfigMap whose single-quoted,
#                      double-quoted and flow values are folded at a space before `#`,
#                      returns each value WHOLE, and a tab inside a value stays one field
#   unit-walker-void   the walker exits 2 with a PARSE ERROR, and prints nothing, for a
#                      missing interpreter, a python3 whose PyYAML cannot be imported and
#                      a document YAML rejects (never a silent fallback to a line reader)
# INV20_SELFTEST_FORMAT=tap renders TAP 13 (for the RED-evidence classifier).
# ===========================================================================
# Plant primitives. Text with newlines travels through the environment, never -v.
inv20_p_envdel() {      # <file> <env name>: delete that env item (and its sub-lines)
    awk -v nm="$2" 'skip && /^          / { next } { skip = 0 } $0 == "        - name: " nm { skip = 1; next } { print }' "$1" > "$1.new" && mv "$1.new" "$1"
}
inv20_p_envadd() {      # <file> <text>: insert text as the first env items
    T="$2" awk '{ print } /^        env:$/ && !d { printf "%s", ENVIRON["T"]; d = 1 }' "$1" > "$1.new" && mv "$1.new" "$1"
}
inv20_p_before() {      # <file> <anchor regex> <text>: insert text before the first matching line
    A="$2" T="$3" awk '!d && $0 ~ ENVIRON["A"] { printf "%s", ENVIRON["T"]; d = 1 } { print }' "$1" > "$1.new" && mv "$1.new" "$1"
}
inv20_p_replace() {     # <file> <anchor regex> <text>: replace the first matching line
    A="$2" T="$3" awk '!d && $0 ~ ENVIRON["A"] { printf "%s", ENVIRON["T"]; d = 1; next } { print }' "$1" > "$1.new" && mv "$1.new" "$1"
}
inv20_p_cm() {          # <file> <name> <key> <value>: append a ConfigMap document
    printf -- '---\napiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: %s\ndata:\n  %s: "%s"\n' "$2" "$3" "$4" >> "$1"
}
inv20_p_lit() {         # <file> <value text as written after "value: ">: the trusted env as a literal
    inv20_p_envdel "$1" KC_PROXY_TRUSTED_ADDRESSES && \
    inv20_p_envadd "$1" "        - name: KC_PROXY_TRUSTED_ADDRESSES
          value: $2
"
}
inv20_p_ref() {         # <file> <configmap> <key>: the trusted env from a configMapKeyRef
    inv20_p_envdel "$1" KC_PROXY_TRUSTED_ADDRESSES && \
    inv20_p_envadd "$1" "        - name: KC_PROXY_TRUSTED_ADDRESSES
          valueFrom:
            configMapKeyRef:
              name: $2
              key: $3
"
}
inv20_p_jvm() {         # <file> <value>: a JAVA_OPTS_APPEND literal
    inv20_p_envadd "$1" "        - name: JAVA_OPTS_APPEND
          value: \"$2\"
"
}
inv20_p_jvm_sq() {      # <file> <value>: a JAVA_OPTS_APPEND literal, single-quoted (the value may hold ")
    inv20_p_envadd "$1" "        - name: JAVA_OPTS_APPEND
          value: '$2'
"
}
inv20_p_args() {        # <file> <replacement for the args line>
    inv20_p_replace "$1" '^        args: \["start"\]$' "$2
"
}

# A JAVA_OPTS_APPEND head long enough that kubectl folds the rest of the value onto a
# continuation line (the fold is at about 80 characters; this is 133).
INV20_LONG_PREFIX='-Xms64m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom -Dfoo.bar.baz.qux=aaaaaaaaaaaaaaaaaaaa'
# The reviewer's repro: kubectl folds this exactly before the `#` (verified on the render),
# so the continuation line begins with "#b" and the rest of the value is on it.
INV20_HASH_PREFIX='-Xms64m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Djava.security.egd="file:/dev/./urandom #b"'
INV20_ARMS=(env-removed optional-ref key-renamed secret-ref foreign-cm-absent foreign-cm-bad envfrom-duplicate
    literal-any literal-single literal-public literal-empty whitespace-literal config-empty config-whitespace config-comma
    legacy-proxy flag-legacy jvm-headers-only jvm-trusted-any jvm-trusted-override jvm-secret
    flag-headers-only flag-trusted-any flag-repeated flag-bare shell-wrapped block-scalar
    jvm-trusted-override-wrapped shell-wrapped-long long-harmless hash-after-fold hash-after-fold-harmless
    envfrom-cm envfrom-secret image-quoted image-custom container-by-label idp-by-kcenv idp-by-args)

inv20_plant() {
    local arm="$1" root="$2" f before kc="$2/components/keycloak/keycloak.yaml"
    case "$arm" in
        config-empty|config-whitespace|config-comma) f="$root/staging/configmap-patch.yaml" ;;
        *) f="$kc" ;;
    esac
    [[ -f "$f" ]] || return 1
    before="$(sha256sum "$f")"
    case "$arm" in
        env-removed)      inv20_p_envdel "$f" KC_PROXY_TRUSTED_ADDRESSES ;;
        optional-ref)
            awk '{ print } /^              key: keycloak\.proxy-trusted-addresses$/ { print "              optional: true" }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        key-renamed)      inv20_p_ref "$f" app-config keycloak.proxy-trusted-addresses-renamed ;;
        secret-ref)
            inv20_p_envdel "$f" KC_PROXY_TRUSTED_ADDRESSES && inv20_p_envadd "$f" "        - name: KC_PROXY_TRUSTED_ADDRESSES
          valueFrom:
            secretKeyRef:
              name: kc-proxy-secret
              key: trusted
" ;;
        foreign-cm-absent) inv20_p_ref "$f" kc-foreign proxy ;;
        foreign-cm-bad)    inv20_p_ref "$f" kc-extra proxy && inv20_p_cm "$f" kc-extra proxy "0.0.0.0/0" ;;
        envfrom-duplicate)
            inv20_p_before "$f" '^        env:$' "        envFrom:
        - configMapRef:
            name: kc-extra
" && inv20_p_cm "$f" kc-extra KC_PROXY_TRUSTED_ADDRESSES "10.244.0.0/16" ;;
        literal-any)       inv20_p_lit "$f" '"0.0.0.0/0"' ;;
        literal-single)    inv20_p_lit "$f" "'0.0.0.0/0'" ;;
        literal-public)    inv20_p_lit "$f" '"8.8.8.0/24"' ;;
        literal-empty)     inv20_p_lit "$f" '""' ;;
        whitespace-literal) inv20_p_lit "$f" '"10.244.0.0/16, 10.0.0.0/8"' ;;
        config-empty)      awk '{ if ($0 ~ /^  keycloak\.proxy-trusted-addresses: /) $0 = "  keycloak.proxy-trusted-addresses: \"\""; print }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        config-whitespace) awk '{ if ($0 ~ /^  keycloak\.proxy-trusted-addresses: /) $0 = "  keycloak.proxy-trusted-addresses: \"10.244.0.0/16, 10.0.0.0/8\""; print }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        config-comma)      awk '{ if ($0 ~ /^  keycloak\.proxy-trusted-addresses: /) $0 = "  keycloak.proxy-trusted-addresses: \"10.244.0.0/16,\""; print }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        legacy-proxy)
            awk '{ print } /^          value: "xforwarded"$/ { print "        - name: KC_PROXY"; print "          value: \"edge\"" }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        flag-legacy)       inv20_p_args "$f" '        args: ["start", "--proxy=edge"]' ;;
        jvm-headers-only)  inv20_p_envdel "$f" KC_PROXY_HEADERS && inv20_p_envdel "$f" KC_PROXY_TRUSTED_ADDRESSES && inv20_p_jvm "$f" "-Dkc.proxy-headers=xforwarded" ;;
        jvm-trusted-any)   inv20_p_envdel "$f" KC_PROXY_TRUSTED_ADDRESSES && inv20_p_jvm "$f" "-Xmx1g -Dkc.proxy-trusted-addresses=0.0.0.0/0" ;;
        jvm-trusted-override) inv20_p_jvm "$f" "-Dkc.proxy-trusted-addresses=0.0.0.0/0" ;;
        jvm-secret)
            inv20_p_envadd "$f" "        - name: JAVA_OPTS_APPEND
          valueFrom:
            secretKeyRef:
              name: kc-jvm
              key: opts
" ;;
        flag-headers-only) inv20_p_envdel "$f" KC_PROXY_HEADERS && inv20_p_envdel "$f" KC_PROXY_TRUSTED_ADDRESSES && inv20_p_args "$f" '        args: ["start", "--proxy-headers=xforwarded"]' ;;
        flag-trusted-any)  inv20_p_envdel "$f" KC_PROXY_TRUSTED_ADDRESSES && inv20_p_args "$f" '        args: ["start", "--proxy-trusted-addresses=0.0.0.0/0"]' ;;
        flag-repeated)     inv20_p_envdel "$f" KC_PROXY_TRUSTED_ADDRESSES && inv20_p_args "$f" '        args: ["start", "--proxy-trusted-addresses=0.0.0.0/0", "--proxy-trusted-addresses=10.244.0.0/16"]' ;;
        flag-bare)         inv20_p_envdel "$f" KC_PROXY_TRUSTED_ADDRESSES && inv20_p_args "$f" '        args: ["start", "--proxy-trusted-addresses", "10.244.0.0/16"]' ;;
        shell-wrapped)
            inv20_p_envdel "$f" KC_PROXY_HEADERS && inv20_p_envdel "$f" KC_PROXY_TRUSTED_ADDRESSES && \
            inv20_p_args "$f" '        command: ["/bin/sh", "-c", "exec /opt/keycloak/bin/kc.sh start --proxy-headers=xforwarded"]' ;;
        jvm-trusted-override-wrapped) inv20_p_jvm "$f" "$INV20_LONG_PREFIX -Dkc.proxy-trusted-addresses=0.0.0.0/0" ;;
        shell-wrapped-long)
            inv20_p_envdel "$f" KC_PROXY_HEADERS && inv20_p_envdel "$f" KC_PROXY_TRUSTED_ADDRESSES && \
            inv20_p_args "$f" "        command: [/bin/sh, -c, 'echo starting the keycloak server with a long preamble to push the option past the fold; exec /opt/keycloak/bin/kc.sh start --proxy-trusted-addresses=0.0.0.0/0']" ;;
        long-harmless)     inv20_p_jvm "$f" "$INV20_LONG_PREFIX -Dfoo.bar.baz.quux=bbbbbbbbbbbbbbbbbbbbbbbb" ;;
        hash-after-fold)   inv20_p_jvm_sq "$f" "$INV20_HASH_PREFIX -Dkc.proxy-trusted-addresses=0.0.0.0/0" ;;
        hash-after-fold-harmless) inv20_p_jvm_sq "$f" "$INV20_HASH_PREFIX -Dfoo.bar.baz.quux=bbbbbbbbbbbbbbbbbbbbbbbb" ;;
        block-scalar)
            inv20_p_envdel "$f" KC_PROXY_HEADERS && inv20_p_envdel "$f" KC_PROXY_TRUSTED_ADDRESSES && \
            inv20_p_args "$f" '        command:
        - /bin/sh
        - -c
        - |
          exec /opt/keycloak/bin/kc.sh start --proxy-headers=xforwarded' ;;
        envfrom-cm)
            inv20_p_envdel "$f" KC_PROXY_TRUSTED_ADDRESSES && \
            inv20_p_before "$f" '^        env:$' "        envFrom:
        - configMapRef:
            name: kc-extra
" && inv20_p_cm "$f" kc-extra KC_PROXY_TRUSTED_ADDRESSES "0.0.0.0/0" ;;
        envfrom-secret)
            inv20_p_before "$f" '^        env:$' "        envFrom:
        - secretRef:
            name: kc-secret
" ;;
        image-quoted)      inv20_p_envdel "$f" KC_PROXY_TRUSTED_ADDRESSES && inv20_p_replace "$f" '^        image: quay\.io/keycloak/keycloak' '        image: "keycloak:26.8.1"
' ;;
        image-custom)      inv20_p_envdel "$f" KC_PROXY_TRUSTED_ADDRESSES && inv20_p_replace "$f" '^        image: quay\.io/keycloak/keycloak' '        image: registry.example.io/jtoye/idp:26
' ;;
        container-by-label)
            inv20_p_before "$f" '^      volumes:$' '      - name: idp-sidecar
        image: registry.example.io/jtoye/idp:1
        env:
        - name: JAVA_OPTS_APPEND
          value: "-Dkc.proxy-headers=xforwarded"
' ;;
        idp-by-kcenv)
            printf -- '---\napiVersion: apps/v1\nkind: Deployment\nmetadata:\n  name: idp\nspec:\n  selector:\n    matchLabels:\n      app: idp\n  template:\n    metadata:\n      labels:\n        app: idp\n    spec:\n      containers:\n      - name: idp\n        image: registry.example.io/jtoye/idp:1\n        env:\n        - name: KC_PROXY_HEADERS\n          value: xforwarded\n' >> "$f" ;;
        idp-by-args)
            printf -- '---\napiVersion: apps/v1\nkind: Deployment\nmetadata:\n  name: idp\nspec:\n  selector:\n    matchLabels:\n      app: idp\n  template:\n    metadata:\n      labels:\n        app: idp\n    spec:\n      containers:\n      - name: idp\n        image: registry.example.io/jtoye/idp:1\n        args: ["--proxy-headers=xforwarded"]\n' >> "$f" ;;
        *) return 1 ;;
    esac
    [[ "$(sha256sum "$f")" != "$before" ]]
}

# The unit arms' probe, run in a fresh bash against the script under test.
inv20_unit_probe() {
    local src="$1" pass=1 paths found
    eval "$(sed -n '/^unq() {/,/^}/p; /^INV20_[A-Z_]*_RE=/p; /^inv20_fail()/p; /^inv20_mentions()/p; /^inv20_word() {/,/^}/p; /^inv20_token() {/,/^}/p' "$src")"
    eval "$(sed -n "/^KEYCLOAK_IMAGE_RE=/p; /^KEYCLOAK_CTRS_AWK='/,/^'\$/p" "$src")"
    eval "$(sed -n '/^render_paths() {/,/^}/p' "$src")"
    local RENDER_PATHS_PY="$(dirname "$src")/render_paths.py"
    local rel=unit who20=unit inv20_bad=0
    local -a I20_TV=() I20_TS=()
    local I20_HDR=0 I20_LEGACY=0 I20_UNJ=0
    case "$2" in
        unq)
            unq "'x'"; [[ "$UNQ" == x ]] || pass=0
            unq '"x"'; [[ "$UNQ" == x ]] || pass=0
            unq 'x'; [[ "$UNQ" == x ]] || pass=0
            unq "'x"; [[ "$UNQ" == "'x" ]] || pass=0
            unq "''"; [[ -z "$UNQ" ]] || pass=0 ;;
        quoted-flag)
            inv20_token "'--proxy-trusted-addresses=0.0.0.0/0'" args 2>/dev/null
            [[ "${I20_TV[0]:-}" == "0.0.0.0/0" && ${#I20_TV[@]} -eq 1 ]] || pass=0
            inv20_token '"--proxy-headers=xforwarded"' args 2>/dev/null
            (( I20_HDR == 1 )) || pass=0 ;;
        quoted-image)
            paths="$(printf '%s\t%s\t%s\t%s\n' \
                Deployment d1 'spec::template::spec::containers::[0]::name' c1 \
                Deployment d1 'spec::template::spec::containers::[0]::image' '"keycloak:26.8.1"' \
                Deployment d2 'spec::template::spec::containers::[0]::name' c2 \
                Deployment d2 'spec::template::spec::containers::[0]::image' "'quay.io/keycloak/keycloak:26.8.1'" \
                Deployment d3 'spec::template::spec::containers::[0]::name' c3 \
                Deployment d3 'spec::template::spec::containers::[0]::image' 'jtoye-core-java:1' \
                Deployment d4 'spec::template::metadata::labels::app' 'keycloak' \
                Deployment d4 'spec::template::spec::containers::[0]::name' c4 \
                Deployment d4 'spec::template::spec::containers::[0]::image' 'registry.example.io/idp:1')"
            found="$(awk -F'\t' -v re="$KEYCLOAK_IMAGE_RE" "$KEYCLOAK_CTRS_AWK" <<< "$paths" | sort | cut -f2 | tr '\n' ' ')"
            [[ "$found" == "d1 d2 d4 " ]] || { pass=0; echo "found: '$found'" >&2; } ;;
        walker-fold)
            local tmp want v
            tmp="$(mktemp -d)"
            printf '%s\n' 'apiVersion: v1' 'kind: ConfigMap' 'metadata:' '  name: fold' 'data:' \
                "  single: 'aaaaaaaaaa bbbbbbbbbb cccccccccc dddddddddd eeeeeeeeee ffffffffff gggggggggg \"file:/dev/./urandom" \
                "    #b\" -Dkc.proxy-trusted-addresses=0.0.0.0/0'" \
                '  double: "aaaaaaaaaa bbbbbbbbbb cccccccccc dddddddddd eeeeeeeeee ffffffffff gggggggggg' \
                '    #c tail"' \
                "  flow: [alpha, 'beta" \
                "    #gamma delta']" \
                '  tabbed: "a\tb"' > "$tmp/fold.yaml"
            render_paths "$tmp/fold.yaml" > "$tmp/out.tsv" 2> "$tmp/err" || { pass=0; echo "walker failed: $(cat "$tmp/err")" >&2; }
            want="'aaaaaaaaaa bbbbbbbbbb cccccccccc dddddddddd eeeeeeeeee ffffffffff gggggggggg \"file:/dev/./urandom #b\" -Dkc.proxy-trusted-addresses=0.0.0.0/0'"
            v="$(awk -F'\t' '$3 == "data::single" { print $4 }' "$tmp/out.tsv")"
            [[ "$v" == "$want" ]] || { pass=0; echo "single: '$v'" >&2; }
            want='"aaaaaaaaaa bbbbbbbbbb cccccccccc dddddddddd eeeeeeeeee ffffffffff gggggggggg #c tail"'
            v="$(awk -F'\t' '$3 == "data::double" { print $4 }' "$tmp/out.tsv")"
            [[ "$v" == "$want" ]] || { pass=0; echo "double: '$v'" >&2; }
            v="$(awk -F'\t' '$3 == "data::flow" { print $4 }' "$tmp/out.tsv")"
            [[ "$v" == "[alpha, 'beta #gamma delta']" ]] || { pass=0; echo "flow: '$v'" >&2; }
            v="$(awk -F'\t' '$3 == "data::tabbed" { print $4 }' "$tmp/out.tsv")"
            [[ "$v" == '"a\tb"' ]] || { pass=0; echo "tabbed: '$v'" >&2; }
            [[ "$(awk -F'\t' 'NF != 4' "$tmp/out.tsv" | wc -l)" == 0 ]] || { pass=0; echo "a record is not 4 fields" >&2; }
            rm -rf "$tmp" ;;
        walker-void)
            local tmp out rc
            tmp="$(mktemp -d)"
            printf '%s\n' 'apiVersion: v1' 'kind: ConfigMap' 'metadata:' '  name: ok' > "$tmp/ok.yaml"
            printf '%s\n' 'apiVersion: v1' 'kind: ConfigMap' 'data:' '  a: [unclosed' > "$tmp/bad.yaml"
            printf 'raise ImportError("planted: PyYAML absent")\n' > "$tmp/yaml.py"
            # (1) no interpreter
            out="$(RENDER_PATHS_PYTHON=/nonexistent/python3 render_paths "$tmp/ok.yaml" 2>&1)"; rc=$?
            { (( rc == 2 )) && [[ "$out" == *"PARSE ERROR"* ]]; } || { pass=0; echo "no-interpreter: rc=$rc out='$out'" >&2; }
            # (2) an interpreter that cannot import PyYAML (a planted yaml.py first on the path)
            out="$(PYTHONPATH="$tmp" render_paths "$tmp/ok.yaml" 2>&1)"; rc=$?
            { (( rc == 2 )) && [[ "$out" == *"PARSE ERROR"* && "$out" == *"PyYAML is not importable"* ]]; } || { pass=0; echo "no-pyyaml: rc=$rc out='$out'" >&2; }
            # (3) a document YAML rejects: rc 2, and NOTHING on stdout to be judged
            out="$(render_paths "$tmp/bad.yaml" 2>/dev/null)"; rc=$?
            { (( rc == 2 )) && [[ -z "$out" ]]; } || { pass=0; echo "bad-yaml: rc=$rc stdout='$out'" >&2; }
            # (4) the control: the good document walks and exits 0
            out="$(render_paths "$tmp/ok.yaml" 2>&1)"; rc=$?
            { (( rc == 0 )) && [[ "$out" == *$'ConfigMap\tok\tmetadata::name\tok'* ]]; } || { pass=0; echo "control: rc=$rc out='$out'" >&2; }
            rm -rf "$tmp" ;;
    esac
    (( pass )) 
}

inv20_selftest() {
    local fmt="${INV20_SELFTEST_FORMAT:-plain}" st arm want out rc caught n=0 bad=0 tap=() line
    st="$(mktemp -d)"
    # shellcheck disable=SC2064
    trap "rm -rf '$st'" EXIT
    cp -a "$K8S_DIR" "$st/pristine" || { echo "PARSE ERROR: --selftest-inv20 could not copy $K8S_DIR" >&2; exit 2; }
    local P="INV-20: Deployment keycloak container 'keycloak'"
    local H="$P turns proxy headers on but sets no KC_PROXY_TRUSTED_ADDRESSES"
    local TWO="$P sets the trusted proxy addresses 2 times"
    local ANY="$P KC_PROXY_TRUSTED_ADDRESSES entry '0.0.0.0/0'"
    declare -A WANT=(
        [env-removed]="$H"
        [optional-ref]="$P KC_PROXY_TRUSTED_ADDRESSES reads ConfigMap 'app-config' through an optional configMapKeyRef"
        [key-renamed]="$P KC_PROXY_TRUSTED_ADDRESSES reads ConfigMap 'app-config' key 'keycloak.proxy-trusted-addresses-renamed', which the render does not define"
        [secret-ref]="$P KC_PROXY_TRUSTED_ADDRESSES is not a literal or a configMapKeyRef"
        [foreign-cm-absent]="$P KC_PROXY_TRUSTED_ADDRESSES reads ConfigMap 'kc-foreign', which is not in the render"
        [foreign-cm-bad]="$ANY"
        [envfrom-duplicate]="$TWO"
        [literal-any]="$ANY"
        [literal-single]="$ANY"
        [literal-public]="$P KC_PROXY_TRUSTED_ADDRESSES entry '8.8.8.0/24'"
        [literal-empty]="$P KC_PROXY_TRUSTED_ADDRESSES is empty"
        [whitespace-literal]="$P KC_PROXY_TRUSTED_ADDRESSES contains whitespace in '10.244.0.0/16, 10.0.0.0/8'"
        [config-empty]="$P KC_PROXY_TRUSTED_ADDRESSES is empty (env KC_PROXY_TRUSTED_ADDRESSES from ConfigMap app-config key keycloak.proxy-trusted-addresses)"
        [config-whitespace]="$P KC_PROXY_TRUSTED_ADDRESSES contains whitespace in '10.244.0.0/16, 10.0.0.0/8' (env KC_PROXY_TRUSTED_ADDRESSES from ConfigMap app-config key keycloak.proxy-trusted-addresses)"
        [config-comma]="$P KC_PROXY_TRUSTED_ADDRESSES has an empty entry in '10.244.0.0/16,'"
        [legacy-proxy]="$P sets the legacy KC_PROXY"
        [flag-legacy]="$P sets the legacy KC_PROXY"
        [jvm-headers-only]="$H"
        [jvm-trusted-any]="$ANY"
        [jvm-trusted-override]="$TWO"
        [jvm-secret]="$P JAVA_OPTS_APPEND is not a literal or a configMapKeyRef"
        [flag-headers-only]="$H"
        [flag-trusted-any]="$ANY"
        [flag-repeated]="$TWO"
        [flag-bare]="$P passes --proxy-trusted-addresses without '=value'"
        [shell-wrapped]="$P mentions a proxy option inside a larger string in its command"
        [jvm-trusted-override-wrapped]="$TWO"
        [shell-wrapped-long]="$P mentions a proxy option inside a larger string in its command"
        [long-harmless]=""
        [hash-after-fold]="$TWO"
        [hash-after-fold-harmless]=""
        [block-scalar]="$P has a block scalar ('|') in its command"
        [envfrom-cm]="$ANY"
        [envfrom-secret]="$P has an envFrom Secret 'kc-secret'"
        [image-quoted]="$H"
        [image-custom]="$H"
        [container-by-label]="INV-20: Deployment keycloak container 'idp-sidecar' turns proxy headers on but sets no KC_PROXY_TRUSTED_ADDRESSES"
        [idp-by-kcenv]="INV-20: Deployment idp container 'idp' turns proxy headers on but sets no KC_PROXY_TRUSTED_ADDRESSES"
        [idp-by-args]="INV-20: Deployment idp container 'idp' turns proxy headers on but sets no KC_PROXY_TRUSTED_ADDRESSES"
    )
    for arm in "${INV20_ARMS[@]}"; do
        n=$((n + 1))
        selftest_stage "$st/pristine" "$st/arm"
        if ! inv20_plant "$arm" "$st/arm/k8s"; then
            caught=no; line="plant did not apply"
        else
            if out=$(bash "$st/arm/k8s/scripts/check-render-invariants.sh" 2>&1); then rc=0; else rc=$?; fi
            want="${WANT[$arm]}"
            if [[ -z "$want" ]] && (( rc == 0 )); then
                caught=yes; line="rc=0 (a control: this arm must PASS)"
            elif [[ -n "$want" ]] && (( rc == 1 )) && grep -qF -- "$want" <<<"$out"; then
                caught=yes; line="rc=1"
            else
                caught=no
                line="expected rc 1 and '$want'; got rc=$rc: $(awk '/INV-20|PARSE ERROR/ && c < 2 { printf "%s ", $0; c++ }' <<<"$out")"
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
    for arm in unit-unq unit-quoted-flag unit-quoted-image unit-walker-fold unit-walker-void; do
        n=$((n + 1))
        if line="$(bash -c "$(declare -f inv20_unit_probe); inv20_unit_probe \"\$1\" \"\$2\"" _ "${BASH_SOURCE[0]}" "${arm#unit-}" 2>&1)"; then caught=yes; line="ok"; else caught=no; line="probe failed: $line"; fi
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

if [[ "${1:-}" == "--selftest-inv20" ]]; then
    inv20_selftest; exit $?
fi

# ===========================================================================
# --selftest-inv21 (#968 item 3): INV-21 shown CATCHING planted holes in the
# Grafana edge gate's render. Same harness shape as -inv18. Arms:
#   no-auth            Ingress grafana-public loses auth-type (an open Grafana)
#   cross-ns-secret    its auth-secret becomes traefik/x (a cross-namespace Secret)
#   secret-type        its auth-secret-type becomes auth-map
#   unsynced-secret    its auth-secret is renamed to a Secret no SPC syncs
#   wrong-key          the SPC syncs grafana-edge-credentials under key htpasswd, not auth
#   open-twin          a second Ingress routes another host to Service grafana, no auth
#   no-ingress         the Ingress is deleted (PARSE ERROR, rc 2: a blind reader never passes)
#   svc-lb             Service grafana becomes type LoadBalancer (published with no Ingress at all)
#   lb-twin            a second Service grafana-lb (selector app=grafana) sits behind an
#                      un-annotated Ingress: a route found only by the Service NAME grafana misses it
#   default-backend    an Ingress with no rules reaches Service grafana through
#                      spec.defaultBackend, un-annotated
# INV21_SELFTEST_FORMAT=tap renders TAP 13 (for the RED-evidence classifier).
# ===========================================================================
inv21_plant() {
    local arm="$1" root="$2" f before
    case "$arm" in
        no-auth|cross-ns-secret|secret-type|unsynced-secret|open-twin|no-ingress|svc-lb|lb-twin|default-backend) f="$root/components/monitoring/grafana.yaml" ;;
        wrong-key) f="$root/components/azure-keyvault/secretproviderclasses.yaml" ;;
        *) return 1 ;;
    esac
    [[ -f "$f" ]] || return 1
    before="$(sha256sum "$f")"
    case "$arm" in
        no-auth)
            awk '!/^    nginx\.ingress\.kubernetes\.io\/auth-type:/' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        cross-ns-secret)
            awk '{ if ($0 ~ /^    nginx\.ingress\.kubernetes\.io\/auth-secret: /) $0 = "    nginx.ingress.kubernetes.io/auth-secret: \"traefik/x\""; print }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        secret-type)
            awk '{ if ($0 ~ /^    nginx\.ingress\.kubernetes\.io\/auth-secret-type: /) $0 = "    nginx.ingress.kubernetes.io/auth-secret-type: \"auth-map\""; print }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        unsynced-secret)
            awk '{ if ($0 ~ /^    nginx\.ingress\.kubernetes\.io\/auth-secret: /) $0 = "    nginx.ingress.kubernetes.io/auth-secret: \"grafana-edge-planted\""; print }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        wrong-key)
            # inside the grafana-edge-kv document only: the first `- key: auth` becomes `- key: htpasswd`
            awk '/^---$/ { inkv = 0 } /^  name: grafana-edge-kv$/ { inkv = 1 } { if (inkv && !done && $0 == "    - key: auth") { $0 = "    - key: htpasswd"; done = 1 } print }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        open-twin)
            cat >> "$f" <<'TWIN'
---
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: grafana-open
  labels:
    app: grafana
spec:
  ingressClassName: nginx
  rules:
  - host: grafana-open.olajay.co.uk
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: grafana
            port:
              number: 3000
TWIN
            ;;
        no-ingress)
            # drop the whole `kind: Ingress` document named grafana-public
            awk 'function flush() { if (buf != "" && !(buf ~ /\n  name: grafana-public\n/ && buf ~ /\nkind: Ingress\n/)) printf "%s", buf; buf = "" }
                 /^---$/ { flush() } { buf = buf "\n" $0 "\n"; sub(/^\n/, "", buf) } END { flush() }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        svc-lb)
            # inside the Service document only: type ClusterIP becomes LoadBalancer
            awk '/^---$/ { insvc = 0 } /^kind: Service$/ { insvc = 1 } { if (insvc && $0 == "  type: ClusterIP") $0 = "  type: LoadBalancer"; print }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        lb-twin)
            cat >> "$f" <<'TWIN'
---
apiVersion: v1
kind: Service
metadata:
  name: grafana-lb
  labels:
    app: grafana
spec:
  type: ClusterIP
  selector:
    app: grafana
  ports:
  - port: 3000
    targetPort: http
    protocol: TCP
    name: http
---
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: grafana-lb-open
  labels:
    app: grafana
spec:
  ingressClassName: nginx
  rules:
  - host: grafana-lb.olajay.co.uk
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: grafana-lb
            port:
              number: 3000
TWIN
            ;;
        default-backend)
            cat >> "$f" <<'TWIN'
---
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: grafana-default
  labels:
    app: grafana
spec:
  ingressClassName: nginx
  defaultBackend:
    service:
      name: grafana
      port:
        number: 3000
TWIN
            ;;
    esac
    [[ "$(sha256sum "$f")" != "$before" ]]
}

inv21_selftest() {
    local fmt="${INV21_SELFTEST_FORMAT:-plain}" st arm want wrc out rc caught n=0 bad=0 tap=() line
    st="$(mktemp -d)"
    # shellcheck disable=SC2064
    trap "rm -rf '$st'" EXIT
    cp -a "$K8S_DIR" "$st/pristine" || { echo "PARSE ERROR: --selftest-inv21 could not copy $K8S_DIR" >&2; exit 2; }
    local -a ARMS=(no-auth cross-ns-secret secret-type unsynced-secret wrong-key open-twin no-ingress svc-lb lb-twin default-backend)
    declare -A WANT=(
        [no-auth]="INV-21: Ingress 'grafana-public' routes host 'grafana-staging.olajay.co.uk' to Service grafana without basic auth"
        [cross-ns-secret]="INV-21: Ingress 'grafana-public' auth-secret 'traefik/x'"
        [secret-type]="INV-21: Ingress 'grafana-public' auth-secret-type 'auth-map'"
        [unsynced-secret]="INV-21: Ingress 'grafana-public' auth-secret 'grafana-edge-planted' is not synced as key 'auth' by any SecretProviderClass"
        [wrong-key]="INV-21: Ingress 'grafana-public' auth-secret 'grafana-edge-credentials' is not synced as key 'auth' by any SecretProviderClass"
        [open-twin]="INV-21: Ingress 'grafana-open' routes host 'grafana-open.olajay.co.uk' to Service grafana without basic auth"
        [no-ingress]="PARSE ERROR: [k8s/staging] INV-21 found 0 Ingress backends to Service grafana"
        [svc-lb]="INV-21: Service 'grafana' reaches Grafana's pod and is type LoadBalancer"
        [lb-twin]="INV-21: Ingress 'grafana-lb-open' routes host 'grafana-lb.olajay.co.uk' to Service grafana-lb without basic auth"
        [default-backend]="INV-21: Ingress 'grafana-default' routes host '(defaultBackend: every host)' to Service grafana without basic auth"
    )
    declare -A WANT_RC=([no-ingress]=2)
    for arm in "${ARMS[@]}"; do
        n=$((n + 1))
        selftest_stage "$st/pristine" "$st/arm"
        if ! inv21_plant "$arm" "$st/arm/k8s"; then
            caught=no; line="plant did not apply"
        else
            if out=$(bash "$st/arm/k8s/scripts/check-render-invariants.sh" 2>&1); then rc=0; else rc=$?; fi
            want="${WANT[$arm]}"; wrc="${WANT_RC[$arm]:-1}"
            if (( rc == wrc )) && grep -qF -- "$want" <<<"$out"; then
                caught=yes; line="rc=$rc"
            else
                caught=no
                line="expected rc $wrc and '$want'; got rc=$rc: $(awk '/INV-21|PARSE ERROR/ && c < 2 { printf "%s ", $0; c++ }' <<<"$out")"
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

if [[ "${1:-}" == "--selftest-inv21" ]]; then
    inv21_selftest; exit $?
fi

# ===========================================================================
# --selftest-inv22 (#999): INV-22 shown CATCHING planted holes. Same harness shape as
# --selftest-inv17/-inv21: the k8s/ tree and core-java's application*.yml are copied
# (selftest_stage), ONE hole is planted per arm, and the COPY's own gate must exit with the
# arm's rc AND print the message that names the hole. The unmodified copy must exit 0.
# Arms:
#   env-removed       the staging overlay stops patching the env in (the regression the
#                     issue describes: staging is labelled production again)
#   wrong-value       the env is set to `production`
#   empty-value       the env is set to ""
#   misspelt-name     MANAGEMENT_METRICS_TAG_ENVIRONMENT: binds nothing, so it counts as unset
#   set-twice         two spellings of the same env on one container
#   unreadable-source the value comes from a Secret the render cannot read
#   production-bleed  base sets it to `staging`, which production inherits (a production
#                     core-java may leave the profile's value, never override it with another)
#   unlisted-overlay  a new overlay (k8s/zz-extra, on base) that INV22_EXPECTED does not name
#                     (review round 1 F3: it ran the prod profile with no override and passed as
#                     "not applicable")
#   local-env-removed k8s/local stops patching the env in
#   prod-key-moved    application-prod.yml no longer sets the tag it overrides (PARSE ERROR, rc 2)
# A plant that does not change its file is reported caught=no.
# INV22_SELFTEST_FORMAT=tap renders TAP 13 (for the RED-evidence classifier).
# ===========================================================================
inv22_plant() {
    local arm="$1" root="$2" f before
    case "$arm" in
        env-removed) f="$root/k8s/staging/kustomization.yaml" ;;
        wrong-value|empty-value|misspelt-name|set-twice|unreadable-source) f="$root/k8s/staging/core-java-metrics-env-patch.yaml" ;;
        production-bleed) f="$root/k8s/base/core-java-deployment.yaml" ;;
        prod-key-moved) f="$root/core-java/src/main/resources/application-prod.yml" ;;
        local-env-removed) f="$root/k8s/local/kustomization.yaml" ;;
        unlisted-overlay) mkdir -p "$root/k8s/zz-extra" && printf 'apiVersion: kustomize.config.k8s.io/v1beta1\nkind: Kustomization\nresources:\n  - ../base\n' > "$root/k8s/zz-extra/kustomization.yaml"; return 0 ;;
        *) return 1 ;;
    esac
    [[ -f "$f" ]] || return 1
    before="$(sha256sum "$f")"
    case "$arm" in
        local-env-removed)
            awk '!/^  - path: core-java-metrics-env-patch\.yaml$/' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        env-removed)
            awk '!/^  - path: core-java-metrics-env-patch\.yaml$/' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        wrong-value)
            awk '{ sub(/value: "staging"/, "value: \"production\""); print }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        empty-value)
            awk '{ sub(/value: "staging"/, "value: \"\""); print }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        misspelt-name)
            awk '{ sub(/MANAGEMENT_METRICS_TAGS_ENVIRONMENT/, "MANAGEMENT_METRICS_TAG_ENVIRONMENT"); print }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        set-twice)
            awk '{ print } /value: "staging"$/ { print "            - name: management-metrics-tags-environment"; print "              value: \"staging\"" }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        unreadable-source)
            awk '/value: "staging"$/ { print "              valueFrom:"; print "                secretKeyRef:"; print "                  name: planted"; print "                  key: planted"; next } { print }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        production-bleed)
            awk '{ print } !done && /^          value: "prod"$/ { print "        - name: MANAGEMENT_METRICS_TAGS_ENVIRONMENT"; print "          value: \"staging\""; done = 1 }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        prod-key-moved)
            awk '{ sub(/^      environment: production$/, "      env: production"); print }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
    esac
    [[ "$(sha256sum "$f")" != "$before" ]]
}

inv22_selftest() {
    local fmt="${INV22_SELFTEST_FORMAT:-plain}" st arm want wrc out rc caught n=0 bad=0 tap=() line
    st="$(mktemp -d)"
    # shellcheck disable=SC2064
    trap "rm -rf '$st'" EXIT
    cp -a "$K8S_DIR" "$st/pristine" || { echo "PARSE ERROR: --selftest-inv22 could not copy $K8S_DIR" >&2; exit 2; }
    local -a ARMS=(env-removed wrong-value empty-value misspelt-name set-twice unreadable-source production-bleed prod-key-moved unlisted-overlay local-env-removed)
    declare -A WANT=(
        [env-removed]="INV-22: Deployment core-java container 'core-java' does not set MANAGEMENT_METRICS_TAGS_ENVIRONMENT"
        [wrong-value]="INV-22: Deployment core-java container 'core-java' sets MANAGEMENT_METRICS_TAGS_ENVIRONMENT to 'production', not 'staging'"
        [empty-value]="INV-22: Deployment core-java container 'core-java' sets MANAGEMENT_METRICS_TAGS_ENVIRONMENT to '', not 'staging'"
        [misspelt-name]="INV-22: Deployment core-java container 'core-java' does not set MANAGEMENT_METRICS_TAGS_ENVIRONMENT"
        [set-twice]="INV-22: Deployment core-java container 'core-java' sets MANAGEMENT_METRICS_TAGS_ENVIRONMENT 2 times"
        [unreadable-source]="INV-22: Deployment core-java container 'core-java' sets MANAGEMENT_METRICS_TAGS_ENVIRONMENT from a source the render cannot read"
        [production-bleed]="FAIL [k8s/production] INV-22: Deployment core-java container 'core-java' sets MANAGEMENT_METRICS_TAGS_ENVIRONMENT to 'staging', not 'production'"
        [unlisted-overlay]="INV-22: target k8s/zz-extra is not declared in INV22_EXPECTED"
        [local-env-removed]="FAIL [k8s/local] INV-22: Deployment core-java container 'core-java' does not set MANAGEMENT_METRICS_TAGS_ENVIRONMENT"
        [prod-key-moved]="INV-22: core-java/src/main/resources/application-prod.yml no longer sets management.metrics.tags.environment"
    )
    declare -A WANT_RC=([prod-key-moved]=2)
    for arm in "${ARMS[@]}"; do
        n=$((n + 1))
        selftest_stage "$st/pristine" "$st/arm"
        if ! inv22_plant "$arm" "$st/arm"; then
            caught=no; line="plant did not apply"
        else
            if out=$(bash "$st/arm/k8s/scripts/check-render-invariants.sh" 2>&1); then rc=0; else rc=$?; fi
            want="${WANT[$arm]}"; wrc="${WANT_RC[$arm]:-1}"
            if (( rc == wrc )) && grep -qF -- "$want" <<<"$out"; then
                caught=yes; line="rc=$rc"
            else
                caught=no
                line="expected rc $wrc and '$want'; got rc=$rc: $(awk '/INV-22|PARSE ERROR/ && c < 2 { printf "%s ", $0; c++ }' <<<"$out")"
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

if [[ "${1:-}" == "--selftest-inv22" ]]; then
    inv22_selftest; exit $?
fi

# ===========================================================================
# --selftest-inv23 (issue #972): INV-23 shown CATCHING planted holes in Mailpit's
# certificate-reload supervisor. Same harness shape as -inv18. Arms (each in the copy's
# k8s/components/mailpit/mailpit.yaml):
#   no-supervisor   the mailpit container's whole `command:` block is deleted (Mailpit runs
#                   as the image entrypoint and never sees a renewal)
#   exec-direct     the supervisor ends in `exec /mailpit` instead of running it as a child
#   wrong-file      the hash covers /etc/hostname, not the files MP_SMTP_TLS_CERT/KEY name
#   no-compare      the comparison is replaced by `[ false ]` (it hashes but never reacts)
#   no-stop         the stop (`kill -TERM "$pid"`) is replaced by `true`
#   starttls-off    MP_SMTP_REQUIRE_STARTTLS becomes "false"
#   sidecar-markers a SIDECAR container carries every supervisor marker (hash, background run,
#                   comparison, stops, trap) while the mailpit container has no command at all:
#                   the markers must be read from the mailpit container only
#   exec-quoted     the supervisor ends in `exec "/mailpit"` (quoted) instead of running it as a child
# INV23_SELFTEST_FORMAT=tap renders TAP 13 (for the RED-evidence classifier).
# ===========================================================================
inv23_plant() {
    local arm="$1" root="$2" f before
    f="$root/components/mailpit/mailpit.yaml"
    [[ -f "$f" ]] || return 1
    before="$(sha256sum "$f")"
    case "$arm" in
        no-supervisor)
            # the SECOND `        command:` (the first is the send-api-lock init container): drop it
            # and its block scalar (every following line indented deeper than the key)
            awk '/^        command:$/ { n++; if (n == 2) { skip = 1; next } }
                 skip { if ($0 ~ /^        - / || $0 ~ /^          /) next; skip = 0 }
                 { print }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        exec-direct)
            awk '{ if ($0 ~ /^          \/mailpit &$/) $0 = "          exec /mailpit"; print }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        wrong-file)
            awk '{ if ($0 ~ /^          fp\(\) \{ sha256sum /) $0 = "          fp() { sha256sum /etc/hostname 2>/dev/null; }"; print }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        no-compare)
            awk '{ if ($0 ~ /^            if \[ "\$now" != "\$start" \]; then$/) $0 = "            if [ false ]; then"; print }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        no-stop)
            awk '{ if ($0 ~ /^              kill -TERM "\$pid" 2>\/dev\/null$/) $0 = "              true"; print }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        starttls-off)
            awk '/^        - name: MP_SMTP_REQUIRE_STARTTLS$/ { print; getline; print "          value: \"false\""; next } { print }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        exec-quoted)
            awk '{ if ($0 ~ /^          \/mailpit &$/) $0 = "          exec \"/mailpit\""; print }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        sidecar-markers)
            # drop the mailpit container's command block (as no-supervisor) and put every marker in a
            # sidecar listed BEFORE the mailpit container
            awk '/^        command:$/ { n++; if (n == 2) { skip = 1; next } }
                 skip { if ($0 ~ /^        - / || $0 ~ /^          /) next; skip = 0 }
                 /^      - name: mailpit$/ && !done {
                     print "      - name: sidecar"
                     print "        image: axllent/mailpit:v1.31.6"
                     print "        command:"
                     print "        - sh"
                     print "        - -c"
                     print "        - |"
                     print "          fp() { sha256sum \"$MP_SMTP_TLS_CERT\" \"$MP_SMTP_TLS_KEY\" 2>/dev/null; }"
                     print "          /mailpit &"
                     print "          trap '\''kill -TERM \"$pid\"'\'' TERM"
                     print "          if [ \"$now\" != \"$start\" ]; then kill -TERM \"$pid\"; fi"
                     done = 1
                 }
                 { print }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
        *) return 1 ;;
    esac
    [[ "$(sha256sum "$f")" != "$before" ]]
}

inv23_selftest() {
    local fmt="${INV23_SELFTEST_FORMAT:-plain}" st arm want out rc caught n=0 bad=0 tap=() line
    st="$(mktemp -d)"
    # shellcheck disable=SC2064
    trap "rm -rf '$st'" EXIT
    cp -a "$K8S_DIR" "$st/pristine" || { echo "PARSE ERROR: --selftest-inv23 could not copy $K8S_DIR" >&2; exit 2; }
    local -a ARMS=(no-supervisor exec-direct wrong-file no-compare no-stop starttls-off sidecar-markers exec-quoted)
    declare -A WANT=(
        [no-supervisor]="INV-23: Deployment mailpit container 'mailpit' has no certificate-reload supervisor"
        [exec-direct]="INV-23: Deployment mailpit container 'mailpit' execs Mailpit"
        [wrong-file]="INV-23: Deployment mailpit container 'mailpit' does not hash the files MP_SMTP_TLS_CERT and MP_SMTP_TLS_KEY name"
        [no-compare]="INV-23: Deployment mailpit container 'mailpit' never compares the current hash with the start hash"
        [no-stop]="INV-23: Deployment mailpit container 'mailpit' never stops Mailpit on a change"
        [starttls-off]="INV-23: Deployment mailpit container 'mailpit' MP_SMTP_REQUIRE_STARTTLS is not the literal true"
        [sidecar-markers]="INV-23: Deployment mailpit container 'mailpit' has no certificate-reload supervisor"
        [exec-quoted]="INV-23: Deployment mailpit container 'mailpit' execs Mailpit"
    )
    for arm in "${ARMS[@]}"; do
        n=$((n + 1))
        selftest_stage "$st/pristine" "$st/arm"
        if ! inv23_plant "$arm" "$st/arm/k8s"; then
            caught=no; line="plant did not apply"
        else
            if out=$(bash "$st/arm/k8s/scripts/check-render-invariants.sh" 2>&1); then rc=0; else rc=$?; fi
            want="${WANT[$arm]}"
            if (( rc == 1 )) && grep -qF -- "$want" <<<"$out"; then
                caught=yes; line="rc=1"
            else
                caught=no
                line="expected rc 1 and '$want'; got rc=$rc: $(awk '/INV-23|PARSE ERROR/ && c < 2 { printf "%s ", $0; c++ }' <<<"$out")"
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

if [[ "${1:-}" == "--selftest-inv23" ]]; then
    inv23_selftest; exit $?
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
  # The base placeholder STAYS (plan 29-23): base, production and local ship no monitoring.
  # The staging monitoring policies come from ../components/monitoring, rows below.
  [observability-placeholder@*]="sel:app=nonexistent-placeholder types:Egress,Ingress"
  # Staging-only platform components (plan 29-10, k8s/components/<name>/, listed
  # under `components:` in k8s/staging only). The manifest names the controller
  # namespace as a literal `traefik`; the row says __INGRESS_NS__, so a staging
  # app-config ingress.controller-namespace that stops agreeing with that literal
  # FAILS here. egress.1 follows db.port through the staging replacement.
  [keycloak-allow@k8s/staging]="sel:app=keycloak types:Egress,Ingress
      in:ns=__INGRESS_NS__:8080 in:pod=app=frontend:8080 in:pod=app=core-java:8080 in:pod=app=edge-go:8080
      in:pod=app=prometheus:9000
      $NETPOL_DNS_EG eg:ip=0.0.0.0/0:__DB_PORT__ eg:pod=app=mailpit:1025"
  [redis-allow@k8s/staging]="sel:app=redis types:Egress,Ingress
      in:pod=app=core-java:6379 in:pod=app=redis-exporter:6379
      $NETPOL_DNS_EG"
  # The broker pods carry the operator-set label app.kubernetes.io/name=rabbitmq. The
  # public 443 egress is for Kubernetes peer discovery, an assumption plan 29-14
  # measures (see k8s/components/rabbitmq/rabbitmq-cluster.yaml).
  [rabbitmq-allow@k8s/staging]="sel:app.kubernetes.io/name=rabbitmq types:Egress,Ingress
      in:pod=app=core-java:5672 in:pod=app=core-java:61613 in:pod=app=prometheus:15692
      in:ns=rabbitmq-system:15672
      in:pod=app.kubernetes.io/name=rabbitmq:4369 in:pod=app.kubernetes.io/name=rabbitmq:25672
      $NETPOL_DNS_EG eg:ip=0.0.0.0/0:443"
  # The always-on consumer of rabbitmq-kv (plan 29-12, ../components/azure-keyvault).
  # Nothing in, nothing out: the Key Vault fetch is the CSI provider's, on the node.
  [secret-sync-allow@k8s/staging]="sel:app=secret-sync types:Egress,Ingress"
  # The staging mail trap (plan 29-22, ../components/mailpit). SMTP 1025 from the
  # three senders only; the controller namespace on 8025 only, for the D-33
  # authenticated testers' UI (Ingress mailpit-ui; INV-18 refuses a route to 1025);
  # no egress at all (Mailpit relays and forwards nothing).
  [mailpit-allow@k8s/staging]="sel:app=mailpit types:Egress,Ingress
      in:pod=app=core-java:1025 in:pod=app=keycloak:1025 in:pod=app=alertmanager:1025
      in:ns=__INGRESS_NS__:8025"
  # The staging monitoring (plan 29-23, ../components/monitoring, D-16/D-19). Prometheus is
  # read only by Grafana (plan 29-24) and has no Ingress; it reaches each scrape target on its
  # metrics port (the target's own policy admits app=prometheus there) and Alertmanager.
  [prometheus-allow@k8s/staging]="sel:app=prometheus types:Egress,Ingress
      in:pod=app=grafana:9090
      $NETPOL_DNS_EG eg:pod=app=core-java:9091 eg:pod=app=edge-go:8080 eg:pod=app=keycloak:9000
      eg:pod=app.kubernetes.io/name=rabbitmq:15692 eg:pod=app=postgres-exporter:9187
      eg:pod=app=redis-exporter:9121 eg:pod=app=alertmanager:9093
      eg:nspod=cert-manager/app.kubernetes.io/name=cert-manager:9402"
  # The two exporters: scraped only by Prometheus. postgres-exporter's egress.1 is the
  # managed server on db.port (the staging replacement, #271); redis-exporter reaches only
  # the in-cluster Redis.
  [postgres-exporter-allow@k8s/staging]="sel:app=postgres-exporter types:Egress,Ingress
      in:pod=app=prometheus:9187
      $NETPOL_DNS_EG eg:ip=0.0.0.0/0:__DB_PORT__"
  [redis-exporter-allow@k8s/staging]="sel:app=redis-exporter types:Egress,Ingress
      in:pod=app=prometheus:9121
      $NETPOL_DNS_EG eg:pod=app=redis:6379"
  # Alert routing and dashboards (plan 29-24, ../components/monitoring, D-17/D-19). Alertmanager
  # is reached by Prometheus and core-java on 9093 (no Ingress); it sends to Zoho on the public
  # range on 587 only (STARTTLS submission) and to Mailpit on 1025 (mailpit-allow admits it).
  # Grafana is the ONE public monitoring host: the controller namespace on 3000, and it reads
  # only Prometheus (prometheus-allow admits app=grafana on 9090).
  [alertmanager-allow@k8s/staging]="sel:app=alertmanager types:Egress,Ingress
      in:pod=app=prometheus:9093 in:pod=app=core-java:9093
      $NETPOL_DNS_EG eg:ip=0.0.0.0/0:587 eg:pod=app=mailpit:1025"
  [grafana-allow@k8s/staging]="sel:app=grafana types:Egress,Ingress
      in:ns=__INGRESS_NS__:3000
      $NETPOL_DNS_EG eg:pod=app=prometheus:9090"
)

# INV-7 arm 3: the ONLY ipBlock shapes a policy may render, as
# `<in|eg>|<cidr>|<except list, sorted, comma-joined>` -> the ports allowed on it.
# One shape today: the RFC1918-excluding public range, egress, on 443 (Keycloak's
# public host, Stripe, CDNs, Azure Blob, Entra ID) and on db.port (the managed
# Postgres public endpoint, Blocker D, accepted as T-29-05). A private endpoint
# (36-PHASE29-HANDOFF.md §5) would be a NEW, scoped row here, reviewed as a
# NetworkPolicy change. Bolting another port onto the public shape, narrowing its
# except list, or any ingress ipBlock FAILS.
# Plan 29-24 adds 587 to the same shape for ONE policy: Alertmanager's SMTP submission to Zoho
# (smtp.zoho.eu, STARTTLS required by the config). Which policy may use which port is still the
# row's business above; this table only bounds the ports the public shape may ever carry.
declare -A NETPOL_IPBLOCK_EXPECTED=(
  ["eg|0.0.0.0/0|10.0.0.0/8,172.16.0.0/12,192.168.0.0/16"]="443 __DB_PORT__ 587"
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
# INV-10 (plan 29-12): a pod template that mounts a SecretProviderClass through this
# driver is ALSO allowed to opt in, and must; the set is derived from the render.
CSI_SECRETS_DRIVER="secrets-store.csi.k8s.io"
WI_CLIENT_ID_KEY="azure.workload.identity/client-id"
GUID_RE='^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'

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
# confidential (the frontend presents frontend-oidc-credentials/client-secret, plan 29-12).
REALM_LOGIN_CLIENTS=" $(awk -F'\t' -v b="$KC_BUILTIN_CLIENTS" 'index(b, " " $1 " ") == 0 && $2 == "true" && $3 == "true" && $4 == "false" && $5 == "false" { printf "%s ", $1 }' <<< "$REALM_CLIENTS_TSV")"
[[ -n "${REALM_LOGIN_CLIENTS// /}" ]] || parse_fail "INV-14: the realm template has no client a vendor login could use (non-built-in, enabled, standard flow, confidential). Either the template lost core-api or the filter is blind."

# INV-16 (Phase 29 plan 29-22, D-30): the targets that MUST build core-java's
# truststore from the JDK cacerts plus the cluster CA. Every entry must be FOUND
# among the discovered targets (checked after the loop), as for WI_TARGETS.
TRUSTSTORE_TARGETS=("k8s/staging")
declare -A TRUSTSTORE_TARGET_SEEN=()
# INV-20 (#976): the targets that MUST render a Keycloak container (its absence
# there is a PARSE ERROR, not "not applicable", so a renamed component cannot make
# the proxy-trust check silently stop running). A container IS Keycloak's when ANY of
#   - its image, unquoted, names keycloak (quay.io/keycloak/keycloak:26.8.1,
#     keycloak:26.8.1, acr.example/jtoye-keycloak:26, a custom or optimized build);
#   - it sets one of Keycloak's own server envs (KC_PROXY*, KC_HOSTNAME*, KC_HTTP*,
#     KC_DB / KC_DB_*, KC_BOOTSTRAP_*, KC_CACHE*, KC_HEALTH_*, KC_METRICS_*, KC_FEATURES*:
#     a build with no keycloak in its name still needs them; core-java's own KC_ADMIN_*,
#     KC_ISSUER_URI and KC_*_REALM are NOT Keycloak's), or its command/args name kc.sh,
#     keycloak or a --proxy option;
#   - its pod template is labelled app: keycloak (every container in that pod).
# The first version matched the RAW image value with '(^|/)keycloak(:|@|$)', so a
# quoted bare image or a renamed build was reported "not applicable" on any target
# that is not in KEYCLOAK_TARGETS.
KEYCLOAK_TARGETS=("k8s/staging")
declare -A KEYCLOAK_TARGET_SEEN=()
KEYCLOAK_IMAGE_RE='keycloak'
# Output: <kind> TAB <name> TAB <container path>.
KEYCLOAK_CTRS_AWK='
function unq(v) { if (v ~ /^".*"$/ || v ~ /^\047.*\047$/) v = substr(v, 2, length(v) - 2); return v }
{
    if ($3 ~ /::(containers|initContainers)::\[[0-9]+\]::name$/) { c = $3; sub(/::name$/, "", c); allc[$1 "\t" $2 "\t" c] = 1 }
    if ($3 ~ /::(containers|initContainers)::\[[0-9]+\]::image$/ && tolower(unq($4)) ~ re) { c = $3; sub(/::image$/, "", c); hit[$1 "\t" $2 "\t" c] = 1 }
    if ($3 ~ /::(containers|initContainers)::\[[0-9]+\]::env::\[[0-9]+\]::name$/ && unq($4) ~ /^KC_(PROXY|HOSTNAME|HTTP|DB($|_)|BOOTSTRAP|CACHE|HEALTH|METRICS|FEATURES)/) { c = $3; sub(/::env::.*$/, "", c); hit[$1 "\t" $2 "\t" c] = 1 }
    if ($3 ~ /::(containers|initContainers)::\[[0-9]+\]::(args|command)(::\[[0-9]+\])?$/ && tolower($4) ~ /--proxy|kc[._]proxy|kc\.sh|keycloak/) { c = $3; sub(/::(args|command)(::.*)?$/, "", c); hit[$1 "\t" $2 "\t" c] = 1 }
    if ($3 ~ /(^|::)template::metadata::labels::app$/ && unq($4) == "keycloak") { p = $3; sub(/metadata::labels::app$/, "", p); lab[$1 "\t" $2 "\t" p] = 1 }
}
END {
    for (k in allc) {
        keep = (k in hit)
        if (!keep) for (l in lab) {
            split(k, a, "\t"); split(l, b, "\t")
            if (a[1] == b[1] && a[2] == b[2] && index(a[3], b[3] "spec::") == 1) keep = 1
        }
        if (keep) print k
    }
}
'
TRUSTSTORE_PATH="/truststore/cacerts"
# INIT_ITEMS_AWK: one record per initContainers item of Deployment <want>, read
# from the RAW render because the builder's command is a block scalar:
#   INIT <TAB> <index> <TAB> <name> <TAB> <image> <TAB> <cp line> <TAB> <import line>
# <cp line> is the 1-based line (within the item) of the first uncommented line
# that starts `cp`, names lib/security/cacerts and the trust store path; <import
# line> the first uncommented line running keytool ... -importcert. 0 = none.
# kustomize emits a sequence at its parent key's indent, so items are `      - `
# under `      initContainers:` (Deployment spec.template.spec) and an item's own
# keys sit at 8 spaces; sorted keys put `name:` after `command:`, which is why the
# whole item is collected before it is judged.
INIT_ITEMS_AWK='
function probe(l) {
    if (l ~ /^        name: /)  { nm2 = l; sub(/^        name:[[:space:]]*/, "", nm2) }
    if (l ~ /^        image: /) { img = l; sub(/^        image:[[:space:]]*/, "", img) }
}
function flush_item(   n, i, L, t, cpl, impl) {
    if (!open) return
    n = split(text, L, "\n"); cpl = 0; impl = 0
    for (i = 1; i <= n; i++) {
        t = L[i]; sub(/^[[:space:]]+/, "", t); sub(/^-[[:space:]]+/, "", t)
        if (t ~ /^#/) continue
        if (!cpl && t ~ /^cp[[:space:]]/ && index(t, "lib/security/cacerts") && index(t, tsp)) cpl = i
        if (!impl && t ~ /(^|[[:space:]])keytool[[:space:]].*-importcert/) impl = i
    }
    printf "INIT\t%d\t%s\t%s\t%d\t%d\n", idx, nm2, img, cpl, impl
    open = 0; text = ""; nm2 = ""; img = ""
}
/^---[[:space:]]*$/ { flush_item(); insec = 0; kind = ""; dn = ""; top = ""; next }
/^kind: / { kind = $2 }
/^[^ ]/ { top = $0; sub(/:.*$/, "", top) }
top == "metadata" && /^  name: / { dn = $0; sub(/^  name:[[:space:]]*/, "", dn) }
kind == "Deployment" && dn == want && /^      initContainers:[[:space:]]*$/ { insec = 1; idx = -1; next }
insec {
    if ($0 ~ /^      - /) { flush_item(); idx++; open = 1; text = substr($0, 9) "\n"; probe("        " substr($0, 9)); next }
    if ($0 ~ /^        / || $0 ~ /^[[:space:]]*$/) { if (open) { text = text $0 "\n"; probe($0) }; next }
    flush_item(); insec = 0
}
END { flush_item() }
'

# INV-17 (Phase 29 plan 29-26, #859 / T-29-95): the customer realm's
# anti-impersonation gate. jtoye.security.customer-jwt.require-verified-email
# must be true wherever core-java is deployed. Its inputs: application.yml's
# default (read here, once), the per-profile application-<p>.yml overrides, and
# the render's env. Spring's relaxed env binding means any env name whose
# lower-cased alphanumerics end in INV17_ENV_SUFFIX binds the property.
APP_RESOURCES="$REPO_ROOT/core-java/src/main/resources"
APP_YML="$APP_RESOURCES/application.yml"
INV17_PROP_RE='^[[:space:]]*require[-_.]?verified[-_.]?email[[:space:]]*:'
INV17_ENV_SUFFIX='requireverifiedemail'
# inv17_yml_value <file> — the value <file> gives the property: the default of a
# ${ENV:default} placeholder, or the literal. "(ABSENT)" when no line names it,
# "(AMBIGUOUS)" when more than one does, "(NODEFAULT)" for a placeholder without
# a default.
inv17_yml_value() {
    local f="$1" hits n v
    hits=$(grep -iE -- "$INV17_PROP_RE" "$f") || true
    n=$(grep -c . <<< "$hits") || true
    if (( n == 0 )); then echo "(ABSENT)"; return 0; fi
    if (( n > 1 )); then echo "(AMBIGUOUS)"; return 0; fi
    v="${hits#*:}"
    v="$(sed -E 's/[[:space:]]+#.*$//; s/^[[:space:]]+//; s/[[:space:]]+$//' <<< "$v")"
    if [[ "$v" =~ ^\"(.*)\"$ || "$v" =~ ^\'(.*)\'$ ]]; then v="${BASH_REMATCH[1]}"; fi
    if [[ "$v" =~ ^\$\{[A-Za-z0-9_.-]+:(.*)\}$ ]]; then
        v="${BASH_REMATCH[1]}"
    elif [[ "$v" == \$\{* ]]; then
        v="(NODEFAULT)"
    fi
    echo "$v"
}
[[ -f "$APP_YML" ]] || parse_fail "INV-17: $APP_YML not found, so the require-verified-email default cannot be read. A reader that finds nothing never passes."
INV17_BASE_DEFAULT="$(inv17_yml_value "$APP_YML")"
case "$INV17_BASE_DEFAULT" in
    "(ABSENT)") parse_fail "INV-17: core-java/src/main/resources/application.yml has no require-verified-email line (expected jtoye.security.customer-jwt.require-verified-email: \${CUSTOMER_JWT_REQUIRE_VERIFIED_EMAIL:true}). Either the property moved or the reader is blind; fix the reader, do not delete the invariant." ;;
    "(AMBIGUOUS)") parse_fail "INV-17: core-java/src/main/resources/application.yml names require-verified-email more than once; the reader cannot tell which line is the customer-jwt one." ;;
esac

# INV-11 (Phase 36 D-08, code review WR-01): the storage-probe switch in every
# spelling Spring's relaxed binding accepts, matched case-insensitively on the raw
# render text; the image that makes a container a core-java container; and the
# env names that could carry the switch from a source the render cannot read.
PROBE_SWITCH_RE='validate[-_.]?on[-_.]?startup'
CORE_JAVA_IMAGE_RE='/jtoye-core-java(:|@|$)'
PROBE_JSON_ENV_NORM='springapplicationjson'
PROBE_JVM_OPTS_ENV_NORM_RE='^(javaopts|javatooloptions|jdkjavaoptions)$'

# INV-22 (#999): the environment label each deployed target's core-java must export. The prod
# profile (every k8s environment runs it, D-10) says `production`; staging must say otherwise.
# "<label> <required>": required=1 means the env MUST be set (the profile's value is wrong
# there); 0 means the profile's value is right, so unset is fine and only a wrong value fails.
# EVERY discovered target must be listed (review round 1 on #1005, F3): all of them run the prod
# profile, so an overlay not named here (k8s/local was one) exported environment="production".
# k8s/base carries the production values and no overlay, so it is labelled production like
# k8s/production. An unlisted target FAILS: add it here, with its own label, in the same change.
declare -A INV22_EXPECTED=(
  ["k8s/staging"]="staging 1"
  ["k8s/local"]="local 1"
  ["k8s/production"]="production 0"
  ["k8s/base"]="production 0"
)
declare -A INV22_TARGET_SEEN=()
INV22_ENV_NORM='managementmetricstagsenvironment'
INV22_PROFILE_YML="$APP_RESOURCES/application-prod.yml"
# inv22_yml_tag <file> — the value of management.metrics.tags.environment in <file>, found by
# walking the YAML nesting by indentation (the key may be written nested or as a dotted path);
# "(ABSENT)" when no line gives that path.
inv22_yml_tag() {
    awk '
        { line = $0; sub(/[ \t]+#.*$/, "", line); if (line ~ /^[ \t]*(#.*)?$/) next
          match(line, /^ */); ind = RLENGTH; key = line; sub(/^ */, "", key)
          if (key !~ /^[A-Za-z0-9_.-]+:/) next
          name = key; sub(/:.*/, "", name); val = key; sub(/^[^:]*:[ \t]*/, "", val)
          while (depth > 0 && ind <= indent[depth]) depth--
          depth++; indent[depth] = ind; path[depth] = name
          p = path[1]; for (i = 2; i <= depth; i++) p = p "." path[i]
          if (p == "management.metrics.tags.environment") { print val; found = 1; exit } }
        END { if (!found) print "(ABSENT)" }' "$1"
}

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

# --- render_paths.py: walk every document into full key PATHS (INV-7/8/9/10/11/16/18/20, LOC-7).
#     Output: <kind> <TAB> <metadata.name> <TAB> <path> <TAB> <value>
#     Path segments are joined with "::"; a sequence item becomes "[i]", indexed
#     from 0 per parent path, so containers::[0]::env::[3]::name is unambiguous.
#
#     WHY PATHS. kubectl kustomize sorts map keys alphabetically, so inside a
#     container `env:` is emitted BEFORE that container's own `name:`, and inside
#     a pod template `metadata:` precedes `spec:`. Any scan that finds a container
#     by its name and reads FORWARD sees none of its env — the ordering trap INV-3
#     and INV-7 already document. A full path is independent of emission order.
#
#     WHY A PARSER (#976 round 3). The walker used to be a hand-written awk
#     re-implementation of YAML, and it was wrong three times on one value: it read
#     only the first line of a folded scalar, then its continuation regex was dead,
#     then it dropped a continuation line starting with `#` as a comment BEFORE the
#     continuation check. kubectl folds a long quoted value at any space, including
#     the one before a `#`, and inside a quoted scalar `#b" -Dkc.proxy-trusted-
#     addresses=0.0.0.0/0` is part of the value, so INV-20 never saw the flag and
#     the walker returned rc 0. Each of the three was a hole in a re-implementation
#     of the grammar, and a fourth would have been the same kind (rule D1: fix at the
#     authority, never per spelling). The authority is now PyYAML, in the sibling
#     k8s/scripts/render_paths.py, whose header is the record contract: values are
#     the COMPLETE scalar, written back in the RAW spelling (quotes kept, so "true"
#     is still told from true and unq still strips one pair), a TAB or line break in
#     a value is escaped (\t \n) so it cannot break the TSV, and a block scalar is its
#     indicator only (INV-20 refuses it as unreadable).
#
#     NO FALLBACK. If python3 or PyYAML is missing, the input is not YAML, or a
#     document is not a mapping, the walker exits 2 with a PARSE ERROR and the caller
#     is VOID. Falling back to a line reader would put the truncating reader back
#     behind a green run on the machine that lacks PyYAML. PyYAML is the runner's
#     (the Key Vault chain gate in the same CI job needs it too); locally run under a
#     conda env on PATH (the host shim refuses a bare python3), or point
#     RENDER_PATHS_PYTHON at an interpreter. Callers must treat a non-zero status as
#     VOID and never judge partial output.
RENDER_PATHS_PY="$SCRIPT_DIR/render_paths.py"
render_paths() {   # <render.yaml>: the path table on stdout; rc 2 + PARSE ERROR on failure
    local -a py
    read -r -a py <<< "${RENDER_PATHS_PYTHON:-python3}"
    if ! command -v "${py[0]}" > /dev/null 2>&1; then
        echo "PARSE ERROR: render_paths: python3 not found ('${py[0]}'); the render cannot be walked and there is no fallback walker." >&2
        return 2
    fi
    [[ -r "$RENDER_PATHS_PY" ]] || { echo "PARSE ERROR: render_paths: $RENDER_PATHS_PY is not readable." >&2; return 2; }
    "${py[@]}" "$RENDER_PATHS_PY" "$1"
}


# --- awk: NetworkPolicy rule TUPLES from the path walk (INV-7).
#     Input: the render_paths output (<kind> TAB <name> TAB <path> TAB <value>).
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

# unq <value> — one pair of surrounding quotes removed (double OR single), left in
# $UNQ. The shell twin of cfg_get's and NETPOL_TUPLES_AWK's unq: the walker's values
# are raw YAML scalars, so a quoted '0.0.0.0/0' or "keycloak:26.8.1" must be
# unquoted ONCE, here, before it is compared (YAML escapes inside the quotes are not
# interpreted: none of the values judged with it can legitimately carry one).
unq() {
    UNQ="$1"
    if [[ "$UNQ" =~ ^\"(.*)\"$ || "$UNQ" =~ ^\'(.*)\'$ ]]; then UNQ="${BASH_REMATCH[1]}"; fi
}

# ---------------------------------------------------------------------------
# INV-20 helpers (#976). They judge the EFFECTIVE proxy-trust configuration of one
# Keycloak container, wherever it is spelled, into these globals (reset per
# container by the INV-20 loop):
#   I20_HDR     how many places turn proxy headers on (KC_PROXY_HEADERS,
#               --proxy-headers, -Dkc.proxy-headers)
#   I20_LEGACY  1 = the legacy KC_PROXY / --proxy / -Dkc.proxy other than none
#   I20_UNJ     1 = a source was met that cannot be read from the render (a Secret,
#               a block scalar, a ConfigMap that is not rendered): fail closed
#   I20_TV/TS   each trusted-addresses value found, and where it came from
# Spellings read (Keycloak 26.8.1 takes each option from env, CLI, or JVM property):
#   env           KC_PROXY_HEADERS, KC_PROXY_TRUSTED_ADDRESSES, KC_PROXY, and any
#                 JAVA_OPTS* / JAVA_TOOL_OPTIONS / JDK_JAVA_OPTIONS (words -Dkc.proxy*)
#   envFrom       every key of a ConfigMap in the render, by the same rules
#   args/command each item, as --proxy-headers / --proxy-trusted-addresses= / --proxy
#                 or the -Dkc.proxy* spelling; an item that only MENTIONS one (a
#                 sh -c script) or is a block scalar cannot be judged and fails
# A configMapKeyRef is followed into ANY ConfigMap in the render.
# ---------------------------------------------------------------------------
INV20_MENTION_RE='--proxy(-headers|-trusted-addresses)?([^a-z0-9_-]|$)|kc[._]proxy([-_](headers|trusted[-_]addresses))?([^a-z0-9_-]|$)'
INV20_BLOCK_RE='^[|>][-+0-9]*$'
INV20_JAVAOPTS_NORM_RE='^(javaopts[a-z0-9]*|javatooloptions|jdkjavaoptions)$'

inv20_fail() { echo "  FAIL [$rel] INV-20: $who20 $1" >&2; inv20_bad=1; }

inv20_mentions() { local t="${1,,}"; [[ "$t" =~ $INV20_MENTION_RE ]]; }

# inv20_word <word> <origin> — one WORD that is exactly a proxy option. rc 1 = not one.
inv20_word() {
    local w="$1" o="$2" v
    case "$w" in
        --proxy-headers|--proxy-headers=*|-Dkc.proxy-headers|-Dkc.proxy-headers=*)
            I20_HDR=$((I20_HDR + 1)) ;;
        --proxy-trusted-addresses=*|-Dkc.proxy-trusted-addresses=*)
            unq "${w#*=}"; I20_TV+=("$UNQ"); I20_TS+=("$o ${w%%=*}") ;;
        -Dkc.proxy-trusted-addresses)
            I20_TV+=(""); I20_TS+=("$o $w") ;;
        --proxy-trusted-addresses)
            inv20_fail "passes --proxy-trusted-addresses without '=value' ($o), so its value cannot be judged here. Write --proxy-trusted-addresses=<cidr list> or use the KC_PROXY_TRUSTED_ADDRESSES env."
            I20_UNJ=1 ;;
        --proxy|--proxy=*|-Dkc.proxy|-Dkc.proxy=*)
            v=""; [[ "$w" == *=* ]] && v="${w#*=}"
            unq "$v"
            [[ "$w" == *=* && "$UNQ" == none ]] || I20_LEGACY=1 ;;
        *) return 1 ;;
    esac
    return 0
}

# inv20_token <raw value> <origin> — one command/args item.
inv20_token() {
    local t o="$2"
    if [[ "$1" =~ $INV20_BLOCK_RE ]]; then
        inv20_fail "has a block scalar ('$1') in its $o, whose text this check cannot read, so a proxy option inside it would go unseen. Write each argument as its own plain list item."
        I20_UNJ=1; return
    fi
    unq "$1"; t="$UNQ"
    inv20_word "$t" "$o" && return
    if inv20_mentions "$t"; then
        inv20_fail "mentions a proxy option inside a larger string in its $o ('${t:0:70}'), such as a shell -c script, so it cannot be judged here. Pass each Keycloak option as its own args item or use the KC_* env."
        I20_UNJ=1
    fi
}

# inv20_javaopts <value> <origin> — the words of a JAVA_OPTS-style value.
inv20_javaopts() {
    local -a words=()
    local w
    IFS=$' \t\n' read -ra words <<< "$1"
    for w in "${words[@]}"; do
        unq "$w"; w="$UNQ"
        inv20_word "$w" "$2" && continue
        if inv20_mentions "$w"; then
            inv20_fail "mentions a proxy option in a form this check cannot judge in $2 ('${w:0:70}')."
            I20_UNJ=1
        fi
    done
}

# inv20_cm_exists <configmap> / inv20_cm_value <configmap> <key> (-> $R20_VAL; rc 0
# ok, 1 no such ConfigMap in the render, 2 no such key, 3 block scalar).
inv20_cm_exists() {
    awk -F'\t' -v n="$1" '$1 == "ConfigMap" && $2 == n { f = 1; exit } END { exit !f }' "$TMP/inv11_paths.tsv"
}
inv20_cm_value() {
    local raw
    raw="$(path_get "$TMP/inv11_paths.tsv" ConfigMap "$1" "data::$2")"
    if [[ "$raw" == "(ABSENT)" ]]; then
        inv20_cm_exists "$1" && return 2
        return 1
    fi
    [[ "$raw" =~ $INV20_BLOCK_RE ]] && return 3
    unq "$raw"; R20_VAL="$UNQ"
    return 0
}

# inv20_resolve <kind> <name> <env item path> <label> — the value of an env item as
# $R20_VAL (+ $R20_SRC, where a ConfigMap supplied it). rc 1 = unreadable (FAIL given).
inv20_resolve() {
    local lit cmn cmk opt rc
    R20_SRC=""
    lit="$(path_get "$TMP/inv11_paths.tsv" "$1" "$2" "$3::value")"
    if [[ "$lit" != "(ABSENT)" ]]; then
        if [[ "$lit" =~ $INV20_BLOCK_RE ]]; then
            inv20_fail "$4 is a block scalar, which this check cannot read."; return 1
        fi
        unq "$lit"; R20_VAL="$UNQ"; return 0
    fi
    cmk="$(path_get "$TMP/inv11_paths.tsv" "$1" "$2" "$3::valueFrom::configMapKeyRef::key")"
    if [[ "$cmk" == "(ABSENT)" ]]; then
        inv20_fail "$4 is not a literal or a configMapKeyRef (a secretKeyRef, fieldRef or resourceFieldRef?), so its value cannot be judged in the render."
        return 1
    fi
    unq "$cmk"; cmk="$UNQ"
    unq "$(path_get "$TMP/inv11_paths.tsv" "$1" "$2" "$3::valueFrom::configMapKeyRef::name")"; cmn="$UNQ"
    unq "$(path_get "$TMP/inv11_paths.tsv" "$1" "$2" "$3::valueFrom::configMapKeyRef::optional")"; opt="$UNQ"
    if [[ "$opt" != "(ABSENT)" && "$opt" != "false" ]]; then
        inv20_fail "$4 reads ConfigMap '$cmn' through an optional configMapKeyRef, so a missing key would silently drop the setting and Keycloak would trust every source."
        return 1
    fi
    inv20_cm_value "$cmn" "$cmk"; rc=$?
    case "$rc" in
        0) R20_SRC=" from ConfigMap $cmn key $cmk"; return 0 ;;
        1) inv20_fail "$4 reads ConfigMap '$cmn', which is not in the render, so its value cannot be judged." ;;
        2) inv20_fail "$4 reads ConfigMap '$cmn' key '$cmk', which the render does not define." ;;
        *) inv20_fail "$4 reads ConfigMap '$cmn' key '$cmk', a block scalar this check cannot read." ;;
    esac
    return 1
}

# inv20_value_of <label> <origin> <item|lit> <env item path | raw literal> — an env
# ITEM is resolved through the render; a literal (an envFrom ConfigMap key) is just
# unquoted. rc 1 = unreadable (FAIL given).
inv20_value_of() {
    if [[ "$3" == lit ]]; then
        if [[ "$4" =~ $INV20_BLOCK_RE ]]; then inv20_fail "$1 (envFrom) is a block scalar, which this check cannot read."; return 1; fi
        unq "$4"; R20_VAL="$UNQ"; R20_SRC=""; return 0
    fi
    inv20_resolve "$k20" "$n20" "$4" "$1"
}

# inv20_apply_name <env name> <origin> <item|lit> <env item path | raw literal>
inv20_apply_name() {
    local nm="$1" o="$2" norm
    case "$nm" in
        KC_PROXY_HEADERS) I20_HDR=$((I20_HDR + 1)) ;;
        KC_PROXY_TRUSTED_ADDRESSES)
            inv20_value_of "$nm" "$o" "$3" "$4" || { I20_UNJ=1; return; }
            I20_TV+=("$R20_VAL"); I20_TS+=("$o$R20_SRC") ;;
        KC_PROXY)
            inv20_value_of "$nm" "$o" "$3" "$4" || { I20_UNJ=1; return; }
            [[ "$R20_VAL" == none ]] || I20_LEGACY=1 ;;
        *)
            norm="${nm,,}"; norm="${norm//[^a-z0-9]/}"
            if [[ "$norm" =~ $INV20_JAVAOPTS_NORM_RE ]]; then
                inv20_value_of "$nm" "$o" "$3" "$4" || { I20_UNJ=1; return; }
                inv20_javaopts "$R20_VAL" "$o"
            fi ;;
    esac
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

# ---------------------------------------------------------------------------
# INV-15 inputs (29-08). Read once, before any target is judged.
#
# INGRESS_AWK: one record per fact about each rendered Ingress, every one keyed
# by the Ingress name, read from the YAML structure (top-level key, then the
# child section) rather than by a forward scan, because kustomize sorts keys.
#   ING  <name>
#   ANN  <name> <annotation key>
#   SNIP <name> <one trimmed line of the configuration-snippet block scalar>
#   TLS  <name> <entry index> <host count> <secretName or "">
# Continuation lines of a wrapped plain scalar (cors-allow-methods wraps) are
# deeper than a key line and are skipped; a block scalar is consumed whole.
# ---------------------------------------------------------------------------
INGRESS_AWK='
function tls_end() { if (ti >= 0 && tls_open) printf "TLS\t%s\t%d\t%d\t%s\n", nm, ti, nh, sec; tls_open = 0 }
function flush(  i, l, k, v, top, sect, blk, bkey, s, sub2) {
    if (n == 0) return
    kind = ""; nm = ""; top = ""
    for (i = 1; i <= n; i++) {
        l = buf[i]
        if (l ~ /^kind: /) { kind = l; sub(/^kind:[[:space:]]*/, "", kind) }
        if (l ~ /^[^ ]/) { top = l; sub(/:.*$/, "", top); continue }
        if (top == "metadata" && l ~ /^  name: /) { nm = l; sub(/^  name:[[:space:]]*/, "", nm) }
    }
    if (kind != "Ingress") { n = 0; delete buf; return }
    printf "ING\t%s\n", nm
    top = ""; sect = ""; blk = 0; ti = -1; tls_open = 0; sub2 = ""
    for (i = 1; i <= n; i++) {
        l = buf[i]
        if (blk) {
            if (l ~ /^      / || l ~ /^[[:space:]]*$/) {
                if (bkey ~ /\/configuration-snippet$/) {
                    s = l; sub(/^[[:space:]]+/, "", s); sub(/[[:space:]]+$/, "", s)
                    if (s != "") printf "SNIP\t%s\t%s\n", nm, s
                }
                continue
            }
            blk = 0
        }
        if (l ~ /^[^ ]/) { tls_end(); top = l; sub(/:.*$/, "", top); sect = ""; continue }
        if (l ~ /^  [^ -]/) {
            tls_end(); sect = l; sub(/^  /, "", sect); sub(/:.*$/, "", sect); continue
        }
        if (top == "metadata" && sect == "annotations" && l ~ /^    [^ ]/) {
            k = l; sub(/^    /, "", k)
            v = k
            if (match(k, /:( |$)/)) { k = substr(k, 1, RSTART - 1); v = substr(v, RSTART + 1); sub(/^ /, "", v) }
            printf "ANN\t%s\t%s\n", nm, k
            if (v ~ /^[|>][-+]?$/) { blk = 1; bkey = k }
            continue
        }
        if (top == "spec" && sect == "tls") {
            if (l ~ /^  - /) {
                tls_end(); ti++; tls_open = 1; nh = 0; sec = ""; sub2 = ""
                s = l; sub(/^  - /, "", s)
                if (s ~ /^hosts:/) sub2 = "hosts"
                else if (s ~ /^secretName:/) { sec = s; sub(/^secretName:[[:space:]]*/, "", sec) }
                continue
            }
            if (l ~ /^    hosts:/) { sub2 = "hosts"; continue }
            if (l ~ /^    secretName:/) { sec = l; sub(/^    secretName:[[:space:]]*/, "", sec); sub2 = ""; continue }
            if (sub2 == "hosts" && l ~ /^    - /) { nh++; continue }
        }
    }
    tls_end()
    n = 0; delete buf
}
/^---[[:space:]]*$/ { flush(); next }
{ buf[++n] = $0 }
END { flush() }
'
# The Traefik provider's documented annotation suffixes (plan 29-03). Missing,
# or nothing but its header, is a PARSE ERROR: an empty list would fail every
# annotation, and a gate that reads no list is blind.
TRAEFIK_SUPPORTED="$K8S_DIR/platform/traefik/supported-annotations.txt"
[[ -f "$TRAEFIK_SUPPORTED" ]] || parse_fail "INV-15: Traefik's supported-annotation list is missing: $TRAEFIK_SUPPORTED (plan 29-03 owns it)."
TRAEFIK_SUPPORTED_LIST=" $(awk '!/^[[:space:]]*#/ && NF { printf "%s ", $1 }' "$TRAEFIK_SUPPORTED")"
[[ -n "${TRAEFIK_SUPPORTED_LIST// /}" ]] || parse_fail "INV-15: $TRAEFIK_SUPPORTED lists no annotation (header only). Every nginx annotation would be reported unsupported — or a blind reader could pass. Restore the list, do not delete the invariant."
# The targets that must tell crawlers to stay away (D-08 applied to search
# engines). Every OTHER deployed target must NOT serve noindex: production is
# indexable once live, and a stray noindex there would de-list the storefront.
NOINDEX_TARGETS=("k8s/staging")
# The base security-header lines a staging snippet copies (a JSON6902 replace
# cannot append to a block scalar, so drift is checked): every more_set_headers
# line of base jtoye-ingress's configuration-snippet, read from the BASE RENDER.
if ! kubectl kustomize "$K8S_DIR/base" > "$TMP/inv15_base.yaml" 2> "$TMP/stderr"; then
    cat "$TMP/stderr" >&2
    parse_fail "INV-15: the k8s/base render failed, so the base security-header lines cannot be read."
fi
awk "$INGRESS_AWK" "$TMP/inv15_base.yaml" | awk -F'\t' '$1 == "SNIP" && $2 == "jtoye-ingress" && $3 ~ /^more_set_headers / { print $3 }' > "$TMP/inv15_base_headers"
(( $(wc -l < "$TMP/inv15_base_headers") > 0 )) || parse_fail "INV-15: found 0 more_set_headers lines in base jtoye-ingress's configuration-snippet. Either base lost its security headers or INGRESS_AWK is blind; the staging drift check would pass on anything. Fix it, do not delete the invariant."

is_noindex_target() {
    local rel="$1" t
    for t in "${NOINDEX_TARGETS[@]}"; do
        [[ "$rel" == "$t" ]] && return 0
    done
    return 1
}

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
        echo "        postgres-credentials/port (pg-backup: its own pg-backup-credentials/port)," >&2
        echo "        a secretKeyRef with 'key: port'. This asserts the SHAPE on the RENDER, so no quoting" >&2
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

    render_paths "$render" > "$TMP/inv7_paths.tsv" || parse_fail "path walker could not read $render safely (see the PARSE ERROR above): refusing to judge a truncated value."
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
        render_paths "$render" > "$TMP/paths.tsv" || parse_fail "path walker could not read $render safely (see the PARSE ERROR above): refusing to judge a truncated value."
        (( $(wc -l < "$TMP/paths.tsv") > 0 )) || parse_fail "[$rel] INV-8..INV-10: the path walker emitted 0 records for the render — it is blind, and every storage assertion below would pass vacuously. Fix render_paths.py, do not delete the invariants."
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
        [[ -n "$secret_refs" ]] || parse_fail "[$rel] INV-9 found 0 Secret references in the render. core-java reads postgres-credentials and pg-backup reads pg-backup-credentials, so zero means the path walker is blind and the 'no storage Secret' assertion would pass vacuously. Fix it, do not delete the invariant."
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
        # The allowed set: the two Blob workloads, plus every pod template whose
        # volumes include a csi volume of $CSI_SECRETS_DRIVER (plan 29-12, derived).
        declare -A wi_allowed=()
        declare -A wi_csi=()
        for wl in "${!WI_WORKLOAD_TEMPLATE[@]}"; do wi_allowed[$wl]="${WI_WORKLOAD_TEMPLATE[$wl]}"; done
        while IFS=$'\t' read -r k n tp; do
            [[ -n "$k" ]] || continue
            wi_allowed[$k/$n]="$tp"; wi_csi[$k/$n]=1
        done <<< "$(awk -F'\t' -v drv="$CSI_SECRETS_DRIVER" '
            { v = $4; if (v ~ /^".*"$/ || v ~ /^\047.*\047$/) v = substr(v, 2, length(v) - 2) }
            v == drv && match($3, /^(spec::template|spec::jobTemplate::spec::template)::spec::volumes::\[[0-9]+\]::csi::driver$/) {
                tp = $3; sub(/::spec::volumes::.*$/, "", tp); print $1 "\t" $2 "\t" tp }' "$TMP/paths.tsv" | sort -u)"
        for wl in $(printf '%s\n' "${!wi_allowed[@]}" | sort); do
            wk="${wl%%/*}"; wn="${wl#*/}"; tp="${wi_allowed[$wl]}"
            lbl="$(path_get "$TMP/paths.tsv" "$wk" "$wn" "$tp::metadata::labels::$WI_LABEL_KEY")"
            san="$(path_get "$TMP/paths.tsv" "$wk" "$wn" "$tp::spec::serviceAccountName")"
            if [[ "$lbl" != '"true"' ]]; then
                echo "  FAIL [$rel] INV-10: $wl pod template label $WI_LABEL_KEY is $lbl, expected the STRING \"true\" at $tp::metadata::labels. Without it the webhook injects no identity: core-java refuses to boot, and a Key Vault mount has no token to fetch with." >&2
                inv10_bad=1
            fi
            if [[ -n "${WI_WORKLOAD_SA[$wl]:-}" && "$san" != "${WI_WORKLOAD_SA[$wl]}" ]]; then
                echo "  FAIL [$rel] INV-10: $wl runs as serviceAccountName '$san', expected '${WI_WORKLOAD_SA[$wl]}' (the federated credential's subject names exactly that ServiceAccount)." >&2
                inv10_bad=1
            fi
            if [[ "$san" == "(ABSENT)" || -z "$san" || "$san" == "default" ]]; then
                echo "  FAIL [$rel] INV-10: $wl opts into Workload Identity but runs as serviceAccountName '$san' — it needs its OWN ServiceAccount, the federated credential's subject." >&2
                inv10_bad=1
                continue
            fi
            am="$(path_get "$TMP/paths.tsv" ServiceAccount "$san" automountServiceAccountToken)"
            if [[ "$am" != "false" ]]; then
                echo "  FAIL [$rel] INV-10: ServiceAccount '$san' (of $wl) has automountServiceAccountToken=$am, expected false (it must exist, and no workload here needs a Kubernetes API token)." >&2
                inv10_bad=1
            fi
            if [[ -n "${wi_csi[$wl]:-}" ]]; then
                cid="$(path_get "$TMP/paths.tsv" ServiceAccount "$san" "metadata::annotations::$WI_CLIENT_ID_KEY")"
                cid="${cid#\"}"; cid="${cid%\"}"
                if ! [[ "$cid" =~ $GUID_RE ]]; then
                    echo "  FAIL [$rel] INV-10: $wl mounts a SecretProviderClass ($CSI_SECRETS_DRIVER) but its ServiceAccount '$san' has $WI_CLIENT_ID_KEY='$cid', expected a GUID (the managed identity the Key Vault fetch runs as)." >&2
                    inv10_bad=1
                fi
            fi
        done
        # Least privilege: no pod template outside the allowed set opts in.
        while IFS=$'\t' read -r k n pth v; do
            [[ -n "$k" ]] || continue
            tp="${wi_allowed[$k/$n]:-}"
            if [[ -z "$tp" || "$pth" != "$tp::metadata::labels::$WI_LABEL_KEY" ]]; then
                echo "  FAIL [$rel] INV-10: $k/$n carries $WI_LABEL_KEY at $pth — only the pod templates of ${!WI_WORKLOAD_TEMPLATE[*]} and of workloads mounting a $CSI_SECRETS_DRIVER volume may opt into Workload Identity." >&2
                inv10_bad=1
            fi
        done <<< "$(awk -F'\t' -v key="$WI_LABEL_KEY" '{ n = split($3, seg, "::"); if (seg[n] == key) print }' "$TMP/paths.tsv")"
        if (( inv10_bad != 0 )); then
            FAILED=1
            inv10_msg="FAIL"
        else
            inv10_msg="OK (${#wi_allowed[@]} pod templates labelled + own SA, automount false; ${#wi_csi[@]} mount a SecretProviderClass with a client-id SA; no other opt-in)"
        fi
        unset wi_allowed wi_csi

        # ---- INV-19: nothing in staging/production can relax STARTTLS (D-20) ----
        inv19_bad=0
        inv19_ctl=0
        while IFS=$'\t' read -r k n pth v; do
            if [[ "$pth" =~ ::env::\[[0-9]+\]::name$ && "$v" == "SMTP_STARTTLS" ]]; then inv19_ctl=$((inv19_ctl + 1)); continue; fi
            [[ "$pth" =~ ::env::\[[0-9]+\]::(name|value)$ ]] || continue
            # core-java is the only JavaMail client. Other workloads' starttls envs are the SERVER
            # side of the same requirement (Mailpit's MP_SMTP_REQUIRE_STARTTLS) and only tighten it.
            [[ "$k/$n" == "Deployment/core-java" ]] || continue
            if [[ "${v,,}" == *starttls* ]]; then
                echo "  FAIL [$rel] INV-19: $k/$n has an env $pth = '$v' naming starttls. STARTTLS is REQUIRED in staging and production (D-20): application-prod.yml sets mail.smtp.starttls.required as a literal, and no env may relax it here (only k8s/local may, for Mailhog)." >&2
                inv19_bad=1
            fi
        done < "$TMP/paths.tsv"
        (( inv19_ctl >= 1 )) || parse_fail "[$rel] INV-19 found no SMTP_STARTTLS env name in the render — the control is missing, so the path walker is blind or core-java lost its mail env. Fix it, do not delete the invariant."
        if (( inv19_bad != 0 )); then
            FAILED=1
            inv19_msg="FAIL"
        else
            inv19_msg="OK (no env or env value names starttls except the $inv19_ctl SMTP_STARTTLS control(s))"
        fi
    else
        inv8_msg="SKIP (not in WI_TARGETS)"
        inv9_msg="SKIP (not in WI_TARGETS)"
        inv10_msg="SKIP (not in WI_TARGETS)"
        inv19_msg="SKIP (not in WI_TARGETS)"
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
    render_paths "$render" > "$TMP/inv11_paths.tsv" || parse_fail "path walker could not read $render safely (see the PARSE ERROR above): refusing to judge a truncated value."
    inv11_ctrs=$(awk -F'\t' -v img="$CORE_JAVA_IMAGE_RE" \
        '$3 ~ /::(containers|initContainers)::\[[0-9]+\]::image$/ && $4 ~ img { p = $3; sub(/::image$/, "", p); print $1 "\t" $2 "\t" p }' \
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

    # ---------------- INV-17 (29-26, #859 / T-29-95) ----------------
    # Deployed targets only: no core-java container may run with the customer
    # realm's require-verified-email gate off — not by an env literal, not by an
    # app-config value (the RENDERED value is read), not by a relaxed-binding
    # spelling, and not by activating a profile (dev) whose yml defaults it off
    # with no env overriding it. application.yml's own default must read true.
    if is_wi_target "$rel"; then
        inv17_bad=0; inv17_envs=0; inv17_ctr_n=0
        if [[ "$INV17_BASE_DEFAULT" != "true" ]]; then
            echo "  FAIL [$rel] INV-17: core-java/src/main/resources/application.yml defaults require-verified-email to '$INV17_BASE_DEFAULT'. With it off, a self-registered customer account carrying a victim's UNVERIFIED address can list the victim's orders (#859, T-29-95). The default must be true; only the dev profile relaxes it." >&2
            inv17_bad=1
        fi
        while IFS=$'\t' read -r ck cn cpath; do
            [[ -n "$cpath" ]] || continue
            inv17_ctr_n=$((inv17_ctr_n + 1))
            cname="$(path_get "$TMP/inv11_paths.tsv" "$ck" "$cn" "$cpath::name")"
            ctr_set=0
            while IFS=$'\t' read -r epath ename; do
                norm="$(tr -cd '[:alnum:]' <<< "$ename" | tr '[:upper:]' '[:lower:]')"
                [[ "$norm" == *"$INV17_ENV_SUFFIX" ]] || continue
                ctr_set=1; inv17_envs=$((inv17_envs + 1))
                ev="$(path_get "$TMP/inv11_paths.tsv" "$ck" "$cn" "$epath::value")"
                cmn="$(path_get "$TMP/inv11_paths.tsv" "$ck" "$cn" "$epath::valueFrom::configMapKeyRef::name")"
                cmk="$(path_get "$TMP/inv11_paths.tsv" "$ck" "$cn" "$epath::valueFrom::configMapKeyRef::key")"
                src=""
                if [[ "$ev" != "(ABSENT)" ]]; then
                    if [[ "$ev" =~ ^\"(.*)\"$ || "$ev" =~ ^\'(.*)\'$ ]]; then ev="${BASH_REMATCH[1]}"; fi
                elif [[ "$cmn" == "app-config" && "$cmk" != "(ABSENT)" ]]; then
                    ev="$(cfg_get "$TMP/cfg.tsv" "$cmk")"; src=" (app-config key $cmk)"
                else
                    echo "  FAIL [$rel] INV-17: $ck $cn container '$cname' sets $ename from a source the render cannot read ($epath: neither a literal value nor an app-config configMapKeyRef), so whether require-verified-email is on cannot be shown." >&2
                    inv17_bad=1; continue
                fi
                if [[ "$ev" != "true" ]]; then
                    echo "  FAIL [$rel] INV-17: $ck $cn container '$cname' sets $ename to '$ev'$src. That switches off the customer realm's email_verified gate (#859, T-29-95); only the dev profile may relax it." >&2
                    inv17_bad=1
                fi
            done < <(awk -F'\t' -v k="$ck" -v n="$cn" -v c="$cpath::env::" '$1 == k && $2 == n && index($3, c) == 1 && $3 ~ /::env::\[[0-9]+\]::name$/ { e = $3; sub(/::name$/, "", e); print e "\t" $4 }' "$TMP/inv11_paths.tsv")
            if awk -F'\t' -v k="$ck" -v n="$cn" -v c="$cpath::envFrom::" '$1 == k && $2 == n && index($3, c) == 1 { f = 1 } END { exit !f }' "$TMP/inv11_paths.tsv"; then
                echo "  FAIL [$rel] INV-17: $ck $cn container '$cname' uses envFrom, which can bind require-verified-email under any relaxed spelling the render does not show. List the env explicitly." >&2
                inv17_bad=1
            fi
            # Profiles: with no env override, the last active profile whose yml
            # names the property decides; otherwise application.yml's default.
            if (( ctr_set == 0 )); then
                pe="$(awk -F'\t' -v k="$ck" -v n="$cn" -v c="$cpath::env::" '$1 == k && $2 == n && index($3, c) == 1 && $3 ~ /::env::\[[0-9]+\]::name$/ && $4 == "SPRING_PROFILES_ACTIVE" { e = $3; sub(/::name$/, "", e); print e; exit }' "$TMP/inv11_paths.tsv")"
                profiles=""
                if [[ -n "$pe" ]]; then
                    profiles="$(path_get "$TMP/inv11_paths.tsv" "$ck" "$cn" "$pe::value")"
                    if [[ "$profiles" == "(ABSENT)" ]]; then
                        pcn="$(path_get "$TMP/inv11_paths.tsv" "$ck" "$cn" "$pe::valueFrom::configMapKeyRef::name")"
                        pck="$(path_get "$TMP/inv11_paths.tsv" "$ck" "$cn" "$pe::valueFrom::configMapKeyRef::key")"
                        if [[ "$pcn" == "app-config" && "$pck" != "(ABSENT)" ]]; then
                            profiles="$(cfg_get "$TMP/cfg.tsv" "$pck")"
                        else
                            echo "  FAIL [$rel] INV-17: $ck $cn container '$cname' takes SPRING_PROFILES_ACTIVE from a source the render cannot read, so the profile that decides require-verified-email cannot be shown." >&2
                            inv17_bad=1; profiles=""
                        fi
                    fi
                    if [[ "$profiles" =~ ^\"(.*)\"$ || "$profiles" =~ ^\'(.*)\'$ ]]; then profiles="${BASH_REMATCH[1]}"; fi
                fi
                IFS=',' read -r -a plist <<< "$profiles"
                for p in "${plist[@]}"; do
                    p="${p//[[:space:]]/}"
                    [[ -n "$p" && -f "$APP_RESOURCES/application-$p.yml" ]] || continue
                    pv="$(inv17_yml_value "$APP_RESOURCES/application-$p.yml")"
                    [[ "$pv" == "(ABSENT)" ]] && continue
                    if [[ "$pv" != "true" ]]; then
                        echo "  FAIL [$rel] INV-17: $ck $cn container '$cname' activates profile '$p', whose application-$p.yml defaults require-verified-email to '$pv', and no env sets it true. A deployed core-java must not run a profile that relaxes the customer email_verified gate (#859)." >&2
                        inv17_bad=1
                    fi
                done
            fi
        done <<< "$inv11_ctrs"
        if (( inv17_ctr_n == 0 )); then
            parse_fail "[$rel] INV-17: found no core-java-image container in Deployment core-java — the render lost it or the container reader is blind."
        fi
        if (( inv17_bad != 0 )); then
            FAILED=1
            inv17_msg="FAIL"
        else
            inv17_msg="OK (application.yml default true; $inv17_envs env override(s), all true; no active profile relaxes it)"
        fi
    else
        inv17_msg="SKIP (not in WI_TARGETS)"
    fi

    # ---------------- INV-15 (29-08, Pitfall 4, D-08, D-29) ----------------
    # Deployed targets only. (a) one host per tls entry; (b) no Secret requested
    # by two cert-manager-managed tls entries; (c) noindex targets: every Ingress
    # serves X-Robots-Tag noindex AND every base security-header line, other
    # deployed targets serve no noindex; (d) every nginx annotation is one the
    # Traefik provider documents.
    if is_wi_target "$rel"; then
        inv15_bad=0
        awk "$INGRESS_AWK" "$render" > "$TMP/ing.tsv"
        mapfile -t inv15_ings < <(awk -F'\t' '$1 == "ING" { print $2 }' "$TMP/ing.tsv")
        (( ${#inv15_ings[@]} > 0 )) || parse_fail "[$rel] INV-15 found 0 Ingress documents in the render. A deployed target always renders jtoye-ingress, so either the render lost it or INGRESS_AWK is blind; every TLS and annotation check would pass vacuously. Fix it, do not delete the invariant."
        inv15_tls=$(awk -F'\t' '$1 == "TLS"' "$TMP/ing.tsv" | wc -l)
        (( inv15_tls > 0 )) || parse_fail "[$rel] INV-15 found 0 tls entries across ${#inv15_ings[@]} Ingress(es). Deployed Ingresses terminate TLS, so INGRESS_AWK is blind (or every tls block vanished). Fix it, do not delete the invariant."
        # (a) exactly one host per entry, and a Secret named
        while IFS=$'\t' read -r _ ing idx nh sec; do
            if [[ "$nh" != "1" ]]; then
                echo "  FAIL [$rel] INV-15: Ingress '$ing' tls[$idx] carries $nh hosts (secretName '${sec:-<none>}'). One host per entry, so each host is its own ACME order: one failed challenge must not fail another host's certificate (Pitfall 4)." >&2
                inv15_bad=1
            fi
            if [[ -z "$sec" ]]; then
                echo "  FAIL [$rel] INV-15: Ingress '$ing' tls[$idx] names no secretName." >&2
                inv15_bad=1
            fi
        done < <(awk -F'\t' '$1 == "TLS"' "$TMP/ing.tsv")
        # (b) among cert-manager-managed Ingresses, each Secret is requested once
        declare -A inv15_managed=()
        for ing in "${inv15_ings[@]}"; do
            if awk -F'\t' -v i="$ing" '$1 == "ANN" && $2 == i && ($3 == "cert-manager.io/cluster-issuer" || $3 == "cert-manager.io/issuer") { f = 1 } END { exit !f }' "$TMP/ing.tsv"; then
                inv15_managed["$ing"]=1
            fi
        done
        while read -r cnt sec; do
            [[ -n "$sec" ]] || continue
            if (( cnt > 1 )); then
                echo "  FAIL [$rel] INV-15: cert-manager-managed TLS Secret '$sec' is requested by $cnt tls entries ($(awk -F'\t' -v s="$sec" '$1 == "TLS" && $5 == s { printf "%s[%s] ", $2, $3 }' "$TMP/ing.tsv")). Two Certificates for one Secret contend over it; one Ingress must own it and the others reference it with no cert-manager annotation." >&2
                inv15_bad=1
            fi
        done < <(awk -F'\t' -v m=" ${!inv15_managed[*]} " '$1 == "TLS" && index(m, " " $2 " ") { print $5 }' "$TMP/ing.tsv" | LC_ALL=C sort | uniq -c)
        # (c) noindex
        for ing in "${inv15_ings[@]}"; do
            robots=$(awk -F'\t' -v i="$ing" '$1 == "SNIP" && $2 == i && /X-Robots-Tag/ && tolower($0) ~ /noindex/' "$TMP/ing.tsv" | wc -l)
            if is_noindex_target "$rel"; then
                if (( robots == 0 )); then
                    echo "  FAIL [$rel] INV-15: Ingress '$ing' serves no X-Robots-Tag noindex. Every staging Ingress must tell crawlers to stay away (D-08, T-29-29): staging is real-looking product content." >&2
                    inv15_bad=1
                fi
                awk -F'\t' -v i="$ing" '$1 == "SNIP" && $2 == i { print $3 }' "$TMP/ing.tsv" > "$TMP/inv15_snip"
                while IFS= read -r hdr; do
                    if ! grep -qxF -- "$hdr" "$TMP/inv15_snip"; then
                        echo "  FAIL [$rel] INV-15: Ingress '$ing' configuration-snippet lacks base header line: $hdr" >&2
                        echo "        The staging snippet REPLACES base's (it cannot append), so it must carry every base" >&2
                        echo "        more_set_headers line verbatim; a drifted copy silently drops a security header." >&2
                        inv15_bad=1
                    fi
                done < "$TMP/inv15_base_headers"
            elif (( robots > 0 )); then
                echo "  FAIL [$rel] INV-15: Ingress '$ing' serves X-Robots-Tag noindex in $rel. Only ${NOINDEX_TARGETS[*]} is kept out of search engines; a deployed target outside that list is indexable (D-08), and a stray noindex would de-list the live storefront." >&2
                inv15_bad=1
            fi
        done
        # (d) annotation support
        inv15_ann=0
        while IFS=$'\t' read -r _ ing key; do
            [[ "$key" == nginx.ingress.kubernetes.io/* ]] || continue
            inv15_ann=$((inv15_ann + 1))
            if [[ "$TRAEFIK_SUPPORTED_LIST" != *" ${key#nginx.ingress.kubernetes.io/} "* ]]; then
                echo "  FAIL [$rel] INV-15: Ingress '$ing' uses $key, which the Traefik Ingress-NGINX provider does not document (k8s/platform/traefik/supported-annotations.txt). Traefik ignores what it does not implement, so the behaviour would be silently absent (D-29)." >&2
                inv15_bad=1
            fi
        done < <(awk -F'\t' '$1 == "ANN"' "$TMP/ing.tsv")
        (( inv15_ann > 0 )) || parse_fail "[$rel] INV-15 found 0 nginx.ingress.kubernetes.io annotations. jtoye-ingress always carries them, so INGRESS_AWK is blind. Fix it, do not delete the invariant."
        if (( inv15_bad != 0 )); then
            FAILED=1
            inv15_msg="FAIL"
        else
            if is_noindex_target "$rel"; then inv15_ni="noindex on all"; else inv15_ni="no noindex"; fi
            inv15_msg="OK (${#inv15_ings[@]} Ingresses, $inv15_tls tls entries x1 host, ${#inv15_managed[@]} managed with unique Secrets, $inv15_ni, $inv15_ann nginx annotations all Traefik-documented)"
        fi
        unset inv15_managed
    else
        inv15_msg="SKIP (not in WI_TARGETS)"
    fi

    # ---------------- INV-16 (29-22, D-21 / D-30) ----------------
    # core-java's JVM-wide truststore is the JDK cacerts PLUS the cluster CA, never
    # the CA alone; JVM flags arrive as a literal JAVA_TOOL_OPTIONS, never JAVA_OPTS.
    inv16_bad=0
    inv16_required=0
    for t in "${TRUSTSTORE_TARGETS[@]}"; do [[ "$rel" == "$t" ]] && inv16_required=1; done
    (( inv16_required )) && TRUSTSTORE_TARGET_SEEN["$rel"]=1
    awk -v want=core-java -v tsp="$TRUSTSTORE_PATH" "$INIT_ITEMS_AWK" "$render" > "$TMP/inv16_init.tsv"
    inv16_builders=$(awk -F'\t' '$1 == "INIT" && $3 == "truststore-builder"' "$TMP/inv16_init.tsv" | wc -l)
    # Env of every container (main and init) of Deployment core-java, by exact path:
    # <container path> TAB <env item path> TAB <env name>
    awk -F'\t' '$1 == "Deployment" && $2 == "core-java" && $3 ~ /^spec::template::spec::(containers|initContainers)::\[[0-9]+\]::env::\[[0-9]+\]::name$/ {
        c = $3; sub(/::env::.*$/, "", c); e = $3; sub(/::name$/, "", e); print c "\t" e "\t" $4 }' "$TMP/inv11_paths.tsv" > "$TMP/inv16_env.tsv"
    inv16_jto=$(awk -F'\t' '$3 == "JAVA_TOOL_OPTIONS"' "$TMP/inv16_env.tsv" | wc -l)
    if (( inv16_required == 0 && inv16_builders == 0 && inv16_jto == 0 )); then
        inv16_msg="not applicable (no truststore component in this render)"
    else
        if (( inv16_builders == 0 )); then
            echo "  FAIL [$rel] INV-16: $rel renders no truststore-builder init container on Deployment core-java. Its core-java then trusts no cluster CA, so every required-STARTTLS send to Mailpit fails (D-20/D-21); list ../components/cluster-ca-truststore in the overlay." >&2
            inv16_bad=1
        fi
        # (a) every core-java-image init container that imports must copy cacerts first
        while IFS=$'\t' read -r _ idx iname iimg cpl impl; do
            [[ "$iimg" =~ $CORE_JAVA_IMAGE_RE ]] || continue
            (( impl > 0 )) || continue
            if (( cpl == 0 || cpl > impl )); then
                echo "  FAIL [$rel] INV-16: Deployment core-java init container '$iname' builds a CA-only truststore: no uncommented 'cp …lib/security/cacerts $TRUSTSTORE_PATH' line precedes its keytool -importcert (cp line $cpl, import line $impl). javax.net.ssl.trustStore REPLACES the JDK default for every TLS client in the JVM, so a store without the public CAs breaks Entra, Blob and Stripe at once (D-30, T-29-81)." >&2
                inv16_bad=1
            fi
        done < <(awk -F'\t' '$1 == "INIT"' "$TMP/inv16_init.tsv")
        # (c) no JAVA_OPTS on any core-java container
        while IFS=$'\t' read -r cpath epath ename; do
            [[ "$ename" == "JAVA_OPTS" ]] || continue
            cname="$(path_get "$TMP/inv11_paths.tsv" Deployment core-java "$cpath::name")"
            echo "  FAIL [$rel] INV-16: Deployment core-java container '$cname' sets JAVA_OPTS ($epath). The image ENTRYPOINT expands JAVA_OPTS from the Dockerfile (container sizing, G1, the scrimage WebP dir); an env value REPLACES those flags. Use JAVA_TOOL_OPTIONS, which the JVM reads in addition." >&2
            inv16_bad=1
        done < "$TMP/inv16_env.tsv"
        # (b) the main container's JAVA_TOOL_OPTIONS: exactly one literal naming the store
        inv16_main=$(awk -F'\t' -v w=core-java '$1 == "Deployment" && $2 == "core-java" && $3 ~ /^spec::template::spec::containers::\[[0-9]+\]::name$/ && $4 == w { p = $3; sub(/::name$/, "", p); print p; exit }' "$TMP/inv11_paths.tsv")
        if [[ -z "$inv16_main" ]]; then
            parse_fail "[$rel] INV-16 found no container named core-java in Deployment core-java by path — the render lost it or the path walker is blind."
        fi
        inv16_main_jto=$(awk -F'\t' -v c="$inv16_main" '$1 == c && $3 == "JAVA_TOOL_OPTIONS" { print $2 }' "$TMP/inv16_env.tsv")
        inv16_main_n=$(grep -c . <<< "$inv16_main_jto" || true)
        if (( inv16_main_n != 1 )); then
            echo "  FAIL [$rel] INV-16: Deployment core-java container 'core-java' sets JAVA_TOOL_OPTIONS $inv16_main_n time(s), expected exactly 1 (the literal naming -Djavax.net.ssl.trustStore=$TRUSTSTORE_PATH)." >&2
            inv16_bad=1
        else
            jv="$(path_get "$TMP/inv11_paths.tsv" Deployment core-java "$inv16_main_jto::value")"
            jf="$(path_get "$TMP/inv11_paths.tsv" Deployment core-java "$inv16_main_jto::valueFrom")"
            if [[ "$jv" == "(ABSENT)" || "$jf" != "(ABSENT)" ]]; then
                echo "  FAIL [$rel] INV-16: Deployment core-java container 'core-java' JAVA_TOOL_OPTIONS is not a literal value ($inv16_main_jto carries valueFrom or no value). A flag from a Secret or ConfigMap is invisible to every render check (D-08, D-30)." >&2
                inv16_bad=1
            elif [[ "$jv" != *"-Djavax.net.ssl.trustStore=$TRUSTSTORE_PATH"* ]]; then
                echo "  FAIL [$rel] INV-16: Deployment core-java container 'core-java' JAVA_TOOL_OPTIONS does not name -Djavax.net.ssl.trustStore=$TRUSTSTORE_PATH (value: $jv)." >&2
                inv16_bad=1
            fi
        fi
        if ! awk -F'\t' -v c="$inv16_main::volumeMounts::" -v tp="${TRUSTSTORE_PATH%/*}" '$1 == "Deployment" && $2 == "core-java" && index($3, c) == 1 && $3 ~ /::mountPath$/ && $4 == tp { f = 1 } END { exit !f }' "$TMP/inv11_paths.tsv"; then
            echo "  FAIL [$rel] INV-16: Deployment core-java container 'core-java' does not mount ${TRUSTSTORE_PATH%/*}, so -Djavax.net.ssl.trustStore names a file it cannot read." >&2
            inv16_bad=1
        fi
        if (( inv16_bad != 0 )); then
            FAILED=1
            inv16_msg="FAIL"
        else
            inv16_msg="OK (truststore-builder copies the JDK cacerts before keytool -importcert; JAVA_TOOL_OPTIONS x1 literal naming $TRUSTSTORE_PATH; 0 JAVA_OPTS)"
        fi
    fi

    # ---------------- INV-18 (29-22, D-33) ----------------
    # Every target that renders the mailpit component: the public mail UI is
    # authenticated and on 8025 only, no Service publishes Mailpit, and Mailpit is
    # configured to send nothing (no relay/forward, Send API locked).
    inv18_bad=0
    inv18_dep=$(awk -F'\t' '$1 == "Deployment" && $2 == "mailpit" && $3 == "kind" { f = 1 } END { print f + 0 }' "$TMP/inv11_paths.tsv")
    inv18_svc=$(awk -F'\t' '$1 == "Service" && $3 == "spec::selector::app" && $4 == "mailpit" { print $2 }' "$TMP/inv11_paths.tsv" | sort -u)
    # Ingress paths whose backend is Service mailpit: <ingress> TAB <path prefix>
    inv18_routes=$(awk -F'\t' '$1 == "Ingress" && $3 ~ /^spec::rules::\[[0-9]+\]::http::paths::\[[0-9]+\]::backend::service::name$/ && $4 == "mailpit" {
        p = $3; sub(/::backend::service::name$/, "", p); print $2 "\t" p }' "$TMP/inv11_paths.tsv")
    if (( inv18_dep == 0 )) && [[ -z "$inv18_svc" && -z "$inv18_routes" ]]; then
        inv18_msg="not applicable (no mailpit component in this render)"
    else
        (( inv18_dep == 1 )) || parse_fail "[$rel] INV-18: the render has a Service or Ingress route for mailpit but no Deployment mailpit (by path). Either the component lost its workload or the path walker is blind; the container rules would pass vacuously."
        [[ -n "$inv18_routes" ]] || parse_fail "[$rel] INV-18 found 0 Ingress backends to Service mailpit in a render that runs Deployment mailpit. The D-33 mail UI route is part of the component, so either it vanished or the path walker is blind; the auth rules would pass vacuously."
        # (a) each route: 8025 and basic auth from a same-namespace auth-file Secret
        while IFS=$'\t' read -r ing pfx; do
            [[ -n "$ing" ]] || continue
            host_pfx="${pfx%%::http::*}"
            ihost="$(path_get "$TMP/inv11_paths.tsv" Ingress "$ing" "$host_pfx::host")"
            pnum="$(path_get "$TMP/inv11_paths.tsv" Ingress "$ing" "$pfx::backend::service::port::number")"
            pname="$(path_get "$TMP/inv11_paths.tsv" Ingress "$ing" "$pfx::backend::service::port::name")"
            if [[ "$pnum" == "(ABSENT)" && "$pname" != "(ABSENT)" ]]; then
                pnum=$(awk -F'\t' -v n="$pname" '$1 == "Service" && $2 == "mailpit" && $3 ~ /^spec::ports::\[[0-9]+\]::name$/ && $4 == n { p = $3; sub(/::name$/, "::port", p); print p; exit }' "$TMP/inv11_paths.tsv")
                [[ -n "$pnum" ]] && pnum="$(path_get "$TMP/inv11_paths.tsv" Service mailpit "$pnum")" || pnum="(no such port name '$pname')"
            fi
            if [[ "$pnum" != "8025" ]]; then
                echo "  FAIL [$rel] INV-18: Ingress '$ing' routes host '$ihost' to Service mailpit on port $pnum, not 8025. 1025 is SMTP and is never published (T-29-109); only the UI/API port 8025 may sit behind the authenticated edge." >&2
                inv18_bad=1
            fi
            atype="$(path_get "$TMP/inv11_paths.tsv" Ingress "$ing" "metadata::annotations::nginx.ingress.kubernetes.io/auth-type")"; atype="${atype//\"/}"
            asec="$(path_get "$TMP/inv11_paths.tsv" Ingress "$ing" "metadata::annotations::nginx.ingress.kubernetes.io/auth-secret")"; asec="${asec//\"/}"
            astype="$(path_get "$TMP/inv11_paths.tsv" Ingress "$ing" "metadata::annotations::nginx.ingress.kubernetes.io/auth-secret-type")"; astype="${astype//\"/}"
            if [[ "$atype" != "basic" ]]; then
                echo "  FAIL [$rel] INV-18: Ingress '$ing' routes host '$ihost' to Service mailpit without basic auth (auth-type '$atype'). Anyone could read every staging email, reset and invite links included (D-33, T-29-108)." >&2
                inv18_bad=1
            fi
            if [[ "$asec" == "(ABSENT)" || -z "$asec" || "$asec" == */* ]]; then
                echo "  FAIL [$rel] INV-18: Ingress '$ing' auth-secret '$asec' is not a same-namespace Secret name. Traefik resolves auth-secret in the Ingress's own namespace (cross-namespace resources are off); an unresolvable Secret skips the route." >&2
                inv18_bad=1
            fi
            if [[ "$astype" != "(ABSENT)" && "$astype" != "auth-file" ]]; then
                echo "  FAIL [$rel] INV-18: Ingress '$ing' auth-secret-type '$astype', expected auth-file (key 'auth' as htpasswd lines, the shape scripts/staging-secrets.sh writes)." >&2
                inv18_bad=1
            fi
        done <<< "$inv18_routes"
        # (b) every Service selecting app=mailpit is ClusterIP
        while IFS= read -r sv; do
            [[ -n "$sv" ]] || continue
            st_type="$(path_get "$TMP/inv11_paths.tsv" Service "$sv" spec::type)"
            [[ "$st_type" == "(ABSENT)" ]] && st_type=ClusterIP
            if [[ "$st_type" != "ClusterIP" ]]; then
                echo "  FAIL [$rel] INV-18: Service '$sv' selects app=mailpit and is type $st_type. A LoadBalancer or NodePort publishes SMTP 1025 and an unauthenticated 8025 (T-29-109)." >&2
                inv18_bad=1
            fi
        done <<< "$inv18_svc"
        # (c) the mailpit container: no relay/forward/accept-any/chaos, no envFrom, the lock set
        inv18_c=$(awk -F'\t' '$1 == "Deployment" && $2 == "mailpit" && $3 ~ /^spec::template::spec::containers::\[[0-9]+\]::name$/ && $4 == "mailpit" { p = $3; sub(/::name$/, "", p); print p; exit }' "$TMP/inv11_paths.tsv")
        [[ -n "$inv18_c" ]] || parse_fail "[$rel] INV-18 found no container named mailpit in Deployment mailpit by path."
        inv18_env=$(awk -F'\t' -v c="$inv18_c::env::" '$1 == "Deployment" && $2 == "mailpit" && index($3, c) == 1 && $3 ~ /::name$/ { print $3 "\t" $4 }' "$TMP/inv11_paths.tsv")
        while IFS=$'\t' read -r epath ename; do
            [[ -n "$ename" ]] || continue
            if [[ "$ename" =~ ^(MP_SMTP_RELAY_[A-Z_]+|MP_SMTP_FORWARD_[A-Z_]+|MP_SEND_API_AUTH_ACCEPT_ANY|MP_ENABLE_CHAOS)$ ]]; then
                echo "  FAIL [$rel] INV-18: Deployment mailpit container 'mailpit' sets $ename. Mailpit must send nothing: no relay, release or forward, no accept-any Send API, no chaos (D-33, T-29-110)." >&2
                inv18_bad=1
            fi
        done <<< "$inv18_env"
        if awk -F'\t' -v c="$inv18_c::envFrom" '$1 == "Deployment" && $2 == "mailpit" && index($3, c) == 1 { f = 1 } END { exit !f }' "$TMP/inv11_paths.tsv"; then
            echo "  FAIL [$rel] INV-18: Deployment mailpit container 'mailpit' uses envFrom, which imports env names the render cannot enumerate (a relay setting could arrive unseen)." >&2
            inv18_bad=1
        fi
        inv18_flags=""; inv18_frc=0
        inv18_flags=$(grep -nE -e '--(smtp-relay|smtp-forward|send-api-auth-accept-any|enable-chaos)' "$render") || inv18_frc=$?
        (( inv18_frc <= 1 )) || parse_fail "[$rel] INV-18: grep failed (rc=$inv18_frc) reading the render."
        if [[ -n "$inv18_flags" ]]; then
            echo "  FAIL [$rel] INV-18: the render names a Mailpit send flag: ${inv18_flags%%$'\n'*}" >&2
            inv18_bad=1
        fi
        inv18_lock=$(awk -F'\t' '$2 == "MP_SEND_API_AUTH_FILE" { print $1 }' <<< "$inv18_env")
        if [[ -z "$inv18_lock" ]]; then
            echo "  FAIL [$rel] INV-18: Deployment mailpit container 'mailpit' does not set MP_SEND_API_AUTH_FILE. Without it Mailpit falls back to its (unset) UI auth and the Send API is OPEN: anyone reaching 8025 could inject mail testers trust (T-29-110)." >&2
            inv18_bad=1
        elif [[ "$(path_get "$TMP/inv11_paths.tsv" Deployment mailpit "${inv18_lock%::name}::value")" == "(ABSENT)" ]]; then
            echo "  FAIL [$rel] INV-18: Deployment mailpit container 'mailpit' MP_SEND_API_AUTH_FILE is not a literal path." >&2
            inv18_bad=1
        fi
        if (( inv18_bad != 0 )); then
            FAILED=1
            inv18_msg="FAIL"
        else
            inv18_msg="OK ($(grep -c . <<< "$inv18_routes") route(s) to mailpit on 8025 behind basic auth from a same-namespace auth-file Secret; $(grep -c . <<< "$inv18_svc") ClusterIP Service(s); no relay/forward/accept-any/chaos; Send API locked)"
        fi
    fi

    # ---------------- INV-23 (issue #972) ----------------
    # Every target that renders the mailpit component: Mailpit's container supervises the
    # process and restarts it when its certificate or key changes on disk, and STARTTLS stays
    # required. See the header for the markers read.
    inv23_bad=0
    if (( inv18_dep == 0 )); then
        inv23_msg="not applicable (no mailpit component in this render)"
    else
        inv23_c=$(awk -F'\t' '$1 == "Deployment" && $2 == "mailpit" && $3 ~ /^spec::template::spec::containers::\[[0-9]+\]::name$/ && $4 == "mailpit" { p = $3; sub(/::name$/, "", p); print p; exit }' "$TMP/inv11_paths.tsv")
        [[ -n "$inv23_c" ]] || parse_fail "[$rel] INV-23 found no container named mailpit in Deployment mailpit by path."
        inv23_env=$(awk -F'\t' -v c="$inv23_c::env::" '$1 == "Deployment" && $2 == "mailpit" && index($3, c) == 1 && $3 ~ /::name$/ { print $3 "\t" $4 }' "$TMP/inv11_paths.tsv")
        for inv23_v in MP_SMTP_REQUIRE_STARTTLS MP_SMTP_TLS_CERT MP_SMTP_TLS_KEY; do
            inv23_p=$(awk -F'\t' -v v="$inv23_v" '$2 == v { print $1; exit }' <<< "$inv23_env")
            if [[ -z "$inv23_p" ]]; then
                echo "  FAIL [$rel] INV-23: Deployment mailpit container 'mailpit' does not set $inv23_v." >&2
                inv23_bad=1; continue
            fi
            inv23_val=$(path_get "$TMP/inv11_paths.tsv" Deployment mailpit "${inv23_p%::name}::value")
            unq "$inv23_val"
            if [[ "$inv23_v" == MP_SMTP_REQUIRE_STARTTLS ]]; then
                if [[ "$UNQ" != true ]]; then
                    echo "  FAIL [$rel] INV-23: Deployment mailpit container 'mailpit' MP_SMTP_REQUIRE_STARTTLS is not the literal true (got '$inv23_val'). STARTTLS is required on both ends (D-20)." >&2
                    inv23_bad=1
                fi
            elif [[ "$inv23_val" == "(ABSENT)" || -z "$UNQ" ]]; then
                echo "  FAIL [$rel] INV-23: Deployment mailpit container 'mailpit' $inv23_v is not a literal path; the supervisor hashes exactly the files these variables name." >&2
                inv23_bad=1
            fi
        done
        # the live (comment-free) text of the Deployment's `mailpit` CONTAINER only: not the whole
        # `containers:` section (a sidecar carrying the same tokens must not satisfy the markers),
        # not the init container's script, not the rest of the pod spec. Each list item of
        # `containers:` starts at indent 6 with `- `; the item is kept when its `name:` is mailpit.
        awk '/^---[[:space:]]*$/ { if (doc ~ /\nkind: Deployment\n/ && doc ~ /\n  name: mailpit\n/) printf "%s", doc; doc = ""; next } { doc = doc "\n" $0 } END { if (doc ~ /\nkind: Deployment\n/ && doc ~ /\n  name: mailpit\n/) printf "%s", doc }' "$render" | awk '/^      containers:$/ { on = 1; next } on && /^      [^ -]/ { on = 0; flush() } on { if ($0 ~ /^      - /) flush(); buf = buf $0 "\n"; if ($0 ~ /^(      - |        )name: mailpit$/) hit = 1 } END { flush() } function flush() { if (hit) printf "%s", buf; buf = ""; hit = 0 }' > "$TMP/inv23_containers.txt"
        [[ -s "$TMP/inv23_containers.txt" ]] || parse_fail "[$rel] INV-23 could not read the containers section of Deployment mailpit from the render; the supervisor markers would pass vacuously."
        if ! grep -qE '^(      - |        )command:$' "$TMP/inv23_containers.txt" \
           || ! grep -qF -e 'sha256sum' -e 'trap ' -e 'kill -TERM' "$TMP/inv23_containers.txt"; then
            echo "  FAIL [$rel] INV-23: Deployment mailpit container 'mailpit' has no certificate-reload supervisor. Mailpit reads its certificate only at start, so a cert-manager renewal would leave it serving the old certificate until it expires (issue #972)." >&2
            inv23_bad=1
        fi
        if (( inv23_bad == 0 )); then
            if grep -qE "(^|[;&|[:space:]])exec[[:space:]]+[\"']?/?mailpit" "$TMP/inv23_containers.txt"; then
                echo "  FAIL [$rel] INV-23: Deployment mailpit container 'mailpit' execs Mailpit, replacing the supervisor with the process that only reads its certificate at start (issue #972)." >&2
                inv23_bad=1
            fi
            if ! grep -qF -e 'sha256sum "$MP_SMTP_TLS_CERT" "$MP_SMTP_TLS_KEY"' "$TMP/inv23_containers.txt"; then
                echo "  FAIL [$rel] INV-23: Deployment mailpit container 'mailpit' does not hash the files MP_SMTP_TLS_CERT and MP_SMTP_TLS_KEY name (expected: sha256sum \"\$MP_SMTP_TLS_CERT\" \"\$MP_SMTP_TLS_KEY\")." >&2
                inv23_bad=1
            fi
            if ! grep -qF -e '/mailpit &' "$TMP/inv23_containers.txt"; then
                echo "  FAIL [$rel] INV-23: Deployment mailpit container 'mailpit' does not run Mailpit in the background under the supervisor (expected: /mailpit &)." >&2
                inv23_bad=1
            fi
            if ! grep -qF -e '[ "$now" != "$start" ]' "$TMP/inv23_containers.txt"; then
                echo "  FAIL [$rel] INV-23: Deployment mailpit container 'mailpit' never compares the current hash with the start hash (expected: [ \"\$now\" != \"\$start\" ])." >&2
                inv23_bad=1
            fi
            # two stops: the SIGTERM trap's and the one taken when the hash changes
            if (( $(grep -cF -e 'kill -TERM "$pid"' "$TMP/inv23_containers.txt") < 2 )); then
                echo "  FAIL [$rel] INV-23: Deployment mailpit container 'mailpit' never stops Mailpit on a change (expected: kill -TERM \"\$pid\" in the change branch as well as in the trap)." >&2
                inv23_bad=1
            fi
            if ! grep -qF -e 'trap ' "$TMP/inv23_containers.txt"; then
                echo "  FAIL [$rel] INV-23: Deployment mailpit container 'mailpit' installs no trap, so a rollout's SIGTERM would not reach Mailpit." >&2
                inv23_bad=1
            fi
        fi
        if (( inv23_bad != 0 )); then
            FAILED=1
            inv23_msg="FAIL"
        else
            inv23_msg="OK (mailpit supervisor hashes MP_SMTP_TLS_CERT/KEY and restarts the container on a change; MP_SMTP_REQUIRE_STARTTLS true)"
        fi
    fi

    # ---------------- INV-20 (#976) ----------------
    # Every Keycloak container with proxy headers on, in ANY spelling, must say WHICH
    # peers it believes them from, once, as a private CIDR list it can start on. See
    # the header for the rules and the helpers (inv20_*) for the spellings read.
    inv20_bad=0
    inv20_n=0
    inv20_required=0
    for t in "${KEYCLOAK_TARGETS[@]}"; do [[ "$rel" == "$t" ]] && inv20_required=1; done
    inv20_ctrs=$(awk -F'\t' -v re="$KEYCLOAK_IMAGE_RE" "$KEYCLOAK_CTRS_AWK" "$TMP/inv11_paths.tsv" | sort)
    if [[ -z "$inv20_ctrs" ]]; then
        (( inv20_required == 0 )) || parse_fail "[$rel] INV-20: $rel is in KEYCLOAK_TARGETS but renders no Keycloak container (image matching '$KEYCLOAK_IMAGE_RE', a KC_* env, or a pod labelled app: keycloak), so the proxy-trust check would pass over nothing. Either the component left the overlay or the path walker is blind; fix it in the SAME change, do not delete the invariant."
        inv20_msg="not applicable (no Keycloak container in this render)"
    else
        (( inv20_required == 0 )) || KEYCLOAK_TARGET_SEEN["$rel"]=1
        while IFS=$'\t' read -r k20 n20 c20; do
            [[ -n "$k20" ]] || continue
            inv20_n=$((inv20_n + 1))
            unq "$(path_get "$TMP/inv11_paths.tsv" "$k20" "$n20" "$c20::name")"; cn20="$UNQ"
            who20="$k20 $n20 container '$cn20'"
            I20_HDR=0; I20_LEGACY=0; I20_UNJ=0; I20_TV=(); I20_TS=()
            # (1) env items, by name
            while IFS=$'\t' read -r ipath iname; do
                [[ -n "$ipath" ]] || continue
                unq "$iname"; inv20_apply_name "$UNQ" "env $UNQ" item "$ipath"
            done <<< "$(awk -F'\t' -v k="$k20" -v n="$n20" -v c="$c20::env::" '$1 == k && $2 == n && index($3, c) == 1 { r = substr($3, length(c) + 1); if (r ~ /^\[[0-9]+\]::name$/) { sub(/::name$/, "", $3); print $3 "\t" $4 } }' "$TMP/inv11_paths.tsv")"
            # (2) envFrom: a ConfigMap in the render is read key by key (prefix applied); a
            # Secret, or a ConfigMap that is not rendered, cannot be read: fail closed.
            while IFS= read -r efp; do
                [[ -n "$efp" ]] || continue
                sref="$(path_get "$TMP/inv11_paths.tsv" "$k20" "$n20" "$efp::secretRef::name")"
                cref="$(path_get "$TMP/inv11_paths.tsv" "$k20" "$n20" "$efp::configMapRef::name")"
                unq "$(path_get "$TMP/inv11_paths.tsv" "$k20" "$n20" "$efp::prefix")"; pfx20="$UNQ"
                [[ "$pfx20" != "(ABSENT)" ]] || pfx20=""
                if [[ "$sref" == "(ABSENT)" && "$cref" == "(ABSENT)" ]]; then
                    inv20_fail "has an envFrom item ($efp) that is neither a configMapRef nor a secretRef, so what it imports cannot be judged."
                    I20_UNJ=1
                fi
                if [[ "$sref" != "(ABSENT)" ]]; then
                    unq "$sref"
                    inv20_fail "has an envFrom Secret '$UNQ', which can set KC_PROXY_* or JAVA_OPTS this check cannot read. Name each env explicitly."
                    I20_UNJ=1
                fi
                if [[ "$cref" != "(ABSENT)" ]]; then
                    unq "$cref"; cm20="$UNQ"
                    if ! inv20_cm_exists "$cm20"; then
                        inv20_fail "has an envFrom ConfigMap '$cm20', which is not in the render, so what it imports cannot be judged."
                        I20_UNJ=1
                    else
                        while IFS=$'\t' read -r dk20 dv20; do
                            [[ -n "$dk20" ]] || continue
                            inv20_apply_name "$pfx20$dk20" "envFrom ConfigMap $cm20 key $dk20" lit "$dv20"
                        done <<< "$(awk -F'\t' -v n="$cm20" '$1 == "ConfigMap" && $2 == n && $3 ~ /^data::[^:]+$/ { k = $3; sub(/^data::/, "", k); print k "\t" $4 }' "$TMP/inv11_paths.tsv")"
                    fi
                fi
            done <<< "$(awk -F'\t' -v k="$k20" -v n="$n20" -v c="$c20::envFrom::" '$1 == k && $2 == n && index($3, c) == 1 { r = substr($3, length(c) + 1); sub(/::.*$/, "", r); print c r }' "$TMP/inv11_paths.tsv" | sort -u)"
            # (3) command / args: each item is a flag, a -D property, or something that
            # must not mention one. NOTE index(), not a regex built from the path: the
            # path carries "[0]", which a regex reads as a character class (the first
            # version of this loop never matched a single item for that reason).
            while IFS=$'\t' read -r apath av; do
                [[ -n "$av" ]] || continue
                a_o=args; [[ "$apath" == "$c20::command"* ]] && a_o=command
                inv20_token "$av" "$a_o"
            done <<< "$(awk -F'\t' -v k="$k20" -v n="$n20" -v c="$c20" '$1 == k && $2 == n && (index($3, c "::args") == 1 || index($3, c "::command") == 1) { print $3 "\t" $4 }' "$TMP/inv11_paths.tsv")"
            if (( I20_LEGACY )); then
                inv20_fail "sets the legacy KC_PROXY / --proxy (edge, reencrypt, passthrough). It trusts X-Forwarded-* from every source and has no trusted-addresses option; use KC_PROXY_HEADERS=xforwarded with KC_PROXY_TRUSTED_ADDRESSES."
            fi
            (( I20_UNJ == 0 )) || continue          # an unreadable source: already FAILed above
            (( I20_HDR > 0 )) || continue
            tcount20=${#I20_TV[@]}
            if (( tcount20 == 0 )); then
                inv20_fail "turns proxy headers on but sets no KC_PROXY_TRUSTED_ADDRESSES (env, --proxy-trusted-addresses, -Dkc.proxy-trusted-addresses or envFrom), so Keycloak believes X-Forwarded-Host/-Proto/-For from EVERY source address (#976). Set it to the ingress path's private pod range."
                continue
            fi
            if (( tcount20 > 1 )); then
                inv20_fail "sets the trusted proxy addresses $tcount20 times ($(printf '%s; ' "${I20_TS[@]}")), so which one wins cannot be judged here. Set it once."
                continue
            fi
            val20="${I20_TV[0]}"; src20=" (${I20_TS[0]})"
            if [[ -z "$val20" ]]; then
                inv20_fail "KC_PROXY_TRUSTED_ADDRESSES is empty$src20. Keycloak 26.8.1 refuses to start on an empty value (measured) and a missing one trusts every source; declare the ingress path's private pod range."
                continue
            fi
            if [[ "$val20" =~ [[:space:]] ]]; then
                # Measured with quay.io/keycloak/keycloak:26.8.1 (KC_PROXY_TRUSTED_ADDRESSES=
                # "10.244.0.0/16, 10.0.0.0/8"): the server exits 2 with "Invalid value for
                # multivalued option 'KC_PROXY_TRUSTED_ADDRESSES': list value ' 10.0.0.0/8'
                # should not have leading nor trailing whitespace". It does NOT trim, so a
                # spaced list would pass every other test here and crash-loop at rollout.
                inv20_fail "KC_PROXY_TRUSTED_ADDRESSES contains whitespace in '$val20'$src20. Keycloak 26.8.1 does not trim list entries and refuses to start (measured: \"list value ' 10.0.0.0/8' should not have leading nor trailing whitespace\"); write the list with bare commas."
                continue
            fi
            if [[ "$val20" == ,* || "$val20" == *, || "$val20" == *,,* ]]; then
                inv20_fail "KC_PROXY_TRUSTED_ADDRESSES has an empty entry in '$val20'$src20."
                continue
            fi
            inv20_entries=0
            IFS=',' read -ra ent20 <<< "$val20"
            for e20 in "${ent20[@]}"; do
                inv20_entries=$((inv20_entries + 1))
                v20="$(tp_entry_verdict "$e20")"
                if [[ -n "$v20" ]]; then
                    inv20_fail "KC_PROXY_TRUSTED_ADDRESSES entry '$e20' $v20$src20."
                fi
            done
            (( inv20_entries > 0 )) || parse_fail "[$rel] INV-20: $who20 trusted addresses '$val20' yielded 0 entries — the splitter is blind. Fix it, do not delete the invariant."
        done <<< "$inv20_ctrs"
        (( inv20_n > 0 )) || parse_fail "[$rel] INV-20: Keycloak containers were found but none was iterated."
        if (( inv20_bad != 0 )); then
            FAILED=1
            inv20_msg="FAIL"
        else
            inv20_msg="OK ($inv20_n Keycloak container(s): proxy headers trusted only from a private CIDR list, once, in a form the render can read)"
        fi
    fi

    # ---------------- INV-21 (#968 item 3) ----------------
    # Every target that renders the Grafana component: each Ingress route to Service grafana
    # carries basic auth from a same-namespace auth-file Secret that a SecretProviderClass syncs
    # as key `auth` (the same chain as the mail UI, INV-18).
    inv21_bad=0
    inv21_dep=$(awk -F'\t' '$1 == "Deployment" && $2 == "grafana" && $3 == "kind" { f = 1 } END { print f + 0 }' "$TMP/inv11_paths.tsv")
    # EVERY Service that can reach Grafana's pod: any Service selecting app=grafana, plus the one
    # named grafana (whatever its selector). A route is found by the Service it names, not by the
    # name `grafana` alone: a second Service `grafana-lb` behind an Ingress serves the same page.
    inv21_svc=$({ awk -F'\t' '$1 == "Service" && $3 == "spec::selector::app" && $4 == "grafana" { print $2 }' "$TMP/inv11_paths.tsv"
                  awk -F'\t' '$1 == "Service" && $2 == "grafana" && $3 == "kind" { print $2 }' "$TMP/inv11_paths.tsv"; } | sort -u)
    # Ingress backends to one of those Services, through spec.rules[].http.paths[] OR through
    # spec.defaultBackend (a catch-all route Traefik serves the same way):
    # <ingress> TAB <path prefix> TAB <service>
    inv21_routes=$(awk -F'\t' -v svcs="$inv21_svc" 'BEGIN { n = split(svcs, a, "\n"); for (i = 1; i <= n; i++) if (a[i] != "") s[a[i]] = 1 }
        $1 == "Ingress" && ($4 in s) && ($3 ~ /^spec::rules::\[[0-9]+\]::http::paths::\[[0-9]+\]::backend::service::name$/ || $3 == "spec::defaultBackend::service::name") {
        p = $3; sub(/::backend::service::name$/, "", p); sub(/::service::name$/, "", p); print $2 "\t" p "\t" $4 }' "$TMP/inv11_paths.tsv")
    if (( inv21_dep == 0 )) && [[ -z "$inv21_routes" && -z "$inv21_svc" ]]; then
        # Not guarded with a staging-only parse error: staging cannot render WITHOUT Grafana (the
        # azure-keyvault component patches the monitoring Deployments, and `kubectl kustomize`
        # fails on a patch with no target; shown 2026-10-20 by dropping ../components/monitoring: "no
        # resource matches strategic merge patch Deployment.v1.apps/postgres-exporter"), so such a
        # guard could never fire and would be an assertion nobody had seen fail.
        inv21_msg="not applicable (no grafana component in this render)"
    else
        (( inv21_dep == 1 )) || parse_fail "[$rel] INV-21: the render has a Service or Ingress route for Grafana but no Deployment grafana (by path). Either the component lost its workload or the path walker is blind; the auth rules would pass vacuously."
        [[ -n "$inv21_routes" ]] || parse_fail "[$rel] INV-21 found 0 Ingress backends to Service grafana in a render that runs Deployment grafana. The edge-gated Grafana route is part of the component, so either it vanished or the path walker is blind; the auth rules would pass vacuously."
        while IFS=$'\t' read -r ing pfx isvc; do
            [[ -n "$ing" ]] || continue
            if [[ "$pfx" == "spec::defaultBackend" ]]; then
                ihost="(defaultBackend: every host)"
            else
                host_pfx="${pfx%%::http::*}"
                ihost="$(path_get "$TMP/inv11_paths.tsv" Ingress "$ing" "$host_pfx::host")"
            fi
            atype="$(path_get "$TMP/inv11_paths.tsv" Ingress "$ing" "metadata::annotations::nginx.ingress.kubernetes.io/auth-type")"; atype="${atype//\"/}"
            asec="$(path_get "$TMP/inv11_paths.tsv" Ingress "$ing" "metadata::annotations::nginx.ingress.kubernetes.io/auth-secret")"; asec="${asec//\"/}"
            astype="$(path_get "$TMP/inv11_paths.tsv" Ingress "$ing" "metadata::annotations::nginx.ingress.kubernetes.io/auth-secret-type")"; astype="${astype//\"/}"
            if [[ "$atype" != "basic" ]]; then
                echo "  FAIL [$rel] INV-21: Ingress '$ing' routes host '$ihost' to Service $isvc without basic auth (auth-type '$atype'). Anyone on the internet could reach Grafana's login page and guess its admin password (#968 item 3)." >&2
                inv21_bad=1
            fi
            if [[ "$asec" == "(ABSENT)" || -z "$asec" || "$asec" == */* ]]; then
                echo "  FAIL [$rel] INV-21: Ingress '$ing' auth-secret '$asec' is not a same-namespace Secret name. Traefik resolves auth-secret in the Ingress's own namespace (cross-namespace resources are off); an unresolvable Secret skips the route." >&2
                inv21_bad=1
            elif ! awk -F'\t' -v s="$asec" '
                    $1 == "SecretProviderClass" && $3 ~ /^spec::secretObjects::\[[0-9]+\]::secretName$/ && $4 == s { p = $3; sub(/::secretName$/, "", p); pfx[$2 "\t" p] = 1 }
                    { rows[NR] = $0 }
                    END {
                        for (i = 1; i <= NR; i++) {
                            split(rows[i], f, "\t")
                            if (f[1] != "SecretProviderClass" || f[3] !~ /::data::\[[0-9]+\]::key$/ || f[4] != "auth") continue
                            q = f[3]; sub(/::data::\[[0-9]+\]::key$/, "", q)
                            if ((f[2] "\t" q) in pfx) found = 1
                        }
                        exit !found
                    }' "$TMP/inv11_paths.tsv"; then
                echo "  FAIL [$rel] INV-21: Ingress '$ing' auth-secret '$asec' is not synced as key 'auth' by any SecretProviderClass in the render. Traefik reads key 'auth' of that Secret; with nothing producing it the route is skipped and Grafana is unreachable (or, if the Secret is hand-made, unmanaged by Key Vault)." >&2
                inv21_bad=1
            fi
            if [[ "$astype" != "(ABSENT)" && "$astype" != "auth-file" ]]; then
                echo "  FAIL [$rel] INV-21: Ingress '$ing' auth-secret-type '$astype', expected auth-file (key 'auth' as htpasswd lines, the shape scripts/staging-secrets.sh writes)." >&2
                inv21_bad=1
            fi
        done <<< "$inv21_routes"
        # (c) every Service reaching Grafana's pod (selector app=grafana, or named grafana) is
        # ClusterIP, as INV-18 (b) does for mailpit: a LoadBalancer or NodePort publishes
        # Grafana's login page with no Ingress, so no edge auth, in front of it at all.
        while IFS= read -r sv; do
            [[ -n "$sv" ]] || continue
            st_type="$(path_get "$TMP/inv11_paths.tsv" Service "$sv" spec::type)"
            [[ "$st_type" == "(ABSENT)" ]] && st_type=ClusterIP
            if [[ "$st_type" != "ClusterIP" ]]; then
                echo "  FAIL [$rel] INV-21: Service '$sv' reaches Grafana's pod and is type $st_type. A LoadBalancer or NodePort publishes Grafana's login page with no basic auth in front of it (#968 item 3, as INV-18 for T-29-109)." >&2
                inv21_bad=1
            fi
        done <<< "$inv21_svc"
        if (( inv21_bad != 0 )); then
            FAILED=1
            inv21_msg="FAIL"
        else
            inv21_msg="OK ($(grep -c . <<< "$inv21_routes") route(s) to grafana behind basic auth from a same-namespace auth-file Secret that a SecretProviderClass syncs as key auth; $(grep -c . <<< "$inv21_svc") ClusterIP Service(s))"
        fi
    fi

    # ---------------- INV-22 (#999) ----------------
    # The Micrometer `environment` tag a target's core-java exports. The prod profile (every k8s
    # environment runs it) says `production`; INV22_EXPECTED names what each deployed target
    # must say instead, and a staging that loses its override is labelled production again.
    inv22_msg="FAIL"
    if [[ -z "${INV22_EXPECTED[$rel]:-}" ]]; then
        echo "  FAIL [$rel] INV-22: target $rel is not declared in INV22_EXPECTED. Every rendered target runs the prod profile (D-10), whose application-prod.yml tags every series environment=\"production\", so a target nobody declared exports metrics labelled as production unless it overrides MANAGEMENT_METRICS_TAGS_ENVIRONMENT. Declare its environment label in INV22_EXPECTED and set the env in the overlay (#999)." >&2
        FAILED=1
    else
        INV22_TARGET_SEEN["$rel"]=1
        read -r inv22_label inv22_required <<< "${INV22_EXPECTED[$rel]}"
        inv22_bad=0; inv22_ctr_n=0; inv22_set=0
        if [[ ! -f "$INV22_PROFILE_YML" ]]; then
            parse_fail "[$rel] INV-22: $INV22_PROFILE_YML not found, so the profile's own environment tag cannot be read. A reader that finds nothing never passes."
        fi
        inv22_prof="$(inv22_yml_tag "$INV22_PROFILE_YML")"
        if [[ "$inv22_prof" == "(ABSENT)" ]]; then
            parse_fail "[$rel] INV-22: core-java/src/main/resources/application-prod.yml no longer sets management.metrics.tags.environment, so MANAGEMENT_METRICS_TAGS_ENVIRONMENT would bind a key nothing documents, or the reader is blind. Re-point INV-22 at the new key in the SAME change that moves it; do not delete the invariant."
        fi
        while IFS=$'\t' read -r ck cn cpath; do
            [[ -n "$cpath" && "$ck" == Deployment && "$cn" == core-java && "$cpath" == *"::containers::["* ]] || continue
            inv22_ctr_n=$((inv22_ctr_n + 1))
            cname="$(path_get "$TMP/inv11_paths.tsv" "$ck" "$cn" "$cpath::name")"
            inv22_vals=()
            while IFS=$'\t' read -r epath ename; do
                norm="$(tr -cd '[:alnum:]' <<< "$ename" | tr '[:upper:]' '[:lower:]')"
                [[ "$norm" == "$INV22_ENV_NORM" ]] || continue
                ev="$(path_get "$TMP/inv11_paths.tsv" "$ck" "$cn" "$epath::value")"
                cmn="$(path_get "$TMP/inv11_paths.tsv" "$ck" "$cn" "$epath::valueFrom::configMapKeyRef::name")"
                cmk="$(path_get "$TMP/inv11_paths.tsv" "$ck" "$cn" "$epath::valueFrom::configMapKeyRef::key")"
                if [[ "$ev" != "(ABSENT)" ]]; then
                    unq "$ev"; ev="$UNQ"
                elif [[ "$cmn" == "app-config" && "$cmk" != "(ABSENT)" ]]; then
                    ev="$(cfg_get "$TMP/cfg.tsv" "$cmk")"
                else
                    echo "  FAIL [$rel] INV-22: $ck $cn container '$cname' sets $ename from a source the render cannot read ($epath: neither a literal value nor an app-config configMapKeyRef), so the environment label cannot be shown." >&2
                    inv22_bad=1; continue
                fi
                inv22_vals+=("$ev"); inv22_set=$((inv22_set + 1))
            done < <(awk -F'\t' -v k="$ck" -v n="$cn" -v c="$cpath::env::" '$1 == k && $2 == n && index($3, c) == 1 && $3 ~ /::env::\[[0-9]+\]::name$/ { e = $3; sub(/::name$/, "", e); print e "\t" $4 }' "$TMP/inv11_paths.tsv")
            if (( ${#inv22_vals[@]} > 1 )); then
                echo "  FAIL [$rel] INV-22: $ck $cn container '$cname' sets MANAGEMENT_METRICS_TAGS_ENVIRONMENT ${#inv22_vals[@]} times (${inv22_vals[*]}), so which value wins cannot be shown here. Set it once." >&2
                inv22_bad=1
            elif (( ${#inv22_vals[@]} == 1 )); then
                if [[ "${inv22_vals[0]}" != "$inv22_label" ]]; then
                    echo "  FAIL [$rel] INV-22: $ck $cn container '$cname' sets MANAGEMENT_METRICS_TAGS_ENVIRONMENT to '${inv22_vals[0]}', not '$inv22_label'. Every core-java series this target exports would carry environment=\"${inv22_vals[0]}\" (#999)." >&2
                    inv22_bad=1
                fi
            elif (( inv22_required == 1 )); then
                echo "  FAIL [$rel] INV-22: $ck $cn container '$cname' does not set MANAGEMENT_METRICS_TAGS_ENVIRONMENT. It runs the prod profile, whose application-prod.yml tags every series environment=\"$inv22_prof\", so $rel would export metrics labelled as production (#999). Set it to '$inv22_label' in the overlay." >&2
                inv22_bad=1
            fi
        done < <(awk -F'\t' -v img="$CORE_JAVA_IMAGE_RE" '$3 ~ /::containers::\[[0-9]+\]::image$/ && $4 ~ img { p = $3; sub(/::image$/, "", p); print $1 "\t" $2 "\t" p }' "$TMP/inv11_paths.tsv")
        (( inv22_ctr_n > 0 )) || parse_fail "[$rel] INV-22: found no core-java-image app container in Deployment core-java — the render lost it or the container reader is blind."
        if (( inv22_bad != 0 )); then
            FAILED=1
            inv22_msg="FAIL"
        elif (( inv22_set > 0 )); then
            inv22_msg="OK ($inv22_ctr_n core-java container(s) export environment=\"$inv22_label\" via MANAGEMENT_METRICS_TAGS_ENVIRONMENT; the prod profile alone would say \"$inv22_prof\")"
        else
            inv22_msg="OK ($inv22_ctr_n core-java container(s) leave the prod profile's environment=\"$inv22_prof\" in force, which is this target's label)"
        fi
    fi

    if [[ "$inv1_msg" == FAIL*|| "$inv2_msg" == FAIL* || "$inv3_msg" == FAIL* \
          || "$inv4_msg" == FAIL* || "$inv6_msg" == FAIL* || "$inv7_msg" == FAIL* \
          || "$inv8_msg" == FAIL* || "$inv9_msg" == FAIL* || "$inv10_msg" == FAIL* \
          || "$inv11_msg" == FAIL* || "$inv12_msg" == FAIL* || "$inv14_msg" == FAIL* || "$inv15_msg" == FAIL* \
          || "$inv16_msg" == FAIL* || "$inv17_msg" == FAIL* || "$inv18_msg" == FAIL* || "$inv19_msg" == FAIL* || "$inv20_msg" == FAIL* || "$inv21_msg" == FAIL* || "$inv22_msg" == FAIL* || "$inv23_msg" == FAIL* ]]; then
        echo "FAIL [$rel]: INV-1 $inv1_msg | INV-2 $inv2_msg | INV-3 $inv3_msg | INV-4 $inv4_msg | INV-6 $inv6_msg | INV-7 $inv7_msg | INV-8 $inv8_msg | INV-9 $inv9_msg | INV-10 $inv10_msg | INV-11 $inv11_msg | INV-12 $inv12_msg | INV-14 $inv14_msg | INV-15 $inv15_msg | INV-16 $inv16_msg | INV-17 $inv17_msg | INV-18 $inv18_msg | INV-19 $inv19_msg | INV-20 $inv20_msg | INV-21 $inv21_msg | INV-22 $inv22_msg | INV-23 $inv23_msg" >&2
    else
        echo "OK   [$rel]: INV-1 $inv1_msg | INV-2 $inv2_msg | INV-3 $inv3_msg | INV-4 $inv4_msg | INV-6 $inv6_msg | INV-7 $inv7_msg | INV-8 $inv8_msg | INV-9 $inv9_msg | INV-10 $inv10_msg | INV-11 $inv11_msg | INV-12 $inv12_msg | INV-14 $inv14_msg | INV-15 $inv15_msg | INV-16 $inv16_msg | INV-17 $inv17_msg | INV-18 $inv18_msg | INV-19 $inv19_msg | INV-20 $inv20_msg | INV-21 $inv21_msg | INV-22 $inv22_msg | INV-23 $inv23_msg"
    fi
done

# WI_TARGETS non-vacuity: a renamed or removed overlay must not make INV-8..INV-10
# silently stop running. Every listed target must have been rendered and checked.
for t in "${WI_TARGETS[@]}"; do
    [[ -n "${WI_TARGET_SEEN[$t]:-}" ]] || parse_fail "INV-8..INV-10: WI_TARGETS names '$t', but no kustomize target at that path was discovered, so its storage invariants never ran. Update WI_TARGETS in the SAME change that renames an overlay."
done

for t in "${!INV22_EXPECTED[@]}"; do
    [[ -n "${INV22_TARGET_SEEN[$t]:-}" ]] || parse_fail "INV-22: INV22_EXPECTED names '$t', but no kustomize target at that path was discovered, so INV-22 never ran there. Update INV22_EXPECTED in the SAME change that renames an overlay."
done

for t in "${KEYCLOAK_TARGETS[@]}"; do
    [[ -n "${KEYCLOAK_TARGET_SEEN[$t]:-}" ]] || parse_fail "INV-20: KEYCLOAK_TARGETS names '$t', but no kustomize target at that path was discovered, so INV-20 never ran there. Update KEYCLOAK_TARGETS in the SAME change that renames an overlay."
done

for t in "${TRUSTSTORE_TARGETS[@]}"; do
    [[ -n "${TRUSTSTORE_TARGET_SEEN[$t]:-}" ]] || parse_fail "INV-16: TRUSTSTORE_TARGETS names '$t', but no kustomize target at that path was discovered, so INV-16 never ran there. Update TRUSTSTORE_TARGETS in the SAME change that renames an overlay."
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
LOCAL_SECTION="LOC-1..LOC-6, LOC-7, LOC-8 SKIPPED (k8s/local/kustomization.yaml not present)"

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
    render_paths "$LOCAL_RENDER" > "$TMP/loc_paths.tsv" || parse_fail "path walker could not read $LOCAL_RENDER safely (see the PARSE ERROR above): refusing to judge a truncated value."
    (( $(wc -l < "$TMP/loc_paths.tsv") > 0 )) || parse_fail "LOC-7: the path walker emitted 0 records for the k8s/local render — it is blind. Fix render_paths.py, do not delete the invariant."
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

    # ---------------- LOC-8 ----------------
    # Review round 1 F1. k8s runs the prod profile in every environment, and
    # application-prod.yml sets mail.smtp.starttls.required as a LITERAL (D-20), which
    # SMTP_STARTTLS=false cannot undo. Local mail goes to Mailhog, which offers no
    # STARTTLS, so without this env every local-k8s email throws "STARTTLS is required
    # but host does not support STARTTLS". The relaxation lives HERE and nowhere else
    # (INV-19 forbids it in staging/production).
    loc8_bad=0
    loc8_env="SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_REQUIRED"
    loc8_prefix="$(awk -F'\t' -v env="$loc8_env" '
        $1 == "Deployment" && $2 == "core-java" && $4 == env && $3 ~ /^spec::template::spec::containers::\[[0-9]+\]::env::\[[0-9]+\]::name$/ { p = $3; sub(/::name$/, "", p); print p; exit }' "$TMP/loc_paths.tsv")"
    if [[ -z "$loc8_prefix" ]]; then
        echo "  FAIL [k8s/local] LOC-8: Deployment core-java sets no $loc8_env. Under the prod profile (application-prod.yml: mail.smtp.starttls.required=true, a literal) Mailhog, which offers no STARTTLS, makes every local email fail with 'STARTTLS is required but host does not support STARTTLS'. Add k8s/local/mail-env-patch.yaml to patches: in k8s/local/kustomization.yaml." >&2
        loc8_bad=1
    else
        loc8_val="$(path_get "$TMP/loc_paths.tsv" Deployment core-java "$loc8_prefix::value")"
        if [[ "$loc8_val" =~ ^\"(.*)\"$ || "$loc8_val" =~ ^\'(.*)\'$ ]]; then loc8_val="${BASH_REMATCH[1]}"; fi
        if [[ "$loc8_val" != "false" ]]; then
            echo "  FAIL [k8s/local] LOC-8: $loc8_env is '$loc8_val', expected the literal 'false' (Mailhog offers no STARTTLS)." >&2
            loc8_bad=1
        fi
    fi
    if (( loc8_bad != 0 )); then
        FAILED=1
        loc8_msg="FAIL"
    else
        loc8_msg="OK ($loc8_env=false on core-java: Mailhog takes plain SMTP under the prod profile)"
    fi

    if [[ "$loc1_msg" == FAIL* || "$loc2_msg" == FAIL* || "$loc3_msg" == FAIL* \
          || "$loc4_msg" == FAIL* || "$loc5_msg" == FAIL* || "$loc6_msg" == FAIL* \
          || "$loc7_msg" == FAIL* || "$loc8_msg" == FAIL* ]]; then
        echo "FAIL [k8s/local]: LOC-1 $loc1_msg | LOC-2 $loc2_msg | LOC-3 $loc3_msg | LOC-4 $loc4_msg | LOC-5 $loc5_msg | LOC-6 $loc6_msg | LOC-7 $loc7_msg | LOC-8 $loc8_msg" >&2
    else
        echo "  OK   [k8s/local] LOC-1 $loc1_msg"
        echo "  OK   [k8s/local] LOC-2 $loc2_msg"
        echo "  OK   [k8s/local] LOC-3 $loc3_msg"
        echo "  OK   [k8s/local] LOC-4 $loc4_msg"
        echo "  OK   [k8s/local] LOC-5 $loc5_msg"
        echo "  OK   [k8s/local] LOC-6 $loc6_msg"
        echo "  OK   [k8s/local] LOC-7 $loc7_msg"
        echo "  OK   [k8s/local] LOC-8 $loc8_msg"
    fi
    LOCAL_SECTION="LOC-1..LOC-6, LOC-7, LOC-8 checked on k8s/local"
    echo
fi

# ===========================================================================
if (( FAILED != 0 )); then
    fail "one or more rendered-manifest invariants are broken — see above. Each invariant pins a defect that already shipped once; fix the manifest or the docs rather than relaxing the assertion."
fi

echo "PASS: INV-1..INV-7, INV-11 and INV-12 hold across ${#TARGETS[@]} kustomize target(s); INV-8, INV-9, INV-10, INV-14, INV-15, INV-17 and INV-19 hold on ${WI_TARGETS[*]}; INV-16 holds on ${TRUSTSTORE_TARGETS[*]} (not applicable elsewhere); INV-18 holds wherever the mailpit component renders; INV-20 holds wherever Keycloak renders (required on ${KEYCLOAK_TARGETS[*]}); INV-21 holds wherever the grafana component renders; INV-23 holds wherever the mailpit component renders; $LOCAL_SECTION."
