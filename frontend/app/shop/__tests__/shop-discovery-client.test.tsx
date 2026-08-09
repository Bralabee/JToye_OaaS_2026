/**
 * The storefront directory island's honesty contract (issue 619, CUST-01).
 *
 * WHAT IS ACTUALLY LOAD-BEARING HERE. Three of these assertions are ABSENCES,
 * and an absence is evidence only once something has proved the query can find
 * the thing it is looking for. Every one is therefore paired:
 *
 *   - "no proximity claim on a `text` response" (CA-D) is paired with the
 *     sibling arm where the SAME `queryByText(/within 3\.1 miles of/i)` finds
 *     exactly one node on a `proximity` response. Without that pair, a typo in
 *     the matcher would read as a clean page.
 *   - "no distance pill without `distanceKm`" is paired with a card that has one.
 *   - "no proximity claim after a failed fetch" is paired with an assertion that
 *     the claim WAS on screen immediately before the failure.
 *
 * `getByRole` / `getByText`, never `getByTestId` or `getByTitle`: React's
 * streaming staging buffer parks a second copy of the shell in
 * `<div id="S:n" hidden>` for ~300 ms and the latter two see it.
 *
 * The component is seeded with a non-null `initial`, so its `serverSeeded` ref
 * suppresses the mount fetch — exactly as a real SSR page-0 render does. That is
 * why `initialInterpretation` has to exist at all: without it a direct
 * `/shop?q=SE22` would render the plain heading and never correct itself.
 */

import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { ShopDiscoveryClient } from "@/app/shop/shop-discovery-client"
import publicApiClient from "@/lib/public-api-client"
import type { SearchInterpretation } from "@/lib/search-interpretation"
import type { PageResponse } from "@/types/api"
import type { PublicShop } from "@/types/storefront"

jest.mock("@/lib/public-api-client", () => ({
  __esModule: true,
  default: { get: jest.fn() },
}))

const mockGet = publicApiClient.get as jest.Mock

function shop(overrides: Partial<PublicShop> & { slug: string; name: string }): PublicShop {
  return {
    description: null,
    address: null,
    logoUrl: null,
    bannerUrl: null,
    phone: null,
    email: null,
    latitude: 51.47,
    longitude: -0.07,
    openingHours: null,
    deliveryInfo: null,
    minimumOrderPennies: 1000,
    deliveryFeePennies: 250,
    freeDeliveryThresholdPennies: null,
    tags: null,
    ...overrides,
  }
}

/** 1.2 km is 0.7 miles — a value that is NOT the radius, so the two cannot be confused. */
const NEAR = shop({
  slug: "dulwich-near-kitchen",
  name: "Dulwich Near Kitchen",
  distanceKm: 1.2,
})
/** A published kitchen with no distance: the server ranked it, or it was never ranked. */
const UNPLACED = shop({
  slug: "mama-ades-kitchen",
  name: "Mama Ade's Kitchen",
  distanceKm: null,
})

const DISTRICT: SearchInterpretation = {
  kind: "proximity",
  postcode: "SE22",
  precision: "district",
  radiusKm: 5,
}
const TEXT: SearchInterpretation = { kind: "text" }

function page(content: PublicShop[], totalElements = content.length): PageResponse<PublicShop> {
  return {
    content,
    totalElements,
    totalPages: totalElements === 0 ? 0 : 1,
    size: 12,
    number: 0,
    first: true,
    last: true,
  }
}

function renderDiscovery({
  shops = [NEAR, UNPLACED],
  total,
  query = "SE22",
  interpretation = TEXT,
}: {
  shops?: PublicShop[]
  total?: number
  query?: string
  interpretation?: SearchInterpretation
} = {}) {
  return render(
    <ShopDiscoveryClient
      initial={page(shops, total ?? shops.length)}
      initialQuery={query}
      initialInterpretation={interpretation}
    />
  )
}

beforeEach(() => {
  mockGet.mockReset()
})

describe("ShopDiscoveryClient — only the server can produce a proximity claim", () => {
  it("CA-D: renders today's copy and NO proximity claim when the server said `text`", () => {
    renderDiscovery({ query: "jollof", interpretation: TEXT })

    expect(screen.queryByText(/within 3\.1 miles of/i)).toBeNull()
    expect(screen.queryByText(/kitchens we cannot place/i)).toBeNull()
    // The existing copy is unchanged, emphasis and all.
    expect(screen.getByText(/2 kitchens for/)).toBeInTheDocument()
    expect(screen.getByText("“jollof”")).toBeInTheDocument()
  })

  it("CA-D CONTROL: the SAME query finds exactly one node when the server disclosed proximity", () => {
    renderDiscovery({ query: "SE22", interpretation: DISTRICT })

    const found = screen.getAllByText(/within 3\.1 miles of/i)
    expect(found).toHaveLength(1)
    expect(found[0]).toHaveTextContent("2 kitchens within 3.1 miles of SE22")
  })

  it("says so plainly when the proximity search found nothing, and keeps the escape hatch", () => {
    renderDiscovery({ shops: [], total: 0, query: "SE22", interpretation: DISTRICT })

    expect(screen.getByText(/No kitchens within 3\.1 miles of SE22/i)).toBeInTheDocument()
    expect(screen.getByRole("button", { name: "Browse all kitchens" })).toBeInTheDocument()
  })

  it("renders a unit-precision key with its space restored", () => {
    renderDiscovery({
      shops: [NEAR],
      query: "SE15 5BS",
      interpretation: { kind: "proximity", postcode: "SE155BS", precision: "unit", radiusKm: 5 },
    })

    expect(screen.getByText(/1 kitchen within 3\.1 miles of SE15 5BS/i)).toBeInTheDocument()
  })

  it("discloses the exclusion generically and offers a route to the full directory", () => {
    renderDiscovery({ query: "SE22", interpretation: DISTRICT })

    expect(
      screen.getByText(/Kitchens we cannot place, and any further away, are not shown\./i)
    ).toBeInTheDocument()
    const escape = screen.getByRole("link", { name: "See every kitchen" })
    expect(escape).toHaveAttribute("href", "/shop")
    // D-D: generic, never a computed count. Nothing here was derived by
    // subtraction and no second request was made.
    expect(mockGet).not.toHaveBeenCalled()
  })

  it("CONTROL: the exclusion disclosure is absent on the text branch", () => {
    renderDiscovery({ query: "jollof", interpretation: TEXT })

    expect(screen.queryByText(/Kitchens we cannot place/i)).toBeNull()
    expect(screen.queryByRole("link", { name: "See every kitchen" })).toBeNull()
  })
})

describe("ShopDiscoveryClient — the distance on a card", () => {
  it("prints the server's distanceKm in miles, converted once", () => {
    renderDiscovery({ shops: [NEAR], query: "SE22", interpretation: DISTRICT })

    // 1.2 km -> 0.7 miles. Never recomputed in the browser: this is a unit
    // conversion of the number the ordering used.
    expect(screen.getByText("0.7 miles")).toBeInTheDocument()
  })

  it("CONTROL: a card with no distanceKm prints no distance at all", () => {
    renderDiscovery({ shops: [UNPLACED], query: "SE22", interpretation: DISTRICT })

    expect(screen.queryByText(/\d+(\.\d)? miles$/)).toBeNull()
  })

  it("keeps the pill out of flow so a located and an unlocated card are the same height", () => {
    const { container } = renderDiscovery({
      shops: [NEAR],
      query: "SE22",
      interpretation: DISTRICT,
    })

    const pill = screen.getByText("0.7 miles")
    expect(pill.className).toContain("absolute")
    // And the banner it is positioned against is the positioning context.
    expect(container.querySelector(".relative")).not.toBeNull()
  })
})

describe("ShopDiscoveryClient — a non-answer never carries a proximity claim", () => {
  it("429 arm: the busy state replaces the claim that was on screen a moment before", async () => {
    renderDiscovery({ query: "SE22", interpretation: DISTRICT })

    // POSITIVE CONTROL: the claim IS on screen before the 429, so its absence
    // afterwards is a statement about the code and not about the matcher.
    expect(screen.getByText(/within 3\.1 miles of SE22/i)).toBeInTheDocument()

    mockGet.mockRejectedValue({ response: { status: 429, headers: {} } })
    fireEvent.change(screen.getByLabelText(/Search kitchens, dishes or a postcode/i), {
      target: { value: "SE22 again" },
    })

    expect(await screen.findByText(/High demand right now/i)).toBeInTheDocument()
    expect(screen.queryByText(/within 3\.1 miles of/i)).toBeNull()
  })

  it("failure arm: a network error falls back to the TEXT copy, never the proximity copy", async () => {
    renderDiscovery({ query: "SE22", interpretation: DISTRICT })

    expect(screen.getByText(/within 3\.1 miles of SE22/i)).toBeInTheDocument()

    // No `response` — not a 429, so the island takes its genuine-failure branch,
    // where the summary line DOES still render. Without the catch-branch reset
    // it would render "No kitchens within 3.1 miles of SE22" over a network
    // error, which is the exact untruth this arm exists to stop.
    mockGet.mockRejectedValue(new Error("Network Error"))
    fireEvent.change(screen.getByLabelText(/Search kitchens, dishes or a postcode/i), {
      target: { value: "SE22 offline" },
    })

    await waitFor(() => {
      expect(screen.getByText(/No kitchens match/i)).toBeInTheDocument()
    })
    expect(screen.queryByText(/within 3\.1 miles of/i)).toBeNull()
  })

  it("adopts the interpretation of the response it actually received", async () => {
    renderDiscovery({ query: "jollof", interpretation: TEXT })

    mockGet.mockResolvedValue({
      data: page([NEAR]),
      headers: { "x-search-interpretation": "proximity; postcode=SE22; precision=district; radiusKm=5.0" },
    })
    fireEvent.change(screen.getByLabelText(/Search kitchens, dishes or a postcode/i), {
      target: { value: "SE22" },
    })

    expect(await screen.findByText(/1 kitchen within 3\.1 miles of SE22/i)).toBeInTheDocument()
  })

  it("returns to the text copy when a later response carries no proximity header", async () => {
    renderDiscovery({ query: "SE22", interpretation: DISTRICT })

    mockGet.mockResolvedValue({ data: page([UNPLACED]), headers: { "x-search-interpretation": "text" } })
    fireEvent.change(screen.getByLabelText(/Search kitchens, dishes or a postcode/i), {
      target: { value: "jollof" },
    })

    expect(await screen.findByText(/1 kitchen for/)).toBeInTheDocument()
    expect(screen.queryByText(/within 3\.1 miles of/i)).toBeNull()
  })
})
