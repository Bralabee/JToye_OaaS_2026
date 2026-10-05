# API Coverage — Phase 31.1 external integrations

> Full coverage by default. Opt-outs are explicit, reasoned decisions.
> Detector: `api-coverage.cjs` fired on the phase scope at plan time 2026-10-05 (nouns `rest`, `api`, `endpoint`).
> Most of the signals are this platform's OWN REST endpoints (not an external integration). The external
> integrations this phase touches are: the **Keycloak Admin REST API** (existing seam `KeycloakAdminClient`, extended
> with user lookup by email and user deletion — D-03, D-01; plans 31.1-11, 31.1-16), **Stripe.js** (existing, its
> loading changes — #793; plan 31.1-04) and the **Companies House register** (read once by a person — D-14; plan 31.1-27).
> Capability ids are prefixed by service.

| capability | decision | reason |
|---|---|---|
| keycloak-admin:token.password-grant (master realm, admin-cli) | INTEGRATE | |
| keycloak-admin:users.search-by-attribute (q=tenant_id) | INTEGRATE | |
| keycloak-admin:users.search-by-email-exact | INTEGRATE | |
| keycloak-admin:users.read-representation (id, username, email, names, created) | INTEGRATE | |
| keycloak-admin:users.update-enabled | INTEGRATE | |
| keycloak-admin:users.logout | INTEGRATE | |
| keycloak-admin:users.delete | INTEGRATE | |
| keycloak-admin:users.create | OPT-OUT | explicitly out of scope: customer accounts are self-registered (ADR-0005); the platform never creates them |
| keycloak-admin:users.reset-password / execute-actions-email | OPT-OUT | not needed: account recovery stays Keycloak's own self-service flow |
| keycloak-admin:users.sessions.list | OPT-OUT | not needed: deleting the user ends its sessions; offboarding already uses users.logout |
| keycloak-admin:users.consents / offline-sessions.revoke | OPT-OUT | not needed: removed with the user on deletion |
| keycloak-admin:users.federated-identity | OPT-OUT | not needed: the customer realm has no identity providers (ADR-0005); links are removed with the user |
| keycloak-admin:users.credentials | OPT-OUT | not needed: the platform never reads or sets credentials |
| keycloak-admin:users.role-mappings / groups | OPT-OUT | not needed: customer authorisation does not use realm roles or groups |
| keycloak-admin:users.count | OPT-OUT | not needed: exact-email search answers the only question asked |
| keycloak-admin:realm.events (admin and user events) | OPT-OUT | not needed yet: the deletion outcome is recorded on dsar_request (account_deletion_status) |
| keycloak-admin:realm / clients / identity-providers configuration | OPT-OUT | explicitly out of scope: realm configuration is managed by the realm export and infra/keycloak/configure-keycloak.sh, never at runtime |
| keycloak-admin:attack-detection | OPT-OUT | not needed: brute-force protection is realm configuration, not an application call |
| stripe-js:loadStripe (pure entry, lazy) | INTEGRATE | |
| stripe-js:elements.PaymentElement | INTEGRATE | |
| stripe-js:confirmPayment | INTEGRATE | |
| stripe-js:setLoadParameters (advancedFraudSignals) | OPT-OUT | not needed: Stripe's default fraud signals stay on for the card path (RESEARCH Pattern 6); the cash path loads no Stripe code at all |
| stripe-js:CardElement / PaymentRequestButton / Link elements | OPT-OUT | not needed: PaymentElement already covers card entry on the one card path |
| companies-house:public-data-api (company profile, registered office) | OPT-OUT | explicitly out of scope: D-14 takes the registered office once from the public register, confirmed by the owner at a checkpoint; no runtime call |
