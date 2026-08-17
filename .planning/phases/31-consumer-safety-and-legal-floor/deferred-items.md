# Deferred items — Phase 31

Out-of-scope discoveries logged rather than fixed, per the executor scope boundary.

---

## DEF-31-11-01 — JSX transform silently deletes a space after an inline element

**Found by:** plan 31-11, while reading rendered output during a break arm.
**Status:** FIXED on the two pages 31-11 owns. **Not fixed elsewhere** — out of scope.

### The mechanism, measured with a four-arm control

When the JSXText node that **follows** an inline element contains an HTML entity
anywhere within it, the transform drops that text node's **leading space**. The entity
does not need to be adjacent — in the real case it was `&apos;` several words later.

| Arm | Shape | Rendered HTML |
|---|---|---|
| control | inline element, no entity in the following text | `</code> so your` — space kept |
| **break** | inline element, `&apos;` later in the same text node | `</code>so your` — **SPACE LOST** |
| control | `&apos;` only *before* the inline element | `</code> so your` — space kept |
| control | explicit `{" "}` at the boundary, entity present | `</code> so your` — space kept |

### Why it matters and why nothing caught it

The **source retains the space**, so code review cannot see the defect; it exists only in
the delivered HTML. It shipped three run-together phrases into a legal page
(`js.stripe.comso your card details`, `<shop>there is one item`,
`Clearing site datain your browser`) and no gate in this repository noticed.

It is **systematically reachable**: this project's own `react/no-unescaped-entities`
lint rule *requires* `&apos;` in JSX text, so any paragraph that mixes an inline
element with an apostrophe can hit it.

### Prevalence elsewhere (approximate, instrument validated)

A static scan of `frontend/app` and `frontend/components` (`.tsx`, tests excluded) for
the boundary shape, checking whether the following JSXText run contains an entity:

```
APPROX suspect boundaries: 3 across 2 files
  2  components/marketing/business-model-guide.tsx
  1  components/marketing/competitive-teardown.tsx
```

**The scanner was controlled before the number was trusted**: run against a fixture
holding one bad variant and two good ones (no-entity, and explicit `{" "}`), it
reported exactly `1`. Positive control fires, both negative controls stay silent.

The count is **approximate** — it is a line-based heuristic, not a JSX parser, so treat
it as a magnitude and confirm each hit by rendering. Both hits are marketing components,
neither owned by this phase.

### Recommended fix

Explicit `{" "}` at the boundary (proven to survive in the fourth arm). The durable
guard is a rendered-output assertion, not a source grep — 31-11 added one to both of its
pages:

```ts
el.innerHTML.match(/<\/(?:code|span|a|strong|em|b|i)>[A-Za-z]\w*/g)
```

A repo-wide version of that assertion would be the right home for this, but it belongs
to whoever owns those marketing surfaces rather than to this phase.

---

## From 31-17 (Legal column reachability)

### 1. Three pre-existing `tsc --noEmit` errors, none in files this plan touched

`npm run build` is rc=0 (it does not typecheck these test files), but `npx tsc --noEmit`
in `frontend/` is rc=2 on the clean base `2e9a51fe`, before and after this plan:

| File | Error |
|---|---|
| `__tests__/shop/server-seeded-islands.test.tsx:100` | TS2739: fixture missing `first`, `last` from `PageResponse<PublicShop>` |
| `components/dashboard/__tests__/dashboard-shell.test.tsx:154` | TS2503: cannot find namespace `JSX` |
| `lib/__tests__/structured-data.test.ts:91` | TS2352: unsound cast to `Record<string, never>` |

Verified out of scope by `git diff --name-only 2e9a51fe`, which lists only this plan's
four files. Left alone under the scope boundary. Worth noting that **no CI gate runs
`tsc --noEmit` across test files**, which is why these have survived — `npm run build`
typechecks app code only.

### 2. `/track` is in `sitemap.ts` while `robots.ts` disallows it

`app/sitemap.ts` lists `/track` in `STATIC_ROUTES`; `app/robots.ts` has `Disallow: /track`.
Advertising a URL in the sitemap and forbidding it in robots.txt is contradictory guidance
to a crawler. Pre-existing, unrelated to the legal routes, and `sitemap.ts` is only
partly this plan's file — the five `/legal` entries added here are correctly NOT
disallowed (verified against the SERVED `/robots.txt`, not just the source).
Belongs to whoever owns the `/track` surface.

### 3. `check-test-count-oracle.sh` VOIDs in a fresh worktree

`--- vitest -> rc=2  VOID: mcp-server/node_modules is absent`. A fresh GSD worktree only
ever gets `npm ci` in `frontend/`, so the vitest third of the oracle cannot run and the
whole gate reports VOID (correctly — it refuses to call an unverified family a pass).
Cleared here by running `npm ci` in `mcp-server/`, after which all three runners agree.
Worth adding to the worktree bootstrap so the gate is not routinely VOID.

### 4. The contrast literal ledger cannot see the legal pages (found by 31-18)

`frontend/__tests__/contrast-literals.test.ts` declares `SCAN_ROOTS` as `app/page.tsx`,
`app/shop`, `app/auth/signin`, `components/public`, `components/storefront`,
`components/marketing`. It does **not** include `components/legal` or `app/legal`, yet all
five `/legal/*` routes are declared in-scope surfaces in the published conformance
statement — and `/legal/accessibility` IS that statement.

Measured consequence, not hypothetical: `PolicyToc`'s links shipped `text-amber-700` on the
below-`lg` `bg-cream-100/60` panel at **4.41:1** against a 4.5:1 requirement, across all four
policy documents (9+7+5+6 nodes). The literal ledger was structurally incapable of seeing it;
only 31-18's browser scan at 390px found it. Fixed in `8cf11de7` (amber-800, 6.23:1).

NOT closed here: widening `SCAN_ROOTS` is outside 31-18's `files_modified`, and adding two
roots would likely surface further literals needing new ledger entries — a contrast-debt
decision with published-commitment consequences, since the `text-contrast-below-minimum`
exception carries a ratified 2027-02-16 date. Raised as an owner question in 31-18's summary.

### 5. `rg` does not exist in a script subprocess — absence checks fail OPEN (re-confirmed by 31-18)

Re-hit while writing 31-18's acceptance script. `rg` and the `grep` shell function are
interactive-shell constructs; inside `bash script.sh` both are `command not found`. Presence
checks then fail loudly (harmless), but **absence checks print OK from `0 == 0`** and certify
a removal that was never verified. Caught only by an explicit instrument control asserting a
token that MUST be absent. Use `/usr/bin/grep` by absolute path in scripts (a real binary,
not `.gitignore`-aware). Secondary trap in the same shape: `grep -c` prints `0` AND exits 1,
so a `|| echo 0` fallback emits `"0\n0"` and every absence comparison fails confusingly.
