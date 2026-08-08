# Criteria decay — Phases 28 and 33, measured 2026-08-08

**What this document is.** `ROADMAP.md` was written 2026-08-01. `ISSUE-DISPOSITION.md` swept the
board 2026-08-07. Neither re-measured the success criteria against the tree, and work landed in
between. This is that measurement, taken before Phase 33 was planned.

**Why it exists.** This repo's Proof Standard #1 says a criterion observed only passing is not
evidence. The roadmap states the same rule about itself, in its own words:

> *Every success criterion below must be capable of FAILING on the tree as it stands on
> 2026-08-01. Where a criterion is already satisfied it is not a criterion.*

Two criteria are no longer capable of failing. Planning either phase from the roadmap as written
would have produced exactly the vacuous-criterion defect Phase 26 found ~22 times.

**Every measurement below carries a non-vacuity control**, because a zero from a search is a
statement about the pattern until something proves the pattern can match.

---

## Summary

| Phase | SC | Issue | Verdict |
|---|---|---|---|
| 28 | SC-4 | — | **DECAYED — already satisfied. Vacuous as written.** |
| 28 | SC-3 | #549 | **MIS-SCOPED** — measures source; the string is stripped at build |
| 28 | SC-1, SC-2 | #548, #551, #552 | not measured here |
| 33 | SC-1 | #460 | **LIVE, and understated — see the five-link chain** |
| 33 | SC-2 | #544 | **LIVE, exactly as filed** |
| 33 | SC-3 | #453 | blocked on a product decision, unchanged |
| 33 | SC-4 | #458 | **DECAYED — nav-gating half shipped. Vacuous as written.** |
| 33 | SC-5 | #432 | **LIVE, exactly as filed** |
| 33 | SC-6 | #546, #545, #285 | **NOT MEASURED — unknown, not clean** |

---

## Phase 28 SC-4 — already satisfied

The criterion names five infrastructure ports as publishing with no bind address. All five are now
loopback-bound:

```
5433   73:      - "${JTOYE_BIND_HOST:-127.0.0.1}:5433:5432"
8025  639:      - "${JTOYE_BIND_HOST:-127.0.0.1}:8025:8025"   # Web UI
9000  513:      - "${JTOYE_BIND_HOST:-127.0.0.1}:9000:9000"   # S3 API
9001  514:      - "${JTOYE_BIND_HOST:-127.0.0.1}:9001:9001"   # Console UI
15672 216:      - "${JTOYE_BIND_HOST:-127.0.0.1}:15672:15672" # Management UI
```

Three published ports remain with no bind address, and all three are **applications**, not the
infrastructure SC-4 governs:

```
line 381  service=edge-go      "8089:8080"
line 446  service=frontend     "3000:3000"
line 482  service=mcp-server   "9100:9100"
```

**Disposition.** SC-4 is satisfied on its own terms. Do not re-plan it. If the intent was broader
than the five named services, say so explicitly and give the new form a control — but note that
binding the three application ports to loopback would change local E2E reachability, so that is a
decision, not a tidy-up.

## Phase 28 SC-3 — measures the wrong artifact

The criterion asks that `OpenApiConfig` "no longer advertises the `X-Tenant-Id` fallback". The
string is present in source:

```
core-java/src/main/java/uk/jtoye/core/config/OpenApiConfig.java:51:
    - Dev fallback: Use `X-Tenant-Id` header when JWT lacks tenant claim
```

But this project's memory records that this exact line is **stripped at build time**, which is why
pentest finding A2 was marked do-not-re-file from this same coordinate. A source-level grep
therefore fails on a tree whose *built* spec is clean — a false red — and the criterion as written
cannot distinguish the two.

**Disposition.** Re-state SC-3 against the **built** OpenAPI document, not the source file, per
Proof Standard #2 (verify the delivered artifact, not the source). Read the spec out of the running
service or the packaged jar. This is unverified either way until that measurement is taken — the
strip is recorded in memory, not confirmed here.

---

## Phase 33 SC-1 (#460) — live, and the roadmap understates it

The roadmap says coordinates are "stored and never read". Measured, the chain is five links, and
the roadmap names only the last three.

**Link 1 — the column exists.** `V16__public_storefront.sql`:

```
15:ALTER TABLE shops ADD COLUMN IF NOT EXISTS latitude DOUBLE PRECISION;
16:ALTER TABLE shops ADD COLUMN IF NOT EXISTS longitude DOUBLE PRECISION;
```

**Link 2 — the entity is ready.** `Shop.java:53-55` declares both fields; `:113-116` exposes full
getters and setters. Nothing is missing at the model layer.

**Link 3 — nothing populates it.** `DemoDataSeeder.upsertShop` (`:508-510`) takes no coordinate
parameters, and no `setLatitude`/`setLongitude` call exists anywhere in the seeder. **Every seeded
shop has NULL coordinates.** The roadmap does not mention this, and it is the load-bearing one: a
distance feature over NULL coordinates ranks nothing.

Shops *do* carry real UK addresses, so geocoding is a viable source:

```
"Mama Ade's Kitchen"     48 Rye Lane, Peckham, London SE15 5BS
"Peckham Jollof Co."     12 Bellenden Road, Peckham, London SE15 4QA
"Brixton Village Grill"  (seeded at DemoDataSeeder.java:259)
```

**Link 4 — one read site, a pass-through.** The only Java code touching either field outside the
entity and DTOs:

```
PublicStorefrontService.java:720:  dto.setLatitude(shop.getLatitude());
PublicStorefrontService.java:721:  dto.setLongitude(shop.getLongitude());
```

Coordinates are shipped to the client and never queried, sorted, or filtered on.

**Link 5 — no distance logic, and no device location.** Both zeros carry controls:

| measurement | result | control | control result |
|---|---|---|---|
| geo alternation over `core-java/src/main/java/` and `.../resources/` | rc=1 (zero matches) | same alternation **plus** `openingHours` | 5 files |
| `geolocation\|getCurrentPosition` over `frontend/{app,components,lib,hooks}` | 0 files | same pattern **plus** `useState` | 53 files |

The alternation was `haversine|ST_Distance|ST_DWithin|earth_distance|radiusKm|deliveryRadius|distanceKm`.
Each control returns non-zero on the same corpus with the same pattern machinery, so the zeros are
about the codebase, not the search.

**Disposition.** SC-1 is live. **Add the population link to the criterion** — a locality feature is
not falsifiable while every coordinate is NULL, because the "before" and "after" both return
nothing. Populating coordinates is a prerequisite task, not part of the ranking work.

## Phase 33 SC-2 (#544) — live, exactly as filed

`frontend/app/page.tsx:51` defines `featuredDishes`; `:192` maps it under the heading "Cooking near
you right now" (`:180`). Five entries, `:52-56`, each with an invented vendor, rating and price:

```
Mama's Kitchen · Spice Route · Olive & Vine · Crumb & Co · Hanoi House
```

None appears anywhere in `core-java/src/main/resources/`. Meanwhile the seeder creates three real
shops (above) that the row never shows — including **"Mama Ade's Kitchen"**, of which the page's
fictional **"Mama's Kitchen"** is a near-duplicate.

That is the sharpest available statement of the defect: the surface does not lack data to show, it
shows invented data *instead of* the real data sitting one query away.

**Disposition.** SC-1 and SC-2 share a substrate and SC-2 depends on it. Sequence
**#460 population → #544**. Keep the roadmap's requirement that the fix be shown to fail against a
reintroduced hardcoded list.

## Phase 33 SC-4 (#458) — decayed

The nav-gating half has shipped, in two merged PRs:

```
96d8432f  fix(storefront): take the operator door off the customer's own pages (#458 items 1a, 2, 4) (#591)
b9f80f81  fix(storefront): ... gate the signed-in nav ... (#467, #463, #458, #459) (#508)
```

The gating is implemented in `public-header.tsx` and `public-footer.tsx`, whose docblock states the
rule directly, and jest specs assert both halves. Issue #458 is **still OPEN by deliberate scope
split** — its own comment of 2026-08-03 says *"the frontend half is done, this issue stays OPEN for
the dispatch half"*.

The roadmap's SC-4 describes only the shipped half. Its second clause — *"tracking moves into the
profile and auto-populates"* — is **not** satisfied: there is no `/profile` route directory at all.

**Disposition.** Rewrite SC-4 to the open dispatch half plus the profile-tracking clause. Planning
it as written would produce a criterion that passes before any work is done.

## Phase 33 SC-5 (#432) — live, exactly as filed

```
infra/keycloak/realm-export-customers.json   realm=jtoye-customers  identityProviders=0
infra/keycloak/realm-export.json             realm=jtoye-dev        identityProviders=0
```

**A correction worth recording.** The first run of this check resolved its realm file by searching
for the string `jtoye-customers` and taking the first hit, which was
`docs/api/openapi-snapshot.json` — not a realm export at all. It reported `identityProviders: 0`,
the right answer from the wrong file. Its own `realm` field read `None`, which is what exposed it.

This is the *Suspect the Instrument First* pattern: a confirmation that arrives too easily deserves
the same scrutiny as a surprise. A check that cannot name what it just measured has not measured it.

## Phase 33 SC-6 — not measured

#546 (look and feel, web and mobile), #545 (Keycloak stock theme on both realms) and #285
(bulk-revoke of JIT `shop_staff` rows) were **not** measured. Recorded as unknown rather than
carried forward as live, because an unmeasured criterion asserted as live is the same defect in the
opposite direction.

Per the roadmap's own UI hint on this phase: a screenshot cannot verify the motion half, and a
screenshot taken without scrolling reads scroll-reveal content as empty bands.

---

## Instrument defects hit while taking these measurements

Recorded because the ratio matters — three tooling faults, zero of them in the product:

1. **`rg -E` is `--encoding`, not extended-regex.** `rg -uu -niE 'a|b'` fails with
   `unknown encoding: a|b`. It errored loudly, which is the good case; the flag is simply
   unnecessary since rg is regex by default.
2. **`-uu` sweeps build output.** `rg -uu 'featuredDishes' frontend/` returned 110KB, most of it
   `.next/` source maps. `-uu` is correct when absence is the claim, but it needs a scoped path.
3. **A first-hit file lookup found the wrong file** — see SC-5 above.

A fourth, from the same session: `gh pr merge ... | tail -5` followed by `echo "rc=$?"` reports the
**tail's** status, not the merge's. The merge was verified by reading `state=MERGED` back from the
API instead. This is the documented exit-code-after-an-intervening-command trap, and it still fires.

---

## What to do with this

1. Apply the three dispositions to `ROADMAP.md` in place (done alongside this document) so a future
   planner reading only the roadmap cannot plan a vacuous criterion.
2. Plan Phase 33 with SC-1 extended by the population link, SC-2 sequenced behind it, SC-4
   rewritten, and SC-6 measured first.
3. **Re-measure before quoting any figure here.** This document has a date in its title for the
   same reason the ones it corrects should have had.
