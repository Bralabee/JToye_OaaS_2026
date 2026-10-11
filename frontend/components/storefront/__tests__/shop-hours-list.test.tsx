/**
 * 37-21 deviation (Rule 2, regression by omission; deferred-items "From 37-22"): the storefront's
 * "Opening hours" list must show the hours the server enforces.
 *
 * Before 37-21 the list printed the free-text `openingHours`. Saving structured hours (the K4 editor)
 * leaves that old text on the row, so the list would have shown the OLD text beside a pill driven by
 * the NEW hours: a shop saved as 09:00-17:00 that once wrote "whenever" would still read "whenever".
 * The list now renders `openingSchedule` when the server gives one, falls back to the free text only
 * where the schedule is not the whole truth (a legacy shop, no schedule, or NEEDS_REVIEW, whose
 * unreadable days are `[]` in the schedule yet OPEN on the server), and never renders both.
 */

import { act, render, screen, within } from "@testing-library/react"

jest.mock("@/lib/public-api-client", () => ({
  __esModule: true,
  default: { get: jest.fn(() => new Promise(() => {})) },
}))

jest.mock("@/components/storefront/cart-provider", () => ({
  useCart: () => ({
    items: [],
    addItem: jest.fn(),
    removeItem: jest.fn(),
    updateQuantity: jest.fn(),
    clearCart: jest.fn(),
    itemCount: 0,
    totalPennies: 0,
    shopSlug: "rosies-kitchen",
  }),
  CartProvider: ({ children }: { children: React.ReactNode }) => children,
}))

import { ShopDetailClient } from "@/app/shop/[slug]/shop-detail-client"
import type { PublicShop, ShopDetail } from "@/types/storefront"

const CLOSED_WEEK = { mon: [], tue: [], wed: [], thu: [], fri: [], sat: [], sun: [] }

function makeShop(overrides: Partial<PublicShop> = {}): PublicShop {
  return {
    slug: "rosies-kitchen",
    name: "Rosie's Kitchen",
    description: null,
    address: null,
    logoUrl: null,
    bannerUrl: null,
    phone: null,
    email: null,
    latitude: null,
    longitude: null,
    openingHours: null,
    deliveryInfo: null,
    minimumOrderPennies: 0,
    deliveryFeePennies: 0,
    freeDeliveryThresholdPennies: null,
    tags: null,
    openNow: true,
    pausedIndefinitely: false,
    ...overrides,
  }
}

async function renderShop(shop: PublicShop) {
  const detail = {
    shop,
    products: {},
    reviews: [],
    reviewCount: 0,
    avgRating: 0,
    promotions: [],
    announcements: [],
  } as unknown as ShopDetail
  await act(async () => {
    render(<ShopDetailClient slug="rosies-kitchen" initial={detail} />)
  })
}

/** The rows of the "Opening hours" list as [day, text], or null when there is no list. */
function hoursRows(): [string, string][] | null {
  const summary = screen.queryByText("Opening hours")
  if (!summary) return null
  const details = summary.closest("details") as HTMLElement
  const grid = details.querySelector("div") as HTMLElement
  return Array.from(grid.children).map((row) => {
    const spans = row.querySelectorAll("span")
    return [spans[0].textContent ?? "", spans[1].textContent ?? ""]
  })
}

describe("storefront Opening hours list reads the structured schedule (37-21 deviation)", () => {
  it("a STRUCTURED shop lists its saved windows, Monday first, not the old free text left on the row", async () => {
    await renderShop(
      makeShop({
        hoursSource: "STRUCTURED",
        openingSchedule: { ...CLOSED_WEEK, mon: [{ open: "09:00", close: "17:00" }], fri: [{ open: "11:00", close: "14:00" }, { open: "17:00", close: "22:00" }] },
        openingHours: { mon: "whenever", tue: "9ish" },
      })
    )
    expect(hoursRows()).toEqual([
      ["Monday", "09:00–17:00"],
      ["Tuesday", "Closed"],
      ["Wednesday", "Closed"],
      ["Thursday", "Closed"],
      ["Friday", "11:00–14:00, 17:00–22:00"],
      ["Saturday", "Closed"],
      ["Sunday", "Closed"],
    ])
    expect(screen.queryByText("whenever")).not.toBeInTheDocument()
    expect(screen.queryByText("9ish")).not.toBeInTheDocument()
  })

  it("marks an overnight window and words the all-day window as open 24 hours", async () => {
    await renderShop(
      makeShop({
        hoursSource: "STRUCTURED",
        openingSchedule: { ...CLOSED_WEEK, sat: [{ open: "18:00", close: "02:00" }], sun: [{ open: "00:00", close: "00:00" }] },
      })
    )
    const rows = Object.fromEntries(hoursRows() ?? [])
    expect(rows.Saturday).toBe("18:00–02:00 (next day)")
    expect(rows.Sunday).toBe("Open 24 hours")
  })

  it("a MIGRATED_FROM_TEXT shop lists its schedule too", async () => {
    await renderShop(
      makeShop({
        hoursSource: "MIGRATED_FROM_TEXT",
        openingSchedule: { ...CLOSED_WEEK, wed: [{ open: "10:00", close: "16:00" }] },
        openingHours: { wed: "10:00 - 16:00" },
      })
    )
    expect(Object.fromEntries(hoursRows() ?? []).Wednesday).toBe("10:00–16:00")
  })

  it("a STRUCTURED shop closed every day lists every day Closed (a list from an empty schedule, not no list)", async () => {
    await renderShop(makeShop({ hoursSource: "STRUCTURED", openingSchedule: CLOSED_WEEK, openNow: false }))
    expect(hoursRows()).toEqual(
      ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"].map((d) => [d, "Closed"])
    )
  })

  it("a NEEDS_REVIEW shop keeps the vendor's text: its unreadable days are open on the server, so the schedule would print Closed for a day customers can order on", async () => {
    await renderShop(
      makeShop({
        hoursSource: "NEEDS_REVIEW",
        openingSchedule: { ...CLOSED_WEEK, mon: [{ open: "10:00", close: "16:00" }] },
        openingHours: { mon: "10:00-16:00", tue: "lunchtime till late" },
      })
    )
    const rows = Object.fromEntries(hoursRows() ?? [])
    expect(rows.Tuesday).toBe("lunchtime till late")
    expect(rows.Monday).toBe("10:00-16:00")
  })

  it("a legacy shop (legacyAlwaysOpen) with text shows the text; without text there is no list", async () => {
    await renderShop(makeShop({ legacyAlwaysOpen: true, hoursSource: "LEGACY_ALWAYS_OPEN", openingHours: { mon: "all day" } }))
    expect(Object.fromEntries(hoursRows() ?? []).Monday).toBe("all day")
  })

  it("no schedule and no text: no list at all", async () => {
    await renderShop(makeShop({ legacyAlwaysOpen: true, hoursSource: "LEGACY_ALWAYS_OPEN", openingHours: null }))
    expect(hoursRows()).toBeNull()
  })

  it("never renders the list twice", async () => {
    await renderShop(
      makeShop({
        hoursSource: "STRUCTURED",
        openingSchedule: { ...CLOSED_WEEK, mon: [{ open: "09:00", close: "17:00" }] },
        openingHours: { mon: "whenever" },
      })
    )
    expect(screen.getAllByText("Opening hours")).toHaveLength(1)
    const details = screen.getByText("Opening hours").closest("details") as HTMLElement
    expect(within(details).getAllByText("Monday")).toHaveLength(1)
  })
})
