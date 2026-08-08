# Phase 33 — Control Arms

**Captured 2026-08-08** on branch `phase/33-the-consumer-product` at `a21aee67`.

This file exists because `.planning/CRITERIA-DECAY-2026-08-08.md` found roadmap criteria that could
no longer fail. Every arm below is a **pre-state**, recorded before the phase changes it, so that a
downstream criterion has something to be false against. **CA-1 expires the moment 33-05 runs** — once
coordinates are populated there is no way to reconstruct what "nothing before" looked like.

Each arm is written in a fixed shape:

```
  measure:        the exact command
  result:         verbatim output, including rc
  control:        a DIFFERENT command over the SAME corpus with the SAME machinery
  control-result: verbatim output; MUST be non-zero/non-empty
  falsifies:      which downstream plan + criterion this arm carries
```

*(The legend above is indented deliberately. Un-indented it would contribute a seventh line-start
occurrence of every key and make the file's own structural check incapable of failing — see
**Structural self-check** at the end of this file, where that was measured rather than assumed.)*

The `control:` line is what makes an arm evidence rather than assertion. **A zero from a search is a
statement about the pattern until something proves the pattern can match.** Two instrument defects
were caught by exactly that discipline while this file was being written; both are recorded in place
rather than quietly corrected (CA-5, and the `jq` note beside it).

Environment: the live dev Compose stack, up 25h. Superuser role name read from the container
(`docker exec jtoye-postgres printenv POSTGRES_USER` → `jtoye`; there is **no** role named
`postgres`). Commands below use the env-var form `-U "$SUP"` and record role **names** only — no
pasted command line carries a literal password value.

---

## A1 — licence confirmation

**Confirmed by the owner 2026-08-08**, at the blocking gate in `33-00` Task 2, *before* the Q-1
dataset-cost decision — because Q-1 asks how to ship a dataset that A1 had not yet established we
may ship at all.

What was under test: `RESEARCH.md` read the three attribution lines verbatim out of the shipped
archive's `Doc/licence.txt` — that is [VERIFIED]. The licence **identity** was not, because the
canonical URL printed inside that same `licence.txt`,
`www.ordnancesurvey.co.uk/opendata/licence`, **404s**. "OGL v3 / commercial / no share-alike" rested
on the OpenStreetMap wiki and secondary pages. Assumption A1 rated this low-probability,
high-impact: if wrong, the whole D-1 substrate is unusable.

**Live URL read: <https://www.ordnancesurvey.co.uk/products/open-data>** (OS's own OpenData
portfolio page, which Code-Point Open belongs to), and the licence it links to,
**<https://www.nationalarchives.gov.uk/doc/open-government-licence/version/3/>**.

| Limb | Source | Quoted sentence |
|---|---|---|
| (a) **It is OGL v3** | OS, `/products/open-data` | *"available for unrestricted, free commercial and personal reuse under the Open Government Licence (OGL)"* — the page links directly to `nationalarchives.gov.uk/doc/open-government-licence/version/3/` |
| (b) **Commercial use permitted, including inside a proprietary product** | OGL v3, National Archives | *"exploit the Information commercially and non-commercially for example, by combining it with other Information, or by including it in your own product or application"* |
| (c) **No share-alike / copyleft clause** | OGL v3, National Archives | The whole of the "You must, where you do any of the above" section reads: *"acknowledge the source of the Information in your product or application by including or linking to any attribution statement specified by the Information Provider(s) and, where possible, provide a link to this licence"*. Attribution is the **only** obligation. There is no clause requiring derived works to carry the same licence — the material contrast with the ODbL concern recorded in ADR-0004. |

Attribution form, confirmed against OS's own
[Copyright acknowledgments](https://www.ordnancesurvey.co.uk/customers/public-sector/public-sector-licensing/copyright-acknowledgments)
page and consistent with the three lines `RESEARCH.md` read out of the archive:

- `Contains OS data © Crown copyright [and database right] [year]`
- `Contains Royal Mail data © Royal Mail copyright and database right [year]`
- `Contains National Statistics data © Crown copyright and database right [year]`

**Two caveats recorded rather than smoothed over**, because a later reader will otherwise find them
and doubt the whole gate:

1. The `/products/code-point-open` product page itself names **no** licence — it says only "Free to
   use for everyone". The OGL v3 identity comes from the OpenData **portfolio** page above, not from
   the product page.
2. `ckan.publishing.service.gov.uk`'s entry for this dataset carries **"No Licence Provided"** in its
   licence field, with OGL v3 mentioned only in the free-text description. That is a government
   catalogue being sloppy, not a contradiction of OS's own page — but it exists, and it is the first
   thing a sceptical reader will hit.

**Verdict: OGL v3, commercial-permitted, no share-alike. Q-1 is live, not moot.**

---

## Control arms

### CA-1 — Every shop coordinate is NULL (#460). THE ARM THAT EXPIRES.

measure: `docker exec -i jtoye-postgres psql -U "$SUP" -d "$DB" -tA -c "SET ROLE jtoye_app; SELECT count(*), count(latitude), count(*) FILTER (WHERE published) FROM shops;"` and the same count query again **as the superuser**, plus the per-shop listing.

result:
```
ARM A — as jtoye_app, NO tenant GUC (role downgrade; RLS applies)
  current_user|is_super|bypassrls|guc_unset
  jtoye_app|f|f|t
  total|with_latitude|published
  3|0|3                                              rc=0    <- UNDERCOUNTS: 2 rows invisible

ARM B — as superuser jtoye, the TRUE state
  5|0|3                                              rc=0

per-shop truth (superuser)  slug|published|lat_is_null|lon_is_null|address
  brixton-village-grill|t|t|t|Unit 74, Brixton Village Market, London SW9 8PS
  mama-ades-kitchen|t|t|t|48 Rye Lane, Peckham, London SE15 5BS
  peckham-jollof-co|t|t|t|12 Bellenden Road, Peckham, London SE15 4QA
  tenant-b-probe|f|t|t|1 Probe Lane, London
  unsorted-legacy-items|f|t|t|—
                                                     rc=0
```

**Why the two roles disagree, and why recording only the first would be a false green.** `shops` is
ENABLE+FORCE RLS and `shops_public_read` reads `((published = true) OR (tenant_id =
current_tenant_id()))`. With no tenant GUC set, that reduces to `published = true`, so the app role
cannot see the two unpublished rows at all. Reporting the app-role figure alone records **3** where
the truth is **5** — the recorded "RLS blinds the verification query" trap — and would make 33-05's
backfill look complete two rows early. Both roles are therefore mandatory here and in
`scripts/check-live-shop-coordinates.sh`.

control: the **same** superuser query over the **same** table counting a POPULATED column alongside the NULL one — `SELECT count(*), count(address), count(latitude) FROM shops;`

control-result:
```
  total|with_address|with_latitude
  5|5|0                                              rc=0
```
`count(address)` = 5 is non-zero, so the counting machinery works and the `0` is about **latitude
specifically**, not about the query, the connection, or an empty table.

falsifies: **33-05** — "Every seeded shop with a real GB postcode has non-NULL coordinates in the LIVE dev database after a rebuild", and the `scripts/check-live-shop-coordinates.sh` gate that re-runs this exact pair of queries post-population. Also **33-06**'s distance ordering, which over an all-NULL column returns nothing before and nothing after, i.e. cannot be shown to fail without this capture.

**Three free permanent negative controls** live in the five rows above and must survive the phase:
`unsorted-legacy-items` and `tenant-b-probe` have no extractable postcode; `peckham-jollof-co` has an
extractable postcode, `SE15 4QA`, that is **not in Code-Point Open** (absent from the dataset, 404
from ONSPD). It is retained deliberately — a shop whose postcode does not geocode must keep its
storefront and keep NULL coordinates, never land at (0,0), and this row is the only thing that can
prove it.

### CA-2 — Geolocation is denied at the HTTP header, on every route. BLOCKER.

measure: read `frontend/next.config.mjs:35` and its enclosing `source:` scope, then read the header back off the running app with `curl -sSI`.

result:
```
frontend/next.config.mjs
  30:        source: '/:path*',
  35:          { key: 'Permissions-Policy', value: 'camera=(), microphone=(), geolocation=(), browsing-topics=()' },

LIVE, curl -sSI http://localhost:3000/          rc=0
  HTTP/1.1 200 OK
  Permissions-Policy: camera=(), microphone=(), geolocation=(), browsing-topics=()

LIVE, curl -sSI http://localhost:3000/shops     rc=0
  HTTP/1.1 404 Not Found
  Permissions-Policy: camera=(), microphone=(), geolocation=(), browsing-topics=()
```

control: `printf 'geolocation=(self)\n' | grep -c 'geolocation=(self)'` — the same pattern over a line that *does* contain it.

control-result:
```
  synthetic line     -> 1        <- the pattern can match
  next.config.mjs    -> 0        <- so the 0 is about the tree
```

falsifies: **33-03** — "navigator.geolocation is permitted by the Permissions-Policy header on the landing route", whose artifact entry requires `geolocation=(self)` in `next.config.mjs`.

**Flagged as a BLOCKER for the located path, not an observation.** `geolocation=()` is an **empty**
allowlist: it denies the API to the document's own origin, on every route including a 404, *before*
any permission prompt is shown, with no console error worth reading. Phase 33's entire located
journey is dead on arrival until 33-03 changes this — and it presents to a user, and to a tester,
**identically to a user denial**. This overturns `RESEARCH.md` assumption A6 ("not measured, low
risk").

### CA-3 — The landing row renders invented vendors that exist nowhere in the product (#544).

measure: read `frontend/app/page.tsx:51` (the `featuredDishes` declaration), `:52-56` (five entries), `:191-192` (the label and the map), then `rg -uu -F -l "<vendor>" core-java/src/main/resources/` for each of the five names.

result:
```
frontend/app/page.tsx
  51:const featuredDishes = [
  52:  { ... name: "Jollof & Grilled Chicken", vendor: "Mama's Kitchen", rating: "4.8", price: "£9.50", q: "jollof" },
  53:  { ... name: "Lamb Biryani",             vendor: "Spice Route",   rating: "4.9", price: "£11.00", q: "biryani" },
  54:  { ... name: "Halloumi Wrap",            vendor: "Olive & Vine",  rating: "4.7", price: "£7.25", q: "wrap" },
  55:  { ... name: "Basque Cheesecake",        vendor: "Crumb & Co",    rating: "4.9", price: "£5.00", q: "dessert" },
  56:  { ... name: "Pho Bo",                   vendor: "Hanoi House",   rating: "4.8", price: "£10.50", q: "pho" },
  57:]
 191:              <DishScroller label="Dishes cooking near you">
 192:                {featuredDishes.map((d) => (

rg -uu -F -l over core-java/src/main/resources/       rg -uu -F -l over ALL of core-java/src/main/
  Mama's Kitchen     -> 0 files                         Mama's Kitchen     -> 0
  Spice Route        -> 0 files                         Spice Route        -> 0
  Olive & Vine       -> 0 files                         Olive & Vine       -> 0
  Crumb & Co         -> 0 files                         Crumb & Co         -> 0
  Hanoi House        -> 0 files                         Hanoi House        -> 0
```
Not one of the five vendors the landing page presents to a customer exists anywhere in the backend,
even over the widened corpus. Ratings (`4.7`–`4.9`) and prices are invented alongside the names.

control: the same `rg -uu -F -l` machinery, same corpus, for a **real** seeded vendor — `Mama Ade`.

control-result:
```
  core-java/src/main/java/uk/jtoye/core/dev/DemoImageManifest.java
  core-java/src/main/java/uk/jtoye/core/dev/DemoDataSeeder.java
  core-java/src/main/resources/dev/demo-images/manifest.json
  -> 3 files over core-java/src/main/ ; 1 file when restricted to resources/
```
Non-zero in both scopes, so the five zeros above are about the tree, not about `-F`, the flags, or the
path. `-uu` is required and the path is **scoped**: dot-directories are otherwise untraversed, while a
bare `-uu` over `frontend/` sweeps `.next/` and returns ~110 KB of build output.

falsifies: **33-03** — "The landing kitchen row names real published shops, sourced from the database", and its `loadShopList` artifact requirement on `page.tsx`.

### CA-4 — The "near you" claim is unconditional, and is NOT the only one on the page.

measure: `grep -niE 'near you' frontend/app/page.tsx`, then read each hit and separate rendered strings from comments.

result:
```
rc=0, 7 occurrences in page.tsx — of which FOUR are rendered:

   25:  { icon: Search, title: "Browse", body: "Find independent kitchens near you and explore their menus." }
         ...rendered at :234
  133:                    <span className="mt-4 text-lg font-bold">Order food near you</span>
         ...the primary customer CTA
  180:              <h2 className="text-2xl font-bold text-oxblood">Cooking near you right now</h2>
         ...THIS is the one that lies about a coordinate
  191:              <DishScroller label="Dishes cooking near you">
         ...becomes the aria-label

  the remaining three (48, 71, 176) are source comments, not rendered

existing assertions standing on these strings:
  frontend/components/marketing/__tests__/hero-scene.test.tsx:30  getByText("Order food near you")
  frontend/app/__tests__/landing.test.tsx:30                      name: /order food near you/i
  frontend/e2e/marketing-dish-scroller.spec.ts:19                 '[aria-label="Dishes cooking near you"]'
```

control: the case-insensitive count over the same file must be ≥ 4 rendered sites, proving the inventory is real rather than a single string.

control-result:
```
  grep -ciE 'near you' frontend/app/page.tsx  ->  7      (4 rendered + 3 comments)
```

falsifies: **33-03** and **33-07** — "No HEADING claims proximity while no coordinate is held". **The inventory is what makes the blanket form of that criterion unsatisfiable**: a DOM-wide "no `near you` anywhere" assertion could never pass, because `:133` is the primary CTA and `:191` is a spec selector, and both are legitimate. The criterion must be scoped to the row **heading** at `:180`, and 33-07 must not break the three assertions listed above.

### CA-5 — The customer realm has no identity providers (#432).

measure: `grep -cE '^  "identityProviders" ?:' infra/keycloak/realm-export-customers.template.json` — distinguishing **ABSENT** from **present-and-empty**, which are different states with different Keycloak import behaviour.

result:
```
  literal 'identityProviders' anywhere in the file   -> 0
  top-level key  identityProviders                   -> 0     == KEY IS ABSENT
```
Absent, not present-and-empty. A present-but-empty `"identityProviders" : [ ]` would render as 1.

control: the same pattern shape over the **same named file** for sibling keys that *are* present, plus the file's own `realm` field in the same output block.

control-result:
```
  top-level key realm     -> 1        2:  "realm" : "jtoye-customers",
  top-level key clients   -> 1
  top-level key roles     -> 1
  top-level key users     -> 1
  top-level key groups    -> 0        (genuinely absent too)

  fail-direction fixture (scratch copy with "identityProviders" : [ ] injected at line 3):
  top-level key identityProviders -> 1
  live file unchanged — `git status --short` on it is empty
```

falsifies: **33-04** — SC-5's second limb, "the `jtoye-customers` realm's `identityProviders` state is settled by a dated recorded decision rather than left at zero by omission".

**Two instrument defects caught here, both recorded rather than quietly fixed:**

1. **My first pattern returned 0 for keys that are present.** I wrote `^  "key":` — this file writes
   `"key" : value`, with a space before the colon. `realm`, `clients`, `roles` and `users` all scored
   0, identical to `identityProviders`. Had I not put present keys in the same block, a wrong
   instrument would have produced the right answer and been trusted. *This is the whole reason the
   `control:` line exists.*
2. **`jq` cannot parse this file at all** — `parse error: Invalid numeric literal at line 15`, because
   line 15 is `"verifyEmail" : ${CUSTOMER_VERIFY_EMAIL},`, an unquoted `envsubst` placeholder in a
   numeric/boolean position. So the measurement here is necessarily textual, and any downstream check
   that assumes this template is valid JSON before rendering will VOID rather than fail.

The decay audit records a third defect of the same family at this exact file: a first-hit lookup for
`jtoye-customers` resolved to `docs/api/openapi-snapshot.json`, which is not a realm export, and
returned the right answer from the wrong file. Every block above names the file it measured.

### CA-6 — There is no distance logic and no device geolocation.

measure: the two alternation searches recorded in `.planning/CRITERIA-DECAY-2026-08-08.md` Link 5, re-run rather than copied. `rg -uu -l 'haversine|ST_Distance|ST_DWithin|earth_distance|radiusKm|deliveryRadius|distanceKm'` over `core-java/src/main/java/` + `core-java/src/main/resources/`; `rg -uu -l 'geolocation|getCurrentPosition'` over `frontend/{app,components,lib,hooks}`.

result:
```
  backend  geo alternation                  rc=1   files=0
  frontend geolocation|getCurrentPosition   rc=1   files=0
```

control: the identical command with one term appended that is known to be common in that corpus — `|openingHours` on the backend, `|useState` on the frontend.

control-result:
```
  backend  alternation + openingHours       rc=0   files=5
      core-java/src/main/java/uk/jtoye/core/storefront/dto/PublicShopDto.java
      core-java/src/main/java/uk/jtoye/core/product/Product.java
      core-java/src/main/java/uk/jtoye/core/shop/Shop.java
      core-java/src/main/java/uk/jtoye/core/shop/dto/ShopDto.java
      core-java/src/main/java/uk/jtoye/core/shop/dto/CreateShopRequest.java

  frontend geolocation|getCurrentPosition|useState   rc=0   files=53
```
All four figures reproduce the decay audit's recorded numbers exactly. Note `rg -E` is `--encoding`,
**not** extended-regex, and was dropped: `rg` is regex by default and `-E` makes the command fail.

falsifies: **33-06** — "An anonymous caller can ask for published shops near a coordinate and receive them ordered by real distance" — and **33-07** — "A customer who grants location sees real published shops ordered by real distance". Both are trivially false today, in the strong sense: the capability does not exist in either tier, so a green result after the phase cannot be a pre-existing one.

---

## Arm → downstream criterion map

| Arm | Pre-state recorded | Carried by | The criterion it lets fail |
|---|---|---|---|
| CA-1 | 5 shops, 0 with latitude; app role sees only 3 | 33-05 | seeded shops have non-NULL coordinates in the LIVE db; `check-live-shop-coordinates.sh` |
| CA-2 | `geolocation=()` on every route, live | 33-03 | `geolocation=(self)` permits the API on the landing route |
| CA-3 | 5 invented vendors, 0 backend hits | 33-03 | the landing row names real published shops from the database |
| CA-4 | 4 rendered "near you" sites, 3 of them legitimate | 33-03, 33-07 | no **heading** claims proximity without a coordinate — scoped, not blanket |
| CA-5 | `identityProviders` key ABSENT | 33-04 | the realm's IdP state is settled by a dated decision |
| CA-6 | 0 distance logic, 0 device geolocation | 33-06, 33-07 | distance ordering exists and is observable |

## Secret-scan disposition

No local `gitleaks` run is claimed. `command -v gitleaks` returns **rc=1** on this machine — the
binary is not installed, and the only gitleaks in this repo is the `gitleaks/gitleaks-action@v2` job
in `.github/workflows/gitleaks.yml`, pinned to `8.27.2`. **CI is the authoritative enforcement
point**, and it runs on the PR carrying this file, which is the commit where the file first exists.

A skipped scan reported as clean is exactly the vacuous shape this file exists to prevent, so it is
reported as skipped. What *is* asserted, and was checked by reading: no command line pasted above
carries a literal password value. Role names (`jtoye`, `jtoye_app`), counts, ports and header values
only; the superuser is referenced as `-U "$SUP"`.

## Structural self-check

The plan's own verify limb for this file was **run in the fail direction and found vacuous**, so it
was replaced. Both forms are recorded here, because a silent substitution is the thing this file
exists to stop.

**Original limb (33-00 Task 3):** for each key, `grep -cE "^key:" <file>` must be `>= 6`.

**Why it could not fail.** This document's legend block listed all five keys at column 0, so every
key measured **7**, not 6. Deleting CA-4's `control:` line — the exact break the plan specifies —
left **6**, which still satisfies `>= 6`. Measured:

```
  break applied (CA-4 control: line deleted)
    measure: 7 PASS   result: 7 PASS   control: 6 PASS   control-result: 7 PASS   falsifies: 7 PASS
    verdict: the check still passes on a file with a control arm gutted
```

A whole-file `>=` count cannot express "each arm carries each key", which is the property that
matters. It tolerates one arm losing a key whenever any other arm, or the legend, carries a spare.

**Replacement — per-arm and exact.** For each of CA-1..CA-6, the section from its heading to the next
heading must contain each of the five keys **exactly once**:

```bash
f=.planning/phases/33-the-consumer-product/33-CONTROL-ARMS.md
fail=0
for n in 1 2 3 4 5 6; do
  sec=$(awk -v n="$n" '$0 ~ "^### CA-"n" " {p=1; next} /^### CA-[0-9] /{p=0} /^## /{p=0} p' "$f")
  for k in measure result control control-result falsifies; do
    c=$(grep -cE "^$k:" <<< "$sec" || true)
    [ "$c" = "1" ] || { echo "CA-$n: '$k:' appears $c times, expected exactly 1"; fail=1; }
  done
done
[ "$fail" = "0" ] || exit 1
echo "per-arm structure OK"
```

Both directions, measured:

```
  real tree        -> per-arm structure OK,                          rc=0
  CA-4 control:    -> "CA-4: 'control:' appears 0 times, expected 1", rc=1
  CA-2 falsifies:  -> "CA-2: 'falsifies:' appears 0 times, expected 1", rc=1
  legend un-indented (the original defect reintroduced) -> still rc=0, because the
    replacement is scoped per-arm and a legend outside any CA section cannot mask a gap
```

Note the shape rules this obeys: the count is captured with `|| true` and compared as a value, never
piped into `grep -q` — `grep -c` exits **1** on a zero count, i.e. on the desired state for an
absence check, and `cmd | grep -q X` under `pipefail` inverts on match via SIGPIPE→141.

## Owner decisions

*Pending — 33-00 Task 4.*
