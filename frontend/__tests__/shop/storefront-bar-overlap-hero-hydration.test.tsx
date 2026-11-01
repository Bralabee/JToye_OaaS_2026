/**
 * Swarm epic #964, storefront lane: three menu-page defects a 2026-10-19 persona swarm met on
 * staging, each pinned here so the tree that had them fails.
 *
 *  - SW-JRD-10 / SW-MRC-06: the fixed "View basket" bar (`fixed bottom-0 z-50`) covered the last
 *    card's Add button and a focused stepper on a phone. The bar publishes its measured height as
 *    `--jt-bottom-chrome`; the menu's bottom padding and the card controls' scroll-margin are now
 *    bound to it. jsdom has no layout, so these arms pin the BINDING (the class names that compile
 *    to `calc(var(--jt-bottom-chrome, 0px) + …)`); the PR's proof table carries the Chromium
 *    measurement that scroll-margin moves a focused control clear of a fixed bar.
 *  - SW-MRC-9: the hero "Back" pill was white on bg-black/30, about 3.6:1 over the gold gradient.
 *    The arm computes the pill's contrast on the WORST hero (pure white) from its own class.
 *  - SW-CLR-13: the menu is server-rendered, so an Add tap before hydration was silently dropped.
 *    The server HTML now carries the Add button disabled; the hydrated client enables it.
 */
import { act, render, screen } from "@testing-library/react"

const mockGet = jest.fn()
jest.mock("@/lib/public-api-client", () => ({
  __esModule: true,
  default: { get: (...args: unknown[]) => mockGet(...args) },
}))

let mockItems: Array<{ productId: string; quantity: number }> = []
jest.mock("@/components/storefront/cart-provider", () => ({
  useCart: () => ({
    items: mockItems,
    addItem: jest.fn(),
    removeItem: jest.fn(),
    updateQuantity: jest.fn(),
    clearCart: jest.fn(),
    itemCount: mockItems.length,
    totalPennies: 0,
    shopSlug: "mama-ades-kitchen",
  }),
  CartProvider: ({ children }: { children: React.ReactNode }) => children,
}))

import { ShopDetailClient } from "@/app/shop/[slug]/shop-detail-client"
import type { PublicProduct, PublicShop, ShopDetail } from "@/types/storefront"

const shop: PublicShop = {
  slug: "mama-ades-kitchen",
  name: "Mama Ade's Kitchen",
  description: null,
  address: "1 Test Street, London",
  logoUrl: null,
  bannerUrl: null,
  phone: null,
  email: null,
  latitude: null,
  longitude: null,
  openingHours: null,
  deliveryInfo: null,
  minimumOrderPennies: null,
  deliveryFeePennies: null,
  freeDeliveryThresholdPennies: null,
  tags: null,
}

function dish(id: string, title: string): PublicProduct {
  return {
    id,
    title,
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
    mayContainAllergens: [],
  }
}

const detail: ShopDetail = {
  shop,
  products: { Mains: [dish("p1", "Jollof Rice"), dish("p2", "Puff Puff")] },
  reviews: [],
  reviewCount: 0,
  avgRating: 0,
  announcements: [],
}

async function renderMenu() {
  await act(async () => {
    render(<ShopDetailClient slug={shop.slug} initial={detail} />)
  })
}

const BAR_PADDING = "pb-[calc(var(--jt-bottom-chrome,0px)+1.5rem)]"
const CONTROL_MARGIN = "scroll-mb-[calc(var(--jt-bottom-chrome,0px)+0.75rem)]"

beforeEach(() => {
  mockItems = []
  mockGet.mockReset()
  mockGet.mockResolvedValue({ data: { content: [], totalPages: 0, totalElements: 0 } })
})

describe("SW-JRD-10 / SW-MRC-06: nothing on the menu ends up under the basket bar", () => {
  it("the menu's bottom padding is bound to the bar's published height, not a fixed py-6", async () => {
    await renderMenu()
    const menu = screen.getByTestId("shop-menu")
    // Control: this is the element that holds the dish cards.
    expect(menu).toContainElement(screen.getByRole("button", { name: "Add Puff Puff to basket" }))
    expect(menu.classList).toContain(BAR_PADDING)
    expect(menu.classList).not.toContain("py-6")
    expect(menu.classList).not.toContain("pb-6")
  })

  it("every card's Add button scrolls clear of the bar when focused", async () => {
    await renderMenu()
    for (const title of ["Jollof Rice", "Puff Puff"]) {
      expect(screen.getByRole("button", { name: `Add ${title} to basket` }).classList).toContain(CONTROL_MARGIN)
    }
  })

  it("a focused quantity stepper scrolls clear of the bar (both its buttons)", async () => {
    mockItems = [{ productId: "p2", quantity: 2 }]
    await renderMenu()
    expect(screen.getByRole("button", { name: "Add one more Puff Puff" }).classList).toContain(CONTROL_MARGIN)
    expect(screen.getByRole("button", { name: "Decrease quantity of Puff Puff" }).classList).toContain(
      CONTROL_MARGIN
    )
  })
})

/** WCAG relative luminance of an sRGB channel triple (0-255). */
function luminance([r, g, b]: number[]): number {
  const lin = (c: number) => {
    const s = c / 255
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
  }
  return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
}

function contrast(a: number[], b: number[]): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (hi + 0.05) / (lo + 0.05)
}

/** The pill's resting `bg-black/NN` alpha, read from its class (hover: variants excluded). */
function restingBlackAlpha(el: HTMLElement): number {
  const match = Array.from(el.classList)
    .filter((c) => !c.includes(":"))
    .map((c) => /^bg-black\/(\d+)$/.exec(c))
    .find(Boolean)
  expect(match).toBeTruthy()
  return Number(match![1]) / 100
}

describe("SW-MRC-9: the hero Back pill reads at 4.5:1 or better on any hero", () => {
  it("white text on the pill's own fill clears 4.5:1 even over a pure-white banner", async () => {
    await renderMenu()
    const back = screen.getByRole("link", { name: "Back" })
    expect(back).toBe(screen.getByTestId("shop-hero-back"))
    expect(back.classList).toContain("text-white")
    const alpha = restingBlackAlpha(back)
    // Worst case: black at `alpha` over white (255) composites to 255 * (1 - alpha) per channel.
    const fill = Math.round(255 * (1 - alpha))
    const ratio = contrast([255, 255, 255], [fill, fill, fill])
    expect(ratio).toBeGreaterThanOrEqual(4.5)
  })

  it("the contrast helper itself can fail: the old bg-black/30 over white is far below 4.5:1 (control)", () => {
    const fill = Math.round(255 * (1 - 0.3))
    expect(contrast([255, 255, 255], [fill, fill, fill])).toBeLessThan(4.5)
  })
})

describe("SW-CLR-13: an Add tap before hydration is not silently dropped", () => {
  it("the SERVER HTML carries every card's Add button disabled", async () => {
    const { renderToString } = await import("react-dom/server")
    const html = renderToString(<ShopDetailClient slug={shop.slug} initial={detail} />)
    const addButtons = html.match(/<button[^>]*aria-label="Add [^"]+ to basket"[^>]*>/g) ?? []
    // Control: both dishes' Add buttons are in the server HTML, so the check below has subjects.
    expect(addButtons).toHaveLength(2)
    for (const tag of addButtons) expect(tag).toMatch(/\sdisabled=""/)
  })

  // PR #1075 review round 1 (inline 4435453169): the arm that stood here used render(), which
  // never hydrates (it mounts with the CLIENT snapshot), so it passed on the pre-fix tree too. This
  // one drives the real path: the server HTML is put in the DOM, checked BEFORE React attaches
  // (disabled: a tap here has no handler), then hydrateRoot attaches to that same markup and the
  // same buttons must become enabled. It fails if the gate is reverted (first half) and if the
  // button never enables after hydration (second half).
  it("hydrating the server HTML turns the same Add buttons from disabled to enabled", async () => {
    const { renderToString } = await import("react-dom/server")
    const { hydrateRoot } = await import("react-dom/client")
    const tree = <ShopDetailClient slug={shop.slug} initial={detail} />
    const container = document.createElement("div")
    container.innerHTML = renderToString(tree)
    document.body.appendChild(container)
    const adds = () =>
      Array.from(container.querySelectorAll<HTMLButtonElement>('button[aria-label$=" to basket"]'))

    // Before hydration: the server markup, no React handlers attached.
    const before = adds()
    expect(before).toHaveLength(2)
    for (const b of before) expect(b.disabled).toBe(true)

    let root: ReturnType<typeof hydrateRoot> | undefined
    await act(async () => {
      root = hydrateRoot(container, tree, { onRecoverableError: () => {} })
    })
    // After hydration: React adopted the SAME nodes (no client re-mount) and enabled them.
    const after = adds()
    expect(after).toHaveLength(2)
    after.forEach((b, i) => {
      expect(b).toBe(before[i])
      expect(b.disabled).toBe(false)
    })
    act(() => root?.unmount())
    container.remove()
  })
})
