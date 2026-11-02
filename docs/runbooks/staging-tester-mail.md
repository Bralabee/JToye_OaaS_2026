# Runbook — staging mail for named outside testers

Owner: the platform owner (Sanmi). Written 2026-11-02 for the owner ruling of the same day, which
amends Phase 29 decision D-20 narrowly (recorded as D-35 in
`.planning/phases/29-deployable-staging-with-its-own-monitoring/29-CONTEXT.md` and in the header of
`k8s/components/mailpit/kustomization.yaml`).

> **No literal credential value and no tester's real address appears in this file.** It names
> Key Vault objects, Kubernetes Secrets and the commands that read them, and uses
> `tester1@example.com` as the placeholder. A value is read into a mode-600 file in your own
> terminal and is never pasted into a chat, a ticket, a commit or an evidence file.

## Why this exists

Staging captures every email in the in-cluster Mailpit and delivers none (D-13, as amended by
D-20). On 2026-10-30 a real outside tester registered on the storefront, Keycloak said "an email
has been sent to" their address, and nothing arrived: the verification link sat in Mailpit, which
only holders of the shared `staging-tester` UI credential can read. No outside tester could finish
sign-up.

**The ruling (2026-11-02):** Mailpit keeps trapping everything EXCEPT recipients on an
owner-maintained allow-list of named tester addresses, which it relays to the real SMTP provider.
Nothing else about staging mail changes.

**The standing rule:** every other recipient stays trapped. Personas keep invented addresses and
read their mail at `mail-staging.olajay.co.uk`. Only an address you have deliberately put on the
allow-list ever receives real mail, and only while the Secret below exists.

## How it works (what is in git, and what is not)

| Piece | Where | In git? |
|-------|-------|---------|
| Relay wiring: 9 `MP_SMTP_RELAY_*` env entries, a start-script gate, DNS + TCP 587 egress | `k8s/components/mailpit/mailpit.yaml` | yes |
| Relay host, port, Zoho login, Zoho password, optional sender override, **the allow-list** | Kubernetes Secret `mailpit-relay` in `jtoye-staging` | **no**: you apply it by hand |

Mailpit v1.31.6 does the relaying itself (source read at tag `v1.31.6`: `config/config.go`,
`config/validators.go`, `internal/smtpd/relay.go`, `internal/smtpd/main.go`; docs page
https://mailpit.axllent.org/docs/configuration/smtp-relay/):

- `MP_SMTP_RELAY_MATCHING` (key `allowlist`): after a message is captured, its envelope
  recipients that match this regular expression get a copy through the relay. A message with no
  matching recipient is never relayed and causes no outbound connection at all.
  `MP_SMTP_RELAY_ALL` (relay everything) is never set, and INV-18 refuses it.
- `MP_SMTP_RELAY_ALLOWED_RECIPIENTS` (the same key `allowlist`): once a relay is configured,
  Mailpit's UI and API also gain a "Release" button. This bounds a manual release to the same
  allow-list, so nobody holding the shared UI credential can send captured mail to an address you
  did not list.
- `MP_SMTP_RELAY_HOST` / `_PORT` / `_USERNAME` / `_PASSWORD` (keys `host`, `port`, `username`,
  `password`): the Zoho submission server. `MP_SMTP_RELAY_STARTTLS=true` and
  `MP_SMTP_RELAY_AUTH=plain` are literals in the manifest (Go's PLAIN auth refuses to send the
  password over a connection that is not encrypted).
- `MP_SMTP_RELAY_OVERRIDE_FROM` (key `override-from`, optional): Zoho accepts mail only from the
  account it authenticated or one of its aliases, and Keycloak sends as `no-reply@olajay.co.uk`.
  Set this to the Zoho login address unless `no-reply@` is an alias of that mailbox. Mailpit
  replaces the `From` address, keeps the display name, and adds `X-Original-From`.

**Absent or incomplete Secret means relay OFF.** Every `secretKeyRef` is `optional: true`, and
the container's start script turns the relay on only when `host`, `port`, `username`,
`password` and `allowlist` are all non-empty; otherwise it unsets every `MP_SMTP_RELAY_*` before
Mailpit starts. So a fresh environment traps everything, and a half-made Secret cannot crash-loop
the pod. A complete but MALFORMED Secret (an allow-list that does not compile, an `override-from`
that is not an address) also ends with the relay off and the trap running (section 1). The first
log line says which state it is in:

```
mailpit-relay: OFF (Secret mailpit-relay absent or incomplete); every message is captured and none is delivered
mailpit-relay: ON via smtp.zoho.eu:587 (STARTTLS), ONLY to recipients on the Secret mailpit-relay allow-list; every message is still captured
```

**Why a hand-applied Secret and not Key Vault (D-23).** A SecretProviderClass mount fails the
pod when a Key Vault object is missing, so a Key Vault-backed relay could not be optional, and a
per-tester allow-list would become a write-once deploy prerequisite. Losing this Secret with the
cluster fails SAFE (relay off). `k8s/scripts/keyvault-chain.py` names `mailpit-relay` as its one
out-of-band optional Secret, readable only by `Deployment/mailpit` and only through optional env
refs. The Zoho password itself can still live in Key Vault (step 1 reads it from there).

## Before you start

Reach the cluster as in [`staging-operations.md` section 1](./staging-operations.md#1-reach-the-cluster)
(`--context jtoye-stg-aks` on every `kubectl`, never `current-context`). The PR that added this
wiring must be merged and deployed: `kubectl --context jtoye-stg-aks -n jtoye-staging logs
deploy/mailpit -c mailpit | head -n 1` must print a `mailpit-relay:` line. If it prints anything
else, the running Mailpit predates the change.

## 1. Turn the relay on, or change the allow-list

**The allow-list regex shape.** Go RE2 syntax, case-insensitive, anchored at both ends, one full
address per alternative, every `.` escaped:

```
(?i)^(tester1@example\.com|tester2@example\.org)$
```

- `(?i)` makes it case-insensitive; `^(` ... `)$` anchors it, so `tester1@example.com.evil.net`
  and `xtester1@example.com` do not match.
- Never a domain-wide pattern (`@gmail\.com$`) and never `.*`: that would relay every persona.
- Mailpit refuses to start on a regex it cannot compile, and on an `override-from` that is not an
  address. The start script catches that: Mailpit is relaunched with the relay OFF, mail is still
  captured, and the log shows Mailpit's own `[relay] failed to compile ...` (or `override-from is
  not a valid email address`) line followed by
  `mailpit-relay: OFF, Mailpit exited rc=1 at start with the relay ON (Secret mailpit-relay is
  malformed ...)`. Fix the Secret and restart again. The rollout itself succeeds, so read the log:
  a green rollout is not proof the relay is on.

**Create or replace the Secret.** Every value goes into a mode-600 file; nothing is on a command
line. The Zoho login and app password below are read from the Key Vault objects Alertmanager
already uses (`alertmanager-smtp-credentials--username` / `--password`, plan 29-25). A dedicated
Zoho app-specific password for the relay is better (revoking it cannot break alert mail): if you
make one, type it at the hidden prompt instead.

```bash
source ~/miniconda3/etc/profile.d/conda.sh && conda activate jtoye-ops
umask 077; d=$(mktemp -d); chmod 700 "$d"
printf '%s' 'smtp.zoho.eu' > "$d/host"
printf '%s' '587'          > "$d/port"
az keyvault secret show --subscription c483d353-5f61-4587-a790-addb9ab5fb94 --vault-name jtoye-stg-kv \
  -n alertmanager-smtp-credentials--username --query value -o tsv | tr -d '\n' > "$d/username"
az keyvault secret show --subscription c483d353-5f61-4587-a790-addb9ab5fb94 --vault-name jtoye-stg-kv \
  -n alertmanager-smtp-credentials--password --query value -o tsv | tr -d '\n' > "$d/password"
# (dedicated app password instead:  read -rs -p 'Zoho app password: ' p; printf '%s' "$p" > "$d/password"; unset p; echo)
cp "$d/username" "$d/override-from"
read -r -p 'allow-list regex: ' a; printf '%s' "$a" > "$d/allowlist"; unset a
for k in host port username password allowlist override-from; do printf '%-14s %s bytes\n' "$k" "$(wc -c < "$d/$k")"; done
kubectl --context jtoye-stg-aks -n jtoye-staging create secret generic mailpit-relay \
  --from-file=host="$d/host" --from-file=port="$d/port" \
  --from-file=username="$d/username" --from-file=password="$d/password" \
  --from-file=allowlist="$d/allowlist" --from-file=override-from="$d/override-from" \
  --dry-run=client -o yaml | kubectl --context jtoye-stg-aks -n jtoye-staging apply -f -
rm -rf "$d"
```

Every byte count must be non-zero (the loop prints them, never the values). The `--dry-run |
apply` form creates the Secret or replaces it in place, so the same block also edits the list.

**Apply it.** Mailpit reads the Secret only when its container starts:

```bash
kubectl --context jtoye-stg-aks -n jtoye-staging rollout restart deploy/mailpit
kubectl --context jtoye-stg-aks -n jtoye-staging rollout status deploy/mailpit --timeout=180s
kubectl --context jtoye-stg-aks -n jtoye-staging logs deploy/mailpit -c mailpit \
  | grep -E '^mailpit-relay:|\[relay\] (enabling|error)'
```

Expect `mailpit-relay: ON via smtp.zoho.eu:587 ...` and `[relay] enabling message relaying via
smtp.zoho.eu:587`. A restart replaces the pod, so the captured mailbox starts empty (by design,
D-20 store).

**Change the Secret only together with a restart.** The env is re-read whenever the container
restarts, including the certificate-reload restart (issue #972), so a Secret edited and left
alone takes effect at an unplanned moment.

## 2. Verify a relayed message

There is no per-message "relayed" marker in Mailpit v1.31.6: a successful auto-relay is logged
only at debug level, and the captured copy looks the same either way. So verify at both ends:

1. **Trigger a real email to the tester.** For example, use "Forgot password" on the storefront
   sign-in for the tester's account, or let them register.
2. **Mailpit (the capture is still there):** the message appears at
   `https://mail-staging.olajay.co.uk` as before.
3. **Mailpit log (no relay error):**
   ```bash
   kubectl --context jtoye-stg-aks -n jtoye-staging logs deploy/mailpit -c mailpit --since=10m | grep '\[relay\]'
   ```
   A failure is one ERROR line, `[relay] error: ...` (for example `error response to AUTH
   command`, `error creating StartTLS config`, or a 5xx from Zoho such as a sender it will not
   relay for, which `override-from` fixes). The message stays captured, and core-java and
   Keycloak are not told: `MP_SMTP_RELAY_FWD_SMTP_ERRORS` is deliberately unset.
4. **Provider:** the tester confirms receipt (check spam). On the Zoho side, look in the relay
   mailbox's Sent folder or, as Zoho admin, the mail logs for the recipient. Which of the two
   shows SMTP-submitted mail for this account was **not verified** when this runbook was written;
   record what you find here.

The live gate `scripts/staging-mail-ui.sh --probe --context jtoye-stg-aks` (UI-7) accepts the
relay only when the settings API reports an anchored allow-list (`^...$`, optionally `(?i)`, with
no `.*` or `.+`) and a server on `:587`, and refuses any relay variable outside the nine named
ones.

## 3. Remove a tester, or turn the relay off

- **Remove one tester:** rerun step 1 with that alternative removed from the regex, then restart.
- **Turn the relay off entirely:**
  ```bash
  kubectl --context jtoye-stg-aks -n jtoye-staging delete secret mailpit-relay
  kubectl --context jtoye-stg-aks -n jtoye-staging rollout restart deploy/mailpit
  kubectl --context jtoye-stg-aks -n jtoye-staging logs deploy/mailpit -c mailpit | head -n 1   # mailpit-relay: OFF ...
  ```
- **If a dedicated Zoho app password was used,** revoke it in Zoho when the last tester is gone.

## What to know before adding someone

- **The tester's address is real personal data on staging**, the one exception to D-33's "no real
  customer data" rule. It is held in the Secret and in the captured mail.
- **Every holder of the shared `staging-tester` UI credential** can read an allow-listed
  tester's captured mail (it is still captured), and Mailpit's settings view
  (`GET /api/v1/webui`, `MessageRelay.AllowedRecipients`) shows the allow-list regex itself. The
  pod log also prints it once at start (`auto-relaying new messages to recipients matching ...`).
  Tell the tester; keep the UI credential to people you trust with that.
- **A relay hop adds latency** to the SMTP transaction for an allow-listed recipient only (Mailpit
  relays inside the SMTP DATA exchange). Mail to anyone else never opens an outbound connection.
- **The tester must use a fresh address for each new account.** `jtoye-customers` refuses a
  duplicate email.

## 4. Zero-code path for a tester who is stuck today: a pre-verified Keycloak account

This needs no Mailpit change and no Secret. It works now, before the PR above is deployed.

The customer realm is `jtoye-customers` (`registrationEmailAsUsername` true, `verifyEmail`
true). The Keycloak pod already holds the master-realm bootstrap admin in its environment
(`KC_BOOTSTRAP_ADMIN_USERNAME` / `KC_BOOTSTRAP_ADMIN_PASSWORD`, from `keycloak-credentials`), so
`kcadm.sh` runs inside the pod and the admin password never reaches your shell. The admin
login goes over the pod's own loopback on 8080.

**Case A: the tester already registered** (Keycloak said "an email has been sent", and they are
stuck at "verify your email"). Mark the existing account verified; they then sign in with the
password they chose:

```bash
read -r -p 'tester email: ' TESTER_EMAIL
kubectl --context jtoye-stg-aks -n jtoye-staging exec -i deploy/keycloak -- bash -c '
set -eu
K=/opt/keycloak/bin/kcadm.sh; C=/tmp/kcadm-tester.$$.config
trap "rm -f $C" EXIT
$K config credentials --config "$C" --server http://localhost:8080 --realm master \
  --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD"
id=$($K get users --config "$C" -r jtoye-customers -q email="$1" -q exact=true --fields id --format csv --noquotes)
[ -n "$id" ] || { echo "no jtoye-customers user with that email" >&2; exit 1; }
$K update "users/$id" --config "$C" -r jtoye-customers -s emailVerified=true -s "requiredActions=[]"
$K get "users/$id" --config "$C" -r jtoye-customers --fields username,emailVerified,requiredActions
' _ "$TESTER_EMAIL"
```

Expect `"emailVerified" : true` and `"requiredActions" : [ ]`.

**Case B: no account yet.** Create one already verified, with a one-time password the tester
must change at first sign-in (required action `UPDATE_PASSWORD`). The temporary password is
generated on your side and sent over stdin, never on a command line:

```bash
read -r -p 'tester email: ' TESTER_EMAIL
TMP_PW=$(openssl rand -base64 18)
printf '%s\n' "$TMP_PW" | kubectl --context jtoye-stg-aks -n jtoye-staging exec -i deploy/keycloak -- bash -c '
set -eu
IFS= read -r PW
K=/opt/keycloak/bin/kcadm.sh; C=/tmp/kcadm-tester.$$.config
trap "rm -f $C" EXIT
$K config credentials --config "$C" --server http://localhost:8080 --realm master \
  --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD"
$K create users --config "$C" -r jtoye-customers -s username="$1" -s email="$1" \
  -s enabled=true -s emailVerified=true -s "requiredActions=[\"UPDATE_PASSWORD\"]"
$K set-password --config "$C" -r jtoye-customers --username "$1" --new-password "$PW" --temporary
$K get users --config "$C" -r jtoye-customers -q email="$1" -q exact=true --fields username,emailVerified,requiredActions
' _ "$TESTER_EMAIL"
echo "temporary password (hand it to the tester out of band, then forget it): $TMP_PW"; unset TMP_PW
```

`create users` answers `409 User exists with same username` if they already registered: use
Case A. The realm's user profile may also ask the tester for first and last name at first sign-in;
that is expected.

**What this path does not give the tester:** any later email (password reset, order mail) is
still captured, not delivered. For that, add them to the allow-list (section 1).

**Not verified live when written:** the exact `kcadm.sh` output shapes above and the bootstrap
admin still being valid on the running realm (the realm import of plans 29-04/29-14 authenticates
with the same Key Vault pair, `keycloak-credentials--bootstrap-admin-*`). If `config credentials`
answers 401, the bootstrap admin has been removed: use the realm-import script's port-forward
admin path (`scripts/staging-realm-import.sh`) instead.
