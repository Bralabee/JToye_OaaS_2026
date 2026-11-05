/** @jest-environment jsdom */
/**
 * #877 UXT-111 (J-14, P2-GRA-20): "React error #418 appeared once" on /shop/orders and /track.
 *
 * #418 is production React's "the server rendered text that the client did not". The persona runs saw it
 * once, after signing out and in, and never reproduced it. This does not find a cause either: it pins what
 * can be pinned. Each page is rendered the way the server renders it (no browser state), its HTML is handed
 * to the browser as markup, and `hydrateRoot` runs over it WITH the state that makes the client differ from the
 * server on a returning customer (a stored tracking email, an order number in the URL, a customer whose
 * orders include dates). Any hydration mismatch React reports through `onRecoverableError` or
 * `console.error` fails the test.
 *
 * The control matters more than the arms: a harness that cannot see a mismatch would also pass. A component
 * that deliberately renders different text on the server is run through the same harness and must be caught.
 */
import "@testing-library/react"
import { act } from "react"
import { hydrateRoot } from "react-dom/client"
import { renderToString } from "react-dom/server"
import { useSearchParams } from "next/navigation"
import TrackOrderPage from "@/app/track/page"
import { OrdersClient } from "@/app/shop/orders/orders-client"
import type { OrdersLoad } from "@/lib/customer-orders"

jest.mock("@/lib/public-api-client", () => ({ __esModule: true, default: { get: jest.fn(), post: jest.fn() } }))
jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: () =>
    Promise.resolve({ profile: { sub: "u1", email: "buyer@example.com", name: "Buyer" }, expiresAt: 9999999999 }),
  customerLogin: jest.fn(),
  customerLogout: jest.fn(),
}))

let serverRender = false

/** Server-render `ui` (no window), put the markup in the document, hydrate it, return every complaint. */
async function hydrationComplaints(ui: () => React.ReactElement, beforeHydrate?: () => void): Promise<string[]> {
  // The server render sees no browser state: storage is empty while it runs, which is what the pages'
  // own `typeof window === "undefined"` branches return on a server. (jsdom's `window` cannot be deleted,
  // so the server's absence of state is simulated by the absence of the state, not of the global.)
  sessionStorage.clear()
  localStorage.clear()
  serverRender = true
  let html: string
  try {
    html = renderToString(ui())
  } finally {
    serverRender = false
  }
  beforeHydrate?.()
  const host = document.createElement("div")
  host.innerHTML = html
  document.body.appendChild(host)
  const complaints: string[] = []
  const spy = jest.spyOn(console, "error").mockImplementation((...args) => {
    complaints.push(String(args[0]).slice(0, 200))
  })
  await act(async () => {
    hydrateRoot(host, ui(), {
      onRecoverableError: (e) => complaints.push(`recoverable: ${String((e as Error).message).slice(0, 200)}`),
    })
  })
  spy.mockRestore()
  host.remove()
  return complaints
}

beforeEach(() => {
  sessionStorage.clear()
  localStorage.clear()
  ;(useSearchParams as jest.Mock).mockReturnValue(new URLSearchParams(""))
  global.fetch = jest.fn().mockResolvedValue({
    ok: true,
    status: 200,
    json: async () => ({ content: [], totalElements: 0 }),
  }) as unknown as typeof fetch
})

function Mismatch() {
  // Different text on the server than in the browser: exactly what #418 reports.
  return <p>{serverRender ? "server text" : "client text"}</p>
}

describe("UXT-111: hydration of the two pages the persona runs saw #418 on", () => {
  it("CONTROL: the harness sees a server/client text mismatch", async () => {
    const complaints = await hydrationComplaints(() => <Mismatch />)
    expect(complaints.length).toBeGreaterThan(0)
    expect(complaints.join(" ")).toMatch(/hydrat|did not match|didn't match/i)
  })

  it("/track hydrates cleanly with a stored tracking email and an order number in the address", async () => {
    ;(useSearchParams as jest.Mock).mockReturnValue(new URLSearchParams("order=ORD-AAAA1111"))
    const complaints = await hydrationComplaints(
      () => <TrackOrderPage />,
      () => sessionStorage.setItem("jtoye-track-email", "buyer@example.com")
    )
    expect(complaints).toEqual([])
  })

  it("/shop/orders hydrates cleanly over a server-rendered list that carries dates", async () => {
    const initial: OrdersLoad = {
      state: "ok",
      totalElements: 1,
      orders: [
        {
          orderNumber: "ORD-AAAA",
          status: "COMPLETED",
          shopName: "Mama Ade's Kitchen",
          shopSlug: "mama-ades-kitchen",
          totalAmountPennies: 1250,
          itemCount: 1,
          unitCount: 1,
          placedVia: "STOREFRONT",
          acknowledgedAllergenMask: 0,
          acknowledgedAllergens: [],
          // 00:01 BST on 4 Oct: the zone-sensitive instant goods P2-CHA-18 is about.
          createdAt: "2026-10-03T23:01:00Z",
          updatedAt: "2026-10-03T23:05:00Z",
        },
      ],
    } as OrdersLoad
    const complaints = await hydrationComplaints(() => <OrdersClient initial={initial} email="buyer@example.com" />)
    expect(complaints).toEqual([])
  })
})
