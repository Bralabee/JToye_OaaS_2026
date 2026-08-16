/**
 * Cookie/storage consent — a CLIENT-ONLY store and gate (LGL-01, D-05).
 *
 * ── WHY THERE IS NO TABLE AND NO MIGRATION ──────────────────────────────────
 * This was the phase's open question and it is settled. There is no
 * `notification_consent` table to extend: V54 creates `notification_suppression`
 * and `marketing_opt_in`, and BOTH carry `tenant_id UUID NOT NULL` +
 * `recipient TEXT NOT NULL` under FORCE RLS with a `tenant_id =
 * current_tenant_id()` policy. A cookie decision is taken by an ANONYMOUS
 * visitor on the platform origin before any tenant context or identity exists —
 * there is no value for either NOT NULL column, and with no GUC set the row
 * would be invisible to every subsequent read. It would be a write nobody could
 * ever read back.
 *
 * The second reason is the decisive one: the object being gated is a
 * BROWSER-EXECUTED SCRIPT. A server row cannot stop a `<script>` from running.
 * The decision has to live where the execution does. The correct tier is the
 * client, and the tampering risk that buys is accepted (T-31-16-02) precisely
 * because nothing security-relevant is gated on it — see `loadWhenAllowed`.
 *
 * ── PECR ────────────────────────────────────────────────────────────────────
 * `localStorage` is storage on terminal equipment exactly as a cookie is, so
 * everything here is disclosed as "cookies and browser storage" and never as
 * "cookies only". The dismissal key below is itself strictly-necessary storage:
 * it records a preference the user expressed by dismissing the notice.
 *
 * Shape borrowed from `lib/shop-context.ts` (SSR guard + same-tab CustomEvent +
 * dual subscribe); the private-mode try/catch is borrowed from
 * `lib/cart-identity.ts`, because `shop-context.ts` does NOT guard it and would
 * throw in Safari private mode. Two analogs, deliberately, one per property.
 */

/**
 * The dismissal key, holding a POLICY VERSION string. ONE definition, exported —
 * the `CART_KEY_PREFIX` rule from `cart-identity.ts:38-43`: two copies of a
 * storage key is how a "clear everything" quietly starts missing keys.
 *
 * Disclosed in the cookie policy: an undisclosed key makes that policy wrong
 * under PECR, which governs the storage and not the technology.
 */
export const COOKIE_NOTICE_ACK_KEY = "jtoye-cookie-notice-ack"

/** Where per-category choices live. Separate from the ack: dismissing a notice
 *  is not the same act as choosing, and conflating them would let a dismissal
 *  read as consent. */
const CONSENT_CHOICES_KEY = "jtoye-cookie-consent-choices"

/** Same-tab broadcast. The native `storage` event fires only in OTHER tabs. */
const CONSENT_CHANGE_EVENT = "jtoye-consent:change"

/**
 * The version of the disclosure the visitor is acknowledging.
 *
 * ── THE VERSION-COMPARISON RULE, AND WHY (no analog existed to copy) ────────
 * Measured: no storage key anywhere in this repo is versioned, so there was
 * nothing to imitate and this is a designed decision.
 *
 * The rule is EXACT MATCH — any value other than the current one re-shows the
 * notice. It is deliberately NOT a semver "patch changes do not re-prompt"
 * comparison. That variant needs somebody to decide, per edit, whether a change
 * was "material" enough to re-prompt, and that judgement has no owner here; the
 * failure mode is silent (a materially changed disclosure that never re-shows)
 * and there is no test that could catch it, because the rule would be a matter
 * of opinion. Exact match makes bumping the constant the single, visible act
 * that re-prompts everyone, and an unnecessary re-prompt is a far cheaper error
 * than an un-disclosed change.
 *
 * Dated rather than numbered so the value is self-describing in a support
 * conversation and in the policy page's "last updated" line.
 */
export const COOKIE_POLICY_VERSION = "2026-08-16"

/** A consent category. `essential` categories are never a choice. */
export interface ConsentCategory {
  /** Stable id used by `isAllowed` / `loadWhenAllowed`. */
  id: string
  /** Strictly necessary: always allowed, never presented as a choice. */
  essential: boolean
  /** Human label for the (currently dormant) banner. */
  label: string
  /** Plain-English purpose for the (currently dormant) banner. */
  purpose: string
}

/**
 * THE SHIPPED CONFIGURATION — deliberately ZERO non-essential categories.
 *
 * Measured on this tree with a positive control: zero analytics/tag scripts and
 * zero non-essential cookies across `app`, `components` and `lib`. So there is
 * nothing to ask about, and asking anyway would be a consent theatre that PECR
 * does not require and that trains people to click through.
 *
 * Adding a non-essential entry here is what turns the notice into a banner; the
 * banner's contract is already built and tested (`consent-banner.tsx`), so that
 * change is a one-line edit, not a project.
 */
export const SHIPPED_CATEGORIES: readonly ConsentCategory[] = [
  {
    id: "strictly-necessary",
    essential: true,
    label: "Strictly necessary",
    purpose:
      "Keeps you signed in, remembers what is in your basket, and keeps your order secure.",
  },
]

/** Live registry. Seeded with the shipped configuration at module load. */
const registry = new Map<string, ConsentCategory>()
for (const category of SHIPPED_CATEGORIES) registry.set(category.id, category)

type Choice = "accepted" | "rejected"

function hasWindow(): boolean {
  return typeof window !== "undefined"
}

/** Every read is guarded: `localStorage` THROWS in Safari private mode. */
function readRaw(key: string): string | null {
  if (!hasWindow()) return null
  try {
    return window.localStorage.getItem(key)
  } catch {
    return null
  }
}

function writeRaw(key: string, value: string): void {
  if (!hasWindow()) return
  try {
    window.localStorage.setItem(key, value)
  } catch {
    /* private mode / quota — the preference simply is not persisted */
  }
}

function readChoices(): Record<string, Choice> {
  const raw = readRaw(CONSENT_CHOICES_KEY)
  if (!raw) return {}
  try {
    const parsed: unknown = JSON.parse(raw)
    // A hand-edited or corrupted value must not become an allow. Anything that
    // is not a plain object reads as "no choices recorded" — fail closed.
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return {}
    return parsed as Record<string, Choice>
  } catch {
    return {}
  }
}

function writeChoices(choices: Record<string, Choice>): void {
  writeRaw(CONSENT_CHOICES_KEY, JSON.stringify(choices))
}

/** Broadcast a same-tab change; `storage` alone would only reach other tabs. */
function broadcast(): void {
  if (!hasWindow()) return
  window.dispatchEvent(new Event(CONSENT_CHANGE_EVENT))
}

/**
 * Register a category. Returns an unregister function.
 *
 * The unregister return is not a test affordance — it is what lets a route-level
 * feature register a category while mounted and withdraw it on unmount without
 * leaving a permitted orphan behind (asserted: an id that is no longer
 * registered is no longer allowed, even with a stored accept).
 */
export function register(category: ConsentCategory): () => void {
  registry.set(category.id, category)
  return () => {
    // Never unregister part of the shipped configuration.
    if (SHIPPED_CATEGORIES.some((c) => c.id === category.id)) return
    registry.delete(category.id)
  }
}

/** Every currently registered category, shipped and dynamic alike. */
export function registeredCategories(): ConsentCategory[] {
  return Array.from(registry.values())
}

/** The non-essential categories — i.e. the ones that need a choice. Empty today. */
export function choosableCategories(): ConsentCategory[] {
  return registeredCategories().filter((c) => !c.essential)
}

/**
 * May this category's storage/scripts be used?
 *
 * FAILS CLOSED on every uncertain branch: an unregistered id, an unparseable
 * store, a throwing `localStorage`, or a server render all answer `false`. The
 * one branch that answers `true` without a stored choice is an ESSENTIAL
 * category, which by definition is not a choice.
 */
export function isAllowed(id: string): boolean {
  const category = registry.get(id)
  if (!category) return false
  if (category.essential) return true
  return readChoices()[id] === "accepted"
}

/**
 * The gate. Runs `load` only if the category is allowed; reports whether it did.
 *
 * NOTHING IN THE SHIPPED CONFIGURATION CALLS THIS TODAY — that is the point, and
 * this comment is here so a future reader does not delete an "unused" export.
 * There are zero non-essential scripts on this tree, which is exactly why the
 * gate is proven in tests against a FIXTURE category instead: a gate over zero
 * categories cannot fail as shipped, so its own green suite would be worthless.
 *
 * Scope: this gates OPTIONAL script/storage loading only. Nothing with a
 * security consequence may be gated on it, because the value is client-side and
 * therefore visitor-tamperable (T-31-16-02). If a future category ever gates
 * something security-relevant, that decision reopens the threat row.
 */
export function loadWhenAllowed(id: string, load: () => void): boolean {
  if (!isAllowed(id)) return false
  load()
  return true
}

function recordChoice(id: string, choice: Choice): void {
  const choices = readChoices()
  choices[id] = choice
  writeChoices(choices)
  broadcast()
}

/** Record consent for a category. */
export function accept(id: string): void {
  recordChoice(id, "accepted")
}

/** Withdraw consent for a category. Withdrawal must be as easy as granting. */
export function reject(id: string): void {
  recordChoice(id, "rejected")
}

/** The acknowledged policy version, or null when nothing is stored. */
export function readNoticeAck(): string | null {
  return readRaw(COOKIE_NOTICE_ACK_KEY)
}

/**
 * Should the notice be shown?
 *
 * SSR answers FALSE. The server cannot know whether this visitor already
 * dismissed the notice, and painting it server-side only to remove it on hydrate
 * is a flash of content — worse than a notice that appears a frame late. Because
 * the notice is `position: fixed` and out of flow, appearing late costs no
 * layout shift.
 */
export function shouldShowCookieNotice(): boolean {
  if (!hasWindow()) return false
  return readNoticeAck() !== COOKIE_POLICY_VERSION
}

/** Dismiss the notice by storing the CURRENT policy version — see the rule above. */
export function acknowledgeCookieNotice(): void {
  writeRaw(COOKIE_NOTICE_ACK_KEY, COOKIE_POLICY_VERSION)
  broadcast()
}

/**
 * Subscribe to consent/dismissal changes. Returns an unsubscribe function.
 *
 * Listens to BOTH channels, and both are load-bearing: the same-tab
 * `CustomEvent` (the browser `storage` event does not fire in the tab that wrote
 * the value, so without this a notice dismissed here would not re-render here)
 * and the cross-tab `storage` event (dismiss in one tab, stay dismissed in the
 * others).
 */
export function onChange(cb: () => void): () => void {
  if (!hasWindow()) return () => {}
  window.addEventListener(CONSENT_CHANGE_EVENT, cb)
  window.addEventListener("storage", cb)
  return () => {
    window.removeEventListener(CONSENT_CHANGE_EVENT, cb)
    window.removeEventListener("storage", cb)
  }
}
