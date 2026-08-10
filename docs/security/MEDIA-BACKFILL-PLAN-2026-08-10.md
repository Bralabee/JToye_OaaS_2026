# Media Backfill — Measurement and Deferred Sweep Plan

**Reference:** Issue #488 (pre-#479 media objects) / Phase 28 decisions **D-05**–**D-08**
(`.planning/phases/28-security-triage-the-dev-prod-boundary/28-CONTEXT.md`)
**Measurement date:** 2026-08-10
**Measured against:** the live `jtoye-images` MinIO bucket on the local full-stack runtime
**Classification:** Internal record — sanitized for a public repository (impact, fix and
acceptance only; no credential values, no object keys, no repro payloads)
**Status:** #488's **urgent limb is closed by measurement**; the full-catalogue sweep is
**deferred with this specification**, and one **blocker** (#626) must clear before it can run

---

## 1. Why this document exists

D-05 says "measure first, remediate the urgent subset now". This records the measurement, the
disposition it produced, and — because the measurement changed what the remaining work *is* —
the full specification the deferred half will execute when it runs.

The short version: the urgent subset is **empty**, and the deferred half is **smaller and less
dangerous than #488 describes**. Both of those are claims about a running object store, so both
are recorded below with the control that makes them non-vacuous. A census reported without its
control cannot distinguish "there is nothing there" from "the instrument reported nothing", and
this document would be worthless if it made that mistake — the entire justification for
deferring work rests on these numbers.

---

## 2. The measurement

All three censuses were taken **2026-08-10 at 02:13Z**, credentialed, against the live bucket.
Nothing in the enumeration path is bounded by `head` or any other truncating filter: a
truncating filter used to prove an absence manufactures the absence it was asked to disprove.

**Denominator: 768 objects.** By prefix — `media` 731, `products` 33, `shops` 4.

### Census 1 — stored Content-Type, by value

| Stored Content-Type | Objects |
|---|---|
| `image/webp` | 731 |
| `image/jpeg` | 35 |
| `image/png` | 2 |
| **Total** | **768** |

**Objects whose stored Content-Type is outside the allowlist `MediaNormalizer` enforces on
upload (`image/jpeg`, `image/png`, `image/webp`, `image/gif`): 0 of 768.**

**Control.** This zero is not self-certifying, so the same predicate was run against a
deliberately broken input: one object was stored with `Content-Type: text/html` and the gate
reported **1 of 769**, naming the offending key; the object was then deleted and the gate
returned to **0 of 768**. The probe is therefore demonstrably able to report a non-allowlist
type, and the zero is a fact about the bucket rather than about the filter. Two further break
arms are recorded in §6.

### Census 2 — EXIF / GPS metadata on legacy objects

The population is the **37 non-WebP objects** (35 `image/jpeg` + 2 `image/png`) — that is, every
object that did not come out of the Phase 24 normaliser, whose decode-and-re-encode drops all
source metadata by construction. Each was fetched and read with `exiftool -q -s -G`.

**Result: 0 of 37 carry any `[EXIF]` or `[GPS]` tag.**

**Control.** One of those same 37 objects was copied and GPS was injected into the copy
(`-GPSLatitude`, `-GPSLatitudeRef`, `-GPSLongitude`, `-GPSLongitudeRef`, `-Make`). The identical
census over the copy reported **8 `[EXIF]`/`[GPS]` tag lines**, including `GPSVersionID`,
`GPSLatitudeRef`, `GPSLongitudeRef` and the `[Composite]` `GPSPosition`. So `exiftool` does
report tags on this population when tags are present, and "0 of 37" means the objects are clean
— not that the instrument was silent.

### Census 3 — quarantine prefix

**Objects under a `*/quarantine/*` prefix: 0.** No quarantined originals are currently resident,
so the retention exposure described in §5's blocker is latent rather than active today.

---

## 3. Disposition of #488's urgent limb — CLOSED by measurement

#488's urgent property is the **stored Content-Type**, and it is urgent for a specific reason:
MinIO serves an object with the Content-Type it was stored with, and this bucket is a public
origin. An object stored as `text/html` is a stored-XSS primitive on the storefront's own origin
regardless of what its bytes actually contain.

The measured population of that subset is **0 of 768**. D-05 scheduled a re-pipeline of exactly
that subset; running it would process zero objects and report success, and any later work built
on its output would be built over an empty input. **That is a scope observation, not a scope
reduction** — the property #488 asks for holds, and what was missing was not remediation but
anything that would notice it stopping to hold.

So the limb is discharged by two things instead:

1. **The measurement above**, with its control.
2. **A permanent gate — `scripts/check-media-content-types.sh`** — which re-runs the census
   against the delivered object store and fails when the count moves off zero, naming the
   offending keys. PR #479 closed the **write** path forward-only (`MediaNormalizer`'s magic-byte
   sniff, so a client-declared Content-Type is never trusted). It says nothing about objects
   already resident, and nothing prevents a manual `mc cp`, a restored backup, or a future code
   path from putting one back. The gate covers precisely the gap #479 cannot.

The gate asserts a **relation with a denominator** — "N of M objects are outside the allowlist" —
never a census. Pinning `M = 768` would red on the next legitimate vendor upload, and a gate that
fails on correct data gets silenced. `M == 0` is a **VOID**, not a pass, so a wrong bucket name,
an empty bucket, or a credential that can authenticate but not list cannot report a clean zero
over a bucket the gate never actually read. It is registered in
`scripts/gates/gate-enforcement.conf` as runtime-dependent, because a hosted runner has no MinIO
container, no credentials and no bucket.

---

## 4. Correction to #488's framing — the deferred half is CWV, not GDPR

This correction is part of the deferral being honest. #488 describes the legacy objects as
carrying **EXIF GPS**, and that framing is what makes deferring the sweep feel dangerous: an
unresolved GDPR exposure on a public origin is not something to leave running for a phase or
more.

**Measured, with its control: 0 of 37 legacy objects carry any EXIF or GPS tag** (§2, Census 2).
The positive control reported 8 tag lines on a GPS-injected copy of one of the same files, so the
instrument was working and the population is genuinely clean.

Therefore the remaining cost of the deferred half is **Core Web Vitals**, not data protection:
**37 objects** are still served as JPEG/PNG rather than as the WebP derivative + 400 px thumbnail
the pipeline produces, with no stored width/height hints. That is a page-weight and CLS cost on
storefront surfaces, bounded and quantified, and it is a legitimate thing to schedule rather than
rush.

If a future upload of a metadata-bearing original lands on the legacy path, this correction stops
being true. That is why the sweep is deferred **with a target** rather than dropped, and why
Census 2 should be re-run — with its control — before the sweep is scheduled.

---

## 5. The deferred sweep — full specification

D-06, D-07 and D-08 are carried forward **unchanged**. Nothing below is a reduction of what the
phase context locked; the measurement changed the urgency and the input size, not the design.

### D-06 — Originals retained on a horizon

Re-pipelined originals move to a **non-public quarantine prefix** with a **declared expiry**,
then are reaped. This mirrors the V60 pattern already in the schema: the
`media_asset.quarantine_expires_at` / `quarantine_reclaimed_at` column pair, swept on a horizon,
so retention is a declared decision rather than a fifteen-minute accident. The property that
matters is reversibility — the public origin is clean immediately, and the original survives long
enough to recover from a bad transcode.

> **BLOCKER — this limb cannot be implemented as written today.** A "non-public quarantine prefix"
> is **not achievable by a naming convention**. The `jtoye-images` bucket carries a **bucket-wide**
> anonymous policy, so it covers every key regardless of prefix; moving an object under
> `.../quarantine/...` grants it no protection whatever. D-06 requires a **prefix-scoped bucket
> policy**, which is a change to the bucket bootstrap, not to the sweep.
>
> This is tracked as **#626** (the bucket additionally grants anonymous `s3:ListBucket`, so the
> whole inventory is enumerable without a credential — which is also why key-obscurity cannot be
> relied on as a substitute). **The fix lands in this phase, in plan 28-09**, in the same bootstrap
> step being reworked for #270. **The sweep must not start until #626 is closed**, or D-06's
> retention window would place unvalidated original bytes on a public, enumerable origin.

### D-07 — Normaliser rejects are pulled, FAILED, and vendor-visible

An object the normaliser cannot decode is **removed from the public origin immediately** (onto the
same quarantine horizon), and its asset is marked `FAILED` **with a reason**. The existing IMG-04
vendor UI already renders `FAILED` → reason + **Re-upload**, so no new UI is required.

Availability loses to safety here, deliberately. The alternative — leaving an undecodable object
serving from a public origin because pulling it would show a gap — is regression by omission with
extra steps. The vendor is told, in the surface they already use, and given the action that fixes
it.

### D-08 — Successful normalisation goes to the standard derivative path

A successfully normalised object produces a **WebP derivative + 400 px thumbnail**, exactly as a
fresh upload does, and every reference updates to it: the `media_asset` row **and** the flat
dual-read columns (`products.image_url`, `products.additional_image_urls[]`) that D-03a kept in
Phase 24. URLs change once, and afterwards every image on the platform lives under a single
pipeline contract.

> **Invariant, stated explicitly: no storefront image 404s during the transition.** This is the
> acceptance criterion the sweep is judged on, not a nice-to-have. It constrains the ordering —
> the new derivative must exist and the references must point at it before the old object stops
> being served — and it must be verified against the running storefront, not inferred from the
> code, since a 200 on a stale reference and a 200 on a fresh one are indistinguishable except by
> content.

---

## 6. Evidence recorded for the gate

The gate in §3 is only worth its exit code if it has been shown to fail. Three break arms were
run, each bracketed **clean → break → observe → restore → clean again**, because the closing
arm is the only thing that proves the restore happened:

| Arm | Break applied | Observed | Restored |
|---|---|---|---|
| Invariant | one object stored as `text/html` | **rc=1**, offending key NAMED, 1 of 769 | object deleted → **rc=0**, 0 of 768 |
| Fail-closed | MinIO container stopped | **rc=2 VOID**, not 0 | container started → **rc=0** |
| Denominator | pointed at a non-existent bucket | **rc=2 VOID**, not 0 on "no offending objects" | n/a — parameter only |

The fail-closed arm is the one that matters most. A gate that fails **open** on missing input is
worse than no gate, because it is trusted.

---

## 7. Implementation analogs — do not rebuild these

The machinery the sweep needs already exists. A future executor should wire these together, not
write new transform or dispatch code:

- **`MediaAssetService.redriveFromQuarantine`** — the re-drive path. In one transaction it returns
  an asset to `PENDING`, clears its failure state, increments `process_attempts`, and inserts a
  fresh outbox row. It already guards three preconditions with typed RFC 7807 409s (bytes still
  retained, not already `ACTIVE`, attempt budget not exhausted) and orders the RLS-scoped
  `findById` before the shop-scoped write gate so a foreign-tenant asset is a 404 and never a 403.
- **`MediaProcessingEvent(tenantId, assetId)` on `media_event_outbox`** — the transactional
  hand-off. The asset's return to `PENDING` and the event that will act on it commit together or
  neither does.
- **V60's `quarantine_expires_at` / `quarantine_reclaimed_at` columns** — D-06's retain-on-a-horizon
  is this pattern, not a new one. Both halves are independently load-bearing: never-claimed bytes
  and already-reclaimed bytes are different histories with the same consequence.

**`outbox_flusher_dispatch_trap` does NOT apply to this work, and the reason is specific.** That
trap bites when a new event type is added to a *shared* outbox whose flusher dispatches on a
closed set of types — the new type falls through the `else` branch and poison-dead-letters unless
`publishRow` is extended in the same change. `MediaEventOutboxFlusher.publishRow` has **no
closed-set dispatch at all**: the dedicated media outbox has exactly one destination exchange, so
publishing is a single deserialise-and-send with no `else` branch that could poison-cast a media
payload. This is why Phase 24 gave media its own outbox table rather than extending the payment
one. Adding sweep-originated events needs **no flusher edit**.

---

## 8. Target, and what stays exposed until it runs

**Target: Phase 29 or later, or a background job.** The sweep is not urgent and should not
displace work that is; it should, however, be scheduled rather than rediscovered.

**Precondition: #626 must be closed first** (§5, D-06). This is a hard ordering constraint, not a
preference.

**What remains exposed until the sweep runs, stated in the measured terms above rather than
#488's original framing:**

- **Not a GDPR exposure.** 0 of 37 legacy objects carry EXIF or GPS metadata, measured with a
  positive control that reported 8 tag lines on an injected copy. #488's EXIF-GPS framing does not
  match the current population.
- **Not a stored-XSS exposure.** 0 of 768 objects carry a Content-Type outside the allowlist, and
  `scripts/check-media-content-types.sh` now fails if that changes.
- **A bounded Core Web Vitals cost: 37 objects** served as JPEG/PNG instead of WebP + thumbnail,
  without stored width/height hints — page weight and CLS on the storefront surfaces that render
  them.
- **A live information-disclosure exposure that is NOT deferred and NOT this document's to fix:**
  the anonymous `s3:ListBucket` grant (#626) makes the whole object inventory, including
  per-tenant object counts, enumerable with no credential. It is **unmitigated as of this
  document's date** and is being fixed in plan 28-09 of this phase. It is recorded here rather
  than implied, so the gap is visible.

---

## 9. Re-measuring

Every number in this document decays on the next upload. Before acting on it:

- Re-run `bash scripts/check-media-content-types.sh` for Census 1 and its denominator.
- Re-run Census 2 **with its GPS-injection control** before repeating the "not GDPR" claim in §4.
  Without the control the census is a statement about `exiftool`, not about the objects.
- Re-run Census 3 before assuming the quarantine prefix is still empty.
