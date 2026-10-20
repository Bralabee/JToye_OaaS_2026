# Runbook — local-stack credential rotation

Owner: unassigned (see #552). Written by plan 28-10, 2026-08-10, when SEC-04's rotation half
(#552 / D-02) rotated the six local credentials the Strix pentest named — the app DB role password,
the four dev-realm OAuth2 client secrets, and the monitoring-UI admin credential — plus, by the
owner's decision that day, the `jtoye_runtime` runtime-role password (which is the same surface as
`DB_PASSWORD` after plan 28-08 repointed the app at the non-owner role).

> **GLOBAL_RULE_6 — no literal credential value appears in this file, in any commit message, or in
> any tracked artifact.** Values flow only through the machine-local, gitignored `.env` (and the
> monitoring stack's env). Every value below is generated with the system CSPRNG (`openssl rand`)
> and read back by IDENTITY, never printed. `.env` is blocked from the Write/Edit tools and from
> `git add` by a hook; edit it with a shell (`sed`) or by hand.

---

## 1. Read the fact off the RUNNING service — never off the compose file

A compose file states an **intent**; only the running service states a **fact**. Two of these three
surfaces silently ignore a config-only edit (see §2), so the *only* trustworthy reading is taken
against the live service.

| Surface | The fact, read live |
|---|---|
| DB role (`jtoye_runtime` / `DB_PASSWORD`) | `PGPASSWORD=… psql -h 127.0.0.1 -p 5433 -U jtoye_runtime -d jtoye -c 'select 1'` — over the **published** port, which pg_hba routes through `scram-sha-256` (real password auth). |
| Keycloak client secrets | a `client_credentials` token request to `http://localhost:8085/realms/jtoye-dev/protocol/openid-connect/token` — **200** = the secret the realm actually holds. |
| Grafana admin | `POST http://127.0.0.1:${GRAFANA_PORT}/login` with `{user,password}` — **200** = the password the running instance actually accepts. |

> **The database has a pg_hba TRAP that makes the obvious check vacuous.** `pg_hba.conf` carries
> `host all all 127.0.0.1/32 trust`, so a psql run **from inside** the postgres container against
> `127.0.0.1` is `trust`-authenticated and accepts **any** password — old, new, or random. It
> cannot discriminate. The `host all all all scram-sha-256` rule governs connections arriving from
> the docker gateway, i.e. from the **host** to the **published** port `5433`. Assert the DB
> credential from the host over `127.0.0.1`, port `5433` (or from a peer container by service name), never
> from inside the postgres container over its own loopback.

---

## 2. The acceptance shape — superseded FAILS and current SUCCEEDS, in the same run

`#552`'s acceptance is **not** "the new value works." The old value working is exactly what an
un-rotated credential looks like, so a one-directional check cannot tell a rotated credential from
an un-rotated one. Every surface below is proven with BOTH directions recorded in one session:
the superseded value **refused** (with its status/error) and the current value **accepted**.

Three failure shapes recur, each stated with the assertion to make **instead**:

- **Grafana applies its configured admin password only when it first CREATES the admin user.** So a
  `GF_SECURITY_ADMIN_PASSWORD` edit changes nothing on a volume that already has an admin, and the
  login that still works is the OLD one — assert a **REJECTED old credential** against the running
  instance, never a successful new one alone.
- **Keycloak's realm lives in Postgres, so dropping the `keycloak_data` volume is a no-op** and
  `--import-realm` SKIPS an existing realm — assert against a **token request** after
  `kc.sh import --override true` **plus a restart**, never against the rendered template file.
- **`docker compose start` never rebuilds, and `--force-recreate` without `--build` reuses the old
  image** — a service can come up healthy on an image built before the rotation. Assert
  `scripts/check-runtime-freshness.sh` **rc=0, per service**, after the final rebuild.

---

## 3. The four Keycloak client secrets (D-02 + D-12, one import)

Clients and their `.env` keys: `core-api`→`KEYCLOAK_CLIENT_SECRET`,
`edge-api`→`EDGE_API_CLIENT_SECRET`, `integration-catalog-ro`→`INTEGRATION_CATALOG_RO_SECRET`,
`integration-orders-rw`→`INTEGRATION_ORDERS_RW_SECRET`. All four are confidential; the three machine
clients (`edge-api`, `integration-catalog-ro`, `integration-orders-rw`) have `serviceAccountsEnabled: true`,
while `core-api` has `serviceAccountsEnabled: false` (`T-37-107`, so `KEYCLOAK_CLIENT_SECRET` cannot mint a
machine token). `client_credentials` is still the acceptance instrument for all four: KeyCloak authenticates
the client secret before checking whether a service account is enabled, so `core-api` returns `401 invalid_client`
for the superseded secret and `400 unauthorized_client` (`"Client not enabled to retrieve service account"`) for
the current secret.

```bash
# 0. capture the OLD secrets (for the same-run superseded arm) BEFORE editing .env.
#    If .env was already rotated, recover them from the RUNNING realm with kcadm:
#      kcadm get clients -r jtoye-dev -q clientId=<c> --fields id   # -> uuid
#      kcadm get clients/<uuid>/client-secret -r jtoye-dev          # -> {"value":...}
# 1. generate + write the new values into .env (system CSPRNG, never a literal in a tracked file)
for k in KEYCLOAK_CLIENT_SECRET EDGE_API_CLIENT_SECRET \
         INTEGRATION_CATALOG_RO_SECRET INTEGRATION_ORDERS_RW_SECRET; do
  sed -i "s|^${k}=.*|${k}=$(openssl rand -hex 32)|" .env
done
# 2. re-render the gitignored realm-export.json from the new .env
docker compose -f docker-compose.full-stack.yml run --rm --no-deps -T keycloak-realm-render
# 3. THE ONE IMPORT — server stopped, --override true (carries rotation AND the D-12 payload
#    that plan 28-05 staged into realm-export.template.json: do not re-edit the template)
docker compose -f docker-compose.full-stack.yml stop keycloak
docker compose -f docker-compose.full-stack.yml run --rm --no-deps \
  --entrypoint /opt/keycloak/bin/kc.sh keycloak \
  import --file /opt/keycloak/data/import/realm-export.json --override true
docker compose -f docker-compose.full-stack.yml start keycloak   # wait for health
# 4. recreate any runtime consumer of a rotated secret (the frontend uses KEYCLOAK_CLIENT_SECRET
#    for NextAuth); edge-go/mcp-server hold none.
docker compose -f docker-compose.full-stack.yml up -d --force-recreate --no-deps frontend
```

**Acceptance arm (run for EACH client, same session):**

```bash
tok(){ curl -s -o /dev/null -w '%{http_code}' -d grant_type=client_credentials \
        -d client_id="$1" --data-urlencode client_secret="$2" \
        http://localhost:8085/realms/jtoye-dev/protocol/openid-connect/token; }
tok <client> "$OLD_SECRET"   # SUPERSEDED — must be 401 (invalid_client) for all 4 clients
tok <client> "$NEW_SECRET"   # CURRENT    — must be 200 for edge-api / integration-*, or 400 (unauthorized_client) for core-api
```

---

## 4. The DB role password (`jtoye_runtime` / `DB_PASSWORD`)

```bash
# recover the genuinely-live superseded value from the running app (until core-java is recreated):
P0=$(docker exec jtoye_oaas_2026-core-java-1 printenv DB_PASSWORD)
P_NEW=$(openssl rand -hex 24)
docker exec jtoye-postgres psql -U jtoye -d jtoye -v ON_ERROR_STOP=1 \
  -c "ALTER ROLE jtoye_runtime PASSWORD '$P_NEW'"        # embed the hex; :'var' does NOT interpolate in -c
sed -i "s|^DB_PASSWORD=.*|DB_PASSWORD=${P_NEW}|" .env    # DB_MIGRATION_PASSWORD (jtoye_app) is out of scope
docker compose -f docker-compose.full-stack.yml up -d --force-recreate --no-deps core-java
bash scripts/seed-order-metric.sh   # a core-java recreate resets NoOrdersCreated; this is expected
```

**Acceptance arm (real scram, host → published port):**

```bash
PGPASSWORD="$P0"    psql -h 127.0.0.1 -p 5433 -U jtoye_runtime -d jtoye -c 'select 1'  # SUPERSEDED — non-zero rc
PGPASSWORD="$P_NEW" psql -h 127.0.0.1 -p 5433 -U jtoye_runtime -d jtoye -c 'select 1'  # CURRENT    — rc 0, "1"
```

Then re-prove the app is **healthy AND still RLS-subject** on the new credential — connect as
`jtoye_runtime` with no tenant GUC (`select count(*) from products` → **0**), pin tenant A then B,
and confirm A+B equals the superuser control total (the leading 0 is RLS, not a blind instrument).
Do the isolation arm on `products`/`orders`/`customers`, **never `shops`** — `shops` carries the
permissive `shops_public_read` policy and legitimately returns published rows with no GUC.

---

## 5. The monitoring UI admin (Grafana)

Grafana ignores `GF_SECURITY_ADMIN_PASSWORD` after first-user creation (see §2). Reset against the
RUNNING instance, then take `scripts/check-infra-exposure.sh` **C1/C2/C3 verbatim** as the acceptance
arm — do not re-derive a probe. C3 offers a RANDOM credential and requires rejection, which is what
makes C2's acceptance mean the endpoint discriminates.

```bash
OLD_GF=$(awk -F= '/^GRAFANA_ADMIN_PASSWORD=/{sub(/^[^=]*=/,"");print;exit}' .env)
NEW_GF=$(openssl rand -hex 24)
docker exec jtoye-grafana grafana-cli --homepath /usr/share/grafana admin reset-admin-password "$NEW_GF"
sed -i "s|^GRAFANA_ADMIN_PASSWORD=.*|GRAFANA_ADMIN_PASSWORD=${NEW_GF}|" .env
```

**Acceptance arm:**

```bash
login(){ curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
          --data "$(jq -n --arg u admin --arg p "$2" '{user:$u,password:$p}')" \
          "http://127.0.0.1:${GRAFANA_PORT}/login"; }
login admin "$OLD_GF"                 # SUPERSEDED — must be non-200 (401)
login admin "$NEW_GF"                 # CURRENT    — must be 200
bash scripts/check-infra-exposure.sh  # C1/C2/C3 report; stop Grafana first to see it VOID (exit 2)
```

`scripts/check-infra-exposure.sh` assertions **C1/C2/C3** are the canonical monitoring arm; §C3's
random-credential rejection is the instrument-validity control. Its VOID (exit 2) with Grafana
stopped is the "cannot measure ≠ measured fine" guard — run it to confirm the arm can fail.

---

## 6. Phase 29 — carrying these to a staging deployment

D-02's stated purpose is that Phase 29's staging secrets follow **this same path**, so the runbook
does not stop at compose. In k8s the same values arrive through the sealed-secret path:

- `k8s/base/secrets-template.yaml.example` — the tracked template (placeholders only). Plan 28-07
  added the `runtime-username`/`runtime-password` pair beside the migrator/owner pair here; the four
  client secrets and the Grafana admin credential take the same shape when the monitoring manifests
  land (DPLY-03).
- `docs/runbooks/sealed-secrets.md` — how a plaintext secret is sealed to the cluster's controller
  and committed as ciphertext. The rotation acceptance shape is unchanged: after resealing, the
  superseded value must be refused by the running pod and the current value accepted, in the same
  run — read the fact off the RUNNING service (§1), not off the manifest.

The Keycloak import (§3) has no compose-only equivalent in staging: the realm still lives in
Postgres, so the same `kc.sh import --override true` + restart applies against the deployed realm.

---

## 6.1 Staging mail UI (`mailpit-ui-credentials`)

The testers' mail UI at `https://mail-staging.olajay.co.uk` (plan 29-22, D-33) has one
shared login, user `staging-tester`. Two Key Vault objects in `jtoye-stg-kv` hold it:

- `mailpit-ui-credentials--password`, the plaintext the owner hands to testers. It is
  mounted by no pod and granted to no workload identity.
- `mailpit-ui-credentials--auth`, its apr1 htpasswd line. secret-sync syncs it into Secret
  `mailpit-ui-credentials` (key `auth`), which Traefik's basic auth reads.

Rotate when a tester leaves, or when the password may have been seen anywhere it should
not be. **Never use `scripts/staging-secrets.sh` for this.** It is write-once by design
and refuses an object that exists. Rotation writes a NEW VERSION of both objects:

```bash
export JTOYE_AZURE_SUBSCRIPTION_ID=c483d353-5f61-4587-a790-addb9ab5fb94
. scripts/lib/azure-guard.sh && az_guard_init
d=$(umask 077 && mktemp -d)
# 0. keep the OLD password for the refusal check (mode 600, never printed)
az_read keyvault secret show --vault-name jtoye-stg-kv -n mailpit-ui-credentials--password --query value -o tsv > "$d/old"
# 1. a new random value, then its apr1 line; the password reaches openssl on stdin only
openssl rand -base64 96 | LC_ALL=C tr -dc 'A-Za-z0-9' | head -c 32 > "$d/pw"
printf 'staging-tester:%s' "$(openssl passwd -apr1 -stdin < "$d/pw")" > "$d/auth"
az_mutate --no-echo keyvault secret set --vault-name jtoye-stg-kv -n mailpit-ui-credentials--password --file "$d/pw" --encoding utf-8
az_mutate --no-echo keyvault secret set --vault-name jtoye-stg-kv -n mailpit-ui-credentials--auth --file "$d/auth" --encoding utf-8
# 2. the CSI driver fetches only on mount (rotation is off), so restart the syncing pod.
#    maxUnavailable 0: the new pod mounts before the old one goes, so neither Secret it
#    keeps (this one and rabbitmq-default-user) disappears during the rollout.
kubectl --context jtoye-stg-aks -n jtoye-staging rollout restart deploy/secret-sync
kubectl --context jtoye-stg-aks -n jtoye-staging rollout status deploy/secret-sync --timeout=10m
# 3. the new password works (8/8), and the OLD one is refused with 401
bash scripts/staging-mail-ui.sh --probe --context jtoye-stg-aks
(umask 077; printf 'user = "staging-tester:%s"\n' "$(cat "$d/old")" > "$d/old.curlrc")
curl -sS -o /dev/null -w '%{http_code}\n' -K "$d/old.curlrc" https://mail-staging.olajay.co.uk/   # expect 401
rm -rf "$d"
```

Then hand the new password to the testers out of band. Traefik re-reads the Secret when
it changes, so the old password stops working once secret-sync's new pod has synced it.

---

## 6.1a Staging Grafana edge gate (`grafana-edge-credentials`)

The operator gate in front of `https://grafana-staging.olajay.co.uk` (#968 item 3, approved
2026-10-20) has the same shape as section 6.1 and one shared login, user `staging-operator`.
Two Key Vault objects in `jtoye-stg-kv` hold it:

- `grafana-edge-credentials--password`, the plaintext an operator types at the browser prompt.
  It is mounted by no pod and granted to no workload identity.
- `grafana-edge-credentials--auth`, its apr1 htpasswd line. secret-sync syncs it into Secret
  `grafana-edge-credentials` (key `auth`), which Traefik's basic auth reads.

This is NOT Grafana's own admin login (`grafana-admin-credentials--admin-password`, class
"random, admin logins" in section 6.2): that one sits behind this gate and rotates separately.

Rotate when an operator leaves, or when the password may have been seen anywhere it should not
be. **Never use `scripts/staging-secrets.sh` for this** (write-once; it refuses an object that
exists). Rotation writes a NEW VERSION of both objects, the password first:

```bash
export JTOYE_AZURE_SUBSCRIPTION_ID=c483d353-5f61-4587-a790-addb9ab5fb94
. scripts/lib/azure-guard.sh && az_guard_init
d=$(umask 077 && mktemp -d)
# 0. keep the OLD password for the refusal check (mode 600, never printed)
az_read keyvault secret show --vault-name jtoye-stg-kv -n grafana-edge-credentials--password --query value -o tsv > "$d/old"
# 1. a new random value, then its apr1 line; the password reaches openssl on stdin only
openssl rand -base64 96 | LC_ALL=C tr -dc 'A-Za-z0-9' | head -c 32 > "$d/pw"
printf 'staging-operator:%s' "$(openssl passwd -apr1 -stdin < "$d/pw")" > "$d/auth"
az_mutate --no-echo keyvault secret set --vault-name jtoye-stg-kv -n grafana-edge-credentials--password --file "$d/pw" --encoding utf-8
az_mutate --no-echo keyvault secret set --vault-name jtoye-stg-kv -n grafana-edge-credentials--auth --file "$d/auth" --encoding utf-8
# 2. the CSI driver fetches only on mount (rotation is off), so restart the syncing pod
#    (maxUnavailable 0: the new pod mounts before the old one goes).
kubectl --context jtoye-stg-aks -n jtoye-staging rollout restart deploy/secret-sync
kubectl --context jtoye-stg-aks -n jtoye-staging rollout status deploy/secret-sync --timeout=10m
# 3. the new password works (4/4), and the OLD one is refused with 401
bash scripts/staging-grafana-edge.sh --probe --context jtoye-stg-aks
(umask 077; printf 'user = "staging-operator:%s"\n' "$(cat "$d/old")" > "$d/old.curlrc")
curl -sS -o /dev/null -w '%{http_code}\n' -K "$d/old.curlrc" https://grafana-staging.olajay.co.uk/login   # expect 401
rm -rf "$d"
```

Traefik re-reads the Secret when it changes, so the old password stops working once
secret-sync's new pod has synced it. The first-time creation of these two objects (before the
deploy that introduces the gate) is `scripts/staging-secrets.sh`, not this procedure.

---

## 6.2 Staging Key Vault objects, by class (plan 29-27, 2026-10-20)

Staging holds 43 objects in `jtoye-stg-kv` (`bash scripts/staging-secrets.sh --list` prints the
names and classes, offline). The list is derived from the render, never typed. **Key Vault is
write-once by construction:** `scripts/staging-secrets.sh` never writes an object that exists, in
any mode, so it is never the rotation tool. Rotation is a deliberate act by the operator that writes
a **new version** of an object, then restarts what reads it, as shown for the mail UI in section 6.1
(`az_mutate --no-echo keyvault secret set ... --file FILE --encoding utf-8`, a mode-700 temp
directory, no value on argv, nothing printed). The acceptance shape is unchanged (section 2): the
superseded value is refused and the current one accepted, read off the RUNNING service, in the same
run. Why a restart is needed (rotation is off, fetch happens at mount): `docs/runbooks/staging-operations.md`
section 3. Day-to-day access to the objects: the same page, section 2.

| Class | Objects | How to rotate |
|---|---|---|
| **estate** | `*-username`, `postgres-credentials--host/port/database`, `grafana-admin-credentials--admin-user` | Not secrets: facts about the estate. They are not rotated; a change is a code change to the class table in `scripts/lib/staging-secrets-common.sh`. |
| **random, database role passwords** | `postgres-credentials--password`, `--migration-password`, `--backup-password`, `postgres-exporter-credentials--password`, `keycloak-server-credentials--db-password` | The object and the role on the server must change together (`ALTER ROLE` on `jtoye-stg-pg`; `scripts/staging-db-bootstrap.sh` is the role writer). Write the new version, set the role password, then `rollout restart` every consumer; new connections from a consumer that has not yet restarted fail until it does, so do it in a quiet period. Prove it over the network: the superseded password is refused, the current one accepted (section 1: never over a loopback that is trusted). |
| **random, Keycloak realm secrets** | `keycloak-realm--*-secret`, `keycloak-credentials--frontend-client-secret` | The realm lives in Postgres and holds the live value, so writing a vault version alone changes nothing. `scripts/staging-realm-import.sh --apply` SKIPS a realm that already exists; `--recreate` deletes the realm **and every user in it**, so it is not a routine rotation. Rotate by regenerating the client secret in the running realm, writing that same value as the new vault version, and, for `keycloak-credentials--frontend-client-secret` (the only one of these a workload mounts: `frontend`), restarting `deploy/frontend`. The three `keycloak-realm--*` objects are read only by `staging-realm-import.sh`, so nothing restarts for them. Prove it with a token request, as in section 3. |
| **random, application keys** | `nextauth-secret--secret`, `notification-credentials--unsubscribe-signing-secret`, `client-ip-credentials--hmac-key` | New version, then restart the consumer (`frontend` for the NextAuth secret, `core-java` for the other two). A rotation invalidates what the old key produced: sessions signed with the NextAuth secret, unsubscribe links already emailed, and the client-address digests stored on existing orders (they stop matching new ones). Pick a quiet moment and accept those. |
| **random, DSAR key** | `dsar-credentials--encryption-key` | **Rotation requires re-encrypting open requests. Not a routine rotation.** core-java refuses to start without a valid 64-hex key, and every open data-subject request holds its subject address as ciphertext under the current key; a new key makes them unreadable. Do not rotate it without a re-encryption plan. |
| **random, broker and cache** | `rabbitmq-credentials--password`, `redis-credentials--password` | New version, then restart the broker or cache and every consumer. The RabbitMQ composites (`rabbitmq-credentials--stomp-passcode`, `rabbitmq-default-user--default-user-conf`) are derived from `rabbitmq-credentials--password` and must be rewritten in the same step, in that order; the broker reads `rabbitmq-default-user` through `secret-sync`. |
| **random, admin logins** | `grafana-admin-credentials--admin-password`, `keycloak-credentials--admin-password`, `staging-seed--test-vendor-password`, `smtp-credentials--password` | Grafana applies `GF_SECURITY_ADMIN_*` on every fresh start and keeps no state, so a new version and `rollout restart deploy/grafana` is enough (login: old refused, new accepted). Keycloak applies its admin password only when it first creates the admin, and the seed password is a Keycloak user's, so for those two a new vault version changes nothing until the value is also changed in the running Keycloak (the trap of section 2). Mailpit takes no SMTP auth in staging. |
| **composite** | `mailpit-ui-credentials--auth`, `grafana-edge-credentials--auth`, the RabbitMQ composites | Derived from other objects: rotate the input, then rewrite the composite. The mail UI is section 6.1, the Grafana edge gate section 6.1a. |
| **external** | `stripe-credentials--api-key`, `stripe-credentials--webhook-secret`, `alertmanager-smtp-credentials--from/username/password/to` | Supplied by the owner. The value is created at the provider (Stripe test mode, Zoho), then written as a new version by hand with the same prefix rule the writer enforces: `sk_test_` and `whsec_` only, because a live Stripe key never enters staging (D-28); no whitespace; an address object is one address. Restart `core-java` for Stripe and `deploy/alertmanager` for the mail objects, then re-run `scripts/check-alert-liveness.sh` (section 5 of the staging operations page). |

Never use `scripts/staging-secrets.sh --set-external` for a rotation: it refuses an existing object.

---

## 7. What did NOT go as written on 2026-08-10 (recorded, not omitted)

- **The pg_hba `127.0.0.1 trust` trap (§1) cost the first DB arm.** An in-container
  `psql -h 127.0.0.1` accepted BOTH the old and the (never-applied) new password — a vacuous pass.
  The arm only discriminates over the host→published-port `scram` path. Validated with a positive
  control (valid password → rc 0) and a negative control (random → rc 2) before it was trusted.
- **`ALTER ROLE … PASSWORD :'var'` did not interpolate under `psql -c`** and raised a syntax error,
  so the first ALTER silently did nothing while `.env` had already been rewritten — the two drifted.
  Embed the hex value directly (it is `[0-9a-f]`, SQL-safe) or use a heredoc with `\set`. The live
  superseded value was recovered from the running core-java's env (`printenv DB_PASSWORD`) to
  re-align and re-prove.
- **`check-infra-exposure.sh` exits rc=1 on this machine because assertion B flags a cohabiting
  foreign compose project** (`asao-*`, OlaJay's stack) publishing on `0.0.0.0`. All eight flagged
  bindings are `asao-*`; **zero jtoye services fail B** (they are loopback or app-tier exempt). The
  credential arm C1/C2/C3 passes; do not "fix" the foreign stack.
- **core-java showed a runtime-freshness DRIFT** because plan 28-09 committed a comment-only
  core-java change (`48969b0f`) and deliberately deferred the rebuild to phase close-out. The
  `up -d --build` in this rotation absorbed it; all four built services then read FRESH.
