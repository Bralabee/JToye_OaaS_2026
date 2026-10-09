# API Coverage — Phase 37 external integrations

> Full coverage by default. Opt-outs are explicit, reasoned decisions.
> The external API this phase integrates is the **Keycloak 24.0.5 Admin REST API**, through the existing seam
> `KeycloakAdminClient` (Jackson 3 bodies since Phase 38), extended for staff invites (D-07, D-26; plan 37-08) and integrator
> credentials (D-22..D-24; plans 37-44, 37-45), and the Keycloak **token endpoint** used by credential holders (proved live in 37-47).
> Outbound webhooks (plan 37-43) are this platform's own delivery surface, not a third-party API; SMTP sending goes through the
> existing Spring `JavaMailSender` seam unchanged. Capability ids are prefixed by service.

| capability | decision | reason |
|---|---|---|
| keycloak-admin:token.password-grant (master realm, admin-cli) | INTEGRATE | |
| keycloak-admin:users.create (vendor realm, password, tenant_id) | INTEGRATE | |
| keycloak-admin:users.search-by-email-exact (vendor realm) | INTEGRATE | |
| keycloak-admin:users.read-by-location / id | INTEGRATE | |
| keycloak-admin:users.profile.read | INTEGRATE | |
| keycloak-admin:clients.create (service account, inline mappers) | INTEGRATE | |
| keycloak-admin:clients.find-by-clientId | INTEGRATE | |
| keycloak-admin:clients.service-account-user | INTEGRATE | |
| keycloak-admin:clients.client-secret.read | INTEGRATE | |
| keycloak-admin:clients.client-secret.regenerate | INTEGRATE | |
| keycloak-admin:clients.delete | INTEGRATE | |
| keycloak-admin:clients.default-client-scopes.assign | INTEGRATE | |
| keycloak-admin:client-scopes.list (scope id lookup for the fallback) | INTEGRATE | |
| keycloak-admin:clients.protocol-mappers.models (separate mapper endpoint) | OPT-OUT | not needed: mappers are sent inline on create, the representation the realm import already uses; the separate endpoint is only a fallback if live proof in 37-47 shows inline mappers ignored |
| keycloak-oidc:token.client-credentials (credential holder obtains a token) | INTEGRATE | |
| keycloak-admin:users.update-enabled / users.logout (existing offboarding) | INTEGRATE | |
| keycloak-admin:users.delete (existing DSAR erasure) | INTEGRATE | |
| keycloak-admin:users.execute-actions-email | OPT-OUT | explicitly out of scope: owner ruling D-26 collects the invitee's password on J'Toye's own accept page; a second Keycloak email would contradict D-07 (invite sent through EmailNotificationService) |
| keycloak-admin:users.reset-password | OPT-OUT | not needed: the password is set at creation; later resets stay Keycloak's own self-service flow |
| keycloak-admin:users.sessions.logout-on-revoke | OPT-OUT | explicitly out of scope: D-08 ends access on the next API request, not the Keycloak session (Deferred Ideas) |
| keycloak-admin:users.impersonation (sign the invitee in server-side) | OPT-OUT | not needed: the invitee signs in through the normal Keycloak flow with login_hint; server-side session minting would be a second auth path (why D-22 rejected API keys); flagged in 37-08 |
| keycloak-admin:realm.user-profile.update at runtime | OPT-OUT | explicitly out of scope: realm configuration is managed by the realm export template and kc.sh import --override true (operator user_setup for staging/production), never at runtime |
| keycloak-admin:realm.sessions.lifetimes | OPT-OUT | explicitly out of scope: D-14 keeps realm session lifetimes unchanged; longer tablet SSO is a Deferred Idea |
| keycloak-admin:clients.update (edit an existing credential's scopes or mappers) | OPT-OUT | not needed yet: a credential's scopes are fixed at creation; changing them is delete + create, which keeps every change on the audited create/revoke path |
| keycloak-admin:clients.sessions / offline-sessions | OPT-OUT | not needed: deleting the client invalidates further token minting and the deleted service account's grant row makes core refuse its next call |
| keycloak-admin:events (per-client last use) | OPT-OUT | not needed: Keycloak has no cheap per-client last-use read; core records last_used_at itself, throttled (D-24, 37-45) |
| keycloak-admin:least-privilege-service-account | OPT-OUT | not needed yet: core keeps the existing master-realm admin credentials already used for offboarding and DSAR; recorded as a residual (T-37-23, T-37-111) for a follow-up |

Notes: users.create sets emailVerified=true and the admin-only tenant_id attribute; users.profile.read is used once to author the declared user profile from the live default; clients.create sends the hardcoded tenant_id claim mapper and the core-api audience mapper inline; default-client-scopes.assign is the fallback when inline default scopes are not honoured (RESEARCH A2/A4, proved live in 37-47).
