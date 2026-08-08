---
quick_id: 260808-dke
description: Criteria-decay audit of Phase 28 and Phase 33 roadmap success criteria
date: 2026-08-08
status: complete
docs_only: true
---

# Quick Task 260808-dke — Summary

## What was done

Wrote `.planning/CRITERIA-DECAY-2026-08-08.md` and annotated `.planning/ROADMAP.md` in place at
Phase 28 and Phase 33, before Phase 33 was planned.

## What it found

**Two criteria could no longer fail**, and both would have been planned as work:

- **Phase 28 SC-4** — all five named infrastructure ports now bind `${JTOYE_BIND_HOST:-127.0.0.1}`.
  The three ports still published wide belong to applications (edge-go, frontend, mcp-server), which
  SC-4 does not govern.
- **Phase 33 SC-4 (#458)** — the nav-gating half shipped in `b9f80f81` (#508) and `96d8432f` (#591).
  #458 stays open by a deliberate scope split recorded in its own 2026-08-03 comment. Only the
  profile-tracking clause is unmet, and there is no `/profile` route directory at all.

**One criterion measures the wrong artifact:**

- **Phase 28 SC-3** — greps `OpenApiConfig.java:51` in source, but memory records that exact
  coordinate as stripped at build. A source gate is a false red over a clean built spec. Re-state it
  against the built OpenAPI document. Whether the strip happens is unverified either way.

**One criterion understates its problem:**

- **Phase 33 SC-1 (#460)** — the roadmap says coordinates are "stored and never read". Measured, the
  chain is five links and the missing one is load-bearing: `DemoDataSeeder.upsertShop` takes no
  coordinate parameters and the seeder never sets them, so **every seeded shop has NULL
  coordinates**. A locality feature is not falsifiable while that holds — before and after both
  return nothing. Populating coordinates is a prerequisite task, and shops carry real UK addresses,
  so geocoding is a viable source.

**Confirmed live, exactly as filed:** SC-2 (#544) and SC-5 (#432). SC-2 is sharper than filed — the
seeder creates three real shops the row never shows, one of them *Mama Ade's Kitchen*, of which the
page's fictional *Mama's Kitchen* is a near-duplicate. The surface shows invented data instead of
real data one query away.

**Recorded as not measured:** SC-6 (#546, #545, #285). Unknown, not clean.

## Evidence discipline

Every zero carries a non-vacuity control returning non-zero on the same corpus with the same pattern
machinery — the geo alternation returns 0 files but returns 5 when `openingHours` is added to it;
`geolocation|getCurrentPosition` returns 0 frontend files but 53 when `useState` is added.

Three instrument defects were hit and are recorded in the audit: `rg -E` is `--encoding` not
extended-regex; `-uu` sweeps `.next/` build output unless the path is scoped; and a first-hit file
lookup resolved the customer realm to `docs/api/openapi-snapshot.json`, giving the right answer from
the wrong file — caught only because that file's own `realm` field read `None`.

## Not done

None of the criteria were fixed. This task records what is true; Phase 33 planning consumes it.
