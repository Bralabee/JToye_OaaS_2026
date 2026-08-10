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
> credential from the host over `127.0.0.1:5433` (or from a peer container by service name), never
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
`integration-orders-rw`→`INTEGRATION_ORDERS_RW_SECRET`. All four are confidential with
`serviceAccountsEnabled`, so `client_credentials` is the acceptance instrument.

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
tok <client> "$OLD_SECRET"   # SUPERSEDED — must be 401 (invalid_client)
tok <client> "$NEW_SECRET"   # CURRENT    — must be 200 (token issued)
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
