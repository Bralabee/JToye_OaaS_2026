# API Coverage — Phase 29 external surfaces

> Full coverage by default. Opt-outs are explicit, reasoned decisions. One table; capability names are prefixed with the
> service they belong to. Plan numbers name where each INTEGRATE row is built and proven.

| capability | decision | reason |
|---|---|---|
| stripe: webhook signature verification | INTEGRATE | signed accepted, unsigned/forged refused — 29-13 registration, 29-18 three arms (D-28) |
| stripe: PaymentIntent create from a storefront order | INTEGRATE | existing app path exercised by the seed order, 29-18 (D-31) |
| stripe: PaymentIntent server-side confirm with a test payment method | INTEGRATE | seed order settlement, 29-18 (D-31) |
| stripe: events read-back for evidence | INTEGRATE | 29-18 records the event id against processed_stripe_events |
| stripe: TEST-mode key enforcement | INTEGRATE | sk_test_/whsec_ prefix checks in the writer and preflight, 29-13; livemode false asserted, 29-18 |
| stripe: live mode | OPT-OUT | explicitly out of scope — TEST mode only, live keys never enter staging (D-28) |
| stripe: Connect connected accounts, destination/direct charges, payouts | OPT-OUT | not needed yet — PAY-03 money path is Phase 30 |
| stripe: refunds | OPT-OUT | not needed yet — PAY-01 refund E2E is Phase 30 |
| stripe: subscriptions and invoices | OPT-OUT | not needed yet — PAY-02 billing is Phase 30 |
| stripe: payment links | OPT-OUT | not needed yet — PAY-04 is Phase 30 |
| azure-key-vault: secrets read via Secrets Store CSI + Workload Identity | INTEGRATE | 29-12 SPCs, 29-14 live (D-23) |
| azure-key-vault: write-once secret set by the operator | INTEGRATE | 29-13 writer |
| azure-key-vault: secret metadata list for the preflight | INTEGRATE | 29-13 preflight; CI names-only, 29-21 |
| azure-key-vault: per-secret RBAC role assignments | INTEGRATE | 29-13 --assign-access |
| azure-key-vault: soft delete and purge protection | INTEGRATE | decided at the 29-09 checkpoint |
| azure-key-vault: CSI rotation polling | OPT-OUT | not needed — env-var consumers need a pod restart on rotation anyway; rotation goes through the runbook (29-27), polling off keeps operation cost negligible |
| azure-key-vault: keys and certificates objects | OPT-OUT | not needed — TLS comes from cert-manager, no application key material is vault-held as keys |
| azure-postgresql-flexible: server create (PG 18, B2s) with one firewall rule | INTEGRATE | 29-07 script, 29-09 run |
| azure-postgresql-flexible: server parameters (azure.extensions) | INTEGRATE | 29-07/29-09 (uuid-ossp before the first Flyway run) |
| azure-postgresql-flexible: point-in-time restore to a new server | INTEGRATE | 29-20 two-arm drill (DPLY-04) |
| azure-postgresql-flexible: TLS verify-full from the JVM truststore | INTEGRATE | 29-08 config, 29-14 live |
| azure-postgresql-flexible: high availability | OPT-OUT | not available on Burstable; production SKU decides in Phase 32 (#101 HA half) |
| azure-postgresql-flexible: read replicas | OPT-OUT | not needed for staging |
| azure-postgresql-flexible: private access (VNet integration) | OPT-OUT | decided at the 29-09 checkpoint (option-c would re-plan); public access with one firewall rule |
| azure-postgresql-flexible: Entra ID database authentication | OPT-OUT | not needed yet — password roles held in Key Vault; Phase 32 may revisit |
| azure-aks: cluster create with Cilium, Workload Identity and Entra RBAC | INTEGRATE | overlay networking, OIDC issuer, Key Vault CSI add-on, local accounts off, ephemeral OS — 29-01 script, 29-09 run |
| azure-aks: credentials through Entra (az aks get-credentials + kubelogin) | INTEGRATE | 29-09 operator, 29-21 CI |
| azure-aks: Standard tier uptime SLA | OPT-OUT | cost — Free tier inside the D-03 ceiling; the 24 h meter check is 29-19 |
| azure-aks: cluster autoscaler | OPT-OUT | not needed — fixed 3 × B2s inside D-03 |
| azure-aks: Container Insights / Azure Monitor | OPT-OUT | explicitly out of scope — in-cluster Prometheus/Alertmanager/Grafana per D-16 |
| azure-blob: media upload, derivative read, anonymous posture | INTEGRATE | Phase 36 code; 29-14 live functional path and #626 read-back |
| azure-blob: backup upload via the write-only role | INTEGRATE | 29-20 first real backup (A7) |
| azure-blob: immutability policy lock | INTEGRATE | human step after the drill, 29-20 (never scripted) |
| azure-entra: federated identity credentials for workloads and CI | INTEGRATE | 29-01 script, 29-09 run, 29-21 CI |
| azure-cost: usage by meter (read-only) | INTEGRATE | 29-19 measured checkpoint (D-03) |
| keycloak-admin-api: realm create/replace from the transformed template | INTEGRATE | 29-04 script, 29-14 live |
| keycloak-admin-api: user create with tenant_id, partialImport fallback | INTEGRATE | 29-06 seed, 29-14 live |
| keycloak-admin-api: short-lived realm-admin seed operator create/delete | INTEGRATE | 29-06 seed |
| keycloak-admin-api: realm SMTP connection test over STARTTLS | INTEGRATE | 29-22 live (Keycloak trusts the cluster CA) |
| keycloak-admin-api: identity brokering (Google IdP) | OPT-OUT | explicitly out of scope — ADR-0005 ships it disabled |
| zoho-smtp: authenticated STARTTLS send from Alertmanager | INTEGRATE | 29-24 config, 29-25 live with the owner's inbox (D-17 corrected) |
| letsencrypt-acme: HTTP-01 issuance per host | INTEGRATE | 29-11 probe, 29-14 production-issuer certificates (D-07) |
| letsencrypt-acme: DNS-01 and wildcard certificates | OPT-OUT | explicitly out of scope — D-07 (deferred idea) |
| github-api: environments, variables, OIDC token for deploys | INTEGRATE | 29-21, 29-28 |
| github-api: issue comments and state changes | INTEGRATE | 29-12 (#100 reopen), 29-28 dispositions |
| namecheap-dns: record management API | OPT-OUT | explicitly out of scope — the owner edits four records by hand (29-11); agent DNS edits are blocked; no external-dns (D-07) |
