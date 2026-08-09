---
phase: 33
slug: the-consumer-product
scope: ADDITIVE plans 33-08 + 33-09 (issue #619 postcode-proximity search)
status: verified
threats_open: 0
threats_total: 15
threats_closed: 15
asvs_level: 1
block_on: high
tree_audited: feature/33-08-postcode-proximity-search @ bf10ffe7
created: 2026-08-09
updated: 2026-08-09
remediation_round: 1
---

# Phase 33 (plans 33-08 / 33-09) — Security

> Per-plan security contract for the two ADDITIVE plans that closed #619, verified separately from
> `33-SECURITY.md` (plans 33-00 … 33-07, 53/53 CLOSED, shipped via PR #620). **That document is a
> closed artefact and was not modified.**
>
> The security surface of these two plans is: a new **interpretation branch** on the already-anonymous
> `GET /api/v1/public/shops?q=`, a first non-primary-key query on the RLS-exempt reference table
> `postcode_centroid`, a new **response header derived from customer input** (`X-Search-Interpretation`),
> one added name on the CORS exposed-headers allowlist, and the storefront rendering that repeats the
> header's claim. **No new endpoint, no new authentication surface, no new dependency, no migration.**
>
> Register authored at plan time: `T-33-08-01`…`T-33-08-08` + `T-33-08-SC` (9 rows) and
> `T-33-09-01`…`T-33-09-05` + `T-33-09-SC` (6 rows) — **15 rows**, read verbatim from the two
> `<threat_model>` blocks.

**Verification stance (FORCE).** Every threat was treated as OPEN until a match in the *cited
location* proved otherwise. Nothing was accepted on a SUMMARY's word, on the code review's word, or
on the shape of the code ("it looks like it validates"). Where the evidence is a negative, a
**positive control** was run first to prove the pattern can match — an empty grep is evidence about
the pattern, not about the code. Controls are cited inline as `CONTROL:`. All searches that carry
evidential weight were run with `rg -uu`. Where the evidence is a gate, the gate was **executed**.
The working tree was never modified.

---

## Two post-plan changes verified against, because the registers' wording predates them

### 1. The D-A flip — `3b038825`, interpretation-first

The registers were written when the postcode attempt ran **third**, after FTS and after the `LIKE`
fallback. The owner gate reversed that: `PublicStorefrontService.searchPublishedShops` now offers
every `q` to the geocoder **first** (`:463`). Three register rows had to be re-verified under the new
ordering rather than inherited:

| Row | What changed | Verdict |
|---|---|---|
| `T-33-08-01` | The regex DoS control moved from "reached only on a zero-result search" to **the first thing every anonymous search touches**. | **Still CLOSED, and now more load-bearing.** The length short-circuit is at `PostcodeGeocoder.java:183-187`, which precedes the matcher at `:189` and both lookups (`:202`, `:231`). The order is a source-line fact, not an argument. |
| `T-33-08-02` | The district aggregate is now reachable on **every postcode-shaped `q`**, not only on one that found no text match. | **Still CLOSED.** Bounded by the same closed-range + length-guard Index Scan. A non-postcode-shaped term still issues **zero** queries — `SEARCH_POSTCODE` is anchored at both ends (`:88-89`) and `matches()` fails before any repository call (`:190-194`). |
| `T-33-08-03` | **More** requests now take the proximity path, so the leakage question is asked more often. | **Still CLOSED.** Same query object: `nearestPublished` (`:357`) is the single shared tail, called from both `listPublishedShopsNear` (`:332`) and the postcode tier (`:474`). |

One artefact-level drift, recorded rather than filed as a threat: `33-08-PLAN.md`'s `key_links` still
describes the postcode tier as "reached only when FTS and the LIKE fallback both return empty". That
is now false of the code. The **in-code** statement of the same fact was the review's WR-01 and is
fixed (`PublicStorefrontService.java:78-91`); the plan's front-matter is a historical document and
was deliberately not edited.

### 2. The review fix pass — `e9151a01`…`2bcb10c5`

Five warnings, all fixed. Two were named for explicit security verification:

**WR-03 — `MisconfiguredPlatformRadiusException` must not leak operator detail to an anonymous
client.** VERIFIED. `GlobalExceptionHandler.java:102-110`:

- status `INTERNAL_SERVER_ERROR` (500), **not** the generic `IllegalStateException` 400 at `:112`;
  Spring resolves the closest handler in the exception hierarchy, and the subclass wins.
- `detail` is the constant string `"Distance search is not available"` — **not** `ex.getMessage()`.
- `title` (`"Misconfigured Platform Radius"`) and `type`
  (`https://jtoye.uk/errors/misconfigured-platform-radius`) are constants. No `setProperty` carries
  the message.
- The specifics — which DO name `jtoye.geo.default-radius-km` / `jtoye.geo.max-radius-km`
  (`PublicStorefrontService.java:160-174`) — reach only `log.error` at `:104`.

So no config-key name and no operator detail reaches the client on this path. The type-level contract
is asserted at `PublicStorefrontServiceTest:1079-1094` (`must not inherit the 400 handler that blames
the caller`). **Evidence gap, recorded not glossed:** no MockMvc arm renders the 500 body, so the
"generic detail" half rests on reading the handler plus Spring's resolution order rather than on an
executed assertion. See [§ Observations](#observations) OB-1.

**WR-04 — the CORS floor cannot be removed by env override.** VERIFIED, structurally.

- `MANDATORY_EXPOSED_HEADERS` (`CorsConfig.java:80-85`) is a `static final List.of(...)` compile-time
  constant with **no property placeholder** — it cannot be bound, overridden or emptied by
  configuration.
- It is applied **unconditionally** on the single construction path:
  `config.setExposedHeaders(withMandatoryExposures(exposedHeaders))` at `:139`. There is no branch
  around it.
- The merge is case-insensitive (`:108`), so `retry-after` cannot defeat `Retry-After`.
- **Single CORS source.** Swept `core-java/src/main/java/` for `setExposedHeaders` /
  `addExposedHeader` / `CorsConfigurationSource` / `addCorsMappings` / `@CrossOrigin`: the only
  producer is `CorsConfig`. `SecurityConfig.java:128` is `.cors(Customizer.withDefaults())`, which
  consumes the `corsFilter` bean rather than defining a second list. A floor with a second, unfloored
  path would be a floor in name only; there is no second path.
- Driven in the fail direction by four arms: `noOverrideCanRemoveAMandatoryName` (`:299-312`,
  including the `List.of()` and `null` degenerate cases), `anOverrideOmittingTheInterpretationHeader
  StillExposesIt` (`:285-297`), `theFloorIsCaseInsensitive` (`:323-335`), and the non-vacuity control
  in the other direction, `theShippedDefaultAlreadySatisfiesTheFloor` (`:337-349`) — which proves the
  floor adds **nothing** to the list that actually ships, so a regression in `application.yml` cannot
  hide behind it.

---

## Trust Boundaries

| Boundary | Description | Data / privilege crossing |
|----------|-------------|---------------------------|
| anonymous client → `GET /api/v1/public/shops?q=` | Untrusted `q` crosses here, unauthenticated, with **no `TenantContext`** — `current_tenant_id()` is NULL and `shops_public_read`'s `published = true` limb is the only thing standing | Arbitrary customer text, now interpreted before any text match |
| `PostcodeGeocoder` → `postcode_centroid` | A derived **range** predicate crosses into SQL where previously only a primary key did | `rangeStart` / `rangeEnd` / `unitLength`, computed in Java |
| `PublicStorefrontService` → `shops` (FORCE RLS) | The deliberately cross-tenant published read, via 33-06's `findPublishedNear` | Every published shop, cross-tenant by design |
| customer `q` → HTTP response header | A value derived from untrusted input crosses into a header sink, then into proxy and access logs | The normalised `[A-Z0-9]{2,8}` key, the precision, the radius |
| core API → browser JavaScript | CORS. A header is on the wire and invisible to script unless `Access-Control-Expose-Headers` names it | One added name; six pre-existing names must not be displaced |
| Next.js server → browser | The SSR seed. `storefront-server.ts` resolves the **internal** core host | The interpretation header, read server-to-server (not subject to CORS) |
| customer input → rendered DOM | The searched term is echoed into the summary copy | The raw `q` on the text branch; the server's key on the proximity branch |
| operator env → query input | `GEO_DEFAULT_RADIUS_KM` / `GEO_MAX_RADIUS_KM` / `CORS_EXPOSED_HEADERS` decide what the page ASSERTS | A misconfiguration that makes the page state the opposite of the truth (WR-03 / WR-04) |

---

## Threat Register — verification results

**15 threats · 15 CLOSED · 0 OPEN.** 10 `mitigate`, 5 `accept`, 0 `transfer`.

### 33-08 — API half: the interpretation branch and its header (9)

| ID | Category | Disposition | Result | Evidence |
|----|----------|-------------|--------|----------|
| T-33-08-01 | Denial of service | mitigate | CLOSED | `PostcodeGeocoder.java:183-187` — `term.length() > MAX_SEARCH_TERM_LENGTH` (`:99`, = 12) returns empty **before** the matcher at `:189` and before both lookups (`:202`, `:231`); the order is a source-line fact. `SEARCH_POSTCODE` (`:88-89`) is anchored at **both** ends, every quantifier bounded (`{1,2}`, `{0,4}`), no nested repetition. Proven **not by timing** — timing passes on a slow regex too — but by `PostcodeGeocoderTest:396-408`: a 400-character input with `verify(repository, never()).findById(...)` **and** `never()).findDistrictCentroid(...)`. Live limb: `PublicStorefrontPostcodeSearchIntegrationTest:584-587`, a 400-character `q` answered by the text page |
| T-33-08-02 | Denial of service | mitigate | CLOSED | `PostcodeCentroidRepository.java:70-81` — CLOSED range `postcode >= :rangeStart AND postcode <= :rangeEnd AND length(postcode) = :unitLength`, **three named JPA parameters, nothing concatenated**, no `LIKE` anywhere in the query body (the 5 `LIKE` matches in the file are in the comment block explaining the rejection — CONTROL for the zero). Bounds computed in Java at `PostcodeGeocoder.java:227-229`. Measured `EXPLAIN (COSTS OFF)` on the live 1,748,230-row table recorded in `33-08-SUMMARY.md:107-133`: shipped predicate → `Index Scan using postcode_centroid_pkey`; `LIKE 'SE22%'` → `Parallel Seq Scan`. Counts cross-checked 507 / 548, and 6,422 without the length guard |
| T-33-08-03 | Information disclosure | mitigate | CLOSED | `ShopRepository.findPublishedNear` carries `s.published = true` in the row query (`:112`) **and** in the `countQuery` (`:130`) — reused unchanged; this phase added no shop query. Single shared tail `PublicStorefrontService.nearestPublished` (`:357-403`), called from the lat/lon path (`:332`) and the postcode tier (`:474`). `PublicStorefrontPostcodeSearchIntegrationTest:515-538`: unpublished shop seeded on the nearest shop's **exact** coordinates, absent from content AND `totalElements` **at `size=2`**, where `PageableExecutionUtils` actually issues the count — plus two non-vacuity assertions that the hidden row exists and is inside the radius. `:544-561`: `>= 2` distinct tenants, slugs from the **response** and tenant ids from the **database**, with `TenantContext.get()` asserted empty first |
| T-33-08-04 | Information disclosure | mitigate | CLOSED | `SearchInterpretation.java:113-115` interpolates exactly three tokens — postcode, precision, radiusKm. No latitude, longitude or `Coordinate` reaches the record. Asserted at `PublicStorefrontServiceTest:1229-1239`. Sweep for a coordinate in any log line across `PublicStorefrontService.java` + `PostcodeGeocoder.java` → **0**. CONTROL: the same pattern over a scratch copy with `log.debug("lat={} lon={}", …)` injected → **1** |
| T-33-08-05 | Tampering | mitigate | CLOSED | `SearchInterpretation.java:69` `SAFE_KEY = ^[A-Z0-9]{2,8}$`, applied at `:110-112` **before** interpolation; a failing key returns the literal `text`. Defence in depth at the sink — upstream the key is already `matcher.group(n).toUpperCase(Locale.ROOT)` over a charset that admits no CR/LF/`;`. **The sink is singular:** `setHeader`/`addHeader` across the whole `storefront` package → exactly one occurrence, `PublicStorefrontController.java:156`, whose only argument is `outcome.interpretation().headerValue()` (CONTROL: the same pattern matches 14 times in `RateLimitInterceptor`, so it can find sinks). Arms at `PublicStorefrontServiceTest:1200-1216` (`SE22\r\nX-Evil: 1`, `SE22; precision=unit`, `SE22\nSet-Cookie: a=b`, lowercase, empty, too-short) **paired with the control** `legitimateKeyIsNotRejected` (`:1218-1227`) — without which a `headerValue()` returning `text` unconditionally would satisfy every hostile arm |
| T-33-08-06 | Information disclosure | mitigate | CLOSED (residual recorded) | `PostcodeGeocoder.java:258-260` logs the **normalised outward key only**, never the raw term; the raw `q` reaches no log in the geocoder. WR-02 downgraded this line WARN→DEBUG (`ba3ca9c6`), which **strengthens** the mitigation rather than weakening it — the miss is anonymous, expected for every NI postcode, and fires once per keystroke. The vendor write path's WARN at `:144` is deliberately untouched. **Residual:** a pre-existing line logs the raw `q` — see [UF-33-02](#uf-33-02--warning--the-raw-q-reaches-a-debug-log-on-a-pre-existing-line) |
| T-33-08-07 | Information disclosure | accept | CLOSED | Logged at [§ AR-6](#ar-6--the-cors-exposed-headers-allowlist-widens-by-one-name) |
| T-33-08-08 | Elevation of privilege | mitigate | CLOSED | `git diff --name-only a5b05236^..HEAD -- core-java/src/main/resources/db/migration/` → **empty**. CONTROL: the same command over `CorsConfig.java` returns a stat line, so the emptiness is about the path and not the command. `scripts/check-no-create-extension.sh` **EXECUTED**: rc=0, "PASS: none of the 61 migration(s) create a PostgreSQL extension (1 exempted occurrence)" |
| T-33-08-SC | Tampering | accept | CLOSED | See [§ Supply chain](#supply-chain-t-33-08-sc--t-33-09-sc) |

### 33-09 — Customer-visible half: the rendering surface and the browser's view (6)

| ID | Category | Disposition | Result | Evidence |
|----|----------|-------------|--------|----------|
| T-33-09-01 | Spoofing | mitigate | CLOSED | `frontend/lib/search-interpretation.ts:110-150` — **every** failure path returns `TEXT_INTERPRETATION`: non-string (`:113`), a control character (`:117`, `CONTROL_CHARACTERS` at `:105`), a kind that is not `proximity` (`:126`), a key failing the server's own `^[A-Z0-9]{2,8}$` (`:136`), an unknown precision (`:139`), a missing/empty/non-finite/non-positive radius (`:144-147`). The UI can only **fail to claim** proximity, never invent one. In the island, `interpretation` is written from exactly three sources and no others: the SSR seed (`shop-discovery-client.tsx:226-227`), the parsed axios header (`:263-267`), and `TEXT_INTERPRETATION` on the catch branch (`:279`). **CA-E:** a postcode-shaped regex under `frontend/app/shop/` + `lib/search-interpretation.ts` → **1 match, in the pre-existing `[slug]/checkout/page.tsx:27`** and nowhere else; that same hit is the positive control proving the search can find one. **CA-D** is permanent and two-directional: `shop-discovery-client.test.tsx:118` (null on a `text` response) paired with `:128` (`getAllByText` finds exactly one on a `proximity` response) |
| T-33-09-02 | Denial of service | accept | CLOSED | Logged at [§ AR-7](#ar-7--scraping-the-public-directory-through-the-search-surface) |
| T-33-09-03 | Information disclosure | accept | CLOSED | Logged at [§ AR-8](#ar-8--x-search-interpretation-is-readable-by-any-cross-origin-script) |
| T-33-09-04 | Information disclosure | mitigate | CLOSED | Every real importer of `@/lib/storefront-server` enumerated: `app/sitemap.ts`, `app/page.tsx`, `app/shop/page.tsx`, `app/shop/[slug]/page.tsx` — each carries **0** `"use client"` directives. CONTROL: the same check over `app/shop/shop-discovery-client.tsx` → **1**, so the zeros are about those files and not about the pattern. (The other five matches for the module name are prose inside docblocks and one `jest.mock`.) The island imports the **type** and issues its own requests through the browser-facing `publicApiClient` (`shop-discovery-client.tsx:8`) |
| T-33-09-05 | Tampering | mitigate | CLOSED | `shop-discovery-client.tsx:444-453` renders `summary.lead`, `summary.term` and `summary.text` as React **text children** — escaped, never `dangerouslySetInnerHTML`. The two `dangerouslySetInnerHTML` occurrences under `app/shop/` are the pre-existing JSON-LD blocks, and the shop-list one is fed `shopListStructuredData(initial.content, origin)` (`app/shop/page.tsx:84,92`) — `q` (`:68`) is never an input to it. On the proximity branch `searchSummary` **discards `query` entirely** (`search-interpretation.ts:191-199`) and prints only the server's key; asserted at `search-interpretation.test.ts:198-201` by feeding `"se 22 <script>"` and requiring no `<script>` in the output |
| T-33-09-SC | Tampering | accept | CLOSED | See [§ Supply chain](#supply-chain-t-33-08-sc--t-33-09-sc) |

---

## Supply chain (`T-33-08-SC` / `T-33-09-SC`)

Both rows share one disposition (`accept`) and one claim: **these plans add no package.** Verified
once, for both, against the branch diff rather than against the plans:

```
git diff --stat a5b05236^..HEAD -- core-java/build.gradle.kts frontend/package.json \
                                   frontend/package-lock.json mcp-server/package.json edge-go/go.mod
  -> (no output) — no dependency manifest changed
```

CONTROL: the identical command over `core-java/src/main/java/uk/jtoye/core/config/CorsConfig.java`
returns `1 file changed, 87 insertions(+), 2 deletions(-)`, so the empty result is a fact about the
manifests and not about the command. No package-manager install task exists in either plan, so the
Package Legitimacy Gate does not apply.

**Result: both SC threats CLOSED.**

---

## Accepted risks

Numbering continues from `33-SECURITY.md` (`AR-1`…`AR-5`) so the two documents can be read together
without collision. Each entry is an `accept`-disposition row from the plan-time register, reproduced
here so the acceptance is **logged rather than implied**, and each was verified to be true of the
tree rather than merely asserted.

### AR-6 — the CORS exposed-headers allowlist widens by one name
**Threat:** `T-33-08-07` (Information disclosure) · **Accepted**

`application.yml:641` and the `@Value` fallback at `CorsConfig.java:55` both name
`X-Search-Interpretation` as the **seventh** entry; the six pre-existing names are all still present
in both. The declared risk was **displacement, not exposure**, and the two guards the register named
were verified **untouched**: the phase diff of `CorsExposedHeadersTest.java` contains **no removed
line inside `preExistingExposuresRetained` (`:118-124`) or `shippedDefaultNamesAllFourHeaders`
(`:157+`)**. The only removed lines in that file belong to `preFixAllowlistFailsTheAssertion` and
`allowlistIsConfigurable`, both of which WR-04 forced to be **re-expressed strictly more strongly**:
the #412 fail-direction arm now asserts the brokenness on the *configured input* (a permanent fact)
**and** proves the floor is what repairs it (`:126-155`); the configurability arm kept an **exact**
set assertion widened by exactly `MANDATORY_EXPOSED_HEADERS` rather than being loosened to
`contains` (`:259-275`, with the reasoning recorded in the test).

**Residual:** the added name discloses only the caller's own normalised query term, the precision and
the radius. Post-WR-04 the acceptance is materially stronger than the register assumed: an operator
override can now only **extend** the list, never remove a name whose absence makes a client assert
something untrue.

### AR-7 — scraping the public directory through the search surface
**Threat:** `T-33-09-02` (Denial of service) · **Accepted**

No new endpoint and no new bypass. `WebConfig.java:72` / `:86` still register `rateLimitInterceptor`
on `/**`, and **no rate-limiting source file appears in the phase diff** — the single match for
`ratelimit|rate-limit` across `git diff --name-only a5b05236^..HEAD` is
`frontend/__tests__/shop/rate-limit.test.tsx`, which only gained the new required prop. CONTROL: the
same command matches 5 `PublicStorefront*` files, so the near-absence is real. The four
`X-RateLimit-*`/`Retry-After` names are in `MANDATORY_EXPOSED_HEADERS` (`CorsConfig.java:80-85`), so
33-08's CORS edit provably added a name rather than displacing them, and the E2E arm at
`e2e/storefront-flows.spec.ts:301-319` reads `x-ratelimit-limit`/`-remaining`/`-reset` **in a real
browsing context** — the one instrument that can answer this question.

**Residual:** the endpoint remains scrapeable at the configured rate (100 req/min per tenant, burst
20) — the pre-existing posture of a public storefront directory, unchanged by this phase. Note the
D-A flip makes an ordinary `?q=` marginally *cheaper* for a scraper on non-postcode terms (zero extra
queries) and adds exactly one indexed lookup on postcode-shaped terms.

### AR-8 — `X-Search-Interpretation` is readable by any cross-origin script
**Threat:** `T-33-09-03` (Information disclosure) · **Accepted**

The value carries the caller's **own** query term, normalised (`SE22`, `SE155BS`), plus the precision
and the radius. It carries **no coordinate** — verified at the source (`SearchInterpretation.java:113-115`)
and asserted at `PublicStorefrontServiceTest:1229-1239`. The term is already in the request URI the
caller themselves sent, so exposing it to script adds no information the caller did not supply.

**Residual:** none material. The one thing the header does disclose that the URI does not is *how the
server read* the term — which is the entire point of the phase, and is a strictly honesty-increasing
disclosure.

---

## Unregistered flags

Neither `33-08-SUMMARY.md` nor `33-09-SUMMARY.md` carries a `## Threat Flags` section (verified;
CONTROL: `## Threat model outcomes` matches in the same file, so the grep can see headings there).
The executor-side flag channel is therefore **absent for both plans**, and per the standing warning
against treating that section as a complete inventory, independent sweeps were run instead over the
phase-touched files: header sinks, coordinate logging, raw-input logging, SQL construction, CORS
producers, `"use client"` chains, `dangerouslySetInnerHTML` sinks, and dependency manifests. Two
items surfaced.

### UF-33-01 — INFORMATIONAL — the geocoder now runs on every `?q=`

`33-09-SUMMARY.md:362-366` records this under "one note for the security record". It is **not
unregistered**: it maps cleanly to `T-33-08-01` and `T-33-08-02`, and both were re-verified under the
flip (see [§ D-A flip](#1-the-d-a-flip--3b038825-interpretation-first)). Recorded here so a later
pass does not re-file it.

### UF-33-02 — WARNING — the raw `q` reaches a DEBUG log on a pre-existing line
**Surface:** `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java:458`
**Introduced by:** `09046d7f` (2026-04-02, PR #19) — **not by this phase**
**Maps to:** `T-33-08-06`, whose mitigation reads "the district miss logs the **normalised key only**,
never the raw `q`"

```java
log.debug("Searching published shops: '{}'", query);
```

`T-33-08-06`'s declared mitigation is scoped to the geocoder's district miss, and **that mitigation is
present and verified**. But the threat as *stated* is "the customer's postcode reaching application
logs", and there is a second sink the register does not cover: the search entry point logs the raw
customer term — which, for the whole class of query this phase exists to serve, **is a postcode**.

**Why this is a WARNING and not a BLOCKER:**
- It is **pre-existing and unmodified** by these plans; `git log -L` places it at the file's creation
  in April 2026. This phase neither added it nor changed how often it runs.
- It is at **DEBUG**, and `uk.jtoye` is `INFO` by default (`application.yml:491`) and **hard-pinned
  to `INFO` in prod** (`application-prod.yml:84`, no env override). It cannot emit in production
  without a deliberate code change.
- The value is the customer's own search term, not a device coordinate. Coordinates reach no log on
  either path (verified above with a positive control).

**Residual, for the owner to accept or close:** in any environment deliberately raised to DEBUG, a
customer's postcode enters the application log. If that matters, the cheap close is to log the term's
length and shape rather than its value, or to route it through the same normalised-key rule the
geocoder already follows. Recorded, **not patched** — implementation files are read-only to this
audit.

---

## Observations

Non-threats, recorded so a later pass does not have to re-derive them.

**OB-1 — the WR-03 error contract has no request-level arm.** The 500-with-generic-detail mapping is
present and correct by reading (`GlobalExceptionHandler.java:102-110`), and the *type* contract is
asserted (`PublicStorefrontServiceTest:1079-1094`). What is not asserted anywhere is the **rendered
body**: no MockMvc arm performs a request under a misconfigured radius and checks that `$.detail` is
the generic string and that no config-key name appears in the response. On this project a claim about
a rendered response that is only ever verified by reading the handler is the shape that has gone
wrong before. Cheap close: one MockMvc arm asserting `status().isInternalServerError()`,
`$.type == "https://jtoye.uk/errors/misconfigured-platform-radius"`, and
`content().string(not(containsString("jtoye.geo")))`.

**OB-2 — the WR-03 fix is a genuine strengthening, not in the register.** Neither register row covers
"a misconfigured platform radius makes the page state the opposite of the truth". `e9151a01` closes it
at two layers — the constructor (`PublicStorefrontService.java:138`, a boot failure) and the query
input (`:364`) — and the equivalence between the caller-supplied and platform paths is asserted across
a twelve-value table in **both** directions (`PublicStorefrontServiceTest:1096-1131`), with a
non-vacuity control proving the arm rejects some values and accepts others (`:1133-1141`).

**OB-3 — the mitigations apply to the only entry point there is.** `searchPublishedShops` has exactly
one caller in `main/` (`PublicStorefrontController.java:155`) and `locateSearchTerm` exactly one
(`PublicStorefrontService.java:463`). There is no second, unguarded route into either.

---

## Audit trail

| Item | Value |
|------|-------|
| Tree audited | `feature/33-08-postcode-proximity-search` @ `bf10ffe7`, working tree clean |
| Plans read | `33-08-PLAN.md`, `33-09-PLAN.md` (2 `<threat_model>` blocks, 15 rows, read verbatim) |
| Summaries read | `33-08-SUMMARY.md`, `33-09-SUMMARY.md`; `## Threat Flags` present in **0 of 2** |
| Review evidence | `33-REVIEW-08-09.md` (0 critical, 5 warning, 4 info); fixes `e9151a01`…`2bcb10c5` verified in-tree, not accepted on the review's word |
| Post-plan changes in scope | `3b038825` (D-A interpretation-first) and the WR-01…WR-05 fix pass |
| Gates executed | `scripts/check-no-create-extension.sh` — **rc=0**, 61 migrations, 1 exempted occurrence |
| Positive controls run | coordinate-in-log pattern (scratch copy → 1); `LIKE` in `PostcodeCentroidRepository` (comment block → 5); migration/manifest diff (`CorsConfig.java` → 1 file); `"use client"` (`shop-discovery-client.tsx` → 1); postcode regex (`checkout/page.tsx` → 1); header sink (`RateLimitInterceptor` → 14); rate-limit diff (`PublicStorefront*` → 5); `## Threat model outcomes` heading → 1 |
| Implementation files modified | **none** — this audit is read-only |
| ASVS level | L1 |
| `block_on` | `high` — no high or blocking finding; nothing blocks |

_Audited: 2026-08-09 · Auditor: gsd-security-auditor · Round 1_
