# Phase 37: User Setup Required

**Generated:** 2026-10-08 (plan 37-08)
**Phase:** 37-real-world-operations-readiness
**Status:** Incomplete

Accepting a staff invitation (37-08, D-07 / D-26) creates the invited person's account in the
**vendor** Keycloak realm through the admin API. Two things outside this repository must be in
place in staging and production before invitations are used there. Everything in the repository
(the local realm template, k8s overlays, compose) is already done.

## Environment Variables

| Status | Variable / key | Where | Value |
|--------|----------------|-------|-------|
| [ ] | `keycloak.admin.enabled` (env `KC_ADMIN_ENABLED`) | k8s `app-config` (base is `"false"`) | `"true"` in each environment that should accept invitations. With it off, preview and accept answer 503 `staff-invite-account-service-unavailable` and the invitation stays open. |
| [ ] | `keycloak-credentials` Secret (`admin-password`) | sealed secret per environment | the master-realm admin password core-java uses for the admin API (already required by tenant offboarding and DSAR account deletion) |
| [x] | `keycloak.admin.vendor-realm` (env `KC_VENDOR_REALM`) | k8s overlays | already set by 37-08: `jtoye-prod` (base/production), `jtoye-staging` (staging), `jtoye-dev` (local). Check it names the real vendor realm of each environment. |

## Dashboard Configuration

- [ ] **Declare `tenant_id` as an admin-only user-profile attribute in the vendor realm** (staging and production)
  - Location: Keycloak admin console → realm (`jtoye-staging` / `jtoye-prod`) → *Realm settings* → *User profile* → *Create attribute*
  - Set to: name `tenant_id`; *Who can view*: admin only; *Who can edit*: admin only; not required; validator `length` min 36 max 36 (optional)
  - And: *Realm settings* → *General* → *Unmanaged attributes* stays **Disabled** (or *Admin can edit*). **Never Enabled**: that lets a vendor user set their own `tenant_id` from the account console and cross the tenant wall (T-37-19).
  - Alternative: `kc.sh import --override true` with `infra/keycloak/realm-export.template.json` (adapted to that environment's realm name and secrets).
  - Why: Keycloak 24 strips an undeclared attribute on an admin-API create, so without this an invited user's token carries no `tenant_id` and they see nothing (measured on Keycloak 24.0.5, see `infra/keycloak/README.md`, "User profile").

## Verification

After completing setup, verify against each environment (admin token from the master realm):

```bash
# 1. The profile declares tenant_id, admin-only
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" https://<keycloak>/admin/realms/<vendor-realm>/users/profile \
  | jq '.unmanagedAttributePolicy, (.attributes[] | select(.name=="tenant_id") | .permissions)'
# 2. Accept one invitation end to end, then read the created user back
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" "https://<keycloak>/admin/realms/<vendor-realm>/users?email=<invitee>&exact=true" \
  | jq '.[0].attributes.tenant_id'
```

Expected results:
- (1) prints `null` (or `"ADMIN_EDIT"`/`"ADMIN_VIEW"`, never `"ENABLED"`) and `{"view":["admin"],"edit":["admin"]}`
- (2) prints `["<the inviting tenant's id>"]`; the invitee's token carries the same `tenant_id`; the invitee's account-console attempt to change it is refused (`error-user-attribute-read-only`)

The same proof for the local compose runtime is owed to the 37-15 gate (after the rebuild and
`kc.sh import --override true`).

---

**Once all items complete:** Mark status as "Complete" at top of file.
