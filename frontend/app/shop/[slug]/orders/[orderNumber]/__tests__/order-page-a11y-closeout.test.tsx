/**
 * #850 remainder, the order page (MAR-08, MAR-14):
 *
 *   MAR-14  The "Track your order" email prompt (what a visitor with no remembered email sees) named its
 *           field only by a placeholder and had no <h1>: a screen reader had no page title and a field
 *           called "your@email.com". It now has an <h1> and a real <label>.
 *   MAR-08  "Copied!" appeared in a plain span: the button was named (earlier fix) but pressing it said
 *           nothing. It is now announced through a polite live region that is mounted BEFORE the press
 *           (a live region added together with its text is often not announced), and the visible text
 *           is hidden from the accessibility tree so it is not read twice.
 *
 * The page keeps exactly ONE role=status region (order-live-region.test.tsx counts them), so the copy
 * announcement uses aria-live on its own element rather than a second role=status.
 */
import { Suspense } from "react"
import { act, fireEvent, render, screen } from "@testing-library/react"
import OrderTrackingPage from "@/app/shop/[slug]/orders/[orderNumber]/page"

const mockLookup = jest.fn()
jest.mock("@/lib/public-api-client", () => ({
  __esModule: true,
  default: {
    get: (...args: unknown[]) => mockLookup(...args),
    post: (...args: unknown[]) => mockLookup(...args),
  },
}))

jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: () => Promise.resolve(null),
}))

const SLUG = "mama-ade"
const ORDER_NUMBER = "ORD-CLOSEOUT-20261104-A11Y0001"
const EMAIL = "ade@example.com"

const ORDER = {
  orderNumber: ORDER_NUMBER,
  status: "PENDING",
  paymentStatus: "NONE",
  shopName: "Mama Ade's Kitchen",
  fulfilmentType: "COLLECTION",
  totalAmountPennies: 1200,
  itemCount: 1,
  unitCount: 1,
  createdAt: "2026-11-04T10:00:00Z",
  updatedAt: "2026-11-04T10:00:05Z",
}

function resolvedThenable<T>(value: T): Promise<T> {
  const p: Promise<T> & { status?: string; value?: T } = Promise.resolve(value)
  p.status = "fulfilled"
  p.value = value
  return p
}

function renderOrderPage() {
  return render(
    <Suspense fallback={<div>loading</div>}>
      <OrderTrackingPage params={resolvedThenable({ slug: SLUG, orderNumber: ORDER_NUMBER })} />
    </Suspense>
  )
}

beforeEach(() => {
  mockLookup.mockReset()
  mockLookup.mockImplementation((url: string) =>
    url === `/public/orders/${ORDER_NUMBER}/lookup`
      ? Promise.resolve({ data: ORDER })
      : Promise.reject(new Error(`unexpected ${url}`))
  )
  localStorage.clear()
  sessionStorage.clear()
})

describe("MAR-14: the email prompt has a page title and a labelled field", () => {
  it("is the page's <h1>, 'Track your order'", async () => {
    renderOrderPage()
    const heading = await screen.findByRole("heading", { level: 1, name: "Track your order" })
    expect(heading).toBeInTheDocument()
    expect(screen.queryByRole("heading", { level: 2, name: "Track your order" })).toBeNull()
  })

  it("the email field is named by a visible label, not only by its placeholder", async () => {
    renderOrderPage()
    const field = await screen.findByLabelText("Email used for this order")
    expect(field).toHaveAttribute("type", "email")
    expect(field).toHaveAttribute("autocomplete", "email")
  })

  it("CONTROL: with a remembered email the prompt is not shown (the page has its own heading)", async () => {
    localStorage.setItem(`jtoye-checkout-email-${SLUG}`, EMAIL)
    renderOrderPage()
    await screen.findByText("Mama Ade's Kitchen")
    expect(screen.queryByLabelText("Email used for this order")).toBeNull()
  })
})

describe("MAR-08: 'Copied!' is announced", () => {
  beforeEach(() => {
    localStorage.setItem(`jtoye-checkout-email-${SLUG}`, EMAIL)
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: { writeText: jest.fn(() => Promise.resolve()) },
    })
  })

  /** The polite live regions that are NOT the page's one role=status region. */
  function copyRegion(): HTMLElement {
    const regions = Array.from(document.querySelectorAll('[aria-live="polite"]')).filter(
      (el) => el.getAttribute("role") !== "status"
    )
    expect(regions).toHaveLength(1)
    return regions[0] as HTMLElement
  }

  it("a polite live region exists, empty, before the press, and says the number was copied after it", async () => {
    renderOrderPage()
    await screen.findByText("Mama Ade's Kitchen")
    const region = copyRegion()
    expect(region).toHaveTextContent(/^$/)

    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Copy order number" }))
    })
    // The SAME node, now filled: that is what makes it announced.
    expect(copyRegion()).toBe(region)
    expect(region).toHaveTextContent("Order number copied")
  })

  it("the visible 'Copied!' is hidden from the accessibility tree so it is not read twice", async () => {
    renderOrderPage()
    await screen.findByText("Mama Ade's Kitchen")
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Copy order number" }))
    })
    const visible = screen.getByText("Copied!")
    expect(visible.closest('[aria-hidden="true"]')).not.toBeNull()
  })

  it("the page still has exactly one role=status region", async () => {
    renderOrderPage()
    await screen.findByText("Mama Ade's Kitchen")
    expect(screen.getAllByRole("status")).toHaveLength(1)
  })
})
