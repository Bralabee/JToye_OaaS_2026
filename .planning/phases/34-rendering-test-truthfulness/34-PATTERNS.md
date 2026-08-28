# Phase 34: Rendering + Test Truthfulness - Pattern Map

**Mapped:** 2026-08-28
**Files analyzed:** 23 (8 created, 15 modified)
**Analogs found:** 21 / 23

> Source of the file list: `34-RESEARCH.md` — "Wave 0 gaps", "Code Examples", "Pattern 1–4",
> "Site-by-site recommendation", "Runtime State Inventory". **No CONTEXT.md exists for this phase**
> (`has_context: false`), so every file below is derived from RESEARCH + ROADMAP criteria, not from
> locked user decisions.
>
> **The phase's own headline finding governs this map:** every pattern this phase needs is already
> shipped in this repo. There is one genuinely new artefact (the SSR-route manifest gate) and one
> genuinely new module shape (a session external store). Everything else is a copy of something
> below, with the numbers changed.

---

## File Classification

### Created

| New file | Role | Data Flow | Closest Analog | Match Quality |
|----------|------|-----------|----------------|---------------|
| `scripts/check-ssr-coverage-contract.sh` | gate script | batch / static-scan | `scripts/check-e2e-skip-budget.sh` | **exact** (default-deny + stale-entry + both-direction self-test + VOID) |
| `scripts/gates/ssr-routes.conf` | config (manifest) | declarative registry | `scripts/gates/e2e-skip-budget.conf` | **exact** |
| `frontend/e2e/ssr-coverage.spec.ts` *(or new blocks inside `storefront-ssr-seo.spec.ts`)* | test (e2e) | request-response (raw HTTP) | `frontend/e2e/storefront-ssr-seo.spec.ts` | **exact** |
| `frontend/e2e/helpers/ssr-fixture-server.ts` | test utility (node:http) | request-response | `frontend/e2e/helpers/public-surface.ts` | role-match (same fixture constants; different transport) |
| `frontend/hooks/use-theme.ts` | hook (external store) | event-driven / pub-sub | `frontend/components/marketing/reveal.tsx:34-58` | **exact** (`useSyncExternalStore` + `getServerSnapshot`) |
| `frontend/hooks/__tests__/use-theme.test.tsx` | test (unit) | event-driven | `frontend/hooks/__tests__/use-stored-state.test.tsx` | role-match |
| `frontend/lib/customer-session-store.ts` | store module | pub-sub | `frontend/hooks/use-cart-count.ts:16-36` (`readCount` + listener set) + `reveal.tsx` subscribe shape | role-match (composite) |
| `frontend/hooks/__tests__/use-customer-session.test.tsx` | test (unit) | event-driven | `frontend/hooks/__tests__/use-cart-count.test.tsx` | role-match |

### Modified

| Modified file | Role | Data Flow | Closest Analog | Match Quality |
|---------------|------|-----------|----------------|---------------|
| `frontend/components/dashboard/sidebar.tsx` | component | event-driven | `frontend/components/marketing/reveal.tsx` | **exact** |
| `frontend/components/dashboard/mobile-tab-bar.tsx` | component | event-driven | same shared `useTheme()` as sidebar | **exact** |
| `frontend/app/shop/auth/callback/page.tsx` | page (client island) | request-response | derive-during-render; no in-repo analog needed (rule shape E) | role-match |
| `frontend/hooks/use-customer-session.ts` | hook | pub-sub | `reveal.tsx` + `use-cart-count.ts` | role-match |
| `frontend/e2e/onboarding-blocked-flow.spec.ts` | test (e2e) | request-response | `frontend/e2e/storefront-ssr-seo.spec.ts:73-76` (`@desktop-only` tag) and `dashboard-interface-corrections.spec.ts:97-105` (`@mobile-only` with a written reason) | **exact** |
| `scripts/gates/e2e-skip-budget.conf` | config | declarative registry | its own retirement protocol, lines 25-29 | **exact (self)** |
| `core-java/build.gradle.kts` | build config | batch | its own `tasks.register<Test>("integrationTest")` block (`:202-284`) | role-match |
| `frontend/jest.config.js` | test config | batch | itself (`collectCoverageFrom` already present, `:13-21`) | **exact (self)** |
| `.github/workflows/ci-cd.yaml` | CI config | batch | job `ops-contracts` gate steps (`:675-878`) + job `test` Go/Jest steps (`:74-153`) | **exact** |
| `frontend/e2e/dashboard-mobile.spec.ts` | test (e2e) | request-response | `frontend/e2e/dashboard-interface-corrections.spec.ts` (real login, **0** `.route(`) | **exact** |
| `frontend/e2e/storefront-ssr-seo.spec.ts` | test (e2e) | request-response | itself | **exact (self)** |
| `frontend/playwright.config.ts` | test config | — | its own two-project block (`:73-112`) | **exact (self)** |
| `docs/metrics.json` + prose in `README.md` / `AGENTS.md` / `CLAUDE.md` | docs | — | `scripts/docs-freshness.sh --write` protocol | **exact** |
| `scripts/gates/gate-enforcement.conf` | config | declarative registry | its own entries (`:19-36`) | **exact (self)** — *only if the new gate turns out runtime-dependent; the static path is to wire it into CI instead* |
| `frontend/app/track/page.tsx` *(discretionary conversion)* | page → server + island | request-response | `frontend/app/shop/page.tsx` + `shop-discovery-client.tsx` | **exact** |
| `frontend/app/shop/[slug]/orders/[orderNumber]/page.tsx` *(discretionary)* | page → server + island | request-response | `frontend/app/shop/orders/page.tsx` (authenticated variant) | **exact** |

---

## Pattern Assignments

### `scripts/check-ssr-coverage-contract.sh` (gate script, static-scan)

**Analog:** `scripts/check-e2e-skip-budget.sh` — the research names this explicitly ("clone
`check-e2e-skip-budget.sh` + its `.conf`"). It already carries default-deny, stale-entry-fails, a
both-directions matcher self-test, and VOID-on-unparseable.

**Header / rationale pattern** (`check-e2e-skip-budget.sh:1-86`) — copy the *shape*: WHY THIS
EXISTS → WHY A COUNT ALONE IS NOT ENOUGH → AND WHY STALE ENTRIES ALSO FAIL → WHAT IT ENFORCES
(numbered assertion IDs) → INPUT → EXIT CODES → USAGE. The assertion IDs (`S-1`…`S-4`) are quoted
verbatim in the failure messages, which is what makes a red build self-explaining. Use `R-1`…`R-3`
per RESEARCH Pattern 2.

**Boilerplate + VOID discipline** (`check-e2e-skip-budget.sh:87-113`):

```bash
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONF="${E2E_SKIP_CONF:-$REPO_ROOT/scripts/gates/e2e-skip-budget.conf}"

echo "check-e2e-skip-budget  ($(date -u +%Y-%m-%dT%H:%M:%SZ))"

void() { echo "VOID: $*" >&2; exit 2; }
fail_count=0
fail() { echo "FAIL: $*" >&2; fail_count=$((fail_count + 1)); }

while [ $# -gt 0 ]; do
    case "$1" in
        -h|--help) sed -n '2,/^set -uo/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//; $d'; exit 0 ;;
        *) void "unknown argument: $1 (try --help)" ;;
    esac
done

command -v jq >/dev/null 2>&1 || void "jq is not installed — ..."
[ -f "$CONF" ] || void "config not found: $CONF"
```

Note `set -uo pipefail` — **not** `set -e`: the script accumulates failures and exits once. The
alternative in-repo style is `set -euo pipefail` (`check-playwright-mobile-contract.sh:38`), used
when the gate exits on the first violation. Pick the accumulating form; a manifest gate should
report every undeclared route in one run.

**Config parser — unknown directive is VOID, not a shrug** (`check-e2e-skip-budget.sh:157-176`):

```bash
MAX_SKIPS=""
ALLOWS=()
while IFS= read -r raw; do
    line="${raw%%$'\r'}"
    case "$(printf '%s' "$line" | sed 's/^[[:space:]]*//')" in
        ''|'#'*) continue ;;
    esac
    directive=$(printf '%s' "$line" | awk '{print $1}')
    value=$(printf '%s' "$line" | cut -d' ' -f2-)
    case "$directive" in
        MAX_SKIPS) MAX_SKIPS="$value" ;;
        ALLOW)     ALLOWS+=("$value") ;;
        *)         void "unknown directive '$directive' in $CONF — refusing to guess" ;;
    esac
done < "$CONF"

[ "${#ALLOWS[@]}" -gt 0 ] || void "$CONF declares no ALLOW entries — a budget with no declarations cannot enforce S-2"
```

**R-3 self-test — both directions, run BEFORE the real checks** (`check-e2e-skip-budget.sh:239-256`):

```bash
matches_any_allow() {
    local title="$1" a
    for a in "${ALLOWS[@]}"; do
        case "$title" in *"$a"*) return 0 ;; esac
    done
    return 1
}

# --- S-4  self-test of the matcher, BOTH directions ---------------------------------
# Run before the real checks: a matcher that silently stopped working would make S-2
# pass over everything, which is the classic vacuous assertion.
if [ "${#SKIPPED[@]}" -gt 0 ]; then
    probe="${ALLOWS[0]}"
    matches_any_allow "prefix ${probe} suffix" \
        || void "S-4 self-test: the matcher failed to match a title containing its own ALLOW"
fi
matches_any_allow "zzz-no-such-test-title-should-ever-match-this-zzz" \
    && void "S-4 self-test: the matcher fired on a constructed-absent title — it would declare anything"
```

For R-3 the equivalent is: classify **one known server page** (`app/shop/page.tsx`) and **one known
client page** (`app/track/page.tsx`, first line is `"use client"` — verified) in the same run, and
VOID if either verdict is wrong.

**Zero-discovery is VOID, never clean** (`check-e2e-skip-budget.sh:180-182`, and the same rule in
`check-playwright-mobile-contract.sh:80`):

```bash
TOTAL=$(jq '...' "$REPORT" 2>/dev/null)
case "${TOTAL:-}" in ''|*[!0-9]*) void "could not count tests in $REPORT — is it a Playwright JSON report?" ;; esac
[ "$TOTAL" -gt 0 ] || void "report contains ZERO test results — a run that executed nothing is not a pass"
```

```bash
[ -n "$REPORT" ] || void "no Playwright projects parsed out of $CONFIG — a scan with no inputs cannot prove anything"
```

**Stale-entry check — the R-2 shape** (`check-e2e-skip-budget.sh:268-275`):

```bash
for a in "${ALLOWS[@]}"; do
    hit=0
    for title in "${SKIPPED[@]}"; do
        case "$title" in *"$a"*) hit=1; break ;; esac
    done
    [ "$hit" -eq 1 ] || fail "S-3 stale ALLOW '$a' matches no skipped test — the exemption outlived its cause; delete it and lower MAX_SKIPS"
done
```

**Exit block** (`check-e2e-skip-budget.sh:277-286`):

```bash
echo "  declared  : ${#ALLOWS[@]} ALLOW entr(ies), ${#SKIPPED[@]} distinct skipped title(s)"
echo "  S-4 self  : matcher fires on a known title and declines a constructed-absent one"

if [ "$fail_count" -eq 0 ]; then
    echo "PASS: all $SKIP_COUNT skip(s) are declared and within the budget of $MAX_SKIPS."
    echo "      NOTE: a declared skip is still UNVERIFIED SURFACE, not a pass."
    exit 0
fi
echo "FAILED: $fail_count skip-budget violation(s) (see above)." >&2
exit 1
```

**Search discipline (copy verbatim from `check-gate-enforcement.sh:47-55`)** — this matters for the
`page.tsx` discovery walk:

```
#   Workflow files are enumerated with `find` and then searched BY NAME, never with a
#   recursive grep. Two reasons, both measured on this machine: `rg` does not exist
#   inside a `bash script.sh` (it is a shell function the harness injects; there is no
#   system ripgrep, so it dies rc=127, which is indistinguishable from "no matches"),
#   and the `grep` function routes to ugrep with --ignore-files, so a recursive search
#   silently honours .gitignore.
```

Enumerate with `find "$REPO_ROOT/frontend/app" -type f -name 'page.tsx' | sort` and read each file
directly. **Do not use `git grep -l '"use client"'`** — RESEARCH Pitfall 1 measured it wrong by 4.

**"What it does NOT check", stated rather than implied** — copy this stanza from
`check-playwright-mobile-contract.sh:29-35`; it is the honest counterweight to a structural gate:

```
# WHAT IT DOES NOT CHECK — stated rather than implied:
#   This is a CONFIG-SHAPE gate. It reads text; it cannot prove the emulation took
#   effect. ... a structural check can pass while the function is still broken.
```

---

### `scripts/gates/ssr-routes.conf` (config, declarative registry)

**Analog:** `scripts/gates/e2e-skip-budget.conf`

**Header + SYNTAX + RETIRING protocol** (`e2e-skip-budget.conf:1-31`):

```
# e2e-skip-budget.conf — the skips the Playwright suite is ALLOWED to report.
#
# WHY THIS FILE EXISTS
#   ... A bare count is not enough. If one skip is fixed and a new one appears, the count is
#   unchanged and the regression is invisible. So this gate matches skips by TITLE ...
#
# SYNTAX — one directive per line. Blank lines and lines whose first non-space character
# is '#' are ignored. An unknown directive is a VOID, not a shrug.
#
#   MAX_SKIPS <n>
#   ALLOW <substring>
#       ... Every ALLOW must be justified in a comment directly above it, naming what
#       would remove it.
#
# RETIRING AN ENTRY
#   When a fixture or capability lands, DELETE its ALLOW and lower MAX_SKIPS. The gate
#   fails on an ALLOW that no longer matches anything (a stale exemption is a lie about
#   coverage) ...
```

**Per-entry justification format** (`e2e-skip-budget.conf:33-44`) — a reason comment *directly
above* each directive, naming the removal condition:

```
# 2 tests x 2 projects. Multi-replica STOMP broadcast: needs the stack scaled to two
# core-java replicas ... REMOVE WHEN: a scaled-stack job exists, or the spec is rewritten
# to drive both replicas itself.
ALLOW stomp-relay.spec.ts
```

**Warning carried by this analog:** its own arithmetic comment (`:51-56`, "Total = 8 (4 distinct
tests x 2 projects)") is **measurably wrong** — RESEARCH confirms actual is 7. Do not let the new
conf carry a hand-computed total; state the measurement and how it was taken.

---

### `frontend/e2e/ssr-coverage.spec.ts` (test, request-response over raw HTTP)

**Analog:** `frontend/e2e/storefront-ssr-seo.spec.ts` — the shipped exemplar. Generalise, do not
re-invent.

**Docblock pattern — the pre-fix measurement is IN the file** (`storefront-ssr-seo.spec.ts:1-27`):

```typescript
/**
 * Storefront server-rendering + discoverability, measured in the SERVED HTML
 * (issues #507, #447).
 *
 * WHY THIS READS THE RAW RESPONSE AND NOT THE DOM
 *
 * The whole change is about what arrives BEFORE JavaScript runs. A `page.goto`
 * + `expect(locator)` would pass identically on the pre-fix tree, because the
 * client-side fetch fills the DOM in about two and a half seconds and Playwright
 * waits. Every block in the first describe therefore uses `request.get`, which
 * performs no navigation, runs no script, and hands back the bytes the crawler
 * and the first paint actually get.
 *
 * MEASURED ON THE PRE-FIX TREE — what each block below was written to fail
 * against (running stack, 2026-08-04):
 *
 *   /shop/brixton-village-grill .. 34,419 bytes, 1 spinner, 0 <h1>,
 *                                  0 occurrences of "Brixton Village Grill"
 *   /shop ........................ 0 occurrences of ANY shop name
 *   ...
 */
```

**Imports** (`storefront-ssr-seo.spec.ts:29-31`):

```typescript
import { test, expect, type APIRequestContext } from "@playwright/test"

const SHOP_SLUGS = ["brixton-village-grill", "mama-ades-kitchen"]
```

**Core pattern — the raw-HTML helpers to lift into a shared module** (`:33-46`):

```typescript
/** The raw response body — no browser, no hydration, no waiting. */
async function servedHtml(request: APIRequestContext, path: string): Promise<string> {
  const res = await request.get(path)
  expect(res.status(), `${path} should serve 200`).toBe(200)
  return res.text()
}

function countOf(html: string, needle: string | RegExp): number {
  const re =
    typeof needle === "string"
      ? new RegExp(needle.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"), "g")
      : new RegExp(needle.source, needle.flags.includes("g") ? needle.flags : needle.flags + "g")
  return (html.match(re) ?? []).length
}
```

Also available for reuse at `:48-70`: `titleOf(html)` (normalises `&#x27;` so a title compares as
text, not as an encoding) and `jsonLdNodes(html)` / `typesOf(nodes)`.

**Project-tagging pattern — `@desktop-only` with the reason stated** (`:72-77`):

```typescript
// The served bytes do not vary with viewport, so this half runs once rather than
// being duplicated across both projects. `@desktop-only` EXCLUDES it from the
// mobile project's enumeration (playwright.config.ts grepInvert) rather than
// skipping it at runtime — a skip must mean "nobody checked this".
test.describe("Storefront served HTML — content before JavaScript @desktop-only", () => {
```

**Assertion pattern — the message states the pre-fix number, so the block is falsifiable by
construction** (`:78-90`):

```typescript
    const html = await servedHtml(request, `/shop/${SHOP_SLUGS[0]}`)

    // 0 before this change, on 34,419 bytes of markup.
    expect(countOf(html, "<h1"), "the shop name must be an h1 in the served HTML").toBeGreaterThan(0)
    expect(
      countOf(html, "Brixton Village Grill"),
      "the shop's name appeared 0 times in the served HTML before #507"
    ).toBeGreaterThan(0)

    // The menu is the reason a customer is on this page, so it has to be here
    // too — a page that serves only the shop name would satisfy the two
    // assertions above and still be a spinner for the thing that matters.
    expect(html).toMatch(/£\d+\.\d{2}/)
```

**Anti-vacuity pattern — a control that must be present in BOTH arms** (`:93-105`, and RESEARCH's
`<h1` control in the Pitfall-2 table): assert something that is true on a *dead* server too, so a
zero is provably about the content and not about a server that never answered.

---

### `frontend/e2e/helpers/ssr-fixture-server.ts` (test utility, node:http)

**Analog:** `frontend/e2e/helpers/public-surface.ts` — same fixture constants, different transport.
**Feed the fixture server from these exact exports; do not write a second fixture set.**

**Why a plain module and not a spec — copy this reasoning** (`public-surface.ts:1-26`):

```typescript
/**
 * WHY THIS FILE EXISTS. `public-layout.spec.ts` grew these helpers, and
 * `public-a11y.spec.ts` (plan 31-18) needs exactly the same ones ... Copying them
 * would have created two definitions of "did this storefront actually load".
 *
 * WHY NOT `import { … } from "./public-layout.spec"`. Importing one spec file
 * from another EXECUTES its module body ... A plain module is not collected by
 * Playwright (`testMatch` is `*.spec.ts`), so it is the only shape that shares
 * code without sharing tests.
 *
 * WHY NAVIGATION HERE IS RELATIVE. `playwright.config.ts` is the ONLY base-URL
 * authority (`scripts/check-e2e-baseurl-contract.sh`, #505). That gate scans
 * `*.spec.ts` files, so a `PLAYWRIGHT_BASE_URL` fallback declared HERE would sit
 * outside its scan and silently escape the check it exists to enforce.
 */
```

> **RESEARCH Pitfall 7 is exactly this paragraph turned into a hazard.** A fixture-server port or
> origin declared in this helper sits **outside** `check-e2e-baseurl-contract.sh`'s `*.spec.ts`
> scan. Declare it where the gate can see it, or extend the gate's scan — and say which.

**Fixture constants to reuse verbatim** (`public-surface.ts:39-73`) — `SHOP` (slug
`test-kitchen`, name `Test Kitchen`) and `PRODUCTS` (3 items, `Mains`).

**Routing table to mirror on the server side** (`public-surface.ts:75-116`) — the path suffixes and
response shapes the SSR loader will ask for. Note the ProductsByCategory shape:

```typescript
export async function stubPublicApi(context: BrowserContext) {
  await context.route("**/public/**", async (route) => {
    const url = new URL(route.request().url())
    const p = url.pathname
    ...
    if (p.endsWith("/public/shops")) {
      return json({ content: [SHOP], totalElements: 1, totalPages: 1, size: 12,
                    number: 0, first: true, last: true })
    }
    // ProductsByCategory — a map keyed by category, NOT a flat array.
    if (p.endsWith("/products")) return json({ Mains: PRODUCTS })
    if (p.endsWith("/reviews")) { return json({ content: [], totalElements: 0, ... }) }
    if (p.endsWith("/promotions") || p.endsWith("/announcements")) return json([])
    if (p.endsWith("/config")) return json({})
    if (p.includes("/public/shops/")) return json(SHOP)
    return json({})
  })
}
```

Cross-check the paths against `lib/storefront-server.ts:128` and `:153-162` — the loader issues
`/public/shops?page=&size=`, `/public/shops/{slug}`, `/public/shops/{slug}/products`,
`/public/shops/{slug}/reviews?size=5`, `/public/shops/{slug}/promotions`,
`/public/shops/{slug}/announcements`. All six must be answered or the loader `defer`s and the
fixture server proves nothing.

**Refuse-to-continue-silently pattern** (`public-surface.ts:157-177`) — the same doctrine applies
to a fixture server that started but never bound:

```typescript
/**
 * The regression this exists to make loud: when the fixture slug started
 * 404ing, `locator("article").click()` simply waited out the full 60s test
 * timeout with a call log that said nothing about why. An empty page also
 * satisfies every invariant below it ... so the sibling layout test passed
 * VACUOUSLY over the same not-found page for as long as the modal test hung.
 */
```

**Security constraint (RESEARCH Security Domain, V14):** bind to `127.0.0.1`, start/stop inside the
Playwright run, and never reference the fixture from committed non-test config —
`CORE_API_INTERNAL_URL` pointed at a fixture in a non-test environment serves fixture data as real.

---

### `frontend/hooks/use-theme.ts` (hook, event-driven) + `sidebar.tsx` / `mobile-tab-bar.tsx`

**Analog:** `frontend/components/marketing/reveal.tsx` — the repo's shipped `useSyncExternalStore`
pattern, written for *exactly* this rule.

**Full pattern to copy** (`reveal.tsx:1-58`):

```typescript
"use client"

import { useSyncExternalStore, type ReactNode } from "react"
import { canEnhance, DESKTOP_MOTION_QUERY } from "@/lib/gsap-gate"

/**
 * No-FOUC contract: children are ALWAYS rendered fully visible unless the
 * client has resolved that this is a floor context ... The server snapshot is
 * `false` (plain, no hidden state in SSR markup), so if JS never runs the
 * content is never hidden. The floor gate is read via `useSyncExternalStore`
 * (React 19) subscribing to a matchMedia listener — NOT `useEffect` +
 * `setState`, which trips `react-hooks/set-state-in-effect`
 * (the rule that bit PR 221).
 */

function subscribeFloor(onChange: () => void): () => void {
  if (!canEnhance()) return () => {}
  const mql = window.matchMedia(DESKTOP_MOTION_QUERY)
  mql.addEventListener("change", onChange)
  return () => mql.removeEventListener("change", onChange)
}

function getFloorSnapshot(): boolean {
  if (!canEnhance()) return true
  return !window.matchMedia(DESKTOP_MOTION_QUERY).matches
}

// Server render: never animate → children render plain + visible (no-FOUC).
function getServerSnapshot(): boolean {
  return false
}

function useFloorActive(): boolean {
  return useSyncExternalStore(subscribeFloor, getFloorSnapshot, getServerSnapshot)
}
```

**The environment guard is the load-bearing part** — `canEnhance()` (`lib/gsap-gate.ts:40-42`) is
the repo's `typeof window !== "undefined" && typeof window.matchMedia === "function"` predicate.
RESEARCH: `useSyncExternalStore` **must** be given a `getServerSnapshot` or it throws during SSR,
and with `dynamic = "force-dynamic"` app-wide every one of these renders on the server per request.

**Safe-read pattern for localStorage** (`hooks/use-cart-count.ts:16-36` — the `getSnapshot`
equivalent for `theme`):

```typescript
function readCount(slug: string): number {
  if (typeof window === "undefined") return 0
  try {
    const raw = localStorage.getItem(cartStorageKey(slug))
    ...
  } catch {
    return 0
  }
}
```

**What is being replaced (the two sites, and the coupling between them):**

`components/dashboard/sidebar.tsx:57-73` — owns the read AND the DOM class:

```typescript
  const [dark, setDark] = useState(false)

  useEffect(() => {
    const saved = localStorage.getItem("theme")
    const isDark = saved === "dark" || (!saved && window.matchMedia("(prefers-color-scheme: dark)").matches)
    // eslint-disable-next-line react-hooks/set-state-in-effect -- SSR-safe mount-time hydration; refactor tracked in issue #99 follow-up
    setDark(isDark)
    document.documentElement.classList.toggle("dark", isDark)
  }, [])

  const toggleDark = () => {
    const next = !dark
    setDark(next)
    document.documentElement.classList.toggle("dark", next)
    localStorage.setItem("theme", next ? "dark" : "light")
  }
```

`components/dashboard/mobile-tab-bar.tsx:59-71` — reads the sidebar's DOM class, with an implicit
mount-ordering dependency the comment admits:

```typescript
  const [dark, setDark] = useState(false)

  // Reflect whatever theme the sidebar established (it owns the on-mount class
  // toggle); we only need the current value to label the toggle button.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- SSR-safe mount-time hydration; refactor tracked in issue #99 follow-up
    setDark(document.documentElement.classList.contains("dark"))
  }, [])
```

**RESEARCH's instruction:** one shared `useTheme()` removes both suppressions **and** the coupling.
Keep the `classList.toggle` side effect in an effect — only the `setDark` moves. The precedent is
`hooks/use-customer-session.ts:1-24` (#457): *extract, do not copy* — two independent readers is how
this class of bug comes back.

> **RESEARCH rule-shape row C:** the ESLint rule **traces into the call graph**, so "move the
> `setState` into a helper function" is not a fix. Rows D (`useSyncExternalStore` with
> `getServerSnapshot`) and E (derive during render) are the only two sanctioned shapes.

---

### `frontend/app/shop/auth/callback/page.tsx` (page/client island, request-response)

**No close in-repo analog** — the fix is rule-shape E (derive during render), which by definition
leaves no residue to copy.

**What is being replaced** (`app/shop/auth/callback/page.tsx:10-31`):

```typescript
function CallbackContent() {
  const searchParams = useSearchParams()
  const router = useRouter()
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const code = searchParams.get("code")
    if (!code) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- SSR-safe mount-time hydration; refactor tracked in issue #99 follow-up
      setError("No authorization code received.")
      return
    }

    handleCallback(code, searchParams.get("state")).then((profile) => {
      if (profile) {
        const returnTo = getAuthReturnUrl()
        router.replace(returnTo)
      } else {
        setError("Authentication failed. Please try again.")
      }
    })
  }, [searchParams, router])
```

**RESEARCH's shape:** `const code = searchParams.get("code")` during render; return the error branch
directly with no state at all for the missing-code case. The `handleCallback(...).then(...)` branch
**stays and is not flagged** (measured rule-shape row B: a promise continuation is not flagged).

**Error-render pattern to preserve verbatim** (`:33-44`) — the `<p className="text-sm text-red-600">`
block plus the `Back to shop` link. This is a user-visible good; the Incremental Betterment Doctrine
applies.

**Coverage gap this file creates:** RESEARCH's validation table flags the OAuth callback error path
as needing a **new Playwright block** — it is on #202's own acceptance list and is currently
uncovered.

---

### `frontend/hooks/use-customer-session.ts` (hook, pub-sub) + `lib/customer-session-store.ts`

**Analogs (composite):** `reveal.tsx:34-58` for the `useSyncExternalStore` triple;
`hooks/use-cart-count.ts:45-73` for the multi-listener subscribe/unsubscribe body.

**RESEARCH marks this HIGH risk — its own task, its own falsification.** Two consumers
(`StorefrontNav`, `PublicHeader`) and #465's single-flight refresh + rotation contract sit
underneath.

**What is being replaced** (`hooks/use-customer-session.ts:25-68`) — note the listener set that must
survive into `subscribe`:

```typescript
export function useCustomerSession() {
  const [profile, setProfile] = useState<CustomerProfile | null>(null)

  const checkSession = useCallback(async () => {
    const session = await getCustomerSession()
    setProfile(session?.profile || null)
  }, [])

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- SSR-safe mount-time hydration; refactor tracked in issue #99 follow-up
    checkSession()

    const onFocus = () => checkSession()
    const onVisibility = () => { if (document.visibilityState === "visible") checkSession() }
    const onStorage = (e: StorageEvent) => {
      if (e.key === "jtoye-customer-logged-in" || e.key === "jtoye-customer-expires-at") checkSession()
    }

    window.addEventListener("focus", onFocus)
    document.addEventListener("visibilitychange", onVisibility)
    window.addEventListener("storage", onStorage)

    // Also poll briefly after mount to catch the redirect scenario
    const timer = setInterval(checkSession, 1000)
    const cleanup = setTimeout(() => clearInterval(timer), 5000)

    return () => { /* remove all three, clearInterval, clearTimeout */ }
  }, [checkSession])

  return { profile, refresh: checkSession }
}
```

**Cleanup-symmetry pattern to copy** (`use-cart-count.ts:65-71`) — every `addEventListener` has its
`removeEventListener` in the returned teardown; `subscribe` must return the same shape.

**Contract that must not be dropped** (`use-customer-session.ts:20-23`), quoted because it is the
reason a naive store would be wrong:

```
 * Deliberately built on the ASYNC getCustomerSession() (server truth) rather than
 * the synchronous isLoggedIn() marker: the marker carries the access-token expiry
 * and only getCustomerSession() re-stamps it, so a marker-only reader on a public
 * surface goes stale and then lies.
```

`useSyncExternalStore`'s `getSnapshot` is **synchronous**, so the store must hold a cached
`CustomerProfile | null` refreshed by the async check, and `getSnapshot` must return a **stable
reference** between refreshes or React loops. `getServerSnapshot` returns `null`.

**Coverage gap:** RESEARCH's validation table flags the **storefront session pill** as needing a new
Playwright block (Wave 0).

---

### `frontend/e2e/onboarding-blocked-flow.spec.ts` (test, request-response)

**Analogs:** `storefront-ssr-seo.spec.ts:72-76` (`@desktop-only` with the reason stated) and
`dashboard-interface-corrections.spec.ts:97-105` (`@mobile-only`, with a measured justification).

**What is being replaced** (`onboarding-blocked-flow.spec.ts:109-119`):

```typescript
  test("bad company number -> fix inline -> re-run checks -> honest in-review", async ({ page }, testInfo) => {
    // Pin this stateful journey to a SINGLE project. vendor_onboarding is
    // UNIQUE(tenant_id): running the mobile + desktop projects as parallel workers
    // would race two concurrent create/submit flows onto the one onboarding this
    // tenant may have. ...
    test.skip(
      testInfo.project.name !== "desktop",
      "single-tenant onboarding journey pinned to the desktop project (UNIQUE(tenant_id) — no cross-worker race)"
    )
```

**Target shape** (RESEARCH Criterion 5) — move the reason into the title tag; delete the runtime pin
so the mobile project stops *enumerating* the block:

```typescript
test("bad company number -> fix inline -> re-run checks -> honest in-review @desktop-only", async ({ page }) => {
```

**Justification-comment pattern to keep** — `dashboard-interface-corrections.spec.ts:97-105` is the
in-repo model for "this tag is deliberate rather than a coverage gap", with a *measured* reason:

```typescript
/**
 * `@mobile-only` — the desktop project's `grepInvert` skips this file, and that
 * is deliberate rather than a coverage gap. Every block here pins its own 390px
 * `isMobile` viewport, so running them again under the desktop project measures
 * the same thing twice; what it does NOT duplicate is the load on a shared API
 * that rate-limits at 100 requests/minute per tenant. Measured 2026-08-04: ...
 */
```

**Do NOT touch** the two *other* `test.skip` calls in this file (`:120-124` password guard,
`:152-157` LIVE/terminal-tenant guard, `:169-171` no-shop guard) — those are genuine
"nobody-checked-this" skips and the ALLOW entry retires only because the project pin is gone.

**Mechanism reference:** `playwright.config.ts:73-112` — `grepInvert: /@desktop-only/` on the mobile
project (`:81`), `grepInvert: /@mobile-only/` on desktop (`:106`), each with the "a skip must mean
nobody checked this" rationale in place.

---

### `scripts/gates/e2e-skip-budget.conf` (config)

**Analog:** itself — the retirement protocol at `:25-29` is the instruction being executed.

**The edit** (RESEARCH Criterion 5):

```diff
-# 1 test x 2 projects. Blocked-onboarding journey needs a shop for the demo tenant
-# (DemoDataSeeder, dev profile). REMOVE WHEN: the seeder runs on the dev stack, or
-# scripts/seed-e2e-fixtures.sh is extended to cover it.
-ALLOW onboarding-blocked-flow.spec.ts
...
-MAX_SKIPS 8
+MAX_SKIPS 6                               # 4 stomp-relay (#304) + 2 vendor-refund (#61)
```

**Two corrections the planner must not skip:**

1. The deleted ALLOW's justification (`:46-48`, "needs a shop for the demo tenant") is **the wrong
   cause** — RESEARCH read the nightly report's own annotation and the desktop project *passed*,
   proving the fixture is present. Do not carry that text forward.
2. The arithmetic comment at `:51-56` ("Total = 8 (4 distinct tests x 2 projects)") is **stale** —
   the onboarding skip is mobile-only, so actual is 7. Replace with a measured figure and say how
   it was measured.

**Falsifiability note from RESEARCH:** keeping `MAX_SKIPS 8` still passes (7 ≤ 8), so **lowering the
ceiling is what makes the criterion falsifiable**. Verify by re-adding the ALLOW after the tag lands
and confirming S-3 goes red on the now-unmatched entry.

---

### `core-java/build.gradle.kts` (build config, batch)

**Analog:** its own `tasks.register<Test>("integrationTest")` block (`:202-284`) — the repo's model
for a build task whose configuration is justified by *measured arms recorded in the file*.

**Plugins block to extend** (`:1-5`):

```kotlin
plugins {
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    java
}
```

Add `jacoco` — a **core** Gradle plugin, so no version and no `dependencies` entry (RESEARCH:
JaCoCo 0.8.12 resolved and executed under Gradle 8.10.2 / JDK 21 on this tree).

**Build-dir trap this file already documents** (`:15`):

```kotlin
// Redirect build directory to 'build-local' to avoid permission issues with the default 'build'
layout.buildDirectory.set(file("build-local"))
```

Every JaCoCo path is therefore `core-java/build-local/...`. **Reading a report from
`core-java/build/` is a stale-artifact read** (RESEARCH Runtime State Inventory).

**The two suites, and why a `test`-only threshold is wrong** (`:182-190` and `:202-209`):

```kotlin
tasks.test {
    useJUnitPlatform {
        // Exclude Testcontainers-dependent tests by default ...
        if (!project.hasProperty("includeIntegration")) {
            excludeTags("testcontainers")
        }
    }
    ...
}

tasks.register<Test>("integrationTest") {
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("testcontainers")
    }
```

Both use `sourceSets["test"]`; the tags are complementary. RESEARCH Pitfall 5 measured the gap:
**LINE 62.12% (`test` only) vs 87.55% (aggregated) — 25.43 points.**

**Measurement-in-a-comment pattern to copy** (`:222-240`) — the repo's house style for a build
setting that must not be "simplified away":

```kotlin
    // KEPT ON MEASUREMENT, NOT ON FAITH — 27-04 T7, three arms, same 88 tagged classes:
    //
    //   forkEvery(4), post-fix   2337s   peak 209 threads (median 80)    SUCCESS 102/414, 0 fail
    //   forkEvery(0), post-fix   3601s   peak 859 threads (median 820)   OOM, hit the 1h ceiling
    //   ...
    // Do not "simplify" it away on the reasoning that the listener bug is fixed — that is the
    // specific wrong conclusion this block exists to prevent
```

The RESEARCH-supplied JaCoCo block already follows this style (`34-RESEARCH.md` "Criterion 4") and
carries both baselines plus the aggregate/unit-floor decision in the comment.

**Env-adaptive-with-a-recorded-reason pattern** (`:271-280`) — relevant if the threshold ever needs
to differ between CI and local; note the file's own warning that `availableProcessors()/4` was
**inert on CI**, a shape RESEARCH assumption **A2** repeats for coverage numbers ("measure the
baseline in CI once before fixing the number").

---

### `frontend/jest.config.js` (test config, batch)

**Analog:** itself — `collectCoverageFrom` is already declared (`:13-21`); only `coverageThreshold`
is missing.

```javascript
const customJestConfig = {
  setupFilesAfterEach: ...,
  collectCoverageFrom: [
    'app/**/*.{js,jsx,ts,tsx}',
    'components/**/*.{js,jsx,ts,tsx}',
    'lib/**/*.{js,jsx,ts,tsx}',
    'types/**/*.{js,jsx,ts,tsx}',
    '!**/*.d.ts',
    '!**/node_modules/**',
    '!**/.next/**',
  ],
  ...
}
```

> **`hooks/**` is NOT in `collectCoverageFrom`.** This phase adds/rewrites three hooks
> (`use-theme.ts`, `use-customer-session.ts`, and a session store). If the store lands in `lib/` it
> is counted; if a hook lands in `hooks/` it is **not**. Widening the glob will move the measured
> baseline — decide and record which, before fixing the threshold number (RESEARCH A2).

RESEARCH-supplied block (baseline measured 2026-08-28: Stmts 63.76 / Branch 57.06 / Funcs 60.71 /
Lines 65.10):

```javascript
coverageThreshold: {
  global: { statements: 61, branches: 54, functions: 58, lines: 62 },
},
```

**CI-side change:** `.github/workflows/ci-cd.yaml:132-134` must gain `--coverage`, and the existing
`--json --outputFile` must be preserved (the count oracle reuses that run — see below).

---

### `.github/workflows/ci-cd.yaml` (CI config, batch)

**Analog A — the Go/Jest step shapes in job `test`** (`:74-83` and `:132-134`):

```yaml
      - name: Run Go tests
        # -race dropped: requires cgo + gcc ...
        run: go test -v -coverprofile=coverage.out ./...
        working-directory: edge-go
        env:
          PATH: ${{ env.PATH }}:/home/runner/go/bin
```

```yaml
      # --json --outputFile is here so the count oracle below can reuse THIS run
      # rather than executing the suite a second time. The human-readable reporter
      # output is unaffected.
      - name: Run frontend Jest tests
        run: npm test -- --ci --watchAll=false --json --outputFile="$RUNNER_TEMP/jest-report.json"
        working-directory: frontend
```

The Go profile is already produced and already uploaded (`:154-167` uploads `edge-go/coverage.out`);
only the **consumer** is missing. Insert the threshold step immediately after `Run Go tests`, in the
same `working-directory: edge-go`.

**Analog B — the gate-step shape in job `ops-contracts`** (`:675-694`, and the trio at `:846-858`):

```yaml
      - name: Assert every terminal failure state has a detection path (27-00 F-9)
        run: |
          chmod +x ./scripts/check-terminal-states.sh
          ./scripts/check-terminal-states.sh
```

`check-ssr-coverage-contract.sh` belongs here: it is static (reads `page.tsx` files and a conf,
touches no runtime), and this job stays green on a docs-only PR.

**The job also carries the falsification record for its own gates** (`:840-846`) — copy the format:

```
      #      All three were shown to FAIL before being wired, against the exact defect
      #      each was filed for, then restored and verified by `git hash-object`:
      #        - mobile contract  : hasTouch removed from the mobile project  -> rc=1
      #        - base-URL contract: a spec-local fallback to an unpublished port -> rc=1
      #        - placeholders     : a <<MEASURED>> default left in application.yml -> rc=1
      #      Clean tree: all three rc=0. A gate observed only passing is not evidence.
```

**Analog C — the JaCoCo `.exec` hand-off between jobs.** `test` (`:62-69`) and `integration-tests`
(`:230-232`) are separate jobs. The existing artifact steps are the model:

```yaml
      - name: Upload test results
        if: always()
        uses: actions/upload-artifact@v7
        with:
          name: test-results
          path: |
            core-java/build-local/reports/tests/
            edge-go/coverage.out
```

```yaml
      - name: Upload integration test results
        if: always() && (github.event_name != 'pull_request' || steps.filter.outputs.integration == 'true')
        uses: actions/upload-artifact@v7
        with:
          name: integration-test-results
          path: core-java/build-local/reports/tests/integrationTest/
```

> **The path-filter is the hazard, and it is already written into these steps.** The
> `integration-tests` job is guarded by
> `github.event_name != 'pull_request' || steps.filter.outputs.integration == 'true'` (`:203`,
> `:211`, `:231`) and **reports SUCCESS while skipping**. RESEARCH Pitfall 5: a coverage gate that
> depends on it would be wrong on exactly the runs that skip. **A skipped integration job must VOID
> (exit 2), never pass.**

**Analog D — the stack-free `frontend-e2e` job's env** (`:303-331`). It sets only
`NEXT_PUBLIC_API_URL` / `NEXTAUTH_URL` / `NEXTAUTH_SECRET`. **`CORE_API_INTERNAL_URL` is absent**,
which is why the SSR fetch falls through to an unreachable `localhost:9090` → `defer` → browser stub
→ green (RESEARCH Pitfall 2). If the fixture server is adopted, `CORE_API_INTERNAL_URL` is added to
the `Start frontend (production build)` step's `env:` — a **runtime** lookup, so no rebuild is
needed (`docs/CHANGELOG.md:1821`).

Health-poll pattern already in that step (`:315-330`) is the model for waiting on a fixture server:

```yaml
        run: |
          npx next start -p 3000 &
          for i in $(seq 1 60); do
            if [ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:3000/shop)" = "200" ]; then
              echo "frontend up after ${i}s"; exit 0
            fi
            sleep 1
          done
          echo "frontend did not come up within 60s"; exit 1
```

**Analog E — nightly gate wiring** (`.github/workflows/e2e-nightly.yml:294-331`): the full suite runs
with `--reporter=json > e2e-artifacts/report.json || true`, then a step *asserts the report is real*,
then `bash scripts/check-e2e-skip-budget.sh`. This is where the re-earned skip budget is proved
(RESEARCH Pitfall 4 — sequence the full-suite run **last**, after every spec edit).

---

### `frontend/e2e/dashboard-mobile.spec.ts` (test, request-response)

**Analog:** `frontend/e2e/dashboard-interface-corrections.spec.ts` — **3 `vendorLogin` refs, 0
`.route(` calls** (re-confirmed by RESEARCH). This is the same 11-ish-route dashboard surface driven
with real auth and real data.

**Real-login pattern** (`dashboard-interface-corrections.spec.ts:49-63`) — the tighter of the two in
the repo; `dashboard-mobile.spec.ts:100-140` is the more defensive variant:

```typescript
async function vendorLogin(page: Page) {
  skipWithoutVendorPassword()
  await page.goto("/auth/signin", { waitUntil: "domcontentloaded" })
  const sso = page.getByRole("button", { name: /sign in with keycloak/i })
  await sso.waitFor({ state: "visible", timeout: 15_000 })
  await sso.click()
  await page.waitForURL(/(openid-connect|\/dashboard)/, { timeout: 30_000 })
  if (!page.url().includes("/dashboard")) {
    await page.fill("#username", VENDOR_USERNAME)
    await page.fill("#password", VENDOR_PASSWORD)
    await page.click("#kc-login")
  }
  await page.waitForURL(/\/dashboard/, { timeout: 30_000 })
}
```

**Why real data matters — the vacuous-pass this file already documents** (`:15-20`):

```
 * AUTH + DATA ARE REAL. The council's own numbers were reproduced only once the
 * API calls actually succeeded: with the vendor signed in but the API refusing
 * the request, the switcher falls back to its zero-grant state, `<select>` never
 * renders, and `#shop-context-select` reads 0 — a "pass" on the duplicate-id
 * assertion over a page that is not working. So this spec asserts the control is
 * PRESENT before counting it.
```

**Streaming-buffer scoping — needed by BOTH specs** (`dashboard-interface-corrections.spec.ts:65-75`,
mirrored at `dashboard-mobile.spec.ts:91-93`):

```typescript
const LIVE = "body > div:not([hidden])"
```

```typescript
function live(page: Page) {
  return page.locator("body > div:not([hidden])")
}
```

**The 9 stubs being replaced** (`dashboard-mobile.spec.ts:261-302`) — registered in
`setupStubs(context)`, ordering-sensitive:

```typescript
async function setupStubs(context: BrowserContext) {
  // Order matters: Playwright matches the LAST-registered handler first, so we
  // register the broad catch-all FIRST and specific handlers AFTER so they win.
  await context.route(`${API}/api/v1/**`, ...)                                // :267
  await context.route(`${API}/api/v1/shops**`, ...)                            // :275
  await context.route(`${API}/api/v1/financial-transactions/summary`, ...)     // :280
  await context.route(`${API}/api/v1/orders?**`, ...)                          // :284
  await context.route(`${API}/api/v1/orders/*/detail`, ...)                    // :288
  await context.route(`${API}/api/v1/onboarding/**`, ...)                      // :294
  await context.route(`${API}/api/v1/orders/stream**`, (route) => route.abort())  // :300
  await context.route("**/ws**", (route) => route.abort())                     // :301
}
// + one more inside the 375px block: `${API}/api/v1/staff/me`                 // :433
```

**The 375px block that already exists** (`:424-425`) — extend this, do not move the project viewport:

```typescript
test.describe("Dashboard mobile shell (375px) — MOBL-01 + switcher regression", () => {
  test.use({ viewport: { width: 375, height: 812 }, isMobile: true })
```

Its overflow assertion (`:460-467`) is the one to lift into the 11-route loop:

```typescript
    expect(geom.docScrollWidth).toBeLessThanOrEqual(geom.viewportWidth + 1)
```

**Per-describe viewport pinning, and why** (`:304-309`):

```typescript
test.describe("Dashboard mobile shell (390px)", () => {
  // This is inherently a 390px phone-shell contract (bottom tab bar visible,
  // 256px sidebar hidden). Pin the viewport so it is exercised correctly under
  // BOTH the `mobile` and `desktop` Playwright projects — at a 1440px desktop
  // viewport the tab bar hides and the sidebar shows, which would be a false red.
  test.use({ viewport: { width: 390, height: 844 }, isMobile: true })
```

> **Do NOT change `playwright.config.ts`'s mobile project from 390×844 to 375** — RESEARCH's
> Incremental Betterment row: it is documented, `mobile-instrument-contract.spec.ts` asserts it, and
> every mobile perf baseline is measured against it. **Additive extension only.**

**Base-URL note:** this spec declares `const BASE = process.env.PLAYWRIGHT_BASE_URL ||
"http://localhost:3000"` (`:34`) — a *matching* local fallback, which
`check-e2e-baseurl-contract.sh` permits (measured today: 22 specs, 14 local fallbacks, 0 divergent).
`dashboard-interface-corrections.spec.ts` uses relative paths instead, which is the cleaner form.

---

### `frontend/app/track/page.tsx` and `app/shop/[slug]/orders/[orderNumber]/page.tsx` (discretionary SSR conversions)

Both are `"use client"` today (verified: line 1 of each; 505 and 365 lines respectively). RESEARCH
Open Question 2 recommends **zero to two** conversions as a demonstration of the pattern under the
guard, with every remaining client route listed **with a reason**.

**Analog (public, unauthenticated):** `frontend/app/shop/page.tsx` + `app/shop/shop-discovery-client.tsx`.

Server page imports + metadata + body (`app/shop/page.tsx:1-10`, `:28-57`, `:59-101`):

```typescript
import type { Metadata } from "next"
import { headers } from "next/headers"
import { loadShopList } from "@/lib/storefront-server"
import { resolvePublicOrigin } from "@/lib/public-origin"
import { serialiseJsonLd, shopListStructuredData } from "@/lib/structured-data"
import { ShopDiscoveryClient, SHOPS_PAGE_SIZE } from "./shop-discovery-client"
```

```typescript
export default async function ShopDiscoveryPage({
  searchParams,
}: {
  searchParams: Promise<{ q?: string | string[] }>
}) {
  const params = await searchParams
  // `?q=` can legally arrive repeated; the page has always treated it as one
  // term, so take the first rather than joining them into a nonsense query.
  const raw = Array.isArray(params.q) ? params.q[0] : params.q
  const q = (raw ?? "").trim()

  const result = await loadShopList({ page: 0, size: SHOPS_PAGE_SIZE, q })
  const initial = result.state === "ok" ? result.data : null
  ...
  const nonce = (await headers()).get("x-nonce") ?? undefined
  const origin = resolvePublicOrigin()
  const jsonLd = initial ? shopListStructuredData(initial.content ?? [], origin) : null

  return (
    <>
      {jsonLd && (
        <script type="application/ld+json" nonce={nonce}
                dangerouslySetInnerHTML={{ __html: serialiseJsonLd(jsonLd) }} />
      )}
      <ShopDiscoveryClient initial={initial} initialQuery={q}
                           initialInterpretation={initialInterpretation} />
    </>
  )
}
```

> **The repeated-`searchParams` handling at `:64-68` is RESEARCH's ASVS V5 control** — copy it into
> any new SSR page that reads a query parameter.

**The three-valued loader to EXTEND, not duplicate** (`lib/storefront-server.ts:70-99`):

```typescript
export type StorefrontLoad<T> =
  | { state: "ok"; data: T; headers?: Headers }
  | { state: "notfound" }
  | { state: "defer" }

const NO_STORE: RequestInit = { cache: "no-store" }

/**
 * One fetch, decoded, never throwing.
 *
 * `notfound` is returned ONLY for a real 404. Everything else that is not a 2xx
 * — including 429 — becomes `defer`, because the caller must not present a
 * non-answer as an authoritative one.
 */
async function getJson<T>(path: string): Promise<StorefrontLoad<T>> {
  try {
    const res = await fetch(`${coreBaseUrl()}${path}`, NO_STORE)
    if (res.status === 404) return { state: "notfound" }
    if (!res.ok) return { state: "defer" }
    const body = (await res.json()) as T
    if (body == null) return { state: "defer" }
    return { state: "ok", data: body, headers: res.headers }
  } catch {
    // DNS / connect / timeout / malformed JSON. Not an authoritative answer.
    return { state: "defer" }
  }
}
```

Also copy `getOptional<T>(path, fallback)` (`:108-111`) for sub-resources that must not downgrade the
page, and the `cache(...)` wrapper (`:148-151`) for any loader called twice per request
(`generateMetadata` + the page body).

**notFound / defer branching** (`app/shop/[slug]/page.tsx:133-143`):

```typescript
  const result = await loadShopDetail(slug)

  // A slug that does not exist answers a REAL 404 rather than a 200 carrying
  // "Shop not found" — a soft 404 keeps dead storefronts in the index.
  if (result.state === "notfound") notFound()

  // `defer` (429 / 5xx / DNS / timeout) hands over to the island with no seed,
  // which then runs exactly the retry-and-backoff path it always did.
  const initial = result.state === "ok" ? result.data : null
```

**Island seeding — the three non-obvious requirements** (`app/shop/shop-discovery-client.tsx:209-212`,
`:245`, `:307-313`):

```typescript
  const [shops, setShops] = useState<PublicShop[]>(initial?.content ?? [])
  // Server-seeded content is not "loading": swapping real HTML for a skeleton on
  // hydration is exactly the layout shift this change exists to remove.
  const [loading, setLoading] = useState(initial === null)
```

```typescript
  // The server already answered for (page 0, initialQuery). One-shot, so the
  // mount effect does not immediately refetch what is already on screen — but
  // any later page or query change still fetches normally.
  const serverSeeded = useRef(initial !== null)
```

```typescript
  useEffect(() => {
    if (serverSeeded.current) {
      serverSeeded.current = false
      return
    }
    fetchShops()
  }, [fetchShops])
```

Plus the "anything the server derived must travel with the data" prop contract
(`shop-discovery-client.tsx:187-204`) — `initialInterpretation` is **required, not optional**,
because the suppressed mount fetch would otherwise never correct a stale heading.

**Analog (authenticated):** `frontend/app/shop/orders/page.tsx:40-69` — the whole file is the
pattern, and it is short enough to read in full:

```typescript
export default async function CustomerOrdersPage() {
  const jar = await cookies()
  const access = jar.get(ACCESS_COOKIE)?.value
  const refresh = jar.get(REFRESH_COOKIE)?.value
  const email = displayEmailFromIdToken(jar.get(ID_COOKIE)?.value)

  // No session material at all — an anonymous visitor or a fully aged-out
  // session. Answer from the server: the wall is in the first paint instead of
  // behind a spinner that resolves into it.
  if (!access && !refresh) {
    return <CustomerSignInPrompt message="..." nextPath="/shop/orders" />
  }

  // Access token expired, refresh token still alive (#465 ...). Renewing means
  // SETTING cookies, which Next only permits in a route handler or server action
  // — not here. Hand to the island ... `initial: null` is precisely this case.
  if (!access) {
    return <OrdersClient initial={null} email={email} />
  }

  const initial = await loadCustomerOrders(access)
  return <OrdersClient initial={initial} email={email} />
}
```

Its metadata block (`:32-38`) carries the per-customer noindex:

```typescript
export const metadata: Metadata = {
  title: "My Orders — J'Toye",
  // A signed-in, per-customer surface. Nothing here should ever be indexed, and
  // there is no canonical version of it to point a crawler at.
  robots: { index: false, follow: false },
}
```

---

## Shared Patterns

### 1. VOID-on-unknown (exit 2), fail-closed — every new gate

**Source:** `scripts/check-e2e-skip-budget.sh:75-80` (contract), `:97` (helper), `:180-182`
(zero-discovery); `scripts/check-playwright-mobile-contract.sh:44-47`, `:80`
**Apply to:** `check-ssr-coverage-contract.sh`, the Go coverage step, the JaCoCo aggregate step

```bash
# EXIT CODES
#   0 = every skip is declared and within budget
#   1 = over budget, an undeclared skip, or a stale ALLOW
#   2 = VOID — no report, unparseable, zero tests, missing jq, a bad config directive, ...
#       "Found nothing" is never "clean".
```

RESEARCH's Go step (already written in the same idiom):

```bash
total=$(go tool cover -func=coverage.out | awk '/^total:/ {gsub(/%/,"",$3); print $3}'); rc=$?
[ "$rc" -eq 0 ] || { echo "VOID: go tool cover failed"; exit 2; }
[ -n "$total" ] || { echo "VOID: no total line — empty or unparseable profile"; exit 2; }
```

Note `rc=$?` **on the same line** as the assignment — the project's recorded trap
("`$?` after an echo reports THAT command's status"). The skip-budget gate does the same at
`:146`: `TREE_DIGEST=$(bash "$DIGEST_SCRIPT"); digest_rc=$?`.

### 2. Every new gate must be wired into CI in the same PR

**Source:** `scripts/check-gate-enforcement.sh:24-34` + `scripts/gates/gate-enforcement.conf:1-17`
**Apply to:** `check-ssr-coverage-contract.sh`

```
# WHAT IT ASSERTS
#   For every scripts/check-*.sh:
#     - if the script is STATIC (invokes no runtime binary), it MUST be referenced by
#       at least one file under .github/workflows/;
#     - if it is RUNTIME-DEPENDENT, it MUST carry an explicit, reasoned entry in
#       scripts/gates/gate-enforcement.conf.
#
#   Default-deny: a new gate that is neither wired nor declared FAILS.
```

The conf's own bar for an entry (`gate-enforcement.conf:11-17`) rules the exemption route out here:

```
# The bar for an entry is NOT "this gate is inconvenient in CI". It is "this gate
# inspects something a GitHub-hosted runner does not have, so it could only ever
# exit 2 (VOID) there" — and a permanently-VOID required job is worse than no job
```

**Note the anti-pattern the conf itself documents at `:27-33`** — `check-openapi-snapshot-fresh.sh`
was *planned* as an exemption and wired into `e2e-nightly.yml` instead. **"Wiring beats exempting
whenever a real runtime is available."** The SSR gate is static, so it goes in `ops-contracts`.

**Self-reference trap** (`gate-enforcement.conf` header + `ci-cd.yaml:872-877`): a gate that names
the tokens it forbids can satisfy itself. `check-playwright-mobile-contract.sh:57-62` shows the fix —
strip comments before matching:

```awk
        # Strip line comments BEFORE matching. Without this a comment merely
        # MENTIONING hasTouch satisfies the gate — and this config is heavily
        # commented ... A gate that its own rationale can satisfy is vacuous.
        code = $0
        sub(/\/\/.*/, "", code)
```

This is directly relevant: `app/shop/page.tsx`, `app/shop/[slug]/page.tsx`,
`app/shop/orders/page.tsx` and `app/unsubscribe/page.tsx` each **mention `"use client"` in prose**
about their own conversion (RESEARCH Pitfall 1). The classifier must strip leading comments and
require the directive to be the **first statement**.

### 3. The falsification record lives beside the thing it certifies

**Source:** `.github/workflows/ci-cd.yaml:840-846`; `core-java/build.gradle.kts:222-240`;
`frontend/e2e/storefront-ssr-seo.spec.ts:14-26`
**Apply to:** every new gate, every threshold, every new spec block

Three in-repo shapes, all the same doctrine — record BOTH directions' real output where the next
reader will find it:

- **CI comment** listing what each gate was broken with and the resulting rc (ci-cd.yaml).
- **Build-file comment** carrying the measured arms table (build.gradle.kts).
- **Spec docblock** carrying the pre-fix measurement so the assertion is falsifiable by
  construction (storefront-ssr-seo.spec.ts, and its per-assertion messages at `:80-88`).

### 4. `docs/metrics.json` is the single source of truth for test counts

**Source:** `.github/workflows/ci-cd.yaml:136-152`; `CLAUDE.md` constraints
**Apply to:** any plan that adds or deletes a test block

```yaml
      - name: Verify docs/metrics.json against the Jest runner (#291)
        if: always()
        run: bash scripts/check-test-count-oracle.sh jest --report "$RUNNER_TEMP/jest-report.json"

      - name: Verify docs/metrics.json against the Playwright declaration list (#291)
        if: always()
        run: bash scripts/check-test-count-oracle.sh playwright
```

Four gates guard this: `docs-freshness.sh` (tree → manifest), `check-doc-metrics.sh` (prose →
manifest), and the two oracles above (runner → manifest). Current: `playwright_blocks 113`,
`playwright_specs 22`, `total_logical_invocations 3188`.

**Regenerate with `scripts/docs-freshness.sh --write`, never arithmetic** (recorded trap: the counter
greps literal `it(` / `test(`). Then update prose in `README.md`, `AGENTS.md`, `CLAUDE.md`.

**Adding an `@desktop-only` tag does NOT change the static block count.** Adding a spec file or a
test block does — and this phase adds at least three new Playwright blocks (SSR fail arm, session
pill, OAuth callback error) plus new Jest hook tests.

### 5. A skip must mean "nobody checked this"

**Source:** `frontend/playwright.config.ts:76-81` and `:100-106`
**Apply to:** `onboarding-blocked-flow.spec.ts`, and any new block that is project-specific

```typescript
      // #420: exclude blocks that are desktop-by-design so they are never ENUMERATED
      // here. Previously they were enumerated and then skipped at runtime, which put 2
      // permanent entries into the suite's skip count for surface that is fully covered
      // by the desktop project. A skip must mean "nobody checked this"; it cannot also
      // mean "not applicable here" and stay useful.
      grepInvert: /@desktop-only/,
```

### 6. Server components must forward the caller's identity (RLS/ASVS V3/V4)

**Source:** `frontend/app/shop/orders/page.tsx:22-29`, `:41-66`; `lib/storefront-server.ts:17-28`
**Apply to:** any authenticated SSR conversion

```
 * The session's access token is an HttpOnly cookie, which a server component can
 * read — so the orders can be fetched and rendered as HTML before the JS bundle
 * has even arrived. ...
 * The root layout already sets `dynamic = "force-dynamic"` app-wide for the CSP
 * nonce, and reading cookies() is itself dynamic, so there is nothing to cache
 * and no per-customer data can leak into a shared render.
```

Three binding rules from RESEARCH's Security Domain:

- **V4** — forward the **caller's** bearer token / HttpOnly cookies. A service account on the SSR
  path bypasses the tenant wall for every visitor.
- **V3** — there is **no** token-refresh path on the SSR fetch (`lib/api-client.ts`'s single-flight
  interceptor is browser-only). A missing/expired token must `defer`, never error, never empty.
- **V8** — never add `revalidate` and never remove `force-dynamic` on a per-user route.

### 7. `getJson` never throws — preserve the contract (ASVS V7)

**Source:** `lib/storefront-server.ts:86-99`
**Apply to:** every SSR loader

A thrown error in a server component renders the route's error boundary and loses the page chrome.
Every failure mode — 404, 429, 5xx, DNS, timeout, malformed JSON — resolves to a state value.

### 8. Read the runtime, not the artefact directory

**Source:** `core-java/build.gradle.kts:15`; RESEARCH Runtime State Inventory
**Apply to:** JaCoCo report reads, any post-conversion E2E

- JaCoCo reports live in **`core-java/build-local/reports/jacoco/`**. `core-java/build/` is stale;
  reading it is a stale-artifact read.
- Any SSR conversion needs a **frontend rebuild** before E2E — `docker compose start` does not
  rebuild. Then `scripts/check-runtime-freshness.sh` + `scripts/check-branch-behind-base.sh`.
- `docker-compose.full-stack.yml` publishes core-java as a **range** `9090-9091:9090` (#671). Confirm
  `docker ps` shows `0.0.0.0:9090->9090/tcp` before trusting any live-stack measurement.

---

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `frontend/e2e/helpers/ssr-fixture-server.ts` (the `node:http` server itself) | test utility | request-response | **No in-repo HTTP fixture server exists.** Every stack-free path in this repo stubs at the *browser* (`context.route`), which is precisely the instrument RESEARCH proves cannot see the SSR path. `public-surface.ts` supplies the fixture *data* and the module *shape*, but the transport is new. RESEARCH assumption **A6** (MEDIUM): the env-var seam is verified, the server was **not built or run** — only the *unreachable* case (`:3105`) was proven. Treat as new code with its own falsification. |
| `frontend/lib/customer-session-store.ts` (a synchronous-snapshot store over an async source) | store module | pub-sub | `reveal.tsx` subscribes to a **synchronous** `matchMedia`; `use-cart-count.ts` subscribes to events but keeps `useState`. Neither solves "async truth behind a synchronous `getSnapshot` with a stable reference". Composite of both plus new caching logic. RESEARCH rates this site **HIGH** risk (two consumers, #465's rotation contract underneath) and asks for its own task and its own falsification. |

**Also recorded N/A rather than silently dropped** (RESEARCH "Alternatives considered", CLAUDE.md
roster rule): **mcp-server coverage.** `@vitest/coverage-v8` is absent, is a genuinely new npm
dependency, was **not verified on any registry** (slopcheck unavailable — blocked by
`block-base-python.py`), and #110 / criterion 4 names only JaCoCo, Go and Jest. If adopted it must be
tagged `[ASSUMED]` behind a `checkpoint:human-verify` task. The zero-new-package path avoids the gate
entirely and is the recommendation.

---

## Metadata

**Analog search scope:**
`frontend/app/`, `frontend/lib/`, `frontend/hooks/`, `frontend/components/dashboard/`,
`frontend/components/marketing/`, `frontend/e2e/` (+ `helpers/`), `frontend/eslint.config.mjs`,
`frontend/jest.config.js`, `frontend/playwright.config.ts`, `scripts/`, `scripts/gates/`,
`core-java/build.gradle.kts`, `.github/workflows/ci-cd.yaml`, `.github/workflows/e2e-nightly.yml`

**Files scanned:** 38 `page.tsx` enumerated; 22 Playwright specs + 1 helper listed; 36
`scripts/check-*.sh` listed; **24 files read in full or in targeted ranges.**

**Search-instrument note:** `rg -uu` was used for the `#99 follow-up` marker sweep and returned
exactly **4** hits in source (`hooks/use-customer-session.ts:35`,
`app/shop/auth/callback/page.tsx:18`, `components/dashboard/sidebar.tsx:63`,
`components/dashboard/mobile-tab-bar.tsx:64`), matching RESEARCH. The same `-uu` sweep for
`useSyncExternalStore` returned mostly `.next/` build artefacts — the one real source hit is
`components/marketing/reveal.tsx`. Recorded because an unfiltered count here would badly
misrepresent how much of this pattern already exists.

**Pattern extraction date:** 2026-08-28
