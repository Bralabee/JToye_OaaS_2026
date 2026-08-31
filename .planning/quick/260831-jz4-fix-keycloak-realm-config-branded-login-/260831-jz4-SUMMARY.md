---
phase: quick-260831-jz4
plan: 01
subsystem: identity / keycloak-realm-config
status: awaiting-human-verify
tags: [keycloak, smtp, mailhog, branding, account-enumeration, R-05, R-06, R-11]
requires: [running compose stack (jtoye-keycloak + jtoye-mailhog healthy)]
provides:
  - "smtpServer wired to Mailhog on both realm templates and both running realms"
  - "displayName / displayNameHtml on both realms"
  - "a custom `jtoye` Keycloak login theme (CSS-only) applied to both realms"
  - "working customer password-reset delivery"
  - "closed account-enumeration oracle on the reset-credentials form"
affects:
  - infra/keycloak/realm-export.template.json
  - infra/keycloak/realm-export-customers.template.json
  - infra/keycloak/README.md
  - infra/keycloak/themes/jtoye/**
  - docker-compose.full-stack.yml
  - infra/docker-compose.yml
  - infra/docker-compose.hostnet.yml
tech-stack:
  added: ["custom Keycloak login theme `jtoye` (CSS-only overlay on the built-in `keycloak` theme; no FTL overrides, no theme jar)"]
  patterns: ["brand tokens copied from frontend/tailwind.config.ts + frontend/app/globals.css so login pages track the app's palette", "self-hosted Work Sans woff2 — no font-CDN request from a login page"]
key-files:
  created:
    - infra/keycloak/themes/jtoye/login/theme.properties
    - infra/keycloak/themes/jtoye/login/resources/css/jtoye.css
    - infra/keycloak/themes/jtoye/login/resources/fonts/work-sans-latin.woff2
  modified:
    - infra/keycloak/realm-export.template.json
    - infra/keycloak/realm-export-customers.template.json
    - infra/keycloak/README.md
    - docker-compose.full-stack.yml
    - infra/docker-compose.yml
    - infra/docker-compose.hostnet.yml
    - .planning/codebase/STACK.md
    - .planning/codebase/INTEGRATIONS.md
decisions: [D-1, D-2, D-3, D-4, D-5 (superseded in review), D-6]
requirements: [R-05, R-06, R-11]
metrics:
  tasks_completed: "2 of 3 + rework round 1 (Task 3 automated halves done; human-verify gate PENDING, round 2)"
  commits: 4
  completed: 2026-08-31
---

# Quick Task 260831-jz4: Keycloak Realm SMTP + Branded Login Summary

Wired `smtpServer` to the dev stack's Mailhog and set `displayName` / `displayNameHtml` /
`loginTheme` on both realm templates **and** both running realms, turning the customer
"Forgot password" flow from a dead end that leaked account existence into a working reset
whose responses are byte-identical for known and unknown addresses.

**Status: the human-verify checkpoint is open for a SECOND round.** Round 1 was **rejected**;
the rework is described below and is committed.

---

## Review round 1 — REJECTED, and the rework

**Verbatim feedback:** *"i've done a hard refresh and the looks is still default not anything
like jtoye branding, yes there's a jtoye in the background bu that page is not jtoye'ish in any
way."*

The reviewer was right, and the plan's own scope boundary was the thing that was wrong. D-5
explicitly ruled out a custom theme and bet that three realm-level keys would be enough. They
were not: `displayNameHtml` colours **one string**, while the stock theme keeps a dark low-poly
background, blue PatternFly buttons, blue links and an uppercased header. A brand-coloured
wordmark on an otherwise stock page is still a stock page. Round 1 passed every automated
assertion it defined — the title changed, the span rendered as markup, the computed colour was
`rgb(58, 11, 13)` — which is a clean example of assertions that are all true while the thing
they exist to protect is not achieved. No automated check in the plan could have caught this;
the human gate did.

**Scope extended: a real custom theme.** `infra/keycloak/themes/jtoye/login/` — CSS-only, no FTL
template overrides (a forked template pins us to one Keycloak version's markup and rots at the
next upgrade). `loginTheme` moved `keycloak` → `jtoye` on both templates and both running realms.

| | Round 1 (rejected) | Round 2 |
|---|---|---|
| Page background | stock dark low-poly PNG | cream `rgb(251, 246, 240)` + brand-tinted washes, no image request |
| Typeface | stock (Overpass/Red Hat) | Work Sans, self-hosted, `document.fonts.check` → `true` |
| Header | oxblood but UPPERCASED by the stock rule | oxblood, `text-transform: none` — the wordmark is cased |
| Primary button | PatternFly blue | orange-700 `rgb(194, 65, 12)`, pill radius |
| Links | PatternFly blue | oxblood `rgb(58, 11, 13)` |
| Card | stock square, blue top rule | white, 16px radius, oxblood top rule, soft shadow |
| Checkbox | browser-default blue | `accent-color` orange-700 |
| Pages covered | n/a | sign-in, reset-credentials, update-password, error — all measured |

## Headline result

| Claim | Before (Task 1, measured) | After (measured) |
|---|---|---|
| Customer login page title | `Sign in to jtoye-customers` | `Sign in to J&#39;Toye` |
| Vendor login page title | `Sign in to jtoye-dev` | `Sign in to J&#39;Toye` |
| Reset for an EXISTING address | **HTTP 500** "Failed to send email, please try again later." | **HTTP 200** "You should receive an email shortly with further instructions." |
| Reset for an UNKNOWN address | HTTP 200 "You should receive an email shortly…" | HTTP 200 "You should receive an email shortly…" |
| Token-stripped body diff between the two | **70 lines (oracle present)** | **rc=0, empty (oracle closed)** |
| Mailhog delta on a reset | **0 (nothing sent)** | **+1, addressed to the requesting account** |

---

## Task 1 — fail direction, recorded before any edit

Preconditions asserted, not assumed:

```
rc=0
jtoye-keycloak	Up 23 hours (healthy)
jtoye-mailhog	Up 23 hours (healthy)
```

### (a) BASELINE-BRANDING — literal output

```
customers_login http=200 rc=0 bytes=5680
dev_login http=200 rc=0 bytes=4618

before-customers-login TITLE rc=0: <title>Sign in to jtoye-customers</title>
before-customers-login HEADER rc=0: id="kc-header-wrapper" class="">jtoye-customers
before-customers-login POSCTRL 'kc-form-login' count=1 rc=0
before-customers-login NEGCTRL count=0 rc=1

before-dev-login TITLE rc=0: <title>Sign in to jtoye-dev</title>
before-dev-login HEADER rc=0: id="kc-header-wrapper" class="">jtoye-dev
before-dev-login POSCTRL 'kc-form-login' count=1 rc=0
before-dev-login NEGCTRL count=0 rc=1
```

The header text sits on a different line from `id="kc-header-wrapper"`, so the HTML was
flattened with `tr -d '\n'` first. A positive control (`kc-form-login`, must match) and a
negative control (a string that cannot exist, must not match) were run on the same flattened
text, so the zero from the negative direction is trustworthy rather than an artefact of the
pattern.

Running realms confirmed the same defect at source — not just on the wire:

```
{"realm":"jtoye-customers","displayName":null,"displayNameHtml":null,"loginTheme":null,
 "resetPasswordAllowed":true,"registrationAllowed":true,"verifyEmail":false,
 "loginWithEmailAllowed":true,"duplicateEmailsAllowed":false,"bruteForceProtected":true,
 "smtpServer":{},"browserSecurityHeaders":"frame-src 'self'; frame-ancestors 'self'; object-src 'none';"}

{"realm":"jtoye-dev","displayName":null,"displayNameHtml":null,"loginTheme":null,
 "resetPasswordAllowed":false,"registrationAllowed":false,"verifyEmail":false,
 "bruteForceProtected":true,"smtpServer":{},
 "browserSecurityHeaders":"frame-src 'self'; frame-ancestors 'self'; object-src 'none';"}
```

The customer realm's CSP was checked rather than assumed (the plan only had it measured on the
vendor realm): it is identical and does **not** restrict `style-src`, so the inline `style=`
attribute in `displayNameHtml` renders there too.

Templates before the edit:

```
vendor_template_smtpServer rc=0: {}
customer_template_smtpServer_lines rc=1: 0        <- no smtpServer key at all
key=displayName      vendor_count=0(rc=1) customer_count=1(rc=0)
key=displayNameHtml  vendor_count=0(rc=1) customer_count=0(rc=1)
key=loginTheme       vendor_count=0(rc=1) customer_count=0(rc=1)
```

The single `displayName` hit on the customer template was checked rather than assumed — it is
the Google identity provider's, not a realm top-level key:

```
69:    "displayName" : "Google",
```

### (b) BASELINE-MAILHOG

```
N0=8 rc=0
```

### (c) RESET DEAD-END + ENUMERATION ORACLE

Test account: `lane3-reverify-1788177824@example.com`. **No account was created** — the plan
allowed creating one only if no user had an email, and the realm holds 21 users all with
emails.

```
=== EXISTING ===
input=lane3-reverify-1788177824@example.com
http_code=500
bytes=2423
message_text=... <div id="kc-error-message"> <p class="instruction">Failed to send email, please try again later.</p> ...

=== NONEXISTENT ===
input=definitely-not-a-user-260831@example.invalid
http_code=200
bytes=6455
message_text=You should receive an email shortly with further instructions.

BEFORE_ORACLE_DIFF rc=1 lines=70
```

**The status code alone is the oracle**: 500 for a real account, 200 for an unknown one. An
attacker needs only to read the HTTP status. The body diff (70 lines) shows the same split —
`We are sorry...` versus `Sign in to your account` with a success alert.

Mailhog after both attempts:

```
N0=8  after_reset_attempts=8  delta=0  rc=0
```

That zero delta is finding R-06.

#### The strip procedure was calibrated, and this mattered

A first pass left two per-session values unstripped (the `authSessionId` UUID and the bare
`tabId`, both naked quoted arguments to `checkCookiesAndSetTimer` where a `tab_id=` pattern
cannot see them). Left in place, the "after" diff could never have been empty and the fix
would have looked like it failed.

The procedure lives in **one script used by both runs**, so the before/after comparison is
valid. It was calibrated by running the **same input twice in two independent sessions**:

```
CALIBRATION same-input-twice: walk_rcs=0/0 diff_rc=0 lines=0
```

Without this control, an empty diff later would have been equally consistent with
over-stripping. Both Task 1 arms were re-run with the hardened procedure before any edit was
made, so Task 1 and Task 3 use provably identical stripping.

Diff-instrument control (a file against itself): `self_diff rc=0 lines=0`.

### (d) MAILHOG POSITIVE CONTROL — the instrument can go up

An SMTP dialogue from **inside the `jtoye-keycloak` container**, which simultaneously proves
the exact reachability the fix depends on:

```
S: 220 mailhog.example ESMTP MailHog
C: HELO keycloak-probe -> S: 250 Hello keycloak-probe
C: MAIL FROM:<probe@jtoye.local> -> S: 250 Sender probe@jtoye.local ok
C: RCPT TO:<positive-control-260831@jtoye.local> -> S: 250 Recipient ... ok
C: DATA -> S: 354 End data with <CR><LF>.<CR><LF>
C: <body>. -> S: 250 Ok: queued as ZLh66gJfQkzN-...@mailhog.example
C: QUIT -> S: 221 Bye
after_control=9 delta=1 rc=0
```

`mailhog:1025` resolves and accepts from the Keycloak container, and the counter can rise.
**N1 = 9.**

### (e) GATE BASELINE

```
verify-env.sh rc=0
PASS: All 20 required credential variables are set, non-weak and long enough
PASS: All 4 same-role credential pair(s) agree
```

No tracked file was modified during Task 1 (`git status --porcelain` → untracked evidence dir
only).

---

## Task 2 — templates + running realms

### Half A/B — templates and render

Added to both templates (identical block), with the vendor realm's `"smtpServer" : { }`
replaced. All values strings, per D-6; `displayNameHtml` per D-5; `loginTheme` pinned to the
built-in theme.

Diff is exactly the intended change — one line removed, nothing else touched:

```
 infra/keycloak/realm-export-customers.template.json | 12 ++++++++++++
 infra/keycloak/realm-export.template.json           | 13 ++++++++++++-
 2 files changed, 24 insertions(+), 1 deletion(-)
--- removed lines:
-  "smtpServer" : { },
```

D-4 keys confirmed untouched (each `in_diff=0`): `resetPasswordAllowed`,
`registrationAllowed`, `verifyEmail`, `bruteForceProtected`, `passwordPolicy`.

Template validation: the vendor template parses with raw `jq` (rc=0). The customer template
does **not** — `parse error: Invalid numeric literal at line 27` — which is the pre-existing
unquoted `${CUSTOMER_VERIFY_EMAIL}` placeholder, not damage introduced here. It was validated
by parsing a **dummy-substituted temp copy** (rc=0), confirming the new keys:

```
{"displayName":"J'Toye","displayNameHtml":"<span style=\"color:#3A0B0D;font-weight:700;letter-spacing:0.01em\">J'Toye</span>","loginTheme":"keycloak","smtpServer":{"host":"mailhog","port":"1025","from":"no-reply@jtoye.local","fromDisplayName":"J'Toye","auth":"false","ssl":"false","starttls":"false"}}
```

Render (`docker compose up keycloak-realm-render --no-deps`) exited 0 and rewrote both files.
Assertions on the **rendered** products, both files:

```
smtp_assert     rc=0 out=true      (.smtpServer.host=="mailhog" and .smtpServer.port=="1025")
branding_assert rc=0 out=true      (displayName + displayNameHtml non-null, loginTheme=="keycloak")
dollar_brace_in_new_fields rc=1 (1=none=correct) match=''
```

**The `grep -F '${'` check was proven capable of matching** before its zero was trusted — the
same pattern over the whole templates returns `17` and `57` lines. `grep -F` is required
because this machine's `grep` is ugrep, where braces are metacharacters.

Rendered products confirmed gitignored (`.gitignore:167` and `:171`) and absent from
`git status`.

### Half C/D — running realms, and an instrument that lied

The branding keys applied, but the first read-back showed `smtpServer` still `{ }` on both
realms — with **rc=0 and empty output from every write**. Three successive writes appeared to
fail. The cause was the *read*, not the write:

```
=== CONTROL: same client, same realm, same moment — two projections ===
  WITH --fields   rc=0 : {"smtpServer":{}}
  WITHOUT --fields rc=0 : {"starttls":"false","port":"1025","auth":"false","host":"mailhog",
                           "from":"no-reply@jtoye.local","fromDisplayName":"J'Toye","ssl":"false"}
```

`kcadm.sh get --fields` renders **any nested map** as `{ }`. Generalised with a second
known-populated map: `--fields browserSecurityHeaders` → `{"browserSecurityHeaders":{}}`,
while the CSP string is demonstrably present. **The plan prescribed `--fields` as the
running-artifact read; it is incapable of verifying the `smtpServer` half of this very
change.** A stronger instrument (full representation + raw Admin REST GET) was substituted and
is recorded here rather than swapped in silently. The README now warns against it.

**Which write actually landed it — determined, not assumed.** Bracketed on the *vendor* realm
only, so the delivered customer fix was never at risk:

```
CLEAN (populated): {"starttls":"false","port":"1025",...,"host":"mailhog",...}
blank PUT http=204
ARMED (expected empty): {}
kcadm update -f rc=0 out=''
AFTER kcadm -f: {"starttls":"false","port":"1025","auth":"false","host":"mailhog",
                 "from":"no-reply@jtoye.local","fromDisplayName":"J'Toye","ssl":"false"}
```

**`kcadm update -f` writes `smtpServer` correctly.** The original Half C write worked all
along. Had this not been run, the SUMMARY and the README would have recorded a false claim
that kcadm silently drops the field.

Final server state, read from the running server (full representation):

```
{"realm":"jtoye-customers","displayName":"J'Toye","displayNameHtml":"<span style=\"color:#3A0B0D;font-weight:700;letter-spacing:0.01em\">J'Toye</span>","loginTheme":"keycloak","smtpServer":{"starttls":"false","port":"1025","auth":"false","host":"mailhog","from":"no-reply@jtoye.local","fromDisplayName":"J'Toye","ssl":"false"},"resetPasswordAllowed":true,"registrationAllowed":true,"verifyEmail":false,"bruteForceProtected":true}

{"realm":"jtoye-dev","displayName":"J'Toye","displayNameHtml":"<span style=\"color:#3A0B0D;font-weight:700;letter-spacing:0.01em\">J'Toye</span>","loginTheme":"keycloak","smtpServer":{"starttls":"false","port":"1025","auth":"false","host":"mailhog","from":"no-reply@jtoye.local","fromDisplayName":"J'Toye","ssl":"false"},"resetPasswordAllowed":false,"registrationAllowed":false,"verifyEmail":false,"bruteForceProtected":true}
```

Every D-4 flag matches the Task 1 baseline exactly.

On the wire:

```
after-customers-login TITLE: <title>Sign in to J&#39;Toye</title>
after-customers-login HEADER: id="kc-header-wrapper" class=""><span style="color:#3a0b0d;font-weight:700;letter-spacing:0.01em">J&#39;Toye</span>
after-customers-login RAW_REALM_ID_IN_TITLE count=0 rc=1
after-dev-login TITLE: <title>Sign in to J&#39;Toye</title>
after-dev-login HEADER: id="kc-header-wrapper" class=""><span style="color:#3a0b0d;font-weight:700;letter-spacing:0.01em">J&#39;Toye</span>
after-dev-login RAW_REALM_ID_IN_TITLE count=0 rc=1
```

The `<span>` is present as **markup**, not as escaped text — D-5 holds. Confirmed in a real
browser: 1 `<span>` element inside `#kc-header-wrapper`, computed colour **`rgb(58, 11, 13)`**
= `#3A0B0D`, read off the running page rather than off the config.

### Break arm — clean → arm → clean again

```
### CLEAN (before arm)
  wire   : <title>Sign in to J&#39;Toye</title>
  server : J'Toye
### ARM: displayName -> sentinel on jtoye-dev
  arm_write rc=0
  wire   : <title>Sign in to SENTINEL-jz4-260831</title>
  server : SENTINEL-jz4-260831
  assertion reports SENTINEL? count=1
  raw-realm-id check under the arm: count=0
### RESTORE
  restore_write rc=0
### CLEAN AGAIN — verified BY CONTENT
  wire   : <title>Sign in to J&#39;Toye</title>
  server : {"displayName":"J'Toye","displayNameHtml":"<span style=\"color:#3A0B0D;...\">J'Toye</span>","loginTheme":"keycloak","smtpHost":"mailhog"}
  sentinel_residue=0
  branded_title_restored=1
```

**The arm found a real weakness in the plan's own check.** Under the sentinel, the
"raw realm id absent from the title" assertion **still passed** (`count=0`) — it cannot
distinguish correct branding from garbage branding. The load-bearing assertion is the
title *content* check, and that is what is reported above. Restore verified by content on both
the wire and the server, never by `git diff --stat`.

### Half E — no regression

Vendor token mint, decoded (a 200 alone would not prove the claim survived):

```
token_mint rc=0 access_token_present=yes err=none
decoded_claims rc=0: {"iss":"http://localhost:8085/realms/jtoye-dev","azp":"core-api",
                      "preferred_username":"tenant-a-user",
                      "tenant_id":"00000000-0000-0000-0000-000000000001"}
```

Customer user inventory — the check a full import would have failed:

```
before_count=21  after_count=21
INVENTORY_DIFF rc=0 (0 = identical)
--- CONTROL: prove the diff can see a missing user
control_diff rc=1
20a21
> lane3-reverify-1788177824@example.com	email=...	enabled=true	emailVerified=false
```

`verify-env.sh rc=0` — identical to the Task 1(e) baseline.

---

## Task 3 — automated halves (human gate still open)

### Half A — the real browser journey

Driven through Playwright against the storefront, not a synthetic POST:

```
customer signin page title: "Sign in to order — J'Toye"
card sign-in BUTTON count: 1
reached keycloak URL: .../realms/jtoye-customers/protocol/openid-connect/auth?client_id=storefront-client...
keycloak login page title: "Sign in to J'Toye"
kc-header-wrapper innerHTML: <span style="color:#3a0b0d;font-weight:700;letter-spacing:0.01em">J'Toye</span>
spans inside header: 1
computed header colour: rgb(58, 11, 13)
forgot-password link count: 1
reset page url: .../login-actions/reset-credentials?client_id=storefront-client&tab_id=wfmWwROAAg4
post-submit visible message: "J'TOYE Sign in to your account You should receive an email shortly with further instructions. ..."
```

Mailhog delivery — asserted on the **addressee**, not just the count:

```
N2=9  N3=10  delta=1   (Task 1 recorded delta=0 on the broken tree)
{
  "to": "lane3-reverify-1788177824@example.com",
  "from": "no-reply@jtoye.local",
  "subject": "Reset password",
  "fromHeader": "J'Toye <no-reply@jtoye.local>"
}
MATCH=yes
```

The `fromDisplayName` is live in the `From` header.

Reset link followed. The **text/plain** MIME part carries the URL on one line (7bit), so the
quoted-printable soft-break trap in the HTML part was sidestepped rather than fought:

```
landed url: .../login-actions/required-action?execution=UPDATE_PASSWORD&client_id=storefront-client&tab_id=yiTIppzZQLk
title: "Sign in to J'Toye"
password-new field count   : 1
password-confirm field count: 1
page heading: "Update password"
UPDATE-PASSWORD PAGE REACHED: true
```

**Fail direction for that assertion, executed.** A deliberately corrupted action token:

```
http status (NOT the assertion): 400
password-new field count   : 0
password-confirm field count: 0
page heading: "We are sorry..."
UPDATE-PASSWORD PAGE REACHED: false      (rc=1)
```

A first attempt at this control used the *earlier* email's link on the assumption it had
expired; it had not, and it passed — so it was discarded and replaced with the corrupted-token
arm above. Asserting on the fields rather than on HTTP 200 is load-bearing: the error page in
the browser flow returned 200 in other runs.

### Half B — the enumeration oracle, closed

Same script, same strip procedure, same arms as Task 1:

```
=== BEFORE (Task 1, broken tree) ===
  existing http=500   nonexistent http=200   status_oracle=YES
  body diff rc=1 lines=70    (NON-EMPTY = oracle present)

=== AFTER (fixed stack, IDENTICAL strip procedure) ===
  existing http=200   nonexistent http=200   status_oracle=no
  body diff rc=0             (EMPTY = oracle closed)
```

The empty diff is meaningful because the same-input-twice calibration proved the procedure
does not over-strip.

### Half C — README

- New **"Email (SMTP) and branding"** section: the `smtpServer` block with all values as
  strings; why the host is a literal rather than an envsubst placeholder (three render sites,
  each with its own allow-list; unlisted names survive as literal tokens and become the SMTP
  host; neither `infra/` compose has a Mailhog service and one uses host networking); and that
  staging/production do not consume these templates, so overrides are k8s-side.
- Branding keys documented, including why `loginTheme` is pinned to the built-in theme, why
  `displayNameHtml` must stay an operator-controlled literal, and why no annotation key is used
  at realm top level.
- **Route 1's wrong `--file` path fixed**, verified against the container before writing:

```
/home/.../infra/keycloak/realm-export.json -> /opt/keycloak/data/import/realm-export.json
/home/.../infra/keycloak/realm-export-customers.json -> /opt/keycloak/data/import/realm-export-customers.json

$ docker exec jtoye-keycloak ls -1 /keycloak
ls: cannot access '/keycloak': No such file or directory
rc=2
```

- D-2 recorded: prefer the Admin API when a realm has live users; the customer template ships
  `users: []` (confirmed: `customer_template_users=[]`), so a full override import deletes every
  storefront self-registration.
- Doc gates: `check-doc-metrics.sh` rc=0, `check-doc-citations.sh` rc=0,
  `docs-freshness.sh` rc=0. No metrics figures quoted, no `file:line` citations added.

### Half D — commits

```
c869bc8f docs(keycloak): document SMTP + branding config and fix a broken import path
9fc63383 fix(keycloak): wire smtpServer to Mailhog and brand both realm login pages
```

Both written via `-F <file>` and read back with `git log -1 --format=%B` — messages stored
intact, no trailer of any kind (checked with a split pattern so the check itself does not
contain the forbidden literal). Branch `feature/keycloak-realm-branding-smtp`;
`git log HEAD..origin/main` empty (not behind base).

### Half E — screenshots

| Realm | 1440 desktop | Throttled mobile (Moto G4, 4× CPU, ~1.6 Mbps) |
|---|---|---|
| jtoye-customers | `.planning/quick/260831-jz4-fix-keycloak-realm-config-branded-login-/evidence/shots/10-customers-1440.png` | `.../shots/11-customers-mobile-throttled.png` |
| jtoye-dev | `.../shots/10-dev-1440.png` | `.../shots/11-dev-mobile-throttled.png` |

Journey captures: `00-shop-signin.png`, `01-customers-login-1440.png`, `02-reset-form.png`,
`03-reset-submitted.png`, `04-update-password.png` in the same directory.

---

## Final live state

```
FINAL customers title: <title>Sign in to J&#39;Toye</title>
FINAL dev title: <title>Sign in to J&#39;Toye</title>
FINAL functional check: mailhog 11 -> 12 (delta=1), newest To=lane3-reverify-1788177824@example.com
FINAL http code: http_code=200
verify-env.sh rc=0
```

## Whole-plan verification gate

1. **Both halves of D-3 true** — templates committed (diff above) AND running realms read back
   from the server (full representation above). Neither substituted for the other.
2. **Fail direction recorded for every headline claim** — branding: sentinel break arm;
   mail delivery: Task 1's zero delta plus the in-container SMTP positive control; oracle:
   Task 1's 70-line diff from the identical procedure; update-password page: corrupted-token
   arm; inventory: doctored-copy control; `grep -F '${'`: whole-file positive control.
3. **No regression** — vendor token mints with `tenant_id`; customer inventory 21 → 21,
   diff rc=0; `verify-env.sh` rc=0 before and after.
4. **No unintended edits** — only the two templates and the README are tracked changes; the
   gitignored rendered JSONs were regenerated and do not appear.
5. **TDD exemption** — no production code, no test file added, `docs/metrics.json` untouched
   (`git status --porcelain docs/metrics.json` empty; `docs-freshness.sh` rc=0 at 3555).

## Deviations from Plan

**1. [Rule 3 — blocking] The plan's prescribed verification instrument cannot verify half the change**

- **Found during:** Task 2 Half D
- **Issue:** `kcadm.sh get realms/<realm> --fields …,smtpServer` renders every nested map as
  `{ }`. Following the plan literally would have reported the SMTP half as unapplied when it
  was applied.
- **Fix:** substituted the full representation plus a raw Admin REST GET, with a same-moment
  two-projection control proving the disagreement, and generalised it to
  `browserSecurityHeaders`. Recorded rather than silently swapped, and documented in the README
  so the next person does not repeat it.

**2. [Rule 1 — bug in my own instrument] Incomplete strip procedure**

- **Found during:** Task 1(c)
- **Issue:** the per-session `authSessionId` and bare `tabId` survived stripping, which would
  have made the Task 3 "empty diff" unreachable.
- **Fix:** hardened the procedure, added a same-input-twice calibration control, and re-ran
  both Task 1 arms before any edit so both runs use identical stripping.

**3. [Plan assumption corrected] `frontend` client does not exist on `jtoye-dev`**

- The plan suggested fetching the vendor login page with `client_id=frontend`. The running
  realm has no such client (it holds `core-api`, `edge-api`, `integration-*` and the built-ins);
  `core-api` with a registered redirect URI was read out of the realm and used instead.

**4. [Scope, deliberate] Password reset not completed**

- The plan made completing the reset optional ("if feasible"). It was **not** completed, so the
  test account's credential state is unchanged and no E2E fixture was disturbed. The
  update-password page was proven reached by asserting on its fields.

## UNVERIFIED

- **A full interactive customer credential login** (typing a password into the customer realm)
  was not performed — the 21 accounts are E2E self-registrations whose passwords are not
  recoverable, and no account was created. What *is* verified: the customer login page serves
  200 and renders its form, the reset-credentials authenticator chain executes end to end, and
  a delivered reset link reaches the update-password form. **Vendor and customer sign-in are
  step 4 of the human checkpoint below.**
- **Whether kcadm's dotted `-s 'smtpServer.host=…'` form writes correctly** — it was issued and
  read back through the defective `--fields` projection, then superseded. Only `update -f` was
  bracketed and proven. Not load-bearing for this change.

## Known Stubs

None.

## Threat Flags

None. No new network endpoint, auth path, file access pattern or schema change was introduced;
the change is realm configuration. T-jz4-01 (the enumeration oracle) is the finding closed, and
T-jz4-06 holds: `displayNameHtml` is a fixed operator-controlled literal containing only a
`<span>` and a style attribute, with realm CSP unchanged.

---

## Rework evidence (round 2)

### The parent's styles list was read, not guessed

`styles` in `theme.properties` **replaces** the inherited value rather than appending, so the
parent's own stylesheet has to be repeated or the page renders unstyled — silently. The parent's
value was extracted from the shipped `org.keycloak.keycloak-themes-24.0.5.jar`:

```
parent=base
import=common/keycloak

styles=css/login.css
stylesCommon=node_modules/@patternfly/patternfly/patternfly.min.css node_modules/patternfly/dist/css/patternfly.min.css node_modules/patternfly/dist/css/patternfly-additions.min.css lib/pficon/pficon.css
```

This theme therefore sets `styles=css/login.css css/jtoye.css` and leaves `stylesCommon`
inherited. That the base stylesheet survived is proven on the wire, not assumed — `css/login.css`
is still linked, now resolved through the `jtoye` path via parent-chain fallback:

```
--- diff: STOCK stylesheet list vs THEMED
5c5,6
< <link href="/resources/h8cet/login/keycloak/css/login.css" rel="stylesheet" />
---
> <link href="/resources/h8cet/login/jtoye/css/login.css" rel="stylesheet" />
> <link href="/resources/h8cet/login/jtoye/css/jtoye.css" rel="stylesheet" />
```

The four PatternFly `stylesCommon` links are unchanged in both lists.

### The theme is actually SERVED — asserted by content, not by screenshot

```
css http=200 bytes=11941
  served css contains #c2410c : 2
  served css contains #3a0b0d : 2
  served css contains #fbf6f0 : 2
  @font-face in served css: 1

font http=200 bytes=50524
magic=774f4632  (wOF2)
identical to the committed copy: YES
```

And the served stylesheet is byte-identical to what is committed, so the review is of the
committed artefact:

```
served bytes=13134  committed bytes=13134
SERVED CSS IS BYTE-IDENTICAL TO THE COMMITTED FILE
```

Fail direction: the stock stylesheet list captured **before** the flip contains no `jtoye.css`
(`jtoye_css_present=0 rc=1`).

### Measured in a real browser (final run, all pages)

```
customers @1440 sign-in / @390 / dev @1440 / dev @390 / reset-credentials @1440 / @390:
   body bg            rgb(251, 246, 240)   image: radial-gradient(...)
   body font          "Work Sans", ui-sans-serif, system-ui, ...
   Work Sans loaded   true
   header colour      rgb(58, 11, 13)  transform=none
   primary button bg  rgb(194, 65, 12)  radius=9999px
   link colour        rgb(58, 11, 13)
   HORIZONTAL OVERFLOW false
FAILURES: 0

update-password @1440: heading="Update password" pw=1 confirm=1 bodyBg=rgb(251,246,240) btn=rgb(194,65,12) font="Work Sans" workSans=true overflow=false
update-password @390 : heading="Update password" pw=1 confirm=1 bodyBg=rgb(251,246,240) btn=rgb(194,65,12) font="Work Sans" workSans=true overflow=false
error page      @1440: heading="We are sorry..." bodyBg=rgb(251,246,240) workSans=true
```

### The mobile-overflow assertion caught a real defect — mine

First run at 390px:

```
customers @390 sign-in: HORIZONTAL OVERFLOW true  (scrollW=411 clientW=390)
customers @390 reset-credentials: HORIZONTAL OVERFLOW true  (scrollW=411 clientW=390)
FAILURES: 2
```

Rather than guess, the offending elements were enumerated from the DOM:

```
{"tag":"div","id":"kc-info","cls":"login-pf-signup","left":-21,"right":411,"w":432,"ml":"-40px","mr":"-40px",...}
{"tag":"div","id":"kc-info-wrapper","left":-21,"right":411,"w":432,...}
```

Cause: the stock theme gives `#kc-info` `margin: 0 -40px` to cancel the stock card's `40px`
padding and make the footer strip full-bleed. This theme sets its own card padding (2rem desktop
/ 1.125rem mobile) but inherited the `-40px`, over-extending the strip by 22px each side. Both
the desktop and mobile rules now restate the margin to match their own padding, with a comment
recording that the two values are **coupled**. Re-measured: `FAILURES: 0` at every viewport. The
dev realm never overflowed, which is why a customer-only check was needed — it has no
registration footer.

### Theme caching — the claim was verified, not assumed

The stack runs `command: ["start-dev", "--import-realm"]`, and `start-dev` does not cache themes.
Proven by editing the CSS and re-fetching with **no restart**:

```
NEW rule present in SERVED css without a restart: 1
```

Under `start` the theme cache is on and each edit needs a restart. Recorded in the README.

### Mount and recreate

`/opt/keycloak/themes` in the Quarkus image ships only a `README.md` (built-ins live in the jar),
so the bind mount shadows nothing. Verified inside the container after recreate:

```
/opt/keycloak/themes/jtoye/login:  resources  theme.properties
/opt/keycloak/themes/jtoye/login/resources/css:    jtoye.css
/opt/keycloak/themes/jtoye/login/resources/fonts:  work-sans-latin.woff2
```

All three composes that define a Keycloak service carry the mount (`infra/docker-compose.yml` and
`infra/docker-compose.hostnet.yml` use `./keycloak/themes`, relative to their own context), so the
theme name cannot dangle on those stacks.

Realm state survived `--force-recreate` (Postgres-backed), re-read from the server:

```
jtoye-customers post-recreate: {"displayName":"J'Toye","loginTheme":"keycloak","smtpHost":"mailhog","resetPasswordAllowed":true}
jtoye-dev       post-recreate: {"displayName":"J'Toye","loginTheme":"keycloak","smtpHost":"mailhog","resetPasswordAllowed":false}
```

…then flipped, and re-read:

```
{"realm":"jtoye-customers","loginTheme":"jtoye","displayName":"J'Toye","smtpHost":"mailhog"}
{"realm":"jtoye-dev","loginTheme":"jtoye","displayName":"J'Toye","smtpHost":"mailhog"}
```

**The admin CLI's cached login does NOT survive a recreate** — `kcadm` returned "not
authenticated" until `config credentials` was re-run. Recorded in the README.

### Font sourcing

Work Sans reaches the app through `next/font/google`, so the only copies in the tree are
content-hashed subsets under the gitignored `frontend/.next/static/media/`. The primary latin
subset was copied to `resources/fonts/work-sans-latin.woff2` and **self-hosted** rather than
`@import`-ed from a font CDN: a login page is precisely the surface that should not make a
third-party request, and self-hosting adds no CSP or CORS surface. Verified empirically in the
browser (`document.fonts.check('16px "Work Sans"')` → `true` on every page) rather than by
metadata introspection, which was blocked by the machine's base-python guard.

### No regression, after the recreate

```
vendor_token_mint present=yes err=none
tenant_id_claim=00000000-0000-0000-0000-000000000001
inventory before=21 after_theme=21
INVENTORY_DIFF rc=0 (0 = identical)
verify-env.sh rc=0 (baseline was 0)
mailhog 13 -> 14 (delta=1)   # reset mail still delivers, same instrument
```

Compose-parsing gates, run because the compose files changed (rc=2 would be VOID, not a pass):

```
scripts/check-infra-exposure.sh        rc=0  PASS: all assertions passed
scripts/check-container-config-drift.sh rc=0  PASS: 16 running container(s) match their compose declaration
```

### A gate my own change broke, and fixed

Inserting 7 lines into `docker-compose.full-stack.yml` shifted eight `file:line` citations in
`.planning/codebase/STACK.md` and `INTEGRATIONS.md`, turning `check-doc-citations.sh` red
(`violations 8`). Each was moved +7 and **verified by content** at its new line (e.g. `200` →
`image: redis:7-alpine`), and the shifted MinIO range was checked byte-identical against the
pre-edit file via `md5sum`. They are committed **with** the compose change so no commit in the
branch leaves the gate red.

```
check-doc-citations rc=0
citations   total=80  verified=73  uncheckable=7
violations  0
```

`check-doc-metrics.sh` rc=0 and `docs-freshness.sh` rc=0 (3555) — `docs/metrics.json` still
untouched; the theme adds no test file.

### Commits (round 2)

```
4d0e2d6c docs(keycloak): document the jtoye login theme, superseding the built-in note
4fed2bcb feat(keycloak): add the jtoye login theme and point both realms at it
```

Both via `-F`, read back with `git log -1 --format=%B`, no trailer anywhere on the branch
(`trailer_count=0`).

### Deviation (round 2)

**5. [Scope extended by review] D-5's "no custom theme" boundary was overturned**

- **Found during:** the Task 3 human-verify gate.
- **Issue:** realm-level keys alone cannot brand a Keycloak login page; the plan's scope
  boundary was the defect, not the implementation of it.
- **Fix:** a CSS-only custom theme, mounted on all three composes, with `loginTheme` flipped on
  both templates and both running realms. The README's built-in-theme paragraph is superseded in
  place, keeping a history note on why it moved and which part of the original reasoning (pinning
  `loginTheme` explicitly) still holds.

---

## Code review fixes (PR #713 — 0 Critical, 9 Warnings, 6 Info)

Review: `.planning/quick/260831-jz4-fix-keycloak-realm-config-branded-login-/260831-jz4-REVIEW.md`.
Commits `c243effa` (CSS) and `ee2b5724` (docs + compose), pushed to the PR.

**The review found two real rendering defects, and both came from the same root cause: I wrote
the overlay against a mis-remembered stock stylesheet and then wrote confident comments asserting
the mistaken values.** The comments made the bugs unfindable by reading. Every stock value below
was re-extracted from `org.keycloak.keycloak-themes-24.0.5.jar` and is now quoted verbatim in the
CSS.

| ID | Disposition | Evidence |
|---|---|---|
| WR-01 | **Fixed** | `#kc-info` margin-bottom was `-30px` at every width — measured 390→1440 |
| WR-02 | **Fixed** | card full-bleed at 481/600/767 — measured, invisible to a 390px capture |
| WR-03 | **Fixed** | comment/README premise was fabricated; restated with measured values |
| WR-04 | **Fixed** | README block said `"keycloak"`, templates ship `"jtoye"` |
| WR-05 | **Fixed (documented, no service added)** | per-stack table; boundary recorded in both composes |
| WR-06 | **Not applied — premise false on KC 24.0.5** | bytecode shows both timeouts hardcoded |
| WR-07 | **Fixed (reworded)** | CHANGELOG + README now state the condition |
| WR-08 | **Fixed** | inline links `underline`; buttons exempt |
| WR-09 | **Fixed** | transparent outline + ring alpha 0.18 → 0.45 |
| IN-01 | **Fixed** | dead `::after` rule deleted |
| IN-02 | **Fixed** | "the realms" → "the imported realm" |
| IN-03 / IN-04 | **Noted, not changed** | `emailTheme` and `.local` recorded as boundaries |
| IN-05 | **Fixed** | snippet names 8085 vs hostnet's 8081 |
| IN-06 | **Not done** | see below |

### WR-01 / WR-02 — measured before and after

The stock values the overlay was written against, read out of the jar:

```
login.css:167   #kc-info { margin: 20px -40px -30px; }      <- three-value shorthand
login.css:514   .card-pf { padding: 0 20px; max-width: 500px; }
login.css:524   @media (max-width: 767px) { .login-pf-page .card-pf {
                  max-width: none; margin-left: 0; margin-right: 0; ... } }
```

My comment had claimed stock was `margin: 0 -40px` cancelling `40px` padding. Both halves false,
and the breakpoint was 767px, not the 480px I had used.

**Before (defect):**

```
w    | kcInfo mb | OVERHANG | card w | ml/mr    | radius
390  | -30px     | 13       | 366    | 12px/12px | 8px    <-- WR-01
480  | -30px     | 13       | 456    | 12px/12px | 8px    <-- WR-01
481  | -30px     | 5        | 481    | 0px/0px  | 16px   <-- WR-01 + WR-02 full-bleed
600  | -30px     | 5        | 600    | 0px/0px  | 16px   <-- WR-01 + WR-02 full-bleed
767  | -30px     | 5        | 767    | 0px/0px  | 16px   <-- WR-01 + WR-02 full-bleed
768  | -30px     | 5        | 500    | 134px    | 16px   <-- WR-01
1440 | -30px     | 5        | 500    | 470px    | 16px   <-- WR-01
```

**After:**

```
w    | kcInfo mb | OVERHANG | card w | ml/mr    | radius
390  | -16px     | -1       | 366    | 12px/12px | 8px
480  | -16px     | -1       | 456    | 12px/12px | 8px
481  | -24px     | -1       | 457    | 12px/12px | 8px
600  | -24px     | -1       | 576    | 12px/12px | 8px
767  | -24px     | -1       | 743    | 12px/12px | 8px
768  | -24px     | -1       | 500    | 134px    | 16px
1440 | -24px     | -1       | 500    | 470px    | 16px
```

`-1px` is the card's 1px bottom border, i.e. the strip is flush inside it.

**I went further than the review's literal snippet, on the review's own criterion.** It proposed
`margin: 0 -2rem`, which removes the `-30px` bug but leaves the strip floating 25px above the
card's bottom with a band of white beneath — failing the review's stated acceptance test that
"the strip's bottom edge and the card's bottom edge coincide". The negative bottom margin now
cancels the card's own `padding-bottom` at each breakpoint.

### WR-06 — not applied, because the premise does not hold on this version

The review asked for `connectionTimeout`/`timeout` keys, on the basis that Keycloak copies them
into JavaMail "only when the corresponding realm keys are present" and that JavaMail defaults to
no timeout. Verified in the shipped bytecode instead of assumed —
`org.keycloak.email.DefaultEmailSenderProvider` from
`org.keycloak.keycloak-services-24.0.5.jar` in the running image:

```
202: ldc  #79   // String mail.smtp.timeout
204: ldc  #81   // String 10000
206: invokevirtual Properties.setProperty
212: ldc  #83   // String mail.smtp.connectiontimeout
214: ldc  #81   // String 10000
216: invokevirtual Properties.setProperty
```

Both are `ldc` of a **constant**, not a lookup against the realm map, and the class's constant
pool contains **no standalone `timeout` or `connectionTimeout` string** (`grep -x` → rc=1). KC
24.0.5 already bounds both at 10 s and exposes no realm key for them. Adding the keys would be
dead config that reads as protection while doing nothing, so they were not added; the
disassembly and a re-check-on-upgrade instruction are in the README.

### WR-08 / WR-09 — verified by computed style

```
customers @390 / @600 / @1440: inlineLinkDecoration=underline  buttonDecoration=none
```

Focus now carries `outline: 2px solid transparent; outline-offset: 2px` alongside the box-shadow,
so `forced-colors: active` has something to repaint, and the ring alpha went 0.18 → 0.45.

### IN-06 — not done, and it is the right call to flag

The review proposes a `check-keycloak-theme-contract.sh` asserting that `styles=` retains
`css/login.css` and that each `#kc-info` margin matches its breakpoint's card padding. **It is
correct in principle and WR-01 is direct evidence for it** — the prose rule failed in the very
commit that wrote it, which is exactly this project's "a script that fails loudly, not a firmer
instruction" doctrine. It is not in this change because a gate must be shown to fail before it is
trusted, and doing that properly (fixture with a shortened `styles=`, a de-coupled margin, wiring
into `ops-contracts`, passing `check-gate-enforcement.sh`) is more than a docs-fix increment.
**Recommend filing it as a follow-up issue rather than treating it as closed.**

### Regression sweep after the fixes

```
served jtoye.css == COMMITTED file (16316 bytes)   [cmp; control confirmed cmp can detect a diff]
both login pages: <title>Sign in to J&#39;Toye</title>
reset journey: mailhog 14 -> 15 (delta=1), To=lane3-reverify-…@example.com, http=200
verify-env.sh                    rc=0  (baseline 0)
check-infra-exposure.sh          rc=0  (baseline 0)
check-container-config-drift.sh  rc=0  (baseline 0)
check-doc-metrics / citations / docs-freshness  rc=0 / 0 / 0
no horizontal overflow at 390, 600, 1440 on sign-in and reset — FAILURES: 0
```

**One check needed its own instrument questioned.** A naive brace count read the CSS as
unbalanced (57/55). Stripping comments first gave 53/53 BALANCED — the new comments quote stock
CSS containing unclosed `{`, which accounts for the difference exactly (4 open / 2 close in
comments). A deliberately broken fixture confirmed the code-only counter still detects a real
imbalance.

**And one gate failure that was not mine.** `docker compose config` on both `infra/` composes
returns rc=1 — because `infra/` has no `.env` (it lives at the repo root), not because of the
edit. Proven by running the *pre-edit* file from `git show` under the same conditions (also
rc=1 without `.env`, rc=0 with it) and then the *edited* files with `.env` present: both rc=0,
with the theme mount resolving `read_only: true`.

### New screenshots (post-review)

| Page | 390 | 600 (the WR-02 band) | 1440 |
|---|---|---|---|
| customers sign-in | `evidence/shots/30-customers-signin-390.png` | `30-customers-signin-600.png` | `30-customers-signin-1440.png` |
| vendor sign-in | `30-dev-signin-390.png` | `30-dev-signin-600.png` | `30-dev-signin-1440.png` |
| reset-credentials | `31-customers-reset-390.png` | `31-customers-reset-600.png` | `31-customers-reset-1440.png` |

---

## Self-Check

Files claimed modified, existence checked:

```
FOUND: infra/keycloak/realm-export.template.json
FOUND: infra/keycloak/realm-export-customers.template.json
FOUND: infra/keycloak/README.md
```

Theme files created, existence checked:

```
FOUND: infra/keycloak/themes/jtoye/login/theme.properties
FOUND: infra/keycloak/themes/jtoye/login/resources/css/jtoye.css
FOUND: infra/keycloak/themes/jtoye/login/resources/fonts/work-sans-latin.woff2
```

Commits claimed, existence checked:

```
FOUND: 9fc63383
FOUND: c869bc8f
FOUND: 4fed2bcb
FOUND: 4d0e2d6c
FOUND: c243effa
FOUND: ee2b5724
```

## Self-Check: PASSED
