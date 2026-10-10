/**
 * 37-22 Task 1 (UI-SPEC K3 customer side, D-15, D-25, theme 2): the storefront says "Paused" or
 * "Closed" because the SERVER says so.
 *
 * Before 37-22 the shop page decided open/closed itself with `lib/opening-hours.ts#isOpenNow`,
 * re-parsing the free-text `openingHours`. The server (37-20's `ShopAvailabilityPolicy`) and the
 * client then disagreed: a paused shop whose hours were open read "Open now" while checkout
 * refused the order, and an unreadable NEEDS_REVIEW day read "Closed" while checkout took it.
 *
 * Every fixture below is built so a client-side computation gives the WRONG answer:
 *  - F8: `openingHours` is `{}`, which `isOpenNow` reads as "always open", while the server says
 *    paused. Reverting the pill to `isOpenNow` renders "Open now" and fails.
 *  - Times are UTC instants whose UK wall-clock time differs by an hour (BST), so a formatter that
 *    forgets Europe/London prints 17:40 instead of 18:40 and fails.
 *  - `openNow` ABSENT (an older backend) must render no claim at all, in either direction.
 */

import { act, render, screen } from "@testing-library/react"

const mockGet = jest.fn()
jest.mock("@/lib/public-api-client", () => ({
  __esModule: true,
  default: { get: (...args: unknown[]) => mockGet(...args) },
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

import { ShopAvailabilityNotice } from "@/components/storefront/shop-availability-notice"
import { ShopDetailClient } from "@/app/shop/[slug]/shop-detail-client"
import type { PublicProduct, PublicShop, ShopDetail } from "@/types/storefront"

/** Tuesday 13 October 2026, 18:20 in the UK (BST, UTC+1). */
const TUESDAY_EVENING = new Date("2026-10-13T17:20:00Z")
/** Monday 12 October 2026, 21:00 in the UK. */
const MONDAY_NIGHT = new Date("2026-10-12T20:00:00Z")

/** Fake ONLY the clock: React, RTL and promises keep real timers. */
function freezeClockAt(now: Date) {
  jest.useFakeTimers({
    now,
    doNotFake: [
      "setTimeout",
      "clearTimeout",
      "setInterval",
      "clearInterval",
      "setImmediate",
      "clearImmediate",
      "nextTick",
      "queueMicrotask",
      "requestAnimationFrame",
      "cancelAnimationFrame",
      "requestIdleCallback",
      "cancelIdleCallback",
      "hrtime",
      "performance",
    ],
  })
}

afterEach(() => {
  jest.useRealTimers()
})

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
    // `{}` is "always open" to the old client parser: any open state that is not the
    // server's own field shows up as "Open now" against these fixtures.
    openingHours: {},
    deliveryInfo: null,
    minimumOrderPennies: 0,
    deliveryFeePennies: 0,
    freeDeliveryThresholdPennies: null,
    tags: null,
    ...overrides,
  }
}

const PAUSED_20_MIN = makeShop({
  openNow: false,
  pausedUntil: "2026-10-13T17:40:00Z",
  nextOpenAt: "2026-10-13T17:40:00Z",
  pausedIndefinitely: false,
})

const PAUSED_INDEFINITELY = makeShop({
  openNow: false,
  pausedUntil: null,
  nextOpenAt: null,
  pausedIndefinitely: true,
})

const CLOSED_OPENS_TUESDAY = makeShop({
  openNow: false,
  // The +01:00 form, as the policy's ZonedDateTime -> OffsetDateTime can serialise it.
  nextOpenAt: "2026-10-13T09:00:00+01:00",
  pausedUntil: null,
  pausedIndefinitely: false,
})

const CLOSED_NO_HOURS = makeShop({
  openNow: false,
  nextOpenAt: null,
  pausedUntil: null,
  pausedIndefinitely: false,
})

const notice = () => screen.queryByTestId("shop-availability-notice")

describe("ShopAvailabilityNotice: K3 customer copy from the server's fields", () => {
  it("a timed pause: '{Shop} isn't taking orders right now' and 'Back at {HH:MM}.' in UK time", () => {
    freezeClockAt(TUESDAY_EVENING)
    render(<ShopAvailabilityNotice shop={PAUSED_20_MIN} />)
    expect(notice()).toHaveTextContent("Rosie's Kitchen isn't taking orders right now")
    // 17:40Z is 18:40 in the UK on 13 October (BST). 17:40 would be a UTC formatter.
    expect(notice()).toHaveTextContent("Back at 18:40.")
  })

  it("a pause that outlasts today's hours names the day orders come back, not a time that reads as today", () => {
    freezeClockAt(TUESDAY_EVENING)
    render(
      <ShopAvailabilityNotice
        shop={makeShop({
          openNow: false,
          pausedUntil: "2026-10-13T21:40:00Z",
          nextOpenAt: "2026-10-14T08:00:00Z",
          pausedIndefinitely: false,
        })}
      />
    )
    expect(notice()).toHaveTextContent("Rosie's Kitchen isn't taking orders right now")
    expect(notice()).toHaveTextContent("Opens Wednesday at 09:00.")
    expect(notice()).not.toHaveTextContent("Back at")
  })

  it("paused until the vendor reopens: 'Check back later.'", () => {
    freezeClockAt(TUESDAY_EVENING)
    render(<ShopAvailabilityNotice shop={PAUSED_INDEFINITELY} />)
    expect(notice()).toHaveTextContent("Rosie's Kitchen isn't taking orders right now")
    expect(notice()).toHaveTextContent("Check back later.")
  })

  it("closed with a next opening: 'Closed now · Opens Tuesday at 09:00.'", () => {
    freezeClockAt(MONDAY_NIGHT)
    render(<ShopAvailabilityNotice shop={CLOSED_OPENS_TUESDAY} />)
    expect(notice()).toHaveTextContent("Closed now")
    expect(notice()).toHaveTextContent("Opens Tuesday at 09:00.")
  })

  it("closed with no opening in the next 7 days: the 7-day copy", () => {
    freezeClockAt(MONDAY_NIGHT)
    render(<ShopAvailabilityNotice shop={CLOSED_NO_HOURS} />)
    expect(notice()).toHaveTextContent("Closed")
    expect(notice()).toHaveTextContent("No opening hours are set for the next 7 days.")
  })

  it("renders nothing when the server says the shop is open", () => {
    render(<ShopAvailabilityNotice shop={makeShop({ openNow: true, openingHours: { mon: "closed" } })} />)
    expect(notice()).toBeNull()
  })

  it("renders nothing when the server said nothing (openNow absent): no claim either way", () => {
    render(<ShopAvailabilityNotice shop={makeShop({ openingHours: { mon: "closed", tue: "closed" } })} />)
    expect(notice()).toBeNull()
  })

  it("is the K3 block: rounded-xl, slate-300 border, white, a Clock icon, and no red", () => {
    freezeClockAt(TUESDAY_EVENING)
    render(<ShopAvailabilityNotice shop={PAUSED_20_MIN} id="availability-explainer" />)
    const el = notice()
    expect(el).not.toBeNull()
    const cls = el!.getAttribute("class") ?? ""
    for (const token of ["rounded-xl", "border", "border-slate-300", "bg-white", "p-4"]) {
      expect(cls.split(/\s+/)).toContain(token)
    }
    // A closed shop is not an error: no red anywhere in the block.
    expect(el!.outerHTML).not.toMatch(/\b(?:text|bg|border|ring)-red-/)
    expect(el!.querySelector("svg.lucide-clock")).not.toBeNull()
    // The id is what checkout's aria-describedby points at.
    expect(el).toHaveAttribute("id", "availability-explainer")
  })
})

const product: PublicProduct = {
  id: "p1",
  title: "Jollof Rice",
  description: null,
  imageUrl: null,
  imageUrls: [],
  ingredientsText: "rice",
  allergenMask: 0,
  pricePennies: 850,
  category: "Mains",
  dietaryTags: null,
  preparationTimeMinutes: null,
  featured: false,
  inStock: true,
}

function detailFor(shop: PublicShop): ShopDetail {
  return {
    shop,
    products: { Mains: [product] },
    reviews: [],
    reviewCount: 0,
    avgRating: 0,
    promotions: [],
    announcements: [],
  } as ShopDetail
}

async function renderShopPage(shop: PublicShop) {
  await act(async () => {
    render(<ShopDetailClient slug="rosies-kitchen" initial={detailFor(shop)} />)
  })
}

const pill = () => screen.queryByTestId("shop-availability-pill")

describe("/shop/[slug] header pill and notice read the server (F8)", () => {
  beforeEach(() => {
    mockGet.mockReset()
  })

  it("F8: a paused shop whose hours are open now reads 'Paused', never 'Open now'", async () => {
    freezeClockAt(TUESDAY_EVENING)
    await renderShopPage(PAUSED_20_MIN)
    // The failure F8 exists for: the client parser reads `{}` hours as open.
    expect(screen.queryByText("Open now")).not.toBeInTheDocument()
    expect(pill()).toHaveTextContent(/^Paused$/)
    expect(notice()).toHaveTextContent("Back at 18:40.")
  })

  it("the notice sits between the shop header and the menu", async () => {
    freezeClockAt(TUESDAY_EVENING)
    await renderShopPage(PAUSED_20_MIN)
    const heading = screen.getByRole("heading", { level: 1, name: "Rosie's Kitchen" })
    const menuCategory = screen.getByRole("heading", { level: 2, name: "Mains" })
    expect(notice()).not.toBeNull()
    expect(heading.compareDocumentPosition(notice()!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(notice()!.compareDocumentPosition(menuCategory) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it("a closed shop reads 'Closed' and shows when it opens", async () => {
    freezeClockAt(MONDAY_NIGHT)
    await renderShopPage(CLOSED_OPENS_TUESDAY)
    expect(pill()).toHaveTextContent(/^Closed$/)
    expect(notice()).toHaveTextContent("Closed now")
    expect(notice()).toHaveTextContent("Opens Tuesday at 09:00.")
  })

  it("an open shop reads 'Open now' even when its free-text hours say closed (the server decides)", async () => {
    await renderShopPage(
      makeShop({ openNow: true, openingHours: { mon: "closed", tue: "closed", wed: "closed", thu: "closed", fri: "closed", sat: "closed", sun: "closed" } })
    )
    expect(pill()).toHaveTextContent(/^Open now$/)
    expect(notice()).toBeNull()
  })

  it("openNow absent (an older backend): no pill text and no notice", async () => {
    await renderShopPage(makeShop())
    expect(pill()).toBeNull()
    expect(screen.queryByText("Open now")).not.toBeInTheDocument()
    expect(screen.queryByText("Paused")).not.toBeInTheDocument()
    expect(notice()).toBeNull()
  })

  it("a client refetch reads the server's fields too, not the free-text hours", async () => {
    // initial null -> the island fetches. The fetched shop is paused with `{}` hours.
    freezeClockAt(TUESDAY_EVENING)
    mockGet.mockImplementation((url: string) => {
      if (url.endsWith("/products")) return Promise.resolve({ data: { Mains: [product] } })
      if (url.includes("/reviews")) return Promise.resolve({ data: { content: [], totalElements: 0 } })
      if (url.endsWith("/promotions") || url.endsWith("/announcements")) return Promise.resolve({ data: [] })
      if (url.endsWith("/config")) return Promise.resolve({ data: null })
      return Promise.resolve({ data: PAUSED_20_MIN })
    })
    await act(async () => {
      render(<ShopDetailClient slug="rosies-kitchen" initial={null} />)
    })
    expect(await screen.findByTestId("shop-availability-pill")).toHaveTextContent(/^Paused$/)
    expect(screen.queryByText("Open now")).not.toBeInTheDocument()
  })
})
