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
| [ ] | `security.trusted-proxies` (env `TRUSTED_PROXY_CIDRS`) | `k8s/staging/configmap-patch.yaml` and `k8s/production/configmap-patch.yaml` (both `""` = trust none; base is `""`) | the ingress-nginx controller's POD CIDR of each AKS cluster, as a comma-separated CIDR list: a PRIVATE range (RFC 1918 or fc00::/7), /8 or narrower, never `0.0.0.0/0`. 37-24 (D-19): core-java believes `X-Forwarded-For` only from these ranges; while empty, every client behind the ingress keys the public rate limit on the ingress pod and shares ONE bucket. `k8s/scripts/check-render-invariants.sh` INV-12 WARNs on the empty value and FAILS a `/0`, a prefix shorter than `/8` or a public range; core-java refuses `/0` at startup. |

## Dashboard Configuration

- [ ] **Declare `tenant_id` as an admin-only user-profile attribute in the vendor realm** (staging and production)
  - Location: Keycloak admin console → realm (`jtoye-staging` / `jtoye-prod`) → *Realm settings* → *User profile* → *Create attribute*
  - Set to: name `tenant_id`; *Who can view*: admin only; *Who can edit*: admin only; not required; validator `length` min 36 max 36 (optional)
  - And: *Realm settings* → *General* → *Unmanaged attributes* stays **Disabled** (or *Admin can edit*). **Never Enabled**: that lets a vendor user set their own `tenant_id` from the account console and cross the tenant wall (T-37-19).
  - Alternative: `kc.sh import --override true` with `infra/keycloak/realm-export.template.json` (adapted to that environment's realm name and secrets).
  - Why: Keycloak 24 strips an undeclared attribute on an admin-API create, so without this an invited user's token carries no `tenant_id` and they see nothing (measured on Keycloak 24.0.5, see `infra/keycloak/README.md`, "User profile").
- [ ] **Measure each cluster's ingress-nginx pod CIDR and declare it as the trusted-proxy range** (staging and production; 37-24, D-19)
  - Location: the cluster's networking (kubenet / Azure CNI pod CIDR), or `kubectl --context <ctx> get pods -n ingress-nginx -o wide` for the controller pods' addresses
  - Set to: `security.trusted-proxies: "<pod CIDR>"` in that overlay's `configmap-patch.yaml` (for example `"10.244.0.0/16"`), then `bash k8s/scripts/check-render-invariants.sh` (INV-12 must read OK, no WARN) and `bash k8s/scripts/render-golden.sh --write`, committing the regenerated golden with the patch
  - Never: `0.0.0.0/0`, `::/0`, anything shorter than `/8`, or a public range — INV-12 fails the render and core-java refuses `/0` at startup; a range that admits the internet lets a client choose its rate-limit identity by writing the header (UXT-006, the #88 regression)
  - Why: `X-Forwarded-For` is only trustworthy from the hop that appends it; the ingress controller is that hop, and its address range is cluster-specific and not in this repo

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

Trusted proxies (37-24), against each environment once the range is declared:

```bash
# 3. The rendered value is the declared pod CIDR, and INV-12 reads OK without a WARN
kubectl kustomize k8s/<overlay> | grep -n 'security.trusted-proxies'
bash k8s/scripts/check-render-invariants.sh | grep 'INV-12'
# 4. Through the live ingress: 40 requests with a different X-Forwarded-For each, from one client
for i in $(seq 1 40); do curl -s -o /dev/null -w '%{http_code}\n' -H "X-Forwarded-For: 198.51.100.$i" https://<api-host>/api/v1/public/shops; done | sort | uniq -c
```

Expected results:
- (3) the overlay's CIDR, and `INV-12 OK (1 private range(s): <CIDR>)` for that target
- (4) the configured public capacity (`rate-limiting.public.requests-per-minute` + `burst`) of `200`s and the rest `429` — the rotated header buys no fresh bucket. Before 37-24 this read 40 `200`s.

---

**Once all items complete:** Mark status as "Complete" at top of file.
