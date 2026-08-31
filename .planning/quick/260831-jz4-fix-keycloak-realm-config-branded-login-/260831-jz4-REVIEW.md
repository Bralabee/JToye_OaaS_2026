---
phase: 260831-jz4-fix-keycloak-realm-config-branded-login
reviewed: 2026-08-31T14:42:45Z
depth: quick
diff_base: origin/main
files_reviewed: 10
files_reviewed_list:
  - docker-compose.full-stack.yml
  - infra/docker-compose.yml
  - infra/docker-compose.hostnet.yml
  - infra/keycloak/realm-export.template.json
  - infra/keycloak/realm-export-customers.template.json
  - infra/keycloak/themes/jtoye/login/theme.properties
  - infra/keycloak/themes/jtoye/login/resources/css/jtoye.css
  - infra/keycloak/themes/jtoye/login/resources/fonts/work-sans-latin.woff2
  - infra/keycloak/README.md
  - docs/CHANGELOG.md
findings:
  critical: 0
  warning: 9
  info: 6
  total: 15
status: issues_found
---

# Quick task 260831-jz4: Code Review Report

**Reviewed:** 2026-08-31T14:42:45Z
**Depth:** quick (pattern scan, extended with targeted verification against the shipped Keycloak 24.0.5 themes jar)
**Files Reviewed:** 10
**Status:** issues_found

## Summary

Auth-surface configuration change: `smtpServer` + realm branding keys on both realm templates,
a new CSS-only `jtoye` login theme, and a read-only theme bind mount on all three compose files.

**Zero Critical.** That is a verdict, not a silence — I ran the security lens the task specified
and every one of its four questions came back clean, verified rather than assumed:

- `displayNameHtml` is a fixed operator literal (`<span style="…">J'Toye</span>`), no
  interpolation, no script, no `$` of any kind. It is an unescaped HTML sink, but nothing
  reaches it from request data.
- No secret landed. All 10 `${…}` envsubst placeholders across the two templates are intact
  (5 in each) and no new field contains a `$`. A `+`-line grep for
  `password|secret|api_key|token|credential|PRIVATE KEY` surfaced only prose and CSS ids.
- All three theme mounts declare `:ro`, and each relative path is correct for its own file's
  directory (`./infra/keycloak/themes` at repo root, `./keycloak/themes` under `infra/`).
- `auth:"false"` / `ssl:"false"` are dev-only in fact as well as in intent: no k8s manifest
  deploys a Keycloak, imports a realm, or mounts these templates (verified across `k8s/`), so
  no production path consumes them.

**What is wrong is the layer below the security lens: the theme CSS was written against a
mis-remembered stock stylesheet, and the docs disagree with the config they document.** I
extracted `theme/keycloak/login/resources/css/login.css` and `theme.properties` out of
`org.keycloak.keycloak-themes-24.0.5.jar` in the running image and diffed the assumptions —
two of the stylesheet's load-bearing premises are false, which produces two real rendering
defects (WR-01, WR-02) that reading the code cannot reveal because the comments assert the
opposite. Separately the README documents `loginTheme: "keycloak"` while shipping `"jtoye"`
(WR-04), and `smtpServer.host: "mailhog"` is wrong-by-construction on two of the three compose
stacks it ships to (WR-05).

### Verified correct (recorded so a fixer does not re-litigate)

| Claim under review | Verdict | Evidence |
|---|---|---|
| `theme.properties` `styles` retains the base stylesheet | **Correct** | Parent `theme/keycloak/login/theme.properties` in the jar is exactly `parent=base`, `import=common/keycloak`, `styles=css/login.css`. The overlay's `styles=css/login.css css/jtoye.css` reproduces it; `stylesCommon` correctly left inherited. |
| Mounting `/opt/keycloak/themes` shadows no built-in theme | **Correct** | `docker run quay.io/keycloak/keycloak:24.0.5 ls -la /opt/keycloak/themes` → `README.md` only; themes live in `/opt/keycloak/lib/lib/main/org.keycloak.keycloak-themes-24.0.5.jar`. |
| The woff2 is a plausible, correctly-declared variable font | **Correct** | Magic `wOF2`, 50,524 B (not oversized), 19 tables including `fvar`, `gvar`, `avar`, `HVAR`, `STAT` → genuinely variable, so `font-weight: 100 900` is right, not a faux-bold trap. |
| No user input interpolated into the CSS | **Correct** | Exactly one `url()` (relative, self-hosted font). No `@import`, no `data:`, no `expression()`, no external origin, no TODO/FIXME/debug residue. |
| Both templates still render to valid JSON | **Correct** | Both parse after placeholder substitution; **no duplicate keys** (checked with an `object_pairs_hook`, since JSON duplicates resolve silently last-wins). |
| README's Route-1 data-loss warning | **Correct and valuable** | `realm-export-customers.template.json:78` is `"users" : [ ]`, so a `--override true` import would indeed delete live storefront registrations. |
| Realm CSP permits the inline style and the self-hosted font | **Correct** | `frame-src 'self'; frame-ancestors 'self'; object-src 'none';` — no `style-src`, no `font-src`, no `default-src`. |
| The branding change breaks no test | **Correct** | No Playwright spec asserts on the Keycloak login header or realm display name. |
| `scripts/verify-env.sh` still parses the templates | **Correct** | It greps `±2` lines around `"type":"password"` for `${VAR}` tokens; the new blocks contain no `$`. |

---

## Warnings

### WR-01 — `#kc-info` silently inherits the stock `margin-bottom: -30px`; the comment justifying the rule states the stock value wrongly

**Severity:** WARNING (Major — incorrect rendering on every login page, at every breakpoint)
**File:** `infra/keycloak/themes/jtoye/login/resources/css/jtoye.css:307-318`, `:416-420`

**Issue.** The rule uses three longhands and never touches `margin-bottom`:

```css
#kc-info {
  margin-top: 0;
  margin-left: -2rem;
  margin-right: -2rem;
}
```

Its comment asserts *"The stock theme achieves that with `margin: 0 -40px`, which exactly cancels
the stock card's 40px horizontal padding."* **Both halves are false.** Read out of
`org.keycloak.keycloak-themes-24.0.5.jar`:

```
login.css:167   #kc-info { margin: 20px -40px -30px; }
login.css:514   .card-pf { margin: 0 auto; box-shadow: …; padding: 0 20px; max-width: 500px; … }
```

The stock margin is a **three-value shorthand** carrying `margin-bottom: -30px`, and the stock
card padding is `0 20px`, not `40px`. Because the overlay overrides only top/left/right, the
stock `-30px` bottom survives at every width. `.card-pf` here has `padding-bottom: 1.5rem` (24px)
desktop and `1rem` (16px) at ≤480px, so the footer strip is pulled 6px (desktop) / 14px (mobile)
below the card's border box — exactly where `#kc-info-wrapper` is given
`border-radius: 0 0 1rem 1rem` and the card `border-radius: 1rem`, so the two rounded bottoms
cannot coincide and the strip overhangs as a cream lip. Nothing sets `overflow: hidden` to clip it.

This is the harder class of defect: the comment is confident and wrong, so a maintainer reading
the file will never find it.

**Fix.**

```css
#kc-info {
  /* stock is `margin: 20px -40px -30px` — the shorthand's -30px bottom must be
     reset explicitly or it survives a longhand override. */
  margin: 0 -2rem; /* == .card-pf horizontal padding; top and bottom both zeroed */
}
```

and at the ≤480px breakpoint likewise `margin: 0 -1.125rem;`. Then re-take the visual proof and
confirm the strip's bottom edge and the card's bottom edge coincide.

---

### WR-02 — mobile fix is scoped to 480px while the stock theme's phone block is 767px, leaving a 481–767px band with unreconciled geometry

**Severity:** WARNING (Major — visible layout defect on a whole device class, on a login page, in a mobile-first project)
**File:** `infra/keycloak/themes/jtoye/login/resources/css/jtoye.css:401-436`

**Issue.** The overlay's only mobile block is `@media (max-width: 480px)`. The stock theme's is
`@media (max-width: 767px)` and contains:

```
login.css:524   @media (max-width: 767px) {
login.css:525     .login-pf-page .card-pf {
login.css:526       max-width: none;
login.css:527       margin-left: 0;
login.css:528       margin-right: 0;
login.css:529       padding-top: 0;
login.css:530       border-top: 0;
login.css:531       box-shadow: 0 0;
```

`padding-top`, `border-top` and `box-shadow` are re-asserted by the overlay's base rule (same
specificity `(0,2,0)`, later source order, so it wins). But `max-width: none`,
`margin-left: 0` and `margin-right: 0` are **never overridden** — the overlay sets those only
inside its own `≤480px` block. So across **481–767px** the card renders full-bleed to the
viewport with the overlay's desktop `border-radius: 1rem`, 3px oxblood top border and
`0 12px 32px -8px` drop shadow: rounded corners and shadow clipped flush at both edges.

The overlay's own comment at line 410 says the ≤480px gutter exists *"so the rounded corners are
not clipped flush against the viewport edge"* — the fix was applied to only half the range where
that condition holds. The recorded verification covers 390px, which cannot see this band.

**Fix.** Align the overlay's breakpoint with the stock theme's, or restate the three properties:

```css
@media (max-width: 767px) {
  .login-pf-page .card-pf {
    margin-left: 0.75rem;
    margin-right: 0.75rem;
    border-radius: var(--jtoye-radius);
  }
}
```

Keep the ≤480px block for the padding/type steps, and re-run the no-overflow assertion at 480px,
600px and 767px — not only at 390px.

---

### WR-03 — the "coupled values" invariant recorded in code and README is not the stock theme's actual behaviour

**Severity:** WARNING
**File:** `infra/keycloak/themes/jtoye/login/resources/css/jtoye.css:307-313`; `infra/keycloak/README.md` ("Coupled values to keep in step")

**Issue.** Both places state the rule as *"the `#kc-info` negative margins … must equal the card's
horizontal padding. The stock theme's `-40px` matches the stock `40px` padding."* Per WR-01's
evidence, stock is `-40px` against `0 20px` padding — the stock theme does **not** satisfy the
invariant being cited as its precedent. The invariant the overlay enforces is a reasonable one to
adopt, but its stated derivation is fabricated, so a future maintainer who checks the premise will
either distrust the whole comment or "correct" the margin back to `-40px`.

**Fix.** Restate as an invariant the overlay *chooses*, with the measured stock values quoted
accurately:

```
/* Stock is `#kc-info { margin: 20px -40px -30px }` against `.card-pf { padding: 0 20px }` —
   the stock pair does NOT cancel. This theme adopts the stricter invariant that the negative
   margin equals its own card padding, at both breakpoints. */
```

---

### WR-04 — README documents `loginTheme: "keycloak"` while both templates ship `"jtoye"`

**Severity:** WARNING (Major — the documented config silently reverts the entire deliverable)
**File:** `infra/keycloak/README.md:126` vs `infra/keycloak/README.md:136`

**Issue.** The "Branding keys" block presented as the config to use reads:

```json
"loginTheme"      : "keycloak"
```

Ten lines later the prose says *"`loginTheme` is `jtoye`, the custom theme in this repository"*,
and both templates ship `"loginTheme" : "jtoye"`. The "History" note explains the value **used
to be** `keycloak` — the block was left at the superseded value when the theme replaced it. An
operator copying the block onto a running realm (which the README's own Route 2 workflow tells
them to do) silently reverts every login page to stock while all other keys look right.

**Fix.** `infra/keycloak/README.md:126` → `"loginTheme"      : "jtoye"`.

---

### WR-05 — `smtpServer.host: "mailhog"` is unresolvable on two of the three compose stacks it ships to, and the README contradicts itself about it

**Severity:** WARNING
**File:** `infra/keycloak/realm-export.template.json:1611-1619`; `infra/docker-compose.yml`; `infra/docker-compose.hostnet.yml`; `infra/keycloak/README.md`

**Issue.** Neither `infra/docker-compose.yml` (services: `postgres`, `keycloak-realm-render`,
`keycloak`) nor `infra/docker-compose.hostnet.yml` (services: `keycloak-realm-render`,
`keycloak`) defines a Mailhog service. `hostnet.yml` additionally sets `network_mode: host`,
where compose service-name DNS does not exist at all — even a Mailhog running in the full-stack
project and published on `127.0.0.1:1025` would need `localhost`, never `mailhog`.

The README states this **both ways in the same section**:

> `host` is the literal compose service name because Keycloak and Mailhog share the same compose
> network, so service-name DNS resolves from inside the Keycloak container.

then twenty lines later:

> neither compose under `infra/` has a Mailhog service at all and one of them uses host networking

The first sentence is unconditional and false for two of three stacks; a reader who stops there
is misled.

Currently latent rather than live: those two stacks import only `realm-export.json`
(`jtoye-dev`), and `jtoye-dev` has `resetPasswordAllowed: false`, so no reachable path hits SMTP
there today. That makes it a trap for whoever flips that flag, not a present outage — hence
WARNING, not Critical.

**Fix.** Two independent actions: (a) delete or qualify the first sentence so the section has one
answer, e.g. *"`host` is the full-stack compose's Mailhog service name; on the two composes under
`infra/` there is no Mailhog and this value is deliberately inert — see below"*; (b) note in
those two compose files, beside the realm mount, that the imported realm names an SMTP host that
stack does not provide.

---

### WR-06 — `smtpServer` sets no `connectionTimeout`/`timeout`, so a wedged SMTP peer blocks Keycloak request threads indefinitely

**Severity:** WARNING (availability, on an unauthenticated endpoint)
**File:** `infra/keycloak/realm-export.template.json:1611-1619`; `infra/keycloak/realm-export-customers.template.json:7-15`

**Issue.** Keycloak's SMTP sender copies `mail.smtp.connectiontimeout` and `mail.smtp.timeout`
into the JavaMail session **only when the corresponding realm keys are present**. Both are absent
here, and JavaMail's default is *no timeout*. DNS failure (the WR-05 case) fails fast and is
harmless, but a peer that accepts TCP and never speaks SMTP — a Mailhog container mid-start, a
paused container, a stale port binding — parks the handling thread forever. The endpoint that
triggers it, `reset-credentials`, is public and unauthenticated, so a handful of submits can
exhaust the worker pool and take the login server down. Two keys make it bounded.

**Fix.**

```json
"smtpServer" : {
  "host" : "mailhog",
  "port" : "1025",
  "connectionTimeout" : "5000",
  "timeout" : "10000",
  …
}
```

(strings, consistent with the rest of the map).

---

### WR-07 — the "account-enumeration oracle closed" claim rests on an unguarded side effect, with no check that can fail

**Severity:** WARNING (security regression risk)
**File:** `docs/CHANGELOG.md` (the `#713` entry, second bullet)

**Issue.** The entry records a security finding as closed:

> **Account-enumeration oracle closed**: the existing-vs-nonexistent reset responses, previously
> distinguishable (70-line token-stripped diff plus differing HTTP codes), are now byte-identical.

Nothing in this diff makes the two responses identical *as a property*. They became identical as
a **side effect** of SMTP becoming reachable: with SMTP working, the existing-account branch
stops throwing 500 and both branches render the same "check your email" page. The moment SMTP is
unreachable — Mailhog stopped, the WR-05 stacks, the WR-06 hang, a `.env` change — the 500-vs-200
divergence returns and the oracle re-opens, silently, with every gate still green.

This is the exact shape this project's own doctrine names: a security property asserted in prose
where an executable check is required, and one that has never been shown to fail.

**Fix.** Add a check that submits reset-credentials for a known-present and a known-absent address
and asserts status + token-stripped body equality, and prove it fails by running it with Mailhog
stopped. Until then, soften the CHANGELOG to state the mechanism and its dependency:
*"…are identical **while SMTP is reachable**; the divergence returns if it is not, and is not yet
guarded."*

---

### WR-08 — global `a { text-decoration: none }` leaves links indistinguishable from body text (WCAG 1.4.1)

**Severity:** WARNING (accessibility, every login-flow page)
**File:** `infra/keycloak/themes/jtoye/login/resources/css/jtoye.css:260-275`

**Issue.** The rule sets `color: var(--jtoye-oxblood)` (`#3A0B0D`) and `text-decoration: none` on
a bare `a` selector, with the underline restored only on `:hover`. WCAG 2.1 SC 1.4.1 (Use of
Color) requires that where colour alone distinguishes a link from surrounding text, the two
differ by at least 3:1. Computed:

| Link vs surrounding text | Ratio | Verdict |
|---|---|---|
| `#3A0B0D` on body ink `#1C1917` | **1.03 : 1** | fails (visually identical) |
| `#3A0B0D` on `#kc-info-wrapper` muted `#57534E` | **2.21 : 1** | fails |

This hits inline links specifically — `#kc-registration` renders `New user? <a>Register</a>`
inside a sentence, as does `#kc-info-wrapper` — where an underline is the only remaining cue.
Contrast *against the background* is fine (14.4 : 1); it is the link-vs-text delta that fails.
Keyboard focus is handled (`a:focus-visible`), so this is a pointer/reading defect only.

Note this surface is outside the frontend axe gate's reach, so CI cannot catch it.

**Fix.** Underline in the default state and reserve the hover change for emphasis:

```css
.login-pf a, #kc-info-wrapper a, #kc-registration a, #kc-form-options a, a {
  color: var(--jtoye-oxblood);
  font-weight: 600;
  text-decoration: underline;
  text-underline-offset: 0.15em;
}
.login-pf a:hover, #kc-registration a:hover, a:hover {
  color: var(--jtoye-primary);
}
```

---

### WR-09 — `outline: none` with a `box-shadow`-only focus ring disappears entirely under forced-colors

**Severity:** WARNING (accessibility)
**File:** `infra/keycloak/themes/jtoye/login/resources/css/jtoye.css:174-180`, `:232-235`

**Issue.** `.pf-c-form-control:focus{,-visible}` and `.pf-c-button.pf-m-primary:focus-visible`
both set `outline: none` and carry the indicator entirely in `box-shadow` + `border-color`. Under
`forced-colors: active` (Windows High Contrast) the user agent discards author `box-shadow` and
overrides `border-color`, leaving **no visible focus indicator at all** on the username, password
and submit controls — i.e. the login form becomes unusable by keyboard in that mode. There is no
`@media (forced-colors: active)` fallback in the file. Separately, the ring itself is
`rgba(194,65,12,0.18)` ≈ `#F5E3DB` over white — about 1.13:1, so even in normal mode the visible
cue is the 1px border colour change alone.

**Fix.** Keep a transparent outline so forced-colors has something to repaint, and thicken the ring:

```css
.pf-c-form-control:focus,
.pf-c-form-control:focus-visible,
.pf-c-button.pf-m-primary:focus-visible {
  outline: 2px solid transparent;   /* repainted by forced-colors */
  outline-offset: 2px;
  box-shadow: 0 0 0 3px rgba(194, 65, 12, 0.45);
}
```

---

## Info

### IN-01 — dead CSS: `::after` on `.pf-c-form-control`, with an incorrect rationale

**File:** `infra/keycloak/themes/jtoye/login/resources/css/jtoye.css:182-186`

`kcInputClass=pf-c-form-control` (verified in the parent `theme.properties`) is applied to
`<input>` and `<select>`, neither of which generates `::before`/`::after` boxes. The rule can
never match, and the comment *"PatternFly draws focus as a thick bottom border via a generated
element"* is not what PatternFly 4 does (it styles `border-bottom` on the control itself, which
lines 174-180 already handle). **Fix:** delete both selectors and the comment.

### IN-02 — compose comments say "the realms" plural on stacks that import one realm

**File:** `infra/docker-compose.yml`, `infra/docker-compose.hostnet.yml` (theme-mount comment)

Both read *"the realms set loginTheme=jtoye"*, but each mounts only `realm-export.json`
(`jtoye-dev`); `jtoye-customers` exists solely on `docker-compose.full-stack.yml`.
**Fix:** singular — "the imported realm sets `loginTheme=jtoye`".

### IN-03 — `emailTheme` unset, so the reset email itself stays stock Keycloak

**File:** both realm templates

`loginTheme` is set; `accountTheme` and `emailTheme` are absent. The password-reset message a
customer receives — the first branded touchpoint the SMTP work makes reachable — renders in the
stock unbranded theme. Correctly *not* set to `jtoye` (the theme ships only a `login/` type, so
that would dangle), but worth recording as scope rather than leaving it to be discovered.
**Fix:** note the boundary in the README, or add a `jtoye/email/` type in follow-up work.

### IN-04 — `from: no-reply@jtoye.local` uses an mDNS-reserved TLD

**File:** both realm templates

`.local` is reserved for multicast DNS (RFC 6762). Harmless against Mailhog, which accepts
anything, and the README correctly scopes these templates to dev — but any real relay will reject
or bounce it, so the value cannot be promoted as-is. **Fix:** none required; a one-line comment in
the README's SMTP block would prevent a future copy-paste.

### IN-05 — README verification snippet hardcodes port 8085; hostnet's Keycloak listens on 8081

**File:** `infra/keycloak/README.md` ("Verify the theme is actually applied, by content")

`curl … http://localhost:8085/resources/<v>/login/jtoye/css/jtoye.css` is right for
`docker-compose.full-stack.yml` and `infra/docker-compose.yml`, but `hostnet.yml` starts Keycloak
with `--http-port=8081`. **Fix:** say which stack the snippet targets.

### IN-06 — two invariants the README calls silent-failure modes have no executable gate

**Files:** `infra/keycloak/themes/jtoye/login/theme.properties`; `infra/keycloak/README.md`

The README names two failure modes it expects prose to prevent: dropping `css/login.css` from
`styles` (*"renders the page unstyled … and it fails silently"*) and de-coupling the `#kc-info`
margin from the card padding. WR-01 is direct evidence that the second prose rule did not hold
even in the commit that wrote it. The tree carries 40+ `scripts/check-*.sh` gates and a
`check-gate-enforcement.sh` meta-gate; the project's standing doctrine is that a recurring
failure is answered with a script that fails loudly, not a firmer instruction. **Fix:** a small
`scripts/check-keycloak-theme-contract.sh` asserting (a) `styles=` contains `css/login.css`,
(b) each `#kc-info` horizontal margin equals its breakpoint's `.card-pf` horizontal padding —
proven by running it against a deliberately shortened `styles=` line first.

---

_Reviewed: 2026-08-31T14:42:45Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: quick_
