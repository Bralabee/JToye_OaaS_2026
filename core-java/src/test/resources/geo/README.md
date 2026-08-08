# Test geo fixtures

`postcode-centroids-fixture.csv` is the offline stand-in for
`core-java/src/main/resources/geo/postcode-centroids.csv.gz` (1,748,230 rows). It carries the same
shape — **no header**, `POSTCODE,latitude,longitude`, postcode space-stripped and uppercased — so a
parser that works against one works against the other.

Every row is a **real** Code-Point Open centroid, copied verbatim from the committed dataset. They
are not invented: a fabricated coordinate would make the accuracy assertion in
`PostcodeGeocoderTest` meaningless, because it would only ever be comparing the fixture to itself.

| Postcode | Why it is here |
|---|---|
| `SE15 5BS` | The Peckham demo shop. Also the accuracy anchor — checked against an independent ONSPD-derived reference, not against this file |
| `SE15 5DQ` | Near SE15 5BS, so `33-06` can assert an *ordering* rather than a single hit |
| `SE15 4BW` | The real postcode nearest to Bellenden Road (51.4665, −0.0730) — the replacement `33-05` needs for the seeded address that currently carries the non-existent `SE15 4QA` |
| `SW9 8PS` | The Brixton demo shop |
| `CF10 1EP`, `M1 1AE`, `EH1 1YZ` | Cardiff, Manchester, Edinburgh — spread across GB so a radius filter has something to *exclude*, not just something to find |

## What is deliberately ABSENT, and must stay absent

**`SE15 4QA` is not in this file and must never be added.** It is the phase's free, permanent
negative control: it appears in the seeded demo data, it satisfies every plausible UK-postcode
regex, and **it is not a real postcode**. Independently confirmed 2026-08-08 — `api.postcodes.io`
returns **404** for it, while returning 200 for `SE15 5BS` and `SW9 8PS`.

That is the whole reason `PostcodeGeocoder` treats the *table* as the authority instead of a regex.
Adding `SE154QA` here would turn the unknown-postcode test green by making the product wrong, which
is why `PostcodeGeocoderTest` asserts its absence directly rather than relying on convention.
