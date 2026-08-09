---
quick_id: 260809-ivz
slug: close-uf-33-01-coarsen-the-client-coordi
status: complete
completed: 2026-08-09
commit: a1e8ef64
---

# Quick 260809-ivz: Close UF-33-01 — coarsen the rejected-coordinate WARN

**One-liner:** The `client_coordinate_rejected` WARN in `ShopService` now logs integer-degree
values (~111 km), never the raw vendor-supplied pair — closing the last open security residual
from phase 33's audit (33-SECURITY.md UF-33-01).

## What changed

- `core-java/src/main/java/uk/jtoye/core/shop/ShopService.java` — the WR-03 rejected branch
  logs `Math.round(latitude)/Math.round(longitude)` with a message noting the coarsening and
  citing UF-33-01. The ACCEPTED branch is untouched: that value is published on the public
  ranking surface anyway, so full precision there discloses nothing extra.
- `core-java/src/test/java/uk/jtoye/core/shop/ShopServiceGeocodeTest.java` — new arm
  "UF-33-01: a rejected client fallback is WARN-logged WITHOUT the raw coordinate pair"
  (ListAppender pattern, same as the Belfast accepted-arm): asserts the WARN carries the event,
  does NOT contain `40.7128` / `-74.006`, and DOES contain the coarse `41` / `-74` (positive
  control — the operator still sees roughly where the rejected pair pointed).

## Proof

- **Fail direction first:** the arm run against the unfixed tree — rc=1, the UF-33-01 arm the
  sole failure (raw pair present in the formatted message).
- **Clean direction:** full `ShopServiceGeocodeTest` green post-fix; freshness proven by result
  files, not the build banner — `TEST-...$WritePathGeocoding.xml` timestamped at run time with
  `tests="15" failures="0"` and the UF-33-01 testcase present by @DisplayName. (First read was
  an instrument error: the XML records display names, not method names.)

## Why the rejected branch only

On the accepted branch the pair becomes the shop's public position. On the rejected branch it
never does — and a vendor mis-entering their own home position would have left a residential
fix at full precision in operator logs. Integer degrees distinguishes "New York" from "a typo
near Calais" without fixing an address.
