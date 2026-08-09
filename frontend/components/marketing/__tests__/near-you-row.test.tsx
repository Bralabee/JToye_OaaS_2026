/**
 * NearYouRow — the landing kitchen row's located upgrade (issue 460, CUST-01).
 * Written without a leading hash on issue numbers: the palette-discipline gate
 * greps this directory for raw hex colours and an issue reference matches that
 * pattern. See the note in shop-card.tsx.
 *
 * WHAT IS ACTUALLY LOAD-BEARING HERE. Two of these assertions are absences, and
 * an absence is only evidence once something has proven the query can find the
 * thing it is looking for:
 *
 *   - "no heading says near you without a coordinate" is paired with the granted
 *     case, where the SAME query finds one. Without that pair, a typo in the
 *     matcher would read as a clean page.
 *   - "no spinner survives a denial" is paired with an assertion that a spinner
 *     IS on screen while the fix is outstanding. `queryBy…` returns null just as
 *     happily when the selector is wrong as when the element is gone.
 *
 * The heading assertions are scoped to HEADINGS deliberately, never to the
 * document. `/` legitimately renders "near you" at three non-heading sites (the
 * primary CTA, the "Browse" step body, and the scroller's accessible name, which
 * is another spec's selector), so a document-wide absence check is unsatisfiable
 * — 33-03 measured that and recorded the scoping decision.
 */

import { render, screen, waitFor, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { NearYouRow, NEAR_YOU_RADIUS_KM } from "@/components/marketing/near-you-row"
import publicApiClient from "@/lib/public-api-client"
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

const BRIXTON = shop({
  slug: "brixton-village-grill",
  name: "Brixton Village Grill",
  latitude: 51.46262,
  longitude: -0.11325,
})
const MAMA = shop({
  slug: "mama-ades-kitchen",
  name: "Mama Ade's Kitchen",
  latitude: 51.472435,
  longitude: -0.070047,
})
/** The shop that cannot be ranked: a real published storefront with no coordinate. */
const NO_COORDS = shop({
  slug: "belfast-bap-co",
  name: "Belfast Bap Co.",
  latitude: null,
  longitude: null,
})

/** The three published shops the server rendered, one of them unrankable. */
const SERVER_SHOPS = [BRIXTON, MAMA, NO_COORDS]

function page<T>(content: T[]) {
  return {
    data: {
      content,
      totalElements: content.length,
      totalPages: 1,
      size: 8,
      number: 0,
      first: true,
      last: true,
    },
  }
}

/**
 * Install a `navigator.geolocation` whose single call is resolved by the test.
 * Returns the mock so a test can assert it was NOT called before a click.
 */
function installGeolocation(
  impl: (
    success: PositionCallback,
    failure: PositionErrorCallback,
    options?: PositionOptions
  ) => void
) {
  const getCurrentPosition = jest.fn(impl)
  Object.defineProperty(global.navigator, "geolocation", {
    value: { getCurrentPosition, watchPosition: jest.fn(), clearWatch: jest.fn() },
    configurable: true,
  })
  return getCurrentPosition
}

const POSITION = {
  coords: { latitude: 51.4712345678, longitude: -0.0701234567 },
} as GeolocationPosition

/** A GeolocationPositionError carries the three codes as properties on itself. */
function geoError(code: 1 | 2 | 3): GeolocationPositionError {
  return {
    code,
    message: "",
    PERMISSION_DENIED: 1,
    POSITION_UNAVAILABLE: 2,
    TIMEOUT: 3,
  } as GeolocationPositionError
}

/** Every shop card link, in DOM order. Excludes the two `/shop` list links. */
function cardSlugs(): string[] {
  return screen
    .getAllByRole("link")
    .map((a) => a.getAttribute("href") ?? "")
    .filter((href) => href.startsWith("/shop/"))
    .map((href) => href.replace("/shop/", ""))
}

const headingsSayingNearYou = () =>
  screen.getAllByRole("heading").filter((h) => /near you/i.test(h.textContent ?? ""))

beforeEach(() => {
  mockGet.mockReset()
})

describe("NearYouRow — no coordinate held", () => {
  it("renders the server's shops verbatim, in the order the server sent them", () => {
    render(<NearYouRow serverShops={SERVER_SHOPS} />)
    expect(cardSlugs()).toEqual([
      "brixton-village-grill",
      "mama-ades-kitchen",
      "belfast-bap-co",
    ])
    expect(screen.getByText("Mama Ade's Kitchen")).toBeTruthy()
  })

  it("has NO heading claiming proximity, and shows no distance on any card", () => {
    render(<NearYouRow serverShops={SERVER_SHOPS} />)
    expect(headingsSayingNearYou().map((h) => h.textContent)).toEqual([])
    expect(screen.getByRole("heading", { name: /kitchens on j'toye/i })).toBeTruthy()
    // No distance can be shown when none was computed. `/\d+(\.\d)? km/` would
    // also match a "5 km" in the empty-state copy, hence the scoped query.
    expect(screen.queryByText(/km$/)).toBeNull()
  })

  it("does not ask for permission until the visitor clicks — no mount-effect prompt", async () => {
    const getCurrentPosition = installGeolocation(() => {})
    render(<NearYouRow serverShops={SERVER_SHOPS} />)

    // The whole point of the gesture gate: rendering must not prompt.
    expect(getCurrentPosition).not.toHaveBeenCalled()

    await userEvent.click(screen.getByRole("button", { name: /use my location/i }))
    expect(getCurrentPosition).toHaveBeenCalledTimes(1)
    // ...and high accuracy is off, with a real timeout, so a hung fix cannot
    // leave the button spinning forever.
    const options = getCurrentPosition.mock.calls[0][2] as PositionOptions
    expect(options.enableHighAccuracy).toBe(false)
    expect(options.timeout).toBeGreaterThan(0)
  })

  it("never issues a shop request before a coordinate is held", async () => {
    installGeolocation(() => {})
    render(<NearYouRow serverShops={SERVER_SHOPS} />)
    expect(mockGet).not.toHaveBeenCalled()
  })
})

describe("NearYouRow — a granted coordinate", () => {
  it("re-renders distance-ordered under a 'near you' heading, showing the real distance", async () => {
    installGeolocation((success) => success(POSITION))
    mockGet.mockResolvedValue(
      page([
        { ...MAMA, distanceKm: 0.2707795900623579 },
        { ...BRIXTON, distanceKm: 3.0104 },
      ])
    )

    render(<NearYouRow serverShops={SERVER_SHOPS} />)
    await userEvent.click(screen.getByRole("button", { name: /use my location/i }))

    await waitFor(() => expect(headingsSayingNearYou().length).toBe(1))
    // The order is the SERVER's, and it is not the order the server list was in.
    expect(cardSlugs()).toEqual(["mama-ades-kitchen", "brixton-village-grill"])
    // The distance displayed is the one the ordering used, rounded for display
    // only — never recomputed in the browser.
    expect(screen.getByText(/^0\.3 km/)).toBeTruthy()
    expect(screen.getByText(/^3\.0 km/)).toBeTruthy()
  })

  it("asks for exactly one page, with the radius the heading quotes, once per grant", async () => {
    installGeolocation((success) => success(POSITION))
    mockGet.mockResolvedValue(page([{ ...MAMA, distanceKm: 0.27 }]))

    render(<NearYouRow serverShops={SERVER_SHOPS} />)
    await userEvent.click(screen.getByRole("button", { name: /use my location/i }))
    await waitFor(() => expect(mockGet).toHaveBeenCalledTimes(1))

    const [path, config] = mockGet.mock.calls[0]
    expect(path).toBe("/public/shops")
    expect(config.params.radiusKm).toBe(NEAR_YOU_RADIUS_KM)
    // Coordinate precision is reduced before it leaves the browser: 4 dp is
    // ~11 m against ~100 m postcode centroids, so it cannot change the ranking.
    expect(config.params.lat).toBe(51.4712)
    expect(config.params.lon).toBe(-0.0701)
  })

  it("shows the nothing-in-radius state honestly, keeping the full list", async () => {
    installGeolocation((success) => success(POSITION))
    mockGet.mockResolvedValue(page([]))

    render(<NearYouRow serverShops={SERVER_SHOPS} />)
    await userEvent.click(screen.getByRole("button", { name: /use my location/i }))

    await waitFor(() =>
      expect(
        screen.getByRole("heading", {
          name: new RegExp(`no kitchens within ${NEAR_YOU_RADIUS_KM} km`, "i"),
        })
      ).toBeTruthy()
    )
    // Not "near you" over a list of shops that are not.
    expect(headingsSayingNearYou().map((h) => h.textContent)).toEqual([])
    // ...and the visitor is not left with an empty page.
    expect(cardSlugs()).toEqual([
      "brixton-village-grill",
      "mama-ades-kitchen",
      "belfast-bap-co",
    ])
  })
})

describe("NearYouRow — the exclusion disclosure (issue 460 / plan-checker B8)", () => {
  it("names the shops that could not be ranked, with a route to the full list", async () => {
    installGeolocation((success) => success(POSITION))
    mockGet.mockResolvedValue(
      page([
        { ...MAMA, distanceKm: 0.27 },
        { ...BRIXTON, distanceKm: 3.01 },
      ])
    )

    render(<NearYouRow serverShops={SERVER_SHOPS} />)
    await userEvent.click(screen.getByRole("button", { name: /use my location/i }))

    const disclosure = await screen.findByText(/no location data/i)
    // ONE shop lacks a coordinate, and the count must come from the shops
    // themselves rather than from (server count - located count): the latter
    // would report a shop that is merely far away as having no location data.
    expect(disclosure.textContent).toMatch(/^1 kitchen has no location data/)
    const escape = within(disclosure).getByRole("link", { name: /see every kitchen/i })
    expect(escape.getAttribute("href")).toBe("/shop")
  })

  it("says nothing when every published shop made it into the located row", async () => {
    installGeolocation((success) => success(POSITION))
    mockGet.mockResolvedValue(
      page([
        { ...MAMA, distanceKm: 0.27 },
        { ...BRIXTON, distanceKm: 3.01 },
      ])
    )

    // Same located result, but this server list holds no unrankable shop.
    render(<NearYouRow serverShops={[BRIXTON, MAMA]} />)
    await userEvent.click(screen.getByRole("button", { name: /use my location/i }))

    await waitFor(() => expect(headingsSayingNearYou().length).toBe(1))
    expect(screen.queryByText(/no location data/i)).toBeNull()
    expect(screen.queryByText(/further than/i)).toBeNull()
  })

  it("distinguishes 'too far' from 'no location data' rather than blaming both on the data", async () => {
    installGeolocation((success) => success(POSITION))
    // Only the nearest shop came back: Brixton is outside the radius, Belfast
    // has no coordinate. Two different reasons, both disclosed as themselves.
    mockGet.mockResolvedValue(page([{ ...MAMA, distanceKm: 0.27 }]))

    render(<NearYouRow serverShops={SERVER_SHOPS} />)
    await userEvent.click(screen.getByRole("button", { name: /use my location/i }))

    const disclosure = await screen.findByText(/no location data/i)
    expect(disclosure.textContent).toMatch(/^1 kitchen has no location data/)
    expect(disclosure.textContent).toMatch(
      new RegExp(`1 more is further than ${NEAR_YOU_RADIUS_KM} km`)
    )
  })
})

describe("NearYouRow — all three geolocation failures degrade the same way", () => {
  it.each([
    ["PERMISSION_DENIED", 1 as const],
    ["POSITION_UNAVAILABLE", 2 as const],
    ["TIMEOUT", 3 as const],
  ])("%s: keeps the server list, the location-free heading, and no spinner", async (_name, code) => {
    installGeolocation((_success, failure) => failure(geoError(code)))

    const { container } = render(<NearYouRow serverShops={SERVER_SHOPS} />)
    await userEvent.click(screen.getByRole("button", { name: /use my location/i }))

    await waitFor(() => expect(screen.getByRole("button", { name: /use my location/i })).toBeEnabled())
    expect(headingsSayingNearYou().map((h) => h.textContent)).toEqual([])
    expect(screen.getByRole("heading", { name: /kitchens on j'toye/i })).toBeTruthy()
    expect(cardSlugs()).toEqual([
      "brixton-village-grill",
      "mama-ades-kitchen",
      "belfast-bap-co",
    ])
    // No spinner survives. The positive control for this selector is the test
    // below, which finds one while the request is still outstanding.
    expect(container.querySelector(".animate-spin")).toBeNull()
    // The visitor is told something happened, not left guessing.
    expect(screen.getByText(/showing every kitchen/i)).toBeTruthy()
  })

  it("CONTROL: a spinner IS shown while the fix is outstanding", async () => {
    // Without this, every "no spinner" assertion above would also pass against a
    // selector that never matched anything.
    installGeolocation(() => {
      /* never calls back — the fix is still pending */
    })
    const { container } = render(<NearYouRow serverShops={SERVER_SHOPS} />)
    await userEvent.click(screen.getByRole("button", { name: /use my location/i }))
    await waitFor(() => expect(container.querySelector(".animate-spin")).not.toBeNull())
  })

  it("a failed shop request falls back too, rather than emptying the row", async () => {
    installGeolocation((success) => success(POSITION))
    mockGet.mockRejectedValue(new Error("429"))

    const { container } = render(<NearYouRow serverShops={SERVER_SHOPS} />)
    await userEvent.click(screen.getByRole("button", { name: /use my location/i }))

    await waitFor(() => expect(screen.getByText(/showing every kitchen/i)).toBeTruthy())
    expect(headingsSayingNearYou().map((h) => h.textContent)).toEqual([])
    expect(cardSlugs()).toHaveLength(3)
    expect(container.querySelector(".animate-spin")).toBeNull()
  })
})

describe("NearYouRow — the goods it must not displace", () => {
  it("keeps the DishScroller region with its label byte-identical", () => {
    render(<NearYouRow serverShops={SERVER_SHOPS} />)
    // marketing-dish-scroller.spec.ts:19's selector. An accessible name on a
    // scroll region, not a heading, so the locality criterion does not reach it.
    expect(screen.getByRole("region", { name: "Dishes cooking near you" })).toBeTruthy()
  })

  it("keeps the route to the full catalogue in every state", () => {
    render(<NearYouRow serverShops={SERVER_SHOPS} />)
    expect(
      screen.getByRole("link", { name: /see all kitchens/i }).getAttribute("href")
    ).toBe("/shop")
  })

  it("announces the heading and the disclosure through a live region", () => {
    const { container } = render(<NearYouRow serverShops={SERVER_SHOPS} />)
    const live = container.querySelector("[aria-live]")
    expect(live).not.toBeNull()
    // The heading must be INSIDE it — a live region that does not contain the
    // thing that changes announces nothing.
    expect(within(live as HTMLElement).getByRole("heading")).toBeTruthy()
  })
})
