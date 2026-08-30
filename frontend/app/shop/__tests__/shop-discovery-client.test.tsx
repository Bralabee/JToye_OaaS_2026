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

import fs from "fs"
import path from "path"
import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { WIDTH_TIER_CLASS } from "@/components/layout/content-tier"
import { ShopDiscoveryClient } from "@/app/shop/shop-discovery-client"
import StorefrontLayout from "@/app/shop/layout"
import ShopBrowseLoading from "@/app/shop/loading"
import publicApiClient from "@/lib/public-api-client"
import type { SearchInterpretation } from "@/lib/search-interpretation"
import type { PageResponse } from "@/types/api"
import type { PublicShop } from "@/types/storefront"

jest.mock("@/lib/public-api-client", () => ({
  __esModule: true,
  default: { get: jest.fn() },
}))

// StorefrontNav reads the customer session. Mirrors shop-layout-a11y.test.tsx,
// which renders the same layout for the same reason.
jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn(() => Promise.resolve(null)),
  customerLogin: jest.fn(),
  customerLogout: jest.fn(),
}))

/**
 * The stock scale token these storefront bands used to carry, named ONCE.
 * A static gate over this token in plan 35-10 must exclude `__tests__/`.
 */
const STOCK_BAND_TOKEN = "max-w-7xl"

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

  it("QA-council A11Y-8: a genuine failure shows a load-error panel in the results grid, never 'No kitchens found'", async () => {
    renderDiscovery({ shops: [NEAR, UNPLACED], query: "jollof", interpretation: TEXT })

    // POSITIVE CONTROL: the two seeded shops ARE on screen before the
    // failure, so their absence afterwards is a statement about the code.
    expect(screen.getByText("Dulwich Near Kitchen")).toBeInTheDocument()

    // No `response` — a genuine failure, not a 429. Before the fix this branch
    // did `setShops([]); setTotalElements(0)` and nothing else, so the results
    // grid fell through to "No kitchens found" — a false claim that the
    // marketplace is empty when the request merely failed.
    mockGet.mockRejectedValue(new Error("Network Error"))
    fireEvent.change(screen.getByLabelText(/Search kitchens, dishes or a postcode/i), {
      target: { value: "jollof again" },
    })

    expect(await screen.findByTestId("discovery-load-error")).toBeInTheDocument()
    expect(screen.queryByText("No kitchens found")).not.toBeInTheDocument()
  })

  it("CONTROL: a genuine 200 with zero rows still shows the real 'No kitchens found' empty state", async () => {
    renderDiscovery({ shops: [NEAR, UNPLACED], query: "jollof", interpretation: TEXT })
    expect(screen.getByText("Dulwich Near Kitchen")).toBeInTheDocument()

    mockGet.mockResolvedValue({ data: page([]) })
    fireEvent.change(screen.getByLabelText(/Search kitchens, dishes or a postcode/i), {
      target: { value: "no such kitchen" },
    })

    expect(await screen.findByText("No kitchens found")).toBeInTheDocument()
    expect(screen.queryByTestId("discovery-load-error")).not.toBeInTheDocument()
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

/**
 * PHASE 35 / UIX-07 — the DECLARED Marketing width tier across the `/shop`
 * route family, and the skeleton/content parity that goes with it.
 *
 * WHY THE LAYOUT AND ROUTE-SKELETON CASES LIVE IN THIS FILE. Plan 35-07 runs in
 * a wave of five plans against one shared branch, and the wave is only
 * parallel-safe because each plan stays inside a declared, non-overlapping file
 * set. `shop-layout-a11y.test.tsx` belongs to no plan in this wave, so the
 * storefront chrome and route-skeleton assertions are made here rather than by
 * reaching outside the set. They are about the same route family.
 *
 * ORCH-01 (orchestrator decision, 2026-08-29, CONTEXT.md section 4b): `/shop`
 * KEEPS the Marketing width. These cases therefore assert a declaration, never a
 * change of width — the value is identical before and after.
 */
describe("/shop route family — the declared Marketing width tier (UIX-07)", () => {
  function bandsIn(container: HTMLElement): Element[] {
    return Array.from(container.querySelectorAll('[data-width-tier="marketing"]'))
  }

  it("declares the tier on the directory band the crawler and the customer see", () => {
    const { container } = renderDiscovery({ query: "jollof", interpretation: TEXT })

    const bands = bandsIn(container)
    expect(bands).toHaveLength(1)
    expect(bands[0].classList.contains(WIDTH_TIER_CLASS.marketing)).toBe(true)
    // CONTROL on this very element, so the absence below is about the token.
    expect(bands[0].classList.contains("mx-auto")).toBe(true)
    expect(bands[0].classList.contains(STOCK_BAND_TOKEN)).toBe(false)
  })

  it("leaves the sub-heading and search-input control widths alone", () => {
    renderDiscovery({ query: "jollof", interpretation: TEXT })

    // PATTERNS 1c: a control's width is not a page band. The search input keeps
    // its own clamp, and it is CLS-sensitive (CONTEXT section 5).
    const input = screen.getByLabelText(/Search kitchens, dishes or a postcode/i)
    const controlClamp = input.closest(".max-w-xl")
    expect(controlClamp).not.toBeNull()
    expect(controlClamp?.hasAttribute("data-width-tier")).toBe(false)
  })

  it("declares the tier on the directory's own Suspense skeleton band too", () => {
    // The Suspense fallback does not render under jsdom (nothing suspends), so
    // this arm reads the source. It is paired with the DOM case above: that one
    // proves one of the two declarations is real and applied, this one proves
    // BOTH sites carry it. A count, not a substring — a half-done file reds.
    const src = fs.readFileSync(
      path.join(process.cwd(), "app/shop/shop-discovery-client.tsx"),
      "utf8"
    )
    const declarations = src.match(/data-width-tier="marketing"/g) ?? []
    expect(declarations).toHaveLength(2)

    // CONTROL: the pattern is capable of finding nothing, so the count above is
    // a measurement rather than a tautology.
    expect(src.match(/data-width-tier="shell"/g)).toBeNull()
  })

  it("declares the tier on the storefront header rail, and leaves main uncapped", () => {
    const { container } = render(
      <StorefrontLayout>
        <p>Page body</p>
      </StorefrontLayout>
    )

    // SCOPED TO THE HEADER ON PURPOSE. This layout also renders PublicFooter,
    // whose rail already declares the same tier (plan 35-06). A container-wide
    // query is therefore satisfied by the FOOTER even when the header rail
    // carries nothing — measured: this case passed against the unmodified
    // layout before the scoping was added. The pass would have been about the
    // wrong element.
    const header = container.querySelector("header")
    expect(header).not.toBeNull()
    const bands = Array.from(header!.querySelectorAll('[data-width-tier="marketing"]'))
    expect(bands).toHaveLength(1)
    expect(bands[0].classList.contains(WIDTH_TIER_CLASS.marketing)).toBe(true)
    expect(bands[0].classList.contains("mx-auto")).toBe(true)
    expect(bands[0].classList.contains(STOCK_BAND_TOKEN)).toBe(false)

    // The layout's own main is deliberately width-free so its children own their
    // bands — the policy pages nest in this tree. Do NOT cap it.
    const main = container.querySelector("main#main")
    expect(main).not.toBeNull()
    expect(main?.className).not.toMatch(/max-w-/)
    expect(main?.hasAttribute("data-width-tier")).toBe(false)
  })

  it("declares the tier on the /shop route skeleton", () => {
    const { container } = render(<ShopBrowseLoading />)

    const bands = bandsIn(container)
    expect(bands).toHaveLength(1)
    expect(bands[0].classList.contains(WIDTH_TIER_CLASS.marketing)).toBe(true)
    expect(bands[0].classList.contains("mx-auto")).toBe(true)
    expect(bands[0].classList.contains(STOCK_BAND_TOKEN)).toBe(false)
  })

  it("PARITY: the /shop route skeleton and the directory content declare the SAME band width", () => {
    // The pair is consistent today at the Marketing width and must stay so, or
    // `/shop` acquires the hydration narrowing that `/shop/[slug]` had. The
    // OTHER pair — the `/shop/[slug]` skeleton, its detail client and its
    // not-found panel — is a THREE-file family at a different width and is
    // asserted by plan 35-10's static gate, not here.
    const skeleton = bandsIn(render(<ShopBrowseLoading />).container)[0]
    const content = bandsIn(
      renderDiscovery({ query: "jollof", interpretation: TEXT }).container
    )[0]

    expect(skeleton).toBeDefined()
    expect(content).toBeDefined()
    expect(skeleton.getAttribute("data-width-tier")).toBe(content.getAttribute("data-width-tier"))
    expect(skeleton.classList.contains(WIDTH_TIER_CLASS.marketing)).toBe(
      content.classList.contains(WIDTH_TIER_CLASS.marketing)
    )
    // And that shared value is a real class, not two matching absences.
    expect(WIDTH_TIER_CLASS.marketing).not.toBe("")
  })
})
