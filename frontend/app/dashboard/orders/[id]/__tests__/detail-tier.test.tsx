/**
 * BRANCH PARITY for the Detail tier — phase 35, plan 35-05.
 *
 * WHY THIS FILE EXISTS AT ALL, and why it is required rather than optional.
 *
 * The order detail route is not a single-return component: it has an early
 * return for `loading`, another for `error`, and the loaded return at the
 * bottom. An edit that tiers only "the main return" reaches exactly one of the
 * three. After plan 35-02 the dashboard band declares the Shell tier (1700px),
 * so an untiered branch renders its content at the shell's content box while the
 * loaded branch renders at the Detail cap — the page would JUMP several hundred
 * pixels the moment the fetch resolves or fails. A width contract that
 * introduces a width jump has failed on its own terms.
 *
 * Nothing else in the phase can catch that. The browser arm (plan 35-08)
 * measures a LOADED page, and its dashboard half belongs to a spec that no
 * current tree executes (#683 records the nightly lane as dark). jsdom cannot
 * measure a width, but it CAN render all three states and compare their
 * declarations TO EACH OTHER — which is the checkable form of "the page does not
 * change width as its request resolves".
 *
 * Comparing branches to each other rather than each to a literal is deliberate:
 * the defect is the DIFFERENCE, and an assertion phrased as three independent
 * facts would pass a review while still permitting a jump if the literal were
 * ever changed in only one place.
 *
 * This file lives in the DETAIL route's own __tests__ directory. The list page's
 * directory (`app/dashboard/orders/__tests__/`) is owned concurrently by plan
 * 35-03, and neither of its two suites mounts this route.
 */
import { render, screen, waitFor, within } from "@testing-library/react"
import { useParams } from "next/navigation"
import OrderDetailPage from "../page"
import apiClient from "@/lib/api-client"
import { WIDTH_TIER_CLASS } from "@/components/layout/content-tier"
import type { OrderDetail } from "@/types/api"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}))

// SSE hook (#92) — no real EventSource in jsdom.
jest.mock("@/hooks/use-order-events", () => ({
  useOrderEvents: jest.fn(),
}))

const ORDER_ID = "11111111-2222-3333-4444-555555555555"

const ORDER: OrderDetail = {
  id: ORDER_ID,
  tenantId: "tenant-1",
  shopId: "shop-1",
  orderNumber: "ORD-00000000-20260829-ABCDEF01",
  status: "PENDING",
  customerName: "Jane Doe",
  customerEmail: "jane@example.com",
  totalAmountPennies: 2149,
  items: [
    {
      id: "item-1",
      productId: "product-1",
      productName: "Jollof Rice",
      quantity: 2,
      unitPricePennies: 1000,
      totalPricePennies: 2000,
      createdAt: "2026-08-29T10:00:00.000Z",
    },
  ],
  createdAt: "2026-08-29T10:00:00.000Z",
  updatedAt: "2026-08-29T10:05:00.000Z",
}

// --- The declaration under test ---------------------------------------------

/**
 * What an element SAYS about its width. Two fields, because two different
 * defects are in scope: a missing/renamed tier attribute, and a second cap
 * layered on top of the first (which resolves by cascade, looks correct in
 * review, and is wrong at exactly one viewport).
 */
interface TierDeclaration {
  tier: string | null
  maxWidthClasses: string[]
}

/**
 * Token match, never a substring: `max-w-` appears inside plenty of unrelated
 * class strings in this tree, and a substring search would count them. The
 * optional variant prefixes are matched so `sm:max-w-lg` counts as a cap too —
 * a responsive second cap is still a second cap.
 */
const MAX_WIDTH_TOKEN = /^(?:[A-Za-z0-9_[\]().%-]+:)*max-w-/

function declarationOf(el: Element): TierDeclaration {
  return {
    tier: el.getAttribute("data-width-tier"),
    maxWidthClasses: Array.from(el.classList)
      .filter((c) => MAX_WIDTH_TOKEN.test(c))
      .sort(),
  }
}

// --- Branch drivers ----------------------------------------------------------
//
// Each returns the page's ROOT element for one render branch, and each proves
// which branch it actually got before returning it. A driver that silently
// returned the wrong branch would make every assertion below vacuous.

function renderLoadingBranch(): Element {
  // A promise that never settles: the component stays on its initial
  // `loading === true` state, which is the branch we want to inspect.
  mockedApiClient.get.mockReturnValue(new Promise(() => {}) as never)
  const { container } = render(<OrderDetailPage />)
  expect(container.querySelector(".animate-spin")).not.toBeNull()
  return container.firstElementChild as Element
}

async function renderErrorBranch(): Promise<Element> {
  mockedApiClient.get.mockRejectedValue({ response: { status: 404 } })
  const { container } = render(<OrderDetailPage />)
  await within(container as HTMLElement).findByRole("alert")
  return container.firstElementChild as Element
}

async function renderLoadedBranch(): Promise<Element> {
  mockedApiClient.get.mockResolvedValue({ data: ORDER } as never)
  const { container } = render(<OrderDetailPage />)
  // The loaded branch is the one with neither the spinner nor the alert.
  await waitFor(() => {
    expect(container.querySelector(".animate-spin")).toBeNull()
  })
  expect(within(container as HTMLElement).queryByRole("alert")).toBeNull()
  return container.firstElementChild as Element
}

const BRANCHES: Array<[string, () => Element | Promise<Element>]> = [
  ["loading", renderLoadingBranch],
  ["error", renderErrorBranch],
  ["loaded", renderLoadedBranch],
]

describe("Order detail — the Detail tier on EVERY page-level render branch", () => {
  beforeEach(() => {
    jest.clearAllMocks()
    ;(useParams as jest.Mock).mockReturnValue({ id: ORDER_ID })
  })

  // --- The instrument's own controls -----------------------------------------
  //
  // Run first, and they carry no dependency on the page: they prove the
  // extractor can SEE a cap, can see a SECOND cap, and can tell two
  // declarations apart. Without them, every assertion below could be passing
  // because the instrument is incapable of failing.

  describe("CONTROL — the instrument can fail", () => {
    it("counts two max-width classes when an element carries two", () => {
      const el = document.createElement("div")
      el.className = `mx-auto ${WIDTH_TIER_CLASS.detail} ${WIDTH_TIER_CLASS.shell} p-6`
      el.setAttribute("data-width-tier", "detail")
      expect(declarationOf(el).maxWidthClasses).toHaveLength(2)
    })

    it("reports a null tier when no element declares one", () => {
      const el = document.createElement("div")
      el.className = "space-y-4 p-6"
      expect(declarationOf(el)).toEqual({ tier: null, maxWidthClasses: [] })
    })

    it("distinguishes two declarations that differ", () => {
      const tiered = document.createElement("div")
      tiered.className = `mx-auto ${WIDTH_TIER_CLASS.detail}`
      tiered.setAttribute("data-width-tier", "detail")
      const bare = document.createElement("div")
      bare.className = "space-y-4"
      expect(declarationOf(tiered)).not.toEqual(declarationOf(bare))
    })
  })

  // --- The declaration, per branch -------------------------------------------

  describe.each(BRANCHES)("the %s branch", (name, driver) => {
    it("declares the detail width tier", async () => {
      const root = await driver()
      expect(declarationOf(root).tier).toBe("detail")
    })

    it("carries the detail max-width utility from the tier vocabulary", async () => {
      const root = await driver()
      expect(root.classList.contains(WIDTH_TIER_CLASS.detail)).toBe(true)
    })

    it("centres inside the wider Shell band", async () => {
      const root = await driver()
      expect(root.classList.contains("mx-auto")).toBe(true)
    })

    it("carries exactly ONE max-width class", async () => {
      const root = await driver()
      expect(declarationOf(root).maxWidthClasses).toEqual([WIDTH_TIER_CLASS.detail])
    })
  })

  // --- BRANCH PARITY, the point of the file ----------------------------------

  it("the LOADING branch renders the same declaration as the loaded branch", async () => {
    const loading = declarationOf(renderLoadingBranch())
    const loaded = declarationOf(await renderLoadedBranch())
    expect(loading).toEqual(loaded)
  })

  it("the ERROR branch renders the same declaration as the loaded branch", async () => {
    const errored = declarationOf(await renderErrorBranch())
    const loaded = declarationOf(await renderLoadedBranch())
    expect(errored).toEqual(loaded)
  })

  // --- The displaced goods: what the tier edit must NOT take away ------------

  const PRESERVED: Array<[string, () => Element | Promise<Element>, string[]]> = [
    ["loading", renderLoadingBranch, ["flex", "h-full", "items-center", "justify-center", "p-12"]],
    ["error", renderErrorBranch, ["space-y-4", "p-6"]],
    ["loaded", renderLoadedBranch, ["space-y-4", "p-6"]],
  ]

  describe.each(PRESERVED)("the %s branch keeps its existing rhythm", (name, driver, classes) => {
    it.each(classes)("still carries %s", async (cls) => {
      const root = await driver()
      expect(root.classList.contains(cls)).toBe(true)
    })
  })
})
