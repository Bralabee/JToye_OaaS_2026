/**
 * Each step of the storefront journey has its own title (37-51, UI-SPEC § F4, RWO-084 / UXT-084
 * #853, D-05).
 *
 * The basket, checkout and order pages are `"use client"` modules, which cannot export metadata,
 * and the storefront layout above them is a client component too. So every step inherited the
 * ROOT title, "J'Toye OaaS - Multi-Tenant Order Management": the Next route announcer read the
 * same words at every step, and a screen-reader customer could not tell basket from checkout from
 * confirmation. A per-route `layout.tsx` is a server module, so it can carry `generateMetadata`:
 *
 *   Basket · {Shop} — J'Toye        /shop/{slug}/cart
 *   Checkout · {Shop} — J'Toye      /shop/{slug}/checkout
 *   Order {number} · {Shop} — J'Toye  /shop/{slug}/orders/{orderNumber}
 *   Track your order — J'Toye       /track (existing, unchanged)
 *
 * The shop name comes from ONE read of the public shop (`/public/shops/{slug}`) — not the full
 * storefront load, which is four upstream calls (menu, reviews, announcements) for a title. A shop
 * that cannot be read gets the step title without a name (never a guessed one), and the layouts
 * never 404 by themselves (the pages own their own empty states). No `robots` key is written: the
 * indexing posture of these steps (disallowed in robots.txt, inherited metadata) is unchanged.
 *
 * T-37-119: the title carries only what the page already shows — the shop name and the order
 * number from the URL.
 */
import type { Metadata } from "next"
import { metadata as trackMetadata } from "@/app/track/layout"

type SlugProps = { params: Promise<{ slug: string }> }
type OrderProps = { params: Promise<{ slug: string; orderNumber: string }> }
type GenerateMetadata<P> = (props: P) => Promise<Metadata>

/**
 * The route's `generateMetadata`, read from its layout module. A route with NO layout module is
 * the pre-37-51 tree: that is reported as "this route exports no generateMetadata" (an assertion
 * failure naming the missing behaviour), while any OTHER import error is rethrown untouched.
 */
async function routeMetadata<P>(load: () => Promise<{ generateMetadata?: GenerateMetadata<P> }>): Promise<GenerateMetadata<P>> {
  let mod: { generateMetadata?: GenerateMetadata<P> } = {}
  try {
    mod = await load()
  } catch (e) {
    if (!/Cannot find module/.test(String(e))) throw e
  }
  expect(typeof mod.generateMetadata).toBe("function")
  return mod.generateMetadata as GenerateMetadata<P>
}

const cartMetadata = async (props: SlugProps) =>
  (await routeMetadata<SlugProps>(() => import("@/app/shop/[slug]/cart/layout")))(props)
const checkoutMetadata = async (props: SlugProps) =>
  (await routeMetadata<SlugProps>(() => import("@/app/shop/[slug]/checkout/layout")))(props)
const orderMetadata = async (props: OrderProps) =>
  (await routeMetadata<OrderProps>(() => import("@/app/shop/[slug]/orders/[orderNumber]/layout")))(props)

const SLUG = "mama-ade"
const ORDER_NUMBER = "ORD-3751TITL-20261014-STEPS003"
const SHOP = { slug: SLUG, name: "Mama Ade's Kitchen" }

const realFetch = global.fetch
let fetchMock: jest.Mock

function answerShop(status: number, body: unknown = SHOP) {
  fetchMock = jest.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    if (/\/public\/shops\/[^/?]+$/.test(url)) {
      return { ok: status >= 200 && status < 300, status, json: async () => body } as Response
    }
    throw new Error(`unexpected upstream call ${url}`)
  })
  global.fetch = fetchMock as unknown as typeof fetch
}

function slugParams() {
  return { params: Promise.resolve({ slug: SLUG }) }
}
function orderParams() {
  return { params: Promise.resolve({ slug: SLUG, orderNumber: ORDER_NUMBER }) }
}

afterEach(() => {
  global.fetch = realFetch
})

describe("F4: every step of the journey says where the customer is", () => {
  it("basket: 'Basket · {Shop} — J'Toye'", async () => {
    answerShop(200)
    const m: Metadata = await cartMetadata(slugParams())
    expect(m.title).toBe("Basket · Mama Ade's Kitchen — J'Toye")
  })

  it("checkout: 'Checkout · {Shop} — J'Toye'", async () => {
    answerShop(200)
    const m: Metadata = await checkoutMetadata(slugParams())
    expect(m.title).toBe("Checkout · Mama Ade's Kitchen — J'Toye")
  })

  it("order: 'Order {number} · {Shop} — J'Toye'", async () => {
    answerShop(200)
    const m: Metadata = await orderMetadata(orderParams())
    expect(m.title).toBe(`Order ${ORDER_NUMBER} · Mama Ade's Kitchen — J'Toye`)
  })

  it("/track keeps 'Track your order — J'Toye', and the four titles are all different", async () => {
    answerShop(200)
    const titles = [
      (await cartMetadata(slugParams())).title,
      (await checkoutMetadata(slugParams())).title,
      (await orderMetadata(orderParams())).title,
      trackMetadata.title,
    ]
    expect(trackMetadata.title).toBe("Track your order — J'Toye")
    expect(new Set(titles).size).toBe(4)
  })
})

describe("F4: how the shop name is read, and what happens when it cannot be", () => {
  it("one upstream read of the public shop per title, by slug — not the four-call storefront load", async () => {
    answerShop(200)
    await cartMetadata(slugParams())
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(String(fetchMock.mock.calls[0][0])).toMatch(new RegExp(`/public/shops/${SLUG}$`))
  })

  it("a shop that cannot be read (5xx, 429) gives the step title with no shop name, never a guess", async () => {
    answerShop(503)
    expect((await cartMetadata(slugParams())).title).toBe("Basket — J'Toye")
    answerShop(429)
    expect((await checkoutMetadata(slugParams())).title).toBe("Checkout — J'Toye")
    answerShop(503)
    expect((await orderMetadata(orderParams())).title).toBe(`Order ${ORDER_NUMBER} — J'Toye`)
  })

  it("an unknown shop (404) does not throw from metadata: the page owns its own not-found state", async () => {
    answerShop(404)
    await expect(cartMetadata(slugParams())).resolves.toEqual(
      expect.objectContaining({ title: "Basket — J'Toye" })
    )
  })

  it("no robots key: the indexing posture of the three steps is unchanged", async () => {
    answerShop(200)
    for (const m of [
      await cartMetadata(slugParams()),
      await checkoutMetadata(slugParams()),
      await orderMetadata(orderParams()),
    ]) {
      expect(m).not.toHaveProperty("robots")
    }
  })
})
