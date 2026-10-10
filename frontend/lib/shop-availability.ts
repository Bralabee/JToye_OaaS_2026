import type { PublicShop } from "@/types/storefront"

/**
 * Whether a shop is taking orders, READ from the server (37-22; UI-SPEC K3 customer side; D-15,
 * D-25; theme 2: "a status claim reads the same source as the action").
 *
 * The server's `ShopAvailabilityPolicy` (37-20) decides once, in UK time, from the pause and the
 * structured hours, and the checkout enforces that same decision (409 `shop-closed`). Every
 * customer surface — the shop page pill and notice, the /shop cards, the landing row, checkout —
 * renders THIS reading of the server's fields and computes no open or closed state of its own.
 * Before 37-22 the storefront re-parsed the free-text `openingHours` (`isOpenNow`), and the two
 * disagreed: a paused shop with open hours read "Open now" while checkout refused, and an
 * unreadable NEEDS_REVIEW day read "Closed" while checkout took the order.
 *
 * Rules, in order:
 *  - a pause the server reports (`pausedIndefinitely`, or a `pausedUntil` it only sends while the
 *    pause is in force) is "paused", whatever `openNow` says — "never Open now while paused";
 *  - `openNow` ABSENT (an older backend, or a payload without the field) is "unknown", which
 *    every surface renders as NO claim at all, neither "Open" nor "Closed";
 *  - otherwise `openNow` itself.
 *
 * No clock is consulted to decide the state. The clock is used only to WORD a time already given
 * ("Back at 18:40" when it is today, the weekday otherwise), always in Europe/London so the
 * server-rendered HTML and the hydrated page agree and a UTC container prints UK times.
 */
export type ShopAvailability =
  | { kind: "unknown" }
  | { kind: "open" }
  | { kind: "paused"; reopensAt: string | null }
  | { kind: "closed"; nextOpenAt: string | null }

type AvailabilityFields = Pick<PublicShop, "openNow" | "nextOpenAt" | "pausedUntil" | "pausedIndefinitely">

export function readShopAvailability(shop: AvailabilityFields): ShopAvailability {
  if (shop.pausedIndefinitely === true) return { kind: "paused", reopensAt: null }
  if (shop.pausedUntil) return { kind: "paused", reopensAt: shop.nextOpenAt ?? null }
  if (typeof shop.openNow !== "boolean") return { kind: "unknown" }
  return shop.openNow ? { kind: "open" } : { kind: "closed", nextOpenAt: shop.nextOpenAt ?? null }
}

/** The `/shop/[slug]` header pill (UI-SPEC K3): "Open now" / "Paused" / "Closed", or no pill. */
export function headerPillLabel(a: ShopAvailability): "Open now" | "Paused" | "Closed" | null {
  switch (a.kind) {
    case "open":
      return "Open now"
    case "paused":
      return "Paused"
    case "closed":
      return "Closed"
    default:
      return null
  }
}

/** The /shop and landing card badge (UI-SPEC K3): "Open" / "Paused" / "Closed", or no badge. */
export function cardBadgeLabel(a: ShopAvailability): "Open" | "Paused" | "Closed" | null {
  switch (a.kind) {
    case "open":
      return "Open"
    case "paused":
      return "Paused"
    case "closed":
      return "Closed"
    default:
      return null
  }
}

const UK_ZONE = "Europe/London"

const UK_TIME = new Intl.DateTimeFormat("en-GB", {
  timeZone: UK_ZONE,
  hour: "2-digit",
  minute: "2-digit",
  hourCycle: "h23",
})
const UK_WEEKDAY = new Intl.DateTimeFormat("en-GB", { timeZone: UK_ZONE, weekday: "long" })
const UK_DATE = new Intl.DateTimeFormat("en-GB", {
  timeZone: UK_ZONE,
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
})

function parse(iso: string | null | undefined): Date | null {
  if (!iso) return null
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? null : d
}

/**
 * When orders come back, as the K3 sentence ends: "Back at 18:40." (a paused shop, later today),
 * "Opens Wednesday at 09:00." (any other day, or a closed shop), "Check back later." (no time).
 */
export function reopeningSentence(
  at: string | null | undefined,
  { paused, now = new Date() }: { paused: boolean; now?: Date }
): string {
  const when = parse(at)
  if (!when) return "Check back later."
  const time = UK_TIME.format(when)
  if (paused && UK_DATE.format(when) === UK_DATE.format(now)) return `Back at ${time}.`
  return `Opens ${UK_WEEKDAY.format(when)} at ${time}.`
}

/** The K3 notice copy, or null when the shop is taking orders or the server said nothing. */
export function availabilityNoticeCopy(
  shopName: string,
  a: ShopAvailability,
  now: Date = new Date()
): { headline: string; detail: string } | null {
  if (a.kind === "paused") {
    return {
      headline: `${shopName} isn't taking orders right now`,
      detail: reopeningSentence(a.reopensAt, { paused: true, now }),
    }
  }
  if (a.kind === "closed") {
    return a.nextOpenAt && parse(a.nextOpenAt)
      ? { headline: "Closed now", detail: reopeningSentence(a.nextOpenAt, { paused: false, now }) }
      : { headline: "Closed", detail: "No opening hours are set for the next 7 days." }
  }
  return null
}
