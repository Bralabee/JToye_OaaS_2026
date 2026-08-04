/**
 * #306 — the marketing status filter runs client-side over one server page, so
 * the screen must not present its results as if they described the whole set.
 *
 * WHAT IS AND IS NOT FIXED HERE. `GET /promotions` and `GET /announcements`
 * accept no status parameter; measured against the live API on 2026-08-03,
 * `?status=active` and `?status=nonsense-value-xyz` each returned the identical
 * unfiltered page (totalElements=3) that no parameter returns. The server-side
 * date-window predicate is therefore a backend change and is escalated, not
 * faked. What the browser owns — and what these tests lock in — is that the
 * count, the disclosure and the empty state tell the truth about a page-local
 * narrow.
 *
 * WHY THE MULTI-PAGE FIXTURE IS 45 ROWS AND NOT 3. The seeded tenant holds 3
 * promotions and 1 announcement against a PAGE_SIZE of 20, i.e. exactly one
 * page — so the multi-page half of this defect CANNOT arise from the data that
 * exists, and no one has ever seen it do so. The condition is forced here
 * deliberately. The fixture total (45) is set to EXCEED the page size (20) on
 * purpose: a fixture whose total is smaller than a page makes correct paging
 * look broken, which has produced a false diagnosis on this repo before.
 */
import { configure, render, screen, waitFor, fireEvent, within } from "@testing-library/react"
import MarketingPage from "../marketing/page"
import apiClient from "@/lib/api-client"

configure({ asyncUtilTimeout: 5000 })

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/lib/shop-context", () => ({
  ALL_SHOPS_CONTEXT: "all",
  getShopContext: jest.fn(() => "all"),
  setShopContext: jest.fn(),
  subscribeShopContext: jest.fn(() => () => {}),
}))

// The `toast` identity must be STABLE across renders. `useToast: () => ({ toast:
// jest.fn() })` hands back a fresh function every render, which changes the
// identity of `fetchPromotions`/`fetchAnnouncements` (both list `toast` in their
// useCallback deps), refires their effects, and drives React to "Maximum update
// depth exceeded". Measured: with an unstable mock this file logged that error
// on every render and the announcements test timed out at 5s; with the shared
// instance below it passes in ~1s.
const mockToast = jest.fn()
jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: mockToast }),
}))

const SHOP_A = "aaaaaaaa-1111-1111-1111-111111111111"
const shops = [{ id: SHOP_A, tenantId: "t-1", name: "Peckham Kitchen", published: true }]

// Anchored to Date.now() rather than to literals: `getPromotionStatus` compares
// against the clock at render, so fixed dates would silently reclassify every
// row the moment they fell into the past and turn this file into a time bomb.
const DAY = 24 * 60 * 60 * 1000
const iso = (offsetDays: number) => new Date(Date.now() + offsetDays * DAY).toISOString()

type Status = "active" | "upcoming" | "expired"

const promo = (id: string, status: Status) => ({
  id,
  shopId: SHOP_A,
  label: `Promo ${id}`,
  discountType: "PERCENTAGE",
  discountPercent: 10,
  discountAmountPennies: null,
  category: null,
  validFrom: status === "upcoming" ? iso(1) : iso(-30),
  validUntil: status === "expired" ? iso(-1) : iso(30),
  active: true,
  createdAt: iso(-40),
})

const announcement = (id: string, status: Status) => ({
  id,
  shopId: SHOP_A,
  title: `Announcement ${id}`,
  body: null,
  validFrom: status === "upcoming" ? iso(1) : iso(-30),
  validUntil: status === "expired" ? iso(-1) : iso(30),
  active: true,
  createdAt: iso(-40),
})

/**
 * A fake server whose page metadata is COHERENT with its content: the caller
 * states the true total, and `totalPages` is derived from it rather than
 * asserted independently. An incoherent triple here would make a correct
 * implementation look wrong.
 */
const PAGE_SIZE = 20
function pagedResponse<T>(rowsOnThisPage: T[], total: number) {
  return {
    data: {
      content: rowsOnThisPage,
      totalElements: total,
      totalPages: Math.ceil(total / PAGE_SIZE),
      size: PAGE_SIZE,
    },
  }
}

function mockServer(opts: {
  promoRows: unknown[]
  promoTotal: number
  annRows?: unknown[]
  annTotal?: number
}) {
  mockedApiClient.get.mockImplementation(((url: string) => {
    if (url.startsWith("/api/v1/promotions")) {
      return Promise.resolve(pagedResponse(opts.promoRows, opts.promoTotal))
    }
    if (url.startsWith("/api/v1/announcements")) {
      return Promise.resolve(pagedResponse(opts.annRows ?? [], opts.annTotal ?? 0))
    }
    if (url.startsWith("/api/v1/shops")) {
      return Promise.resolve({
        data: { content: shops, totalPages: 1, totalElements: shops.length },
      })
    }
    return Promise.resolve({ data: { content: [], totalPages: 0, totalElements: 0 } })
  }) as jest.Mock)
}

const clickFilter = (name: RegExp) =>
  fireEvent.click(screen.getAllByRole("button", { name })[0])

beforeEach(() => {
  jest.clearAllMocks()
})

describe("#306 — a client-side status filter must not misreport the set", () => {
  describe("single page (the shape the seeded tenant actually has)", () => {
    // 2 active + 1 expired + 0 upcoming — the live tenant's exact distribution.
    const rows = [promo("a1", "active"), promo("a2", "active"), promo("e1", "expired")]

    beforeEach(() => {
      mockServer({ promoRows: rows, promoTotal: 3 })
    })

    it("leaves the unfiltered count byte-identical to its pre-fix wording", async () => {
      render(<MarketingPage />)
      await waitFor(() => expect(screen.getByText("Promo a1")).toBeInTheDocument())

      // The default view was never wrong; a fix that reworded it would be a
      // regression dressed up as a change.
      expect(screen.getByText("3 promotions in total")).toBeInTheDocument()
    })

    it("counts what is on screen, not the unfiltered total, under a filter", async () => {
      render(<MarketingPage />)
      await waitFor(() => expect(screen.getByText("Promo a1")).toBeInTheDocument())

      clickFilter(/^active$/i)

      // Pre-fix this line read "3 promotions in total" over a 2-row table.
      await waitFor(() =>
        expect(screen.getByText("2 active of 3 promotions in total")).toBeInTheDocument()
      )
      expect(screen.queryByText("3 promotions in total")).not.toBeInTheDocument()
    })

    it("does not caveat a count that is exact, because one page holds everything", async () => {
      render(<MarketingPage />)
      await waitFor(() => expect(screen.getByText("Promo a1")).toBeInTheDocument())

      clickFilter(/^expired$/i)

      await waitFor(() =>
        expect(screen.getByText("1 expired of 3 promotions in total")).toBeInTheDocument()
      )
      // A warning shown over a correct view is one vendors learn to ignore.
      expect(screen.queryByText(/narrows this page only/i)).not.toBeInTheDocument()
    })

    it("explains an empty filter result instead of rendering a bodyless table", async () => {
      render(<MarketingPage />)
      await waitFor(() => expect(screen.getByText("Promo a1")).toBeInTheDocument())

      clickFilter(/^upcoming$/i)

      // Pre-fix: the table rendered its header row over zero body rows and said
      // nothing at all — measured on the live stack, screenshot in the report.
      await waitFor(() =>
        expect(screen.getByRole("heading", { name: /no upcoming promotions$/i })).toBeInTheDocument()
      )
      expect(screen.queryByRole("table")).not.toBeInTheDocument()
      expect(screen.getByText(/none of your 3 promotions are upcoming/i)).toBeInTheDocument()
    })

    it("offers a way back out of an empty filter result", async () => {
      render(<MarketingPage />)
      await waitFor(() => expect(screen.getByText("Promo a1")).toBeInTheDocument())

      clickFilter(/^upcoming$/i)
      await waitFor(() =>
        expect(screen.getByRole("button", { name: /show all promotions/i })).toBeInTheDocument()
      )

      fireEvent.click(screen.getByRole("button", { name: /show all promotions/i }))

      await waitFor(() => expect(screen.getByText("Promo a1")).toBeInTheDocument())
      expect(screen.getByText("3 promotions in total")).toBeInTheDocument()
    })
  })

  describe("multiple pages (FORCED — the seeded tenant cannot produce this)", () => {
    // 45 rows across 3 pages; page 0 carries 20, of which 2 are active.
    const pageZero = [
      promo("a1", "active"),
      promo("a2", "active"),
      ...Array.from({ length: 18 }, (_, i) => promo(`e${i}`, "expired")),
    ]

    beforeEach(() => {
      mockServer({ promoRows: pageZero, promoTotal: 45 })
    })

    it("says the count is page-local rather than presenting it as the total", async () => {
      render(<MarketingPage />)
      await waitFor(() => expect(screen.getByText("Promo a1")).toBeInTheDocument())
      expect(screen.getByText("45 promotions in total")).toBeInTheDocument()

      clickFilter(/^active$/i)

      // The number 2 is true of this page and false of the set. Saying which is
      // the whole point of the fix.
      await waitFor(() =>
        expect(
          screen.getByText("2 active on this page — of 45 promotions in total")
        ).toBeInTheDocument()
      )
    })

    it("discloses that other pages may hold more matches", async () => {
      render(<MarketingPage />)
      await waitFor(() => expect(screen.getByText("Promo a1")).toBeInTheDocument())

      clickFilter(/^active$/i)

      const notice = await screen.findByRole("status")
      expect(within(notice).getByText(/narrows this page only/i)).toBeInTheDocument()
      expect(within(notice).getByText(/other pages may hold more active promotions/i)).toBeInTheDocument()
    })

    it("stays silent — and unchanged — in the unfiltered view", async () => {
      render(<MarketingPage />)
      await waitFor(() => expect(screen.getByText("Promo a1")).toBeInTheDocument())

      // The live region is mounted so its announcements are reliable, but it
      // must carry nothing when there is nothing to disclose.
      expect(screen.getByRole("status")).toBeEmptyDOMElement()
      expect(screen.getByText("45 promotions in total")).toBeInTheDocument()
    })
  })

  describe("announcements — the same screen, the second list", () => {
    it("applies the identical count honesty to the announcements tab", async () => {
      mockServer({
        promoRows: [promo("a1", "active")],
        promoTotal: 1,
        annRows: [
          announcement("x1", "active"),
          announcement("x2", "active"),
          announcement("x3", "expired"),
        ],
        annTotal: 3,
      })

      render(<MarketingPage />)
      await waitFor(() => expect(screen.getByText("Promo a1")).toBeInTheDocument())

      fireEvent.click(screen.getByRole("button", { name: /^announcements$/i }))
      await waitFor(() => expect(screen.getByText("Announcement x1")).toBeInTheDocument())
      expect(screen.getByText("3 announcements in total")).toBeInTheDocument()

      clickFilter(/^expired$/i)

      await waitFor(() =>
        expect(screen.getByText("1 expired of 3 announcements in total")).toBeInTheDocument()
      )
    })
  })
})
